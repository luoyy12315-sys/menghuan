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

/**
 * 业务级失败（非 2xx 响应）。与网络异常分开，便于按状态码给提示。
 *
 * [body] 是服务端返回的**原始错误体**（v0.56.17 起公开为属性）：调用方要能把服务端原话
 * 带给用户看——方舟的 404 会明确区分"模型不存在"和"你没有权限/未开通"，只回我们自己
 * 的固定话术会把用户引去查本来正确的地址。
 */
class ApiException(val code: Int, val body: String?) :
    IOException("HTTP $code" + (body?.take(200)?.let { "：$it" } ?: ""))

/**
 * 「获取可用模型」的结果（v0.57）：成功给 [ids]（保持服务端顺序、已去重），失败给 [error]。
 */
data class ModelListResult(val ids: List<String> = emptyList(), val error: String? = null)

/**
 * 一次调用的用量读数（纯函数产物，JVM 单测口径）。
 *
 * [cachedPromptTokens] 是**输入里被服务商缓存命中的那部分**。它一直是 §零 里
 * "当前设置会不会多花钱"唯一缺的那块读数：前缀（人设 + 稳定记忆）有没有真的稳定，
 * 只看 total_tokens 永远看不出来。
 */
data class UsageReadout(
    val totalTokens: Int,
    val promptTokens: Int,
    val cachedPromptTokens: Int,
)

/**
 * `usage` 块 → [UsageReadout]；三个数全为 0（或没有 usage 块）时返回 null —— **拿不到的不猜**。
 *
 * cached 的字段名各家不一样，这里按"取第一个非 0"认三种方言，而不是只认 OpenAI 那一种：
 * - `prompt_tokens_details.cached_tokens`（OpenAI / DeepSeek / Moonshot 兼容口径）
 * - `cached_tokens`（个别兼容端把这层摊平在 usage 根上）
 * - `cache_read_input_tokens`（Anthropic 方言；走兼容网关转出来的那一类会用它）
 * 认不出来就是 0，**不编一个看起来合理的数**。
 */
fun parseUsage(usage: JSONObject?): UsageReadout? {
    usage ?: return null
    val total = usage.optInt("total_tokens", 0)
    val prompt = usage.optInt("prompt_tokens", 0)
    val nested = usage.optJSONObject("prompt_tokens_details")?.optInt("cached_tokens", 0) ?: 0
    val cached = when {
        nested > 0 -> nested
        usage.optInt("cached_tokens", 0) > 0 -> usage.optInt("cached_tokens", 0)
        else -> usage.optInt("cache_read_input_tokens", 0)
    }
    if (total <= 0 && prompt <= 0 && cached <= 0) return null
    return UsageReadout(total.coerceAtLeast(0), prompt.coerceAtLeast(0), cached.coerceAtLeast(0))
}

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

    /**
     * 用量统计。后两个字段带默认值，所以老调用点 `StreamEvent.Usage(tokens)` 与
     * `ev.tokens` 全部照旧编译——缓存命中率这一路是**加读数**，不是改协议。
     */
    data class Usage(
        val tokens: Int,
        val promptTokens: Int = 0,
        val cachedPromptTokens: Int = 0,
    ) : StreamEvent
}

