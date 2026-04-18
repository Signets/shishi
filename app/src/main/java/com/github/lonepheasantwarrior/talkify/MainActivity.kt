package com.github.lonepheasantwarrior.talkify

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.lonepheasantwarrior.talkify.domain.playlist.PlaylistItem
import com.github.lonepheasantwarrior.talkify.domain.playlist.PlaylistManager
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.service.player.BackgroundPlaybackService
import com.github.lonepheasantwarrior.talkify.ui.screens.AboutScreen
import com.github.lonepheasantwarrior.talkify.ui.screens.MainScreen
import com.github.lonepheasantwarrior.talkify.ui.theme.TalkifyTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TalkifyMain"

        /** 判断字符串是否为 HTTP/HTTPS URL */
        fun isUrl(text: String): Boolean {
            val t = text.trim()
            return t.startsWith("http://") || t.startsWith("https://")
        }
    }

    // ──────────────────────────────────────────────────────────
    // 剪贴板弹窗状态：用原生 MutableState 持有，供 Compose 读取
    // ──────────────────────────────────────────────────────────
    private val clipboardDialogText = mutableStateOf("")
    private val clipboardDialogIsUrl = mutableStateOf(false)
    private val showClipboardDialog = mutableStateOf(false)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TtsLogger.i(TAG) { "MainActivity.onCreate: 应用启动" }

        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        enableEdgeToEdge()

        setContent {
            TalkifyTheme {
                val versionName = remember {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
                }
                val navController = rememberNavController()

                // 读取 Compose 层面的状态（与 Activity 层共享同一个 MutableState 对象）
                val showDialog by showClipboardDialog
                val clipText by clipboardDialogText
                val clipIsUrl by clipboardDialogIsUrl

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavHost(navController = navController, startDestination = "main") {
                        composable("main") {
                            MainScreen(
                                modifier = Modifier.fillMaxSize(),
                                onAboutClick = {
                                    getSharedPreferences("talkify_app_config", MODE_PRIVATE)
                                        .edit()
                                        .putBoolean("has_opened_about_page", true)
                                        .apply()
                                    navController.navigate("about")
                                }
                            )
                        }
                        composable("about") {
                            AboutScreen(
                                onBackClick = { navController.popBackStack() },
                                versionName = versionName
                            )
                        }
                    }

                    // ──────────────── 剪贴板弹窗 ────────────────
                    if (showDialog) {
                        val previewText = if (clipIsUrl) clipText
                        else "\"${clipText.take(60)}${if (clipText.length > 60) "…" else ""}\""

                        AlertDialog(
                            onDismissRequest = { showClipboardDialog.value = false },
                            title = { Text(if (clipIsUrl) "发现复制的链接" else "发现复制的内容") },
                            text = {
                                Text(
                                    if (clipIsUrl)
                                        "检测到您复制了一个网址，是否加入朗读列表？\n（将朗读网页正文）\n\n$previewText"
                                    else
                                        "检测到您复制了一段文本，是否加入朗读列表？\n\n$previewText"
                                )
                            },
                            confirmButton = {
                                // 【立即朗读】：加到列表 → 启动服务播放
                                TextButton(onClick = {
                                    recordAndAdd(clipText, clipIsUrl)
                                    showClipboardDialog.value = false
                                    startPlaybackService()
                                }) { Text("立即朗读") }
                            },
                            dismissButton = {
                                // 【稍后朗读】：只加列表，不触发播放
                                TextButton(onClick = {
                                    recordAndAdd(clipText, clipIsUrl)
                                    showClipboardDialog.value = false
                                }) { Text("稍后朗读") }
                            }
                        )
                    }
                }
            }
        }
    }

    /**
     * 窗口真正获得焦点时才读剪贴板。
     *
     * 这是比 ON_RESUME 更可靠的时机：
     * - ON_RESUME 触发时窗口可能还未获得焦点，Android 10+ 此时读剪贴板可能拿到空值
     * - onWindowFocusChanged(true) 确保 Activity 窗口已完全显示并可交互
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        TtsLogger.d(TAG) { "onWindowFocusChanged: 窗口获得焦点，读取剪贴板" }
        tryReadClipboard()
    }

    // ────────────────────────────────────────────────────────
    // 私有方法
    // ────────────────────────────────────────────────────────

    /** 读取剪贴板并决定是否弹窗 */
    private fun tryReadClipboard() {
        val clipPrefs = getSharedPreferences("shishi_clip_state", MODE_PRIVATE)

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return

        val text = clip.getItemAt(0).text?.toString()?.trim()
            ?.takeIf { it.length > 5 }   // 少于 5 字的内容忽略
            ?: return

        // 与上次已处理的内容相同 → 不重复弹出
        val lastSeen = clipPrefs.getString("last_clip", "") ?: ""
        if (text == lastSeen) {
            TtsLogger.d(TAG) { "剪贴板内容与上次相同，跳过弹窗" }
            return
        }

        TtsLogger.i(TAG) { "检测到新剪贴板内容，准备弹窗" }
        clipboardDialogText.value = text
        clipboardDialogIsUrl.value = isUrl(text)
        showClipboardDialog.value = true
    }

    /** 记录已处理 + 加入播放列表 */
    private fun recordAndAdd(text: String, isUrlItem: Boolean) {
        getSharedPreferences("shishi_clip_state", MODE_PRIVATE)
            .edit().putString("last_clip", text).apply()
        val item = PlaylistItem(
            content = text,
            isUrl = isUrlItem,
            title = if (isUrlItem) "网页朗读" else text.take(20)
        )
        PlaylistManager.addItem(item)
    }

    /** 启动后台朗读前台服务 */
    private fun startPlaybackService() {
        val intent = Intent(this, BackgroundPlaybackService::class.java).apply {
            action = BackgroundPlaybackService.ACTION_PLAY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
