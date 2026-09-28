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

// ===== Figma 设计系统令牌（2026-09-28 用户拍板，取代 Neumorphism） =====
// 定调：界面层严格黑白（画廊白墙），品牌彩色只作为「产品内容」出现在
// 渐变环 / 英雄卡 / 火花标上。药丸（999）与圆形（50%）几何，虚线选择手柄是签名。

val NeuBackground = Color(0xFFFFFFFF)   // 页面基底：画廊白
val NeuSurface = Color(0xFFFFFFFF)      // 凸起面：白卡片
val NeuTrack = Color(0xFFF2F3F5)        // 凹陷/输入面：浅灰纸（Figma 画布灰）
val NeuHairline = Color(0x14000000)     // 发丝线：黑 8%

val TextPrimary = Color(0xFF000000)     // 纯黑：所有文字
val TextSecondary = Color(0xFF595959)
val MutedText = Color(0xFF8C8C8C)
val FaintText = Color(0xFFBFBFBF)

// 强调色 = 纯黑（Figma 界面唯一"颜色"）：主按钮 / 勾选 / 未读点 / 指示器
val NeuAccent = Color(0xFF000000)
val NeuAccentDeep = Color(0xFF1A1A1A)
val NeuDanger = Color(0xFFF24E1E)       // Figma 橙红：仅破坏性操作

// ===== 品牌彩色（只做「产品内容」，禁止用于界面装饰） =====
val BrandGreen = Color(0xFF0ACF83)
val BrandYellow = Color(0xFFFFC700)
val BrandPurple = Color(0xFFA259FF)
val BrandBlue = Color(0xFF1ABCFE)
val BrandSalmon = Color(0xFFFF7262)

/** 英雄渐变：绿→黄→紫→蓝回环（进度环 / 头像环 / 英雄卡） */
val HeroBrush: Brush
    get() = Brush.sweepGradient(listOf(BrandGreen, BrandYellow, BrandPurple, BrandBlue, BrandGreen))

/** 火花渐变（AI 洞察标）：紫→橙红 */
val SparkBrush: Brush
    get() = Brush.linearGradient(listOf(BrandPurple, BrandSalmon))

/** 品牌彩渐变笔刷（按 id 稳定取色，头像环等「产品内容」用） */
private val personaPalettes = listOf(
    listOf(BrandGreen, BrandBlue),
    listOf(BrandPurple, BrandSalmon),
    listOf(BrandYellow, BrandSalmon),
    listOf(BrandBlue, BrandPurple),
)

fun personaBrush(id: Long): Brush {
    val pair = personaPalettes[(id.hashCode().toLong() and 0x7FFFFFFFL).toInt() % personaPalettes.size]
    return Brush.linearGradient(pair)
}

/** 强调渐变：黑色微渐变（主按钮 / 勾选 / 用户气泡），比纯色多一层纸感 */
val AccentBrush: Brush
    get() = Brush.verticalGradient(listOf(Color(0xFF000000), Color(0xFF262626)))

private val LightColors = lightColorScheme(
    primary = NeuAccent,
    onPrimary = Color.White,
    secondary = MutedText,
    background = NeuBackground,
    surface = NeuSurface,
    surfaceVariant = NeuTrack,
    onSurface = TextPrimary,
    error = NeuDanger,
    outline = Color(0xFFD9D9D9),
)

@Composable
fun MentorTheme(
    // 浅色锁定：黑白界面不跟系统深色（v29 已退出 Force Dark）
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
