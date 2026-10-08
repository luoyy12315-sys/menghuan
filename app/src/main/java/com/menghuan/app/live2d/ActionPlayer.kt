package com.menghuan.app.live2d

import com.live2d.sdk.cubism.framework.model.CubismModel
import kotlin.math.abs
import kotlin.math.max

/**
 * 动作播放器（v0.64.0）——整套系统的运行时心脏。
 *
 * 职责只有一个：**把一条 [Live2dAction] 的轨道，按当前进度写进模型参数，并吐出舞台姿态。**
 * 它不认识通话、不认识 UI、不联网、不碰数据库。
 *
 * 线程模型（照抄本项目已有的 Live2D 那一套，别改）：
 * - [play] / [stop] 可能来自任意线程（UI、通话引擎），只写 `@Volatile` 的意图字段；
 * - [applyTo] **必须在 GL 线程**，每帧调一次，在它里面把意图落实成参数写入。
 *
 * ⚠️ 三层幅度相乘的含义各不相同，别合并（见 [ModelCapability.applyScale]）：
 * `轨道值(±1) × 动作强度 × 缺通道降级系数 × 淡入淡出包络`。
 * 其中"轨道值 ×1.0"对应模型该参数可用范围的 [AMPLITUDE_SPAN] ——
 * 这道闸管的是 **"任何模型都不该被甩到破图"**：参数范围小的小幅动、
 * 范围大的也不会把角色扯变形。
 *
 * ⚠️ 口型**不在这里处理**。`ParamMouthOpenY` 由框架的 lip-sync 更新器在动作写完之后
 * 以加法叠加（官方实现如此），所以"动作里写张嘴"在安静时生效、在说话时被语音盖过 ——
 * 这正是我们要的互不打架。四态默认动作刻意不碰它（否则角色会一直张着嘴）。
 */
class ActionPlayer {

    // ---- 跨线程意图 ----

    /** 待播放的动作（null = 没有新意图）。放进去由 GL 线程下一帧取用 */
    @Volatile
    private var pending: Live2dAction? = null

    /** 待播放的动作在**这张模型**上算出来的能力系数（GL 线程取用前不算，见 [applyTo]） */
    @Volatile
    private var stopRequested = false

    /** 正在播的动作（GL 线程读，也开放给 UI 读，所以是 volatile） */
    @Volatile
    var current: Live2dAction? = null
        private set

    /** 当前进度 0..1（UI 上的进度条 / 预览用） */
    @Volatile
    var progress: Float = 0f
        private set

    /** 空闲 = 没有动作在写参数，模型回到它自己的待机动作与呼吸眨眼 */
    val isPlaying: Boolean
        get() = current != null

    /** GL 线程每帧填：这张模型能写哪些参数、各自的静止值与可用跨度 */
    private var slots: Map<GenericChannel, List<ParamSlot>> = emptyMap()

    private var elapsedMs = 0f
    private var baseScale = 1f
    private var capability: ModelCapability = ModelCapability.EMPTY

    // ---- 对外接口 ----

    /**
     * 请求播放。**任意线程可调**。
     *
     * 不在这里判"模型支不支持"：能力表只有 GL 线程手上那份是准的
     * （模型是 GL 线程加载的），判定放到 [applyTo] 里，判不过就立刻置空闲，
     * 调用方下一帧就能发现并回退到别的动作。
     */
    fun play(action: Live2dAction) {
        stopRequested = false
        pending = action
    }

    /** 请求停止并回到模型的自然状态。任意线程可调 */
    fun stop() {
        stopRequested = true
        pending = null
    }

    /**
     * 把模型绑上来（**GL 线程**，模型加载完成、还没播任何动作时调一次）。
     *
     * 这里记下的 `rest` 是**模型自己的静止值**，不是 0 ——
     * 很多模型的 `ParamArmLA` 静止在 5、`ParamAngleY` 静止在 -2，
     * 一律当成 0 会让每次播动作都先"跳"一下。
     *
     * @param capability 探测出来的通道 → 参数 ID
     * @param indexOf 参数名 → 参数下标（`< 0` = 这张模型没有这个参数）
     */
    fun bindModel(
        model: CubismModel,
        capability: ModelCapability,
        indexOf: (String) -> Int,
    ) {
        this.capability = capability
        val map = LinkedHashMap<GenericChannel, List<ParamSlot>>()
        capability.available.forEach { (channel, ids) ->
            if (channel.isStage) return@forEach
            val list = ArrayList<ParamSlot>(ids.size)
            ids.forEach { id ->
                val index = runCatching { indexOf(id) }.getOrDefault(-1)
                if (index < 0) return@forEach
                val rest = runCatching { model.getParameterValue(index) }.getOrDefault(0f)
                val maxV = runCatching { model.getParameterMaximumValue(index) }.getOrDefault(1f)
                val minV = runCatching { model.getParameterMinimumValue(index) }.getOrDefault(-1f)
                val span = max(abs(maxV - rest), abs(rest - minV)).takeIf { it > 1e-4f } ?: 0f
                if (span > 0f) list.add(ParamSlot(index, rest, span))
            }
            if (list.isNotEmpty()) map[channel] = list
        }
        slots = map
    }

