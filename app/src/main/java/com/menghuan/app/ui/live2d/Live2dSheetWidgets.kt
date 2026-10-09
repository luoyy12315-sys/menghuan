package com.menghuan.app.ui.live2d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.live2d.GenericChannel
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary

/**
 * 角色空间那几个弹层**共用的小组件与常量**（v0.82.0 P2-3 从 `Live2dActionSheets.kt` 拆出来）。
 *
 * 弹层本体各自成文件（动作库 / 动作编辑 / AI 生成 / 背景 / 点击互动），
 * 这几样东西被其中两个以上用到，所以单独放一份，免得"改一次胶囊得改两个文件"。
 *
 * 三条项目约定在弹层这边同样成立：
 * 1. **可点区域不画涟漪**（`indication = null`），也不做"点击之后的灰色条"；
 * 2. **删除类操作一律长按 → 菜单里点删除**，长按补一次震动；
 * 3. 每层都只有一个主操作，别把"预览 / 保存 / 收藏 / 删除"平铺成一排。
 */

// =====================================================================
// 共用常量与标签
// =====================================================================

internal const val NONE_KEY = "__none__"

/** 高级模式里一条通道叫什么：`组名 · 通道名`（"头部 · 上下点头"），比裸通道名好认 */
internal fun channelLabel(ch: GenericChannel): String = "${ch.group.label} · ${ch.label}"

internal val AI_EXAMPLES = listOf(
    "做一个开心地挥手动作",
    "做一个害羞地低头动作",
    "做一个生气地跺脚动作",
    "做一个夸张的翻跟斗动作",
)

// =====================================================================
// 小组件（这一批弹层共用）
// =====================================================================

/** 选择胶囊：选中 = 纯黑药丸白字（与全局 NeuChip 同一套语言） */
@Composable
internal fun PickChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .neuSurface(999.dp, color = if (selected) NeuAccent else NeuSurface)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) AccentInk else TextPrimary,
            maxLines = 1,
        )
    }
}

/** 药丸主按钮：filled = 纯黑实心，否则白纸 + 发丝线 */
@Composable
internal fun PrimaryPill(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .neuSurface(999.dp, color = if (filled) NeuAccent else NeuSurface, intensity = if (enabled) 1f else 0.4f)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (filled) AccentInk else TextPrimary,
        )
    }
}

/**
 * 滑块行。
 *
 * 项目里原本没有滑块（这是第一处用到），所以自己画一个而不是引入 M3 的 `Slider`：
 * 后者的轨道/滑块尺寸与配色是它自己那套，和这里"发丝线 + 纯黑填充 + 圆形白钮"的语言对不上，
 * 硬套会在一屏里出现两种控件质感。
 *
 * 拖动与点按各占一个 pointerInput（同 Live2dSpaceScreen 里那两个手势的道理）：
 * 合在一起的话点一下改不了值。
 */
@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    val track = NeuTrack
    val fill = NeuAccent
    val knob = NeuSurface
    val hairline = NeuHairline

    fun valueAt(x: Float, width: Float): Float {
        val frac = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return range.start + (range.endInclusive - range.start) * frac
    }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = TextSecondary, modifier = Modifier.weight(1f))
            Text(display, fontSize = 12.5.sp, color = TextPrimary)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(range) {
                    val w = size.width.toFloat()
                    detectTapGestures { pos -> onValueChange(valueAt(pos.x, w)) }
                }
                .pointerInput(range) {
                    val w = size.width.toFloat()
                    detectHorizontalDragGestures { change, _ ->
                        change.consume()
                        onValueChange(valueAt(change.position.x, w))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                val h = 6.dp.toPx()
                val cy = size.height / 2f
                val r = CornerRadius(h / 2f)
                // 轨道
                drawRoundRect(
                    color = track,
                    topLeft = Offset(0f, cy - h / 2f),
                    size = Size(size.width, h),
                    cornerRadius = r,
                )
                // 已填部分
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(0f, cy - h / 2f),
                    size = Size((size.width * frac).coerceAtLeast(h), h),
                    cornerRadius = r,
                )
                // 滑块：白圆 + 发丝线（"界面层只有发丝线一种分隔手段"）
                val knobR = 10.dp.toPx()
                val cx = (size.width * frac).coerceIn(knobR, size.width - knobR)
                drawCircle(color = knob, radius = knobR, center = Offset(cx, cy))
                drawCircle(
                    color = hairline,
                    radius = knobR,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }
}
