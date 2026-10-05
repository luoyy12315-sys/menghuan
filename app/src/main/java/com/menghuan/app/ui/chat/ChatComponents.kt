package com.menghuan.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.menghuan.app.data.ApiProfile
import com.menghuan.app.data.ImageGenerationClient
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import java.io.File

/** 「尚未落盘」占位路径：ChatViewModel 与气泡之间约定的加载态标记 */
const val IMAGE_PENDING = ImageGenerationClient.PENDING_PATH

/**
 * 缩略图跨条目缓存（条数制 LruCache，32 张）：
 * remember(path) 只在条目存续期间有效——滚动离开视口后条目被回收，回来又得重新读盘解码，
 * 长会话上下翻历史就是在这里一顶一顿。加了跨条目缓存后，看过一遍的图二次进入视口零 IO。
 */
private val chatThumbCache = object : android.util.LruCache<String, android.graphics.Bitmap>(32) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = 1
}

/** 按目标宽度降采样解码本机图片（纯函数，不在主线程调用方处的 @Composable 里直接暴露 IO 细节） */
private fun decodeLocalBitmap(path: String, targetPx: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
    return android.graphics.BitmapFactory.decodeFile(
        path,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

/** 加载本机图片（带记忆缓存 + 跨条目 LruCache，列表滚动不重复解码）；失败返回 null */
@Composable
fun rememberLocalBitmap(path: String): ImageBitmap? {
    val density = LocalDensity.current
    return remember(path, density) {
        if (path.isBlank() || path == IMAGE_PENDING) null
        else runCatching {
            val cached = chatThumbCache.get(path)
            if (cached != null) {
                cached.asImageBitmap()
            } else {
                val target = with(density) { 320.dp.roundToPx() }
                val bmp = decodeLocalBitmap(path, target)
                if (bmp != null) chatThumbCache.put(path, bmp)
                bmp?.asImageBitmap()
            }
        }.getOrNull()
    }
}

/** 全屏查看用的高清解码（~1080px，缩放后不糊；一次性解码不入缓存，避免多张大图撑爆内存） */
@Composable
private fun rememberViewerBitmap(path: String): ImageBitmap? {
    return remember(path) {
        if (path.isBlank() || path == IMAGE_PENDING) null
        else runCatching { decodeLocalBitmap(path, 1080)?.asImageBitmap() }.getOrNull()
    }
}

/**
 * 全屏图片查看器（v0.39.0）：黑底 + 捏合缩放（1x–5x）+ 拖动平移 + 双击放大/还原，
 * 单击任意处关闭。
 */
@Composable
fun ImageViewerOverlay(path: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val bitmap = rememberViewerBitmap(path)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() },
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val img = bitmap
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = "查看图片",
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("图片读取失败", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

/**
 * 图片气泡：AI 生图结果 / 用户发来的图，落盘到本机后渲染在这里。
 * [bitmap] 为 null 表示还在生成（骨架占位）或生成失败（给一句可行动的话）。
 *
 * [fromUser] 决定靠边（v0.42.3）：用户发的图必须靠右（自己那侧），
 * 只有 AI 生图才靠左。此前无脑左对齐，用户发图会跑到 AI 那一侧。
 */
@Composable
fun ImageBubble(
    prompt: String,
    bitmap: ImageBitmap?,
    maxWidth: Dp,
    failed: Boolean,
    onClick: (() -> Unit)? = null,
    fromUser: Boolean = false,
) {
    Column(
        Modifier.widthIn(max = maxWidth),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    }
                )
                .clip(RoundedCornerShape(18.dp))
                .neuSurface(18.dp, color = NeuSurface, elevation = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = prompt.ifBlank { "AI 生成的图片" },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    contentScale = ContentScale.Crop,
                )
            } else if (failed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        MIcons.ImageIcon,
                        contentDescription = null,
                        tint = NeuDanger,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("图片生成失败", fontSize = 13.sp, color = TextSecondary)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        MIcons.ImageIcon,
                        contentDescription = null,
                        tint = MutedText,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("正在生成图片…", fontSize = 13.sp, color = MutedText)
                }
            }
        }
        // 提示词作为图注（事实信息：这是模型收到的画面描述）
        if (prompt.isNotBlank() && bitmap != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                prompt,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MutedText,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * 自定义表情气泡（v0.45.0）：与图片气泡区分——**固定小尺寸、无边框、不可点开**。
 * 用户明确要求"发我的表情包的时候应该跟图片不一样，显示区域小点，并且不能点开"。
 *
 * 尺寸固定 [STICKER_BUBBLE_SIZE]（120dp），不随版心放大；透明底直接铺图（ContentScale.Fit
 * 保留表情原本比例，不做 Crop 裁切），也不挂点击放大——表情就是一眼看完的东西。
 */
val STICKER_BUBBLE_SIZE = 120.dp

@Composable
fun StickerBubble(
    bitmap: ImageBitmap?,
    fromUser: Boolean,
) {
    Column(
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "表情",
                modifier = Modifier
                    .size(STICKER_BUBBLE_SIZE)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                Modifier
                    .size(STICKER_BUBBLE_SIZE)
                    .clip(RoundedCornerShape(12.dp))
                    .neuSurface(12.dp, inset = true),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MIcons.ImageIcon, contentDescription = null, tint = MutedText, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * 生图进行中的动画卡（v0.39.0）：小方卡里一团墨色粒子缓慢环绕明灭，
 * 像"她正在作画"的呼吸感；不再放"正在生成图片…"的文字解释。
 * 动画期间挂在消息列表底部，`isGeneratingImage` 归位后由真图消息接管。
 */
@Composable
fun ImageGeneratingCard(
    prompt: String,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.widthIn(max = maxWidth),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.62f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(18.dp))
                // 凹陷灰面板（无边框无阴影）：白卡 + 发丝线描边在白底会话里
                // 左缘会呈现为一条突兀的竖直灰线（用户反馈），凹陷面与输入框同语言
                .neuSurface(18.dp, inset = true),
        ) {
            ParticleField(Modifier.matchParentSize())
        }
        if (prompt.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                prompt,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MutedText,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

private data class DotSeed(
    val x: Float,          // 基准横向位置（0-1）
    val y: Float,          // 基准纵向位置（0-1）
    val radiusDp: Float,   // 点半径 dp：大 = 近 = 深
    val depth: Float,      // 0 远 1 近，决定透明度深浅
    val floatAmpDp: Float, // 浮动幅度 dp
    val floatCycles: Int,  // 每循环浮动次数（整数保证循环无缝）
    val phase: Float,      // 相位错开，波次才不齐舞
)

/**
 * 波点浮动的粒子场（v0.40.0，用户指定样式）：深浅两层的圆点散布在卡面上，
 * 各自以不同速度、不同相位上下浮沉，像水面上的波点。
 * 深浅层次 = 近层点大且实、远层点小且淡；所有浮动都是"每循环整数次"，
 * 线性时间 0→1 循环时首尾状态完全一致，无限播放无接缝。
 */
@Composable
private fun ParticleField(modifier: Modifier = Modifier) {
    val ink = TextPrimary
    val seeds = remember {
        val rnd = java.util.Random(20261003L)
        List(24) {
            val near = rnd.nextFloat() < 0.4f // 四成是近景点
            DotSeed(
                x = 0.12f + rnd.nextFloat() * 0.76f,
                y = 0.14f + rnd.nextFloat() * 0.72f,
                radiusDp = if (near) 3.2f + rnd.nextFloat() * 2.6f else 1.4f + rnd.nextFloat() * 1.8f,
                depth = if (near) 0.75f + rnd.nextFloat() * 0.25f else 0.12f + rnd.nextFloat() * 0.32f,
                floatAmpDp = 3f + rnd.nextFloat() * 5f,
                floatCycles = 1 + rnd.nextInt(3),
                phase = rnd.nextFloat(),
            )
        }
    }
    val t by rememberInfiniteTransition(label = "particles").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "pt",
    )
    Canvas(modifier) {
        val twoPi = (2 * Math.PI).toFloat()
        // 远层先画、近层后画：近点更深更大，自然盖出层次
        seeds.sortedBy { it.depth }.forEach { s ->
            val floatY = kotlin.math.sin((t * s.floatCycles + s.phase) * twoPi) * s.floatAmpDp.dp.toPx()
            val driftX = kotlin.math.cos((t * s.floatCycles + s.phase) * twoPi) * s.floatAmpDp.dp.toPx() * 0.28f
            val breathe = 0.8f + 0.2f * kotlin.math.sin((t * s.floatCycles + s.phase) * twoPi)
            val alpha = (0.16f + 0.66f * s.depth) * breathe
            drawCircle(
                color = ink.copy(alpha = alpha),
                radius = s.radiusDp.dp.toPx() / 2f * breathe,
                center = Offset(size.width * s.x + driftX, size.height * s.y + floatY),
            )
        }
    }
}

/**
 * 长按头像弹出的「切换 AI 接口」弹层（v0.35.0 多接口；v0.36.4 改为按会话独立）。
 *
 * 选择只作用于**当前会话**：给该会话设一个接口覆盖，不影响其他会话和全局默认。
 * 顶部多一行「跟随全局」用于清除覆盖、回落到设置页里的全局默认接口。
 * 只列已配置的接口 + 当前生效项打勾；未配置时给一句去设置的话（不弹空的列表）。
 */
@Composable
fun ApiSwitchSheet(
    profiles: List<ApiProfile>,
    activeId: String,
    isFollowingGlobal: Boolean,
    globalLabel: String,
    onSelect: (String) -> Unit,
    onFollowGlobal: () -> Unit,
    onDismiss: () -> Unit,
) {
    NeuSheetScaffold(title = "本会话的 AI 接口", onDismiss = onDismiss) {
        Spacer(Modifier.height(12.dp))
        if (profiles.isEmpty()) {
            Text(
                "还没有配置接口，先到「我的 → 设置 → API 配置」添加",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MutedText,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            NeuSheetRow(
                icon = MIcons.Code,
                title = "跟随全局",
                subtitle = "当前全局：$globalLabel",
                selected = isFollowingGlobal,
                onClick = onFollowGlobal,
            )
            Spacer(Modifier.height(8.dp))
            profiles.forEach { profile ->
                NeuSheetRow(
                    icon = MIcons.Code,
                    title = profile.name,
                    subtitle = "${profile.model} · ${profile.baseUrl}",
                    selected = profile.id == activeId,
                    onClick = { onSelect(profile.id) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** 会话预览用：取图片消息的可读摘要（「[图片] 提示词」） */
fun imageMessagePreview(content: String, hasImage: Boolean): String =
    if (hasImage && content.isNotBlank()) "[图片] $content" else "[图片]"

/**
 * 语音条气泡（v0.43.0）：微信式——播放键 + 声波条 + 时长，点击播放/暂停。
 *
 * 宽度按时长递增（3" 短、30"+ 封顶），像微信那样让长语音"看起来就长"，
 * 不用点开就能判断要不要听。
 *
 * **v0.43.1 新增长按「转文字」开关**（用户要求）：
 * 长按语音条弹出一颗菜单，选「转文字」→ 识别结果以**加粗引号**形式显示在语音条
 * **下方**（`transcript` 非空时才显示）；再长按同一条 → 菜单变「取消转文字」，
 * 收起文字。识别文字只给用户看，不参与"是否已发送"的任何逻辑。
 *
 * 状态由父级持有（`showTranscript` 在消息列表层按 message.id 记住），
 * 气泡本身无状态——这样滚动出屏再回来，展开状态不会丢。
 */
@Composable
fun VoiceBubble(
    durationMs: Long,
    maxWidth: Dp,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit = {},
    isUser: Boolean = true,
) {
    val sec = (durationMs / 1000).coerceAtLeast(1)
    val ratio = (0.30f + (sec.coerceAtMost(30) - 1) / 29f * 0.26f).coerceIn(0.30f, 0.56f)
    // AI 侧走文字气泡那套「面色 + 细线」，用户侧保持强调色药丸——
    // 否则整屏只有 AI 语音条是黑块，跟 AI 文字气泡不是一家人。
    val ink = if (isUser) AccentInk else TextPrimary
    Row(
        Modifier
            .widthIn(max = maxWidth * ratio, min = maxWidth * 0.30f)
            .then(
                if (isUser) Modifier.clip(RoundedCornerShape(18.dp)).background(NeuAccent)
                else Modifier.neuSurface(18.dp, color = MaterialTheme.colorScheme.surface, elevation = 2.dp)
            )
            .combinedClickable2(onLongPress = onLongPress, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (isPlaying) MIcons.Pause else MIcons.Play,
            contentDescription = if (isPlaying) "暂停" else "播放",
            tint = ink,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(9.dp))
        // 声波：三条错落竖条，播放时呼吸起伏
        val phase = rememberInfiniteTransition(label = "voiceWave")
        val wave by phase.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(620, easing = LinearEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "voiceWaveAmp",
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf(0.55f, 1f, 0.75f).forEachIndexed { _, h ->
                val amp = if (isPlaying) wave else 0.8f
                Box(
                    Modifier
                        .width(2.5.dp)
                        .height((13.dp * h * amp).coerceAtLeast(4.dp))
                        .clip(RoundedCornerShape(2.dp))
                        .background(ink.copy(alpha = 0.9f)),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            com.menghuan.app.data.VoiceRecorder.formatDuration(durationMs),
            color = ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 文件气泡（v0.43.0）：图标 + 文件名 + 大小；抽到正文时补一句"已读取内容" */
@Composable
fun FileBubble(
    name: String,
    size: Long,
    hasExtractedText: Boolean,
    maxWidth: Dp,
    onLongPress: () -> Unit = {},
) {
    Row(
        Modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(18.dp))
            .background(NeuAccent)
            .combinedClickable2(onLongPress = onLongPress, onClick = {})
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MIcons.FileDoc, contentDescription = null, tint = AccentInk, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                name,
                color = AccentInk,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                buildString {
                    append(com.menghuan.app.data.FileTextExtractor.humanSize(size))
                    if (hasExtractedText) append(" · 已读取内容")
                },
                color = AccentInk.copy(alpha = 0.72f),
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 长按 + 点击（indication 关闭，与项目其它手势入口一致）。
 *
 * v0.54.6：原来是自己用 `composed { pointerInput { detectTapGestures(...) } }` 拼的，
 * 两个毛病 —— ① `composed` 已废弃；② `pointerInput` 的 key 用 lambda，**每次重组都会重启手势检测**，
 * 长按很容易被打断（用户反馈"长按语音条唤不起菜单"）。改用官方 `combinedClickable`。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.combinedClickable2(
    onLongPress: () -> Unit,
    onClick: () -> Unit,
): Modifier = combinedClickable(
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onLongClick = onLongPress,
    onClick = onClick,
)