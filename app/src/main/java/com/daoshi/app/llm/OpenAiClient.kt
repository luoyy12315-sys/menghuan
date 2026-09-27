package com.daoshi.app.llm

import com.daoshi.app.data.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 业务级失败（非 2xx 响应）。与网络异常分开，便于按状态码给提示。 */
class ApiException(val code: Int, body: String?) :
    IOException("HTTP $code" + (body?.take(200)?.let { "：$it" } ?: ""))

/**
 * OpenAI 兼容协议客户端：baseUrl + apiKey + model 三件套，接任意服务商。
 * 流式输出通过 SSE（data: {...} 行）解析。
 */
class OpenAiClient(
    private val baseUrl: String,   // 例：https://api.deepseek.com/v1
    private val apiKey: String,
    private val model: String,
) {
    fun chat(systemPrompt: String, history: List<ChatMessage>): Flow<String> = flow {
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            history.forEach { m ->
                put(
                    JSONObject()
                        .put("role", if (m.isFromUser) "user" else "assistant")
                        .put("content", m.content)
                )
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${baseUrl.trim().trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = http.newCall(request)
        try {
            call.execute().use { resp ->
                // 非 2xx 一律抛 ApiException：错误文本绝不混进回复流（否则会被当作 AI 回复落库）
                if (!resp.isSuccessful) {
                    throw ApiException(resp.code, resp.body?.string())
                }
                val source = resp.body?.source() ?: return@use
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val delta = runCatching {
                        JSONObject(payload)
                            .optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                            ?.optString("content")
                    }.getOrNull()
                    if (!delta.isNullOrEmpty()) emit(delta)
                }
            }
        } catch (e: CancellationException) {
            call.cancel() // 收集端已取消：中断底层连接，别让阻塞读滞留 IO 线程
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /** 测试连接：GET {base}/models，返回 null 表示成功，否则为错误信息 */
    suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${baseUrl.trim().trimEnd('/')}/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            http.newBuilder().readTimeout(10, TimeUnit.SECONDS).build()
                .newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) null else "HTTP ${resp.code}"
                }
        }.getOrElse { it.message ?: "网络错误" }
    }

    companion object {
        /**
         * 全 App 共享一个 OkHttpClient（连接池 / 线程池），不要每次请求都 new。
         * readTimeout = 两次数据间的最大间隔：推理类模型（deepseek-reasoner 等）首 token
         * 前可能长时间无输出，30s 会误报失败，故放宽到 120s；设 0 则服务端停摆时永久挂起。
         */
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)   // 整次流式的兜底上限
            .build()
    }
}
