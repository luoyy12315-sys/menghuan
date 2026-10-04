package com.menghuan.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导出文件分享：把文本写入 cache/exports/ 后经 FileProvider 以真实文件分享
 * （文件可保存到文件管理器 / 直接发送，比大段文本分享更可用）。
 */
object ShareFiles {

    /** 写入并拉起系统分享；title = 选择器标题，失败静默（返回 false） */
    suspend fun share(
        context: Context,
        fileName: String,
        content: String,
        mime: String = "text/markdown",
        chooserTitle: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            // 同名旧文件直接覆盖；同批多次导出不堆积
            val file = File(dir, fileName)
            file.writeText(content)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, chooserTitle))
            true
        }.getOrDefault(false)
    }
}
