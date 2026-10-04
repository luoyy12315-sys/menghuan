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
) {
    /** 给 LLM/日志看的标签：优先 text，其次 contentDesc */
    val label: String get() = listOf(text, contentDesc).firstOrNull { it.isNotBlank() } ?: type
}

object ScreenState {

    /** 清单上限：控制 token，也防超长对话 */
    const val MAX_ELEMENTS = 40

    /** 节点树 → 编号元素（BFS）。只留可见且（可点/可编辑/可滚/有文本）的节点。 */
    fun snapshot(root: AccessibilityNodeInfo?): List<UiElement> {
        if (root == null) return emptyList()
        val result = mutableListOf<UiElement>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && result.size < MAX_ELEMENTS && visited < 600) {
            val node = queue.removeFirst()
            visited++
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
            val interesting = node.isClickable || node.isEditable || node.isScrollable || hasText
            if (rect.width() > 0 && rect.height() > 0 && interesting) {
                val cls = node.className?.toString() ?: "View"
                result += UiElement(
                    index = result.size,
                    type = cls.substringAfterLast('.'),
                    text = node.text?.toString()?.trim()?.take(30) ?: "",
                    contentDesc = node.contentDescription?.toString()?.trim()?.take(30) ?: "",
                    centerX = rect.centerX(),
                    centerY = rect.centerY(),
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                )
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    /** 编号元素 → LLM 可读清单文本 */
    fun toPrompt(elements: List<UiElement>): String = buildString {
        elements.forEach { e ->
            append("[${e.index}] ${e.type}")
            val label = e.label
            if (label != e.type) append(" \"$label\"")
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
