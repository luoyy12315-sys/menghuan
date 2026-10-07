package com.menghuan.app.realtime.visual

import android.content.Context
import android.view.View
import com.menghuan.app.data.CallVisualAsset
import com.menghuan.app.data.CallVisualType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 视觉层的运行状态。UI 只认这个，不认 Live2D 也不认播放器（方案文档 §四：
 * 视觉层是可插拔的表现层，不得成为实时通话的单点故障）。
 */
sealed interface VisualRuntime {
    /** 这个人设没有可用形象 —— 通话页保持原来的大头像主视觉 */
    data object Empty : VisualRuntime

    /** 资源加载中（模型首次上 GPU / 播放器准备首帧） */
    data object Loading : VisualRuntime

    /**
     * 可以显示。[aspect] = 宽/高，<=0 表示还不知道（UI 按容器铺满）。
     *
     * [drawn] = **画面真的画出来过一帧没有**。这两个状态必须分开：
     * 视图要尽早挂上（不挂就没有 surface、GL 线程不启动、模型永远加载不完 → 死等），
     * 但页面的布局不能跟着一起切 —— 否则用户先看到几秒"空画面 + 顶栏"，
     * 角色才蹦出来（v0.61.0 真机反馈"第二张图跟之前有什么区别"）。
     * 现在：Ready 挂视图并开始加载，drawn 才切到视频布局。
     */
    data class Ready(val aspect: Float = 0f, val drawn: Boolean = false) : VisualRuntime

    /**
     * 视觉坏了但**通话照打**（方案文档 §二十四的硬性要求）。
     * [reason] 只在通话页顶部提示条里显示，不打断对话。
     */
    data class Degraded(val reason: String) : VisualRuntime
}

/**
 * 通话视觉引擎的统一接口（方案文档 §二十六）。
 *
 * 三条边界要一直守住：
 * 1. **UI 不直接碰 Live2D / ExoPlayer**：视图由 [onCreateView] 交给页面，
 *    播放器与 GL 上下文的生命周期都在引擎里；
 * 2. **实时通话层不依赖具体实现**：通话引擎与 VM 只调 [setCallState] / [setAiMouthLevel]；
 * 3. **语音通话完全不碰这套接口**：只有视频通话才会构造视觉引擎（方案文档 §二.一）。
 */
interface CallVisualEngine {

    val runtime: StateFlow<VisualRuntime>

    /**
     * 加载资源。必须能在协程里挂起（模型解压/纹理上传是重活），
     * 且**任何失败都只把 [runtime] 变成 [VisualRuntime.Degraded]**，不许抛出去。
     */
    suspend fun load(personaId: Long, asset: CallVisualAsset)

    /**
     * 创建显示用的 View（由 Compose 的 `AndroidView` 装载）。
     * 返回 null = 这个引擎没有可视输出，页面继续用头像。
     */
    fun onCreateView(context: Context): View? = null

    /** 通话状态变化（[CallVisualState]，由引擎状态映射而来） */
    fun setCallState(state: CallVisualState)

    /**
     * AI 实时语音的音量电平（0f–1f）。
     * 伪视频不需要它（说话状态已经由 [setCallState] 表达），Live2D 用它驱动口型。
     */
    fun setAiMouthLevel(level: Float) = Unit

    /** 页面切后台 / 锁屏：暂停驱动，省电也防止 GL 上下文丢失后乱画 */
    fun pause() = Unit

    /** 回到前台：重新订阅当前状态（**不许**因此重建通话会话，方案文档 §二十三） */
    fun resume() = Unit

    /** 挂断 / 页面销毁：释放播放器、渲染器、模型引用与 GPU 占用。必须幂等 */
    fun release()
}

/** 没有形象时的实现：什么都不画，页面自然回落到头像主视觉 */
class NoneVisualEngine : CallVisualEngine {
    private val _runtime = MutableStateFlow<VisualRuntime>(VisualRuntime.Empty)
    override val runtime: StateFlow<VisualRuntime> = _runtime.asStateFlow()

    override suspend fun load(personaId: Long, asset: CallVisualAsset) = Unit
    override fun setCallState(state: CallVisualState) = Unit
    override fun release() = Unit
}

/**
 * 按人设的视觉配置挑选引擎（方案文档 §二.二：NONE / LIVE2D / VIDEO 三选一）。
 *
 * 一次视频通话只激活一种视觉类型；配置读不出来（metadata.json 丢失/损坏）
 * 就当 NONE —— 那是"没有形象"，不是错误，通话必须照打。
 */
object CallVisualEngines {

    fun create(context: Context, asset: CallVisualAsset): CallVisualEngine = when {
        !asset.isUsable -> NoneVisualEngine()
        asset.type == CallVisualType.LIVE2D -> Live2dVisualEngine(context.applicationContext, asset)
        asset.type == CallVisualType.VIDEO -> VideoVisualEngine(context.applicationContext, asset)
        else -> NoneVisualEngine()
    }
}