/** collectReply 的返回：全量回复文本 + 思考链 + 该次调用的 token 用量（拿不到为 null） */
data class CollectedReply(
    val text: String,
    val usageTokens: Int? = null,
    /** 思考链全文（没有推理段落的模型为空串） */
    val reasoning: String = "",
    /** 输入 token 数（服务商没回 usage 时为 0，不猜） */
    val promptTokens: Int = 0,
    /** 输入里被缓存命中的 token 数（服务商没回时为 0，不猜） */
    val cachedPromptTokens: Int = 0,
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
        runtimeContext: String = "",
        imageDataUrl: suspend (String) -> String? = { null },
    ): CollectedReply {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var usage: Int? = null
        var prompt = 0
        var cached = 0
        streamEvents(systemPrompt, history, runtimeContext, imageDataUrl).collect { ev ->
            when (ev) {
                is StreamEvent.Delta -> text.append(ev.text)
                is StreamEvent.Reasoning -> reasoning.append(ev.text)
                is StreamEvent.Usage -> {
                    usage = ev.tokens
                    prompt = ev.promptTokens
                    cached = ev.cachedPromptTokens
                }
            }
        }
        return CollectedReply(text.toString(), usage, reasoning.toString(), prompt, cached)
    }

    /** 事件流（公开：ChatViewModel 一边渲染增量一边收 usage 记账） */
    fun streamEvents(
        systemPrompt: String,
        history: List<ChatMessage>,
        runtimeContext: String = "",
        imageDataUrl: suspend (String) -> String? = { null },
    ): Flow<StreamEvent> = flow {
        val messages = buildMessagesJson(systemPrompt, history, runtimeContext, imageDataUrl)
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
                    parseUsage(json.optJSONObject("usage"))?.let {
                        emit(StreamEvent.Usage(it.totalTokens, it.promptTokens, it.cachedPromptTokens))
                    }
                }
            }
        } catch (e: CancellationException) {
            call.cancel() // 收集端已取消：中断底层连接，别让阻塞读滞留 IO 线程
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 测试连接：**真的发一条最小对话请求**，成功返回 null，否则返回一句可读原因。
     *
     * ## 为什么不用 `GET {base}/models`（v0.56.17 改掉）
     * 那个端点只证明"Key + 地址能通"，**完全不校验用户填的模型名**。真机踩过：
     * 豆包方舟地址与 Key 都对、`/models` 返回 200 → 测试显示"连接成功"，
     * 但真发消息时因为**该模型没在控制台开通**直接 404
     * （`The model or endpoint … does not exist or you do not have access to it`），
     * 用户被这个"假绿"误导，跑去查本来正确的地址。
     * 生图那边的 [com.menghuan.app.llm.ImageProbe] 一直是"发一条最小真请求"的设计，这里对齐它。
     *
     * **刻意不发 `max_tokens` / `max_completion_tokens`**：各家对新旧字段支持不一致
     * （o 系 / gpt-5 拒 `max_tokens`，部分兼容端点不认 `max_completion_tokens`），
     * 而一条 "hi" 的回复本身就极短，没必要为省这点 token 冒"测试被自己发错字段搞挂"的风险。
     */
    suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("model", model.trim())
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                )
                .put("stream", false)
                .toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${baseUrl.trim().trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            http.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()
                .newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        null
                    } else {
                        // 失败一律把服务端原话带出来：模型不存在/没开通、Key 无权限、
                        // 参数不支持……用户能直接照着这句话去处理
                        val detail = NetworkStack.apiErrorText(runCatching { resp.body?.string() }.getOrNull())
                        "HTTP ${resp.code}" + if (detail != null) "：$detail" else ""
                    }
                }
        }.getOrElse { NetworkStack.describeNetworkFailure(it) }
    }

    /**
     * 拉取该接口的可用模型列表（OpenAI 兼容 `GET {base}/models`），设置页「获取可用模型」用。
     *
     * 为什么值得做：各家的模型 ID 是"全小写 + 连字符 + 日期后缀"那套写法（方舟尤其，
     * 如 `doubao-seed-2-1-lite-260915`），手打必错 —— 真机就是因为手打错模型名，
     * 出现"测试连接成功、聊天 404"。列出官方原文点一下，比让人抄准得多。
     *
     * ⚠️ 各家实现并不统一：有的只回 `data[].id`，有的把数组塞在 `models` / `results` 下，
     * 也有直接回数组的；还有中转网关**根本没实现这个端点**（404）。所以失败一律当作
     * "这个接口不支持列模型"，由 UI 提示手填，**绝不阻塞保存**。
     */
    suspend fun listModels(): ModelListResult = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("${baseUrl.trim().trimEnd('/')}/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            http.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()
                .newCall(request).execute().use { resp ->
                    val text = runCatching { resp.body?.string() }.getOrNull()
                    if (!resp.isSuccessful) {
                        val detail = NetworkStack.apiErrorText(text)
                        ModelListResult(error = "HTTP ${resp.code}" + if (detail != null) "：$detail" else "")
                    } else {
                        val ids = parseModelIds(text)
                        if (ids.isEmpty()) ModelListResult(error = "接口没返回任何模型") else ModelListResult(ids = ids)
                    }
                }
        }.getOrElse { ModelListResult(error = NetworkStack.describeNetworkFailure(it)) }
    }

    /**
     * 从 `/models` 响应里抠出模型 ID 列表（纯函数，JVM 可测）。兼容四种形状：
     * 顶层数组、`data[]`、`models[]`、`results[]` / `list[]`；元素取 `id`（后备 `model`）。
     * 返回保持服务端顺序、已去重。
     */
    internal fun parseModelIds(body: String?): List<String> {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val array = runCatching {
            if (text.startsWith("[")) {
                JSONArray(text)
            } else {
                val json = JSONObject(text)
                listOf("data", "models", "results", "list")
                    .firstNotNullOfOrNull { key -> json.optJSONArray(key) }
            }
        }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val id = when (val item = array.opt(i)) {
                is JSONObject -> item.optString("id").ifBlank { item.optString("model") }
                is String -> item
                else -> ""
            }
            if (id.isNotBlank()) out += id.trim()
        }
        return out.toList()
    }

    companion object {
        /**
         * 易变上下文（当前时间等）的开场标记（照 kiwi-mem `_RUNTIME_CONTEXT_BEGIN` 的措辞口径）。
         *
         * **为什么拼在最后一条用户消息里，而不是再发一条 system / 再插一条 user**：
         * 各家 OpenAI 兼容端点对 `messages` 里 system 能出现在什么位置、能不能连续两条 user，
         * **都不保证** —— 与本项目"不猜外部服务的参数字段名"（`tools` 那条）是同一条纪律。
         * 拼进最新那条用户消息，是所有兼容端都不挑的写法。
         *
         * **为什么这样仍然保得住缓存**：上一轮发出去的请求里，这个块挂在当时最新的那条消息上；
         * 本轮重放历史时那条消息已不是最新、且库里存的是**不带块的原文**，
         * 于是分歧点恰好落在"最后一条消息"这一处 —— 而那一处本来就是要变的。
         * **前缀（system + 其余全部历史）逐字节与上一轮相同**，缓存照样命中。
         * 反过来，把时间写进 system prompt 会让整段前缀每分钟作废一次。
         */
        const val RUNTIME_CONTEXT_MARK = "[以下为系统自动注入的实时上下文，并非用户发送]"

        /**
         * 把易变上下文拼到消息正文前面（纯函数，JVM 单测口径）。
         * [runtimeContext] 为空 = 原文一字不动（**不塞一个空标记进去污染正文**）。
         */
        fun withRuntimeContext(content: String, runtimeContext: String): String {
            val block = runtimeContext.trim()
            if (block.isEmpty()) return content
            val head = "$RUNTIME_CONTEXT_MARK\n$block"
            return if (content.isBlank()) head else "$head\n\n$content"
        }

        /**
         * 组装 messages 数组（纯函数，JVM 可测）：
         * - system + 历史逐条展开；
         * - 用户消息带 imagePath 且 [imageDataUrl] 给得出 data URL → vision 多段 content
         *   （image_url 在前、caption 文本在后；文本为空则只发图）；
         * - 助手消息的 imagePath（AI 生成图）不进请求——图是本地产物，模型只需要当时的文字。
         * - 助手消息的 reasoning（思考链，v0.41.4）**同样不进请求**：它是给用户看的旁白，
         *   回送既白费 token，又会把模型上一轮的旧思路重新灌进上下文带偏本轮（显式标注，
         *   防止以后有人"顺手"把它拼进去）。
         *
         * ⚠️ **[runtimeContext] 排在 [imageDataUrl] 之前**：这个位置是刻意的。
         * 调用点（含既有单测）用的是**尾随 lambda**写法，尾随 lambda 永远绑到最后一个参数；
         * 把新参数加在 suspend lambda 后面，等于把所有尾随调用点的图片编码回调
         * 悄悄换成一个 String —— 编译期报"类型不匹配"还算好事，本轮真机没跑到才发现就晚了。
         */
        internal suspend fun buildMessagesJson(
            systemPrompt: String,
            history: List<ChatMessage>,
            runtimeContext: String = "",
            imageDataUrl: suspend (String) -> String? = { null },
        ): JSONArray = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            // 易变上下文只贴到**最后一条用户消息**上（理由见 RUNTIME_CONTEXT_MARK 注释）
            val lastUserIndex = history.indexOfLast { it.isFromUser }
            history.forEachIndexed { index, m ->
                val role = if (m.isFromUser) "user" else "assistant"
                val content =
                    if (index == lastUserIndex) withRuntimeContext(m.content, runtimeContext) else m.content
                val dataUrl = if (m.isFromUser && m.imagePath.isNotBlank()) imageDataUrl(m.imagePath) else null
                if (dataUrl != null) {
                    val parts = JSONArray()
                        .put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", dataUrl))
                        )
                    if (content.isNotBlank()) {
                        parts.put(JSONObject().put("type", "text").put("text", content))
                    }
                    put(JSONObject().put("role", role).put("content", parts))
                } else {
                    put(
                        JSONObject()
                            .put("role", role)
                            .put("content", content)
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
