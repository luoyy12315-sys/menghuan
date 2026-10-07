package com.menghuan.app.realtime.visual

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.View
import com.live2d.sdk.cubism.core.ICubismLogger
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.CubismFrameworkConfig
import com.live2d.sdk.cubism.framework.ICubismLoadFileFunction
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.rendering.android.CubismOffscreenManagerAndroid
import com.live2d.sdk.cubism.framework.rendering.android.CubismShaderAndroid
import com.menghuan.app.data.CallVisualAsset
import com.menghuan.app.data.CallVisualStore
import com.menghuan.app.realtime.visual.live2d.GlTextureView
import com.menghuan.app.realtime.visual.live2d.Live2dCharacter
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live2D 视觉引擎（v0.58.0）。
 *
 * 路线是**官方 Cubism SDK for Java**（方案文档 §8.1：不要自己解析 `.moc3`）：
 * 视图 = [GlTextureView]（TextureView + 自建 EGL 上下文与渲染线程），
 * 模型 = [Live2dCharacter]，两者都由本类持有，页面只拿到一个 View。
 *
 * v0.61.0 之前视图是 `GLSurfaceView`，换掉的原因写在 [GlTextureView] 的类注释里
 * （一句话：SurfaceView 的"挖洞 / 盖住 UI"二选一在 Compose 的 AndroidView 里没有好答案）。
 *
 * 与官方 Sample 的差别：Sample 用进程单例（`LAppMinimumDelegate.getInstance()`）串起
 * 框架、纹理管理、视图和模型；本工程一次只允许一路通话（方案文档 §五），
 * 所以这些协作者都挂在引擎实例上，挂断即随实例释放——**不再需要单例**，
 * 只有框架本体（`CubismFramework` / shader / 离屏管理）是进程级的，见 [Live2dBootstrap]。
 */
