package com.menghuan.app.data

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 端侧离线语音识别（v0.44.1 引入，成为转写链的**主力**）。
 *
 * ## 方案来源（用户拍板"先抄 GitHub 成熟案例"）
 * 引擎：`k2-fsa/sherpa-onnx`（10.9k+ star，Apache-2.0，2026 年仍在发版，
 * next-gen Kaldi 官方 Android AAR，中文流式识别 / VAD / 标点一应俱全，有国内镜像）。
 * 模型：`sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`（中文流式 Zipformer，
 * 1400 万参数，**全套 int8 量化**，四个文件共 25MB，APK 内置）。
 * 换它的原因：原定 small-bilingual 中英包的 release 归档 458MB（下不动、也塞不进 APK），
 * 14M 版 74MB 包体里只有 25MB 是真正要用的 int8 四件套，中文场景完全够用。
 * 参考实现：官方 Android 示例 + `saysrytoyou/Android-Offline-ASR-TTS-Deploy`
 * （中文端侧离线流式 ASR 完整示例）+ `zst50/voice-chatbot-android`（端侧语音 bot 管线）。
 *
 * ## 为什么端侧当主力（用户四条语音全部转写为空之后的结论）
 *   ① 云端 Whisper 依赖用户配置的接口支持 `/audio/transcriptions`，实测不支持；
 *   ② 系统 `SpeechRecognizer` 的文件输入在国产 ROM 上普遍不生效（v0.43.1 老结论）；
 *   ③ 端侧识别**同时绕开这两者**：不碰网络、不碰系统识别服务，模型就在 APK 里，
 *      任何设备行为一致 —— 还顺带满足"数据只存本机"。
 *
 * ## 工作方式
 * 按住说话录出的 WAV（16bit PCM，见 [VoiceRecorder]）→ [WavPcm] 解析成
 * 16kHz 单声道采样 → 喂给流式识别器一次性解码 → 文本。
 * 模型懒加载（首次转写约几百毫秒），进程内复用；加载/解码全部兜
 * `runCatching` —— **识别失败绝不炸发送链**（JVM 单测没有 native 库，同样安静返回 null）。
 */
object SherpaAsr {

    private const val TAG = "SherpaAsr"
    private const val ASSET_DIR = "asr"
    private const val SAMPLE_RATE = 16_000

    private const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
    private const val DECODER = "decoder-epoch-99-avg-1.int8.onnx"
    private const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
    private const val TOKENS = "tokens.txt"

    @Volatile
    private var recognizer: OnlineRecognizer? = null
    private val lock = Any()

    /** assets 里是否有模型文件（APK 内置了才有；快速预检，避免每次转写白跑初始化） */
    fun modelPresent(context: Context): Boolean = runCatching {
        context.assets.open("$ASSET_DIR/$TOKENS").use { it.read() > 0 }
    }.getOrDefault(false)

    private fun obtain(context: Context): OnlineRecognizer? = runCatching {
        recognizer ?: synchronized(lock) {
            recognizer ?: runCatching {
                // 位置传参：assetManager 在前（签名经 javap 核对：AssetManager + Config）
                OnlineRecognizer(
                    context.assets,
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
            }.onFailure {
                Log.e(TAG, "init recognizer failed: ${it.javaClass.simpleName} ${it.message.orEmpty().take(120)}")
            }.getOrNull()?.also { recognizer = it }
        }
    }.getOrNull()

    /**
     * 录好的 WAV → 文字。失败（模型缺失 / native 库不可用 / 解析失败）一律返回 null，
     * 由调用方降级到云端 / 系统识别。
     */
    suspend fun transcribeWav(context: Context, wavPath: String): String? =
        withContext(Dispatchers.IO) {
            val rec = obtain(context) ?: return@withContext null
            val samples = runCatching { WavPcm.readMonoAt(java.io.File(wavPath), SAMPLE_RATE) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?: return@withContext null

            synchronized(lock) {
                runCatching {
                    val stream = rec.createStream()
                    // 流式模型按 0.1s 分片喂（官方示例做法）：一次性灌整段会让
                    // encoder 的 chunk 缓冲一次性堆积，长录音内存峰值高、也没法边喂边解码。
                    val chunk = SAMPLE_RATE / 10
                    var offset = 0
                    while (offset < samples.size) {
                        val end = minOf(offset + chunk, samples.size)
                        stream.acceptWaveform(samples.copyOfRange(offset, end), SAMPLE_RATE)
                        while (rec.isReady(stream)) rec.decode(stream)
                        offset = end
                    }
                    // 尾部补 0.3s 静音：zipformer 是流式模型，末尾那一小段还在 chunk
                    // 缓冲里没被算完，不补就直接 inputFinished 会丢最后几个字。
                    stream.acceptWaveform(FloatArray(SAMPLE_RATE * 3 / 10), SAMPLE_RATE)
                    stream.inputFinished()
                    while (rec.isReady(stream)) rec.decode(stream)
                    val text = rec.getResult(stream).text.trim()
                    stream.release()
                    // 留一条成功日志 + 采样数：真机上"转写还是空的"时，
                    // 靠这条能立刻分清是「没读到音频」还是「模型没识别出来」。
                    Log.i(TAG, "sample=${samples.size} text=${text.take(80)}")
                    text.takeIf { it.isNotBlank() }
                }.getOrElse {
                    Log.e(TAG, "decode failed: ${it.javaClass.simpleName} ${it.message.orEmpty().take(120)}")
                    null
                }
            }
        }
}
