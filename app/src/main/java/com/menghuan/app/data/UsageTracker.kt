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
     */
    fun encodeDataUrl(path: String): String? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= MAX_DIM) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scaled = scaleDown(bitmap, MAX_DIM)
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
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

    /** 记一笔：跨月自动重置（月键不匹配即清零重计） */
    suspend fun add(settingsRepository: SettingsRepository, tokens: Int) {
        if (tokens <= 0) return
        settingsRepository.addUsageTokens(monthKey(), tokens)
    }
}
