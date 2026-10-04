package com.menghuan.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// ===== Figma 设计系统令牌（2026-09-28 用户拍板，取代 Neumorphism） =====
// 定调：界面层严格黑白（画廊白墙），品牌彩色只作为「产品内容」出现在
// 渐变环 / 英雄卡 / 火花标上。药丸（999）与圆形（50%）几何，虚线选择手柄是签名。
//
// v0.35.0 起令牌不再是一组写死的 val，而是一份 [NeuPalette]：
// 浅色 = 画廊白墙 + 纯黑强调，深色 = 近黑墙 + 纯白强调（严格反相）。
// 令牌读取走 [LocalNeuPalette]，因此**只能读在 @Composable 里**（含 @ReadOnlyComposable 取值）；
// drawBehind 之类的非 composable 回调请在组件里先把颜色取成局部变量再传进去。

/** 一整套界面色。浅/深两版，字段一一对应，禁止在业务代码里写死具体色值。 */
@Immutable
data class NeuPalette(
    val background: Color,
    val surface: Color,
    val track: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val muted: Color,
    val faint: Color,
    val accent: Color,
    val accentDeep: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val danger: Color,
    val shadow: Color,
    val selectionInk: Color,
    val handleFill: Color,
    val handleInk: Color,
    val grip: Color,
    val outline: Color,
    val isDark: Boolean,
)

/** 浅色：画廊白墙 + 纯黑强调（2026-09-28 起用的那一版） */
val LightPalette = NeuPalette(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    track = Color(0xFFF2F3F5),
    hairline = Color(0x14000000),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0xFF595959),
    muted = Color(0xFF8C8C8C),
    faint = Color(0xFFBFBFBF),
    accent = Color(0xFF000000),
    accentDeep = Color(0xFF1A1A1A),
    accentSoft = Color(0xFF262626),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFF24E1E),
    shadow = Color(0xFF000000),
    selectionInk = Color(0xFF000000),
    handleFill = Color(0xFFFFFFFF),
    handleInk = Color(0xFF000000),
    grip = Color(0xFFE0E0E0),
    outline = Color(0xFFD9D9D9),
    isDark = false,
)

/** 深色：近黑墙 + 纯白强调（严格反相；品牌彩色不变，仍只作内容点缀） */
val DarkPalette = NeuPalette(
    background = Color(0xFF0E0E11),
    surface = Color(0xFF17171B),
    track = Color(0xFF24242B),
    hairline = Color(0x1FFFFFFF),
    textPrimary = Color(0xFFF3F3F5),
    textSecondary = Color(0xFFB6B6BF),
    muted = Color(0xFF85858F),
    faint = Color(0xFF63636D),
    accent = Color(0xFFFFFFFF),
    accentDeep = Color(0xFFEDEDF0),
    accentSoft = Color(0xFFBFBFC6),
    onAccent = Color(0xFF0E0E11),
    danger = Color(0xFFFF6A45),
    shadow = Color(0xFF000000),
    selectionInk = Color(0xFFFFFFFF),
    handleFill = Color(0xFF17171B),
    handleInk = Color(0xFFFFFFFF),
    grip = Color(0xFF3A3A42),
    outline = Color(0xFF3A3A42),
    isDark = true,
)

/** 主题色唯一来源（AGENTS §四：界面层色一律走令牌，不许写死色值） */
val LocalNeuPalette = staticCompositionLocalOf { LightPalette }

// ===== 令牌读取入口（名字与 v0.34 保持一致，全 App 无需改调用方式） =====

val NeuBackground: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.background
val NeuSurface: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.surface
val NeuTrack: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.track
val NeuHairline: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.hairline
val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.textPrimary
val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.textSecondary
val MutedText: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.muted
val FaintText: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.faint
val NeuAccent: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.accent
val NeuAccentDeep: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.accentDeep
/** 强调色上的文字 / 图标色（浅色=白，深色=近黑） */
val AccentInk: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.onAccent
val NeuDanger: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.danger
/** 纸叠投影色（调用处再乘 7% 透明度） */
val PaperShadow: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.shadow
/** figmaSelection 虚线 / 手柄描边墨色 */
val SelectionInk: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.selectionInk
val HandleFill: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.handleFill
val HandleInk: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.handleInk
/** 弹层顶部抓手色 */
val SheetGrip: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.grip
val OutlineGray: Color @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.outline
/** 当前是否深色（系统栏图标反色等跨层判断用） */
val IsDarkTheme: Boolean @Composable @ReadOnlyComposable get() = LocalNeuPalette.current.isDark

// ===== 品牌彩色（只做「产品内容」，禁止用于界面装饰；深浅两版一致） =====

