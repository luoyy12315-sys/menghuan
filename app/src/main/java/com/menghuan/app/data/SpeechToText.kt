package com.menghuan.app.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 语音转文字（v0.43.0 引入，v0.43.1 两轮重做）。
 *
 * ## v0.43.1 第一轮：删掉"录制期并行识别"
 * 原来为兼容 API 31 以下，在录音同时另开一个 `SpeechRecognizer` 并行识别。实测两条路都废：
 * - **抢麦克风**：识别器自身要开麦，`MediaRecorder` 拿不到音 → 录成静音。
 * - **格式**：`EXTRA_AUDIO_SOURCE` 要**未压缩 PCM**，原来录 `.m4a`(AAC) 读不了，直接 `onError`。
 * → 改成"录完再识别"，并把录音格式换成 WAV/PCM。
 *
 * ## v0.43.1 第二轮（**用户实测"还是识别不了"后重做**）：云端优先
 * 光换格式还不够 —— **系统识别器的"文件输入"在国产 ROM 上普遍不生效**。
 * `EXTRA_AUDIO_SOURCE` 是 API 31 才有的新能力，小米等定制系统的识别服务
 * 往往**只监听实时麦克风**，喂它文件路径等于没喂：`onResults` 不回调、
 * `onError` 也可能不给，最终 `withTimeoutOrNull` 走满 20s 返回 null。
 * 用户感受就是「语音发出去了，AI 那边什么都不知道」。
 *
 * ## v0.44.1：端侧离线识别成为**主力**（用户四条语音全空 + 拍板"端侧离线"后接入）
 * 前两轮攒下两个铁的事实：
 *   ① 云端 Whisper 依赖用户配置的接口支持 `/audio/transcriptions`，实测不支持 → 永远空；
 *   ② 系统识别器的"文件输入"在国产 ROM 上普遍不生效（识别服务只监听实时麦克风）。
 * sherpa-onnx 端侧离线识别**同时绕开这两者**：不碰网络、不碰系统识别服务，
 * 模型内置 APK（streaming-zipformer-zh-14M，全套 int8，四件套 25MB），
 * 任何设备行为一致，还顺带满足"数据只存本机"。见 [SherpaAsr]。
 *
 * 因此现在是**三级级联**，任一级成功即返回：
 *   ① 端侧 sherpa-onnx（[SherpaAsr.transcribeWav]）—— 主力，全离线
 *   ② 云端 Whisper（[OpenAiClient.transcribe]）—— 端侧不可用/识别为空时的兜底
 *   ③ 系统 `SpeechRecognizer` 喂文件 —— 最后兜底（国产 ROM 上大概率无效）
 *   ④ 都失败 → 返回 null，语音照发，`content` 为空
 *      （历史里写明"用户发了段语音但没转成文字"，模型不会瞎猜内容）
 *
 * 转录失败**不阻断发送**：语音条该发还是发。
 */
object SpeechToText {

    /** 单次系统识别超时：识别器不回调时不能把用户卡在这儿 */
    private const val TIMEOUT_MS = 20_000L

    /** 设备是否有可用的系统识别服务（没有则跳过第 ③ 级） */
    fun isAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * 录好的音频 → 文字，三级级联（见类注释）。
     *
     * @param cloudTranscribe 第 ② 级：云端转写（由调用方注入，因为它需要当前生效的 API 配置）。
     *   返回 null 表示"这条路走不通"，继续往下降级。
     * @param onPartial 第 ③ 级的中间结果回调（目前未接 UI，保留参数以便后续做进度提示）
     */
    suspend fun transcribe(
        context: Context,
        audioPath: String,
        cloudTranscribe: (suspend (java.io.File) -> String?)? = null,
        onPartial: ((String) -> Unit)? = null,
    ): String? {
        val file = java.io.File(audioPath)
        if (!file.exists() || file.length() == 0L) return null

        // ① 端侧离线识别：主力路径（模型在 APK 内；引擎不可用/识别为空安静降级）
        runCatching { SherpaAsr.transcribeWav(context, audioPath) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // ② 云端 Whisper：兜底路径
        cloudTranscribe?.let { cloud ->
            runCatching { cloud(file) }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        // ③ 系统识别（仅 API 31+ 支持文件输入）
        return transcribeFile(context, audioPath, onPartial)
    }

    /**
     * 第 ③ 级：系统识别器喂音频文件。
     *
     * 只在 API 31+ 可用（`EXTRA_AUDIO_SOURCE` 是 API 31 引入的）；
     * 低版本返回 null，调用方按"转写失败"处理。
     *
     * ⚠️ 国产 ROM 上这条路**大概率不生效**（识别服务只盯实时麦克风），
     * 所以它只是最后兜底，主力是 [transcribe] 里的端侧那一级。
     */
    suspend fun transcribeFile(
        context: Context,
        audioPath: String,
        onPartial: ((String) -> Unit)? = null,
    ): String? {
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        val file = java.io.File(audioPath)
        if (!file.exists() || file.length() == 0L) return null
        if (!isAvailable(context)) return null

        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
                    .getOrNull()
                if (recognizer == null) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                fun finish(text: String?) {
                    if (!done.compareAndSet(false, true)) return
                    runCatching { recognizer.destroy() }
                    if (cont.isActive) cont.resume(text)
                }
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        finish(
                            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()?.takeIf { it.isNotBlank() }
                        )
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { onPartial?.invoke(it) }
                    }

                    override fun onError(error: Int) = finish(null)
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, onPartial != null)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // API 31+：直接识别指定音频文件（我们录好的 WAV/PCM）
                    putExtra(
                        RecognizerIntent.EXTRA_AUDIO_SOURCE,
                        android.net.Uri.fromFile(file),
                    )
                }
                runCatching { recognizer.startListening(intent) }
                    .onFailure { finish(null) }
                cont.invokeOnCancellation {
                    if (done.compareAndSet(false, true)) runCatching { recognizer.cancel() }
                    runCatching { recognizer.destroy() }
                }
            }
        }
    }
}
