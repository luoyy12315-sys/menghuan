package com.menghuan.app.realtime.visual

import android.content.Context
import android.view.View
import com.menghuan.app.live2d.InteractionRegion
import com.menghuan.app.live2d.Live2dAction
import com.menghuan.app.live2d.ModelActionCatalog
import com.menghuan.app.live2d.ModelCapability
import com.menghuan.app.data.CallVisualAsset
import kotlinx.coroutines.flow.StateFlow

/**
 * 「角色 Live2D 空间」需要、而视频通话**不需要**的那一组能力（v0.64.0）。
 *
 * 为什么另立一个接口而不是把方法都堆进 [CallVisualEngine]：
 * 用户第 1 / 第 2 条要的是"通话与动作系统解耦"。通话只需要"切四个状态"这一件事，
 * 它**不该**看得见"拖动角色""命中区域""播任意动作"这些空间才有的东西 ——
 * 一个接口里塞两种消费者的需求，最后一定会长出"通话页也能拖角色"这种不该有的耦合。
 *
 * [Live2dVisualEngine] 两个接口都实现：同一套渲染与模型，两种用法。
 * 这一层同样守住老规矩：**页面不出现任何 Cubism / GL 的名字**。
 */
interface Live2dStage {

    val runtime: StateFlow<VisualRuntime>

    /**
     * 加载模型。**不抛异常、没有返回值**：加载结果一律经 [runtime] 表达
     * （`Ready` = 能看，`Empty` / `Degraded` = 看不了，页面自己决定怎么回落）。
     *
     * 与 [CallVisualEngine.load] 是同一个方法（返回值也保持一致），
     * 所以 [Live2dVisualEngine] 能同时满足两个接口 —— 同一套渲染、两种用法。
     */
    suspend fun load(personaId: Long, asset: CallVisualAsset)

    fun onCreateView(context: Context): View

    /**
     * 底部要留出的 UI 高度（画面高度的比例，0 = 不留）。
     *
     * 换了布局（角色区大小变化）时调，实现里只影响投影/矩阵计算 ——
     * **不重建渲染目标**，所以可以随手调，哪怕动画期间每帧调一次。
     */
    fun setViewportBottomInset(fraction: Float)

    /** 播放任意一条动作（角色空间的动作条 / 点击互动都走它） */
    fun playAction(action: Live2dAction)

    /** 停掉动作，回到模型自己的待机（离开页面时调） */
    fun stopAction()

    /**
     * 拖动角色：位移用**画面比例**（-1..1，1 = 一整屏宽/高）。
     * 用比例而不是像素，是为了换机型、分屏、旋转之后位置仍然一致。
     */
    fun setOffset(x: Float, y: Float)

    /** 点在某处算哪个区域。传 NDC 坐标（-1..1，y 向上） */
    fun hit(ndcX: Float, ndcY: Float): InteractionRegion

    /** 这张模型的动作能力（"支持 12 项动作能力"之类的展示用） */
    fun capability(): ModelCapability

    /** 模型加载完成后的自带素材清单（动作组 + 表情），由页面转手给仓库同步进动作库 */
    fun onCatalogReady(callback: (ModelActionCatalog) -> Unit)

    fun pause()

    fun resume()

    fun release()
}
