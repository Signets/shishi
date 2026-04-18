package com.github.lonepheasantwarrior.talkify.service.player

import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * 网页正文抓取器
 *
 * 支持以下场景：
 *  1. 微信公众号文章（mp.weixin.qq.com）—— 读取 id="js_content" 节点
 *  2. 通用文章页面 —— 提取 <article>、<main>、<div.content> 等常见正文容器
 *
 * 微信防盗链说明：
 *  - 微信会检查 Referer、Cookie 等字段，纯 HTTP 请求大概率被拒或返回无正文页面
 *  - 本实现尽量模拟移动端 Chrome 请求头提高成功率
 *  - 如果仍返回空，通常是文章设置了"仅粉丝可见"或微信临时风控，非代码问题
 */
object UrlContentFetcher {

    private const val TAG = "UrlContentFetcher"
    private const val TIMEOUT_SEC = 20L
    private const val MAX_CHARS = 8_000

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // 禁用 HTTP/2 帧压缩，兼容部分反爬服务器
            .build()
    }

    fun fetch(url: String): FetchResult {
        TtsLogger.i(TAG) { "开始抓取: $url" }
        return try {
            val html = downloadHtml(url) ?: return FetchResult.Error("HTTP 请求被拒绝（微信等平台需登录或不允许程序访问）")
            if (html.isBlank()) return FetchResult.Error("页面内容为空")

            val doc = Jsoup.parse(html, url)
            val title = doc.title().takeIf { it.isNotBlank() } ?: "网页内容"

            val text = when {
                isWeixinArticle(url) -> extractWeixin(doc)
                else -> extractGeneric(doc)
            }

            if (text.isNullOrBlank()) {
                // 尝试降级：直接取所有 <p> 文本
                val fallback = doc.select("p")
                    .joinToString("\n") { it.text().trim() }
                    .filter { it.isNotBlank() }

                if (fallback.isBlank()) {
                    TtsLogger.w(TAG) { "正文提取为空（含降级尝试）: $url" }
                    FetchResult.Error("未能提取到正文。微信文章可能需要登录或仅粉丝可见。")
                } else {
                    FetchResult.Success(title = title, content = fallback.take(MAX_CHARS))
                }
            } else {
                FetchResult.Success(title = title, content = text.take(MAX_CHARS))
            }
        } catch (e: Exception) {
            TtsLogger.e("抓取失败: $url", e, TAG)
            FetchResult.Error("网络错误: ${e.message?.take(60)}")
        }
    }

    private fun downloadHtml(url: String): String? {
        val isWeixin = isWeixinArticle(url)
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                if (isWeixin)
                // 微信内置浏览器 UA，最接近真实请求
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/124.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.50.2401(0x28003237) " +
                            "NetType/WIFI Language/zh_CN"
                else
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/124.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Accept-Encoding", "identity") // 关闭 gzip，避免解压问题
            .header("Connection", "keep-alive")
            .apply {
                if (isWeixin) {
                    header("Referer", "https://mp.weixin.qq.com/")
                }
            }
            .build()

        return httpClient.newCall(request).execute().use { response ->
            TtsLogger.i(TAG) { "HTTP ${response.code} ${response.message} - $url" }
            if (!response.isSuccessful && response.code != 302) {
                TtsLogger.w(TAG) { "HTTP 异常: ${response.code}" }
                return@use null
            }
            response.body?.string()
        }
    }

    private fun isWeixinArticle(url: String): Boolean {
        return url.contains("weixin.qq.com") || url.contains("mp.weixin.qq.com")
    }

    /** 微信公众号文章正文 */
    private fun extractWeixin(doc: Document): String? {
        val el = doc.getElementById("js_content")
            ?: doc.selectFirst(".rich_media_content")
            ?: doc.selectFirst("[id*=js_content]")

        if (el == null) {
            TtsLogger.w(TAG) { "微信：未找到 #js_content，页面标题：${doc.title()}" }
            return null
        }
        el.select("script, style, .qr_code_pc, .weapp_display_element, .js_editor_qqfaceparse").remove()
        return cleanText(el.text())
    }

    /** 通用网页正文提取 */
    private fun extractGeneric(doc: Document): String? {
        doc.select("header, footer, nav, aside, script, style, .ad, .advertisement, .sidebar, .comment").remove()

        val candidates = listOf(
            doc.selectFirst("article"),
            doc.selectFirst("main"),
            doc.selectFirst("[class~=article-content]"),
            doc.selectFirst("[class~=post-content]"),
            doc.selectFirst("[class~=entry-content]"),
            doc.selectFirst("[class~=content-body]"),
            doc.selectFirst("[id~=article]"),
            doc.selectFirst("[id~=content]"),
            doc.body()
        )

        return candidates
            .firstOrNull { it != null && it.text().length > 100 }
            ?.text()
            ?.let { cleanText(it) }
    }

    private fun cleanText(raw: String): String =
        raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()

    sealed class FetchResult {
        data class Success(val title: String, val content: String) : FetchResult()
        data class Error(val message: String) : FetchResult()
    }
}
