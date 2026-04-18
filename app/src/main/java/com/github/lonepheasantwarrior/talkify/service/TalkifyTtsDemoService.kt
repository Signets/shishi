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
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentEngine: TtsEngineApi? = null

    @Volatile
    private var audioPlayer: TalkifyAudioPlayer? = null

    /** 当前播放倍速 */
    @Volatile
    private var currentSpeed: Float = 1.0f

    /** 用户是否手动暂停了播放 */
    @Volatile
    private var isPausedByUser = false

    /** 合成是否在暂停期间完成 */
    @Volatile
    private var synthesisCompletedWhilePaused = false

    @Volatile
    private var isStopped = AtomicBoolean(false)

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
        isPausedByUser = false
        synthesisCompletedWhilePaused = false
        if (currentState == STATE_PLAYING) {
            stop()
        }

        isStopped.set(false)
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
                if (isPausedByUser) {
                    // 暂停期间合成完成，延迟到恢复后再处理
                    synthesisCompletedWhilePaused = true
                    return
                }
                stopPlayback(isNaturalCompletion = true)
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
            isPausedByUser = true
            audioPlayer?.pause()
            // 不改变 currentState，由外部（Service）管理状态
        }
    }

    /** 恢复播放 */
    fun resume() {
        TtsLogger.d("Resuming playback")
        isPausedByUser = false
        audioPlayer?.resume()
        if (currentSpeed != 1.0f) {
            audioPlayer?.setPlaybackSpeed(currentSpeed)
        }
        // 如果合成在暂停期间已完成，等待音频播放完毕后触发完成回调
        if (synthesisCompletedWhilePaused) {
            synthesisCompletedWhilePaused = false
            serviceScope.launch(Dispatchers.IO) {
                audioPlayer?.waitForPlaybackComplete(timeoutSeconds = 300) { isStopped.get() }
                if (!isStopped.get() && !isPausedByUser) {
                    stopPlayback(isNaturalCompletion = true)
                }
            }
        }
    }

    fun stop() {
        TtsLogger.d("Stopping playback")
        isPausedByUser = false
        synthesisCompletedWhilePaused = false
        isStopped.set(true)
        audioPlayer?.stop()
        stopPlayback()
    }

    /**
     * 停止播放并释放资源。
     *
     * @param isNaturalCompletion 是否为自然播放完毕（true → STATE_STOPPED，触发自动下一条）。
     *   手动停止或切换条目时传 false（默认）→ STATE_IDLE，不触发自动下一条。
     */
    private fun stopPlayback(isNaturalCompletion: Boolean = false) {
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
                currentState = when {
                    lastErrorMessage != null -> STATE_ERROR
                    isNaturalCompletion     -> STATE_STOPPED
                    else                    -> STATE_IDLE
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
        stop()
        try {
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
