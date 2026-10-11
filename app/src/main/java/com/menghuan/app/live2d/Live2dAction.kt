package com.menghuan.app.live2d

import org.json.JSONArray
import org.json.JSONObject

/**
 * 动作的播放形态（v0.64.0）。
 *
 * 三种形态对应三种"动作资源"，都不写死在某一个模型上：
 * - [PROCEDURAL]：**程序化轨道**，本项目的主力。内置的三个趣味动作、
 *   用户的"我的动作"、AI 生成的动作全是这一种。换任何模型都能跑。
 * - [NATIVE]：模型自带的 `.motion3.json` 动作组。**只有那张模型有**，
 *   所以入库时按人设归属（`personaId` != 0），别的角色看不到。
 * - [EXPRESSION]：模型自带的 `.exp3.json` 表情。同上，按人设归属。
 */
enum class ActionType { PROCEDURAL, NATIVE, EXPRESSION }

/** 动作从哪来（只影响列表分组与"能不能删"，不影响播放） */
enum class ActionSource(val label: String) {
    /** 系统内置：四态默认动作 + 摆手 / 劈叉 / 翻跟斗。**不可删** */
    BUILTIN("内置"),

    /** 从当前模型包里扫出来的自带动作 / 表情。随模型走，**不可单独删** */
    MODEL("模型自带"),

    /** 用户自己拼的（含"我的动作"里改过强度的内置动作） */
    USER("我的动作"),

    /** AI 根据自然语言生成的 */
    AI("AI 生成"),
    ;

    companion object {
        fun fromName(raw: String?): ActionSource =
            entries.firstOrNull { it.name == raw } ?: USER
    }
}

/**
 * 兼容性声明（v0.64.0）。
 *
 * 为什么不用一个"支持的模型列表"：本项目面向的是**用户自己导入的任意模型**，
 * 不存在一份可枚举的模型清单。真正可判定的只有"这张动作需要哪些通道"。
 * 加载模型时探测它有哪些通道，缺了就按 [ModelCapability] 的规则降级
 * ——**不崩、跳过缺失部分、保留其余部分、必要时压低幅度**（用户要求的四档行为）。
 */
data class ModelCompatibility(
    /** 缺了这些通道动作"不成立"（劈叉必须有身体侧倾）；缺任意一个就整段不播，改为回退 */
    val required: Set<GenericChannel> = emptySet(),
    /** 有就更完整、没有也能看的通道。缺了只跳过那一条轨道 */
    val optional: Set<GenericChannel> = emptySet(),
) {
    companion object {
        val NONE = ModelCompatibility()
    }
}

/**
 * 统一的动作数据结构（v0.64.0，用户要求 §八 的落地）。
 *
 * 一份 [Live2dAction] 要同时能被五处消费：视频通话、角色空间、点击互动、
 * 用户自定义组合、AI 生成后保存 —— 所以它必须是**纯数据 + 纯函数**，
 * 里面不许出现 Context / View / 模型实例。
 *
 * 时间字段一律用毫秒整数（落库和 JSON 都稳），播放时再换算成秒。
 */
