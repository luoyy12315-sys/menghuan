package com.menghuan.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一条轻反馈（v0.97.0，审查 U15）。
 *
 * @param id 自增序号，用来让 [AppToastHost] 的 `LaunchedEffect` 每次都是新 key ——
 *   连续两次同样的文案（"已删除"连按两下）也要重新计时，否则第二次会在第一次的剩余时间里被吞掉。
 */
data class AppToastMessage(
    val id: Long,
    val text: String,
    /** true = 危险色底（失败类）；false = 强调色底（成功/中性） */
    val danger: Boolean = false,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
)

/**
 * 全局统一轻反馈通道（v0.97.0，审查 U15）。
 *
 * ## 为什么要把 Toast 换掉
 * 全项目原来 **21 处 `Toast.makeText`、0 处 Snackbar**，同时 `MomentsScreen` 里又有一套自绘的
 * 反馈胶囊（`FeedToast`）—— 同一件事两套皮肤。系统 Toast 的具体毛病：
 * ① 不受 Compose 主题控制，深色模式下它在浅色纸上、浅色模式下是系统灰，跟整套 Figma 风色板对不上；
 * ② **装不下动作按钮**（"重试 / 撤销"这类就永远给不出来）；
 * ③ 部分 ROM 在后台会把它丢掉，用户以为操作没反应；
 * ④ 页面上已经有自绘胶囊时，两套并存＝同一屏两套视觉语言。
 *
 * ## 为什么是全局单例而不是每页 plumb 一个 state
 * 调用点都在 `scope.launch {}` 的异步回调里（选图失败、导入失败、落库失败…），
 * 要把 state 一路 plumb 到 `MainActivity` 需要改每个页面的签名；而 Toast 之所以方便正是
 * 因为它不需要 Context 之外的任何东西。这里保留那份便利，只把**渲染**收到一处。
 *
 * 宿主：[AppToastHost]，挂在 `MainActivity` 的根 `Box`（盖在 NavHost 与开屏之上）**只挂一次**。
 */
object AppToast {

    private val seq = java.util.concurrent.atomic.AtomicLong(0L)

    private val _current = MutableStateFlow<AppToastMessage?>(null)
    val current: StateFlow<AppToastMessage?> = _current.asStateFlow()

    /**
     * 弹一条。同一条消息**立刻替换**上一条（后到的赢），不做排队 ——
     * 排队会让"连续两次失败"里第一次的提示延后显示，反而误导。
     */
    fun show(
        text: String,
        danger: Boolean = false,
        actionLabel: String? = null,
        action: (() -> Unit)? = null,
    ) {
        _current.value = AppToastMessage(
            id = seq.incrementAndGet(),
            text = text,
            danger = danger,
            actionLabel = actionLabel,
            action = action,
        )
    }

    /** 成功 / 中性：强调色底 */
    fun ok(text: String) = show(text, danger = false)

    /** 失败：危险色底。**失败必须用这个**（项目铁律：失败不许静默、也不许看着像成功） */
    fun fail(text: String) = show(text, danger = true)

    fun dismiss() {
        _current.value = null
    }
}

/**
 * 轻反馈宿主。**全局只挂一处**（`MainActivity` 根 Box 的底部居中）。
 *
 * 带动作按钮时停留更久（3.6s vs 2.2s）—— 有按钮要留出够点的时间。
 */
@Composable
fun AppToastHost(modifier: Modifier = Modifier) {
    val message by AppToast.current.collectAsState()
    val msg = message

    if (msg != null) {
        LaunchedEffect(msg.id) {
            delay(if (msg.actionLabel != null) 3600L else 2200L)
            AppToast.dismiss()
        }
    }

    AnimatedVisibility(
        visible = msg != null,
        modifier = modifier,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(200)),
    ) {
        val shown = msg ?: return@AnimatedVisibility
        val bg = if (shown.danger) NeuDanger else NeuAccent
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .background(bg, RoundedCornerShape(999.dp))
                    .padding(horizontal = 16.dp, vertical = 9.dp)
                    // 读屏播报（liveRegion = Polite）：以前 Toast 由系统代为播报，
                    // 换成自绘之后必须显式声明，否则视障用户完全收不到这些反馈。
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    shown.text,
                    fontSize = 13.sp,
                    color = AccentInk,
                )
                val label = shown.actionLabel
                val act = shown.action
                if (label != null && act != null) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label,
                        fontSize = 13.sp,
                        color = AccentInk,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                act()
                                AppToast.dismiss()
                            }
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
