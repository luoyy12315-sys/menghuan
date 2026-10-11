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
import java.util.concurrent.locks.LockSupport

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

    /**
     * 页面要求的暂停状态。
     *
     * ⚠️ 必须存下来、并且交给**新建的那条线程**：`onSurfaceTextureAvailable` 每次曲面可用
     * 都会重开一条线程，而切后台期间转屏 / 分屏 / 回前台重测都会走这条路。
     * 不继承的话，"锁屏 + 转一下手机"就会让渲染在后台悄悄跑起来（省电那条要求当场失效）。
     */
    private var pausedByPage = false

    init {
        // 不透明 = 底下没有东西也填黑；通话页要的是"角色以外透出页面"，必须 false
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        st.setDefaultBufferSize(width, height)
        val t = GlRenderThread(Surface(st), renderer, width, height)
        t.setPaused(pausedByPage)
        thread = t
        t.start()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        st.setDefaultBufferSize(width, height)
        thread?.resize(width, height)
    }

    /**
     * 返回 true = 交给系统释放 SurfaceTexture；返回 false = 由我们自己稍后释放。
     *
     * v0.97.0 修：以前**无论线程有没有停干净都返回 true**，把 `shutdown()` 的读数丢掉了。
     * 线程若还卡在一帧里，系统随即销毁 SurfaceTexture，而 GL 线程仍在 `eglSwapBuffers`
     * 或不定期 `eglMakeCurrent` 引用它 —— 切页/转屏时偶发 native 崩溃。
     * 现在：停干净了才交还（true）；没停干净就返回 false，并交给一条守护线程
     * **等它退出后再 release**（既不让系统在用时销毁它，也不靠 GC 兜底）。
     */
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        val t = thread
        val stopped = shutdown()
        if (stopped) return true
        Thread {
            runCatching { t?.join() }   // 守护线程，等多久都无所谓
            runCatching { st.release() }
        }.apply {
            isDaemon = true
            name = "GlSurfaceTextureReaper"
        }.start()
        return false
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    /** 页面切后台：停画但**不停线程、不销毁上下文**（回来只要接着画） */
    fun pauseRendering() {
        pausedByPage = true
        thread?.setPaused(true)
    }

    fun resumeRendering() {
        pausedByPage = false
        thread?.setPaused(false)
    }

    /** 只叫它停，**不在当前线程等**：视图被换掉时用这条（调用点是主线程的组合期） */
    fun quitSoon() { thread?.quit() }

    /**
     * 停线程并等它真的退出。
     *
     * @return true = 线程已经不在了（调用方可以安全拆模型）；
     *         false = 预算内没等到（有一帧卡住），**绝不要**在此刻从外面释放它正在用的资源。
     */
    fun shutdown(): Boolean {
        val t = thread ?: return true
        t.quit()
        val stopped = t.joinQuietly()
        thread = null
        return stopped
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

    /**
     * 暂停用 `LockSupport.parkNanos` 睡着，不是"醒来一次做一次 EGL 调用再查一遍"。
     *
     * 原来暂停期间每 20ms 也要跑满一圈：先 `eglMakeCurrent`（那是真的 EGL 调用）、
     * 才看到 paused、再 sleep。锁屏一夜就是几百万次空转的 EGL 调用与线程唤醒。
     * 用 park 而不是监视器：Kotlin 的 `Any` 类型看不到 `wait/notify`，
     * 而这里要的只是"能被打断的睡觉"，`unpark` 还会留许可，不会把唤醒弄丢。
     */
    @Volatile private var paused = false

    @Volatile private var pendingWidth = width
    @Volatile private var pendingHeight = height
    @Volatile private var sizeChanged = true

    fun setPaused(value: Boolean) {
        paused = value
        if (!value) LockSupport.unpark(this)
    }

    fun resize(width: Int, height: Int) {
        pendingWidth = width
        pendingHeight = height
        sizeChanged = true
    }

    fun quit() {
        quit = true
        // 睡着的那条要立刻醒来收尾：否则它最坏多睡一个 park 超时，
        // 而挂断时主线程正卡在 join 上等它
        LockSupport.unpark(this)
    }

    /**
     * @return true = 线程确实退了；false = 被中断或超时还活着。
     * 调用方（[GlTextureView.shutdown]）要靠这个读数决定能不能去拆模型。
     */
    fun joinQuietly(): Boolean = runCatching {
        join(JOIN_TIMEOUT_MS)
        !isAlive
    }.getOrDefault(false)

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
        var nextFrameAt = System.nanoTime()
        while (!quit) {
            // 暂停的判断必须排在 eglMakeCurrent **之前**：停画期间一个 EGL 调用都不该发
            if (paused) {
                LockSupport.parkNanos(PAUSE_PARK_NANOS)
                // 睡了一觉回来要重新起表：否则循环会以为"欠了几百帧"，一路狂补
                nextFrameAt = System.nanoTime()
                continue
            }
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
            runCatching { renderer.onDrawFrame(null) }
                .onFailure { Log.e(TAG, "live2d frame threw", it) }
            if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                // surface 被销毁（视图拆掉）时会走到这里，退出循环让 run() 收尾
                Log.e(TAG, "eglSwapBuffers failed, stop rendering")
                return
            }
            nextFrameAt = pace(nextFrameAt)
        }
    }

    /**
     * 帧节拍：把下一帧的期限算出来，该睡就睡（见 [FRAME_INTERVAL_NS] 为什么必须是 60fps）。
     *
     * 落后太多就重新起表而不是追帧 —— 追帧的表现是"回前台那几秒角色飞快地抖"，
     * 而 Live2D 的动作是按墙钟推进的（[Live2dCharacter.update] 里那个 dt 还另外夹了上限），
     * 少画几帧不会让动作变形，只会让它少几帧的平滑度。
     *
     * @return 下一帧的期限（纳秒，单调时钟）
     */
    private fun pace(nextFrameAt: Long): Long {
        val now = System.nanoTime()
        val deadline = nextFrameAt + FRAME_INTERVAL_NS
        if (deadline < now - FRAME_INTERVAL_NS) return now + FRAME_INTERVAL_NS
        val wait = deadline - now
        if (wait > 0) LockSupport.parkNanos(wait)
        return deadline
    }

    private companion object {
        const val TAG = "GlRenderThread"
        const val JOIN_TIMEOUT_MS = 1_000L

        /**
         * 一帧最短画多少（60fps）。
         *
         * 原来这里**没有任何节拍**，只靠 `eglSwapBuffers` 的缓冲队列背压限速 ——
         * 而 TextureView 那条队列有 2~3 块缓冲，结果在 120Hz 的机器上**满速跑一百多帧每秒**：
         * 一套 Live2D 的顶点变形 + 全图元绘制白做两倍，GPU 占用翻倍、机身发热，
         * 热到降频就是"通话打久了越来越卡"。用户报的"卡顿"多半是这条，不是单帧太慢。
         *
         * 为什么定 60 而不是跟着屏幕刷新率走：Live2D 的动作本来就是按 60fps 设计的
         * （轨道值由墙钟推进，见 [Live2dCharacter.update]），120Hz 上多画的那些帧
         * 只是把同一份姿态差插了一遍；60fps 在 120Hz 上是整齐的 1:2 合成节奏，看不出抖。
         * 省下来的那一倍 GPU/电量才是这通电话真正需要的（通话还要同时跑识别与合成）。
         */
        const val FRAME_INTERVAL_NS = 1_000_000_000L / 60

        /** 停画时一次睡 200ms：`setPaused(false)` 与 `quit()` 都会 unpark 立刻叫醒，
         *  这个时长只是"万一许可丢了也别永远醒不过来"的保险 */
        const val PAUSE_PARK_NANOS = 200_000_000L
    }
}
