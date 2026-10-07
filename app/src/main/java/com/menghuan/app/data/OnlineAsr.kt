package com.menghuan.app.data

import android.util.Log
import com.menghuan.app.llm.NetworkStack
import com.menghuan.app.llm.callWithTlsFallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 在线语音识别（v0.59.0）：OpenAI 兼容的 `POST {接口地址}/audio/transcriptions`。
 *
 * ## 和 v0.50.0 删掉的那条"云端 Whisper"有什么不一样
 * 当年删它，不是因为在线识别本身没用，而是它坐在**自动回退链的第二级**上：
 * 用户配的那些端点实测都不支持这个接口，于是每次都白等一轮超时、再把锅甩给下一级，
 * "识别为空"这件事变得谁也说不清（AGENTS §零.4 记的就是这段）。
 *
 * 这次改成**手动切换、不回退**：
 * - 「设置 → 语音 → 识别引擎」里你自己选离线 SenseVoice 还是在线接口；
 * - 选了在线，失败就**把原因原样报出来**（[lastFailure]），绝不偷偷改用离线再假装没事；
 * - 没配在线（缺地址 / 缺 Key / 缺模型）时这一路直接判"未配置"，不去发请求。
 *
 * 服务商的 Key 与额度都由用户自己申请，本项目不代管、不猜地址（AGENTS §零.6）。
 */
object OnlineAsr {

    private const val TAG = "OnlineAsr"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)   // 识别一段长语音可能几十秒，别按普通 API 的 10s 掐
            .build()
    }

    /** 最近一次失败的原因（给用户看的一句话）。成功时清空 */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * 地址怎么填都尽量能用：
     * - 填到版本段（`https://xxx/v1`）→ 拼成 `…/v1/audio/transcriptions`
     * - 直接填完整接口（结尾就是 `/audio/transcriptions`）→ 原样用
     */
    fun endpointOf(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/audio/transcriptions")) trimmed
        else "$trimmed/audio/transcriptions"
    }

    /** 三项都齐了才谈得上发请求 */
    fun isConfigured(baseUrl: String, apiKey: String, model: String): Boolean =
        baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /**
     * 音频文件 → 文字。失败返回 null 并把原因写进 [lastFailure]（**不抛异常**，
     * 调用方是聊天页按住说话的松手处，抛出去就是一次闪退）。
     */
    suspend fun transcribe(
        baseUrl: String,
        apiKey: String,
        model: String,
        audioFile: File,
    ): String? = withContext(Dispatchers.IO) {
        lastFailure = null
        if (!audioFile.isFile || audioFile.length() == 0L) {
            lastFailure = "录音文件是空的"
            return@withContext null
        }
        if (!isConfigured(baseUrl, apiKey, model)) {
            lastFailure = "在线识别没配全（缺接口地址 / Key / 模型）"
            return@withContext null
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("application/octet-stream".toMediaType()))
            .addFormDataPart("model", model.trim())
            .build()
        val request = Request.Builder()
            .url(endpointOf(baseUrl))
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(body)
            .build()
        runCatching {
            http.callWithTlsFallback(request) { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // 服务端原话带回去：401 是没配好 Key，404 多半是这家压根没有这个接口，
                    // 这三种情况的处理动作完全不同，不能揉成一句"识别失败"
                    lastFailure = "HTTP ${resp.code}：" +
                        (com.menghuan.app.llm.NetworkStack.apiErrorText(text) ?: text.take(160))
                    return@callWithTlsFallback null
                }
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json == null) {
                    lastFailure = "返回不是合法 JSON"
                    return@callWithTlsFallback null
                }
                // 字段名按常见写法试：Whisper 是 {"text":…}，也有家写 transcript / result.text
                val said = json.optString("text", "").ifBlank {
                    json.optString("transcript", "").ifBlank {
                        json.optJSONObject("result")?.optString("text").orEmpty()
                    }
                }.trim()
                if (said.isBlank()) {
                    lastFailure = "返回里没有识别结果（data.text 是空的）"
                    return@callWithTlsFallback null
                }
                said
            }
        }.getOrElse {
            lastFailure = NetworkStack.describeNetworkFailure(it)
            Log.w(TAG, "online asr failed", it)
            null
        }
    }
}
