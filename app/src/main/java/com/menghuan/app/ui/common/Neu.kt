package com.menghuan.app.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.menghuan.app.ui.theme.AccentBrush
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.HandleFill
import com.menghuan.app.ui.theme.HandleInk
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuBackground
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.PaperShadow
import com.menghuan.app.ui.theme.RaisedShadow
import com.menghuan.app.ui.theme.SelectionInk
import com.menghuan.app.ui.theme.SheetGrip
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary

// ===== 动效令牌（Figma 系统统一时序：状态走弹簧，层级走 expo） =====
val EaseOutExpo = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
val EaseOutQuart = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
val NeuPressSpec = tween<Float>(110)
val NeuStateSpec = tween<Float>(220, easing = EaseOutQuart)
val NeuSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMediumLow,
)
val NeuSnappySpring = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMedium,
)

// ===== 错峰入场（v0.55.0）=====

/**
 * 整页 / 整组内容的**错峰入场进度**：0 → 1，按 [index] 依次延后 [stepMs] 起步。
 *
 * 为什么需要它：设置页、会话设置页这类"一屏十几行"的页面，整块内容在同一帧出现，
 * 与 push 页面的 420ms 横向滑入叠在一起就是"内容啪地糊上来"。按行错峰抬起后，
 * 页面是"铺开"的，而不是"弹出"的。
 *
 * 只在**首次组合**跑一次（`LaunchedEffect(Unit)`）：后续因状态变化重组不会重播，
 * 否则每次开关一抖整页都要再飘一遍。
 *
 * 进度值走 [animateFloatAsState]，每帧只更新 `graphicsLayer`，**不触发条目重组**。
 */
@Composable
fun staggerProgress(index: Int, stepMs: Int = 24, durationMs: Int = 260): Float {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (index > 0) delay((index * stepMs).toLong())
        started = true
    }
    return animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMs, easing = EaseOutQuart),
        label = "stagger$index",
    ).value
}

/**
 * 把 [staggerProgress] 接到渲染层：淡入 + 上滑 [slideDp]。
 * 用 `graphicsLayer` 而不是 `Modifier.offset/alpha` —— 前者在渲染期生效，
 * 动画期间不产生任何重组。
 */
@Composable
fun Modifier.staggerEnter(index: Int, stepMs: Int = 24, slideDp: Dp = 10.dp): Modifier {
    val progress = staggerProgress(index, stepMs)
    val slide = with(LocalDensity.current) { slideDp.toPx() }
    return this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * slide
    }
}

/**
 * 按压态参数：按下整体微缩（0.96）+ 弹簧回弹，Figma 式"按下去会弹回来"。
 * 所有可点组件统一走这一套，保证手感一致。
 *
 * v0.42.1 起 `elevation` 字段只剩占位意义（全应用去阴影后没有阴影可收矮），
 * 保留是为了不动几十个调用点的 `raised = ...` 实参；按压反馈现在纯靠 scale。
 */
class NeuPressState internal constructor(
    val interactionSource: MutableInteractionSource,
    val scale: Float,
)

@Composable
fun rememberNeuPressState(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    @Suppress("UNUSED_PARAMETER") raised: Dp = 6.dp,
): NeuPressState {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, NeuPressSpec)
    return NeuPressState(interactionSource, scale)
}

/**
 * 表面（核心）：白纸 + 发丝线，**无任何投影**。
 * - inset=false：color 填充 + 1px 发丝线描边
 * - inset=true：凹陷灰面（NeuTrack 平涂），输入框 / 轨道 / 搜索胶囊专用
 * intensity：整体浓度（禁用态压淡用）
 *
 * elevation 参数**保留但已失效**（v0.42.1 用户要求"把所有阴影全部去掉"）：
 * 大量调用点还在传 elevation，直接改签名会牵动几十个文件；这里统一吞掉即可。
 * 别再让它重新画阴影——层级只能靠 hairline 描边与色差表达。
 */
@Composable
fun Modifier.neuSurface(
    cornerRadius: Dp,
    color: Color = NeuSurface,
    @Suppress("UNUSED_PARAMETER") elevation: Dp = 0.dp,
    intensity: Float = 1f,
    inset: Boolean = false,
    shape: Shape = RoundedCornerShape(cornerRadius),
    clipContent: Boolean = true,
): Modifier {
    // 令牌只能在 composable 里读，先取成局部值再交给 drawBehind（见 Theme.kt 说明）
    val track = NeuTrack
    val hairline = NeuHairline
    return this
        .drawBehindFg(cornerRadius, color, intensity, inset, track, hairline)
        .then(if (clipContent) Modifier.clip(shape) else Modifier)
}

