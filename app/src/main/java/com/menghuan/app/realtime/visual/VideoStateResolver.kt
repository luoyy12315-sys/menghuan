package com.menghuan.app.realtime.visual

import com.menghuan.app.data.CallVisualSlots

/**
 * 伪视频的状态素材匹配（v0.58.0，纯函数，JVM 单测）。
 *
 * 回退链就是方案文档 §十六那张表，本期做到第三档为止：
 *
 *   精确状态素材 → idle → （没有 idle）无视觉/头像降级
 *
 * `default` 不参与匹配：[CallVisualManifestCodec] 已经把素材包里的 `default` 读成 idle，
 * 两处都认会让"到底播哪条"变成第二个真相。
 *
 * 以后加情绪（方案文档 §二十七）是往这张表里加"先看 `speaking_happy` 再看 `speaking`"，
 * metadata 的 `states` 是 map，结构不用动。
 */
object VideoStateResolver {

    /** 通话状态对应要播哪个状态槽 */
    fun slotFor(state: CallVisualState): String = when (state) {
        CallVisualState.IDLE -> CallVisualSlots.IDLE
        CallVisualState.LISTENING -> CallVisualSlots.LISTENING
        CallVisualState.THINKING -> CallVisualSlots.THINKING
        CallVisualState.SPEAKING -> CallVisualSlots.SPEAKING
        // 挂断与出错都回落到待机：视觉停在最不突兀的一帧，而不是冻在张嘴那一帧
        CallVisualState.ENDED, CallVisualState.ERROR -> CallVisualSlots.IDLE
    }

    /**
     * 从已绑定的状态表里挑一条能播的。
     *
     * [bound] 是 `状态键 → 素材文件名`，**只包含此刻文件真存在的那些**
     * （存在性由调用方用 CallVisualStore 判掉，素材被人手工删了也走同一条回退）。
     * 返回 null 表示连待机都没有 → 视觉层降级为无形象，通话继续。
     */
    fun pick(bound: Map<String, String>, state: CallVisualState): String? {
        val slot = slotFor(state)
        bound[slot]?.takeIf { it.isNotBlank() }?.let { return it }
        if (slot == CallVisualSlots.IDLE) return null
        return bound[CallVisualSlots.IDLE]?.takeIf { it.isNotBlank() }
    }
}
