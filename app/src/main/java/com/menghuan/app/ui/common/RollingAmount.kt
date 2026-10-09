package com.menghuan.app.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 金额数字滚动（v0.55.0）：金额变化时每一位数字像**里程表**一样滚到新值，
 * 而不是整串"啪"地换掉。
 *
 * ## 为什么按位滚
 * 金额是这个页面最重要的一个数。整串替换时，用户的视线得重新读一遍；
 * 按位滚动则能**看见变化发生的过程** —— 哪一档进位、涨了多少，眼睛是跟得上的。
 *
 * ## 实现要点
 * - 每一位独立 `AnimatedContent`：`initialState` → `targetState` 之间按数字大小决定滚动方向
 *   （**数字变大 → 新字从下方滚上来**，里程表正转；变小则反向），方向一致才不会"乱跳"；
 * - 每位外面套一个**固定高度 + `clipToBounds`** 的 Box：滚进滚出的那半截被裁掉，
 *   才有"从数字窗口里翻过去"的味道，而不是整串上下平移；
 * - **首次进入也滚**：`AnimatedContent` 只在 targetState 变化时播 transition，
 *   所以先摆一串**等长的 0**（[zeroMask]，符号与小数点原位保留），下一帧再滚到真值 ——
 *   进页面那一刻金额"算出来"的过程被做出来了；
 * - 从左到右**错峰**落定（`stepMs`）：像一个个珠子到位，比整排同时停更有质感。
 *
 * ## v0.56.5 修：切页进来不跳的问题（[restartKey]）
 * 症状 = 从别的 tab 切到数据页，金额一动不动；只有从记账本 push 页返回时才跳。
 * 根因 = 四个顶级 tab 在 Pager 里**同时被组合**，`LaunchedEffect` 一上来就把"从 0 滚"这一遍
 * 在后台播完了；等用户真切过来，早就停在终值上。
 * 修法与 [staggerEnter] / 本周回顾同一条思路：**由外部喂"我现在可见了"**——
 * 调用方把 `restartKey` 传成"本页是否可见"，它的值一变，`remember` 重新回到 0 起点、效果重跑，
 * 于是切 tab 进来和从 push 页返回**两条路径都会重播**。
 * 默认 `Unit` = 不重播（push 页 / 弹层里的金额保持原样，进页滚一次就够）。
 *
 * ## 用法边界
 * 只给**汇总类金额**用（现金余额 / 收入 / 支出 / 结余）。流水列表里每一行的金额**不用** ——
 * 列表复用会让它们在滚动时反复触发，满屏数字乱跳反而更吵。
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun RollingAmountText(
    /** 已格式化的金额串（含正负号与小数点，如 `-1234.56`）；非数字字符原样静态显示 */
    text: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = 0.sp,
    /** 单字滚动时长 */
    durationMs: Int = 620,
    /** 相邻两位的错峰间隔；总错峰封顶 8 档，免得长金额最后几位等太久 */
    stepMs: Int = 26,
    /** 首次显示是否也从 0 滚上来 */
    rollOnFirstShow: Boolean = true,
    /**
     * 数字窗口高度 = 字号 × 该系数。1.35 是常规字形（含上下伸展部）的稳妥值；
     * 系数太小会把字的上下缘裁掉，太大则滚动时会露出相邻位置的残影。
     */
    windowFactor: Float = 1.35f,
    /**
     * 重播信号：值一变就**重新从 0 滚一遍**（重挂载，见下面的 `key`）。
     *
     * 数据页传的是"本页可见后延后一拍翻的计数"（`DataScreen.ledgerRollKey`）——
     * 直接拿 visible 当 key 会在切页那一帧就开滚，和 Pager 切换、错峰入场抢同一帧，
     * 观感就是"卡着在那跳"（v0.56.7 的坑）。
     * 默认 `Unit` = 不重播（记账本页 / 弹层里的金额进页滚一次就够）。
     */
    restartKey: Any? = Unit,
) {
    // ⚠️ v0.56.7：重播**改成"重新挂载"**（`key(restartKey)`），不再用"改自己的状态"那套。
    //
    // 为什么：记账本页那条金额（restartKey 从不变化）一直是好的 —— 说明"首帧播种一串 0、
    // 下一帧滚到真值"这条路径本身没有问题；数据页那条只是在自身状态上打转，重组时机一变
    // （Pager 里四个页面同时组合、又被 `staggerEnter` 包着）就可能整段被跳过。
    // 用 `key` 把内容整块重新挂载后，**两条调用点走的是同一段代码、同一套时序**，
    // 于是"切 tab 进来"与"从记账本返回"必然表现一致。
    key(restartKey) {
        RollingAmountBody(
            text = text,
            fontSize = fontSize,
            color = color,
            // v0.79.0：整串金额**合成一个无障碍节点**报出去。
            // 按位滚动把每一位拆成了独立的 Text，读屏会一位一位念（"六、三、六、点、二、零"），
            // 语义树里也找不到"636.20"这个整体（`UiRenderTest.render19LedgerPage` 因此红了好几个版本）。
            // clearAndSetSemantics 把子节点的语义收掉，只留完整的一串。
            modifier = modifier.clearAndSetSemantics { contentDescription = text },
            fontWeight = fontWeight,
            letterSpacing = letterSpacing,
            durationMs = durationMs,
            stepMs = stepMs,
            rollOnFirstShow = rollOnFirstShow,
            windowFactor = windowFactor,
        )
    }
}

