package com.menghuan.app.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 屏幕状态压缩（DroidRun 式控件编号）：把无障碍节点树压成编号清单给 LLM 读。
 * 编号即动作地址——LLM 只需返回编号，执行层按编号找节点。
 */
data class UiElement(
    val index: Int,
    val type: String,       // Button / EditText / CheckBox / View …
    val text: String,
    val contentDesc: String,
    val centerX: Int,
    val centerY: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    /** resourceId（`viewIdResourceName`）：执行前**优先**用它回找节点，坐标只作兜底 */
    val viewId: String = "",
    /** 出生时的窗口 tick：执行前与当前 tick 不一致 = 界面在期间动过，坐标不再可信 */
    val snapTick: Int = 0,
    /** 被折叠掉的同型重复节点数（`×N`，N 含自己那一个；1 = 未折叠） */
    val repeatCount: Int = 1,
) {
    /** 给 LLM/日志看的标签：优先 text，其次 contentDesc */
    val label: String get() = listOf(text, contentDesc).firstOrNull { it.isNotBlank() } ?: type

    /**
     * 「同类」判据：类型 + **真实标签**（text/contentDesc）。
     * ⚠️ 没有真实文案的节点返回 null（**不折叠**）—— 一排纯图标按钮类型相同但作用完全不同，
     * 折叠掉会让模型拿不到"哪一个"。
     */
    internal val sameShapeKey: String?
        get() = listOf(text, contentDesc).firstOrNull { it.isNotBlank() }?.let { "$type|$it" }
}

object ScreenState {

    /** 清单上限：控制 token，也防超长对话 */
    const val MAX_ELEMENTS = 40

    /** 同一形状（type + label）最多保留几个，其余折叠成 `×N` */
    const val REPEAT_KEEP = 3

    /** 给模型看的标签截断长度（**只影响清单文本**，`UiElement` 内部值保持完整） */
    const val LABEL_MAX = 20

    /**
     * 节点树 → 编号元素（BFS），再经 [compact] 瘦身。
     * [tick] = 采集瞬间的窗口 tick（`AgentAccessibilityService.windowTick`），随元素一起带走，
     * 执行前用它判「这一屏在我们思考期间动过没有」。
     * BFS 阶段仍按「可见且（可点/可编辑/可滚/有文本）」收集，**准入收紧与折叠统一在 [compact] 做**
     * （纯函数、可单测；也让兜底逻辑只有一处）。
     */
    fun snapshot(root: AccessibilityNodeInfo?, tick: Int): List<UiElement> {
        if (root == null) return emptyList()
        val raw = mutableListOf<UiElement>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && raw.size < 600 && visited < 600) {
            val node = queue.removeFirst()
            visited++
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val interesting = node.isClickable || node.isEditable || node.isScrollable ||
                text.isNotBlank() || desc.isNotBlank()
            if (rect.width() > 0 && rect.height() > 0 && interesting) {
                raw += UiElement(
                    index = raw.size,
                    type = node.className?.toString()?.substringAfterLast('.') ?: "View",
                    text = text,
                    contentDesc = desc,
                    centerX = rect.centerX(),
                    centerY = rect.centerY(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    viewId = node.viewIdResourceName?.toString().orEmpty(),
                    snapTick = tick,
                )
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return compact(raw)
    }

    /**
     * 清单瘦身（纯函数）：
     * ① **准入收紧**：`hasText` 会把纯文本装饰节点（标题/价格/说明）全放进来，40 条里可能一半模型永远不会点
     *    → 只留 `clickable || editable || scrollable`（scrollable 在清单文本里带标记）；
     *    **纯文本节点仅当这一屏没有任何可交互元素时才作为兜底发出**（避免模型无处可点）。
     * ② **去重**：形状相同（`type + 真实文案`）的成排按钮只留前 [REPEAT_KEEP] 个，其余折叠，在留下的那条上标 `×N`。
     *    ⚠️ **没有真实文案的节点不参与折叠** —— 一排纯图标按钮类型相同但作用完全不同，折叠掉会让模型拿不到"哪一个"。
     */
    fun compact(raw: List<UiElement>): List<UiElement> {
        val interactive = raw.filter { it.clickable || it.editable || it.scrollable }
        val candidates = if (interactive.isEmpty()) raw else interactive
        // 先折叠、再截断：否则成排的重复按钮会把 40 个名额吃光，后面的不同元素反而进不来
        val totals = candidates.mapNotNull { it.sameShapeKey }.groupingBy { it }.eachCount()
        val used = mutableMapOf<String, Int>()
        val kept = mutableListOf<UiElement>()
        for (e in candidates) {
            val key = e.sameShapeKey
            if (key == null) {
                kept += e
                continue
            }
            val n = (used[key] ?: 0) + 1
            used[key] = n
            if (n <= REPEAT_KEEP) {
                // 总数只标在该形状的第一条上，其余保持 1
                kept += if (n == 1) e.copy(repeatCount = totals[key] ?: 1) else e
            }
        }
        return kept.take(MAX_ELEMENTS).mapIndexed { i, e -> e.copy(index = i) }
    }

    /** 编号元素 → LLM 可读清单文本（标签截断 [LABEL_MAX] 字，重复节点标 `×N`） */
    fun toPrompt(elements: List<UiElement>): String = buildString {
        elements.forEach { e ->
            append("[${e.index}] ${e.type}")
            val label = e.label.take(LABEL_MAX)
            if (label != e.type) append(" \"$label\"")
            if (e.repeatCount > 1) append(" ×${e.repeatCount}")
            append(" center=(${e.centerX},${e.centerY})")
            val flags = buildList {
                if (e.clickable) add("clickable")
                if (e.editable) add("editable")
                if (e.scrollable) add("scrollable")
            }
            if (flags.isNotEmpty()) append(" ${flags.joinToString(",")}")
            append('\n')
        }
    }
}
