package com.menghuan.app.live2d

/**
 * 互动区域（v0.64.0）。
 *
 * 三段而不是"头部 / 身体"两段：用户明确要 **HEAD / BODY / OTHER** 三个，
 * 第三段是"点到了角色以外"（背景/空白），它必须有独立的一档 ——
 * 否则点空白会掉进"身体"里，出现"我明明点的是旁边，人却在摆手"的怪事。
 *
 * 与 Live2D 官方约定的对应关系（调研结论，别另发明一套）：
 * - `model3.json` 的 `HitAreas` 里 `Name` 取值就是 `Head` / `Body`（还有 `Left` / `Right`），
 *   官方配套的动作组是 `TapBody` / `TapLeft` / `TapRight` / `Tap`；
 * - 大量免费模型这块是**空的**（真机用的"奶蛙"包就是），所以必须有几何兜底。
 *
 * 以后要加 `Left` / `Right` / 自定义区域，就是往这个枚举里加一项 +
 * 在 [InteractionRules.fromHitAreas] 里加一条分支 —— 这也是"区域系统不写死"的落点。
 */
enum class InteractionRegion(val key: String, val label: String) {
    HEAD("HEAD", "头部"),
    BODY("BODY", "身体"),
    OTHER("OTHER", "其他"),
    ;

    companion object {
        fun fromKey(raw: String?): InteractionRegion =
            entries.firstOrNull { it.key.equals(raw, ignoreCase = true) } ?: OTHER
    }
}

/**
 * 区域判定规则（v0.64.0，纯函数，JVM 单测）。
 *
 * 两级判定，先精确后兜底 —— 顺序不能反：几何兜底在"角色只占了屏幕一小块"时
 * 会把点击背景也判成头/身体，而模型自带的 HitArea 是美术在 Cubism Editor 里
 * 亲手画的，一定更准。
 */
object InteractionRules {

    /** 官方 HitArea 的名字（`model3.json` 的 `HitAreas[].Name`），大小写不敏感地匹配 */
    const val HIT_AREA_HEAD = "Head"
    const val HIT_AREA_BODY = "Body"

    /** 兜底分区：角色包围盒的上 30% 算头，30%–74% 算身体，其余算其他 */
    const val HEAD_BAND = 0.30f
    const val BODY_BAND = 0.74f

    /**
     * 第一级：模型自带 HitArea 的判定结果。
     * 注意**头优先于身体**：很多模型的身体 HitArea 会把脑袋一起圈进去，
     * 先判身体的话点脸也会当成点身体（官方 Sample 也是这个顺序）。
     */
    fun fromHitAreas(headHit: Boolean, bodyHit: Boolean): InteractionRegion = when {
        headHit -> InteractionRegion.HEAD
        bodyHit -> InteractionRegion.BODY
        else -> InteractionRegion.OTHER
    }

    /**
     * 第二级（兜底）：按"触点落在角色包围盒里的相对高度"分区。
     *
     * @param normalizedY 0 = 角色包围盒顶部，1 = 底部；**null = 根本没点在角色身上** → OTHER
     */
    fun fromBands(normalizedY: Float?): InteractionRegion {
        val y = normalizedY ?: return InteractionRegion.OTHER
        if (y < 0f || y > 1f) return InteractionRegion.OTHER
        return when {
            y <= HEAD_BAND -> InteractionRegion.HEAD
            y <= BODY_BAND -> InteractionRegion.BODY
            else -> InteractionRegion.OTHER
        }
    }
}

/**
 * 互动 → 动作的绑定（v0.64.0）。
 *
 * ⚠️ 这一版是**代码里的默认值**，没有做配置界面（用户拍板"只做默认行为 + 预留结构"）。
 * 之所以还是抽成一个数据类而不是直接写 `when`：下一版加配置界面时，
 * 只要把这里的默认值换成"从库里读出来的一行"，**调用方一行都不用改** ——
 * 这就是用户要的"预留结构"。
 *
 * 默认值照用户给的例子：
 * - 点头部 → 摇头
 * - 点身体 → 摆手
 * - 点其他 → 从趣味动作里随机一个
 */
data class InteractionBindings(
    val headBuiltinKey: String = BuiltinActions.SHAKE,
    val bodyBuiltinKey: String = BuiltinActions.WAVE,
    val otherRandomKeys: List<String> = BuiltinActions.funKeys,
) {
    /** 这一档指向哪个内置动作的键；[InteractionRegion.OTHER] 需要随机，返回 null */
    fun builtinKeyOf(region: InteractionRegion): String? = when (region) {
        InteractionRegion.HEAD -> headBuiltinKey
        InteractionRegion.BODY -> bodyBuiltinKey
        InteractionRegion.OTHER -> null
    }

    /**
     * 随机池里挑一个。用 [seed] 而不是 `Random` 默认源，是为了让单测能钉住结果；
     * 实机上调用方传 `System.currentTimeMillis()`。
     */
    fun randomKeyFor(seed: Long): String? {
        if (otherRandomKeys.isEmpty()) return null
        val i = ((seed % otherRandomKeys.size) + otherRandomKeys.size).toInt() % otherRandomKeys.size
        return otherRandomKeys[i]
    }

    companion object {
        val DEFAULT = InteractionBindings()
    }
}
