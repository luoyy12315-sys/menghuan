package com.menghuan.app.realtime.visual

import com.menghuan.app.live2d.ActionSlot

/**
 * 通话视觉状态 → 动作槽（v0.64.0，纯函数）。
 *
 * 之所以要一层显式映射而不是让动作引擎直接认 [CallVisualState]：
 * 动作系统还要服务「角色 Live2D 空间」，而角色空间与通话毫无关系（用户第 1 条要解耦）。
 * 让引擎去 import 通话的枚举，方向就反了 —— 通话依赖动作，不是反过来。
 *
 * ⚠️ 映射是**字面**的，不像引擎状态那样有"同名不同义"的坑
 * （那是 `RealtimeCallState.LISTENING` = 线路通 vs `CallVisualState.LISTENING` = 用户在说话，
 * 见 [toVisualState]）。这里两边都已经在同一套语义里了：
 * `CallVisualState.LISTENING` = 用户正在讲话 = 角色在倾听。
 * `ENDED` / `ERROR` 没有对应动作，一律回待机 —— 挂断时角色不该还摆着"惊讶"的脸。
 */
fun CallVisualState.toActionSlot(): ActionSlot = when (this) {
    CallVisualState.SPEAKING -> ActionSlot.SPEAKING
    CallVisualState.THINKING -> ActionSlot.THINKING
    CallVisualState.LISTENING -> ActionSlot.LISTENING
    CallVisualState.IDLE,
    CallVisualState.ENDED,
    CallVisualState.ERROR,
    -> ActionSlot.IDLE
}
