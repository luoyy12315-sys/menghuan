package com.menghuan.app.data

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 通话视觉形象的本地资源仓库（v0.58.0）。
 *
 * 目录形态（方案文档 §十八）：
 *
 *   filesDir/call_visuals/<personaId>/
 *   ├── metadata.json     ← [CallVisualManifestCodec] 那份配置
 *   ├── video/            ← 伪视频素材（导入时复制进来的 mp4）
 *   └── live2d/           ← Live2D 模型包（安全解压后的原样结构）
 *
 * **只认私有目录里的文件，不长期持有 `content://` URI**：SAF 的读授权是会话级的，
 * 冷启动后 URI 可能失效，通话页正播的东西突然读不到是最难查的故障。
 * 所以导入一律复制进来（同 StickerStore / 自定义头像的做法）。
 *
 * 这里的 `metadata.json` 与 `personas.videoVisualType` 是**两份真相**，口径是：
 * 列只回答"这个人设想用哪种视觉"，文件目录回答"具体用哪些素材、准备好了没有"。
 * 两边不一致（比如目录被清理过）时以文件为准，通话页降级成无视觉，不报错。
 */
object CallVisualStore {

    private const val TAG = "CallVisualStore"
    private const val ROOT = "call_visuals"
    private const val METADATA = "metadata.json"
    private const val VIDEO_DIR = "video"
    private const val LIVE2D_DIR = "live2d"
    private const val STAGING_PREFIX = ".staging_live2d_"

    /** 单条 zip 文件的解压上限（防 zip bomb：压缩比极高的包） */
    private const val MAX_ENTRY_BYTES = 200L * 1024 * 1024

    /** `video/` 里的一条素材，导入界面用 */
    data class VideoClip(
        val name: String,
        val displayName: String,
        val bytes: Long,
        val durationMs: Long,
        val width: Int,
        val height: Int,
    )

    /** 导入结果统一形状：成功带相对路径，失败带一句能直接显示给用户的原因 */
    data class ImportOutcome(
        val ok: Boolean,
        val relativePath: String = "",
        val message: String = "",
    )

    // ---- 目录 ----

    fun personaDir(context: Context, personaId: Long): File =
        File(File(context.filesDir, ROOT), personaId.toString())

    private fun videoDir(context: Context, personaId: Long): File =
        File(personaDir(context, personaId), VIDEO_DIR)

    private fun live2dDir(context: Context, personaId: Long): File =
        File(personaDir(context, personaId), LIVE2D_DIR)

    private fun metadataFile(context: Context, personaId: Long): File =
        File(personaDir(context, personaId), METADATA)

    /** 人设删除时级联清目录（没有别的引用方，整个目录可以直接消失） */
    fun deletePersona(context: Context, personaId: Long) {
        runCatching { personaDir(context, personaId).deleteRecursively() }
    }

    // ---- metadata 读写 ----

    fun read(context: Context, personaId: Long): CallVisualAsset = runCatching {
        if (personaId <= 0L) return CallVisualAsset.EMPTY
        val file = metadataFile(context, personaId)
        if (!file.exists()) return CallVisualAsset.EMPTY
        CallVisualManifestCodec.decode(runCatching { file.readText() }.getOrNull())
    }.getOrDefault(CallVisualAsset.EMPTY)

    fun write(context: Context, personaId: Long, asset: CallVisualAsset): Boolean = runCatching {
        val dir = personaDir(context, personaId)
        if (!dir.exists() && !dir.mkdirs()) return false
        val now = System.currentTimeMillis()
        val stored = asset.copy(
            createdAt = if (asset.createdAt > 0L) asset.createdAt else now,
            updatedAt = now,
        )
        metadataFile(context, personaId).writeText(CallVisualManifestCodec.encode(stored))
        true
    }.getOrElse {
        Log.e(TAG, "write metadata failed", it)
        false
    }

