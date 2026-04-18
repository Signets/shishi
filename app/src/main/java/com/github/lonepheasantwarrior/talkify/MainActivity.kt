package com.github.lonepheasantwarrior.talkify

import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import com.github.lonepheasantwarrior.talkify.ui.screens.AboutScreen
import com.github.lonepheasantwarrior.talkify.ui.screens.MainScreen
import com.github.lonepheasantwarrior.talkify.ui.theme.TalkifyTheme

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.github.lonepheasantwarrior.talkify.domain.playlist.PlaylistItem
import com.github.lonepheasantwarrior.talkify.domain.playlist.PlaylistManager
import com.github.lonepheasantwarrior.talkify.service.player.BackgroundPlaybackService

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "TalkifyMain"
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

                    // Clipboard Dialog Logic
                    var showClipboardDialog by remember { mutableStateOf(false) }
                    var clipboardText by remember { mutableStateOf("") }
                    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                if (clipboard.hasPrimaryClip()) {
                                    val clip = clipboard.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val text = clip.getItemAt(0).text?.toString()
                                        // Simple checking to avoid re-adding the same text immediately. 
                                        // In a real app we would track the latest processed text locally.
                                        if (!text.isNullOrBlank() && text.length > 5) {
                                            // Check if it's already in the playlist (extremely simple dedup for MVP)
                                            val exists = PlaylistManager.playlist.value.any { it.content == text }
                                            if (!exists) {
                                                clipboardText = text
                                                showClipboardDialog = true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }

                    if (showClipboardDialog) {
                        AlertDialog(
                            onDismissRequest = { showClipboardDialog = false },
                            title = { Text("发现复制的内容") },
                            text = { Text("检测到您复制了一段文本，是否加入朗读列表？\n\n\"${clipboardText.take(50)}...\"") },
                            confirmButton = {
                                TextButton(onClick = {
                                    // Add to playlist and play
                                    PlaylistManager.addItem(PlaylistItem(content = clipboardText))
                                    showClipboardDialog = false
                                    // Optionally trigger playback
                                    val playIntent = Intent(this@MainActivity, BackgroundPlaybackService::class.java).apply {
                                        action = BackgroundPlaybackService.ACTION_PLAY
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(playIntent)
                                    } else {
                                        startService(playIntent)
                                    }
                                }) {
                                    Text("立即朗读")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    // Just add to playlist
                                    PlaylistManager.addItem(PlaylistItem(content = clipboardText))
                                    showClipboardDialog = false
                                }) {
                                    Text("稍后朗读")
                                }
                            }
                        )
                    }

                }
            }
        }
    }
}
