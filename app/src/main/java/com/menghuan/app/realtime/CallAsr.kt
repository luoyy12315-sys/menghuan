package com.menghuan.app.realtime

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * 通话用流式语音识别（sherpa-onnx 中文流式 zipformer，APK 内置模型复用
 * `asr/` 目录四件套——与按住说话的 [com.menghuan.app.data.SherpaAsr] 同一套模型，
 * 但**独立实例、独立生命周期**：通话结束 release，不与语音消息链路共享任何状态，
 * 满足任务书 §八"两套系统互不破坏"）。
 *
 * 工作方式：麦克风采样持续 feed → 边说边出字（partial 字幕）；
 * VAD 判定一句话结束后 [finalizeUtterance] 收口文本并 reset 流，
 * 同一个 stream 连续服务整通电话。
 */
class CallAsr(assets: AssetManager) {

    private val recognizer = OnlineRecognizer(
        assets,
        OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$ASSET_DIR/$ENCODER",
                    decoder = "$ASSET_DIR/$DECODER",
                    joiner = "$ASSET_DIR/$JOINER",
                ),
                tokens = "$ASSET_DIR/$TOKENS",
                numThreads = 2,
            ),
            decodingMethod = "greedy_search",
        ),
    )

    private var stream = recognizer.createStream()

    /** 喂一个 16kHz 采样块并尽量解码（官方示例：喂完即清空 ready 队列） */
    fun feed(chunk: FloatArray) {
        stream.acceptWaveform(chunk, CallVad.IN_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
    }

    /** 当前流式识别的 partial 文本（实时字幕） */
    fun partial(): String = runCatching {
        recognizer.getResult(stream).text.trim()
    }.getOrDefault("")

    /** 一句话结束：收口文本并重置识别状态，准备下一句 */
    fun finalizeUtterance(): String {
        val text = partial()
        runCatching { recognizer.reset(stream) }
        return text
    }

    fun release() {
        runCatching { stream.release() }
        runCatching { recognizer.release() }
    }

    private companion object {
        const val ASSET_DIR = "asr"
        const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
        const val DECODER = "decoder-epoch-99-avg-1.int8.onnx"
        const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
        const val TOKENS = "tokens.txt"
    }
}
