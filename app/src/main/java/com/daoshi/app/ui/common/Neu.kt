package com.daoshi.app.ui.common

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoshi.app.ui.theme.AccentBrush
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.NeuAccentDeep
import com.daoshi.app.ui.theme.NeuBackground
import com.daoshi.app.ui.theme.NeuDanger
import com.daoshi.app.ui.theme.NeuShadowDark
import com.daoshi.app.ui.theme.NeuShadowLight
import com.daoshi.app.ui.theme.NeuSurface
import com.daoshi.app.ui.theme.TextPrimary
import com.daoshi.app.ui.theme.TextSecondary

// ===== 动效令牌（新拟态微交互统一时序） =====
val EaseOutExpo = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
val EaseOutQuart = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
val NeuPressSpec = tween<Float>(110)
val NeuStateSpec = tween<Float>(220, easing = EaseOutQuart)
val NeuPressDpSpec = tween<Dp>(110)
val NeuSpring = spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)

/**
 * 按压态参数：凸起元素被按下时"沉入纸面"——阴影收矮变淡、整体微缩。
 * 所有可点凸起组件统一走这一套，保证手感一致。
 * 注意：直接持有 animateXxxAsState 的当前值，每次重组刷新，不要 remember 缓存旧值。
 */
class NeuPressState internal constructor(
    val interactionSource: MutableInteractionSource,
    val elevation: Dp,
    val scale: Float,
)

@Composable
fun rememberNeuPressState(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    raised: Dp = 6.dp,
): NeuPressState {
    val pressed by interactionSource.collectIsPressedAsState()
    val elevation by animateDpAsState(if (pressed) raised * 0.38f else raised, NeuPressDpSpec)
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, NeuPressSpec)
    return NeuPressState(interactionSource, elevation, scale)
}

/**
 * 新拟态表面（核心）：在 drawBehind 里先画双向软阴影、再画面体填充。
 * 凸起（inset=false）：右下深色投影 + 左上白色反光；凹陷（inset=true）：内缘双渐变描边。
 * 全部走 GPU 渐变绘制，不用 BlurMaskFilter（硬件加速下不生效）/Modifier.blur（API31+），
 * minSdk 26 真机也流畅；阴影重绘只发生在参数变化时（按压动画帧）。
 */
fun Modifier.neuSurface(
    cornerRadius: Dp,
    color: Color = NeuSurface,
    elevation: Dp = 6.dp,
    intensity: Float = 1f,
    inset: Boolean = false,
    shape: Shape = RoundedCornerShape(cornerRadius),
    clipContent: Boolean = true,
): Modifier {
    val dark = NeuShadowDark
    val light = NeuShadowLight
    return this
        .drawBehindNeu(cornerRadius, color, elevation, intensity, inset, dark, light)
        .then(if (clipContent) Modifier.clip(shape) else Modifier)
}

/** 拆分出来只为让上面链式可读；真正的绘制在这里 */
private fun Modifier.drawBehindNeu(
    cornerRadius: Dp,
    color: Color,
    elevation: Dp,
    intensity: Float,
    inset: Boolean,
    dark: Color,
    light: Color,
): Modifier = this.then(
    Modifier.drawBehind {
        val r = cornerRadius.toPx()
        val e = elevation.toPx().coerceAtLeast(0.5f)
        val w = size.width
        val h = size.height
        if (inset) {
            drawRoundRect(color, size = size, cornerRadius = CornerRadius(r))
            drawInsetEdges(w, h, r, e, dark, light, intensity)
        } else {
            drawRaisedShadows(w, h, r, e, dark, light, intensity)
            drawRoundRect(color, size = size, cornerRadius = CornerRadius(r))
        }
    },
)

/** 凸起双向软阴影：两张膨胀圆角矩形 + 方向线性渐变，越靠元素边缘越浓、向外淡出 */
private fun DrawScope.drawRaisedShadows(
    w: Float, h: Float, r: Float, e: Float,
    dark: Color, light: Color, intensity: Float,
) {
    val blur = e * 2.6f
    // 深色投影（右下）
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(dark.copy(alpha = 0.55f * intensity), dark.copy(alpha = 0f)),
            start = Offset(w * 0.78f, h * 0.78f),
            end = Offset(w + blur * 1.6f, h + blur * 1.6f),
        ),
        topLeft = Offset(e, e),
        size = Size(w + blur, h + blur),
        cornerRadius = CornerRadius(r + e),
    )
    // 白色反光（左上）
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(light.copy(alpha = 0.95f * intensity), light.copy(alpha = 0f)),
            start = Offset(w * 0.22f, h * 0.22f),
            end = Offset(-blur * 1.6f, -blur * 1.6f),
        ),
        topLeft = Offset(-blur, -blur),
        size = Size(w + blur, h + blur),
        cornerRadius = CornerRadius(r + blur),
    )
}

