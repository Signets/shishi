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
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
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
        const val ACTION_PLAY        = "io.shishi.reader.ACTION_PLAY"
        const val ACTION_PLAY_ITEM   = "io.shishi.reader.ACTION_PLAY_ITEM"
        const val ACTION_PAUSE       = "io.shishi.reader.ACTION_PAUSE"
        const val ACTION_RESUME      = "io.shishi.reader.ACTION_RESUME"
        const val ACTION_PREV        = "io.shishi.reader.ACTION_PREV"
        const val ACTION_NEXT        = "io.shishi.reader.ACTION_NEXT"
        const val ACTION_STOP        = "io.shishi.reader.ACTION_STOP"

        const val EXTRA_ENGINE_ID = "engine_id"
        const val EXTRA_ITEM_ID   = "item_id"
        const val EXTRA_SPEED     = "speed"

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
    private var currentSpeed: Float = 1.0f

    private var mediaSession: MediaSessionCompat? = null

    override fun onCreate() {
        super.onCreate()
        TtsLogger.i(TAG) { "Service Created" }
        createNotificationChannel()
        setupMediaSession()
        startForeground(NOTIFICATION_ID, createNotification("准备播放", false))
        
        observePlaylist()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resumePlayback()
                }

                override fun onPause() {
                    pausePlayback()
                }

                override fun onSkipToNext() {
                    skipToNext()
                }

                override fun onSkipToPrevious() {
                    skipToPrevious()
                }
                
                override fun onStop() {
                    stopPlayback()
                }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY -> {
                    val engineId = intent.getStringExtra(EXTRA_ENGINE_ID)
                    if (engineId != null && engineId != currentEngineId) {
                        currentEngineId = engineId
                        initializeDemoService(engineId)
                    }
                    val speed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
                    currentSpeed = speed
                    playNextItem()
                }
                ACTION_PLAY_ITEM -> {
                    val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return START_NOT_STICKY
                    val engineId = intent.getStringExtra(EXTRA_ENGINE_ID)
                    if (engineId != null && engineId != currentEngineId) {
                        currentEngineId = engineId
                        initializeDemoService(engineId)
                    }
                    val speed = intent.getFloatExtra(EXTRA_SPEED, currentSpeed)
                    currentSpeed = speed
                    
                    demoService?.stop()
                    val target = PlaylistManager.playlist.value.find { it.id == itemId }
                    if (target != null) {
                        PlaylistManager.markAllAs(PlaylistItemStatus.IDLE)
                        currentPlayingItem = null
                        val list = PlaylistManager.playlist.value
                        val idx = list.indexOfFirst { it.id == itemId }
                        list.take(idx).forEach { PlaylistManager.updateItemStatus(it.id, PlaylistItemStatus.COMPLETED) }
                        playNextItem()
                    }
                }
                ACTION_PAUSE -> {
                    pausePlayback()
                }
                ACTION_RESUME -> {
                    resumePlayback()
                }
                ACTION_PREV -> {
                    skipToPrevious()
                }
                ACTION_NEXT -> {
                    skipToNext()
                }
                ACTION_STOP -> {
                    stopPlayback()
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
                        currentPlayingItem?.let { item ->
                            if (item.status != PlaylistItemStatus.ERROR) {
                                PlaylistManager.updateItemStatus(item.id, PlaylistItemStatus.COMPLETED)
                            }
                        }
                        playNextItem()
                    }
                    TalkifyTtsDemoService.STATE_ERROR -> {
                        currentPlayingItem?.let { item ->
                            PlaylistManager.updateItemStatus(item.id, PlaylistItemStatus.ERROR)
                        }
                        updateNotification("播放出错: $errorMessage", false)
                        updateMediaSessionState(PlaybackStateCompat.STATE_ERROR)
                    }
                    TalkifyTtsDemoService.STATE_PLAYING -> {
                        currentPlayingItem?.let { item ->
                            PlaylistManager.updateItemStatus(item.id, PlaylistItemStatus.PLAYING)
                            updateNotification("正在播放: ${item.content.take(20)}...", true)
                            updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING)
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
                
            }
        }
    }

    private fun playNextItem() {
        val nextItem = PlaylistManager.getNextItemAfter(currentPlayingItem?.id ?: "")
            ?: PlaylistManager.getNextIdleItem()

        if (nextItem == null) {
            currentPlayingItem = null
            stopPlayback()
            return
        }

        currentPlayingItem = nextItem
        PlaylistManager.updateItemStatus(nextItem.id, PlaylistItemStatus.PLAYING)
        updateMediaMetadata(nextItem)

        val engineId = currentEngineId ?: "com.github.lonepheasantwarrior.talkify.qwen3"
        val configRepo = com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineFactory
            .createConfigRepository(engineId, this)
        val config = configRepo?.getConfig(engineId)

        if (config == null) {
            updateNotification("无法加载引擎配置", false)
            PlaylistManager.updateItemStatus(nextItem.id, PlaylistItemStatus.ERROR)
            updateMediaSessionState(PlaybackStateCompat.STATE_ERROR)
            return
        }

        if (nextItem.isUrl) {
            updateNotification("正在解析链接…", true)
            updateMediaSessionState(PlaybackStateCompat.STATE_BUFFERING)
            serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                when (val result = UrlContentFetcher.fetch(nextItem.content)) {
                    is UrlContentFetcher.FetchResult.Success -> {
                        PlaylistManager.updateItemStatus(nextItem.id, PlaylistItemStatus.PLAYING)
                        withMain {
                            updateNotification("正在播放: ${result.title.take(20)}", true)
                            val updatedItem = nextItem.copy(title = result.title)
                            updateMediaMetadata(updatedItem)
                            updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING)
                            demoService?.speak(result.content, config, speed = currentSpeed)
                        }
                    }
                    is UrlContentFetcher.FetchResult.Error -> {
                        TtsLogger.w(TAG) { "URL 抓取失败: ${result.message}" }
                        withMain {
                            PlaylistManager.updateItemStatus(nextItem.id, PlaylistItemStatus.ERROR)
                            updateNotification("链接解析失败: ${result.message.take(30)}", false)
                            playNextItem()
                        }
                    }
                }
            }
        } else {
            updateNotification("正在播放: ${nextItem.content.take(20)}", true)
            updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING)
            demoService?.speak(nextItem.content, config, speed = currentSpeed)
        }
    }

    private suspend fun withMain(block: () -> Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { block() }
    }

    private fun skipToPrevious() {
        val currentId = currentPlayingItem?.id ?: return
        val prevItem = PlaylistManager.getPreviousItemBefore(currentId)
        
        demoService?.stop()
        
        if (prevItem != null) {
            PlaylistManager.markAllAs(PlaylistItemStatus.IDLE)
            currentPlayingItem = null
            val list = PlaylistManager.playlist.value
            val idx = list.indexOfFirst { it.id == prevItem.id }
            list.take(idx).forEach { PlaylistManager.updateItemStatus(it.id, PlaylistItemStatus.COMPLETED) }
            playNextItem()
        } else {
            PlaylistManager.updateItemStatus(currentId, PlaylistItemStatus.IDLE)
            currentPlayingItem = null
            playNextItem()
        }
    }

    private fun skipToNext() {
        demoService?.stop()
    }

    private fun pausePlayback() {
        if (demoService?.getState() == TalkifyTtsDemoService.STATE_PLAYING) {
            demoService?.pause()
            currentPlayingItem?.let { item ->
                PlaylistManager.updateItemStatus(item.id, PlaylistItemStatus.PAUSED)
            }
            updateNotification("已暂停", false)
            updateMediaSessionState(PlaybackStateCompat.STATE_PAUSED)
        }
    }

    private fun resumePlayback() {
        if (demoService?.getState() == TalkifyTtsDemoService.STATE_PAUSED) {
            demoService?.resume()
            currentPlayingItem?.let { item ->
                PlaylistManager.updateItemStatus(item.id, PlaylistItemStatus.PLAYING)
            }
            updateNotification("正在播放: ${currentPlayingItem?.content?.take(20) ?: ""}", true)
            updateMediaSessionState(PlaybackStateCompat.STATE_PLAYING)
        }
    }

    private fun stopPlayback() {
        demoService?.stop()
        updateMediaSessionState(PlaybackStateCompat.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        TtsLogger.i(TAG) { "Service Destroyed" }
        demoService?.release()
        serviceScope.cancel()
        mediaSession?.isActive = false
        mediaSession?.release()
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

    private fun getPendingIntent(actionStr: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, BackgroundPlaybackService::class.java).apply {
            action = actionStr
        }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun updateNotification(text: String, isPlaying: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(text, isPlaying))
    }

    private fun createNotification(content: String, isPlaying: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val prevPendingIntent = getPendingIntent(ACTION_PREV, 1)
        val pausePlayIntent = if (isPlaying) getPendingIntent(ACTION_PAUSE, 2) else getPendingIntent(ACTION_RESUME, 3)
        val nextPendingIntent = getPendingIntent(ACTION_NEXT, 4)
        val stopPendingIntent = getPendingIntent(ACTION_STOP, 5)

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "暂停" else "播放"

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)
            
        mediaSession?.let {
            mediaStyle.setMediaSession(it.sessionToken)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TTS 后台朗读")
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .addAction(android.R.drawable.ic_media_previous, "上一首", prevPendingIntent)
            .addAction(playPauseIcon, playPauseTitle, pausePlayIntent)
            .addAction(android.R.drawable.ic_media_next, "下一首", nextPendingIntent)
            .setStyle(mediaStyle)
            .setOngoing(isPlaying)
            .build()
    }
    
    private fun updateMediaSessionState(state: Int) {
        val playbackStateBuilder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            
        mediaSession?.setPlaybackState(playbackStateBuilder.build())
    }
    
    private fun updateMediaMetadata(item: PlaylistItem) {
        val title = if (!item.title.isNullOrBlank() && item.isUrl) item.title else item.content.take(40)
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Talkify TTS")
            
        mediaSession?.setMetadata(metadataBuilder.build())
    }
}
