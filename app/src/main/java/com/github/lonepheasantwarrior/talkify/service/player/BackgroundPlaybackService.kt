package com.github.lonepheasantwarrior.talkify.service.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.github.lonepheasantwarrior.talkify.MainActivity
import com.github.lonepheasantwarrior.talkify.R
import com.github.lonepheasantwarrior.talkify.domain.model.BaseEngineConfig
import com.github.lonepheasantwarrior.talkify.domain.playlist.PlaylistItem
import com.github.lonepheasantwarrior.talkify.domain.playlist.PlaylistItemStatus
import com.github.lonepheasantwarrior.talkify.domain.playlist.PlaylistManager
import com.github.lonepheasantwarrior.talkify.service.TalkifyTtsDemoService
import com.github.lonepheasantwarrior.talkify.service.TtsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BackgroundPlaybackService : Service() {

    companion object {
        const val ACTION_PLAY = "com.github.lonepheasantwarrior.talkify.ACTION_PLAY"
        const val ACTION_PAUSE = "com.github.lonepheasantwarrior.talkify.ACTION_PAUSE"
        const val ACTION_NEXT = "com.github.lonepheasantwarrior.talkify.ACTION_NEXT"
        const val ACTION_STOP = "com.github.lonepheasantwarrior.talkify.ACTION_STOP"
        
        const val EXTRA_ENGINE_ID = "engine_id"
        
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "playback_channel"
        private const val TAG = "BackgroundPlaybackService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var demoService: TalkifyTtsDemoService? = null
    private var currentEngineId: String? = null
    private var currentConfig: BaseEngineConfig? = null
    private var currentPlayingItem: PlaylistItem? = null
    private var playlistObserverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        TtsLogger.i(TAG) { "Service Created" }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("准备播放"))
        
        // Obverve playlist changes if we were paused
        observePlaylist()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY -> {
                    // Update configuration if passed
                    val engineId = intent.getStringExtra(EXTRA_ENGINE_ID)
                    if (engineId != null && engineId != currentEngineId) {
                        currentEngineId = engineId
                        initializeDemoService(engineId)
                    }
                    // For the MVP, we assume Config is serialized or injected, 
                    // but we can fetch it dynamically via repository in a real case.
                    // For now, Play next idle item
                    playNextItem()
                }
                ACTION_PAUSE, ACTION_STOP -> {
                    stopPlayback()
                }
                ACTION_NEXT -> {
                    skipToNext()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun initializeDemoService(engineId: String) {
        demoService?.release()
        demoService = TalkifyTtsDemoService(engineId).apply {
            setStateListener { state, errorMessage ->
                when (state) {
                    TalkifyTtsDemoService.STATE_STOPPED -> {
                        // Current item finished
                        currentPlayingItem?.let { item ->
                            if (item.status != PlaylistItemStatus.ERROR) {
                                PlaylistManager.updateItemStatus(item.id, PlaylistItemStatus.COMPLETED)
                            }
                        }
                        // Trigger next
                        playNextItem()
                    }
                    TalkifyTtsDemoService.STATE_ERROR -> {
                        currentPlayingItem?.let { item ->
                            PlaylistManager.updateItemStatus(item.id, PlaylistItemStatus.ERROR)
                        }
                        updateNotification("播放出错: $errorMessage")
                        // Wait or trigger next depending on strategy. We'll pause for now.
                    }
                    TalkifyTtsDemoService.STATE_PLAYING -> {
                        currentPlayingItem?.let { item ->
                            PlaylistManager.updateItemStatus(item.id, PlaylistItemStatus.PLAYING)
                            updateNotification("正在播放: ${item.content.take(20)}...")
                        }
                    }
                }
            }
        }
    }

    private fun observePlaylist() {
        playlistObserverJob?.cancel()
        playlistObserverJob = serviceScope.launch {
            PlaylistManager.playlist.collectLatest { playlist ->
                // Basic check: if nothing is playing but we have idle items, start.
                // In actual deployment, user explicit 'play' triggers it.
                if (demoService?.getState() != TalkifyTtsDemoService.STATE_PLAYING && currentPlayingItem == null) {
                    // We shouldn't auto play just by observing in this MVP to prevent unwanted background start
                }
            }
        }
    }

    private fun playNextItem() {
        val nextItem = PlaylistManager.getNextItemAfter(currentPlayingItem?.id ?: "")
            ?: PlaylistManager.getNextIdleItem()

        if (nextItem == null) {
            // 列表播放完毕
            currentPlayingItem = null
            stopPlayback()
            return
        }

        currentPlayingItem = nextItem
        PlaylistManager.updateItemStatus(nextItem.id, PlaylistItemStatus.PLAYING)

        val engineId = currentEngineId ?: "com.github.lonepheasantwarrior.talkify.qwen3"
        val configRepo = com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineFactory
            .createConfigRepository(engineId, this)
        val config = configRepo?.getConfig(engineId)

        if (config == null) {
            updateNotification("无法加载引擎配置")
            PlaylistManager.updateItemStatus(nextItem.id, PlaylistItemStatus.ERROR)
            return
        }

        if (nextItem.isUrl) {
            // URL 条目：先在 IO 线程抓取正文，再朗读
            updateNotification("正在解析链接…")
            serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                when (val result = UrlContentFetcher.fetch(nextItem.content)) {
                    is UrlContentFetcher.FetchResult.Success -> {
                        // 同时把标题回写到 PlaylistItem（仅内存，方便 UI 显示）
                        PlaylistManager.updateItemStatus(nextItem.id, PlaylistItemStatus.PLAYING)
                        withMain {
                            updateNotification("正在播放: ${result.title.take(20)}")
                            demoService?.speak(result.content, config)
                        }
                    }
                    is UrlContentFetcher.FetchResult.Error -> {
                        TtsLogger.w(TAG) { "URL 抓取失败: ${result.message}" }
                        withMain {
                            PlaylistManager.updateItemStatus(nextItem.id, PlaylistItemStatus.ERROR)
                            updateNotification("链接解析失败: ${result.message.take(30)}")
                            // 自动跳下一条
                            playNextItem()
                        }
                    }
                }
            }
        } else {
            // 普通文本条目：直接朗读
            updateNotification("正在播放: ${nextItem.content.take(20)}")
            demoService?.speak(nextItem.content, config)
        }
    }

    /** 在主线程执行 lambda（辅助函数） */
    private suspend fun withMain(block: () -> Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
    }

    private fun skipToNext() {
        demoService?.stop() // This will trigger STATE_STOPPED -> playNextItem
    }

    private fun stopPlayback() {
        demoService?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        TtsLogger.i(TAG) { "Service Destroyed" }
        demoService?.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "播放控制",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Add robust stop action intent
        val stopIntent = Intent(this, BackgroundPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TTS 后台朗读")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent)
            .setOngoing(true)
            .build()
    }
}
