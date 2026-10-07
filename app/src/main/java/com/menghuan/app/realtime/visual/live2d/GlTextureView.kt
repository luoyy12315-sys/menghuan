package com.menghuan.app.realtime.visual.live2d

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import android.view.TextureView

/**
 * **TextureView 版 GL 承载**（v0.61.0），替代 `GLSurfaceView`。
 *
 * 为什么非换不可（v0.58~v0.60.4 在 `GLSurfaceView` 上绕了四版）：
 * `SurfaceView` 的画面是一块**独立于窗口的曲面**，只有两种摆法 ——
 * · 排在窗口**下面**：靠"在窗口上挖洞"露出来，而 Compose 的 `AndroidView` 里
 *   **整棵 Compose 绘制树比子 View 先画**，洞会把同屏顶栏/字幕/挂断一起擦掉，这条路不成立；
 * · `setZOrderOnTop` 排到窗口**上面**：洞不用挖了，但角色会**盖住它覆盖范围内的 UI**
 *   （真机反馈"挡住了挂断键"）。
 * `TextureView` 不走曲面合成，它就是一个正常绘制的内容视图：
 * **透明处透出下面的 Compose，UI 永远画在它上面**，两头都占。
 *
 * 代价是 `GLSurfaceView` 免费给的三件事这里要自己做，都收在这个类里：
 * EGL 上下文、渲染线程、surface 生命周期。
 */
class GlTextureView(
    context: Context,
    private val renderer: GLSurfaceView.Renderer,
) : TextureView(context), TextureView.SurfaceTextureListener {

    private var thread: GlRenderThread? = null

    init {
        // 不透明 = 底下没有东西也填黑；通话页要的是"角色以外透出页面"，必须 false
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        st.setDefaultBufferSize(width, height)
        val t = GlRenderThread(Surface(st), renderer, width, height)
        thread = t
        t.start()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        st.setDefaultBufferSize(width, height)
        thread?.resize(width, height)
    }

    /** 返回 true = 交给系统释放 SurfaceTexture；线程必须先停干净，否则 EGL 还绑着一块死 surface */
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        thread?.let { it.quit(); it.joinQuietly() }
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    /** 页面切后台：停画但**不停线程、不销毁上下文**（回来只要接着画） */
    fun pauseRendering() { thread?.setPaused(true) }

    fun resumeRendering() { thread?.setPaused(false) }

    /** 视图还在但引擎要被拆掉：先把 GL 线程停干净，再让调用方释放模型 */
    fun shutdown() {
        thread?.let { it.quit(); it.joinQuietly() }
        thread = null
    }
}

/**
 * 最小可用的 EGL + 渲染循环。
 *
 * 刻意不复刻 `GLSurfaceView` 的全部机制（多档 config 回退、context 保留、queueEvent 队列）：
 * Live2D 这一路只有一帧循环、一个线程，模型自己的跨线程输入全走 volatile
 * （见 [Live2dCharacter.setMouthLevel] / [Live2dCharacter.requestState]），不需要事件队列。
 */
private class GlRenderThread(
    private val surface: Surface,
    private val renderer: GLSurfaceView.Renderer,
    width: Int,
    height: Int,
) : Thread("Live2dGlThread") {

    @Volatile private var quit = false
    @Volatile private var paused = false
    @Volatile private var pendingWidth = width
    @Volatile private var pendingHeight = height
    @Volatile private var sizeChanged = true

    fun setPaused(value: Boolean) { paused = value }

    fun resize(width: Int, height: Int) {
        pendingWidth = width
        pendingHeight = height
        sizeChanged = true
    }

    fun quit() { quit = true }

    fun joinQuietly() {
        runCatching { join(JOIN_TIMEOUT_MS) }
    }

    override fun run() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            Log.e(TAG, "eglGetDisplay failed")
            surface.release()
            return
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            Log.e(TAG, "eglInitialize failed")
            surface.release()
            return
        }
        val config = chooseConfig(display) ?: run {
            Log.e(TAG, "no usable EGLConfig")
            EGL14.eglTerminate(display)
            surface.release()
            return
        }
        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (context == null || context === EGL14.EGL_NO_CONTEXT) {
            Log.e(TAG, "eglCreateContext failed")
            EGL14.eglTerminate(display)
            surface.release()
            return
        }
        val eglSurface = EGL14.eglCreateWindowSurface(
            display, config, surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        if (eglSurface == null || eglSurface === EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed")
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            surface.release()
            return
        }
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            Log.e(TAG, "eglMakeCurrent failed")
        } else {
            loop(display, eglSurface, context)
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        surface.release()
    }

    /**
     * 显式要 **8 位 alpha** 的配置。
     * `GLSurfaceView` 时代这条是靠 `setEGLConfigChooser` 表达的；TextureView 这里没有那个入口，
     * 少了 ALPHA_SIZE 就会拿到 RGBX8888 —— 清屏色里的 a=0 存不下来，
     * 角色背后会多出一块不透明的方框（v0.60.3 真机就是这块白）。
     */
    private fun chooseConfig(display: EGLDisplay): EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] < 1) {
            return null
        }
        return configs[0]
    }

    private fun loop(display: EGLDisplay, eglSurface: EGLSurface, context: EGLContext) {
        var created = false
        var lastW = -1
        var lastH = -1
        while (!quit) {
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                Log.e(TAG, "eglMakeCurrent lost, stop rendering")
                return
            }
            if (!created) {
                // 官方 Sample 同样是在 surface 就绪后、拿到窗口尺寸时才建渲染器与离屏缓冲
                renderer.onSurfaceCreated(null, null)
                created = true
            }
            if (sizeChanged) {
                val w = pendingWidth
                val h = pendingHeight
                sizeChanged = false
                if (w > 0 && h > 0 && (w != lastW || h != lastH)) {
                    lastW = w
                    lastH = h
                    renderer.onSurfaceChanged(null, w, h)
                }
            }
            if (paused) {
                sleepQuietly(IDLE_MS)
                continue
            }
            runCatching { renderer.onDrawFrame(null) }
                .onFailure { Log.e(TAG, "live2d frame threw", it) }
            if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                // surface 被销毁（视图拆掉）时会走到这里，退出循环让 run() 收尾
                Log.e(TAG, "eglSwapBuffers failed, stop rendering")
                return
            }
        }
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { sleep(ms) }
    }

    private companion object {
        const val TAG = "GlRenderThread"
        const val JOIN_TIMEOUT_MS = 1_000L
        const val IDLE_MS = 20L
    }
}
