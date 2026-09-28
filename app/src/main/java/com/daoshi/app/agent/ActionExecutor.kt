package com.daoshi.app.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 动作执行层：tap/swipe = dispatchGesture（无障碍手势，无需 root）；
 * 输入 = ACTION_SET_TEXT（失败降级焦点+粘贴）；back/home = performGlobalAction。
 */
object ActionExecutor {

    fun tap(x: Int, y: Int): Boolean {
        val service = AgentAccessibilityService.instance ?: return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    fun swipe(dir: String): Boolean {
        val service = AgentAccessibilityService.instance ?: return false
        val metrics = service.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val path = Path()
        when (dir) {
            "up" -> { path.moveTo(w / 2f, h * 0.7f); path.lineTo(w / 2f, h * 0.3f) }
            "down" -> { path.moveTo(w / 2f, h * 0.3f); path.lineTo(w / 2f, h * 0.7f) }
            else -> return false
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    /** 对编号元素填入文本：按 bounds 中心在当前窗口里找回节点 */
    fun setText(element: UiElement, value: String): Boolean {
        val node = findByCenter(element) ?: return false
        val target = node.let { if (it.isEditable) it else findEditableSelfOrChild(node) } ?: node
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun back(): Boolean = AgentAccessibilityService.instance
        ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false

    fun home(): Boolean = AgentAccessibilityService.instance
        ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false

    /** 当前窗口包名（白名单围栏用） */
    fun currentPackage(): String? =
        AgentAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()

    private fun findByCenter(element: UiElement): AccessibilityNodeInfo? {
        val root = AgentAccessibilityService.instance?.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++
            val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            if (rect.centerX() == element.centerX && rect.centerY() == element.centerY) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findEditableSelfOrChild(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findEditableSelfOrChild(node.getChild(i) ?: continue)?.let { return it }
        }
        return null
    }
}
