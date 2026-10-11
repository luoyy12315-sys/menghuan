package com.menghuan.app.data

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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
    /** 背景视频目录（v0.74.0）：与图片分开放，删一类不碰另一类 */
    private const val BG_VIDEO_DIR = "background_video"

    /** 单张背景图上限：手机壁纸量级，超了直接拒（不做压缩，压缩会毁掉用户挑的图） */
    const val MAX_BACKGROUND_BYTES = 24L * 1024 * 1024

    /**
     * 背景视频上限（v0.74.0）：**比图片宽得多但不无限**。
     *
     * 80MB 取自 [CallVisualLimits.MAX_VIDEO_BYTES]（伪视频素材那套已定的口径）——
     * 同一款 App 里"用户导入的视频"不该有两个上限。30 秒 1080p 大约 20–40MB，
     * 够用；再大用户是在拿它当片库，不是在挑一张背景。
     */
    const val MAX_BACKGROUND_VIDEO_BYTES = 80L * 1024 * 1024

    /** 背景视频最长时长（秒）：超了拒 —— 背景是循环用的，一段超长视频没有意义 */
    const val MAX_BACKGROUND_VIDEO_SECONDS = 30

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
         * - `video:<文件名>`：用户导入的视频（v0.74.0）
         */
        val background: String = BACKGROUND_THEME,
    ) {
        val builtinIndex: Int? get() = background.removePrefix("builtin:").toIntOrNull()
            ?.takeIf { background.startsWith("builtin:") && it in 0 until BUILTIN_COUNT }

        val fileName: String? get() =
            background.removePrefix("file:").takeIf { background.startsWith("file:") && it.isNotBlank() }

        /** 导入的视频文件名（v0.74.0）；null = 不是视频档 */
        val videoName: String? get() =
            background.removePrefix("video:").takeIf { background.startsWith("video:") && it.isNotBlank() }

        val isTheme: Boolean get() = background == BACKGROUND_THEME

        /**
         * 用的是用户自己的素材（v0.65.0 图片，v0.74.0 起视频也算）。
         *
         * 反过来就是"系统预设"——**theme 也算预设**：老数据里的 `theme` 与
         * `builtin:1..3` 在页面上都落到同一张预设背景。
         */
        val isCustom: Boolean get() = fileName != null || videoName != null

        /** 视频档（与 [isCustom] 的区别：UI 弹层要分开显示"图片背景/视频背景"两行） */
        val isVideo: Boolean get() = videoName != null
    }

    fun dir(context: Context, personaId: Long): File =
        File(File(context.filesDir, ROOT), personaId.toString())

    private fun bgDir(context: Context, personaId: Long): File = File(dir(context, personaId), BG_DIR)

    private fun bgVideoDir(context: Context, personaId: Long): File =
        File(dir(context, personaId), BG_VIDEO_DIR)

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

    /**
     * 背景视频真实文件（v0.74.0）；不存在返回 null ——
     * 调用方回落到预设，与 [backgroundFile] 同一条兜底链。
     */
    fun backgroundVideoFile(context: Context, personaId: Long, config: SpaceConfig): File? =
        config.videoName
            ?.takeIf { !it.contains('/') && !it.contains('\\') }
            ?.let { File(bgVideoDir(context, personaId), it) }
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

    /**
     * 从相册导入一段背景视频（v0.74.0）。
     *
     * 与 [importBackground] 同一条路子（复制进私有目录、落盘后按真实大小判），
     * 外加**时长上限**与**能不能解码**两道 —— 背景是循环播的，一段 5 分钟的视频
     * 既没意义又占地方；而 HEVC / 坏文件的坑与 [CallVisualStore.importVideo] 完全一样，
     * 拖到页面才暴露就是"背景黑了但没任何提示"，所以在这里当场拒掉并说明原因。
     */
    fun importBackgroundVideo(context: Context, personaId: Long, uri: Uri): BackgroundImport {
        if (personaId <= 0L) return BackgroundImport.Failed("请先保存这个人设")
        val d = bgVideoDir(context, personaId)
        if (!d.exists() && !d.mkdirs()) return BackgroundImport.Failed("创建目录失败")

        // 只留一段：背景同时只用一条，留着旧的几十 MB 只会变成孤儿
        d.listFiles()?.forEach { if (it.isFile) runCatching { it.delete() } }
        val target = File(d, sanitize(displayNameOf(context, uri)) + ".mp4")

        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                target.delete()
                return BackgroundImport.Failed("读不到选中的视频")
            }
            when {
                target.length() == 0L -> {
                    target.delete()
                    BackgroundImport.Failed("选中的视频是空的")
                }
                target.length() > MAX_BACKGROUND_VIDEO_BYTES -> {
                    target.delete()
                    BackgroundImport.Failed("这段视频超过 ${MAX_BACKGROUND_VIDEO_BYTES / 1048576}MB，换短一点的")
                }
                else -> probeBackgroundVideo(target)?.let { bad ->
                    target.delete()
                    BackgroundImport.Failed(bad)
                } ?: BackgroundImport.Ok(target.name)
            }
        }.getOrElse {
            runCatching { target.delete() }
            Log.e(TAG, "import background video failed", it)
            BackgroundImport.Failed("导入失败，换一段视频再试")
        }
    }

    /**
     * 能不能当背景视频播。返回 null = 没问题；非 null = 拒绝理由。
     *
     * 与 [CallVisualStore.probeVideo] 同一套口径（时长可读 → 有视频画面轨 → 这机器有解码器），
     * 只多一条**时长上限**：背景循环播，超过 [MAX_BACKGROUND_VIDEO_SECONDS] 秒的没有意义。
     */
    private fun probeBackgroundVideo(file: File): String? = runCatching {
        var durationMs = 0L
        runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally {
                runCatching { mmr.release() }
            }
        }
        if (durationMs <= 0L) return "视频时长读不出来，文件可能已损坏"
        if (durationMs > MAX_BACKGROUND_VIDEO_SECONDS * 1000L) {
            return "这段视频超过 ${MAX_BACKGROUND_VIDEO_SECONDS} 秒，剪短一点再试"
        }
        val track = videoTrackOf(file) ?: return "这个文件里没有视频画面，只有声音"
        val mime = track.getString(MediaFormat.KEY_MIME).orEmpty()
        if (!decoderSupports(track)) {
            return "这台机器播不了「$mime」这种编码，请换 H.264 的 mp4"
        }
        null
    }.getOrElse {
        Log.e(TAG, "probe background video failed", it)
        "视频解不开，可能已损坏或格式不受支持"
    }

    private fun videoTrackOf(file: File): MediaFormat? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(file.absolutePath)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) return@runCatching f
            }
            null
        } finally {
            runCatching { ex.release() }
        }
    }.getOrNull()

    private fun decoderSupports(format: MediaFormat): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            ?.isNotBlank() == true
    }.getOrDefault(false)

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
