package com.menghuan.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 用户发图给 AI 看（v0.38.0 vision 输入）：
 * - 选图先拷进应用私有目录（chat_images/），消息 imagePath 存绝对路径——与 AI 生成图
 *   （filesDir/images/）分目录，清数据时好区分处理；
 * - 发请求时现场降采样转 base64 data URL（原图可能好几 MB，直接塞进 JSON 会爆），
 *   只在内存里压，落盘的始终是原图（用户自己的照片不悄悄变质量）。
 */
object VisionImage {

    private const val MAX_DIM = 1024
    private const val JPEG_QUALITY = 78

    /** 用户发图的落盘目录：应用私有 filesDir/chat_images */
    fun chatImagesDir(appFilesDir: File): File = File(appFilesDir, "chat_images")

    /** 拷贝选中的图到私有目录，返回绝对路径；失败返回 null */
    fun copyToChatImages(context: Context, uri: Uri, appFilesDir: File): String? = runCatching {
        val dir = chatImagesDir(appFilesDir)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, "user_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (file.length() == 0L) return null
        file.absolutePath
    }.getOrNull()

    /**
     * 本地图片 → 降采样 JPEG → `data:image/jpeg;base64,…`（vision 请求用）。
     * 解不出位图返回 null（调用方按纯文本消息发送兜底）。
     *
     * [maxDim]（v0.56.14）：聊天发图默认 1024 够用；**识图记账传 1536**——账单是文字密集图，
     * 长截图（支付详情页高宽比 ≥2:1）压到 1024 后金额数字会糊到模型认不出
     * （真机反馈"识图返回没识别到金额"的嫌疑之一）。
     */
    fun encodeDataUrl(path: String, maxDim: Int = MAX_DIM, quality: Int = JPEG_QUALITY): String? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scaled = scaleDown(bitmap, maxDim)
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
        if (scaled !== bitmap) bitmap.recycle()
        scaled.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
    }
}

/**
 * API token 用量记账（v0.38.0）：按自然月累计 total_tokens，
 * 只有服务商在响应里回了 usage 才记（拿不到的不猜）。
 */
object UsageTracker {

    /** 当前月份键 "yyyy-MM"（纯函数，单测口径） */
    fun monthKey(epochMillis: Long = System.currentTimeMillis()): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
        val month = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
        return "${cal.get(java.util.Calendar.YEAR)}-$month"
    }

    /** token 数 → 展示文本（纯函数）：12345 → "约 1.2 万"，1200000 → "约 120 万" */
    fun formatTokens(tokens: Int): String {
        val us = java.util.Locale.US
        return when {
            tokens >= 1_000_000 -> "约 " + String.format(us, "%.0f", tokens / 1_000_000.0) + " 万"
            tokens >= 10_000 -> "约 " + String.format(us, "%.1f", tokens / 10_000.0) + " 万"
            else -> tokens.toString()
        }
    }

    /**
     * 缓存命中率（纯函数，JVM 单测口径）：命中 ÷ 输入；**输入为 0 返回 null**。
     *
     * ⚠️ null 的含义是"没有读数"（服务商没回 usage），**不是 0%**。
     * 把"没有读数"画成 0% 会推着用户去改一个本来就对的前缀——这条在 v0.69.0 定口径时就为这个吵过。
     */
    fun cacheHitRate(promptTokens: Int, cachedTokens: Int): Double? {
        if (promptTokens <= 0) return null
        val hit = cachedTokens.coerceAtLeast(0).toDouble() / promptTokens
        return hit.coerceIn(0.0, 1.0)
    }

    /** 命中率的一行读数（配合 [cacheHitRate] 的 null 口径：没读数就明说，不编百分比） */
    fun cacheHitLabel(promptTokens: Int, cachedTokens: Int): String {
        val rate = cacheHitRate(promptTokens, cachedTokens)
            ?: return "缓存命中：服务商未返回用量"
        // `kotlin.math.roundToInt` 是扩展函数、不是顶层函数，写成 roundToInt(x) 编译不过（本轮真挂过一次）
        return "缓存命中 " + formatTokens(cachedTokens) + " / " + formatTokens(promptTokens) +
            " tokens（" + kotlin.math.round(rate * 100).toInt() + "%）"
    }

    /**
     * 记一笔：跨月自动重置（月键不匹配即清零重计）。
     *
     * v0.69.0 起顺带记**输入**与其中**缓存命中**两个数：`tokens` 为 0 但带了 prompt/cached
     * 也照样记（有些兼容网关只回这两项），三者全 0 才跳过。
     */
    suspend fun add(
        settingsRepository: SettingsRepository,
        tokens: Int,
        model: String = "",
        promptTokens: Int = 0,
        cachedTokens: Int = 0,
    ) {
        if (tokens <= 0 && promptTokens <= 0 && cachedTokens <= 0) return
        val month = monthKey()
        settingsRepository.addUsageTokens(month, tokens, promptTokens, cachedTokens)
        // v0.56.7：同时记到**这个模型自己**的账上（用户要求"按每个模型分别算各自的用量"）。
        // 调用点都拿得到 ApiConfig.model；拿不到时落到"（未填模型）"这一桶，不丢数。
        if (tokens > 0) settingsRepository.addUsageTokensForModel(month, model, tokens)
    }
}
