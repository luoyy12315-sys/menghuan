package com.menghuan.app.data

import android.content.Context
import android.net.Uri
import com.menghuan.app.llm.NetworkStack
import com.menghuan.app.realtime.Mp3Decoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 一个已克隆好的音色（硅基 `/audio/voice/list` 的一行） */
data class ClonedVoice(val customName: String, val uri: String, val model: String)

/**
 * 音色克隆（v0.50.0）。用户拍板走**「App 内全自动」**路线：
 *
 *   选一段样本 → 裁到前 15 秒 → **端侧 SenseVoice 自动转写出逐字稿** → multipart 上传 → 拿 `uri`
 *
 * ## 为什么必须自己转写
 * 硅基上传接口把 `text`（参考音频说了什么）标成**必填**。只让用户选一个 MP3 是凑不齐请求的。
 * 本项目 v0.50.0 步骤 1 正好装了端侧离线识别，克隆前跑一次即可，零额外依赖、不额外联网。
 * 转写不出来时把输入框露给用户手填，**不传空串去赌服务端容忍**（官方标 required）。
 *
 * ## 为什么只收 mp3 / wav
 * 这两家接受的样本格式本来就窄（硅基 mp3/wav/pcm/opus，MiMo 只有 mp3/wav），
 * 而这两种容器我们**已经有解码器**（[Mp3Decoder] / [WavPcm]），能精确裁到 15 秒；
 * m4a 之类要另起 MediaExtractor 管线，收益不抵复杂度——直接拒收并说清原因。
 *
 * ## 关于「实名认证」
 * 硅基官方明写「使用用户预置音色，需要进行实名认证」。未认证时的错误码与文案**没有公开**，
 * 所以这里不自己猜码，把服务端 message 原文透传，并在 UI 上提示去认证。
 */
object VoiceCloner {

    /** 样本裁剪上限：官方要求 <30s、建议 8~10s，取 15s 是安全中值 */
    const val MAX_SAMPLE_SECONDS = 15

    private const val OUT_RATE = 24_000

