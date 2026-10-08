package com.menghuan.app.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 视频通话背景（v0.66.0，用户点名「视频通话加个通话背景选择」）。
 *
 * 目录形态：`filesDir/call_background/<personaId>/<用户那张图的原名>`，一张就够（换图先删旧的）。
 * 库里（DataStore 的 `call_background` 表）只存**档位串**：
 * - `preset` 或空 = 系统预设（App 给的那张渐变）
 * - `file:<文件名>` = 用户从相册挑的那张
 *
 * 存文件名而不是绝对路径：换机、备份还原、目录改名之后绝对路径全废（同 [CallVisualStore] 的口径）。
 * 文件名沿用用户那张图的原名 —— 编一个 `asset_<时间戳>` 出来，用户在行上看到的就是一串没见过的字。
 *
 * ⚠️ 只有**视频通话**读它（用户口径：语音通话维持现在的页面底色），
 * 画法与角色空间共用同一份（`ui/common/StageBackground.kt`），两处不会各说各话。
 */
object CallBackgroundStore {

    private const val ROOT = "call_background"

    /** 系统预设档；不是 `file:` 开头的都按这一档画（含从没配过的空值） */
    const val PRESET = "preset"

    /** 单张上限：手机壁纸量级，超了直接拒（不做压缩，压缩会毁掉用户挑的图） */
    const val MAX_BYTES = 24L * 1024 * 1024

    /** 这一档是不是用户自己的图 */
    fun isCustom(value: String): Boolean = value.startsWith("file:")

    /** 设置行右侧那句当前选择（`file:壁纸.jpg` → 「壁纸」） */
    fun labelOf(value: String): String =
        if (!isCustom(value)) "系统预设"
        else value.removePrefix("file:").substringBeforeLast('.').ifBlank { "自定义图" }

    fun dir(context: Context, personaId: Long): File =
        File(File(context.filesDir, ROOT), personaId.toString())

    /** 档位里的文件名；不存在或空文件返回 null（页面回落到系统预设） */
    fun backgroundFile(context: Context, personaId: Long, value: String): File? =
        value.removePrefix("file:")
            .takeIf { isCustom(value) && it.isNotBlank() }
            ?.takeIf { !it.contains('/') && !it.contains('\\') }   // 只认一层目录，防越界
            ?.let { File(dir(context, personaId), it) }
            ?.takeIf { it.isFile && it.length() > 0L }

    /** 导入结果：成功带档位串（调用方直接写库），失败带一句能直接显示给用户的原因 */
    sealed interface Import {
        data class Ok(val value: String) : Import
        data class Failed(val reason: String) : Import
    }

    /**
     * 从相册导入一张。复制进私有目录（不长期持有 `content://`：SAF 授权是会话级的，
     * 冷启动后可能失效——这正是 [Live2dSpaceStore] 当年踩过的坑）。
     * 体积只在**落盘之后**按真实文件大小判：`openInputStream().available()` 在云盘 / SD 卡上
     * 的返回不可信（经常只有一个缓冲区大小），拿它当判据会误杀正常图片。
     */
    fun import(context: Context, personaId: Long, uri: Uri): Import {
        if (personaId <= 0L) return Import.Failed("请先保存这个人设")
        val d = dir(context, personaId)
        if (!d.exists() && !d.mkdirs()) return Import.Failed("创建目录失败")

        val name = displayNameOf(context, uri).substringBeforeLast('.')
            .replace(Regex("[^\\p{L}\\p{Digit}._\\- ]"), "_")
            .trim('.', ' ', '_')
            .take(32)
            .ifBlank { "background" } + ".jpg"
        // 先删掉旧的：一张就够，不做图库；留着上一个人的图只会变成孤儿
        d.listFiles()?.forEach { if (it.isFile) runCatching { it.delete() } }
        val target = File(d, name)

        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                target.delete()
                return Import.Failed("读不到选中的图片")
            }
            when {
                target.length() == 0L -> {
                    target.delete()
                    Import.Failed("选中的图片是空的")
                }
                target.length() > MAX_BYTES -> {
                    target.delete()
                    Import.Failed("这张图超过 ${MAX_BYTES / 1048576}MB，换一张小一点的")
                }
                else -> Import.Ok("file:$name")
            }
        }.getOrElse {
            runCatching { target.delete() }
            Import.Failed("导入失败，换一张图再试")
        }
    }

    /** 人设删除时级联清目录（几百 KB～几十 MB 的文件不能变孤儿） */
    fun deletePersona(context: Context, personaId: Long) {
        runCatching { dir(context, personaId).deleteRecursively() }
    }

    private fun displayNameOf(context: Context, uri: Uri): String = runCatching {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "background"
    }.getOrDefault("background")
}