private fun Modifier.drawBehindFg(
    cornerRadius: Dp,
    color: Color,
    intensity: Float,
    inset: Boolean,
    track: Color,
    hairline: Color,
): Modifier = this.then(
    Modifier.drawBehind {
        val r = cornerRadius.toPx()
        if (inset) {
            // 凹陷：平涂灰面（无边框，靠色差分区）
            drawRoundRect(track.copy(alpha = intensity.coerceIn(0f, 1f)), cornerRadius = CornerRadius(r))
        } else {
            drawRoundRect(color.copy(alpha = intensity.coerceIn(0f, 1f)), cornerRadius = CornerRadius(r))
            // 描边内缩半宽：默认居中描边的外半圈会伸出布局边界，气泡这类右对齐、
            // 宽度随文字变化的圆角矩形落在分数像素上时，外溢的半圈呈一条黑边
            // （用户反馈「发出的消息框左边有黑线」）。内缩后描边整体落在形状内部。
            val stroke = 1.dp.toPx()
            drawRoundRect(
                color = hairline,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius((r - stroke / 2f).coerceAtLeast(0f)),
                style = Stroke(width = stroke),
            )
        }
    },
)

/** 虚线选择框 + 四角手柄（Figma 编辑器选中态的元设计引用） */
@Composable
fun Modifier.figmaSelection(active: Boolean, cornerRadius: Dp = 14.dp): Modifier {
    if (!active) return this
    val ink = SelectionInk
    val fill = HandleFill
    val handleInk = HandleInk
    return this.then(
        Modifier.drawBehind {
            val r = cornerRadius.toPx()
            val dash = Stroke(
                width = 1.6.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f)),
            )
            val pad = 3.dp.toPx()
            drawRoundRect(
                color = ink,
                topLeft = Offset(-pad, -pad),
                size = Size(size.width + pad * 2, size.height + pad * 2),
                cornerRadius = CornerRadius(r + pad),
                style = dash,
            )
            // 四角手柄：底色块 + 描边小方块（选中手柄）
            val hs = 7.dp.toPx()
            val left = -pad - hs / 2
            val top = -pad - hs / 2
            val right = size.width + pad - hs / 2
            val bottom = size.height + pad - hs / 2
            listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom))
                .forEach { corner ->
                    drawRect(
                        color = fill,
                        topLeft = corner,
                        size = Size(hs, hs),
                    )
                    drawRect(
                        color = handleInk,
                        topLeft = corner,
                        size = Size(hs, hs),
                        style = Stroke(width = 1.4.dp.toPx()),
                    )
                }
        },
    )
}

/**
 * 卡片容器。content 为 **ColumnScope**：卡片内容纵向流式排列。
 * 注意不能用 BoxScope——多个子元素会被 Box 全部叠在同一位置，文字叠印成"乱码"（v0.2.2 修复）。
 */
@Composable
fun NeuCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    elevation: Dp = 3.dp,
    color: Color = NeuSurface,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .neuSurface(cornerRadius, color = color, elevation = elevation),
    ) {
        Column(Modifier.padding(contentPadding)) {
            content()
        }
    }
}

/**
 * 立体卡片面（v0.76.0，**只有动态页用**）。
 *
 * 背景 — 投影 — 圆角三件套，层级靠"抬起来"表达，而不是画一圈发丝线。
 * 之所以不塞进 [neuSurface]：那种纸感的其他页面还在用，把投影混进去
 * 等于把 v0.42.1 的"全应用去阴影"回退掉。
 *
 * ⚠️ `clip = false` + 自己 `background(shape)`：让投影落在形状**外面**，
 * 走外设它的默认 `clip = true` 会把投影裁成一条贴在边缘的硬边。
 */
@Composable
fun Modifier.raisedCard(
    cornerRadius: Dp = 20.dp,
    elevation: Dp = 14.dp,
    alpha: Float = 1f,
): Modifier {
    val shadow = RaisedShadow
    val surface = NeuSurface
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .shadow(elevation, shape, clip = false, ambientColor = shadow, spotColor = shadow)
        .background(surface.copy(alpha = alpha.coerceIn(0f, 1f)), shape)
        .clip(shape)
}

