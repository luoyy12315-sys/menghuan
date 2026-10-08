package com.menghuan.app.live2d

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 动作之间的**承接层**（v0.67.0，纯函数，JVM 单测）。
 *
 * ## 它解决的是"说不清但一眼看得出"的那一族毛病
 *
 * [ActionPlayer] 每帧把轨道值写进模型参数，而 `CubismModel.loadParameters()` 每帧都会把参数
 * **恢复成模型自己的姿态**（它自己的 motion 摆出来的位置）。于是"没有动作在写"这件事
 * 在屏幕上的表现不是"停在最后那一帧"，而是**立刻弹回静止位**。v0.64 的写法里：
 *
 * - 换新动作时包络从 0 起（`envelope = elapsed / fadeIn`）——旧动作正把头摆在 +0.3 的位置，
 *   新动作的第一帧把该通道写成 `rest + 0`，**头先弹回中间、再跟着新动作淡进去**。
 *   通话里 待机/倾听/思考/说话 来回切，每切一次都能看见脖子抖一下。
 * - 一次动作播完（非循环）直接 `current = null`、舞台姿态返回 NONE —— 翻跟斗转到一半
 *   被新状态打断，角色**瞬间转正**。
 * - 循环动作绕回起点的瞬间，若首帧与末帧的关键值不相等（用户自己拼的、AI 生成的几乎必然不等），
 *   每圈都会**硬跳一格**。
 *
 * ## 做法
 *
 * 只有一个机制：**从"上一帧真正写出去的值"淡到"这一帧该写的值"**。
 * 起点在 [startFade] 那一刻冻结，终点每帧重算，进度由 [weight] 给。
 *
 * · 全新开始（上一帧什么都没写）→ 起点全 0 → 输出 = 目标 × 权重 —— **与旧的淡入包络一字不差**，
 *   所以这条改动不会让"从静止起"的动作变样，只把"从别的姿态接手"的那三种情况救回来；
 * · 换动作 → 起点是旧动作的最后一帧，新动作没写的那些通道终点是 0 → **各回各家，不弹**；
 * · 停止 → 目标清空 + 一段淡出 → 姿态**收着回去**而不是撒手就消失；
 * · 循环接缝 → 重开一段很短的淡化，绕回起点那一下被摊平。
 *
 * ⚠️ 残留淡完就不再"拥有"那个通道（[owns] 为 false），ActionPlayer 随即停止写它 ——
 * 不是把它写成 0。写成 0 等于把参数钉死在静止位，**模型自己那条 Idle 动作就再也摆不动它了**
 * （表现为"播过一次摆手，角色自己的待机动作从此没了"）。
 *
 * 全部是定长数组、每帧零分配（渲染线程每秒要跑 60 次，这里 new 一个 Map 都是负担）。
 */