data class Live2dAction(
    /** 0 = 还没入库（内置动作在首次使用时播种、AI 生成的动作在用户确认后才写库） */
    val id: Long = 0L,
    val name: String,
    val type: ActionType = ActionType.PROCEDURAL,
    val source: ActionSource = ActionSource.USER,

    /**
     * 内置动作的**稳定标识**（`builtin_wave` / `builtin_state_idle` …），非内置为空串。
     *
     * 有它的意义：四态绑定的默认值是"用这个槽的内置默认动作"（`actionId` = 0），
     * 靠这个键去找行 —— 用户删过库、换过机、重装过，默认绑定依然能落地。
     */
    val builtinKey: String = "",

    val durationMs: Int = 2400,
    val loop: Boolean = false,
    val fadeInMs: Int = 240,
    val fadeOutMs: Int = 300,

    /** 参数类轨道（程序化动作的主体） */
    val tracks: List<ParameterTrack> = emptyList(),

    /** [ActionType.NATIVE] 用：动作组名 */
    val motionGroup: String = "",

    /** [ActionType.NATIVE] 用：组内序号 */
    val motionIndex: Int = 0,

    /** 表情名（[ActionType.EXPRESSION] 必填；其他类型也可顺带放一个表情） */
    val expression: String = "",

    /** 强度倍率（0.2–2.0）。用户调的就是它，最终幅度 = 轨道值 × 强度 × 模型能力系数 */
    val amplitude: Float = 1f,

    /** 速度倍率（0.5–2.0）。**只改播放时长，不改轨道值** */
    val speed: Float = 1f,

    val compatibility: ModelCompatibility = ModelCompatibility.NONE,
    val favorite: Boolean = false,

    /** 0 = 全机共享（内置 / 我的动作 / AI 生成）；`> 0` = 仅该人设可见（模型自带动作） */
    val personaId: Long = 0L,

    /** 备注：AI 生成时原样存用户那句话，便于"再改一版" */
    val note: String = "",

    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {

    /** 时间类字段的合法区间：越界的值一律夹回来，不抛异常（脏数据不该让动作播不了） */
    val safeDurationMs: Int get() = durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    val safeFadeInMs: Int get() = fadeInMs.coerceIn(0, MAX_FADE_MS)
    val safeFadeOutMs: Int get() = fadeOutMs.coerceIn(0, MAX_FADE_MS)
    val safeAmplitude: Float get() = amplitude.coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE)
    val safeSpeed: Float get() = speed.coerceIn(MIN_SPEED, MAX_SPEED)

    /** 真正播一轮要多久（速度只影响这里） */
    val playDurationMs: Int get() = (safeDurationMs / safeSpeed).toInt().coerceAtLeast(120)

    /** 这条动作涉及的参数通道（舞台通道由渲染层处理，不在此列） */
    val paramChannels: Set<GenericChannel>
        get() = tracks.filter { !it.channel.isStage }.map { it.channel }.toSet()

    /** 这条动作涉及的舞台通道 */
    val stageChannels: Set<GenericChannel>
        get() = tracks.filter { it.channel.isStage }.map { it.channel }.toSet()

    /** 是不是"什么都不动"的空动作（用来挡住 AI 生成的空壳） */
    val isEmpty: Boolean
        get() = tracks.isEmpty() && motionGroup.isBlank() && expression.isBlank()

    val hasTracks: Boolean get() = tracks.isNotEmpty()

    /** 能不能被单独删除：内置与模型自带的不让删 */
    val deletable: Boolean get() = source == ActionSource.USER || source == ActionSource.AI

    /**
     * 按"动作幅度"档位整体缩放（v0.67.0，纯函数）。
     *
     * 只改 [amplitude]、**不碰轨道值**：轨道值是动作作者写的"这条动作在干什么"，
     * 强度是用户说的"演得多用力"，两件事分开存，用户调档才不会把内置动作越调越不像。
     * 结果仍然夹在 [MIN_AMPLITUDE]..[MAX_AMPLITUDE] 里 —— 那道夹子是"任何模型都不该被甩到破图"
     * 的唯一保险，调档不许绕过它。
     *
     * 倍率 1.0 时**原样返回**（不 copy）：通话每帧都在拿动作，白分配一份数据类没道理。
     */
    fun scaledBy(multiplier: Float): Live2dAction =
        if (multiplier == 1f) this
        else copy(
            amplitude = (safeAmplitude * multiplier).coerceIn(MIN_AMPLITUDE, MAX_AMPLITUDE),
        )

    companion object {
        const val MIN_DURATION_MS = 400
        const val MAX_DURATION_MS = 20_000
        const val MAX_FADE_MS = 2_000
        const val MIN_AMPLITUDE = 0.2f
        const val MAX_AMPLITUDE = 2.0f
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
    }
}