/** 圆形按压按钮（顶栏/输入栏用）：默认玻璃黑 5% 圆；accent = 纯黑实心圆 */
@Composable
fun NeuCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 42.dp,
    content: @Composable () -> Unit,
) {
    val press = rememberNeuPressState()
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, NeuStateSpec)
    Box(
        modifier
            .scale(press.scale)
            .size(size)
            .neuSurface(
                size / 2,
                color = if (accent) NeuAccent else NeuSurface,
                elevation = if (accent) 3.dp else 0.dp,
                intensity = alpha,
            )
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.scale(press.scale)) { content() }
    }
}

/** 主/次/危险 文字按钮：药丸（999 圆角），主按钮纯黑 */
@Composable
fun NeuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: NeuButtonKind = NeuButtonKind.Secondary,
    enabled: Boolean = true,
) {
    val press = rememberNeuPressState()
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, NeuStateSpec)
    val contentColor = when (kind) {
        NeuButtonKind.Primary -> AccentInk
        NeuButtonKind.Danger -> NeuDanger
        NeuButtonKind.Secondary -> TextPrimary
    }
    Box(
        modifier
            .scale(press.scale)
            .fillMaxWidth()
            .height(52.dp)
            .neuSurface(
                999.dp,
                color = if (kind == NeuButtonKind.Primary) NeuAccent else NeuSurface,
                elevation = if (kind == NeuButtonKind.Primary) 4.dp else 0.dp,
                intensity = alpha,
            )
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.14).sp,
            color = contentColor,
        )
    }
}

enum class NeuButtonKind { Primary, Secondary, Danger }

/** 输入框：浅灰凹陷面 + 上方 mono 风 label，聚焦光标纯黑 */
@Composable
fun NeuTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 5,
    /** 软键盘类型（v0.56.6）：纯数字字段传 [KeyboardType.Number]，默认文本键盘 */
    keyboardType: KeyboardType = KeyboardType.Text,
    /**
     * 只读字段（v0.60.1）：非 null 时整块凹陷面变成一次点击——**不弹键盘、不聚焦**。
     * 给"点一下就去看/去选东西"的字段用（如编辑人设里的角色立绘：点它选图，框里显示文件名），
     * 这样它的 label 与内边距和真输入框是同一套，不用另画一个长得像输入框的东西。
     */
    onClick: (() -> Unit)? = null,
) {
    Column(modifier) {
        if (label != null) {
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
                letterSpacing = 0.6.sp,
            )
            Spacer(Modifier.height(7.dp))
        }
        Box(Modifier.fillMaxWidth().neuSurface(12.dp, inset = true)) {
            if (onClick != null) {
                Text(
                    text = value.ifBlank { placeholder },
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    letterSpacing = (-0.14).sp,
                    color = if (value.isBlank()) MutedText else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onClick() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            } else {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        color = TextPrimary,
                        letterSpacing = (-0.14).sp,
                    ),
                    singleLine = singleLine,
                    maxLines = maxLines,
                    minLines = minLines,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    cursorBrush = SolidColor(NeuAccent),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) {
                                Text(placeholder, fontSize = 15.sp, color = MutedText, lineHeight = 21.sp)
                            }
                            inner()
                        }
                    },
                )
            }
        }
    }
}

/** 筛选胶囊：选中 = 纯黑药丸白字（弹簧色变），未选 = 白纸药丸 */
@Composable
fun NeuChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val press = rememberNeuPressState(raised = 4.dp)
    Box(
        modifier
            .scale(press.scale)
            .neuSurface(
                999.dp,
                color = if (selected) NeuAccent else NeuSurface,
                elevation = if (selected) 3.dp else 0.dp,
            )
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            letterSpacing = (-0.1).sp,
            color = if (selected) AccentInk else TextPrimary,
        )
    }
}

/**
 * 圆形勾选框：未选 = 白底发丝线圈；已选 = 纯黑填充 + 对勾弹簧弹出。
 *
 * ## 无障碍（v0.97.0，审查 U16）
 * 旧实现只有 `Modifier.clickable` + 一个 `Text("✓")`：TalkBack 读到的是一堆无角色的文字，
 * 视障用户根本不知道这是"能勾的"，更听不出当前是勾了还是没勾 —— 任务打卡、Agent 白名单
 * 这两个功能对他们等于不存在。现在改用 `Modifier.toggleable(role = Role.Checkbox)`，
 * 并补 `stateDescription`（读屏会念"未完成/已完成"）。
 *
 * @param label 读屏用的名字（如任务标题、应用名）。null = 不覆盖，由外层节点负责朗读。
 * @param checkedDescription / [uncheckedDescription] 状态朗读文案，默认中性词
 */