/** 真正画数字的那一段（每次挂载都从 0 起步，见 [RollingAmountText] 的说明） */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun RollingAmountBody(
    text: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier,
    fontWeight: FontWeight?,
    letterSpacing: TextUnit,
    durationMs: Int,
    stepMs: Int,
    rollOnFirstShow: Boolean,
    windowFactor: Float,
) {
    // 起点 = 一串等长的 0（[zeroMask]，符号与小数点原位保留）
    var seeded by remember { mutableStateOf(!rollOnFirstShow) }
    // 等一帧：让"全是 0"那一帧先落定，下一帧滚到真值才看得到过程
    LaunchedEffect(Unit) {
        if (!rollOnFirstShow) return@LaunchedEffect
        withFrameNanos { }
        seeded = true
    }
    val shown = if (seeded) text else zeroMask(text)

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        shown.forEachIndexed { index, ch ->
            if (ch.isDigit()) {
                RollingDigit(
                    digit = ch,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    color = color,
                    letterSpacing = letterSpacing,
                    durationMs = durationMs,
                    delayMs = (index * stepMs).coerceAtMost(stepMs * 8),
                    windowFactor = windowFactor,
                )
            } else {
                // 符号 / 小数点不滚：它们不是"数"，跟着动会显得整串在抖
                Text(
                    ch.toString(),
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    color = color,
                    letterSpacing = letterSpacing,
                )
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun RollingDigit(
    digit: Char,
    fontSize: TextUnit,
    fontWeight: FontWeight?,
    color: Color,
    letterSpacing: TextUnit,
    durationMs: Int,
    delayMs: Int,
    windowFactor: Float,
) {
    val density = LocalDensity.current
    val window: Dp = with(density) { fontSize.toDp() * windowFactor }
    Box(
        Modifier
            .height(window)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = digit,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                val slide = tween<IntOffset>(durationMs, delayMillis = delayMs, easing = EaseOutQuart)
                val fade = tween<Float>(durationMs, delayMillis = delayMs)
                (slideInVertically(slide) { full -> dir * full } + fadeIn(fade))
                    .togetherWith(slideOutVertically(slide) { full -> -dir * full } + fadeOut(fade))
            },
            label = "rollingDigit",
        ) { d ->
            Text(
                d.toString(),
                fontSize = fontSize,
                fontWeight = fontWeight,
                color = color,
                letterSpacing = letterSpacing,
            )
        }
    }
}

/** 把金额串里的数字全换成 0（符号与小数点原位保留），作为滚动的起点 —— 长度与真值一致，不会跳位 */
private fun zeroMask(text: String): String = buildString {
    text.forEach { ch -> append(if (ch.isDigit()) '0' else ch) }
}
