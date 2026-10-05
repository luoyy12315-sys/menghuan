package com.menghuan.app.data

import com.menghuan.app.llm.NetworkStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 合成失败的业务异常（消息就是给用户看的那句话） */
class TtsException(message: String) : Exception(message)

/**
 * 错误信息提取（三家共用）。
 *
 * 为什么不能只按 JSON 解析：硅基 401/403/404 回的是**裸字符串**（`"Invalid token"`），
 * 400/429/503 才是 JSON；MiMo 的错误体形状官方**根本没公开**。所以统一三级兜底。
 */
object TtsErrors {

    fun extract(body: String?): String {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return "（服务端没给原因）"
        if (text.startsWith("{")) {
            runCatching {
                val json = JSONObject(text)
                // 硅基/通用网关：{"message":...} 或 {"code":..,"message":..}
                json.optString("message").takeIf { it.isNotBlank() }?.let { return it }
                // OpenAI 形状：{"error":{"message":...}}
                json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.let { return it }
                // MiMo 猜测形状兜不住时，把整个 JSON 截断给用户，至少能拿去搜
            }
        }
        return text.take(300)
    }

    fun describe(t: Throwable?): String {
        val msg = t?.message?.takeIf { it.isNotBlank() }
        return msg ?: (t?.javaClass?.simpleName ?: "未知原因")
    }
}

/**
 * 统一语音合成入口（v0.50.0）。
 *
 * 取代原来的两套合成（Edge TTS 朗读 + 通话自己一套）：AI 语音条与通话**共用这一条链**，
 * 音色也共用一套（[VoiceRef]）。
 *
 * 契约：
 * - 成功返回 [TtsAudio]（带格式，调用方按格式选解码器）；
 * - 任何失败返回 null，原因写在 [lastFailure] 里 —— **不静默、不假装成功**，
 *   UI 要把这句话给用户看（云端 TTS 未配 / Key 失效导致通话无声是这个项目踩过的坑）。
 */
interface TtsClient {

    /** 配置是否齐（Key + 地址 + 模型）。没配齐就别发请求，也别告诉模型"你能发语音" */
    val isConfigured: Boolean

    /**
     * [target] 决定我们要什么响应格式（v0.52.0）：语音条要 MP3（小、`MediaPlayer` 直接播），
     * 通话要 WAV + 与播放轨道同采样率（**通话链路从此不解 MP3**）。默认 [TtsTarget.NOTE]。
     */
    suspend fun synthesize(text: String, voice: VoiceRef, target: TtsTarget = TtsTarget.NOTE): TtsAudio?

    val lastFailure: String?
}

/** 三家共用的 HTTP 骨架：超时、成败判据、错误翻译。各家差异只在请求体与响应解析 */
abstract class HttpTtsClient(protected val config: TtsConfig) : TtsClient {

    @Volatile
    private var failure: String? = null

    override val lastFailure: String? get() = failure

    override val isConfigured: Boolean get() = config.isComplete

