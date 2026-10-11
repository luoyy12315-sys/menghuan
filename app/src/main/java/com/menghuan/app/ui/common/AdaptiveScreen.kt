package com.menghuan.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.ui.theme.HeroBrush
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary

/** 版心上限：手机 / 默认平板版心（AGENTS §四）。平板宽内容页用 [WideMaxContentWidth]。 */
val ContentMaxWidth = 600.dp

/** 平板（>=600dp）数据 / 任务 / 我的 页的内容区最大宽 */
val WideMaxContentWidth = 840.dp

/**
 * 窗口宽度等级（MainActivity 用 calculateWindowSizeClass 计算后提供）：
 * != Compact 即 >=600dp 平板 / 展开态折叠屏，页面切换宽版式（双栏消息 / 两列网格）。
 */
val LocalWindowWidthClass = staticCompositionLocalOf { WindowWidthSizeClass.Compact }

/** 当前窗口是否宽屏（>=600dp） */
@Composable
fun isExpandedWindow(): Boolean = LocalWindowWidthClass.current != WindowWidthSizeClass.Compact

/**
 * 当前页面**版心宽度**（手机 = 屏幕宽；平板/折叠屏 = 版心上限）。
 * 气泡等按比例缩放的元素一律以它为基准，别再用 LocalConfiguration.screenWidthDp。
 */
val LocalContentWidth = staticCompositionLocalOf { 390.dp }

/**
 * 自适应页面容器：**唯一**处理系统栏 insets 的地方（页面内部不要再加 statusBarsPadding）。
 * - 手机（< 600dp）：内容铺满，与设计稿 1:1
 * - 平板 / 折叠屏（>= 600dp）：内容居中收窄到 [contentMaxWidth]（默认 600dp，宽内容页传 840dp），
 *   两侧留浅灰边槽
 */
@Composable
fun AdaptiveScreen(
    modifier: Modifier = Modifier,
    contentMaxWidth: Dp = ContentMaxWidth,
    /**
     * 是否由本容器负责底部导航条避让。默认 true。
     * 单聊页（ChatScreen）传 false：它的输入栏必须把 `ime` 与 `navigationBars` 合并成
     * 单一量做动画，若这里再加一层 padding，键盘收起时两处各自变化 → 输入栏"往上弹"
     * （v0.42.4 修复的用户反馈）。传 false 时页面必须自己处理底部 inset。
     */
    applyNavigationBarsPadding: Boolean = true,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = isExpandedWindow()
        val contentWidth = if (wide) contentMaxWidth.coerceAtMost(maxWidth) else maxWidth
        Box(
            Modifier
                .fillMaxSize()
                .background(if (wide) NeuTrack else MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                // 手势导航条避让：顶级 tab 页由底栏消费该 inset（Scaffold innerPadding
                // 已 consumeWindowInsets），单聊等隐藏底栏的 push 页由此处补上
                .then(if (applyNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier),
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .then(
                        if (wide) {
                            Modifier.widthIn(max = contentWidth).align(Alignment.TopCenter)
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    )
                    .background(MaterialTheme.colorScheme.background),
            ) {
                CompositionLocalProvider(LocalContentWidth provides contentWidth) {
                    content()
                }
            }
        }
    }
}

/**
 * 宽屏双栏容器（消息页平板模式专用）：左列表列 360dp + 发丝线分隔 + 右自适应列。
 * insets 同样只在这里处理（与 [AdaptiveScreen] 同一红线）。
 */
@Composable
fun AdaptiveSplitScreen(
    modifier: Modifier = Modifier,
    /** 同 [AdaptiveScreen.applyNavigationBarsPadding]：右栏是单聊时传 false，底部避让交给输入栏。 */
    applyNavigationBarsPadding: Boolean = true,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(NeuTrack)
            .statusBarsPadding()
            .then(if (applyNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier),
    ) {
        Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(Modifier.width(360.dp).fillMaxHeight()) {
                CompositionLocalProvider(LocalContentWidth provides 360.dp) { left() }
            }
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(NeuHairline),
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(LocalContentWidth provides maxWidth) { right() }
            }
        }
    }
}

/**
 * 标准顶栏：内容高 56dp，无发丝线（靠间距分区）。
 * 左右槽位宽度按版心比例自适应（56–84dp），保证标题永远居中；
 * 动作按钮超过两颗时传 [actionsWidth] 显式加宽右侧槽（如任务页三按钮 148dp）。
 */
