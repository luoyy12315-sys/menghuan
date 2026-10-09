package com.menghuan.app.live2d

/**
 * 角色空间的**捏合缩放**（v0.82.0 P2-2）。
 *
 * 刻意做成"一个 Float 进、一个 Float 出"的纯函数：手感这种东西最难在真机上调
 * （两只手指头搓来搓去没法断点），能单测就先单测。
 *
 * - [apply]：把"这一次双指又放大了多少"叠到当前倍数上。越界的部分**减速但依旧跟手**
 *   （橡皮筋），松手后再由 ViewModel 弹回边界；
 * - [settleTarget]：松手那一刻该去的位置 —— 在范围内就原地不动，**超出就回到边界**。
 *
 * 橡皮筋为什么只能"减速"而不能照单全收：照单收的话手指一滑就能把角色拽到看不见的尺寸
 * （放大到只剩一只眼睛、或者缩小成一个点）；完全不跟又像卡住了。
 * 这里取 0.35 —— 手指多撑开一倍，画面只多涨三分之一，手感上"顶住了"。
 *
 * ⚠️ 越界量还有一道**硬顶**（[MAX_SLACK]）：橡皮筋负责手感，不负责无限拉伸。
 *
 * ⚠️ 缩放只落在**渲染矩阵**上（[com.menghuan.app.realtime.visual.live2d.Live2dCharacter.fitProjection]
 * 的 scale 通道），一个模型参数都不碰 —— 动作引擎铁律③，这样任何模型都能缩放，
 * 不会因为缺 `ParamBodyAngleY` 之类就整段失效。
 */
object PinchScale {

    /** 最小 0.5×：再小角色就只剩一条缝了 */
    const val MIN = 0.5f

    /** 最大 2.0×：再大就顶到画面外，看着像被裁掉 */
    const val MAX = 2.0f

    /** 橡皮筋最多能越界多少（超过就钉住，见类注释） */
    const val MAX_SLACK = 0.4f

    /** 越界部分的跟手比例（0 = 完全顶住，1 = 完全跟手） */
    private const val RESISTANCE = 0.35f

    /** 叠一次捏合：范围内照算，越界减速，再超硬顶就钉住 */
    fun apply(scale: Float, factor: Float): Float {
        val raw = scale * factor
        // 越界的部分按 [RESISTANCE] 减速跟手（橡皮筋）…
        val stretched = when {
            raw > MAX -> MAX + (raw - MAX) * RESISTANCE
            raw < MIN -> MIN - (MIN - raw) * RESISTANCE
            else -> raw
        }
        // …再由 [MAX_SLACK] 钉死：橡皮筋负责手感，不负责无限拉伸。
        // ⚠️ 夹的是**结果**不是 raw —— 夹 raw 的话 MAX_SLACK 描述的是"手指越界多少"，
        //    与字段注释"最多能越界多少"对不上，读代码的人会算错上限。
        return stretched.coerceIn(MIN - MAX_SLACK, MAX + MAX_SLACK)
    }

    /** 松手后该停在哪：范围内原地不动，越界回到最近的边界 */
    fun settleTarget(scale: Float): Float = scale.coerceIn(MIN, MAX)
}
