package com.menghuan.app.realtime

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * Silero VAD（sherpa-onnx，模型 asr/silero_vad.onnx 约 1.8MB，ModelScope 镜像下载）。
 * 职责：① 用户话语分段（说完停顿 [MIN_SILENCE_S] 判定一句结束）；② 打断检测
 * （AI 播放期间检测到用户开口 → 引擎 barge-in，任务书 §十一）。
 *
 * API 签名经 javap 核对（sherpa-onnx 1.13.8 AAR）：
 * `acceptWaveform(FloatArray)` / `isEmpty()` / `front(): SpeechSegment` / `pop()` /
 * `isSpeechDetected()` / `reset()` / `release()`。
 */
class CallVad(assets: AssetManager) {

    private val vad = Vad(
        assets,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = MODEL_ASSET,
                threshold = 0.5f,
                // 说完停 0.6s 判定一句结束：太短容易把换气切断，太长"抢话感"重
                minSilenceDuration = MIN_SILENCE_S,
                minSpeechDuration = 0.25f,
                windowSize = WINDOW,
                maxSpeechDuration = 15f,
            ),
            sampleRate = IN_RATE,
            numThreads = 1,
        ),
    )

    /** 喂一个 512 采样窗口；返回**刚结束**的话语段（无则 null） */
    fun accept(chunk: FloatArray): SpeechSegment? {
        vad.acceptWaveform(chunk)
        return if (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            seg
        } else null
    }

    /** 当前是否检测到语音活动（AI 播放期间据此触发打断） */
    val speechDetected: Boolean
        get() = vad.isSpeechDetected()

    /** 用户被静音/打断后重置内部状态，避免残留半截语音段 */
    fun reset() = vad.reset()

    fun release() = vad.release()

    companion object {
        const val MODEL_ASSET = "asr/silero_vad.onnx"
        const val WINDOW = 512 // 32ms @16kHz
        const val MIN_SILENCE_S = 0.6f
        const val IN_RATE = 16_000
    }
}
