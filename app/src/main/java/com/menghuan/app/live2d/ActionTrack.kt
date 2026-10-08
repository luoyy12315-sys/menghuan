package com.menghuan.app.live2d

import kotlin.math.pow

/**
 * 关键帧之间的过渡方式（v0.64.0，纯函数）。
 *
 * 刻意只有三种：普通用户面对的是「强度 / 速度 / 时长」三个滑块，
 * 过渡曲线是模板自带的质感，不该变成第四个要用户理解的东西。
 */
enum class TrackEasing {
    /** 线性：机械感，适合"摆"这种来回动作的中间段 */
    LINEAR,

    /** 两头慢中间快：默认，最像人做出来的动作 */
    IN_OUT,

    /** 起步快、收尾慢：适合"甩出去" */
    OUT,
    ;

    fun apply(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> x
            IN_OUT -> x * x * (3f - 2f * x)              // smoothstep
            OUT -> 1f - (1f - x).pow(3f)                  // ease-out cubic
        }
    }
}

/**
 * 一个关键帧：`at` 是**动作进度**（0 = 开头，1 = 结尾，与时长无关），
 * `value` 是归一化幅度。
 *
 * 关于归一化值的约定（这是整套系统的地基，别在别处另立一套）：
 * - 参数通道：**0 是"这个模型此参数的默认值"**，±1 是"可用范围的 60%"。
 *   也就是说 `1.0` 永远表示"这个角色能做到的、幅度最大的自然动作"，
 *   换一张参数范围 ±10° 的模型不会突然把头甩掉。
 * - 舞台通道：1.0 的换算见 [GenericChannel.stageUnit]，允许超出 ±1（翻跟斗写 2.0）。
 */
data class TrackKey(val at: Float, val value: Float) {
    companion object {
        /** 落盘精度：两位小数足够，也让 JSON 短一半（AI 生成的动作尤其长） */
        fun round(v: Float): Float = Math.round(v * 100f) / 100f
    }
}

/**
 * 一条参数轨道：一个通道 + 一串关键帧。
 *
 * 求值 [valueAt] 是纯函数，单测直接打表比对（`ActionTrackTest`）。
 */
data class ParameterTrack(
    val channel: GenericChannel,
    val keys: List<TrackKey>,
    val easing: TrackEasing = TrackEasing.IN_OUT,
) {

    /** 首帧之前取第一个值、末帧之后取最后一个值（不做环绕），这样"停在末态"是自然结果 */
    fun valueAt(progress: Float): Float {
        if (keys.isEmpty()) return 0f
        val p = progress.coerceIn(0f, 1f)
        val first = keys.first()
        if (p <= first.at) return first.value
        val last = keys.last()
        if (p >= last.at) return last.value
        for (i in 0 until keys.size - 1) {
            val a = keys[i]
            val b = keys[i + 1]
            if (p >= a.at && p <= b.at) {
                val span = b.at - a.at
                if (span <= 1e-6f) return b.value
                val local = easing.apply((p - a.at) / span)
                return a.value + (b.value - a.value) * local
            }
        }
        return last.value
    }

    companion object {
        /**
         * 建轨道的便捷入口：`track(CH, 0f to 0f, .5f to 1f, 1f to 0f)`。
         * 关键帧会**按进度排序**并**合并同一进度的重复帧**（后者优先）——
         * AI 生成的动作里这一步经常写乱，排序放在这里，求值函数就不用再防。
         */
        fun of(channel: GenericChannel, easing: TrackEasing = TrackEasing.IN_OUT, vararg keys: Pair<Float, Float>) =
            ParameterTrack(
                channel = channel,
                keys = keys.map { TrackKey(it.first.coerceIn(0f, 1f), it.second) }
                    .sortedBy { it.at }
                    .fold(ArrayList<TrackKey>()) { acc, k ->
                        if (acc.isNotEmpty() && acc.last().at == k.at) acc[acc.size - 1] = k else acc.add(k)
                        acc
                    },
                easing = easing,
            )
    }
}

/**
 * 一条动作在某一帧的"舞台姿态"（v0.64.0）。
 *
 * 只有舞台通道会产出它，由渲染层换算成矩阵 —— **不经过模型参数**，
 * 所以任何模型（哪怕一个标准参数都没有）都能做完整套动作。
 *
 * 全部是归一化值，换算在渲染层（那里才知道画布多大）：
 * [dx] / [dy] 乘 [GenericChannel.stageUnit] × 画布尺寸，[rotationDeg] 直接是度数，
 * [scale] 是倍率（1.0 = 原样）。
 */
data class StagePose(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val rotationDeg: Float = 0f,
    val scale: Float = 1f,
) {
    val isIdentity: Boolean
        get() = dx == 0f && dy == 0f && rotationDeg == 0f && scale == 1f

    companion object {
        val NONE = StagePose()
    }
}
