package com.menghuan.app.mcp

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
import java.io.IOException
import java.util.concurrent.TimeUnit

/** MCP 侧失败：HTTP 层（[httpCode] 非 0）或 JSON-RPC 层（[rpcCode] 非 0），消息可直接给用户看 */
class McpException(message: String, val httpCode: Int = 0, val rpcCode: Int = 0) : IOException(message)

/** MCP 服务器清单里的一个工具（inputSchema 原样保留，直接转 OpenAI parameters 用） */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject?,
    /** annotations.readOnlyHint：true = 只读工具，调用免确认；缺省/false = 调用前必须用户点头 */
    val readOnly: Boolean,
)

/** tools/call 的扁平化结果：文本拼一起（多段 content 按序拼接），isError 原样透出 */
data class McpCallResult(val text: String, val isError: Boolean)

/** initialize 握手结果（服务器名片，设置页「测试」显示用） */
data class McpServerInfo(val name: String, val version: String, val protocolVersion: String)

/**
 * MCP Streamable HTTP 客户端（P3-3，手撸极简版）。
 *
 * ## 为什么不引官方 kotlin-sdk（用户拍板 2026-10-09）
 * 中央仓全部版本（0.8.0 起）都用 Kotlin 2.2+ 编译，本项目工具链锁 Kotlin 2.0.21，
 * 编译器读不了 2.2 的元数据；为它升级 Kotlin 会连带 KSP/Compose 插件，是另一条大工程。
 * MCP 客户端面很窄（initialize / tools/list / tools/call 三个方法 + SSE 响应解析），
 * 用现有 OkHttp + org.json 栈实现 ≈300 行、零新依赖、MockWebServer 可全覆盖。
 *
 * ## 协议要点（Streamable HTTP，spec 2025-06-18）
 * - 一律 POST 单个端点：请求 `Accept: application/json, text/event-stream`；
 * - 响应两种形状都要收：`application/json`（单体结果）或 `text/event-stream`
 *   （SSE 流里捞 id 匹配的那条 JSON-RPC 响应，期间的通知/进度消息跳过）；
 * - initialize 响应头可能带 `Mcp-Session-Id`，之后每个请求都要回带；
 * - 后续请求还要带 `MCP-Protocol-Version` 头（用握手协商出的版本）；
 * - notification（无 id）发出去服务器回 202 空体；
 * - **只支持远程 HTTP，不支持本地 stdio**（计划定死，UI 也这么写）。
 */