@Composable
fun NeuCheckCircle(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    boxSize: Dp = 24.dp,
    label: String? = null,
    checkedDescription: String = "已选中",
    uncheckedDescription: String = "未选中",
) {
    val press = rememberNeuPressState(raised = 4.dp)
    val checkScale by animateFloatAsState(if (checked) 1f else 0f, NeuSpring)
    val accentBrush = AccentBrush
    val hairline = NeuHairline
    val checkInk = AccentInk
    Box(
        modifier
            .scale(press.scale)
            .size(boxSize)
            .then(
                if (checked) {
                    Modifier.drawBehind {
                        drawCircle(accentBrush)
                    }.clip(CircleShape)
                } else {
                    Modifier.drawBehind {
                        drawCircle(
                            color = hairline,
                            style = Stroke(width = 1.4.dp.toPx()),
                        )
                    }.clip(CircleShape)
                },
            )
            .toggleable(
                value = checked,
                interactionSource = press.interactionSource,
                indication = null,
                role = Role.Checkbox,
            ) { onToggle() }
            .semantics {
                if (label != null) contentDescription = label
                stateDescription = if (checked) checkedDescription else uncheckedDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "✓",
            fontSize = 13.sp,
            color = checkInk,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.scale(checkScale),
        )
    }
}

/**
 * 开关：灰轨 + 底色钮，选中强调轨（位移走 expo）。
 *
 * ## 无障碍（v0.97.0，审查 U16）
 * 旧实现是 `Modifier.clickable`：读屏既读不出"这是一个开关"，也读不出开/关状态。
 * 设置链上几十个开关（联网、朗读、摘要、主动关心、MCP 启停…）对视障用户全部不可用。
 * 现在用 `Modifier.toggleable(role = Role.Switch)` + `stateDescription`。
 *
 * @param label 读屏用的名字；null = 由外层行负责朗读（当前多数调用点是这种）
 */
@Composable
fun NeuSwitch(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // v0.55.0：滑块位移从 `animateDpAsState` + `offset` 改成 **animateFloat + graphicsLayer**。
    // `animateDpAsState` 每帧产生新的 Dp，`offset` 又是个布局修饰符 —— 于是拨一次开关
    // 要重新布局 220ms（约 13 帧）。开关遍布设置链，拨起来就是在白烧布局帧。
    // `graphicsLayer` 的 translationX 走渲染层，同一个 220ms 里**一次布局都不做**。
    // 终点位移仍是 17dp（46dp 轨 − 3dp 左内边距 − 21dp 钮 − 5dp 右内边距 与原实现一致）。
    val knobShiftDp by animateFloatAsState(
        targetValue = if (checked) 17f else 0f,
        animationSpec = tween(220, easing = EaseOutQuart),
        label = "knobShift",
    )
    val knobShiftPx = with(LocalDensity.current) { knobShiftDp.dp.toPx() }
    val trackColor by animateColorAsStateFast(checked)
    val knobFill = NeuSurface
    val knobHairline = NeuHairline
    Box(
        modifier
            .width(46.dp)
            .height(27.dp)
            .background(trackColor, CircleShape)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
            ) { onToggle(!checked) }
            .semantics {
                if (label != null) contentDescription = label
                stateDescription = if (checked) "已开启" else "已关闭"
            },
    ) {
        Box(
            Modifier
                .offset(x = 3.dp, y = 3.dp)
                .graphicsLayer { translationX = knobShiftPx }
                .size(21.dp)
                .background(knobFill, CircleShape)
                .drawBehind {
                    drawCircle(
                        color = knobHairline,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                },
        )
    }
}

@Composable
private fun animateColorAsStateFast(checked: Boolean) = androidx.compose.animation.animateColorAsState(
    targetValue = if (checked) NeuAccent else NeuTrack,
    animationSpec = tween(220, easing = EaseOutQuart),
    label = "switchTrack",
)

