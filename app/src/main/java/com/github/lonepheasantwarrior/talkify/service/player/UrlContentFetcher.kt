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
 * 返回纯文本，供 TTS 引擎直接朗读。
 */
object UrlContentFetcher {

    private const val TAG = "UrlContentFetcher"

    /** 单次请求超时（秒） */
    private const val TIMEOUT_SEC = 15L

    /** 单篇文章最大字符数（防止超长文本塞满内存） */
    private const val MAX_CHARS = 8_000

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * 同步抓取并解析 URL 对应页面的文本正文。
     * 应在 IO 协程中调用。
     *
     * @return 成功时返回正文文字，失败返回 null。
     */
    fun fetch(url: String): FetchResult {
        TtsLogger.i(TAG) { "开始抓取: $url" }
        return try {
            val html = downloadHtml(url) ?: return FetchResult.Error("HTTP 请求失败")
            val doc = Jsoup.parse(html, url)
            val title = doc.title().takeIf { it.isNotBlank() } ?: "网页内容"

            val text = when {
                isWeixinArticle(url) -> extractWeixin(doc)
                else -> extractGeneric(doc)
            }

            if (text.isNullOrBlank()) {
                TtsLogger.w(TAG) { "正文提取结果为空: $url" }
                FetchResult.Error("未能从该页面提取到有效正文，可能需要登录或页面为动态渲染")
            } else {
                val truncated = text.take(MAX_CHARS)
                TtsLogger.i(TAG) { "抓取成功: \"$title\"，长度 ${truncated.length} 字" }
                FetchResult.Success(title = title, content = truncated)
            }
        } catch (e: Exception) {
            TtsLogger.e("抓取失败: $url", e, TAG)
            FetchResult.Error("网络错误: ${e.message}")
        }
    }

    // ────────────────────────────────────────────────────────
    // 内部方法
    // ────────────────────────────────────────────────────────

    private fun downloadHtml(url: String): String? {
        val request = Request.Builder()
            .url(url)
            // 模拟常规移动端 UA，部分站点会对空 UA 拒绝访问
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                TtsLogger.w(TAG) { "HTTP ${response.code}: $url" }
                null
            } else {
                response.body?.string()
            }
        }
    }

    private fun isWeixinArticle(url: String): Boolean {
        val host = try { java.net.URL(url).host } catch (_: Exception) { "" }
        return host.contains("weixin.qq.com") || host.contains("mp.weixin.qq.com")
    }

    /** 微信公众号文章正文提取（服务端渲染，内容在 #js_content） */
    private fun extractWeixin(doc: Document): String? {
        // 主正文容器
        val contentEl = doc.getElementById("js_content")
            ?: doc.selectFirst(".rich_media_content")

        return contentEl?.let { el ->
            // 移除"查看原文"、广告等干扰节点
            el.select("script, style, .qr_code_pc, .weapp_display_element").remove()
            cleanText(el.text())
        }
    }

    /** 通用网页正文提取（优先级：<article> > <main> > 常见 class） */
    private fun extractGeneric(doc: Document): String? {
        // 移除页头、页脚、导航、侧栏、广告等噪音节点
        doc.select("header, footer, nav, aside, script, style, .ad, .advertisement, .sidebar").remove()

        val candidates = listOf(
            doc.selectFirst("article"),
            doc.selectFirst("main"),
            doc.selectFirst("[class*=article-content]"),
            doc.selectFirst("[class*=post-content]"),
            doc.selectFirst("[class*=entry-content]"),
            doc.selectFirst("[class*=content-body]"),
            doc.selectFirst("[id*=article]"),
            doc.selectFirst("[id*=content]"),
            doc.body()
        )

        val text = candidates
            .firstOrNull { it != null && it.text().length > 100 }
            ?.text()

        return text?.let { cleanText(it) }
    }

    /**
     * 清理文本：
     * - 合并连续空行为单个换行
     * - 去除首尾空白
     */
    private fun cleanText(raw: String): String {
        return raw
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
    }

    // ────────────────────────────────────────────────────────
    // 结果类型
    // ────────────────────────────────────────────────────────

    sealed class FetchResult {
        data class Success(val title: String, val content: String) : FetchResult()
        data class Error(val message: String) : FetchResult()
    }
}