class ActionBlender(
    /** 舞台旋转的"一整圈"是多少度；>0 时冻结起点会把角度折到最近的那一圈上，见 [startFade] */
    private val rotPeriod: Float = 0f,
    private val channelCount: Int = GenericChannel.entries.size,
) {

    /** 淡化起点：[startFade] 那一刻冻结的上一帧输出 */
    private val from = FloatArray(channelCount)

    /** 这一帧新动作要写的值（同通道多条轨道累加） */
    private val to = FloatArray(channelCount)

    /** 上一帧真正写出去的值，也是本帧算完的输出 */
    private val value = FloatArray(channelCount)

    private var blendMs = 0f
    private var blendAge = 0f

    private var fromDx = 0f
    private var fromDy = 0f
    private var fromRot = 0f
    private var fromScale = 0f
    private var toDx = 0f
    private var toDy = 0f
    private var toRot = 0f
    private var toScale = 0f
    private var outDx = 0f
    private var outDy = 0f
    private var outRot = 0f
    private var outScale = 0f

    /** 淡化进度 0..1（1 = 已经完全跟着新动作走） */
    fun weight(): Float =
        if (blendMs <= 0f) 1f
        else smoothstep((blendAge / blendMs).coerceIn(0f, 1f))

    /** 淡化是否还在跑（跑完就不该再"拥有"旧通道） */
    val blending: Boolean get() = blendAge < blendMs

    /** 开始一段承接：把上一帧的输出冻结成起点。旋转起点会折到**最近的那一圈**，见构造参数 */
    fun startFade(durationMs: Float) {
        value.copyInto(from)
        blendMs = durationMs.coerceAtLeast(0f)
        blendAge = 0f
        fromDx = outDx
        fromDy = outDy
        fromScale = outScale
        fromRot = if (rotPeriod > 0f) snapToNearestTurn(outRot, rotPeriod) else outRot
    }

    /** 清掉本帧目标（每帧累加之前调） */
    fun beginFrame() {
        to.fill(0f)
        toDx = 0f
        toDy = 0f
        toRot = 0f
        toScale = 0f
    }

    /** 累加一条参数轨道的目标值（归一化幅度，已乘强度与能力系数） */
    fun addTarget(channel: GenericChannel, v: Float) {
        val i = channel.ordinal
        if (i in to.indices) to[i] += v
    }

    /** 累加一条舞台轨道的目标值（单位与 [GenericChannel.stageUnit] 换算后的量纲一致） */
    fun addStageTarget(dx: Float, dy: Float, rotation: Float, scaleDelta: Float) {
        toDx += dx
        toDy += dy
        toRot += rotation
        toScale += scaleDelta
    }

    /** 推进淡化时钟 */
    fun advance(dtMs: Float) {
        if (blendAge < blendMs) blendAge = min(blendAge + dtMs, blendMs)
    }

    /**
     * 算出这一帧的输出（就地写进 [value] 与舞台四元组）。
     * 目标与起点相同的那条通道结果就是那个值 —— 不跳；起点有残留而目标没有的会淡回 0。
     */
    fun settle() {
        val w = weight()
        for (i in value.indices) {
            value[i] = from[i] + (to[i] - from[i]) * w
        }
        outDx = fromDx + (toDx - fromDx) * w
        outDy = fromDy + (toDy - fromDy) * w
        outRot = fromRot + (toRot - fromRot) * w
        outScale = fromScale + (toScale - fromScale) * w
    }

    /**
     * 这一帧这个通道**该不该写**。
     *
     * 目标非零 → 要写；起点有残留且淡化还没跑完 → 还要写（正在淡回去）；
     * 两者都没有 → **不许写**，让模型自己的 motion 拿回这个参数的控制权（见类注释那条警告）。
     */
    fun owns(channel: GenericChannel): Boolean {
        val i = channel.ordinal
        if (i !in value.indices) return false
        return to[i] != 0f || (from[i] != 0f && blending)
    }

    /** 这一帧写出去的值（[owns] 为 true 时才有意义） */
    fun valueOf(channel: GenericChannel): Float = value[channel.ordinal]

    /**
     * 这一帧的舞台姿态；没在动就是 [StagePose.NONE]。
     *
     * ⚠️ 缩放要夹：`0.6..1.6`。轨道值本身不设上限（翻跟斗的旋转要写 2.0），
     * 而缩放一旦被用户拼的动作或 AI 生成的垃圾值推到 3 倍，画面上就是角色冲出屏幕、
     * 再淡回来时整场通话看不见人 —— 这道夹子是唯一的兜底（v0.64 的 `ActionPlayer` 里就有，
     * v0.67 把承接搬到这里时必须一起搬过来，别当成冗余删掉）。
     */
    fun stagePose(): StagePose =
        if (outDx == 0f && outDy == 0f && outRot == 0f && outScale == 0f) StagePose.NONE
        else StagePose(
            dx = outDx,
            dy = outDy,
            rotationDeg = outRot,
            scale = (1f + outScale).coerceIn(MIN_STAGE_SCALE, MAX_STAGE_SCALE),
        )

    /** 残留是否还没清干净（决定 [ActionPlayer] 能不能宣布"这条动作放完了"） */
    fun isSettled(): Boolean =
        !blending && value.all { it == 0f } &&
            outDx == 0f && outDy == 0f && outRot == 0f && outScale == 0f

    /** 模型要没了 / 播放器 reset：连起点一起清，别让下一张模型接上一张的残值 */
    fun reset() {
        value.fill(0f)
        from.fill(0f)
        to.fill(0f)
        blendAge = 0f
        blendMs = 0f
        fromDx = 0f; fromDy = 0f; fromRot = 0f; fromScale = 0f
        toDx = 0f; toDy = 0f; toRot = 0f; toScale = 0f
        outDx = 0f; outDy = 0f; outRot = 0f; outScale = 0f
    }

    /**
     * 把一个角度折到"离它最近的那一整圈"附近，结果落在 `[-period/2, period/2]`。
     *
     * 翻跟斗的旋转走到 350° 时被打断，直接淡回 0 会**倒着转大半圈**；
     * 而 350° 和 -10° 画面上是同一个姿势，从 -10° 淡回 0 才是"翻过去差一点站稳了"。
     */
    fun snapToNearestTurn(angle: Float, period: Float): Float =
        angle - period * (angle / period).roundToInt().toFloat()

    /** 与 [TrackEasing.IN_OUT] 同一条曲线：承接段两头慢中间快，才不像被机器拽过去 */
    private fun smoothstep(x: Float): Float = x * x * (3f - 2f * x)

    companion object {
        /** 舞台缩放的上下限（见 [stagePose] 那道夹子为什么必须在） */
        const val MIN_STAGE_SCALE = 0.6f
        const val MAX_STAGE_SCALE = 1.6f
    }
}

/**
 * 归一化旋转值 `1.0` 对应的度数 × 一整圈（`2.0`）= 360°。
 *
 * 整条链路里"一整圈是多少度"只有这一个来源：舞台旋转的折圈、[ActionBlender] 的起点冻结都用它。
 * 写死两个 360 的话，下一版有人调 `stageUnit` 就会让"翻跟斗翻到一半被淡走"。
 */
internal val STAGE_FULL_TURN_DEG: Float
    get() = abs(GenericChannel.STAGE_ROT.stageUnit) * 2f
