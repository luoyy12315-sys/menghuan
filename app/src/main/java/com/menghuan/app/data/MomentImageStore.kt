package com.menghuan.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * 动态配图的落盘（v0.76.0）：从相册选中的图拷进 `filesDir/moment_images/`，
 * 库里只存绝对路径（见 [MomentMath.encodeImages]）。
 *
 * ## 为什么必须拷进来而不留相册 Uri
 * 相册 Uri（`content://`）只在**被授予访问权的那一刻起有效**：用户在相册里删了这张图、
 * 把 App 卸了重装、甚至只是某次系统媒体库重建，Uri 读出来就是一片空白格子。
 * 动态是"发出去的内容"，发出去之后不该再受外部文件的影响。
 *
 * 与表情（`filesDir/stickers/`）、录音、通话素材分目录：清空某一类内容时不误伤另一类。
 * 做法与 [StickerStore] 完全一致。**手写 IO 而不用图片加载库**：本项目至今零图片库依赖
 * （聊天/头像同样是自己采样解码），为一个页面引 Coil 不值。
 */
object MomentImageStore {

    /** 动态图的目录（不存在就建） */
    fun imagesDir(context: Context): File =
        File(context.filesDir, "moment_images").apply { if (!exists()) mkdirs() }

    /**
     * 拷一张选中的图进来，返回它的绝对路径；任何一步失败返回 null（调用方不提示、不落半成品）。
     *
     * 文件名 = `m_<毫秒>.<扩展名>`：毫秒保证同一条动态连选多张不重名（同一毫秒内选两张在
     * 真人手速下不可能）；扩展名按图片的真实类型猜（内容可能是 png/webp/jpeg 任一），
     * 猜不出来一律 `.jpg` —— 扩展名只影响"文件管理器里看起来是什么"，解码走的是文件头。
     */
    fun import(context: Context, uri: Uri): String? = runCatching {
        val ext = mimeToExt(context.contentResolver.getType(uri))
        val file = File(imagesDir(context), "m_${System.currentTimeMillis()}$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (file.length() == 0L || !isImage(file)) {
            file.delete()
            return null
        }
        file.absolutePath
    }.getOrNull()

    /** 删动态时把它的图一起删掉（只删本目录下的，防止误删别处的文件） */
    fun deleteAll(context: Context, paths: List<String>) {
        val dir = imagesDir(context).absolutePath
        paths.asSequence()
            .map { File(it) }
            .filter { it.exists() && it.parentFile?.absolutePath == dir }
            .forEach { runCatching { it.delete() } }
    }

    /**
     * 选到的文件是不是**真的解得出图**（有的人把损坏的下载文件塞进相册）。
     * 只解边界（不进内存），几百 KB 的图也不占额外内存。
     */
    private fun isImage(file: File): Boolean = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        opts.outWidth > 0 && opts.outHeight > 0
    }.getOrDefault(false)

    private fun mimeToExt(mime: String?): String = when (mime) {
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "image/gif" -> ".gif"
        "image/jpeg", "image/jpg" -> ".jpg"
        else -> ".jpg"
    }
}
