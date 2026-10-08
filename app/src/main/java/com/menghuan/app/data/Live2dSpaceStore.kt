package com.menghuan.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 「角色 Live2D 空间」的背景与配置（v0.64.0）。
 *
 * 目录形态：
 *
 *   filesDir/live2d_space/<personaId>/
 *   ├── space.json        ← 用哪个背景（[SpaceConfig]）
 *   └── background/       ← 用户从相册导入的背景图（原图复制进来）
 *
 * 为什么不复用「聊天背景」那套：聊天背景属于**会话外观**，角色空间属于**形象互动**，
 * 两者的生命周期与删除时机都不一样（删人设要连形象素材一起清，聊天背景走的是另一条链路）。
 * 混在一起会让"删人设"这一件事同时要碰两个模块的状态。
 *
 * 存的是**相对文件名**不是绝对路径：用户换机、备份还原、目录改名之后绝对路径全废
 * （同 [CallVisualStore] 的口径）。
 */
object Live2dSpaceStore {

    private const val TAG = "Live2dSpaceStore"
    private const val ROOT = "live2d_space"
    private const val CONFIG = "space.json"
    private const val BG_DIR = "background"

    /** 单张背景图上限：手机壁纸量级，超了直接拒（不做压缩，压缩会毁掉用户挑的图） */
    const val MAX_BACKGROUND_BYTES = 24L * 1024 * 1024

    /** 主题底色 —— 背景的默认档，也是任何配置读不出来时的兜底 */
    const val BACKGROUND_THEME = "theme"

    /**
     * 系统预设背景（v0.65.0 用户点名：背景只给两个选项 —— 系统预设 / 自定义背景）。
     *
     * 存成 `builtin:0` 是为了不动老数据：v0.64.0 那四档渐变里存过 1/2/3 的用户，
     * 读出来仍然合法，页面统一画同一张预设（见 `SpaceBackgroundLayer`），不用迁移。
     */
    const val BACKGROUND_PRESET = "builtin:0"

    /** 内置渐变背景的档数（具体配色画在 UI 层，这里只管合法范围与存法） */
    const val BUILTIN_COUNT = 4

    // ---- 配置 ----

    /**
     * 空间配置。目前只有背景一项，但**不把背景写成单个字符串参数** ——
     * 后面要加"角色位置""是否显示栅格"之类的设置时，加一个字段就行，
     * 不用再改一遍读写签名。
     */
    data class SpaceConfig(
        /**
         * 背景标识：
         * - `theme`：老版本的"跟随主题底色"（v0.65.0 起 UI 不再给这一档，页面把它当预设画）
         * - `builtin:<0..3>`：内置渐变，`builtin:0` = 现在的**系统预设**
         * - `file:<文件名>`：用户导入的图片
         */
        val background: String = BACKGROUND_THEME,
    ) {
        val builtinIndex: Int? get() = background.removePrefix("builtin:").toIntOrNull()
            ?.takeIf { background.startsWith("builtin:") && it in 0 until BUILTIN_COUNT }

        val fileName: String? get() =
            background.removePrefix("file:").takeIf { background.startsWith("file:") && it.isNotBlank() }

        val isTheme: Boolean get() = background == BACKGROUND_THEME

        /**
         * 用的是用户自己的图（v0.65.0）。
         *
         * 反过来就是"系统预设"——**theme 也算预设**：老数据里的 `theme` 与
         * `builtin:1..3` 在页面上都落到同一张预设背景，界面上只有「系统预设 / 自定义背景」两档。
         */
        val isCustom: Boolean get() = fileName != null
    }

    fun dir(context: Context, personaId: Long): File =
        File(File(context.filesDir, ROOT), personaId.toString())

    private fun bgDir(context: Context, personaId: Long): File = File(dir(context, personaId), BG_DIR)

