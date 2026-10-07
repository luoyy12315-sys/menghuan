package com.menghuan.app.realtime

import kotlin.math.sqrt

/**
 * AI 播放音频 → 口型电平（v0.58.0，纯函数，JVM 单测）。
 *
 * 官方 Java Sample 的 [com.live2d.sdk.cubism.framework.motion.CubismLipSyncUpdater] 只负责
 * "把 0..1 的值写进 `ParamMouthOpenY`"，它依赖的 `LAppWavFileHandler.getParameter()`
 * 在示例里是**直接 return 0.0f 的空壳**（源码注释写明"本示例未实现口型"）。
 * 所以"从音频算出口型开度"这一段必须自己实现——就是这里。
 *
 * 形状选的是**RMS + 门限 + 平方根压缩**，不是峰值：
 * - 峰值（abs max）对齿音和爆音过敏，一个 s 音就能把嘴拉到全开；
 * - 裸 RMS 又偏"小声不动、大声糊成一片"，所以过门限后开平方把低段拉开。
 *
 * 平滑（attack / release）**不在这里做**：那是随帧时间变化的量，归视觉引擎管
 * （方案文档 §十一把链路写成"Level/RMS/Envelope → 平滑 → MouthOpenY"正是这个分工）。
 */
object CallAudioLevel {

    /**
     * 噪声门限：RMS 归一化低于这个值就当没在说话。
     *
     * TTS 静音段与呼吸尾巴的 RMS 通常在千分之几这个量级，不过门限的话角色会
     * "没出声也一直在轻轻嚼"（方案文档 §十一点名要解决的"停顿时嘴持续大幅运动"）。
     */
    const val GATE = 0.035f

    /** 一段 16 位 PCM 的归一化 RMS（0..1）。空区间返回 0 */
    fun rms(samples: ShortArray, from: Int, to: Int): Float {
        val start = from.coerceAtLeast(0)
        val end = to.coerceAtMost(samples.size)
        if (end <= start) return 0f
        var sum = 0.0
        for (i in start until end) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / (end - start)).toFloat()
    }

    /**
     * 归一化 RMS → 口型开度（0..1）。
     *
     * 门限以下归零；门限以上线性映射到 0..1 后开平方（低段拉开），
     * 再乘 [CEILING] 封顶——满格张嘴在 Live2D 里非常吓人（"张嘴过大"那条）。
     */
    fun toMouth(rms: Float): Float {
        if (rms <= GATE) return 0f
        val linear = (rms - GATE) / (1f - GATE)
        return (sqrt(linear) * CEILING).coerceIn(0f, 1f)
    }

    /** 口型最大开度上限 */
    const val CEILING = 0.85f

    /** 一步平滑：向上快（咬得住声）、向下慢（不抖），系数按 60fps 一帧给 */
    fun smoothStep(current: Float, target: Float, attack: Float = 0.45f, release: Float = 0.18f): Float {
        val k = if (target > current) attack else release
        return (current + (target - current) * k).coerceIn(0f, 1f)
    }
}
