package com.menghuan.app.data

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 端侧离线语音识别（v0.50.0 换成 SenseVoice 离线非流式；v0.44.1 起是转写链的唯一一级）。
 *
 * ## 为什么从流式 zipformer-14M 换成离线 SenseVoice
 * 旧模型 1400 万参数、官方定性"准确率不佳"，而且梦欢是**录完再识别**——用流式模型
 * 等于拿错了工具：流式的 chunk 缓冲要补尾部静音才不丢字，长录音内存峰值还高。
 * SenseVoice（int8 228MB）中文 CER 约 8%，一次吃完整段音频，形态与用法对得上。
 * 顺带能出情绪标签与音频事件（`OfflineRecognizerResult.emotion/event`），本期只取 text。
 *
 * ## 为什么语音消息与通话共用这一个实例（不再各建一个 CallAsr）
 * 228MB 权重会被 onnxruntime 读进 session，两个实例 ≈ 500MB，中低端机直接 OOM。
 * 而这两条链路**从不同时工作**（通话中不触发语音条），所以进程内单例 + 互斥解码就够了。
 *
 * ## 模型不随包
 * 权重在 `filesDir/asr_sensevoice/`，由 [AsrModelManager] 下载安装。没下载时
 * [warmUp] / [transcribeWav] 安静返回失败，UI 层据此弹下载入口——不静默、也不崩。
 *
 * ## API 签名经 javap 核对（sherpa-onnx 1.13.8 AAR）
 * `OfflineRecognizer(AssetManager?, OfflineRecognizerConfig)`：第一个参数**可为 null**
 * （字节码只对 config 做了 `checkNotNullParameter`），传 null 即从文件系统路径读模型。
 * `OfflineSenseVoiceModelConfig(model, language, useInverseTextNormalization, qnnConfig)`。
 */
object SherpaAsr {

    private const val TAG = "SherpaAsr"
    private const val SAMPLE_RATE = 16_000

    @Volatile
    private var recognizer: OfflineRecognizer? = null
    private val lock = Any()

    /**
     * 强制加载模型并报告是否可用。通话页在 start() 时调它，好把"模型没下载/加载失败"
     * 在拨号那一刻就亮出来，而不是等用户说完第一句才发现整通电话都识别不了。
     */
    fun warmUp(context: Context): Boolean = obtain(context) != null

    private fun obtain(context: Context): OfflineRecognizer? {
        recognizer?.let { return it }
        if (!AsrModelManager.isReady(context)) {
            Log.w(TAG, "model not ready, skip init")
            return null
        }
        return synchronized(lock) {
            recognizer ?: runCatching {
                OfflineRecognizer(
                    null,
                    OfflineRecognizerConfig(
                        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                        modelConfig = OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(
                                model = AsrModelManager.fileOf(context, AsrModelFile.MODEL).absolutePath,
                                language = "auto",
                                useInverseTextNormalization = true,
                            ),
                            tokens = AsrModelManager.fileOf(context, AsrModelFile.TOKENS).absolutePath,
                            numThreads = 2,
                            debug = false,
                        ),
                        decodingMethod = "greedy_search",
                    ),
                )
            }.onFailure {
                Log.e(TAG, "init recognizer failed: ${it.javaClass.simpleName} ${it.message.orEmpty().take(160)}")
            }.getOrNull()?.also { recognizer = it }
        }
    }

    /** 录好的 WAV → 文字。模型缺失 / native 不可用 / 解析失败一律 null，由调用方决定怎么提示 */
    suspend fun transcribeWav(context: Context, wavPath: String): String? =
        withContext(Dispatchers.IO) {
            val samples = runCatching { WavPcm.readMonoAt(java.io.File(wavPath), SAMPLE_RATE) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: return@withContext null
            transcribeSamples(context, samples)
        }

    /** 一段 16kHz 单声道采样 → 文字（通话按 VAD 分段后走这条） */
    suspend fun transcribeSamples(context: Context, samples: FloatArray): String? =
        withContext(Dispatchers.IO) {
            if (samples.isEmpty()) return@withContext null
            val rec = obtain(context) ?: return@withContext null
            synchronized(lock) {
                runCatching {
                    val stream = rec.createStream()
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    rec.decode(stream)
                    val text = stripSenseVoiceTags(rec.getResult(stream).text)
                    stream.release()
                    Log.i(TAG, "sample=${samples.size} text=${text.take(80)}")
                    text.takeIf { it.isNotBlank() }
                }.getOrElse {
                    Log.e(TAG, "decode failed: ${it.javaClass.simpleName} ${it.message.orEmpty().take(160)}")
                    null
                }
            }
        }

    /**
     * SenseVoice 的输出可能带 `<|zh|><|NEUTRAL|><|Speech|><|withitn|>` 这类特殊标记。
     * sherpa-onnx 的 C++ 侧一般会剥掉，这里再兜一层——漏一个进用户可见的转写文本就是脏数据。
     */
    internal fun stripSenseVoiceTags(raw: String): String =
        raw.replace(TAG_PATTERN, "").trim()

    private val TAG_PATTERN = Regex("<\\|[^|>]*\\|>")
}