    fun read(context: Context, personaId: Long): SpaceConfig = runCatching {
        if (personaId <= 0L) return SpaceConfig()
        val f = File(dir(context, personaId), CONFIG)
        if (!f.isFile) return SpaceConfig()
        val o = JSONObject(f.readText())
        SpaceConfig(background = o.optString("background", BACKGROUND_THEME))
    }.getOrDefault(SpaceConfig())

    fun write(context: Context, personaId: Long, config: SpaceConfig): Boolean = runCatching {
        if (personaId <= 0L) return false
        val d = dir(context, personaId)
        if (!d.exists() && !d.mkdirs()) return false
        File(d, CONFIG).writeText(JSONObject().put("background", config.background).toString())
        true
    }.getOrElse {
        Log.e(TAG, "write space config failed", it)
        false
    }

    /** 背景图真实文件；不存在返回 null（调用方回落到主题底色） */
    fun backgroundFile(context: Context, personaId: Long, config: SpaceConfig): File? =
        config.fileName
            ?.takeIf { !it.contains('/') && !it.contains('\\') }   // 只认一层目录，防越界
            ?.let { File(bgDir(context, personaId), it) }
            ?.takeIf { it.isFile && it.length() > 0L }

    // ---- 导入 / 删除 ----

    /** 背景导入结果：成功带落盘文件名，失败带一句能直接显示给用户的原因 */
    sealed interface BackgroundImport {
        data class Ok(val fileName: String) : BackgroundImport
        data class Failed(val reason: String) : BackgroundImport
    }

    /**
     * 从相册导入一张背景图。
     *
     * 复制进私有目录（不长期持有 `content://`，SAF 授权是会话级的，
     * 冷启动后可能失效 —— 这正是 [CallVisualStore] 当年踩过的坑）。
     * 体积只在**落盘之后**按真实文件大小判：`openInputStream().available()` 在
     * 云盘/SD 卡上的返回是不可信的（经常只有一个缓冲区大小），拿它当判据会误杀正常图片。
     */
    fun importBackground(context: Context, personaId: Long, uri: Uri): BackgroundImport {
        if (personaId <= 0L) return BackgroundImport.Failed("请先保存这个人设")
        val d = bgDir(context, personaId)
        if (!d.exists() && !d.mkdirs()) return BackgroundImport.Failed("创建目录失败")

        val name = sanitize(displayNameOf(context, uri)) + ".jpg"
        // 先删掉上一张：一张就够，不做图库；留着旧的几十 MB 只会变成孤儿
        d.listFiles()?.forEach { if (it.isFile) runCatching { it.delete() } }
        val target = File(d, name)

        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                target.delete()
                return BackgroundImport.Failed("读不到选中的图片")
            }
            when {
                target.length() == 0L -> {
                    target.delete()
                    BackgroundImport.Failed("选中的图片是空的")
                }
                target.length() > MAX_BACKGROUND_BYTES -> {
                    target.delete()
                    BackgroundImport.Failed("这张图超过 ${MAX_BACKGROUND_BYTES / 1048576}MB，换一张小一点的")
                }
                else -> BackgroundImport.Ok(target.name)
            }
        }.getOrElse {
            runCatching { target.delete() }
            Log.e(TAG, "import background failed", it)
            BackgroundImport.Failed("导入失败，换一张图再试")
        }
    }

    fun deleteBackground(context: Context, personaId: Long) {
        runCatching { bgDir(context, personaId).deleteRecursively() }
    }

    /** 人设删除时级联清目录 */
    fun deletePersona(context: Context, personaId: Long) {
        runCatching { dir(context, personaId).deleteRecursively() }
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.CHINA, "%.1fMB", bytes / 1048576f)
        else -> "${bytes / 1024}KB"
    }

    private fun sanitize(raw: String): String =
        raw.substringBeforeLast('.')
            .replace(Regex("[^\\p{L}\\p{Digit}._\\- ]"), "_")
            .trim('.', ' ', '_')
            .take(32)
            .ifBlank { "background" }

    private fun displayNameOf(context: Context, uri: Uri): String = runCatching {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "background"
    }.getOrDefault("background")
}