class Live2dVisualEngine(
    private val appContext: Context,
    private var asset: CallVisualAsset,
) : CallVisualEngine {

    private val _runtime = MutableStateFlow<VisualRuntime>(VisualRuntime.Loading)
    override val runtime: StateFlow<VisualRuntime> = _runtime.asStateFlow()

    private var character: Live2dCharacter? = null
    private var glView: GlTextureView? = null
    private val projection = CubismMatrix44.create()

    private var viewWidth = 0
    private var viewHeight = 0
    private var released = false

    /** 首帧的透明度假设只回读一次，见 [renderer] 里的 probeTransparencyOnce */
    private var probeDone = false

    /** 第一帧真的画出来了没有（只发一次 Ready(drawn=true)，避免每帧刷状态流） */
    @Volatile
    private var drawnPublished = false

    @Volatile
    private var callState = CallVisualState.IDLE

    override suspend fun load(personaId: Long, asset: CallVisualAsset) {
        this.asset = asset
        val modelFile = CallVisualStore.live2dModelFile(appContext, personaId, asset)
        if (modelFile == null) {
            // 资源被删 / 目录被清：视觉降级，通话照打（方案文档 §二十四）
            _runtime.value = VisualRuntime.Degraded("Live2D 模型文件不在了，本次通话只用头像")
            return
        }
        Live2dBootstrap.ensureStarted(appContext)
        character = Live2dCharacter(
            baseDir = CallVisualStore.live2dRoot(appContext, personaId),
            modelJsonRelPath = asset.live2dModelJson,
        )
        // ⚠️ **必须在这里就发布 Ready**，不能等 GL 的 onSurfaceCreated 再说：
        // 页面只在 Ready 时才把 onCreateView 出来的那块视图挂上去，
        // 而 Ready 又只能由那块视图的 GL 回调发出 —— 两个条件互相等，
        // 结果就是真机上"上传了 Live2D 但视频通话还是只有头像"（v0.58.0 真实踩到）。
        // 真正的加载（读 moc / 传纹理）仍在 onSurfaceCreated 里做，失败再从那里改判 Degraded。
        _runtime.value = VisualRuntime.Ready()
    }

    override fun onCreateView(context: Context): View {
        // v0.61.0：**从 GLSurfaceView 换成 TextureView 承载**（EGL 上下文与渲染线程在 GlTextureView 里）。
        // GLSurfaceView 那两条路都不通：排在窗口下面要在窗口上挖洞，而 Compose 的 AndroidView 里
        // 整棵 Compose 绘制树比子 View 先画，洞会把顶栏/字幕/挂断一起擦掉；
        // 改 setZOrderOnTop 排到窗口上面，角色又会盖住它覆盖范围内的 UI（真机"挡住挂断键"）。
        // TextureView 是普通绘制的内容视图：透明处透出页面、UI 永远画在它上面，两头都占。
        // 带 alpha 的 EGLConfig 现在由 GlTextureView 自己申请，不再需要 setEGLConfigChooser。
        val view = GlTextureView(context, renderer)
        glView = view
        return view
    }

    private val renderer = object : GLSurfaceView.Renderer {

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glEnable(GLES20.GL_BLEND)
            // 纹理按预乘 alpha 上传（同官方 PREMULTIPLIED_ALPHA_ENABLE=true）
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

            if (!CubismFramework.isInitialized()) CubismFramework.initialize()
            // EGL 上下文重建后旧 program 是失效的，官方就是在这里删掉实例重建
            runCatching { CubismShaderAndroid.getInstance().releaseInvalidShaderProgram() }
            CubismShaderAndroid.deleteInstance()

            // ⚠️ 首帧的模型加载**不在这里做**，见 onSurfaceChanged：
            // GLSurfaceView 保证 created 先于 changed，此刻 viewWidth/viewHeight 还是 0，
            // 而 `CubismRendererAndroid.create(w, h)` 是拿这两个数去建离屏渲染缓冲的。
            // 用 0x0 建出来的缓冲不会报错，只会把整帧画进一个零尺寸的目标里 ——
            // 于是"模型加载成功、runtime 是 Ready、画面层挂上去了"，屏幕上却一片空白。
            // 这里只处理上下文重建（尺寸早就有了）。
            val c = character ?: return
            if (c.ready && viewWidth > 0 && viewHeight > 0) {
                // reloadRenderer 内部没有 runCatching（框架的 createRenderer 会 error()），
                // 而这里是 GL 线程的回调 —— 抛出去等于把整条 GL 线程弄死，屏幕从此不再刷新一帧。
                // 视觉重建失败只降级成头像，通话照打（方案文档 §二十四）。
                runCatching { c.reloadRenderer(viewWidth, viewHeight) }
                    .onFailure {
                        Log.e(TAG, "live2d renderer reload failed", it)
                        _runtime.value = VisualRuntime.Degraded("画面重建失败，本次通话只用头像")
                    }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewWidth = width
            viewHeight = height
            GLES20.glViewport(0, 0, width, height)
            val c = character ?: return
            if (width <= 0 || height <= 0) return
            if (!c.ready) {
                // 拿到真实尺寸了才建渲染器（官方是在 onSurfaceChanged 里
                // setRenderTargetSize 把渲染目标改成窗口大小，这里是同一个口径）
                c.loadAssets(width, height)
                publish(c)
            } else {
                // 视图被重新量过（旋转、分屏、页面回来）：离屏缓冲必须跟着改尺寸，
                // 否则画面又被画回旧的那块缓冲里
                c.setRenderTargetSize(width, height)
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val c = character ?: return
            if (released || !c.ready) return
            runCatching {
                val offscreen = CubismOffscreenManagerAndroid.getInstance()
                offscreen.beginFrameProcess()

                // 画布比例适配（算法在 Live2dCharacter 里，同官方 onUpdate）
                c.fitProjection(projection, viewWidth, viewHeight)
                c.applyPendingState()
                c.update()
                c.draw(projection)

                offscreen.endFrameProcess()
                offscreen.releaseStaleRenderTextures()

                // 第一帧真的画完了才告诉页面"可以切视频布局"。
                // 在那之前页面保持居中大头像那一套（视图已经挂着，只是全透明），
                // 用户不会先看到几秒"空画面 + 顶栏"、角色再蹦出来。
                if (!drawnPublished) {
                    drawnPublished = true
                    _runtime.value = VisualRuntime.Ready(drawn = true)
                }
            }.onFailure {
                Log.e(TAG, "live2d frame failed", it)
                // 单帧异常不许把整通电话带走：停掉视觉，通话继续
                _runtime.value = VisualRuntime.Degraded("画面渲染出错，本次通话只用头像")
            }
            probeTransparencyOnce()
        }

        /**
         * 首帧回读**左上角那一个像素**。
         *
         * 那个位置必定在角色以外 = 应该是清屏色 `(0,0,0,0)`。
         * 一次读数同时回答两个平时长得一模一样的问题：
         * · a=0 → 帧缓冲真的带 alpha，透明这条路通了；
         * · a=255（尤其再来个 r=g=b=255）→ alpha 根本没地方存，整块表面是不透明的，
         *   于是"白屏挡住 UI"和"什么都没画"在屏幕上看着是一回事。
         * 只读一次，不进每帧（`glReadPixels` 会强制等 GPU）。
         */
        private fun probeTransparencyOnce() {
            if (probeDone) return
            probeDone = true
            runCatching {
                val shaderFail = Live2dBootstrap.shaderLoadFailure
                if (shaderFail != null) {
                    // 框架的 GLSL 是运行时读文件的：读不到就一个 shader 都编译不出来，
                    // 于是"模型加载成功、ready=true、零异常"，屏幕上却永远什么都不画。
                    // 这个失败必须**当场判死**并回落到头像 + 说清原因，
                    // 不许它继续表现成"白屏"让人从头猜（v0.58~v0.60.3 就是猜掉三版的）。
                    Log.e(TAG, "live2d framework resource missing: $shaderFail")
                    _runtime.value = VisualRuntime.Degraded("Live2D 着色器资源没打进包，本次通话只用头像")
                    return
                }
                val px = java.nio.ByteBuffer.allocateDirect(4)
                    .order(java.nio.ByteOrder.nativeOrder())
                GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px)
                px.rewind()
                val r = px.get().toInt() and 0xFF
                val g = px.get().toInt() and 0xFF
                val b = px.get().toInt() and 0xFF
                val a = px.get().toInt() and 0xFF
                Log.e(TAG, "live2d probe corner rgba=$r,$g,$b,$a")
            }
        }
    }

    private fun publish(c: Live2dCharacter) {
        val fail = c.failure
        _runtime.value = if (fail != null) VisualRuntime.Degraded(fail)
            else VisualRuntime.Ready()
        // 形象这条链路一旦不出画面，"到底是没加载、加载失败、还是加载成功但画不出来"
        // 三种情况在屏幕上长得一模一样（都是空白/头像），只能靠这一行分辨
        Log.e(TAG, "live2d publish size=${viewWidth}x$viewHeight ready=${c.ready} fail=$fail")
    }

    override fun setCallState(state: CallVisualState) {
        callState = state
        val c = character ?: return
        c.requestState(Live2dCharacter.groupFor(state))
        // 用户打断后口型要立刻闭上：状态一离开 SPEAKING 就把目标电平归零，
        // 平滑会在几十毫秒内收合（不会突然合嘴）
        if (state != CallVisualState.SPEAKING) c.setMouthLevel(0f)
    }

    override fun setAiMouthLevel(level: Float) {
        character?.setMouthLevel(level)
    }

    override fun pause() {
        glView?.pauseRendering()
    }

    override fun resume() {
        // 只把渲染节拍接回来 + 重新订阅当前状态，**绝不重建通话会话**（方案文档 §二十三）
        glView?.resumeRendering()
        character?.requestState(Live2dCharacter.groupFor(callState))
    }

    override fun release() {
        if (released) return
        released = true
        // 先把 GL 线程停干净（quit + join），再拆模型：
        // 否则可能有一帧正在用已经 delete 掉的 moc/纹理，那是"挂断时偶发闪退"的经典形状。
        // GLSurfaceView 时代不用管这件事，它自己保证回调串行。
        runCatching { glView?.shutdown() }
        // 与官方 delegate.onDestroy 同样的做法：主线程释放 moc/model/渲染器。
        // GPU 纹理随 EGL 上下文销毁一起回收（上下文已经随线程退出拆掉了）。
        runCatching { character?.destroy() }
        character = null
        glView = null
        _runtime.value = VisualRuntime.Empty
    }

    private companion object {
        const val TAG = "Live2dVisualEngine"
    }
}

