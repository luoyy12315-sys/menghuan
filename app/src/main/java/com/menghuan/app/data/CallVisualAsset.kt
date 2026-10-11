package com.menghuan.app.data

import org.json.JSONObject

/**
 * 通话视觉形象的类型（v0.58.0）。本期只有三种，**没有 3D**：
 * 方案文档 §一明确视频通话只做 Live2D 与伪视频。
 *
 * 名字与 `personas.videoVisualType` 列里的字符串一一对应，旧数据（列刚加时全是默认值）
 * 落 NONE，所以老用户升级后语音通话与聊天完全不受影响。
 */
enum class CallVisualType(val label: String) {
    NONE("无"),
    LIVE2D("Live2D"),
    VIDEO("伪视频");

    companion object {
        /** 列里存的是枚举名；脏数据/未来新增值一律当 NONE，绝不抛异常拖垮人设加载 */
        fun fromName(name: String?): CallVisualType =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/**
 * 视觉资源的生命周期状态（方案文档 §二十一）。
 *
 * IMPORTING 只存在于导入过程中：**没导入成功之前不改 Persona 的最终绑定**，
 * 失败也不把 Persona 写成 ERROR 之外还留着半截文件——清理临时目录、回到原状态。
 */
enum class CallVisualStatus {
    NONE,
    IMPORTING,
    READY,
    ERROR,
}

/**
 * 一个人设的通话视觉资源配置（落在 `filesDir/call_visuals/<personaId>/metadata.json`）。
 *
 * 为什么不整份塞进 Room：视觉资源的主体是**文件**（几十 MB 的 mp4 / 模型包），
 * 库里只放"这个 Persona 用哪种视觉"这一列（见 [CallVisualType]），
 * 剩下的细节跟着文件走——文件被人删了，配置自然失效，检测到了就降级（方案文档 §二十四）。
 * 这也保住了"Persona 基础资料与视觉资源管理职责分离"（方案文档 §十九）。
 *
 * 路径一律存**相对各自子目录的文件名**，不存绝对路径：用户换机、备份还原、
 * 或者我们把目录改名，绝对路径就全废了。
 */
data class CallVisualAsset(
    val type: CallVisualType = CallVisualType.NONE,
    val status: CallVisualStatus = CallVisualStatus.NONE,
    val errorMessage: String = "",
    /** 伪视频：状态键（[CallVisualSlots]）→ `video/` 下的文件名。允许缺项，缺的走回退 */
    val videoStates: Map<String, String> = emptyMap(),
    /** Live2D：`.model3.json` 在 `live2d/` 下的相对路径（导入时已定位好，运行期不再猜） */
    val live2dModelJson: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 能不能真拿这块视觉去通话：类型对、状态 READY、且素材至少还有一个能用 */
    val isUsable: Boolean
        get() = status == CallVisualStatus.READY && when (type) {
            CallVisualType.VIDEO -> videoStates.isNotEmpty()
            CallVisualType.LIVE2D -> live2dModelJson.isNotBlank()
            CallVisualType.NONE -> false
        }

    companion object {
        val EMPTY = CallVisualAsset()
    }
}

/**
 * 伪视频的四个通话状态槽（方案文档 §六 / §十三）。
 *
 * [key] 是落进 metadata.json 的稳定英文键（也用于素材包文件名匹配），
 * [label] 是人设编辑里给用户看的中文。**只有这一份名单**，导入界面和回退逻辑都读它，
 * 两边不会各说各话（同 `TtsVoices.library()` 的做法）。
 *
 * 以后要加情绪素材（happy/surprised）就是往这张表里加一行，
 * metadata.json 的 `states` 是 map、不用改结构（方案文档 §十六要求的"数据允许扩展"）。
 */
object CallVisualSlots {
    data class Slot(val key: String, val label: String)

    const val IDLE = "idle"
    const val LISTENING = "listening"
    const val THINKING = "thinking"
    const val SPEAKING = "speaking"

    val all: List<Slot> = listOf(
        Slot(IDLE, "待机"),
        Slot(LISTENING, "倾听"),
        Slot(THINKING, "思考"),
        Slot(SPEAKING, "说话"),
    )
}

/**
 * metadata.json 的编解码（纯函数，JVM 单测）。
 *
 * 解析一律宽容（同 ProfileExtractor / ApiProfileCodec 的口径）：字段缺失给默认值，
 * 整份 JSON 读不通就返回 [CallVisualAsset.EMPTY]——视觉配置读不出来只是"没有形象"，
 * 绝不能变成人设页或通话页的崩溃点。
 */
object CallVisualManifestCodec {

    private const val KEY_VERSION = "version"
    private const val KEY_TYPE = "type"
    private const val KEY_STATUS = "status"
    private const val KEY_ERROR = "errorMessage"
    private const val KEY_STATES = "states"
    private const val KEY_DEFAULT = "default"
    private const val KEY_LIVE2D = "model3Json"
    private const val KEY_CREATED = "createdAt"
    private const val KEY_UPDATED = "updatedAt"

    /** 当前落盘格式版本。v1 = 本期形态 */
    const val VERSION = 1

    fun encode(asset: CallVisualAsset): String {
        val o = JSONObject()
        o.put(KEY_VERSION, VERSION)
        o.put(KEY_TYPE, asset.type.name)
        o.put(KEY_STATUS, asset.status.name)
        o.put(KEY_ERROR, asset.errorMessage)
        o.put(KEY_STATES, JSONObject().apply {
            asset.videoStates.forEach { (k, v) -> put(k, v) }
        })
        // default 不是独立字段，而是 idle 的别名——写出来只为让人手看的素材包更易读，
        // 读的时候两处都认（见 decode）
        o.put(KEY_DEFAULT, asset.videoStates[CallVisualSlots.IDLE].orEmpty())
        o.put(KEY_LIVE2D, asset.live2dModelJson)
        o.put(KEY_CREATED, asset.createdAt)
        o.put(KEY_UPDATED, asset.updatedAt)
        return o.toString()
    }

    fun decode(text: String?): CallVisualAsset {
        if (text.isNullOrBlank()) return CallVisualAsset.EMPTY
        return runCatching {
            val o = JSONObject(text)
            val states = LinkedHashMap<String, String>()
            val obj = o.optJSONObject(KEY_STATES)
            if (obj != null) {
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = obj.optString(k, "")
                    if (v.isNotBlank()) states[k] = v
                }
            }
            // 素材包里的 manifest.json 可以只写 default 不写 idle（方案文档 §十五），
            // 这里把它当 idle 兜底读进来，两条来源等价。
            val idle = states[CallVisualSlots.IDLE].orEmpty()
            if (idle.isBlank()) {
                o.optString(KEY_DEFAULT, "").takeIf { it.isNotBlank() }
                    ?.let { states[CallVisualSlots.IDLE] = it }
            }
            CallVisualAsset(
                type = CallVisualType.fromName(o.optString(KEY_TYPE)),
                status = CallVisualStatus.entries.firstOrNull { it.name == o.optString(KEY_STATUS) }
                    ?: CallVisualStatus.NONE,
                errorMessage = o.optString(KEY_ERROR, ""),
                videoStates = states,
                live2dModelJson = o.optString(KEY_LIVE2D, ""),
                createdAt = o.optLong(KEY_CREATED, 0L),
                updatedAt = o.optLong(KEY_UPDATED, 0L),
            )
        }.getOrDefault(CallVisualAsset.EMPTY)
    }

}

/**
 * 视觉资源的体积上限（方案文档 §二十二）。
 *
 * 是**软限制**：超了只拒绝导入并说明原因，不动用户已有资源。
 * 数值按"手机存一段 10 秒 1080p mp4 ≈ 8–15MB、一个 Live2D 包 5–30MB"的量级放，
 * 真机跑下来偏大再调。
 */
object CallVisualLimits {
    const val MAX_VIDEO_BYTES = 80L * 1024 * 1024
    const val MAX_LIVE2D_PACKAGE_BYTES = 100L * 1024 * 1024
    const val MAX_PERSONA_TOTAL_BYTES = 300L * 1024 * 1024