    private val http by lazy {
        NetworkStack.baseBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun voiceDir(context: Context): File =
        File(context.filesDir, "voices").apply { if (!exists()) mkdirs() }

    /**
     * 把用户选的样本裁到前 [MAX_SAMPLE_SECONDS] 秒，统一落成 24kHz 单声道 16bit WAV。
     *
     * @return 落盘文件；格式不支持 / 解码失败时抛 [IllegalStateException]，消息可直接给用户看
     */
    suspend fun prepareSample(context: Context, uri: Uri, name: String): File =
        withContext(Dispatchers.IO) {
            val source = File(voiceDir(context), "source_${System.currentTimeMillis()}.tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(source).use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("读取所选音频失败")

            val decoded = runCatching { decodeToPcm(source) }.getOrElse {
                source.delete()
                throw IllegalStateException("解码音频失败：${it.javaClass.simpleName}")
            }
            source.delete()
            if (decoded == null) {
                throw IllegalStateException("只支持 mp3 / wav 样本，请换一个文件")
            }

            val (samples, rate) = decoded
            if (samples.isEmpty()) throw IllegalStateException("音频里没有采样数据")
            val limit = rate * MAX_SAMPLE_SECONDS
            val trimmed = if (samples.size > limit) samples.copyOf(limit) else samples
            val wav = WavPcm.writeMono(trimmed, rate)

            val out = File(voiceDir(context), "${sanitize(name)}.wav")
            out.writeBytes(wav)
            out
        }

    /** @return (单声道 PCM16, 采样率)；容器不认返回 null */
    private fun decodeToPcm(source: File): Pair<ShortArray, Int>? {
        val bytes = source.readBytes()
        // WAV：直接解 RIFF 头，顺手重采样到 24k（上传体积小一半，两家都收 wav）
        WavPcm.parse(bytes, OUT_RATE)?.let { floats ->
            val shorts = ShortArray(floats.size) { (floats[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
            return shorts to OUT_RATE
        }
        // MP3：系统 MediaCodec 解码（与通话播放同一条路）
        Mp3Decoder.decode(bytes)?.let { pcm ->
            val mono = if (pcm.channels > 1) downmix(pcm.samples, pcm.channels) else pcm.samples
            return if (pcm.sampleRate == OUT_RATE) {
                mono to OUT_RATE
            } else {
                val floats = FloatArray(mono.size) { mono[it] / 32768f }
                val resampled = WavPcm.resampleLinear(floats, pcm.sampleRate, OUT_RATE)
                ShortArray(resampled.size) { (resampled[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() } to OUT_RATE
            }
        }
        return null
    }

    private fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += interleaved[i * channels + c]
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    /** 端侧转写样本，拿到上传接口必填的 `text`。识别模型没下载 / 转不出来返回 null，由 UI 让用户手填 */
    suspend fun transcribeSample(context: Context, sample: File): String? =
        SherpaAsr.transcribeWav(context, sample.absolutePath)?.takeIf { it.isNotBlank() }

    /**
     * 上传样本换 `uri`（硅基 `POST /uploads/audio/voice`，multipart 形态）。
     * @return `speech:<customName>:<accountId>:<voiceId>`，原样存进 [VoiceRef.voiceId]
     */
    suspend fun upload(config: TtsConfig, sample: File, customName: String, transcript: String): String =
        withContext(Dispatchers.IO) {
            if (transcript.isBlank()) throw IllegalStateException("样本逐字稿不能为空（转写失败时请手填）")
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", sample.name, sample.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", config.effectiveModel())
                .addFormDataPart("customName", customName)
                .addFormDataPart("text", transcript)
                .build()
            val request = Request.Builder()
                .url("${config.effectiveBaseUrl()}/uploads/audio/voice")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .post(body)
                .build()
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // 未实名认证时的错误码官方没公开 → 原文透传，别自己编判断
                    throw IllegalStateException("HTTP ${resp.code}：${TtsErrors.extract(text)}")
                }
                val uri = runCatching { JSONObject(text).optString("uri") }.getOrDefault("")
                if (uri.isBlank()) throw IllegalStateException("上传成功但响应里没有 uri：${text.take(200)}")
                uri
            }
        }

    /**
     * 列出我的克隆音色（硅基 `GET /audio/voice/list`）。
     *
     * 数组键名有**三份互相矛盾的证词**：官方文档 = `results`，两个第三方实现分别是 `result` / `data`。
     * 只能四路全兜（含"整个 body 就是数组"）；取不到时报"解析失败"而不是"没有音色"——
     * 后者会让用户以为自己克隆的东西丢了。
     */
    suspend fun listVoices(config: TtsConfig): List<ClonedVoice> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${config.effectiveBaseUrl()}/audio/voice/list")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}：${TtsErrors.extract(text)}")
            parseVoiceList(text)
        }
    }

    /** 纯解析，JVM 可测 */
    internal fun parseVoiceList(body: String): List<ClonedVoice> {
        if (body.isBlank()) throw IllegalStateException("音色列表响应为空")
        val array: JSONArray = runCatching {
            val json = JSONObject(body)
            listOf("results", "result", "data", "voice")
                .firstNotNullOfOrNull { key -> json.optJSONArray(key) }
                ?: throw IllegalStateException("音色列表解析失败：找不到数组字段")
        }.getOrElse {
            runCatching { JSONArray(body) }.getOrElse {
                throw IllegalStateException("音色列表解析失败：${TtsErrors.extract(body)}")
            }
        }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val uri = o.optString("uri")
            if (uri.isBlank()) return@mapNotNull null
            ClonedVoice(
                customName = o.optString("customName").ifBlank { uri },
                uri = uri,
                model = o.optString("model"),
            )
        }
    }

    /** 音色名会进文件名与 customName，去掉路径分隔符等会惹麻烦的字符 */
    private fun sanitize(name: String): String =
        name.trim().ifBlank { "voice" }.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
}
