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
val NeuPressDpSpec = tween<Dp>(110)
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

/** 圆形勾选框：未选 = 白底发丝线圈；已选 = 纯黑填充 + 对勾弹簧弹出 */
@Composable
fun NeuCheckCircle(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    boxSize: Dp = 24.dp,
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
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onToggle,
            ),
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

/** 开关：灰轨 + 底色钮，选中强调轨（位移走 expo） */
@Composable
fun NeuSwitch(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
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
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { onToggle(!checked) },
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
