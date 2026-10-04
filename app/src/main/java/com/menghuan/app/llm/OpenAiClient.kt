package com.menghuan.app.llm

import com.menghuan.app.data.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
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
 * 流式事件：正文增量 / 思考链增量 / 用量统计
 * （部分服务商在最后一个 SSE 块带 usage；没有就没有）。
 */
sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent

    /**
     * 思考链增量（v0.41.4）：推理类模型（deepseek-reasoner / GLM / qwen-thinking 等）
     * 在正文之前逐块吐 `reasoning_content`。与正文分开成独立事件——正文要上屏，
     * 思考链只在长按菜单里按需查看，绝不混进气泡。
     */
    data class Reasoning(val text: String) : StreamEvent
    data class Usage(val tokens: Int) : StreamEvent
}

/** collectReply 的返回：全量回复文本 + 思考链 + 该次调用的 token 用量（拿不到为 null） */
data class CollectedReply(
    val text: String,
    val usageTokens: Int? = null,
    /** 思考链全文（没有推理段落的模型为空串） */
    val reasoning: String = "",
)

/**
 * OpenAI 兼容协议客户端：baseUrl + apiKey + model 三件套，接任意服务商。
 * 流式输出通过 SSE（data: {...} 行）解析。
 *
 * v0.38.0：
 * - 流式解析抽成 [streamEvents]（Delta / Usage 两类事件）——chat() 只要正文，
 *   ChatViewModel / AgentLoop / 提炼 / 周报可顺带拿 usage 记账；
 * - 支持 vision 输入：用户消息带 imagePath 时经 [buildMessagesJson] 转多段 content
 *   （image_url data URL + 可选 text），是否看得到图由模型能力决定。
 */
class OpenAiClient(
    private val baseUrl: String,   // 例：https://api.deepseek.com/v1
    private val apiKey: String,
    private val model: String,
) {

    /** 正文增量流（兼容旧调用点） */
    fun chat(systemPrompt: String, history: List<ChatMessage>): Flow<String> =
        streamEvents(systemPrompt, history).mapNotNull { (it as? StreamEvent.Delta)?.text }

    /** 全量收集（Agent / 提炼 / 周报 / 主动关心等非流式调用点），顺带返回 usage 与思考链 */
    suspend fun collectReply(
        systemPrompt: String,
        history: List<ChatMessage>,
        imageDataUrl: suspend (String) -> String? = { null },
    ): CollectedReply {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var usage: Int? = null
        streamEvents(systemPrompt, history, imageDataUrl).collect { ev ->
            when (ev) {
                is StreamEvent.Delta -> text.append(ev.text)
                is StreamEvent.Reasoning -> reasoning.append(ev.text)
                is StreamEvent.Usage -> usage = ev.tokens
            }
        }
        return CollectedReply(text.toString(), usage, reasoning.toString())
    }

    /** 事件流（公开：ChatViewModel 一边渲染增量一边收 usage 记账） */
    fun streamEvents(
        systemPrompt: String,
        history: List<ChatMessage>,
        imageDataUrl: suspend (String) -> String? = { null },
    ): Flow<StreamEvent> = flow {
        val messages = buildMessagesJson(systemPrompt, history, imageDataUrl)
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
                    // 只接受 String 类型的 content：推理类模型（deepseek-reasoner / GLM-4.5 /
                    // qwen-thinking 等）思考阶段发 "content": null，org.json 会把 JSON null
                    // 读成字面量 "null"，optString 会把这些垃圾逐个 emit 成屏幕上的 nullnull…
                    val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    val delta = json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                    val content = delta?.opt("content")
                    if (content is String && content.isNotEmpty()) emit(StreamEvent.Delta(content))
                    // 思考链（v0.41.4）：字段名各家不一——官方 deepseek / 智谱用 reasoning_content，
                    // 部分 OpenAI 兼容端用 reasoning。同样只认 String，null 阶段一律跳过。
                    val reasoning = delta?.opt("reasoning_content") ?: delta?.opt("reasoning")
                    if (reasoning is String && reasoning.isNotEmpty()) {
                        emit(StreamEvent.Reasoning(reasoning))
                    }
                    // 用量块（部分服务商最后一块带 usage，choices 为空）：有就记账
                    val usage = json.optJSONObject("usage")
                    val tokens = usage?.optInt("total_tokens", 0) ?: 0
                    if (tokens > 0) emit(StreamEvent.Usage(tokens))
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
        }.getOrElse { NetworkStack.describeNetworkFailure(it) }
    }

    companion object {
        /**
         * 组装 messages 数组（纯函数，JVM 可测）：
         * - system + 历史逐条展开；
         * - 用户消息带 imagePath 且 [imageDataUrl] 给得出 data URL → vision 多段 content
         *   （image_url 在前、caption 文本在后；文本为空则只发图）；
         * - 助手消息的 imagePath（AI 生成图）不进请求——图是本地产物，模型只需要当时的文字。
         * - 助手消息的 reasoning（思考链，v0.41.4）**同样不进请求**：它是给用户看的旁白，
         *   回送既白费 token，又会把模型上一轮的旧思路重新灌进上下文带偏本轮（显式标注，
         *   防止以后有人"顺手"把它拼进去）。
         */
        internal suspend fun buildMessagesJson(
            systemPrompt: String,
            history: List<ChatMessage>,
            imageDataUrl: suspend (String) -> String? = { null },
        ): JSONArray = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            history.forEach { m ->
                val role = if (m.isFromUser) "user" else "assistant"
                val dataUrl = if (m.isFromUser && m.imagePath.isNotBlank()) imageDataUrl(m.imagePath) else null
                if (dataUrl != null) {
                    val parts = JSONArray()
                        .put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", dataUrl))
                        )
                    if (m.content.isNotBlank()) {
                        parts.put(JSONObject().put("type", "text").put("text", m.content))
                    }
                    put(JSONObject().put("role", role).put("content", parts))
                } else {
                    put(
                        JSONObject()
                            .put("role", role)
                            .put("content", m.content)
                    )
                }
            }
        }

        /**
         * 全 App 共享一个 OkHttpClient（连接池 / 线程池），不要每次请求都 new。
         * readTimeout = 两次数据间的最大间隔：推理类模型（deepseek-reasoner 等）首 token
         * 前可能长时间无输出，30s 会误报失败，故放宽到 120s；设 0 则服务端停摆时永久挂起。
         *
         * 通过 [NetworkStack.baseBuilder] 起手：HostnameVerifier 放宽以兼容证书 SAN
         * 只写 IP 的服务商，明文 HTTP 由 network_security_config.xml 放开。
         */
        private val http = NetworkStack.baseBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)   // 整次流式的兜底上限
            .build()
    }
}
