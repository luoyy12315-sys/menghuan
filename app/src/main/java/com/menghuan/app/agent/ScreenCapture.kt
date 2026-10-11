package com.menghuan.app.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.menghuan.app.data.VisionImage
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 屏幕截图（v1.0 M4-L 观测层混合输入）：把"无障碍元素清单"升级成 **截图 + 树**。
 *
 * 依据（方案 §4.4）：SOTA 里最强的不是纯视觉也不是纯树，而是两者混合
 * （AutoRPA 截图+树 76.9、mobile-run 84.5、DroidRun+AppCards 88.8）。
 * 元素编号清单仍然保留 —— **动作只能按编号**，截图只用来"看懂这一屏是干什么的"。
 *
 * ⚠️ 硬约束（方案 §6.3-L 已标）：`AccessibilityService.takeScreenshot()` 要 **API 30**，
 * 而 `minSdk = 26`；API 26–29（或服务没声明 `canTakeScreenshot`）一律**静默退回纯树**，
 * 不影响任何现有流程，也不把它做成唯一路径。
 */
object ScreenCapture {

    /** takeScreenshot 的最低 API（低于它只能靠元素清单） */
    const val MIN_API = 30

    /** Agent 看布局与图标，不需要账单识别那种 1536 精度：压小省 token */
    const val MAX_DIM = 1024
    const val JPEG_QUALITY = 78

    /** 截图固定落这一个文件（每步覆盖写，不涨磁盘；也方便复现问题时抓一张） */
    const val FILE_NAME = "agent_screenshot.jpg"

    /** 纯判定（可单测）：系统版本够 + 服务已连接 */
    fun canCapture(sdkInt: Int, serviceConnected: Boolean): Boolean =
        sdkInt >= MIN_API && serviceConnected

    fun supported(): Boolean =
        canCapture(Build.VERSION.SDK_INT, AgentAccessibilityService.instance != null)

    fun fileIn(filesDir: File): File = File(filesDir, FILE_NAME)

    /**
     * 截当前屏并写进 [AgentLoop] 约定的截图文件。
     * @return 图片绝对路径（供多模态通道按 `ChatMessage.imagePath` 读取）；**任何失败返回 null**，
     *   调用方按纯树继续（截不到图不该让任务停下来）。
     */
    suspend fun captureToFile(filesDir: File): String? {
        val service = AgentAccessibilityService.instance ?: return null
        if (Build.VERSION.SDK_INT < MIN_API) return null
        val target = fileIn(filesDir)
        val bitmap = takeScreenshot(service) ?: return null
        val ok = VisionImage.encodeToFile(bitmap, target, MAX_DIM, JPEG_QUALITY)
        bitmap.recycle()
        return if (ok) target.absolutePath else null
    }

    /** takeScreenshot 回调 → 软件位图（HARDWARE 位图不能直接压缩，必须先 copy 回 CPU 内存） */
    private suspend fun takeScreenshot(service: AccessibilityService): Bitmap? =
        suspendCancellableCoroutine { cont ->
            val executor = Executors.newSingleThreadExecutor()
            val handler = Handler(Looper.getMainLooper())
            fun finish(bitmap: Bitmap?) {
                executor.shutdown()
                handler.post { if (cont.isActive) cont.resume(bitmap) }
            }
            val started = runCatching {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            val bitmap = runCatching {
                                val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                hardware?.copy(Bitmap.Config.ARGB_8888, false)
                            }.getOrNull()
                            runCatching { buffer.close() }
                            finish(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            finish(null)
                        }
                    },
                )
            }.isSuccess
            if (!started) finish(null)
        }
}
