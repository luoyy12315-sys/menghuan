package com.menghuan.app.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 用户自定义表情（v0.43.0）：从相册选图导入到 filesDir/stickers/，
 * 库里只存路径（表见 [com.menghuan.app.data.model.Sticker]）。
 *
 * 与聊天图片（chat_images/）分目录：表情是"素材库"，聊天图是"会话内容"，
 * 清空聊天记录时不该把表情一起带走。
 */
object StickerStore {

    fun stickersDir(context: Context): File =
        File(context.filesDir, "stickers").apply { if (!exists()) mkdirs() }

    /**
     * 拷贝相册选中的图到表情目录。用时间戳命名避免重名，
     * 返回绝对路径；读取失败返回 null。
     */
    fun import(context: Context, uri: Uri): String? = runCatching {
        val dir = stickersDir(context)
        val file = File(dir, "sticker_${System.currentTimeMillis()}.png")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        if (file.length() == 0L) {
            file.delete()
            return null
        }
        file.absolutePath
    }.getOrNull()

    /** 删除表情文件（同时由仓库删库表行） */
    fun deleteFile(path: String) {
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }
}
