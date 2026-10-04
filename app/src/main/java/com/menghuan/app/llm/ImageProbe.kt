package com.menghuan.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 生图接口连通性探测（设置 · API 配置 · 生图接口 · 「测试连接」）。
 *
 * 生图端点只有 POST `/images/generations`，没有 OpenAI 那种 `/models` 可查，
 * 所以这里发一个**故意极简但结构合法**的请求：prompt 短到几乎不耗算力，
 * 但 model / size 都得填对，否则会被服务商当成非法请求 400 掉，探不到真鉴权状态。
 *
 * **v0.36.2 修复的三大 400 陷阱**：
 * 1. 之前请求体没带 `model`——SiliconFlow / 智谱 CogView / 豆包 Seedream / gpt-image-1
 *    全都强制要求 model，缺了直接 400 `{"error":"model is required"}`。
 * 2. 之前 `size = "256x256"` 是老 DALL-E 2 尺寸，主流服务商基本不支持
 *    （gpt-image-1 只吃 1024x1024/1536x1024/1024x1536/auto；cogview-3 只吃 1024x1024 等几种）。
 *    改为 **1024x1024**——业内支持最广的尺寸。
 * 3. 之前 HTTP 400 时 `else -> "HTTP 400"` 直接扔掉响应体，服务商明明说了具体原因
 *    （"model is required" / "size not supported"）全被吞。现在 4xx 一律尝试解析 body
 *    里的 `error.message`，拼进返回值给用户看。
 *
 * **注意**：现在 probe 会传 model 且 size=1024x1024，理论上如果一切合法服务商可能真跑一次生图
 * 消耗额度。但绝大多数服务商在鉴权通过前会先做参数校验，实际不会真跑；且我们只在用户主动
 * 点「测试连接」时触发，不是后台常驻探测。
 */
class ImageProbe(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
) {
    /**
     * 成功返回 null，失败返回一句可读原因。
     *
     * **必须走 [Dispatchers.IO]**：调用点是 Compose 的 `rememberCoroutineScope()`（Main 线程），
     * 而 OkHttp `execute()` 是阻塞调用，主线程会抛 `NetworkOnMainThreadException`——
     * 该异常 `message` 为 null，会被外层兜底吞掉真错误。v0.35.3 修复。
     */
    suspend fun probe(): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("model", model.trim())
            .put("prompt", "a dot")
            .put("n", 1)
            .put("size", "1024x1024")
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${baseUrl.trim().trimEnd('/')}/images/generations")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        runCatching {
            // v0.36.3：握手被服务端掐断时自动降级 TLS1.2+http1.1 重试（见 callWithTlsFallback）
            http.callWithTlsFallback(request) { resp ->
                when {
                    resp.isSuccessful -> null
                    resp.code == 401 || resp.code == 403 -> "API Key 无效或无权限"
                    resp.code == 404 -> "接口路径不存在（检查地址是否填到版本段，如 /v1）"
                    // 4xx：读响应体里的 error.message，服务商通常会说清楚哪个字段不对
                    resp.code in 400..499 -> {
                        val detail = extractErrorMessage(resp.body?.string())
                        if (detail != null) "HTTP ${resp.code}：$detail" else "HTTP ${resp.code}"
                    }
                    else -> "HTTP ${resp.code}"
                }
            }
        }.getOrElse { NetworkStack.describeNetworkFailure(it) }
    }

    private companion object {
        val http: OkHttpClient by lazy {
            // 与 OpenAiClient / ImageGenerationClient 同一套 HostnameVerifier 策略：
            // 探活成功但正式调用失败、或反过来的诡异现象，都是三处配置漂移的信号。
            NetworkStack.baseBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * 从错误响应体里挖一句可读原因。兼容三种常见形态：
         * - OpenAI 风格：`{"error":{"message":"...","type":"..."}}`
         * - 扁平：`{"message":"..."}` 或 `{"error":"..."}`
         * - 纯文本：直接取前 200 字
         */
        fun extractErrorMessage(body: String?): String? {
            if (body.isNullOrBlank()) return null
            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) return trimmed.take(200).ifBlank { null }
            return runCatching {
                val root = JSONObject(trimmed)
                val err = root.opt("error")
                val msg = when {
                    err is JSONObject -> err.optString("message").ifBlank { err.optString("code") }
                    err is String -> err
                    else -> root.optString("message").ifBlank { root.optString("detail") }
                }
                msg.trim().take(200).ifBlank { null }
            }.getOrNull() ?: trimmed.take(200)
        }
    }
}