/**
 * 动作的落盘编解码（v0.64.0，纯函数，JVM 单测）。
 *
 * 只编码"动作本体"（轨道 + 原生引用 + 强度速度 + 兼容性 + 备注），
 * 名称 / 来源 / 收藏 / 归属这些"列表要用的字段"留在表列上 ——
 * 否则每次列动作库都要把每一行的 JSON 解一遍。
 *
 * 解析一律宽容（与 `CallVisualManifestCodec` 同一口径）：认不出来的通道跳过、
 * 认不出来的字段忽略、整份读不通就当空动作。**脏一行数据不该让动作库打不开**。
 */
object ActionCodec {

    private const val VERSION = 1

    private const val KEY_VERSION = "v"
    private const val KEY_MOTION_GROUP = "motionGroup"
    private const val KEY_MOTION_INDEX = "motionIndex"
    private const val KEY_EXPRESSION = "expression"
    private const val KEY_AMPLITUDE = "amplitude"
    private const val KEY_SPEED = "speed"
    private const val KEY_FADE_IN = "fadeIn"
    private const val KEY_FADE_OUT = "fadeOut"
    private const val KEY_NOTE = "note"
    private const val KEY_REQUIRED = "required"
    private const val KEY_OPTIONAL = "optional"
    private const val KEY_TRACKS = "tracks"
    private const val KEY_CHANNEL = "ch"
    private const val KEY_EASING = "ease"
    private const val KEY_KEYS = "keys"
    private const val KEY_AT = "t"
    private const val KEY_VALUE = "x"

    fun encode(action: Live2dAction): String {
        val o = JSONObject()
        o.put(KEY_VERSION, VERSION)
        if (action.motionGroup.isNotBlank()) o.put(KEY_MOTION_GROUP, action.motionGroup)
        if (action.motionIndex != 0) o.put(KEY_MOTION_INDEX, action.motionIndex)
        if (action.expression.isNotBlank()) o.put(KEY_EXPRESSION, action.expression)
        o.put(KEY_AMPLITUDE, TrackKey.round(action.safeAmplitude).toDouble())
        o.put(KEY_SPEED, TrackKey.round(action.safeSpeed).toDouble())
        o.put(KEY_FADE_IN, action.safeFadeInMs)
        o.put(KEY_FADE_OUT, action.safeFadeOutMs)
        if (action.note.isNotBlank()) o.put(KEY_NOTE, action.note)

        if (action.compatibility.required.isNotEmpty()) {
            o.put(KEY_REQUIRED, JSONArray(action.compatibility.required.map { it.id }))
        }
        if (action.compatibility.optional.isNotEmpty()) {
            o.put(KEY_OPTIONAL, JSONArray(action.compatibility.optional.map { it.id }))
        }

        val arr = JSONArray()
        action.tracks.forEach { track ->
            val t = JSONObject()
            t.put(KEY_CHANNEL, track.channel.id)
            if (track.easing != TrackEasing.IN_OUT) t.put(KEY_EASING, track.easing.name)
            val keys = JSONArray()
            track.keys.forEach { k ->
                val ko = JSONObject()
                ko.put(KEY_AT, TrackKey.round(k.at).toDouble())
                ko.put(KEY_VALUE, TrackKey.round(k.value).toDouble())
                keys.put(ko)
            }
            t.put(KEY_KEYS, keys)
            arr.put(t)
        }
        if (arr.length() > 0) o.put(KEY_TRACKS, arr)
        return o.toString()
    }

    /**
     * 解一条动作本体。**缺 tracks / motionGroup / expression 三者全部 = 空动作**，
     * 由调用方决定要不要丢弃（AI 生成时这就是"什么都没生成"的判据）。
     */
    fun decode(text: String?): ActionSpec {
        if (text.isNullOrBlank()) return ActionSpec()
        return runCatching {
            val o = JSONObject(text)
            ActionSpec(
                motionGroup = o.optString(KEY_MOTION_GROUP, ""),
                motionIndex = o.optInt(KEY_MOTION_INDEX, 0),
                expression = o.optString(KEY_EXPRESSION, ""),
                amplitude = o.optDouble(KEY_AMPLITUDE, 1.0).toFloat(),
                speed = o.optDouble(KEY_SPEED, 1.0).toFloat(),
                fadeInMs = o.optInt(KEY_FADE_IN, 240),
                fadeOutMs = o.optInt(KEY_FADE_OUT, 300),
                note = o.optString(KEY_NOTE, ""),
                compatibility = ModelCompatibility(
                    required = channelSet(o.optJSONArray(KEY_REQUIRED)),
                    optional = channelSet(o.optJSONArray(KEY_OPTIONAL)),
                ),
                tracks = readTracks(o.optJSONArray(KEY_TRACKS)),
            )
        }.getOrDefault(ActionSpec())
    }

