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
     * 把一次点击翻成**给模型听的一句人话**（v0.71.0，"触碰→对话"那一条链）。
     *
     * 两条讲究：
     * - **说"刚才做了什么"，不说坐标也不说内部枚举名**。模型拿到 `HEAD` 或 `0.12,-0.4`
     *   只会瞎猜；拿到"用户摸了摸你的头"就能直接接话。
     *   （爱语那边有 11 个部位，我们只有三区 —— 因为免费模型的 HitArea 基本只画了 Head/Body，
     *   硬凑出"左手右手"会在一大半模型上说出没发生过的事，那比粗更糟。）
     * - **`OTHER` 不许谎称被碰到**：点到画面空白处就是空白处，
     *   告诉模型"用户碰了碰你的身体"是让它对一个没发生的接触表情绪。
     */
    fun spokenActionOf(region: InteractionRegion): String = when (region) {
        InteractionRegion.HEAD -> "摸了摸你的头"
        InteractionRegion.BODY -> "轻轻碰了碰你的身体"
        InteractionRegion.OTHER -> "点了一下画面，但没点到你的身上"
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
 * 互动 → 动作的绑定（v0.64.0 建结构，v0.74.0 接上配置界面）。
 *
 * v0.64.0 时这只是**代码里的默认值**（用户当时拍板"只做默认行为 + 预留结构"）；
 * v0.74.0 起用户可以进「点击互动」界面逐个指定，值从库里读出来填进这三个字段 ——
 * 调用方（[com.menghuan.app.data.Live2dActionRepository.resolveInteraction] 那一链）一行都没改，
 * 这正是当初预留结构时要的效果。
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
    /**
     * 用户实际挑的那条动作 id（v0.74.0）；0 = 用内置默认。
     *
     * 与 [headBuiltinKey] / [bodyBuiltinKey] 并存而不是二选一：内置默认那一套**永远要在**，
     * 因为用户挑的那条动作可能被删除（仓库层会把绑定收回 0），这时得有个能落回去的默认。
     */
    val headActionId: Long = 0,
    val bodyActionId: Long = 0,
) {
    /** 这一档指向哪个内置动作的键；[InteractionRegion.OTHER] 需要随机，返回 null */
    fun builtinKeyOf(region: InteractionRegion): String? = when (region) {
        InteractionRegion.HEAD -> headBuiltinKey
        InteractionRegion.BODY -> bodyBuiltinKey
        InteractionRegion.OTHER -> null
    }

    /** 这一档用户指定的动作 id（0 = 没指定，走内置默认） */
    fun actionIdOf(region: InteractionRegion): Long = when (region) {
        InteractionRegion.HEAD -> headActionId
        InteractionRegion.BODY -> bodyActionId
        InteractionRegion.OTHER -> 0L
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