/** 凹陷内缘：裁进面体后，沿内缘画两条渐变描边（深色贴左上、白色贴右下），模拟嵌入纸面 */
private fun DrawScope.drawInsetEdges(
    w: Float, h: Float, r: Float, e: Float,
    dark: Color, light: Color, intensity: Float,
) {
    val path = androidx.compose.ui.graphics.Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, CornerRadius(r)))
    }
    val strokeWidth = e * 2.4f
    clipPath(path) {
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(dark.copy(alpha = 0.5f * intensity), dark.copy(alpha = 0f)),
                start = Offset(-e, -e),
                end = Offset(w * 0.55f, h * 0.55f),
            ),
            topLeft = Offset(-e, -e),
            size = Size(w + e * 2, h + e * 2),
            cornerRadius = CornerRadius(r + e),
            style = Stroke(width = strokeWidth),
        )
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(light.copy(alpha = 0.95f * intensity), light.copy(alpha = 0f)),
                start = Offset(w + e, h + e),
                end = Offset(w * 0.45f, h * 0.45f),
            ),
            topLeft = Offset(-e, -e),
            size = Size(w + e * 2, h + e * 2),
            cornerRadius = CornerRadius(r + e),
            style = Stroke(width = strokeWidth),
        )
    }
}

/** 凸起卡片容器。content 为 **ColumnScope**：卡片内容纵向流式排列（与预览 HTML 一致）。
 *  注意不能用 BoxScope——多个子元素会被 Box 全部叠在同一位置，文字叠印成"乱码"（v0.2.2 修复）。 */
@Composable
fun NeuCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    elevation: Dp = 6.dp,
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

/** 圆形按压凸起按钮（顶栏/输入栏用）：42dp 命中区 + 阴影收缩反馈 */
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
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, NeuStateSpec)
    Box(
        modifier
            .scale(press.scale)
            .size(size)
            .neuSurface(
                size / 2,
                color = if (accent) NeuAccent else NeuSurface,
                elevation = press.elevation,
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

/** 主/次/危险 文字按钮：凸起胶囊，主按钮用强调渐变 */
@Composable
fun NeuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: NeuButtonKind = NeuButtonKind.Secondary,
    enabled: Boolean = true,
) {
    val press = rememberNeuPressState()
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, NeuStateSpec)
    val contentColor = when (kind) {
        NeuButtonKind.Primary -> Color.White
        NeuButtonKind.Danger -> NeuDanger
        NeuButtonKind.Secondary -> TextPrimary
    }
    Box(
        modifier
            .scale(press.scale)
            .fillMaxWidth()
            .height(52.dp)
            .then(
                if (kind == NeuButtonKind.Primary) {
                    Modifier.drawAccentFill(press.elevation, alpha)
                } else {
                    Modifier.neuSurface(
                        18.dp,
                        color = NeuSurface,
                        elevation = press.elevation,
                        intensity = alpha,
                    )
                },
            )
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = contentColor)
    }
}

enum class NeuButtonKind { Primary, Secondary, Danger }

/** 主按钮渐变填充 + 柔和投影（强调色专用的"发光下沉"质感） */
private fun Modifier.drawAccentFill(elevation: Dp, alpha: Float): Modifier = this.then(
    Modifier.drawBehind {
        val e = elevation.toPx().coerceAtLeast(0.5f)
        val blur = e * 3f
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(
                    NeuAccent.copy(alpha = 0.55f * alpha),
                    NeuAccentDeep.copy(alpha = 0.45f * alpha),
                ),
            ),
            topLeft = Offset(e * 0.6f, e * 1.1f),
            size = Size(size.width + blur * 0.4f, size.height + blur * 0.7f),
            cornerRadius = CornerRadius(18.dp.toPx() + e),
        )
        drawRoundRect(AccentBrush, size = size, cornerRadius = CornerRadius(18.dp.toPx()))
    }.clip(RoundedCornerShape(18.dp)),
)

