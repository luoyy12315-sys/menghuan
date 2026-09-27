package com.daoshi.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// ===== 新拟态（Neumorphism）设计令牌 =====
// 定调：柔和灰蓝基底 + 深/浅双向软阴影 + 单一靛蓝点睛。浅色锁定（AGENTS §四）。

val NeuBackground = Color(0xFFE4E9F3)   // 页面基底
val NeuSurface = Color(0xFFEDF1F9)      // 凸起面填充（比基底略亮，卡片/按钮）
val NeuShadowDark = Color(0xFFBFC9DC)   // 右下深色投影
val NeuShadowLight = Color(0xFFFFFFFF)  // 左上白色反光
val NeuTrack = Color(0xFFD9DFEC)        // 凹陷轨道底（进度环/开关）

val TextPrimary = Color(0xFF3D4557)
val TextSecondary = Color(0xFF717A93)
val MutedText = Color(0xFF99A1B6)
val FaintText = Color(0xFFB9C0D0)

val NeuAccent = Color(0xFF6C7DF0)       // 唯一强调色：发送/勾选/未读/进度
val NeuAccentDeep = Color(0xFF5766DA)
val NeuDanger = Color(0xFFE06078)

/** 强调渐变（竖直）：仅用于主按钮、发送键、完成勾选、进度环 */
val AccentBrush: Brush
    get() = Brush.verticalGradient(listOf(NeuAccent, NeuAccentDeep))

private val LightColors = lightColorScheme(
    primary = NeuAccent,
    onPrimary = Color.White,
    secondary = MutedText,
    background = NeuBackground,
    surface = NeuSurface,
    surfaceVariant = NeuBackground,
    onSurface = TextPrimary,
    error = NeuDanger,
    outline = Color(0xFFC6CEDD),
)

@Composable
fun MentorTheme(
    // 新拟态锁定浅色：暗色阴影体系未建，跟系统深色会"黑底黑字"
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 屏幕自适应：系统字体缩放过大/过小会撑破紧凑排版，这里夹到 0.9–1.25 倍
    val base = LocalDensity.current
    val density = remember(base) {
        Density(
            density = base.density,
            fontScale = base.fontScale.coerceIn(0.9f, 1.25f),
        )
    }
    CompositionLocalProvider(LocalDensity provides density) {
        MaterialTheme(
            colorScheme = LightColors,
            content = content,
        )
    }
}