    /** 解压防炸：单个素材包最多认多少个条目 */
    const val MAX_PACKAGE_ENTRIES = 4000

    fun totalOk(currentBytes: Long, addingBytes: Long): Boolean =
        currentBytes + addingBytes <= MAX_PERSONA_TOTAL_BYTES
}

/**
 * ZIP 条目落点校验（纯函数，方案文档 §十 / §二十九的 Zip Slip 防线）。
 *
 * 用户传来的 zip 是不可信输入：条目名可能是 `../../Android/data/...`，也可能是绝对路径。
 * 这里做的是**唯一**的判定口径——`entryName` 规范化之后必须仍落在 [rootPath] 里面，
 * 越界或绝对路径一律返回 null，由调用方丢弃该条目并计入失败。
 */
object ZipEntryGuard {

    /**
     * 条目名 → 可安全拼到 root 下的相对路径；**不可信就返回 null**。
     *
     * ⚠️ **绝对路径一律拒绝**（开头的 `/`、以及带盘符的 `C:/...`）。
     * 以前这里对开头的 `/` 是 `trimStart` 掉放行的，而对 `C:` 是拒绝的 ——
     * 同一类东西两种待遇，而且 trim 之后 `//etc/passwd` 会变成一条"合法"的相对路径。
     * 现在统一成"绝对路径不要"，与 `ZipEntryGuardTest` 的口径一致。
     *
     * 对 `..` 也是直接拒绝而不是"弹一层"：解压工具常见的消解写法在恶意包下
     * 仍能拼出奇怪落点，而对用户传来的东西我们只需要"要么规规矩矩在里面、要么不要"。
     */
    fun safeRelativePath(entryName: String): String? {
        val name = entryName.replace('\\', '/').trim()
        if (name.isBlank()) return null
        if (name.startsWith("/")) return null
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        if (parts.any { it.contains(':') }) return null   // C:\... 这类绝对路径
        return parts.joinToString("/")
    }
}
