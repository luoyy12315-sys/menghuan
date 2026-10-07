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
 * 一次生图尝试的结果（v0.56.17）。
 *
 * 以前 `generate()` 失败只 `return null`，服务端原话（例如豆包那句
 * "The parameter `size` specified in the request is not valid: image size must be at least
 * 3686400 pixels"）被整个吞掉，用户只看到"生图失败：模型未配置或调用失败"，无从下手。
 * 本项目在 ImageProbe 的 404、记账识图上已经因为同一毛病踩过三次，这次一并收口。
 *
 * [note]（v0.58.1）= **图成功了但没完全按预期成功**要告诉用户的话：
 * 目前只有一种——带了立绘参考图、这家服务不认，于是自动退回纯文生图出了图。
 * 这种"结果对但条件没生效"的情况绝不能闷声，否则用户会以为那张像 TA。
 */
data class ImageGenResult(
    val path: String? = null,
    val error: String? = null,
    val note: String? = null,
)

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
     * 调生图接口并把图片落盘，返回 [ImageGenResult]：成功带路径，失败带**给用户看的**原因
     * （服务端原话优先，见 [NetworkStack.apiErrorText]）。[refImage] = 角色立绘（v0.58.1，图生图参考图）。
     *
     * ## 为什么不再传 `size`（v0.56.17）
     * 这里原来写死 `"size": "1024x1024"`，真机上豆包 Seedream 5.0 直接 400：
     * 官方要求**总像素 ≥ 2560x1440=3686400**（默认 2048x2048），1024x1024 只有 1/3.5；
     * 而改成 2048x2048 又会被 OpenAI gpt-image-1（只吃 1024/1536 那几档）、
     * 智谱 cogview-3（只吃 1024x1024 等）拒 —— **没有任何一个尺寸能满足所有家**。
     * 所以这个字段干脆不发，让各家用自己的默认值（豆包 2048x2048、OpenAI auto、…）。
     * ⚠️ 「测试连接」的 [ImageProbe] 必须与这里保持一致，否则会出现"测试通过、真生图失败"。
     *
     * ## 参考图怎么发、发不出去怎么办（v0.58.1）
     * 各家 OpenAI 兼容网关的 img2img **字段名不统一**（`image` / `images` / `init_image` /
     * `ref_image`…），而且相当一部分压根没实现。本项目按 AGENTS §零.6 的规矩**不猜参数名**：
     * 只发国内网关最通用的一种写法（`"image": ["data:image/png;base64,…"]`），
     * 一旦被服务端判错，就**自动去掉参考图重试一次**——
     * 出图成功则在 [ImageGenResult.note] 里明说"这张没按立绘生成"，两次都失败就把两句原话都带回去。
     * 这样"支持参考图的家用得上、不支持的家最多回到原来的行为"，不会因为这个新功能把生图整体搞挂。
     */
    suspend fun generate(prompt: String, dir: File, refImage: File? = null): ImageGenResult =
        withContext(Dispatchers.IO) {
            if (!isConfigured() || prompt.isBlank()) {
                return@withContext ImageGenResult(error = "配置不完整（缺接口地址 / Key / 模型）")
            }
            val dataUrl = refImage?.let { referenceDataUrl(it) }
            if (refImage != null && dataUrl == null) {
                // 立绘在、但读不出来或超过上限：不许"悄悄当没这张图"就出图，
                // 那会让用户以为画的是 TA
                return@withContext ImageGenResult(
                    error = "立绘读不出来或超过 $MAX_REF_MB MB，这次没生成（换一张小一点的图试试）",
                )
            }
            val first = post(prompt, dir, dataUrl)
            if (dataUrl == null || first.path != null) return@withContext first
            val second = post(prompt, dir, null)
            if (second.path != null) {
                ImageGenResult(
                    path = second.path,
                    note = "这家生图服务不认参考图（${first.error.orEmpty().take(90)}），" +
                        "这张没按立绘生成",
                )
            } else {
                ImageGenResult(
                    error = "带立绘：${first.error ?: "失败"}；不带立绘：${second.error ?: "失败"}",
                )
            }
        }

    /** 一次 HTTP 尝试。[refDataUrl] 非空才带 `image` 字段（[ImageProbe] 探活走的是不带的那条） */
    private suspend fun post(prompt: String, dir: File, refDataUrl: String?): ImageGenResult {
        val body = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("n", 1)
            .apply {
                if (refDataUrl != null) put("image", org.json.JSONArray().put(refDataUrl))
            }
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${baseUrl.trim().trimEnd('/')}/images/generations")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        return runCatching {
            // v0.36.3：握手被服务端掐断时自动降级 TLS1.2+http1.1 重试（见 callWithTlsFallback）
            http.callWithTlsFallback(request) { resp ->
                if (!resp.isSuccessful) {
                    // 失败一律把服务端原话带回去（豆包/智谱/OpenAI 都会说清哪个参数不对、
                    // 模型是否开通），别再用"调用失败"四个字让用户猜
                    val detail = NetworkStack.apiErrorText(runCatching { resp.body?.string() }.getOrNull())
                    return@callWithTlsFallback ImageGenResult(
                        error = "HTTP ${resp.code}" + if (detail != null) "：$detail" else "",
                    )
                }
                val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
                    ?: return@callWithTlsFallback ImageGenResult(error = "响应不是合法 JSON")
                val url = parseImageUrl(json)
                    ?: return@callWithTlsFallback ImageGenResult(error = "响应里没有图片数据（data[0].url / b64_json）")
                val bytes = download(url)
                    ?: return@callWithTlsFallback ImageGenResult(error = "图片下载或 base64 解码失败")
                val saved = saveToDisk(bytes, dir, prompt)
                    ?: return@callWithTlsFallback ImageGenResult(error = "图片写入本机失败")
                ImageGenResult(path = saved)
            }
        }.getOrElse { ImageGenResult(error = NetworkStack.describeNetworkFailure(it)) }
    }

    /**
     * 立绘 → `data:image/…;base64,…`。读不出、超过 [MAX_REF_MB]、或不是可识别的图片格式时返回 null。
     *
     * 上限是**故意压低**的：base64 比原文件大 ~33%，一张 20MB 的立绘塞进 JSON 里，
     * 轻则超时、重被网关按 body size 拒掉，重到把整个生图请求打死。
     * 真机上要传大图应先压一遍再说，这里不静默放大限制。
     */
    private fun referenceDataUrl(file: File): String? = runCatching {
        if (!file.isFile || file.length() <= 0L) return null
        if (file.length() > MAX_REF_MB * 1024 * 1024) return null
        val mime = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> return null
        }
        "$mime;base64," + java.util.Base64.getEncoder().encodeToString(file.readBytes())
    }.getOrNull()

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

        /**
         * 参考图（立绘）的原图大小上限。base64 会让请求体再涨约 1/3，
         * 压到 8MB 是为了不被网关按 body size 拒掉——宁可不带也不静默发一个超大包。
         */
        const val MAX_REF_MB = 8

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