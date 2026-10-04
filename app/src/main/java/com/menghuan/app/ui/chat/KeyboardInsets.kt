package com.menghuan.app.ui.chat

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 输入栏底部避让（v0.44.0 重做）—— 换掉旧版三套补丁，改用官方 + 主流 IM 的通用做法。
 *
 * ## 为什么要重做
 * 旧实现（v0.42.4 / v0.43.0 / v0.43.1）在 `max(ime, navigationBars)` 之上连打三块补丁：
 *   ① **单调收敛**（`insetFloor` + `INSET_SETTLE_MS`）—— 修"键盘收起时中间帧往下掉再弹回"
 *   ② **切换静默期**（`OUTPUT_SWITCH_QUIET_MS`）—— 修"点表情后中间空一大片"
 *   ③ **方向性动画**（升高 0ms / 回落 90ms）—— 修"输入栏比键盘慢半拍"
 * 三块补丁互相咬合（改一个会误伤另一个，v0.43.1 连着返工两轮就是这么来的），
 * 根因却只有一个：**`navigationBars` 的高度被当成了逐帧实时量**。
 *
 * 键盘可见时 `navigationBars` 会被 IME 吸收变成 0，键盘收起时它再恢复成 48dp。
 * 于是收起过程中必然出现一帧 `ime=0 && navigationBars=0` → 输入栏掉到底 → 下一帧又升回来。
 * 取 max 只能保证**终值**正确，保证不了中间帧。
 *
 * ## 新做法（业界通用解）
 * 参考 Google 官方《控制软件键盘并为其添加动画效果》与 IM 类开源实现
 * （`DSAppTeam/PanelSwitchHelper`，2.4k star：核心思想就是"面板与键盘互斥 +
 * 底部偏移取一个稳定基线"），落到两点：
 *
 * 1. **`navigationBars` 用基线，不用实时值**：只在键盘完全不可见（`ime == 0`）时刷新基线。
 *    键盘弹出/收起的全过程里基线恒定 → 那帧"两个量同时为 0"的洞被基线填平，
 *    ① 号补丁从根上不再需要。
 * 2. **形态互斥由调用方一句话表达**：`followKeyboard = false`（面板/语音态）时
 *    直接返回基线，不读 ime。旧版要靠"静默期"猜"这轮是不是不要键盘了"，
 *    现在调用方明确表态，② 号补丁也不再需要。
 *
 * 键盘高度本身**不做二次插值**（沿用 v0.43.1 的结论）：直接用系统逐帧上报的真实高度，
 * 输入栏与键盘严丝合缝一起动，③ 号补丁同样不需要。
 *
 * ## 关于 `WindowInsetsAnimationCompat`
 * 官方推荐的逐帧源（`onProgress` 给出插值后的 IME 高度）。这里作为 **API 30+ 的增强**：
 * 收到过回调就用它的插值值（比系统 dispatch 更平滑）；收不到（低版本 / 无动画）就回落到
 * Compose 的 `WindowInsets.ime`。
 * ⚠️ 合成规则里 `imeComposePx == 0` 优先判 0 —— 这是**自愈保护**：万一某次收键盘
 * 没触发 `onEnd`，回调值会停在旧的高值上，而 Compose 值已经归零，此时以归零为准，
 * 不会把输入栏永久顶在上面。
 *
 * @param followKeyboard 当前形态是否需要跟随键盘（打字态 = true；面板/语音态 = false）
 * @return 输入栏应让出的底部高度
 */
