package com.menghuan.app.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.TextPrimary

/**
 * 长按菜单的统一外壳与条目（v0.63.0）。
 *
 * **为什么不直接用 M3 的 `DropdownMenuItem`**：它的最小行高是写死的 48dp、
 * 默认字号跟着 Material 的 bodyLarge 走，条目一多就撑成一大块，
 * 和本 App 正文 14.5sp / 说明文字 12sp 的密度对不上（v0.63.0 用户点名"菜单太大、和字体不匹配"）。
 * 行高这东西 `Modifier` 压不动，只能自己画 `Box + Text`。
 *
 * 锚点规矩不变：`DropdownMenu` 的 Popup 锚定它的**直接父节点**，
 * 所以调用方仍要把它包进一个贴在行右下角（或手指位置）的小 Box 里。
 */
@Composable
fun NeuMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        // 一圈发丝线：v0.42.1 起全应用去阴影，白底菜单落在白页面上边界会糊成一片
        modifier = Modifier.border(1.dp, NeuHairline, RoundedCornerShape(12.dp)),
        containerColor = NeuSurface,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 0.dp,
    ) { content() }
}

/** 紧凑菜单条目：13sp + 上下 8dp（整行约 33dp，比 M3 默认矮 15dp）；danger = 删除类红字 */
@Composable
fun ColumnScope.NeuMenuItem(
    text: String,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    val press = rememberNeuPressState(raised = 2.dp)
    androidx.compose.foundation.layout.Box(
        Modifier
            .fillMaxWidth()
            .scale(press.scale)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            color = if (danger) NeuDanger else TextPrimary,
        )
    }
}
