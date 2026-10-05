package com.menghuan.app.data

import com.menghuan.app.llm.NetworkStack
import com.menghuan.app.llm.callWithTlsFallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * AI 生图（v0.35.0）。
 *
 * 走 OpenAI 兼容的 `POST {baseUrl}/images/generations`，各家路径不同，
 * 用户在「设置 · API 配置 · 生图模型」里把 baseUrl 填到版本段为止即可。
 *
 * 约定：模型在回复里输出 `[生图:提示词]` 单行标记，ChatViewModel 解析后调这里，
 * 拿到 PNG/JPEG 落盘到本机 filesDir/images/，再把路径写回消息（imagePath 列）。
 * 之所以用标记而不是 function calling：DeepSeek / 智谱 / 通义等兼容端点
 * 对 tools 的支持并不一致，用标记最稳。
 */
class ImageGenerationClient(
    private val baseUrl: String,
    private val apiKey: String,
    /** 生成记录里要写"实际用的哪个模型"（v0.53.4 多模型自动切换），所以是公开的 */
    val model: String,
) {
    fun isConfigured(): Boolean =
        baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** 解析 AI 回复里的 `[生图:…]` 标记（纯函数，可单测） */
    fun findPromptIn(raw: String): String? {
        val start = raw.indexOf(MARK_START)
        if (start < 0) return null
        val end = raw.indexOf(MARK_END, start + MARK_START.length)
        if (end < 0) return null
        return raw.substring(start + MARK_START.length, end).trim().ifBlank { null }
    }

    /** 从回复里剥掉标记，只留要展示的文字（纯函数，可单测） */
    fun stripMarker(raw: String): String {
        val start = raw.indexOf(MARK_START)
        if (start < 0) return raw.trim()
        val end = raw.indexOf(MARK_END, start + MARK_START.length)
        val text = if (end < 0) raw.substring(0, start) else raw.substring(0, start) + raw.substring(end + MARK_END.length)
        return text.trim()
    }

    /**
     * 调生图接口并把图片落盘，返回本机绝对路径；任何失败返回 null（调用方给用户一句提示，不崩）。
     */
    suspend fun generate(prompt: String, dir: File): String? = withContext(Dispatchers.IO) {
        if (!isConfigured() || prompt.isBlank()) return@withContext null
        val body = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
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
                if (!resp.isSuccessful) return@callWithTlsFallback null
                val json = JSONObject(resp.body?.string().orEmpty())
                val url = parseImageUrl(json) ?: return@callWithTlsFallback null
                val bytes = download(url) ?: return@callWithTlsFallback null
                saveToDisk(bytes, dir, prompt)
            }
        }.getOrNull()
    }

    /** 兼容三种常见返回：data[0].url（b64_json 时用本地 base64）、data[0].b64_json */
    private fun parseImageUrl(json: JSONObject): String? {
        val data = json.optJSONArray("data") ?: return null
        val first = data.optJSONObject(0) ?: return null
        first.optString("url").trim().ifBlank { null }?.let { return it }
        val b64 = first.optString("b64_json").trim()
        if (b64.isNotBlank()) return "data:image/png;base64,$b64"
        return null
    }

    /** 下载或解码 base64，返回字节数组 */
    private fun download(url: String): ByteArray? {
        if (url.startsWith("data:")) {
            val comma = url.indexOf(',')
            if (comma < 0) return null
            return runCatching {
                java.util.Base64.getDecoder().decode(url.substring(comma + 1))
            }.getOrNull()
        }
        return runCatching {
            // 图片可能落在服务商的 CDN 上，同样可能握手不兼容，走同一套回退
            http.callWithTlsFallback(Request.Builder().url(url).build()) { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()
    }

    private fun saveToDisk(bytes: ByteArray, dir: File, prompt: String): String? {
        if (bytes.isEmpty()) return null
        if (!dir.exists() && !dir.mkdirs()) return null
        val name = "img_${System.currentTimeMillis()}_${abs(prompt.hashCode()) % 100_000}.png"
        val file = File(dir, name)
        return runCatching {
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    companion object {
        const val MARK_START = "[生图:"
        const val MARK_END = "]"

        /** 落盘目录：应用私有目录，卸载即清理；不进相册不联网 */
        fun imagesDir(appFilesDir: File): File = File(appFilesDir, "images")

        private val http: OkHttpClient by lazy {
            // 走 NetworkStack 共享 HostnameVerifier 策略（同 OpenAiClient）：
            // 生图接口跟对话接口往往是同一家服务商、同样的证书 SAN 问题。
            NetworkStack.baseBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                // 生图普遍比对话慢：给足 180s
                .readTimeout(180, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.MINUTES)
                .build()
        }

        /** 图片消息的「尚未落盘」占位路径（气泡据此显示加载态） */
        const val PENDING_PATH = "__pending__"
    }
}