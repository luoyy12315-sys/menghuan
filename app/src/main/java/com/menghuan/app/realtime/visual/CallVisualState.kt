package com.menghuan.app.realtime.visual

import com.menghuan.app.realtime.RealtimeCallState

/**
 * 通话视觉层的统一状态（v0.58.0，方案文档 §六）。
 *
 * Live2D 和伪视频**共用这一套状态**，两种视觉的差别只体现在"拿什么素材去表达"，
 * 状态定义不各自长一套——否则状态机就散了，也没法统一测（方案文档 §30.4）。
 */
enum class CallVisualState {
    /** 没人说话（AI 没出声、用户也没开口） */
    IDLE,

    /** 用户正在讲话 */
    LISTENING,

    /** 等 LLM/TTS 出结果的间隙 */
    THINKING,

    /** AI 的语音**正在真播**（不是"正在生成"，见 [toVisualState] 的注释） */
    SPEAKING,

    /** 挂断：视觉层立刻停止驱动并释放资源 */
    ENDED,

    /** 视觉层自己出错（素材坏了等）。⚠️ 不代表通话出错 */
    ERROR,
}

/**
 * 通话引擎状态 → 视觉状态（纯函数，JVM 单测的就是这张表）。
 *
 * ⚠️ 名字撞车要小心：引擎的 `LISTENING` 意思是"线路通了、**还没人说话**"，
 * 而视觉的 `LISTENING` 意思是"用户**正在**讲话"。所以
 * `RealtimeCallState.LISTENING → CallVisualState.IDLE`、
 * `RealtimeCallState.USER_SPEAKING → CallVisualState.LISTENING`，
 * 照字面对映必错（方案文档 §七要求 SPEAKING 跟着"真正播放"走，也正是这个意思：
 * 引擎只在 AudioTrack 真出声后才进 AI_SPEAKING，播完/被打断立刻离开）。
 */
fun RealtimeCallState.toVisualState(): CallVisualState = when (this) {
    RealtimeCallState.IDLE,
    RealtimeCallState.CONNECTING,
    RealtimeCallState.CONNECTED,
    RealtimeCallState.LISTENING,
    RealtimeCallState.DISCONNECTING,
    -> CallVisualState.IDLE

    RealtimeCallState.USER_SPEAKING -> CallVisualState.LISTENING
    RealtimeCallState.AI_THINKING -> CallVisualState.THINKING
    RealtimeCallState.AI_SPEAKING -> CallVisualState.SPEAKING

    // barge-in：视觉要**立即**停掉 speaking 转到倾听（方案文档 §七的打断链）
    RealtimeCallState.INTERRUPTED -> CallVisualState.LISTENING

    RealtimeCallState.ENDED -> CallVisualState.ENDED
    RealtimeCallState.ERROR -> CallVisualState.ERROR
}
