package com.menghuan.app.live2d

/**
 * 一张模型自带素材的清单（v0.64.0）。
 *
 * 模型加载成功后由 [Live2dCharacter] 侧发布出来（`motionGroups` / `expressions` 都是它
 * 加载时就有的东西），再由仓库层同步成动作库里"模型自带"那一档。
 *
 * **为什么要同步进库而不是每次现查**：动作库是一个统一列表 ——
 * 用户的"我的动作"、内置的摆手、模型自带的点头动作在同一个选择器里出现，
 * 现查意味着选择器要同时认识"库里的行"和"模型里的东西"两种东西，
 * 而收藏、排序、绑定 id 这三件事就都得写两遍。
 */
data class ModelActionCatalog(
    /** 这张模型的标识（用入口 model3.json 的相对路径），变了就说明换模型了 */
    val modelKey: String = "",
    /** 动作组名 → 组内动作数量（例：`Idle` → 2） */
    val motionGroups: Map<String, Int> = emptyMap(),
    /** 表情名清单（`.exp3.json` 的 Name） */
    val expressions: List<String> = emptyList(),
) {
    /** 同步用的指纹：内容一样就不用重写库 */
    fun signature(): String =
        modelKey + "|" + motionGroups.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" } +
            "|" + expressions.sorted().joinToString(",")

    companion object {
        val EMPTY = ModelActionCatalog()

        const val MOTION_KEY_PREFIX = "native:motion:"
        const val EXPRESSION_KEY_PREFIX = "native:expr:"

        fun motionKey(group: String, index: Int): String = "$MOTION_KEY_PREFIX$group:$index"
        fun expressionKey(name: String): String = "$EXPRESSION_KEY_PREFIX$name"
    }
}
