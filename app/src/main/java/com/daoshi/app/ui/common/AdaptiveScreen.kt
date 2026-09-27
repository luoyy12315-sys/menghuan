package com.daoshi.app.ui.common

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.NeuBackground
import com.daoshi.app.ui.theme.NeuTrack
import com.daoshi.app.ui.theme.TextPrimary

/** 版心上限：平板 / 折叠屏 / 横屏把内容收窄到 600dp 居中（AGENTS §四） */
val ContentMaxWidth = 600.dp

/**
 * 当前页面**版心宽度**（手机 = 屏幕宽；平板/折叠屏 = 600dp）。
 * 气泡等按比例缩放的元素一律以它为基准，别再用 LocalConfiguration.screenWidthDp。
 */
val LocalContentWidth = staticCompositionLocalOf { 390.dp }

/**
 * 自适应页面容器：**唯一**处理系统栏 insets 的地方（页面内部不要再加 statusBarsPadding）。
 * - 手机（< 600dp）：内容铺满，与设计稿 1:1
 * - 平板 / 折叠屏（>= 600dp）：内容居中收窄到 600dp，两侧留浅灰边槽
 */
@Composable
fun AdaptiveScreen(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= ContentMaxWidth
        val contentWidth = if (wide) ContentMaxWidth else maxWidth
        Box(
            Modifier
                .fillMaxSize()
                .background(if (wide) NeuTrack else MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                // 手势导航条避让：顶级 tab 页由底栏消费该 inset（Scaffold innerPadding
                // 已 consumeWindowInsets），单聊等隐藏底栏的 push 页由此处补上
                .navigationBarsPadding(),
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .then(
                        if (wide) {
                            Modifier.widthIn(max = ContentMaxWidth).align(Alignment.TopCenter)
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
 * 标准顶栏：内容高 56dp，无发丝线（新拟态靠间距分区）。
 * 左右槽位宽度按版心比例自适应（56–76dp），保证标题永远居中。
 */
@Composable
fun NeuTopBar(
    title: String? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
    center: @Composable (() -> Unit)? = null,
) {
    // 84dp = 双 38dp 图标按钮 + 8dp 间距：会话列表右侧两颗按钮正好放下不外溢
    val slot = (LocalContentWidth.current * 0.195f).coerceIn(56.dp, 84.dp)
    Column(Modifier.fillMaxWidth().background(Color.Transparent)) {
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
 * 新拟态头像：凸起圆饼 + emoji。
 * accentRing = 一圈细强调渐变环（聊天气泡页顶栏用）。
 */
@Composable
fun NeuAvatar(emoji: String, size: Dp = 42.dp, accentRing: Boolean = false) {
    Box(
        Modifier
            .size(size)
            .neuSurface(size / 2, color = com.daoshi.app.ui.theme.NeuSurface, elevation = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (accentRing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(2.5.dp)
                    .drawRing(),
            )
        }
        Text(emoji, fontSize = (size.value * 0.42f).sp)
    }
}

/** 细渐变环（头像点缀） */
private fun Modifier.drawRing(): Modifier = this.then(
    Modifier.drawBehind {
        drawCircle(
            brush = com.daoshi.app.ui.theme.AccentBrush,
            radius = (size.minDimension - 3.dp.toPx()) / 2,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
        )
    },
)

/** 顶栏文字按钮（如"编辑"）：强调色文字 + 按压缩放 */
@Composable
fun NeuTextButton(text: String, onClick: () -> Unit, color: Color = NeuAccent) {
    Box(
        Modifier
            .height(40.dp)
            .neuRowPress(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

/** 次级说明文字 */
@Composable
fun NeuMuted(modifier: Modifier = Modifier, text: String, fontSize: Float = 12f) {
    Text(text, fontSize = fontSize.sp, color = MutedText, modifier = modifier)
}