    protected val http: OkHttpClient by lazy {
        NetworkStack.baseBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // 读超时给足：一句长文本合成 + 网关排队，官方建议 120s
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun synthesize(text: String, voice: VoiceRef, target: TtsTarget): TtsAudio? =
        withContext(Dispatchers.IO) {
            failure = null
            if (!isConfigured) {
                failure = "语音合成未配置（设置 · 语音）"
                return@withContext null
            }
            val clean = text.trim()
            if (clean.isEmpty()) {
                failure = "要合成的文字为空"
                return@withContext null
            }
            runCatching { doSynthesize(clean, voice, target) }
                .onFailure { failure = TtsErrors.describe(it) }
                .getOrNull()
        }

    protected abstract fun doSynthesize(text: String, voice: VoiceRef, target: TtsTarget): TtsAudio

    /**
     * 读「裸二进制音频」型响应（硅基 / 通用端点）。
     *
     * 判据不看 Content-Type：官方没给成功响应的 Content-Type 逐字值，
     * 而且存在「200 里塞错误 JSON」的网关，所以对 body 首字节做一次嗅探。
     */
    protected fun readBinaryAudio(response: Response): TtsAudio {
        val bytes = response.body?.bytes() ?: ByteArray(0)
        if (!response.isSuccessful) {
            throw TtsException("HTTP ${response.code}：${TtsErrors.extract(String(bytes, Charsets.UTF_8))}")
        }
        if (bytes.isEmpty()) throw TtsException("服务端返回了空音频")
        if (bytes[0] == '{'.code.toByte()) {
            throw TtsException(TtsErrors.extract(String(bytes, Charsets.UTF_8)))
        }
        val format = TtsAudioFormat.sniff(bytes)
            ?: throw TtsException(
                "返回的不是能识别的音频（首 4 字节 ${bytes.take(4).joinToString { "%02X".format(it) }}）",
            )
        return TtsAudio(bytes, format)
    }

    protected fun jsonBody(vararg pairs: Pair<String, Any?>): String = JSONObject().apply {
        // 可选字段为 null 时**不写进 JSON**：JSONObject.put(k, null) 会写出 "k":null，
        // 严格网关会直接 400（研究文档 §5.1 第 2 条）
        pairs.forEach { (k, v) -> if (v != null) put(k, v) }
    }.toString()

    protected companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * 硅基流动 CosyVoice2：`POST /audio/speech` → **裸二进制**。
 *
 * `voice` 必须带模型名前缀（预置音色），克隆返回的 `speech:...` uri 则原样发，
 * 两种形态由 [TtsVoices.siliconVoice] 区分。
 */
class SiliconFlowTts(config: TtsConfig) : HttpTtsClient(config) {

    override fun doSynthesize(text: String, voice: VoiceRef, target: TtsTarget): TtsAudio {
        val model = config.effectiveModel()
        val alias = voice.voiceId.ifBlank { TtsVoices.defaultVoiceId(TtsProvider.SILICONFLOW) }
        val format = target.formatFor(TtsProvider.SILICONFLOW)
        val body = jsonBody(
            "model" to model,
            "input" to text,
            "voice" to TtsVoices.siliconVoice(model, alias),
            "response_format" to format.name.lowercase(),
            // 官方文档自相矛盾（正文说默认 44100、API 参考的 Default 栏印 32000）→ 一律显式传，不赌默认值。
            // 通话传 24000 = 播放轨道采样率，回来正好直接用，不必重采样（v0.52.0）
            "sample_rate" to (target.sampleRate ?: 44_100),
            "stream" to false,
        )
        val request = Request.Builder()
            .url("${config.effectiveBaseUrl()}/audio/speech")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        return http.newCall(request).execute().use { readBinaryAudio(it) }
    }
}

/**
 * 通用 OpenAI 兼容端点：`POST /audio/speech` → 裸二进制。
 *
 * 只发最小公约数字段（`model`/`input`/`voice`/`response_format`）：
 * `speed`、`instructions`、`stream_format` 各家支持面差异很大，
 * 多塞一个就可能被严格网关 400（研究文档 §3.3）。
 */
class CustomOpenAiTts(config: TtsConfig) : HttpTtsClient(config) {

    override fun doSynthesize(text: String, voice: VoiceRef, target: TtsTarget): TtsAudio {
        val body = jsonBody(
            "model" to config.effectiveModel(),
            "input" to text,
            "voice" to voice.voiceId,
            "response_format" to target.formatFor(TtsProvider.CUSTOM).name.lowercase(),
        )
        val request = Request.Builder()
            .url("${config.effectiveBaseUrl()}/audio/speech")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            // OpenAI 官方 SDK 显式带这个头，照抄（有些网关按 Accept 决定回二进制还是 JSON）
            .addHeader("Accept", "application/octet-stream")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        return http.newCall(request).execute().use { readBinaryAudio(it) }
    }
}

/**
 * 小米 MiMo：**走 `/chat/completions`，不是 `/audio/speech`**，音频在
 * `choices[0].message.audio.data`（base64）。三个反直觉点，逐条都来自官方 API 参考：
 *
 * 1. **要合成的文本必须放 `role: "assistant"` 的 content**（放 user 里不会念）；
 *    `role: "user"` 只用来传风格指令，其内容不会被念出来。
 * 2. **不需要 `modalities` 字段**——小米自己的 OpenAI 兼容参考页全文 0 次出现，
 *    三个独立开源实现也都不传。
 * 3. 鉴权头官方给的是 `api-key`，FAQ 又说 `Authorization: Bearer` 也行 → **两个都发**。
 *
 * 默认回 **WAV**（官方所有示例都用 wav/pcm16，enum 里那个 `mp3` 没有任何实现用过，不赌）。
 */
class XiaomiMimoTts(config: TtsConfig) : HttpTtsClient(config) {

    override fun doSynthesize(text: String, voice: VoiceRef, target: TtsTarget): TtsAudio {
        // MiMo 永远要 wav（见 TtsTarget.formatFor）：官方示例全是 wav/pcm16，mp3 没人用过，不赌
        val body = JSONObject().apply {
            put("model", config.effectiveModel())
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "user").put("content", ""))
                    .put(JSONObject().put("role", "assistant").put("content", text)),
            )
            put("audio", JSONObject().put("format", "wav").put("voice", resolveVoice(voice)))
            put("stream", false)
        }
        val request = Request.Builder()
            .url("${config.effectiveBaseUrl()}/chat/completions")
            .addHeader("api-key", config.apiKey)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        return http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 421 是内容审核拦截，不是网络故障：重试没意义，文案要区别对待
                if (resp.code == 421) {
                    throw TtsException("内容审核拦截（421）：这段文字被服务商判为敏感，换个说法再试")
                }
                throw TtsException("HTTP ${resp.code}：${TtsErrors.extract(text)}")
            }
            parseAudio(text)
        }
    }

    /**
     * 克隆音色 = 每次请求把样本 base64 内联进 `audio.voice`（MiMo **没有上传接口**，
     * 也没有 list/delete，样本只能存在本机、每次重传）。
     */
    private fun resolveVoice(voice: VoiceRef): String {
        val id = voice.voiceId.ifBlank { TtsVoices.defaultVoiceId(TtsProvider.XIAOMI_MIMO) }
        val path = TtsVoices.localSamplePath(id) ?: return id
        val file = File(path)
        if (!file.isFile) throw TtsException("克隆样本已丢失：$path")
        if (!TtsVoices.isWithinMimoCloneLimit(file.length())) {
            throw TtsException(
                "克隆样本编码后超过 10MB 上限（实际约 " +
                    "${TtsVoices.mimoCloneBase64Size(file.length()) / 1024 / 1024} MB），请换一段更短的音频",
            )
        }
        val mime = if (path.endsWith(".wav", true)) "audio/wav" else "audio/mpeg"
        return TtsVoices.mimoCloneVoice(mime, TtsVoices.base64NoWrap(file.readBytes()))
    }

    private fun parseAudio(body: String): TtsAudio {
        if (body.isBlank()) throw TtsException("MiMo 响应体为空")
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw TtsException("解析 MiMo 响应失败：${TtsErrors.extract(body)}")
        }
        val choices = json.optJSONArray("choices")
            ?: throw TtsException("MiMo 响应无 choices：${TtsErrors.extract(body)}")
        val audio = choices.optJSONObject(0)?.optJSONObject("message")?.optJSONObject("audio")
            ?: throw TtsException("MiMo 响应无 message.audio：${TtsErrors.extract(body)}")
        val data = audio.optString("data")
        if (data.isBlank()) throw TtsException("MiMo 响应 audio.data 为空")

        val bytes = runCatching { android.util.Base64.decode(TtsVoices.stripBase64(data), android.util.Base64.DEFAULT) }
            .getOrElse { throw TtsException("MiMo 音频 base64 解码失败：${it.javaClass.simpleName}") }
        if (bytes.isEmpty()) throw TtsException("MiMo 解出的音频为空")
        val format = TtsAudioFormat.sniff(bytes)
            ?: throw TtsException("MiMo 返回的不是能识别的音频格式")
        return TtsAudio(bytes, format)
    }
}

/** 按配置里的 provider 挑实现（调用方只认 [TtsClient] 接口） */
object TtsClients {

    fun from(config: TtsConfig): TtsClient = when (config.provider) {
        TtsProvider.SILICONFLOW -> SiliconFlowTts(config)
        TtsProvider.XIAOMI_MIMO -> XiaomiMimoTts(config)
        TtsProvider.CUSTOM -> CustomOpenAiTts(config)
    }
}