val BrandGreen = Color(0xFF0ACF83)
val BrandYellow = Color(0xFFFFC700)
val BrandPurple = Color(0xFFA259FF)
val BrandBlue = Color(0xFF1ABCFE)
val BrandSalmon = Color(0xFFFF7262)

/** 英雄渐变：绿→黄→紫→蓝回环（进度环 / 应用徽标 / 关于页头像环） */
val HeroBrush: Brush
    @Composable
    get() = remember {
        Brush.sweepGradient(listOf(BrandGreen, BrandYellow, BrandPurple, BrandBlue, BrandGreen))
    }

/** 火花渐变（AI 洞察标）：紫→橙红 */
val SparkBrush: Brush
    @Composable
    get() = remember { Brush.linearGradient(listOf(BrandPurple, BrandSalmon)) }

/** 强调渐变：强调色的竖向微渐变（主按钮 / 勾选 / 用户气泡），比纯色多一层纸感 */
val AccentBrush: Brush
    @Composable
    get() {
        val palette = LocalNeuPalette.current
        return remember(palette.accent, palette.accentSoft) {
            Brush.verticalGradient(listOf(palette.accent, palette.accentSoft))
        }
    }

/**
 * 人设专属渐变环：按 id 稳定散列到 12 组品牌配色 × 2 种走向，
 * 每个 persona 的环都不同，且重启 / 换页后不变（不做每次进页面重摇）。
 */
private val personaPairs = listOf(
    listOf(BrandGreen, BrandBlue),
    listOf(BrandPurple, BrandSalmon),
    listOf(BrandYellow, BrandSalmon),
    listOf(BrandBlue, BrandPurple),
    listOf(BrandGreen, BrandYellow),
    listOf(BrandPurple, BrandBlue),
    listOf(BrandSalmon, BrandYellow),
    listOf(BrandBlue, BrandGreen),
    listOf(BrandPurple, BrandGreen),
    listOf(BrandSalmon, BrandPurple),
    listOf(BrandYellow, BrandBlue),
    listOf(BrandGreen, BrandPurple),
)

/**
 * 人设渐变环的配色（纯函数，可单测）：稳定散列 id → 两个品牌色。
 * 单测直接比这个返回值，比对 Brush 实例更可靠（Brush 没有值相等语义）。
 */
fun personaPaletteOf(id: Long): List<Color> {
    val h = (id * 2654435761L) and 0x7FFFFFFF
    val pair = personaPairs[(h % personaPairs.size).toInt()]
    return if ((h / 11) % 2 == 0L) pair else pair.reversed()
}

/** 人设渐变环笔刷：环形适合 sweepGradient（起点在 3 点钟方向，两色各自成段） */
fun personaBrush(id: Long): Brush {
    val ordered = personaPaletteOf(id)
    return Brush.sweepGradient(listOf(ordered[0], ordered[1], ordered[0]))
}

// ===== 主题模式（设置 · 外观；跟随系统 / 强制浅色 / 强制深色） =====

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
    ;

    companion object {
        fun fromName(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/** 纯函数：模式 + 系统当前深色 → 实际用哪套（单测口径） */
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

private fun schemeFor(palette: NeuPalette) = if (palette.isDark) {
    darkColorScheme(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        secondary = palette.muted,
        background = palette.background,
        surface = palette.surface,
        surfaceVariant = palette.track,
        onSurface = palette.textPrimary,
        onBackground = palette.textPrimary,
        error = palette.danger,
        outline = palette.outline,
        onSurfaceVariant = palette.textSecondary,
    )
} else {
    lightColorScheme(
        primary = palette.accent,
        onPrimary = palette.onAccent,
        secondary = palette.muted,
        background = palette.background,
        surface = palette.surface,
        surfaceVariant = palette.track,
        onSurface = palette.textPrimary,
        onBackground = palette.textPrimary,
        error = palette.danger,
        outline = palette.outline,
        onSurfaceVariant = palette.textSecondary,
    )
}

@Composable
fun MentorTheme(
    // 调用方（MainActivity）传 resolveDarkTheme(模式, 系统深色) 的结果；
    // 默认跟随系统，页面预览可直接用默认值。
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    // 屏幕自适应：系统字体缩放过大/过小会撑破紧凑排版，这里夹到 0.9–1.25 倍
    val base = LocalDensity.current
    val density = remember(base) {
        Density(
            density = base.density,
            fontScale = base.fontScale.coerceIn(0.9f, 1.25f),
        )
    }
    val colors = remember(palette) { schemeFor(palette) }
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalNeuPalette provides palette,
    ) {
        MaterialTheme(
            colorScheme = colors,
            content = content,
        )
    }
}