@Composable
fun NeuTopBar(
    /**
     * v0.55.0：顶栏自身的外挂装饰（聊天页滚动时在下沿画一条发丝线用它）。
     * 放在第一位且给默认值，所有既有调用点一行都不用改。
     */
    modifier: Modifier = Modifier,
    title: String? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    center: @Composable (() -> Unit)? = null,
    actionsWidth: Dp? = null,
) {
    // 84dp = 双 38dp 图标按钮 + 8dp 间距：会话列表右侧两颗按钮正好放下不外溢
    val slot = actionsWidth ?: (LocalContentWidth.current * 0.195f).coerceIn(56.dp, 84.dp)
    Column(modifier.fillMaxWidth().background(Color.Transparent)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(slot), contentAlignment = Alignment.CenterStart) {
                navigationIcon?.invoke()
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (center != null) {
                    center()
                } else if (title != null) {
                    Text(
                        title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Box(Modifier.width(slot), contentAlignment = Alignment.CenterEnd) {
                actions?.invoke()
            }
        }
    }
}

/**
 * 头像：底色圆 + 发丝线 + emoji；[imagePath] 非空时改为渲染自定义图片（v0.35.0）。
 * accentRing = 一圈英雄品牌渐变环（关于页 App 徽标用，「产品内容」彩色）。
 * ringBrush = 人设专属渐变环（会话列表 / 聊天顶栏 / 编辑器预览）。
 *
 * 图片按 size 采样解码，文件缺失或解码失败一律回退 emoji（绝不出现空白头像）。
 * [modifier] 供调用方加点击等交互（人设编辑器直接点头像换图）—— 加在圆的外层，
 * 传入时调用方负责 clip(CircleShape) 才能让点击热区也是圆的。
 *
 * [emojiScaleX] / [emojiScaleY]（v0.56.9）：**只缩放 emoji 本身**，圆底与环不动 ——
 * 编辑人设里用它做"放大镜"：滑到的那个 emoji 是被**挤进**头像框的（从放大态压回原大小）。
 * 默认 1f，其它调用点不受影响。
 *
 * [emojiMaxScale]（v0.56.11）：emojiScale 的**放大上限**。emoji 是位图字形，graphicsLayer
 * 放大必糊——传它之后 emoji 按"基准字号 × emojiMaxScale"渲染、layer 改做 ≤1 的缩小
 * （emojiScaleX/Y ÷ emojiMaxScale），动效观感不变、位图不再被拉糊。默认 1f 行为不变。
 */
@Composable
fun NeuAvatar(
    emoji: String,
    size: Dp = 42.dp,
    accentRing: Boolean = false,
    ringBrush: androidx.compose.ui.graphics.Brush? = null,
    imagePath: String = "",
    emojiScaleX: Float = 1f,
    emojiScaleY: Float = 1f,
    emojiMaxScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val bitmap = remember(imagePath, size, density) {
        if (imagePath.isBlank()) null
        else decodeSampledBitmap(imagePath, with(density) { size.roundToPx() })
    }
    Box(
        modifier
            .size(size)
            .neuSurface(size / 2, color = NeuSurface),
        contentAlignment = Alignment.Center,
    ) {
        if (accentRing) {
            // 环不再加 padding：就画在最外圈，内容内缩到环内侧（v0.53.4）
            Box(
                Modifier
                    .fillMaxSize()
                    .drawRing(HeroBrush),
            )
        } else if (ringBrush != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawRing(ringBrush),
            )
        }
        val ringed = accentRing || ringBrush != null
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    // 有环时图片内缩到环内侧（4.5dp），环才不会被图压住；
                    // 顺手 clip 成圆：部分调用点（会话列表 / 聊天顶栏）没有在外面套 clip
                    .padding(if (ringed) 4.5.dp else 1.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            val base = emojiMaxScale.coerceAtLeast(0.01f)
            Text(
                emoji,
                // 按"基准 × 放大上限"渲染，layer 只做 ≤1 的缩小（v0.56.11）：
                // 最大态 = scale 1.0 清晰落定，落定态 = 原字号大小，全程不放大位图字形
                fontSize = (size.value * 0.42f * base).sp,
                modifier = Modifier.graphicsLayer {
                    scaleX = emojiScaleX / base
                    scaleY = emojiScaleY / base
                },
            )
        }
    }
}

/** 按目标像素采样解码本机图片（降内存）；任何失败返回 null，调用方回退 emoji */
private fun decodeSampledBitmap(path: String, targetPx: Int): android.graphics.Bitmap? {
    if (targetPx <= 0) return null
    return runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
            sample *= 2
        }
        android.graphics.BitmapFactory.decodeFile(
            path,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }.getOrNull()
}

/** 渐变环（头像点缀：英雄渐变 / 人设专属品牌色） */
/**
 * 头像**外圈**的品牌渐变环（v0.53.4 改成贴最外圈）。
 *
 * 以前的画法：环画在距边缘 2~3dp 的地方，而自定义图片内缩 3dp——
 * 图片正好把环压住，看起来"环在头像里面"，用了自定义图更是几乎看不见。
 * 现在环贴最外圈（外缘距边 1.5dp），内容再内缩到环内侧，环一定露在头像外面。
 */
// v0.93.0：改成 internal —— 个人档案那张「我」的圆脸也要套同一款环（`MeAvatar`），
// 画法必须和这里一模一样，不能另写一份（另写一份早晚会长歪）。
internal fun Modifier.drawRing(brush: androidx.compose.ui.graphics.Brush): Modifier = this.then(
    Modifier.drawBehind {
        drawCircle(
            brush = brush,
            radius = (size.minDimension - 5.dp.toPx()) / 2,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
        )
    },
)

/** 顶栏文字按钮（如"编辑"）：纯黑文字 + 按压缩放 */
@Composable
fun NeuTextButton(text: String, onClick: () -> Unit, color: Color = NeuAccent) {
    Box(
        Modifier
            .height(40.dp)
            .neuRowPress(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.14).sp,
            color = color,
        )
    }
}

/** 次级说明文字 */
@Composable
fun NeuMuted(modifier: Modifier = Modifier, text: String, fontSize: Float = 12f) {
    Text(
        text,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.5f).sp,
        color = MutedText,
        modifier = modifier,
    )
}