    /** 模型要没了（挂断 / 换模型）：清干净，别让下一张模型用到上一张的参数下标 */
    fun release() {
        pending = null
        stopRequested = true
        current = null
        slots = emptyMap()
        capability = ModelCapability.EMPTY
        elapsedMs = 0f
        progress = 0f
    }

    /**
     * 每帧推进并写参数（**GL 线程**）。
     *
     * @return 这一帧的舞台姿态；没有舞台轨道就是 [StagePose.NONE]
     */
    fun applyTo(model: CubismModel, dtSeconds: Float): StagePose {
        val dtMs = (dtSeconds.coerceIn(0f, 0.25f) * 1000f)

        // ① 落实"停"的意图：把当前动作丢掉，参数下一帧就由 loadParameters 复位
        if (stopRequested) {
            stopRequested = false
            if (current != null) {
                current = null
                elapsedMs = 0f
                progress = 0f
            }
            return StagePose.NONE
        }

        // ② 落实"播"的意图
        pending?.let { next ->
            pending = null
            // 必需通道全缺 → 这条动作在这张模型上不成立，直接判空闲，调用方去回退
            current = if (capability.canRun(next.compatibility)) next else null
            elapsedMs = 0f
            progress = 0f
            baseScale = capability.applyScale(next)
        }

        val action = current ?: return StagePose.NONE
        if (action.tracks.isEmpty()) {
            current = null
            return StagePose.NONE
        }

        elapsedMs += dtMs
        val cycleMs = action.playDurationMs.toFloat().coerceAtLeast(1f)
        val fadeIn = action.safeFadeInMs.toFloat()
        val fadeOut = action.safeFadeOutMs.toFloat()

        val fraction: Float
        val envelope: Float
        if (action.loop) {
            fraction = (elapsedMs % cycleMs) / cycleMs
            // 循环动作只在最开头淡入一次，之后一直满幅 —— 每圈都淡一遍会看着一抽一抽
            envelope = if (fadeIn <= 0f) 1f else (elapsedMs / fadeIn).coerceIn(0f, 1f)
        } else if (elapsedMs < cycleMs) {
            fraction = elapsedMs / cycleMs
            envelope = if (fadeIn <= 0f) 1f else (elapsedMs / fadeIn).coerceIn(0f, 1f)
        } else if (elapsedMs < cycleMs + fadeOut) {
            // 收尾段：轨道停在末态，包络把它淡回静止。
            // 这一段是"动作播完不会啪地弹回原位"的原因 —— 没有它，末帧值不为 0 的动作会闪一下。
            fraction = 1f
            envelope = 1f - ((elapsedMs - cycleMs) / fadeOut).coerceIn(0f, 1f)
        } else {
            current = null
            elapsedMs = 0f
            progress = 0f
            return StagePose.NONE
        }
        progress = fraction

        val scale = baseScale * envelope
        val stage = StagePose()

        var dx = 0f
        var dy = 0f
        var rot = 0f
        var sc = 1f

        for (track in action.tracks) {
            val raw = track.valueAt(fraction)
            if (raw == 0f && scale == 0f) continue
            val v = raw * scale
            val channel = track.channel
            if (channel.isStage) {
                when (channel) {
                    GenericChannel.STAGE_X -> dx += v * channel.stageUnit
                    GenericChannel.STAGE_Y -> dy += v * channel.stageUnit
                    GenericChannel.STAGE_ROT -> rot += v * channel.stageUnit
                    GenericChannel.STAGE_SCALE -> sc *= (1f + v * channel.stageUnit)
                    else -> Unit
                }
            } else {
                val list = slots[channel] ?: continue
                list.forEach { slot -> model.setParameterValue(slot.index, slot.rest + v * slot.span * AMPLITUDE_SPAN) }
            }
        }

        return if (dx == 0f && dy == 0f && rot == 0f && sc == 1f) {
            StagePose.NONE
        } else {
            StagePose(dx = dx, dy = dy, rotationDeg = rot, scale = sc.coerceIn(0.6f, 1.6f))
        }
    }

    /** 一个参数槽：下标 + 模型自己的静止值 + 可用跨度的一半 */
    private class ParamSlot(val index: Int, val rest: Float, val span: Float)

    companion object {
        /**
         * 归一化 1.0 对应"参数可用范围的多少"。
         *
         * v0.65.0 从 0.6 提到 **0.9**（用户点名"动作显示幅度大一点"，选的是 +50% 那一档）。
         * 留 0.1 的余量不推到端点：参数顶到端点时网格会明显拉坏（尤其半身模型的手臂），
         * 真机上如果看到"摆手把手臂扯变形"，往回收就改这一个数，不要去改各条动作的强度
         *（内置动作是按 1.0 写的，逐条改会让四态默认与用户自自动作的观感不一致）。
         */
        const val AMPLITUDE_SPAN = 0.9f
    }
}
