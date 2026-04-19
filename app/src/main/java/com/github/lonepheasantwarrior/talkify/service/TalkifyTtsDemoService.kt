package com.github.lonepheasantwarrior.talkify.service

import com.github.lonepheasantwarrior.talkify.domain.model.BaseEngineConfig
import com.github.lonepheasantwarrior.talkify.service.engine.SynthesisParams
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineApi
import com.github.lonepheasantwarrior.talkify.service.engine.TtsEngineFactory
import com.github.lonepheasantwarrior.talkify.service.engine.TtsSynthesisListener
import com.github.lonepheasantwarrior.talkify.util.TalkifyAudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class TalkifyTtsDemoService(
    private val engineId: String
) {
    companion object {
        const val STATE_IDLE = 0
        const val STATE_PLAYING = 1
        const val STATE_STOPPED = 2
        const val STATE_ERROR = 3
        const val STATE_PAUSED = 4
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentEngine: TtsEngineApi? = null

    @Volatile
    private var audioPlayer: TalkifyAudioPlayer? = null

    /** 当前播放倍速 */
    @Volatile
    private var currentSpeed: Float = 1.0f

    @Volatile
    private var isStopped = AtomicBoolean(false)

    /** 暂停标志：暂停期间合成完成不触发 stopPlayback() */
    @Volatile
    private var isPaused = AtomicBoolean(false)

    @Volatile
    private var currentState = STATE_IDLE

    @Volatile
    private var lastErrorMessage: String? = null

    private var stateListener: ((Int, String?) -> Unit)? = null

    fun setStateListener(listener: (Int, String?) -> Unit) {
        stateListener = listener
    }

    fun speak(
        text: String,
        config: BaseEngineConfig,
        params: SynthesisParams = SynthesisParams(language = "Auto"),
        speed: Float = 1.0f
    ) {
        currentSpeed = speed
        if (currentState == STATE_PLAYING || currentState == STATE_PAUSED) {
            stop()
        }

        isStopped.set(false)
        isPaused.set(false)
        currentState = STATE_IDLE
        lastErrorMessage = null
        notifyStateChange()

        var engine = currentEngine
        if (engine == null) {
            engine = TtsEngineFactory.createEngine(engineId)
            if (engine == null) {
                TtsLogger.e("Failed to create engine: $engineId")
                onError("无法创建引擎：$engineId")
                return
            }
            currentEngine = engine
        }

        currentState = STATE_PLAYING
        notifyStateChange()

        serviceScope.launch {
            try {
                engine.synthesize(text, params, config, createListener())
            } catch (e: Exception) {
                TtsLogger.e("Synthesis failed: ${e.message}", e)
                onError("合成失败：${e.message}")
            }
        }
    }

    private fun createListener(): TtsSynthesisListener {
        return object : TtsSynthesisListener {
            override fun onSynthesisStarted() {
                TtsLogger.d("Synthesis started")
            }

            override fun onAudioAvailable(
                audioData: ByteArray,
                sampleRate: Int,
                audioFormat: Int,
                channelCount: Int
            ) {
                if (isStopped.get()) {
                    TtsLogger.d("Audio skipped due to stop")
                    return
                }

                try {
                    if (audioPlayer == null) {
                        audioPlayer = TalkifyAudioPlayer(
                            sampleRate = sampleRate,
                            channelCount = channelCount,
                            audioFormat = audioFormat
                        )
                        audioPlayer?.setErrorListener { errorMessage ->
                            TtsLogger.e("Audio player error: $errorMessage")
                            lastErrorMessage = errorMessage
                            stopPlayback()
                        }
                        val created = audioPlayer?.createPlayer()
                        if (created != true) {
                            throw IllegalStateException("Failed to create audio player")
                        }
                    }
                    audioPlayer?.play(audioData)
                    // 首次写入后设置倍速
                    if (currentSpeed != 1.0f) {
                        audioPlayer?.setPlaybackSpeed(currentSpeed)
                    }
                } catch (e: Exception) {
                    TtsLogger.e("Audio playback error: ${e.message}", e)
                }
            }

            override fun onSynthesisCompleted() {
                TtsLogger.d("Synthesis completed")
                if (isPaused.get()) {
                    // 暂停期间合成完成：音频已写入缓冲区，等待音频播完后再结束
                    // 不立即调用 stopPlayback()，避免覆盖 PAUSED 状态
                    TtsLogger.d("Synthesis completed while paused, waiting for audio drain")
                    return
                }
                stopPlayback()
            }

            override fun onError(error: String) {
                TtsLogger.e("Synthesis error: $error")
                val errorCode = TtsErrorCode.inferErrorCodeFromMessage(error)
                lastErrorMessage = TtsErrorCode.getErrorMessage(errorCode, error)
                stopPlayback()
            }
        }
    }

    /** 暂停播放（不释放资源，允许恢复） */
    fun pause() {
        if (currentState == STATE_PLAYING) {
            TtsLogger.d("Pausing playback")
            isPaused.set(true)
            audioPlayer?.pause()
            currentState = STATE_PAUSED
            notifyStateChange()
        }
    }

    /** 恢复播放 */
    fun resume() {
        if (currentState == STATE_PAUSED) {
            TtsLogger.d("Resuming playback")
            isPaused.set(false)
            audioPlayer?.resume()
            currentState = STATE_PLAYING
            notifyStateChange()
            if (currentSpeed != 1.0f) {
                audioPlayer?.setPlaybackSpeed(currentSpeed)
            }
        }
    }

    fun stop() {
        TtsLogger.d("Stopping playback")
        isStopped.set(true)
        isPaused.set(false)
        audioPlayer?.stop()
        stopPlayback()
    }

    private fun stopPlayback() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                audioPlayer?.stop()
                audioPlayer?.release()
                audioPlayer = null
            } catch (e: Exception) {
                TtsLogger.e("Error stopping audio player: ${e.message}", e)
            }

            try {
                currentEngine?.stop()
            } catch (e: Exception) {
                TtsLogger.e("Error stopping engine: ${e.message}", e)
            }

            if (currentState != STATE_STOPPED) {
                currentState = if (lastErrorMessage != null) {
                    STATE_ERROR
                } else {
                    // 正常完成上报 STATE_STOPPED，触发 BackgroundPlaybackService.playNextItem()
                    STATE_STOPPED
                }
                notifyStateChange()
            }
        }
    }

    private fun onError(message: String) {
        lastErrorMessage = message
        currentState = STATE_ERROR
        notifyStateChange()
    }

    private fun notifyStateChange() {
        stateListener?.invoke(currentState, lastErrorMessage)
    }

    fun release() {
        TtsLogger.d("Releasing service")
        isStopped.set(true)
        isPaused.set(false)
        try {
            audioPlayer?.stop()
            audioPlayer?.release()
            audioPlayer = null
        } catch (e: Exception) {
            TtsLogger.e("Error releasing audio player on release: ${e.message}", e)
        }
        try {
            currentEngine?.stop()
            currentEngine?.release()
        } catch (e: Exception) {
            TtsLogger.e("Error releasing engine: ${e.message}", e)
        }
        currentEngine = null
        serviceScope.cancel()
        currentState = STATE_IDLE
        lastErrorMessage = null
    }

    fun getState(): Int = currentState
}
