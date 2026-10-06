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
 * 生图模型连通性探测（设置 · API 配置 · 生图模型 · 「测试连接」）。
 *
 * 生图端点只有 POST `/images/generations`，没有 OpenAI 那种 `/models` 可查，
 * 所以这里发一个**故意极简但结构合法**的请求：prompt 短到几乎不耗算力，
 * 但 model 必须填对，否则会被服务商当成非法请求 400 掉，探不到真鉴权状态。
 *
 * **v0.36.2 修复的三大 400 陷阱**：
 * 1. 之前请求体没带 `model`——SiliconFlow / 智谱 CogView / 豆包 Seedream / gpt-image-1
 *    全都强制要求 model，缺了直接 400 `{"error":"model is required"}`。
 * 2. 之前 `size = "256x256"` 是老 DALL-E 2 尺寸，主流服务商基本不支持
 *    （gpt-image-1 只吃 1024x1024/1536x1024/1024x1536/auto；cogview-3 只吃 1024x1024 等几种）。
 * 3. 之前 HTTP 400 时 `else -> "HTTP 400"` 直接扔掉响应体，服务商明明说了具体原因
 *    （"model is required" / "size not supported"）全被吞。现在 4xx 一律尝试解析 body
 *    里的 `error.message`，拼进返回值给用户看。
 *
 * **v0.56.17 起 `size` 一个都不传**（用户真机踩的坑）：原来折中填了 1024x1024，
 * 但豆包 Seedream 5.0 要求**总像素 ≥ 2560x1440=3686400**（官方默认 2048x2048），
 * 1024x1024 直接被 400 拒；反过来改成 2048x2048 又会被 gpt-image-1 / cogview-3 拒
 * ——**没有任何一个尺寸能同时满足各家**，所以这个字段干脆不发，让各家用自己的默认值。
 * 正式生图链路（`ImageGenerationClient`）同一个处理，两边必须一致，
 * 否则会出现"测试连接通过、真生图失败"。
 *
 * 报错提取统一走 [NetworkStack.apiErrorText]（v0.56.17 从本文件提出来共用）。
 *
 * **注意**：现在 probe 会传 model 且不传 size，理论上如果一切合法服务商可能真跑一次生图
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
                    // 404 不一定是地址错——模型不存在/未开通很多服务商也回 404（真机踩过：
                    // 豆包 ark v3 地址是对的，模型 ID 填错被报成"接口路径不存在"误导用户去查地址）。
                    // 所以先读响应体原文，有原文带原文，没有才回落到"检查地址"的固定提示。
                    resp.code == 404 -> {
                        val detail = NetworkStack.apiErrorText(resp.body?.string())
                        if (detail != null) {
                            "HTTP 404：$detail（模型 ID / 接口地址都确认无误时，再检查地址是否填到版本段，如 /v1）"
                        } else {
                            "接口路径不存在（检查地址是否填到版本段，如 /v1）"
                        }
                    }
                    // 4xx：读响应体里的原因，服务商通常会说清楚哪个字段不对
                    // （豆包那句 "size … must be at least 3686400 pixels" 就是这么来的）
                    resp.code in 400..499 -> {
                        val detail = NetworkStack.apiErrorText(resp.body?.string())
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
    }
}
