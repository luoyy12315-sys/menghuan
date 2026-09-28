package com.daoshi.app.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 无障碍服务：Agent 的感知 + 执行入口（canRetrieveWindowContent + canPerformGestures）。
 * - 单例引用供 ActionExecutor / AgentLoop 访问当前窗口根节点
 * - windowTick：窗口变化信号（AgentLoop 等界面稳定用）
 * - 运行期间悬浮「■ 停止」按钮（TYPE_ACCESSIBILITY_OVERLAY，无需悬浮窗权限）
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        /** 窗口变化计数（窗口切换 / 内容变化 +1），AgentLoop 据此等界面稳定 */
        val windowTick = MutableStateFlow(0)

        /** 服务是否已连接（系统设置启用后 onServiceConnected 置位） */
        fun isRunning(): Boolean = instance != null

        /** 服务是否已在系统无障碍设置里启用（连接前也能查到） */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any {
                it.startsWith(context.packageName) && it.contains(AgentAccessibilityService::class.java.name)
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        hideStopOverlay()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> windowTick.value += 1
        }
    }

    override fun onInterrupt() = Unit

    // ===== 悬浮停止按钮 =====

    private var overlayView: TextView? = null

    fun showStopOverlay(onStop: () -> Unit) {
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "■ 停止"
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC1A1A1A.toInt())
            textSize = 14f
            setPadding(28, 16, 28, 16)
            elevation = 12f
            setOnClickListener { onStop() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 160
        }
        runCatching {
            wm.addView(view, params)
            overlayView = view
        }
    }

    fun hideStopOverlay() {
        val view = overlayView ?: return
        overlayView = null
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        }
    }

    /** 跳系统无障碍设置（服务未开启时的引导） */
    fun openAccessibilitySettings() {
        startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