@Composable
fun rememberBottomBarInset(followKeyboard: Boolean): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    val holder = remember(view) { BottomInsetHolder() }

    // 官方逐帧源：WindowInsetsAnimationCompat（API 30+ 生效，低版本不回调 → 自动回落）
    DisposableEffect(view) {
        val callback = object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: MutableList<WindowInsetsAnimationCompat>,
            ): WindowInsetsCompat {
                val hasIme = runningAnimations.any {
                    it.typeMask and WindowInsetsCompat.Type.ime() != 0
                }
                if (hasIme) {
                    holder.animatedImePx =
                        insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                }
                // 原样返回：不消费 insets，Compose 自己的处理链路不受影响
                return insets
            }

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                if (animation.typeMask and WindowInsetsCompat.Type.ime() != 0) {
                    holder.animatedImePx = ViewCompat.getRootWindowInsets(view)
                        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                }
            }
        }
        ViewCompat.setWindowInsetsAnimationCallback(view, callback)
        onDispose { ViewCompat.setWindowInsetsAnimationCallback(view, null) }
    }

    // ⚠️ 这两个读取必须留在本函数内：它们是逐帧变化的量，
    // 放到上层 composable 读会让整个聊天页在键盘动画期间每帧重组（v0.43.1 踩过）。
    val imeComposePx = WindowInsets.ime.getBottom(density)
    val navComposePx = WindowInsets.navigationBars.getBottom(density)

    // 导航条基线：只在键盘不可见时刷新（键盘可见期间它会被 IME 吸收成 0，不能采信）
    SideEffect { if (imeComposePx == 0) holder.navBarBaselinePx = navComposePx }

    val animated = holder.animatedImePx
    val imePx = when {
        imeComposePx == 0 -> 0
        animated >= 0 -> animated
        else -> imeComposePx
    }
    val usedPx = if (followKeyboard) maxOf(imePx, holder.navBarBaselinePx)
    else holder.navBarBaselinePx

    return with(density) { usedPx.toDp() }
}

/**
 * 不参与渲染的判断量之外的两个**参与渲染**的量。
 * 渲染量必须是 state（否则键盘动时不重组）；判断量一律不用 state（避免每帧多打一轮重组）。
 */
private class BottomInsetHolder {
    /** 官方动画回调上报的键盘高度；-1 = 从未回调过（低版本/无动画） */
    var animatedImePx by mutableIntStateOf(-1)
    /** 导航条基线高度（只在键盘不可见时刷新） */
    var navBarBaselinePx by mutableIntStateOf(0)
}

/**
 * 键盘弹起 → 自动收面板/语音态（v0.44.1，微信行为）。
 *
 * ## 为什么需要它（真机 bug：点表情 → 点输入框 → 键盘把输入栏盖住）
 * `NeuChatInput` 的面板退出只挂在 `onFocus` 上，但「点表情收键盘」**不会清掉输入框焦点** ——
 * 面板开着时输入框仍处于聚焦态。此时再点输入框：
 *   ① `onFocus` 不触发（焦点没变）→ `switchOutputMode` 没跑 → `panel` 还挂在表情态；
 *   ② 系统对"已聚焦的输入框被点击"会**自动弹键盘**。
 * 于是键盘弹起来了，而底部避让仍按"面板态 = 不要键盘"处理 → 输入栏停在导航条高度被盖住
 * （2026-10-04 真机截图实锤）。
 *
 * ## 修法（`DSAppTeam/PanelSwitchHelper` 的核心思想：互斥由**键盘状态**驱动）
 * 不再依赖"哪个入口点了一下"，而是盯着键盘本身：
 *   - 键盘收干净（`ime == 0`）→ **布防**；
 *   - 布防状态下键盘再次出现（`ime > 0`）且有面板/语音态开着 → 一律收掉。
 * 不需要时间窗：hide() 的收起动画里 ime 单调下降到 0 才布防，收起过程中的残留高度
 * 不可能触发误收；而"弹键盘"必然经过 ime==0 → 布防 → 再出现 的序列，必然命中。
 *
 * @param panelOpen 当前是否开着面板/语音态（调用方传 `panel != 0 || voiceMode`）
 * @param onKeyboardShown 键盘真正弹起时执行（调用方在这里收面板/退语音态，**不要 hide 键盘**）
 */
@Composable
fun ImeDismissesPanels(panelOpen: Boolean, onKeyboardShown: () -> Unit) {
    val density = LocalDensity.current
    val imePx = WindowInsets.ime.getBottom(density)
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(imePx) {
        when {
            imePx == 0 -> armed = true
            armed && panelOpen -> onKeyboardShown()
        }
    }
}
