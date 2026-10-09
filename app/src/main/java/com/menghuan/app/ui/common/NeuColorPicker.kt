package com.menghuan.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.ui.theme.BrandBlue
import com.menghuan.app.ui.theme.BrandGreen
import com.menghuan.app.ui.theme.BrandPink
import com.menghuan.app.ui.theme.BrandPurple
import com.menghuan.app.ui.theme.BrandSalmon
import com.menghuan.app.ui.theme.BrandYellow
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.TextPrimary

// ============================================================
// 色盘（v0.80.0，聊天气泡自定义配色用）
// ============================================================

/**
 * 把颜色拆成 HSV 三轴（各 0~1，纯函数）。
 *
 * Compose 自带 `Color.hsv()` 却没有配套的 `toHsv()`，自己写这三个数更直接 ——
 * 色盘要的就是 H/S/V 三个数，不是色彩空间对象。
 */
fun colorToHsv(color: Color): Triple<Float, Float, Float> {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta <= 0f -> 0f
        max == r -> (((g - b) / delta).let { if (it < 0f) it + 6f else it }) / 6f
        max == g -> ((b - r) / delta + 2f) / 6f
        else -> ((r - g) / delta + 4f) / 6f
    }
    val sat = if (max <= 0f) 0f else delta / max
    return Triple(hue, sat, max)
}

/** HSV（各轴 0~1）转颜色；alpha 沿用调用方给的（色盘全程不透明） */
fun hsvToColor(hue: Float, saturation: Float, value: Float, alpha: Float = 1f): Color {
    val h = ((hue - hue.toInt()).coerceIn(0f, 1f)) * 6f
    val i = h.toInt()
    val f = h - i
    val p = value * (1f - saturation)
    val q = value * (1f - saturation * f)
    val t = value * (1f - saturation * (1f - f))
    val (r, g, b) = when (i % 6) {
        0 -> Triple(value, t, p)
        1 -> Triple(q, value, p)
        2 -> Triple(p, value, t)
        3 -> Triple(p, q, value)
        4 -> Triple(t, p, value)
        else -> Triple(value, p, q)
    }
    return Color(r, g, b, alpha)
}

/**
 * 色盘弹层（v0.80.0）：明度方盘 + 色相条 + 快捷色 + 当前色确认。
 *
 * ## 为什么手写而不引第三方拾色器
 * 拾色器就三样东西：一块「横向加白、纵向加黑」的方盘 + 一条彩虹色相条 + 一颗 thumb。
 * 两个 Canvas 一次画完、手势各一个 pointerInput —— 引一个拾色器库来干这件事不划算，
 * 且第三方组件的视觉（Material 大圆盘）与这套黑白语言不合。
 *
 * ## 交互
 * - 点/拖方盘 = 同时定饱和度（横向）与明度（纵向，上亮下暗）；
 * - 点/拖色相条 = 只定色相（方盘底色跟着变）；
 * - 快捷色一排 = 常用色一键到位（黑白灰 + 旧版四档低饱和色 + 品牌六色 —— 都是这套 App 里出现过的颜色）；
 * - 「用这个颜色」才落值，点弹层外面 = 放弃。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NeuColorPickerSheet(
    title: String,
    initial: Color,
    onPick: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    val (h0, s0, v0) = remember(initial) { colorToHsv(initial) }
    var hue by remember { mutableFloatStateOf(h0) }
    var sat by remember { mutableFloatStateOf(s0) }
    var value by remember { mutableFloatStateOf(v0) }
    val current = hsvToColor(hue, sat, value)

    NeuSheetScaffold(title = title, onDismiss = onDismiss) {
        // 明度方盘：横向 = 饱和度，纵向 = 明度（上亮下暗）
        var svSize by remember { mutableStateOf(IntSize.Zero) }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .clip(RoundedCornerShape(14.dp))
                .onSizeChanged { svSize = it }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        setSvFromPos(pos, svSize, { sat = it }, { value = it })
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        setSvFromPos(change.position, svSize, { sat = it }, { value = it })
                    }
                },
        ) {
            drawRect(hsvToColor(hue, 1f, 1f))
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.Transparent)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            // thumb：当前 (sat,value) 处一颗白环
            val cx = sat * size.width
            val cy = (1f - value) * size.height
            drawCircle(Color.White, radius = 26f, center = Offset(cx, cy), style = Stroke(width = 6f))
            drawCircle(Color.Black.copy(alpha = 0.35f), radius = 26f, center = Offset(cx, cy), style = Stroke(width = 2.5f))
        }
        Spacer(Modifier.height(12.dp))

        // 色相条：只定色相
        var hueSize by remember { mutableStateOf(IntSize.Zero) }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(999.dp))
                .onSizeChanged { hueSize = it }
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        hue = (pos.x / hueSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        hue = (change.position.x / hueSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                    }
                },
        ) {
            drawRect(
                Brush.horizontalGradient(
                    listOf(
                        Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
                        Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
                    ),
                ),
            )
            val cx = hue * size.width
            drawCircle(
                Color.White, radius = size.height / 2f - 4f,
                center = Offset(cx, size.height / 2f), style = Stroke(width = 5f),
            )
            drawCircle(current, radius = size.height / 2f - 8f, center = Offset(cx, size.height / 2f))
        }
        Spacer(Modifier.height(12.dp))

        // 快捷色（FlowRow：一排放不下就换行，不挤爆）
        val quick = remember {
            listOf(
                Color(0xFF1A1A1A), Color(0xFF4A4A4A), Color(0xFFEDEEF1), Color(0xFFF7F7F8),
                Color(0xFF3A4A7A), Color(0xFF3E5B4A), Color(0xFFE7DCCB), Color(0xFF8C5A4A),
                BrandGreen, BrandYellow, BrandPurple, BrandBlue, BrandSalmon, BrandPink,
            )
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            quick.forEach { c ->
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(c)
                        .border(1.dp, NeuHairline, CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            val (h, s, v) = colorToHsv(c)
                            hue = h; sat = s; value = v
                        },
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // 当前色 + 确认
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(current)
                    .border(1.dp, NeuHairline, RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Text("#%06X".format(current.toArgb() and 0xFFFFFF), fontSize = 13.sp, color = TextPrimary)
            Spacer(Modifier.weight(1f))
            NeuButton("用这个颜色", { onPick(current) }, kind = NeuButtonKind.Primary)
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** 方盘手势换算：x → 饱和度，y → 明度（上亮下暗） */
private inline fun setSvFromPos(
    pos: Offset,
    size: IntSize,
    setSat: (Float) -> Unit,
    setValue: (Float) -> Unit,
) {
    if (size.width <= 0 || size.height <= 0) return
    setSat((pos.x / size.width).coerceIn(0f, 1f))
    setValue((1f - pos.y / size.height).coerceIn(0f, 1f))
}
