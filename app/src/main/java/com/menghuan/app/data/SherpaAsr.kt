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
 * 一次端侧识别的读数（v0.69.0）：文字 + SenseVoice 顺带出的语气与音频事件。
 *
 * [toneHint] 是给模型看的那一句。两个口径要守住：
 * 1. **认不出的标签就整条不提**，不翻译、不猜、不把英文原词甩给模型
 *    （把 `EMO` 写进上下文等于让模型自己去猜那是什么意思）；
 * 2. **NEUTRAL 不写**（"情绪=中性"对模型没有任何信息量，只费 token）。
 */
data class AsrReadout(
    val text: String,
    val emotion: String = "",
    val event: String = "",
    val lang: String = "",
) {

    /** 语气那一句；没有任何可用信号时返回空串（调用方据此整条不注入） */
    fun toneHint(): String {
        val parts = ArrayList<String>(2)
        EMOTION_LABELS[normalize(emotion)]?.let { parts += "听起来" + it }
        // 事件只报"有用的那几种"：Sing/BGM/Ambient 说明用户那边有声场，
        // 而 Speech / Default 是常态，写进上下文纯属占位费 token
        EVENT_LABELS[normalize(event)]?.let { parts += it }
        if (parts.isEmpty()) return ""
        return "用户这一句是语音，" + parts.joinToString("，") + "（按这个语气回应，不用把这行字念出来）"
    }

    /**
     * 标签归一：去首尾空格、大写、把内部空白压成单个下划线。
     * SenseVoice 的事件标签本身带空格（`Ambient sounds`），不归一就得为每种写法各留一个键。
     */
    private fun normalize(raw: String): String =
        raw.trim().uppercase().replace(Regex("\\s+"), "_")

    companion object {
        /** SenseVoice 的情绪标签 → 中文措辞。**表外的标签一律忽略**（不翻译、不把英文原词甩给模型） */
        private val EMOTION_LABELS = mapOf(
            "HAPPY" to "心情不错",
            "SAD" to "有点低落",
            "ANGRY" to "带着火气",
            "FEAR" to "有些紧张",
            "DISGUST" to "不太情愿",
            "SURPRISED" to "被惊到了",
        )

        private val EVENT_LABELS = mapOf(
            "SING" to "而且正在唱歌",
            "BGM" to "背景有音乐",
            "AMBIENT_SOUNDS" to "背景有环境声",
        )
    }
}

/**
 * 端侧离线语音识别（v0.50.0 换成 SenseVoice 离线非流式；v0.44.1 起是转写链的唯一一级）。
 *
 * ## 为什么从流式 zipformer-14M 换成离线 SenseVoice
 * 旧模型 1400 万参数、官方定性"准确率不佳"，而且梦欢是**录完再识别**——用流式模型
 * 等于拿错了工具：流式的 chunk 缓冲要补尾部静音才不丢字，长录音内存峰值还高。
 * SenseVoice（int8 228MB）中文 CER 约 8%，一次吃完整段音频，形态与用法对得上。
 * 顺带能出情绪标签与音频事件（`OfflineRecognizerResult.emotion/event`）：
 * v0.50.0 那版只取 text 把这两个数扔了，**v0.69.0 起接回来当语气读数**（见 [AsrReadout]）。
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
        readoutSamples(context, samples)?.text?.takeIf { it.isNotBlank() }

    /**
     * 同一次解码，但把 SenseVoice 顺手出的**语气读数**一起带回来（v0.69.0）。
     *
     * ## 为什么以前扔了
     * 这个模型一直是连着情绪与音频事件一起出的（v0.50.0 那版注释里自己写着"本期只取 text"），
     * 于是"用户声音听着很低落"这种一手信号白丢了三轮。
     * `OfflineRecognizerResult` 上确实有 `getEmotion()` / `getEvent()` / `getLang()`
     * （**javap 对过 aar，不是照文档猜的**）。
     *
     * ## 为什么不另起一条解码
     * 权重 228MB，两个 session ≈ 500MB，中低端机直接 OOM —— 与 §零.4"语音消息与通话共用同一个识别器实例"
     * 是同一条理由。所以 [transcribeSamples] 改成走这里，**只有一条解码路径**，
     * 返回值除了 text 以外一律不影响既有行为。
     */
    suspend fun readoutSamples(context: Context, samples: FloatArray): AsrReadout? =
        withContext(Dispatchers.IO) {
            if (samples.isEmpty()) return@withContext null
            val rec = obtain(context) ?: return@withContext null
            synchronized(lock) {
                runCatching {
                    val stream = rec.createStream()
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    rec.decode(stream)
                    val result = rec.getResult(stream)
                    stream.release()
                    val text = stripSenseVoiceTags(result.text)
                    Log.i(
                        TAG,
                        "sample=${samples.size} text=${text.take(80)} " +
                            "emotion=${result.emotion.orEmpty()} event=${result.event.orEmpty()}",
                    )
                    // 空文本不当成一次成功识别（既有口径不变）
                    if (text.isBlank()) null else AsrReadout(
                        text = text,
                        emotion = result.emotion.orEmpty().trim(),
                        event = result.event.orEmpty().trim(),
                        lang = result.lang.orEmpty().trim(),
                    )
                }.getOrElse {
                    Log.e(TAG, "decode failed: ${it.javaClass.simpleName} ${it.message.orEmpty().take(160)}")
                    null
                }
            }
        }

    /** wav 文件 → 语气读数（与 [transcribeWav] 同一套读法，只是多带回两个字段） */
    suspend fun readoutWav(context: Context, wavPath: String): AsrReadout? =
        withContext(Dispatchers.IO) {
            val samples = runCatching { WavPcm.readMonoAt(java.io.File(wavPath), SAMPLE_RATE) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: return@withContext null
            readoutSamples(context, samples)
        }

    /**
     * SenseVoice 的输出可能带 `<|zh|><|NEUTRAL|><|Speech|><|withitn|>` 这类特殊标记。
     * sherpa-onnx 的 C++ 侧一般会剥掉，这里再兜一层——漏一个进用户可见的转写文本就是脏数据。
     */
    internal fun stripSenseVoiceTags(raw: String): String =
        raw.replace(TAG_PATTERN, "").trim()

    private val TAG_PATTERN = Regex("<\\|[^|>]*\\|>")
}
