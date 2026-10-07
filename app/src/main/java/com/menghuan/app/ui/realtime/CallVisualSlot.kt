package com.menghuan.app.ui.realtime

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.menghuan.app.realtime.visual.CallVisualEngine

/**
 * 视频通话的视觉位（v0.58.0；**v0.60 改铺满**）。
 *
 * 页面**只拿到一个 View**：View 里面是 TextureView（伪视频）还是 GLSurfaceView（Live2D）
 * 由引擎决定，播放器与 GL 上下文的生命周期也都在引擎里。
 * 这一层存在的意义就是把方案文档 §二十六那条要求落地——
 * "UI 不直接操作底层视觉 SDK"，通话页里不该出现 ExoPlayer 或 Cubism 的任何名字。
 *
 * 调用方一律传 `fillMaxSize()`：v0.60 起视频通话的主视觉就是**铺满整屏的背景层**
 * （用户要求 9:16 竖屏全屏铺满）。这里**不再按素材比例排一块卡片** ——
 * 那正是"竖屏素材把字幕与静音/挂断挤出屏幕"的根因；也不在 Compose 层做缩放，
 * "铺满且不变形"（cover）由引擎在 View 内部完成：
 * · 伪视频：`VideoVisualEngine` 给 TextureView 设 cover 矩阵（放大到刚好盖住、居中裁边）；
 * · Live2D：`fitProjection` 自己按画布比例居中摆角色（竖屏画布透明，角色完整可见，不拉伸）。
 *
 * ⚠️ **必须给这块一个尺寸**：AndroidView 里的 GLSurfaceView / TextureView 都没有固有尺寸，
 * 只给 `fillMaxWidth` 之类会测量成 0 高，画面"加载成功了但看不见"。
 */
@Composable
fun CallVisualSlot(
    engine: CallVisualEngine,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            // 引擎没给视图（NoneVisualEngine）时占一个空 View；
            // 外层已经按 runtime 决定要不要挂这一块，正常走不到这里
            engine.onCreateView(context) ?: android.view.View(context)
        },
        modifier = modifier,
    )
}