    /** 只解"有没有东西"（列表页要显示"这条动作要几张轨道"时用，省一次全解） */
    fun trackCount(text: String?): Int = runCatching {
        JSONObject(text.orEmpty()).optJSONArray(KEY_TRACKS)?.length() ?: 0
    }.getOrDefault(0)

    private fun channelSet(arr: JSONArray?): Set<GenericChannel> {
        if (arr == null) return emptySet()
        val out = LinkedHashSet<GenericChannel>()
        for (i in 0 until arr.length()) {
            GenericChannel.byId(arr.optString(i))?.let { out.add(it) }
        }
        return out
    }

    private fun readTracks(arr: JSONArray?): List<ParameterTrack> {
        if (arr == null) return emptyList()
        val out = ArrayList<ParameterTrack>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val channel = GenericChannel.byId(t.optString(KEY_CHANNEL)) ?: continue
            val keysArr = t.optJSONArray(KEY_KEYS) ?: continue
            val keys = ArrayList<TrackKey>()
            for (j in 0 until keysArr.length()) {
                val ko = keysArr.optJSONObject(j) ?: continue
                keys.add(
                    TrackKey(
                        at = ko.optDouble(KEY_AT, 0.0).toFloat().coerceIn(0f, 1f),
                        value = ko.optDouble(KEY_VALUE, 0.0).toFloat(),
                    ),
                )
            }
            if (keys.isEmpty()) continue
            val easing = TrackEasing.entries.firstOrNull { it.name == t.optString(KEY_EASING) }
                ?: TrackEasing.IN_OUT
            out.add(ParameterTrack(channel, keys.sortedBy { it.at }, easing))
        }
        return out
    }
}

/**
 * 动作本体的解码结果（v0.64.0）。
 *
 * 与 [Live2dAction] 的区别：[Live2dAction] 是"完整的一条动作"（含 id / 名称 / 来源），
 * 这个只是从 JSON 里读出来的那一半。分开放是为了让 AI 生成链路能**只产出本体**
 * （AI 不需要、也不该去编 id 和来源），再由仓库层组装成完整的 [Live2dAction]。
 */
data class ActionSpec(
    val motionGroup: String = "",
    val motionIndex: Int = 0,
    val expression: String = "",
    val amplitude: Float = 1f,
    val speed: Float = 1f,
    val fadeInMs: Int = 240,
    val fadeOutMs: Int = 300,
    val note: String = "",
    val compatibility: ModelCompatibility = ModelCompatibility.NONE,
    val tracks: List<ParameterTrack> = emptyList(),
) {
    val isEmpty: Boolean
        get() = tracks.isEmpty() && motionGroup.isBlank() && expression.isBlank()

    /** 组装成完整动作。[source] 决定它在库里怎么分组显示 */
    fun toAction(
        name: String,
        source: ActionSource,
        type: ActionType,
        durationMs: Int,
        loop: Boolean,
        personaId: Long = 0L,
        builtinKey: String = "",
        now: Long = System.currentTimeMillis(),
    ) = Live2dAction(
        name = name,
        type = type,
        source = source,
        builtinKey = builtinKey,
        durationMs = durationMs,
        loop = loop,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        tracks = tracks,
        motionGroup = motionGroup,
        motionIndex = motionIndex,
        expression = expression,
        amplitude = amplitude,
        speed = speed,
        compatibility = compatibility,
        personaId = personaId,
        note = note,
        createdAt = now,
        updatedAt = now,
    )
}