/** 凹陷输入框：内嵌纸面 + 前景点缀光标，label 浮在上方 */
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
) {
    Column(modifier) {
        if (label != null) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextSecondary)
            Spacer(Modifier.height(7.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .neuSurface(16.dp, color = NeuBackground, elevation = 5.dp, inset = true),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = TextPrimary),
                singleLine = singleLine,
                maxLines = maxLines,
                minLines = minLines,
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

/** 筛选胶囊（任务日期等）：选中=凹陷强调底，未选=凸起 */
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
                color = if (selected) NeuBackground else NeuSurface,
                elevation = if (selected) 4.dp else press.elevation,
                inset = selected,
                shape = RoundedCornerShape(999.dp),
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
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) NeuAccent else TextPrimary,
        )
    }
}

/** 圆形勾选框：未选=凹陷圆点，已选=渐变强调填充 + 对勾弹出 */
@Composable
fun NeuCheckCircle(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    boxSize: Dp = 24.dp,
) {
    val press = rememberNeuPressState(raised = 4.dp)
    val checkScale by animateFloatAsState(if (checked) 1f else 0f, NeuSpring)
    Box(
        modifier
            .scale(press.scale)
            .size(boxSize)
            .then(
                if (checked) {
                    Modifier.drawBehind {
                        val e = press.elevation.toPx()
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(
                                    NeuAccent.copy(alpha = 0.5f),
                                    NeuAccentDeep.copy(alpha = 0.4f),
                                ),
                            ),
                            topLeft = Offset(e * 0.5f, e * 0.9f),
                            size = Size(size.width + e, size.height + e * 1.4f),
                            cornerRadius = CornerRadius(size.width / 2 + e),
                        )
                        drawCircle(AccentBrush)
                    }.clip(CircleShape)
                } else {
                    Modifier.neuSurface(
                        boxSize / 2,
                        color = NeuBackground,
                        elevation = press.elevation,
                        inset = true,
                        shape = CircleShape,
                    )
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
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.scale(checkScale),
        )
    }
}

/** 新拟态开关：凹陷轨道 + 凸起滑块，滑块位移用弹簧 */
@Composable
fun NeuSwitch(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val knobOffset by animateDpAsState(
        if (checked) 20.dp else 3.dp,
        tween(220, easing = EaseOutQuart),
    )
    Box(
        modifier
            .width(46.dp)
            .height(27.dp)
            .neuSurface(999.dp, color = NeuBackground, elevation = 4.dp, inset = true)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { onToggle(!checked) },
    ) {
        Box(
            Modifier
                .offset(x = knobOffset, y = 3.dp)
                .size(21.dp)
                .neuSurface(
                    999.dp,
                    color = if (checked) NeuAccent else NeuSurface,
                    elevation = 4.dp,
                    shape = CircleShape,
                ),
        )
    }
}

/** 设置/风格面板里的选项行：图标凹陷圆 + 标题/副题 + 右箭头或选中钩 */
@Composable
fun NeuSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val press = rememberNeuPressState(raised = 4.dp)
    Row(
        modifier
            .scale(press.scale)
            .fillMaxWidth()
            .neuSurface(18.dp, color = NeuSurface, elevation = press.elevation)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .neuSurface(999.dp, color = NeuBackground, elevation = 4.dp, inset = true),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) NeuAccent else TextPrimary,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 12.sp, color = MutedText, maxLines = 1)
            }
        }
        if (selected) {
            Text("✓", fontSize = 15.sp, color = NeuAccent, fontWeight = FontWeight.Bold)
        } else {
            Icon(
                com.daoshi.app.ui.theme.MIcons.ChevronRight,
                contentDescription = null,
                tint = FaintLocal,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private val FaintLocal = Color(0xFFB9C0D0)

/** 弹层通用容器：新拟态底 + 顶部抓握手柄 + 统一标题 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeuSheetScaffold(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NeuBackground,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = {
            Column(Modifier.padding(top = 12.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.5.dp)
                        .neuSurface(999.dp, color = NeuBackground, elevation = 3.dp, inset = true),
                )
                Spacer(Modifier.height(14.dp))
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            }
        },
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 34.dp)) {
            content()
        }
    }
}

/** 分组小标题（凹陷字感：灰蓝小字 + 字距） */
@Composable
fun NeuSectionTitle(title: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Text(
        title,
        modifier = modifier.padding(horizontal = 4.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (accent) NeuDanger else MutedText,
        letterSpacing = 0.8.sp,
    )
}

/** 列表行通用按压包装：只缩不放阴影（IM 行不需要凸起） */
@Composable
fun Modifier.neuRowPress(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, NeuPressSpec)
    return this
        .scale(scale)
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}