/**
 * Cubism 框架的进程级启动（一次就够）。
 *
 * 官方在 `LAppMinimumDelegate` 的私有构造里做同一件事（cleanUp → startUp）。
 * 这里刻意**不调 `CubismFramework.dispose()`**：dispose 会把 IdManager 等进程级对象拆掉，
 * 而 GL 线程可能还有最后一帧在跑，跨线程拆进程单例正是"挂断时偶发闪退"的经典形状。
 * 真正占内存的是模型本体（moc + 纹理），那部分由 [Live2dCharacter.destroy] 释放。
 */
object Live2dBootstrap {

    @Volatile
    private var started = false

    @Volatile
    private var loader: AssetLoadFileFunction? = null

    /**
     * 第一个读不到的框架资源路径（目前只有 GLSL 着色器）。
     * null = 到目前为止都读到了。着色器读不到 = 一个 shader 都编译不出来 = 永远画不出东西，
     * 而框架只打一条日志就继续跑，所以必须在这里留这个记录。
     */
    val shaderLoadFailure: String? get() = loader?.firstFailure

    @Synchronized
    fun ensureStarted(context: Context) {
        if (started || CubismFramework.isStarted()) {
            started = true
            return
        }
        val fileLoader = AssetLoadFileFunction(context.assets)
        loader = fileLoader
        val option = CubismFramework.Option().apply {
            // 官方默认 OFF。这里开到 ERROR：帧循环里刷 VERBOSE 会实打实拖慢渲染，
            // 但模型加载失败必须留得下证据（真机排错最难的就是"只说画面没出来"）
            loggingLevel = CubismFrameworkConfig.LogLevel.ERROR
            logFunction = ICubismLogger { message -> Log.e("Live2D", message) }
            // ⚠️ **这一行是 v0.60.3 之前整条 Live2D 不显示的真凶之一**：
            // 框架靠它去读 GLSL 源码，不给 loader 就是"文件读不到"，
            // 而它只 `cubismLogError("File loader is not set.")` 后 return 0，不抛异常。
            loadFileFunction = fileLoader
        }
        CubismFramework.cleanUp()
        started = CubismFramework.startUp(option)
    }
}

/**
 * 从 APK assets 读框架资源（同官方 Sample 的 `LAppMinimumPal.LoadFileFunction` 走 AssetManager）。
 *
 * 路径由框架自己拼（`com/live2d/sdk/cubism/framework/shaders/standardES/xxx.frag`），
 * 和 assets 里的目录结构一一对应，所以直接 `assets.open(path)` 就行。
 * 读失败只记不抛 —— 抛到 GL 线程会把整条渲染线程弄死。
 */
private class AssetLoadFileFunction(private val assets: android.content.res.AssetManager) :
    ICubismLoadFileFunction {

    @Volatile
    var firstFailure: String? = null
        private set

    override fun load(filePath: String): ByteArray? = runCatching {
        assets.open(filePath).use { it.readBytes() }.takeIf { it.isNotEmpty() }
            ?: error("empty")
    }.getOrElse {
        if (firstFailure == null) firstFailure = filePath
        null
    }
}
