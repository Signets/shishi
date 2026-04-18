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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TtsLogger.i(TAG) { "MainActivity.onCreate: 应用启动" }

        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        enableEdgeToEdge()
        setContent {
            TalkifyTheme {
                val versionName = remember { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0" }
                val navController = rememberNavController()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = "main"
                    ) {
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

                    // ──────────────────────────────────────────────────────────
                    // 剪贴板监听弹窗逻辑
                    //
                    // 用 lastSeenClip 记录"上次已弹窗处理"的内容，
                    // 避免同一段内容多次弹出；同时允许历史列表里有相同内容
                    // 的新复制再次触发（用户重新复制即视为想再次朗读）。
                    // ──────────────────────────────────────────────────────────
                    var showClipboardDialog by remember { mutableStateOf(false) }
                    var clipboardText by remember { mutableStateOf("") }
                    var clipboardIsUrl by remember { mutableStateOf(false) }

                    // 持久化记录已处理过的剪贴板文本，防止重启后同一内容再次弹出
                    val clipPrefs = remember {
                        getSharedPreferences("shishi_clip_state", MODE_PRIVATE)
                    }

                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                if (!clipboard.hasPrimaryClip()) return@LifecycleEventObserver
                                val clip = clipboard.primaryClip ?: return@LifecycleEventObserver
                                if (clip.itemCount == 0) return@LifecycleEventObserver

                                val text = clip.getItemAt(0).text?.toString()
                                    ?.trim()
                                    ?.takeIf { it.length > 5 }   // 过短内容忽略
                                    ?: return@LifecycleEventObserver

                                // 与上次弹窗内容相同则不再重复弹出
                                val lastSeen = clipPrefs.getString("last_clip", "")
                                if (text == lastSeen) return@LifecycleEventObserver

                                clipboardText = text
                                clipboardIsUrl = isUrl(text)
                                showClipboardDialog = true
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    if (showClipboardDialog) {
                        val previewText = if (clipboardIsUrl) clipboardText
                                         else "\"${clipboardText.take(60)}${if (clipboardText.length > 60) "…" else ""}\""

                        AlertDialog(
                            onDismissRequest = { showClipboardDialog = false },
                            title = { Text(if (clipboardIsUrl) "发现复制的链接" else "发现复制的内容") },
                            text = {
                                Text(
                                    if (clipboardIsUrl)
                                        "检测到您复制了一个网址，是否加入朗读列表（将朗读网页正文）？\n\n$previewText"
                                    else
                                        "检测到您复制了一段文本，是否加入朗读列表？\n\n$previewText"
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    // 记录已处理，下次不重复弹出
                                    clipPrefs.edit().putString("last_clip", clipboardText).apply()
                                    val item = PlaylistItem(
                                        content = clipboardText,
                                        isUrl = clipboardIsUrl,
                                        title = if (clipboardIsUrl) "网页朗读" else clipboardText.take(20)
                                    )
                                    PlaylistManager.addItem(item)
                                    showClipboardDialog = false
                                    // 立即启动后台朗读服务
                                    val playIntent = Intent(this@MainActivity, BackgroundPlaybackService::class.java).apply {
                                        action = BackgroundPlaybackService.ACTION_PLAY
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(playIntent)
                                    } else {
                                        startService(playIntent)
                                    }
                                }) { Text("立即朗读") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    // 记录已处理
                                    clipPrefs.edit().putString("last_clip", clipboardText).apply()
                                    val item = PlaylistItem(
                                        content = clipboardText,
                                        isUrl = clipboardIsUrl,
                                        title = if (clipboardIsUrl) "网页朗读" else clipboardText.take(20)
                                    )
                                    PlaylistManager.addItem(item)
                                    showClipboardDialog = false
                                }) { Text("稍后朗读") }
                            }
                        )
                    }
                }
            }
        }
    }
}