    /** 这个人的视觉资源总占用（体积软限制用） */
    fun totalBytes(context: Context, personaId: Long): Long = runCatching {
        val dir = personaDir(context, personaId)
        if (!dir.exists()) return 0L
        dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    // ---- 运行期解析：给视觉引擎用 ----

    /** 状态键 → 真实存在的视频文件；素材被删掉时返回 null（引擎据此走降级） */
    fun videoFile(context: Context, personaId: Long, asset: CallVisualAsset, key: String): File? =
        asset.videoStates[key]
            ?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') }
            ?.let { File(videoDir(context, personaId), it) }
            ?.takeIf { it.isFile && it.length() > 0L }

    /** 伪视频至少还有一条能用吗（一条都没有 = 视觉层降级为无形象，方案文档 §十六最后一档） */
    fun hasAnyVideo(context: Context, personaId: Long, asset: CallVisualAsset): Boolean =
        asset.videoStates.keys.any { videoFile(context, personaId, asset, it) != null }

    /** Live2D 的 `live2d/` 根目录（模型内部路径都相对它） */
    fun live2dRoot(context: Context, personaId: Long): File = live2dDir(context, personaId)

    /** 入口 `.model3.json` 的真实文件；不存在返回 null */
    fun live2dModelFile(context: Context, personaId: Long, asset: CallVisualAsset): File? =
        asset.live2dModelJson
            ?.takeIf { it.isNotBlank() }
            ?.let { File(live2dDir(context, personaId), it) }
            ?.takeIf { it.isFile && it.length() > 0L }

    // ---- 伪视频导入 ----

    /**
     * 把一个 SAF 选中的视频复制进 `video/`，**当场验一遍能不能解码**。
     *
     * 校验放在导入阶段是有原因的（方案文档 §二十二）：用户嘴里的"mp4"可能是 HEVC、
     * 可能是可变帧率的屏幕录制、也可能是 0 字节的坏文件。这些拖到通话页才暴露，
     * 表现就是"画面黑了但没任何提示"。这里直接拒掉并说明原因，且不留半成品。
     *
     * 只复制、不改 Persona 绑定 —— 绑定等用户在状态指认界面点认完、保存时一次性提交。
     */
    fun importVideo(context: Context, personaId: Long, uri: Uri): ImportOutcome {
        if (personaId <= 0L) return ImportOutcome(false, message = "请先保存这个人设")
        val dir = videoDir(context, personaId)
        if (!dir.exists() && !dir.mkdirs()) return ImportOutcome(false, message = "创建素材目录失败")
        if (!CallVisualLimits.totalOk(totalBytes(context, personaId), 0L)) {
            return ImportOutcome(false, message = "素材总量已到 ${CallVisualLimits.MAX_PERSONA_TOTAL_BYTES / 1048576}MB 上限，先删掉一些")
        }
        val target = uniqueFile(dir, sanitize(displayNameOf(context, uri)), "mp4")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                target.delete()
                return ImportOutcome(false, message = "读不到选中的文件")
            }
            if (target.length() == 0L) {
                target.delete()
                return ImportOutcome(false, message = "选中的文件是空的")
            }
            if (target.length() > CallVisualLimits.MAX_VIDEO_BYTES) {
                target.delete()
                return ImportOutcome(false, message = "单条视频不能超过 ${CallVisualLimits.MAX_VIDEO_BYTES / 1048576}MB")
            }
            probeVideo(target)?.let { bad ->
                target.delete()
                return ImportOutcome(false, message = bad)
            }
            ImportOutcome(ok = true, relativePath = target.name)
        }.getOrElse {
            runCatching { target.delete() }
            Log.e(TAG, "import video failed", it)
            ImportOutcome(false, message = "导入失败：${it.javaClass.simpleName}")
        }
    }

    /**
     * 能不能当伪视频播。返回 null = 没问题；非 null = 拒绝理由。
     *
     * 三道判断：**有视频画面轨**（纯音轨的 m4a 播不出画面）、**这机器有解码器**
     * （HEVC 在部分机器上没有）、**时长可读**。刻意不按扩展名猜格式——
     * `MediaCodecList.findDecoderForFormat` 才是权威答复（v0.53.3 那条
     * "别用硬写的 MediaFormat 去赌真实流"的教训同源）。
     */
    private fun probeVideo(file: File): String? = runCatching {
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
        val track = videoTrackOf(file) ?: return "这个文件里没有视频画面，只有声音"
        val mime = track.getString(MediaFormat.KEY_MIME).orEmpty()
        if (!decoderSupports(track)) {
            return "这台机器播不了「$mime」这种编码，请换 H.264 的 mp4"
        }
        null
    }.getOrElse {
        Log.e(TAG, "probe failed", it)
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
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        !name.isNullOrBlank()
    }.getOrDefault(false)

    /** `video/` 里当前的素材池（新导入的排前面，方便刚传完就能在上一行看到） */
    fun listVideos(context: Context, personaId: Long): List<VideoClip> = runCatching {
        val dir = videoDir(context, personaId)
        val files = dir.listFiles { f -> f.isFile && f.length() > 0L } ?: return emptyList()
        files.sortedByDescending { it.lastModified() }.map { f ->
            val meta = clipMeta(f)
            VideoClip(
                name = f.name,
                displayName = f.name.substringBeforeLast('.'),
                bytes = f.length(),
                durationMs = meta.first,
                width = meta.second,
                height = meta.third,
            )
        }
    }.getOrDefault(emptyList())

    private fun clipMeta(file: File): Triple<Long, Int, Int> {        var duration = 0L
        var w = 0
        var h = 0
        runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.absolutePath)
                duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            } finally {
                runCatching { mmr.release() }
            }
        }
        return Triple(duration, w, h)
    }

    /**
     * 素材的显示宽高比（宽/高），读不出来返回 0 = "还不知道"。
     *
     * 视觉引擎在 **load 阶段**就要拿到它：页面的视觉位得先按正确形状占好位置，
     * 等播放器报 first frame 再改尺寸会看着"跳一下"。0 由调用方兜成固定高度的方框。
     */
    fun aspectOf(file: File): Float {
        val meta = clipMeta(file)
        if (meta.second <= 0 || meta.third <= 0) return 0f
        return meta.second.toFloat() / meta.third.toFloat()
    }

    /** 删一条素材。名字里带路径分隔符的一律拒绝——这里只该有一层目录 */
    fun deleteVideo(context: Context, personaId: Long, name: String): Boolean {
        if (name.isBlank() || name.contains('/') || name.contains('\\')) return false
        return runCatching { File(videoDir(context, personaId), name).delete() }.getOrDefault(false)
    }

    /**
     * 清掉没被任何状态引用到的素材。
     *
     * 用户在编辑页导了三条、只指认一条就点了取消——那两条不该留在盘上。
     * 导入阶段无法预知最终留哪几条，所以这件事只能在绑定落定之后收尾一次。
     */
    fun pruneUnbound(context: Context, personaId: Long, asset: CallVisualAsset): Int {
        var removed = 0
        val dir = videoDir(context, personaId)
        if (dir.exists()) {
            val bound = asset.videoStates.values.toSet()
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name !in bound) runCatching { f.delete() }.onSuccess { removed++ }
            }
        }
        // Live2D 目录整体跟着类型走：改成伪视频/无，就没理由再占着那几百 MB
        if (asset.type != CallVisualType.LIVE2D && asset.status != CallVisualStatus.IMPORTING) {
            val ld = live2dDir(context, personaId)
            if (ld.exists()) runCatching { ld.deleteRecursively() }.onSuccess { removed++ }
        }
        return removed
    }

    /**
     * 放弃一次未完成的编辑：把 staging 目录和未绑定的素材一起清掉。
     * 只动 metadata.json 里**没有**引用的东西，已生效的形象绝不会被误删。
     */
    fun discardStaging(context: Context, personaId: Long) {
        val dir = personaDir(context, personaId)
        runCatching {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name.startsWith(STAGING_PREFIX)) f.deleteRecursively()
            }
        }
    }

    // ---- Live2D 模型包导入 ----

    /**
     * 从 zip 导入完整模型包：临时目录 → 安全解压 → 定位 `.model3.json` → 结构校验
     * → 整体搬进 `live2d/`（方案文档 §九的九步）。
     *
     * 失败路径必须做到"删临时资源、保留原绑定、不影响通话"，所以全程只写 `.staging_*`，
     * **只有校验通过才搬进正式目录**；任何一步失败都把 staging 整个删掉再返回原因。
     */
    fun importLive2dPackage(context: Context, personaId: Long, zipUri: Uri): ImportOutcome {
        if (personaId <= 0L) return ImportOutcome(false, message = "请先保存这个人设")
        val base = personaDir(context, personaId)
        if (!base.exists() && !base.mkdirs()) return ImportOutcome(false, message = "创建资源目录失败")
        val staging = File(base, STAGING_PREFIX + System.currentTimeMillis())
        if (!staging.exists() && !staging.mkdirs()) return ImportOutcome(false, message = "创建临时目录失败")

        val unzip = unzipTo(context, zipUri, staging)
        if (unzip == null) {
            staging.deleteRecursively()
            return ImportOutcome(false, message = "压缩包打不开，或里面没有可识别的文件")
        }
        if (unzip.oversize) {
            staging.deleteRecursively()
            return ImportOutcome(false, message = "解压后体积超过 ${CallVisualLimits.MAX_LIVE2D_PACKAGE_BYTES / 1048576}MB，模型包太大")
        }
        if (unzip.entryCount > CallVisualLimits.MAX_PACKAGE_ENTRIES) {
            staging.deleteRecursively()
            return ImportOutcome(false, message = "压缩包内文件数量异常（${unzip.entryCount} 项）")
        }
        if (unzip.rejected > 0) {
            // 越界条目宁可整包拒绝，也不要"跳过一半"留下一个能加载但缺贴图的模型
            staging.deleteRecursively()
            return ImportOutcome(false, message = "压缩里有 ${unzip.rejected} 个越出包范围的路径，为安全起见整包拒绝")
        }

        val paths = unzip.names.toSet()
        val modelJson = Live2dPackageCheck.locateModelJson(unzip.names)
        if (modelJson == null) {
            staging.deleteRecursively()
            return ImportOutcome(false, message = "没找到 .model3.json，请确认导出的是完整模型包")
        }
        val text = runCatching { File(staging, modelJson).readText() }.getOrNull()
        val check = Live2dPackageCheck.validate(modelJson, text, paths)
        if (check !is Live2dPackageCheck.Ok) {
            staging.deleteRecursively()
            val why = (check as? Live2dPackageCheck.Invalid)?.reason ?: "模型包校验未通过"
            return ImportOutcome(false, message = why)
        }

        // 校验过了才换正式目录：先删旧的再搬，避免新旧模型混在同一层
        val target = live2dDir(context, personaId)
        runCatching { if (target.exists()) target.deleteRecursively() }
        if (!staging.renameTo(target) && !target.exists()) {
            // renameTo 在个别 ROM 上跨卷会失败，退化成复制
            val copied = runCatching { staging.copyRecursively(target, overwrite = true) }.getOrDefault(false)
            staging.deleteRecursively()
            if (!copied) return ImportOutcome(false, message = "模型包落盘失败，磁盘空间可能不足")
        } else {
            staging.deleteRecursively()
        }
        return ImportOutcome(ok = true, relativePath = check.modelJsonPath)
    }

    private data class UnzipResult(
        val names: List<String>,
        val entryCount: Int,
        val rejected: Int,
        val oversize: Boolean,
    )

    /**
     * 安全解压：条目名先过 [ZipEntryGuard]，越界的直接计数并丢弃；
     * 单个条目和总量都有上限（zip bomb 的一个 1KB 压缩项能解出 4GB）。
     */
    private fun unzipTo(context: Context, zipUri: Uri, staging: File): UnzipResult? = runCatching {
        val names = ArrayList<String>()
        var entryCount = 0
        var rejected = 0
        var oversize = false
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        context.contentResolver.openInputStream(zipUri)?.use { input ->
            // 条目名解码：带 UTF-8 标志的按 UTF-8（Java 自己判），**没带的按 GBK**。
            // 国内打包工具/网盘传的中文模型包（如"免费模型—奶蛙"）大多没打 UTF-8 标志，
            // 用默认 UTF-8 解会把条目名变成乱码 —— 于是解压出来的文件名和 model3.json 里写的
            // 引用对不上，校验报"模型包缺少 xxx"，用户看着完全莫名其妙。
            ZipInputStream(input, java.nio.charset.Charset.forName("GBK")).use { zin ->
                var entry: ZipEntry? = zin.nextEntry
                while (entry != null) {
                    val rel = ZipEntryGuard.safeRelativePath(entry.name)
                    if (entry.isDirectory) {
                        zin.closeEntry()
                        entry = zin.nextEntry
                        continue
                    }
                    if (rel == null || rel.startsWith("__MACOSX/") || rel.substringAfterLast('/').startsWith(".")) {
                        rejected += if (rel == null) 1 else 0
                        zin.closeEntry()
                        entry = zin.nextEntry
                        continue
                    }
                    entryCount++
                    var entryBytes = 0L
                    val outFile = File(staging, rel)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        while (true) {
                            val n = zin.read(buffer)
                            if (n <= 0) break
                            entryBytes += n
                            total += n
                            if (entryBytes > MAX_ENTRY_BYTES || total > CallVisualLimits.MAX_LIVE2D_PACKAGE_BYTES) {
                                oversize = true
                                break
                            }
                            out.write(buffer, 0, n)
                        }
                    }
                    if (oversize) {
                        runCatching { outFile.delete() }
                        zin.closeEntry()
                        break
                    }
                    names.add(rel)
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
        } ?: return null
        if (oversize) return UnzipResult(names, entryCount, rejected, true)
        if (names.isEmpty()) return null
        UnzipResult(names, entryCount, rejected, false)
    }.getOrNull()

    // ---- 小工具 ----

    /** 文件名里的奇奇怪怪（斜杠、控制符、超长中文名）先收一收，只留可读字符 */
    private fun sanitize(raw: String): String =
        raw.substringBeforeLast('.')
            .replace(Regex("[^\\p{L}\\p{Digit}._\\- ]"), "_")
            .trim('.', ' ', '_')
            .take(40)
            .ifBlank { "video" }

    private fun displayNameOf(context: Context, uri: Uri): String = runCatching {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "video"
    }.getOrDefault("video")

    /** 加时间戳后缀，同名素材不会互相覆盖（用户连传两个 idle.mp4 太常见） */
    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var i = 0
        while (f.exists()) {
            i++
            f = File(dir, "$base-$i.$ext")
        }
        return f
    }
}