/**
 * 面板选项行：图标灰圆 + 标题/副题 + 右箭头或选中钩；选中态 = 虚线选择框手柄。
 *
 * 图标位由 [iconSlot] 决定：null = 画 [icon]（绝大多数行卡），
 * 给了 = 画调用方自己的东西（「我的」页那张档案卡用它在图标槽里放圆头像）。
 * ⚠️ 两条路共用**同一个行框与同一个 38dp 图标槽**，所以这几种卡高矮胖瘦必然一致。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NeuSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 副标题下的**第二行小字**（v0.91.0，可选）。给的是纯文本 —— 由本组件统一管三件事：
     * ① 固定行宽 + 尾部省略；② 11sp；③ `MutedText`。
     *
     * 为什么单位是"行"而不是行尾控件：模型卡原来把用量挂 [trailing]，而 trailing 只能吃
     * Row 的**剩余宽度**，标题列 `weight(1f)` 一长就把读数压成竖排单字（真机截图里
     * "deepseek-chat" 逐字竖着排、卡片撑高一倍）。
     *
     * ⚠️ 溢出只能用 `TextOverflow.Ellipsis`（尾部省略）：本项目 Compose BOM 2024.12.01
     * （ui-text 1.7.6）**没有** `MiddleEllipsis`（那是 1.8 才加的），写了直接编译不过。
     */
    extra: String? = null,
    selected: Boolean = false,
    /** v0.80.0：自定义图标位（圆头像）；为 null 时画 [icon] */
    iconSlot: (@Composable () -> Unit)? = null,
    /**
     * selected 时是否画那颗 ✓。API 配置页的副标题已经写了「默认 · 」，
     * 选中态还有虚线框，勾是多余的（v0.53.4 用户点名去掉）。
     */
    showCheck: Boolean = true,
    /**
     * v0.54.5：卡片右侧的自定义控件（开关 / 当前值选择器）。
     * 给了它就不再画默认那颗浅箭头 —— 右侧已经有明确的控件当"这个位置能操作"的提示，
     * 再挂一颗箭头会让人以为点箭头还有别的东西（用户点名"开关按钮放在卡片右边就行"）。
     */
    trailing: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onChevronClick: (() -> Unit)? = null,
) {
    val press = rememberNeuPressState(raised = 4.dp)
    Row(
        modifier
            .scale(press.scale)
            .fillMaxWidth()
            .figmaSelection(selected, 14.dp)
            .neuSurface(14.dp, color = NeuSurface, elevation = if (selected) 2.dp else 0.dp)
            .combinedClickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick ?: {},
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .neuSurface(999.dp, color = NeuTrack, inset = true),
            contentAlignment = Alignment.Center,
        ) {
            if (iconSlot != null) {
                iconSlot()
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) NeuAccent else TextPrimary,
                modifier = Modifier.size(19.dp),
                )
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.14).sp,
                color = TextPrimary,
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 12.sp, color = MutedText, maxLines = 1)
            }
            extra?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (selected && showCheck) {
            Text("✓", fontSize = 15.sp, color = NeuAccent, fontWeight = FontWeight.Bold)
        }
        // 给了 onChevronClick 就恒定画一颗**更大更深**的箭头：
        // 点箭头 = 看/改这条的详情，点卡片其余地方 = 主操作（v0.53.0 用户点名"沿用箭头形状，
        // 改大点颜色改深点明显一点"）。原来箭头是 14dp 的 FaintText，几乎看不见，也没法点。
        if (onChevronClick != null) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .size(40.dp)
                    // v0.91.0：箭头定点居中、**不参与行高**。原来它给行立了个 40dp 的下限，
                    // 三行字的模型卡（标题+副题+用量）本来已经超过 40dp 所以看不出来，
                    // 两行的卡还是会被它撑高一截地显出多余的空白。
                    .align(Alignment.CenterVertically)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onChevronClick,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    com.menghuan.app.ui.theme.MIcons.ChevronRight,
                    contentDescription = "详情",
                    tint = TextSecondary,
                    modifier = Modifier.size(21.dp),
                )
            }
        } else if (!selected && trailing == null) {
            Icon(
                com.menghuan.app.ui.theme.MIcons.ChevronRight,
                contentDescription = null,
                tint = FaintText,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/** 弹层通用容器：底色 + 顶部抓握手柄 + 统一标题 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeuSheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // skipPartiallyExpanded：弹层一次展开到位——内容较高的弹层（新建任务/编辑档案/API 配置）
    // 停在半展开态会让用户以为"展开不完全"（用户反馈）；内容自身可滚动兜底超长场景
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NeuBackground,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        // v0.42.1：全应用去阴影（ModalBottomSheet 只有 tonalElevation，没有 shadowElevation）
        tonalElevation = 0.dp,
        dragHandle = {
            Column(Modifier.padding(top = 12.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(4.5.dp)
                        .background(SheetGrip, RoundedCornerShape(999.dp)),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.2).sp,
                    color = TextPrimary,
                )
            }
        },
    ) {
        Column(
            Modifier
                // v0.55.0：弹层内容跟着"抬起"一档（淡入 + 上滑 10dp），
                // 与底栏滑入的弹簧叠在一起，不再是"框先到、内容后到"的两段观感
                .staggerEnter(0, slideDp = 12.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 34.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

/** 分组小标题：mono 技术路标风格（字距拉开，全大写由文案层控制） */
@Composable
fun NeuSectionTitle(title: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Text(
        title,
        modifier = modifier.padding(horizontal = 4.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = if (accent) NeuDanger else MutedText,
        letterSpacing = 1.1.sp,
    )
}

/**
 * 防重复点击闸门（v0.97.0，审查 U20）。
 *
 * 用法：`val gate = rememberClickGate(); onClick = { if (gate()) doSubmit() }`。
 *
 * 为什么要有它：这些动作都是"写一条记录"，而触发点常常既是行本身又是行里的按钮
 * （任务行整行可点 + 勾选圈可点、记账页「记一笔」、动态发布）。手指抖一下、或者
 * 触摸事件被两个节点各收一次，就会写出两条数据 —— 而这类脏数据在界面上看不出来，
 * 只有累计金额对不上、打卡天数多一天时才会被发现。
 *
 * 用 `SystemClock.uptimeMillis()` 而不是 `currentTimeMillis()`：前者不受用户改系统时间影响。
 */
@Composable
fun rememberClickGate(windowMs: Long = 350L): () -> Boolean {
    val last = remember { longArrayOf(Long.MIN_VALUE / 2) }
    return {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - last[0] >= windowMs) {
            last[0] = now
            true
        } else {
            false
        }
    }
}

/** 列表行通用按压包装：只缩不放（IM 行不需要凸起） */
@Composable
fun Modifier.neuRowPress(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, NeuPressSpec)
    return this
        .scale(scale)
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}

/**
 * 通用骨架屏占位（v0.97.0，审查 U2/U5/U6/U9）。
 *
 * ## 为什么要有这个共用件
 * 项目里三态纪律最早是在会话列表立起来的（`ChatListScreen` 的 `ChatListSkeleton`），
 * 但那份骨架是**私有的**，于是别的页面在"数据还没到"这一帧只能写成
 * `Box(Modifier.fillMaxSize())`（纯空白，用户以为页面坏了）或者干脆跳过加载态直接渲染空态
 * （冷启动闪一下"暂无 XX"）。把骨架提成公共件，是让"三态"这件事能一键复制。
 *
 * ## 视觉
 * 沿用设计系统：灰轨色圆角块、**零阴影**、行高节奏与真实列表一致（[rowHeight] + [gap]）。
 * 不写"加载中…"这类说明性小字（§四：界面不写说明性小字）。
 *
 * @param count 占位行数，默认 6（一屏大致能看到的行数，再多也是白画）
 * @param rowHeight 单行的块高；窄行用 14.dp，卡片型用 72.dp
 * @param gap 行间距
 * @param horizontalPadding 左右留白，与宿主页对齐
 */
@Composable
fun NeuSkeleton(
    modifier: Modifier = Modifier,
    count: Int = 6,
    rowHeight: Dp = 14.dp,
    gap: Dp = 20.dp,
    horizontalPadding: Dp = 16.dp,
) {
    val track = NeuTrack
    Column(modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 14.dp)) {
        repeat(count) { index ->
            // 末行短一点（0.55 宽），读起来像"正文的最后一行"，比齐刷刷等长更像真实内容
            val widthFraction = if (index == count - 1) 0.55f else 1f
            Box(
                Modifier
                    .fillMaxWidth(widthFraction)
                    .height(rowHeight)
                    .background(track, RoundedCornerShape(rowHeight / 2)),
            )
            if (index != count - 1) Spacer(Modifier.height(gap))
        }
    }
}

/**
 * 卡片型骨架的便捷包装：一叠圆角大块，用在"一屏两三条卡"的页面（动态 / 记忆 / 定时消息）。
 */
@Composable
fun NeuCardSkeleton(modifier: Modifier = Modifier, count: Int = 3) {
    val track = NeuTrack
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        repeat(count) { index ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(track, RoundedCornerShape(16.dp)),
            )
            if (index != count - 1) Spacer(Modifier.height(12.dp))
        }
    }
}
