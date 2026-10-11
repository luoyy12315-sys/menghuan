package com.menghuan.app.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 动作执行层：tap/swipe = dispatchGesture（无障碍手势，无需 root）；
 * 输入 = ACTION_SET_TEXT（失败降级焦点+粘贴）；back/home = performGlobalAction。
 *
 * 定位口径（v1.0 M2-F）：**resourceId 优先 → 坐标兜底**。
 * 旧的 `findByCenter` 只认快照时的屏幕坐标，页面一变就找不到或点到别的按钮。
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

    /** 点击编号元素：先重新定位（resourceId 优先），再按**当前**节点中心点执行手势 */
    fun tapElement(element: UiElement, allowCoordinateOnly: Boolean): Boolean {
        val node = resolve(element, allowCoordinateOnly) ?: return false
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return tap(rect.centerX(), rect.centerY())
    }

    /** 对编号元素填入文本：重新定位后对可编辑节点（或其可编辑子节点）SET_TEXT */
    fun setText(element: UiElement, value: String, allowCoordinateOnly: Boolean): Boolean {
        val node = resolve(element, allowCoordinateOnly) ?: return false
        val target = if (node.isEditable) node else findEditableSelfOrChild(node) ?: node
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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

    fun back(): Boolean = AgentAccessibilityService.instance
        ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) ?: false

    fun home(): Boolean = AgentAccessibilityService.instance
        ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) ?: false

    /** 当前窗口包名（白名单围栏用） */
    fun currentPackage(): String? =
        AgentAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()

    /**
     * 执行前定位：
     * ① `viewIdResourceName` 命中 → 用它（同一 id 有多个时取**离快照中心最近**的那个，
     *    列表行共享 id 时不会串行）；
     * ② 没有 id 或没找到 → 坐标兜底；
     * ③ [allowCoordinateOnly] = false（= 快照之后界面动过）时，坐标兜底**必须通过标签校验**：
     *    同一坐标上如果已经不是原来那个按钮，宁可不做也不点错。彻底认不出就返回 null（上报"界面已变化"）。
     */
    fun resolve(element: UiElement, allowCoordinateOnly: Boolean): AccessibilityNodeInfo? {
        val root = AgentAccessibilityService.instance?.rootInActiveWindow ?: return null
        if (element.viewId.isNotEmpty()) {
            findByViewId(root, element.viewId, element)?.let { return it }
        }
        return findByCenter(root, element, verifyLabel = !allowCoordinateOnly)
    }

    private fun findByViewId(
        root: AccessibilityNodeInfo,
        viewId: String,
        element: UiElement,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestDist = Long.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++
            if (node.viewIdResourceName == viewId) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                val dx = (rect.centerX() - element.centerX).toLong()
                val dy = (rect.centerY() - element.centerY).toLong()
                val dist = dx * dx + dy * dy
                if (dist < bestDist) {
                    bestDist = dist
                    best = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    private fun findByCenter(
        root: AccessibilityNodeInfo,
        element: UiElement,
        verifyLabel: Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited++
            val rect = Rect().also { node.getBoundsInScreen(it) }
            if (rect.centerX() == element.centerX && rect.centerY() == element.centerY) {
                // 嵌套节点（可点父容器与它的文字子节点）共心，所以校验不过要继续找，不能直接放弃
                if (!verifyLabel || labelMatches(node, element.label)) return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** 坐标复校验：节点文案/描述与快照标签互含，或类型与"无标签元素"的类型一致 */
    private fun labelMatches(node: AccessibilityNodeInfo, label: String): Boolean {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() && (text.contains(label) || label.contains(text))) return true
        if (desc.isNotEmpty() && (desc.contains(label) || label.contains(desc))) return true
        val type = node.className?.toString()?.substringAfterLast('.') ?: ""
        return label == type
    }

    private fun findEditableSelfOrChild(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findEditableSelfOrChild(node.getChild(i) ?: continue)?.let { return it }
        }
        return null
    }
}