class McpClient(
    private val url: String,
    private val token: String?,
    /** 客户端名片（握手时自报家门） */
    private val clientName: String = "menghuan",
    private val clientVersion: String = "1.0.0",
    /** 可注入：单测传 MockWebServer 场景下的默认实例即可，生产走 [NetworkStack.baseBuilder] */
    http: OkHttpClient? = null,
) {

    private val http: OkHttpClient = http ?: NetworkStack.baseBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 工具调用可能很慢（服务器端批处理），与 OpenAiClient 同一档：读 120s / 整次 5 分钟
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    private var sessionId: String? = null
    private var protocolVersion: String = PROTOCOL_VERSION
    private var nextId = 1

    /** 是否已完成握手（listTools/callTool 前必须 initialize） */
    var initialized = false
        private set

    /** 握手 + 发 initialized 通知；返回服务器名片。失败抛 [McpException] */
    suspend fun initialize(): McpServerInfo = withContext(Dispatchers.IO) {
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put(
                "clientInfo",
                JSONObject().put("name", clientName).put("version", clientVersion),
            )
        val (result, responseSession) = rpc(RPC_INITIALIZE, params)
        if (!responseSession.isNullOrBlank()) sessionId = responseSession
        // 服务器可以协商出别的协议版本；没回就维持我们提议的版本
        protocolVersion = result?.optString("protocolVersion")?.takeIf { it.isNotBlank() }
            ?: PROTOCOL_VERSION
        initialized = true
        // notifications/initialized：无 id 的通知，服务器回 202 空体
        notify(RPC_INITIALIZED)
        val info = result?.optJSONObject("serverInfo")
        McpServerInfo(
            name = info?.optString("name").orEmpty(),
            version = info?.optString("version").orEmpty(),
            protocolVersion = protocolVersion,
        )
    }

    /** 全量工具清单（自动翻 nextCursor 页；服务器没声明分页就一轮拿完） */
    suspend fun listTools(): List<McpTool> = withContext(Dispatchers.IO) {
        checkInitialized()
        val out = ArrayList<McpTool>()
        var cursor: String? = null
        do {
            val params = JSONObject()
            if (cursor != null) params.put("cursor", cursor)
            val (result, _) = rpc(RPC_TOOLS_LIST, params)
            val resultObj = result ?: throw McpException("tools/list 没有返回结果")
            val tools = resultObj.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until tools.length()) {
                val t = tools.optJSONObject(i) ?: continue
                val name = t.optString("name").trim()
                if (name.isEmpty()) continue
                out += McpTool(
                    name = name,
                    description = t.optString("description").trim(),
                    inputSchema = t.optJSONObject("inputSchema"),
                    readOnly = t.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) == true,
                )
            }
            cursor = resultObj.optString("nextCursor").takeIf { it.isNotBlank() }
        } while (cursor != null)
        out
    }

    /** 调用工具。参数是模型生成的原始 JSON 串——解析失败按调用失败回给模型，不让它崩链路 */
    suspend fun callTool(name: String, argumentsJson: String): McpCallResult = withContext(Dispatchers.IO) {
        checkInitialized()
        val arguments = runCatching {
            if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
        }.getOrElse {
            return@withContext McpCallResult("工具参数不是合法 JSON：${it.message}", isError = true)
        }
        val params = JSONObject().put("name", name).put("arguments", arguments)
        val (result, _) = rpc(RPC_TOOLS_CALL, params)
        val resultObj = result ?: throw McpException("tools/call 没有返回结果")
        val content = resultObj.optJSONArray("content")
        val text = StringBuilder()
        if (content != null) {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                // 只扁平化 text 段；image/audio/resource 等二进制段留个占位说明，不塞 base64 给模型
                when (part.optString("type")) {
                    "text" -> {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(part.optString("text"))
                    }
                    else -> {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append("[${part.optString("type").ifBlank { "未知" }} 类型内容，无法在对话中展示]")
                    }
                }
            }
        }
        McpCallResult(
            text = text.toString().ifBlank { "（工具没有返回文本内容）" },
            isError = resultObj.optBoolean("isError", false),
        )
    }

    /** 设置页「测试连接」：握手成功即通。返回 null = 通，否则一句可读原因 */
    suspend fun probe(): String? = runCatching { initialize(); null }
        .getOrElse { it.message ?: it.javaClass.simpleName }

    /** 关闭会话（有 session 就 DELETE 告知服务器，尽力而为，失败不抛） */
    suspend fun close() = withContext(Dispatchers.IO) {
        val sid = sessionId ?: return@withContext
        runCatching {
            val request = baseRequest()
                .delete()
                .addHeader("Mcp-Session-Id", sid)
                .build()
            http.newCall(request).execute().close()
        }
        sessionId = null
        initialized = false
    }

    // ---- 协议骨架 ----

    private fun checkInitialized() {
        if (!initialized) throw McpException("MCP 客户端尚未 initialize")
    }

    /** 发通知（无 id，不回结果；202 空体即成功） */
    private fun notify(method: String) {
        val payload = JSONObject().put("jsonrpc", "2.0").put("method", method)
        post(payload) { /* 202 无体直接忽略 */ null }
    }

    /**
     * 发一个 JSON-RPC 请求并取响应。返回 (result, 响应头里的 sessionId)；
     * result 为 null 只可能发生在通知路径，正常请求拿不到结果一律抛。
     */
    private fun rpc(method: String, params: JSONObject): Pair<JSONObject?, String?> {
        val id = nextId++
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        return post(payload) { resp ->
            val session = resp.header("Mcp-Session-Id")
            val body = resp.body?.string().orEmpty()
            val message = extractResponse(resp.header("Content-Type").orEmpty(), body, id)
                ?: throw McpException("服务器没有返回 id=$id 的响应")
            val error = message.optJSONObject("error")
            if (error != null) {
                throw McpException(
                    "MCP 错误 ${error.optInt("code")}：${error.optString("message")}",
                    rpcCode = error.optInt("code"),
                )
            }
            message.optJSONObject("result") to session
        }
    }

    /** 统一 POST：鉴权头 / 会话头 / 协议版本头在这里集中加；非 2xx 翻译成 [McpException] */
    private fun <T> post(payload: JSONObject, handler: (Response) -> T): T {
        val request = baseRequest()
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { resp ->
            // 通知的 202 与正常 200 都算成功；其余非 2xx 带服务端原话抛出去
            if (!resp.isSuccessful) {
                val detail = NetworkStack.apiErrorText(runCatching { resp.body?.string() }.getOrNull())
                throw McpException(
                    "HTTP ${resp.code}" + if (detail != null) "：$detail" else "",
                    httpCode = resp.code,
                )
            }
            handler(resp)
        }
    }

    private fun baseRequest(): Request.Builder {
        val b = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json, text/event-stream")
        if (!token.isNullOrBlank()) b.addHeader("Authorization", "Bearer ${token.trim()}")
        sessionId?.let { b.addHeader("Mcp-Session-Id", it) }
        if (initialized) b.addHeader("MCP-Protocol-Version", protocolVersion)
        return b
    }

    companion object {
        /** 我们提议的协议版本：2025-06-18 是 Streamable HTTP 定型版，现役服务器普遍支持 */
        const val PROTOCOL_VERSION = "2025-06-18"

        private const val RPC_INITIALIZE = "initialize"
        private const val RPC_INITIALIZED = "notifications/initialized"
        private const val RPC_TOOLS_LIST = "tools/list"
        private const val RPC_TOOLS_CALL = "tools/call"

        /**
         * 从响应体里捞出 id 匹配的那条 JSON-RPC 消息（纯函数，JVM 可测）：
         * - `application/json`：整个 body 就是（个别服务器回数组，遍历找）；
         * - `text/event-stream`：按 SSE 事件块解析（空行分块、data: 行拼接），
         *   逐块找 id 匹配的响应，期间的通知/进度消息跳过；
         * - 空体（202）→ null。
         */
        internal fun extractResponse(contentType: String, body: String, id: Int): JSONObject? {
            if (body.isBlank()) return null
            if (contentType.startsWith("text/event-stream")) {
                var dataLines = StringBuilder()
                fun flushEvent(): JSONObject? {
                    val data = dataLines.toString().trim()
                    dataLines = StringBuilder()
                    if (data.isEmpty()) return null
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: return null
                    return if (json.opt("id")?.toString() == id.toString()) json else null
                }
                body.lineSequence().forEach { line ->
                    when {
                        line.startsWith("data:") -> {
                            if (dataLines.isNotEmpty()) dataLines.append('\n')
                            dataLines.append(line.removePrefix("data:").trim())
                        }
                        line.isBlank() -> flushEvent()?.let { return it }
                        else -> Unit // event: / id: / retry: 行不需要
                    }
                }
                return flushEvent()
            }
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return null
                for (i in 0 until arr.length()) {
                    val json = arr.optJSONObject(i) ?: continue
                    if (json.opt("id")?.toString() == id.toString()) return json
                }
                return null
            }
            val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            return if (json.opt("id")?.toString() == id.toString()) json else null
        }
    }
}
