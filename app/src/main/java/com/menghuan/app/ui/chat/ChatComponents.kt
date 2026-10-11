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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.menghuan.app.ui.common.rememberNeuPressState
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.FaintText
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
 *
 * [modifier]（v0.89.0）：由调用处置入长按菜单的锚点记录 —— 必须挂在**气泡自己这一层**
 * （而不是外层的整行容器），否则菜单箭头会按行边缘对齐、离气泡老远。
 */
@Composable
fun ImageBubble(
    prompt: String,
    bitmap: ImageBitmap?,
    maxWidth: Dp,
    failed: Boolean,
    onClick: (() -> Unit)? = null,
    fromUser: Boolean = false,
    /** 失败态文案（v0.97.0）：失败有两种——生图没出来、或图曾经在但文件已失效，说法不该一样 */
    failedText: String = "图片生成失败",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.widthIn(max = maxWidth),
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
                    Text(failedText, fontSize = 13.sp, color = TextSecondary)
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
    /** v0.89.0：长按菜单锚点（挂气泡自己这一层，见 [ImageBubble] 说明） */
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
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
 *
 * **v0.56.6（用户点名）**：去掉顶部的「跟随全局」那一行 ——
 * "选中哪个模型就使用哪个"：列表里当前生效的那条直接打勾（没单独选过时是全局默认那条），
 * 点任意一条即把这个会话钉在那条上，不再有"回落全局"这个中间态。
 */
@Composable
fun ApiSwitchSheet(
    profiles: List<ApiProfile>,
    activeId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    NeuSheetScaffold(title = "本会话的 AI 接口", onDismiss = onDismiss) {
        Spacer(Modifier.height(12.dp))
        if (profiles.isEmpty()) {
            Text(
                "还没有配置接口，先到「我的 → 设置 → 模型配置」添加",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MutedText,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
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
    /** v0.89.0：长按菜单锚点（挂气泡自己这一层，见 [ImageBubble] 说明） */
    modifier: Modifier = Modifier,
) {
    val sec = (durationMs / 1000).coerceAtLeast(1)
    val ratio = (0.30f + (sec.coerceAtMost(30) - 1) / 29f * 0.26f).coerceIn(0.30f, 0.56f)
    // AI 侧走文字气泡那套「面色 + 细线」，用户侧保持强调色药丸——
    // 否则整屏只有 AI 语音条是黑块，跟 AI 文字气泡不是一家人。
    val ink = if (isUser) AccentInk else TextPrimary
    Row(
        modifier
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
        // v0.55.0：三条声波改 **Canvas 单节点**绘制。
        // 原来是三个 Box、高度绑着动画值 —— 播放期间每一帧都要重新量一遍（布局 + 绘制两趟），
        // 列表里有几条语音条在播就要付出几份。改成一个 Canvas 后只有一个节点、一次绘制。
        VoiceWaveBars(
            amp = if (isPlaying) wave else 0.8f,
            color = ink,
            modifier = Modifier.width(14.dp).height(16.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            com.menghuan.app.data.VoiceRecorder.formatDuration(durationMs),
            color = ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 语音条上的三根声波竖条（v0.43.0 建；v0.55.0 改 Canvas 单节点）。
 *
 * 高度比例沿用原来的 0.55 / 1 / 0.75（错落才像声波），[amp] 是统一振幅系数：
 * 播放中随呼吸动画起伏，静止时钉在 0.8。
 *
 * 画在 Canvas 里而不是用三个 Box，是为了让"播放中"这个持续动画**不产生任何布局** ——
 * 三条一起画、一次绘制，配合外层 `graphicsLayer` 之外的动画都不会拖累列表滚动。
 */
@Composable
private fun VoiceWaveBars(
    amp: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val ink = color
    Canvas(modifier) {
        val ratios = floatArrayOf(0.55f, 1f, 0.75f)
        val w = 2.5.dp.toPx()
        val gap = 3.dp.toPx()
        val unit = w + gap
        val startX = (size.width - (ratios.size * unit - gap)) / 2f
        ratios.forEachIndexed { i, r ->
            val h = (13.dp.toPx() * r * amp).coerceAtLeast(4.dp.toPx()).coerceAtMost(size.height)
            drawRoundRect(
                color = ink.copy(alpha = 0.9f),
                topLeft = Offset(startX + i * unit, (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

/**
 * 语音/视频通话记录卡（v0.49.0 建；**v0.55.0 整卡重绘**；**v0.60 分通话类型**）。
 *
 * 重绘前它只是「📞 + 一行灰字」的小药丸：在消息流里既不醒目，也完全看不出这通电话是什么情况
 * （谁打给谁、打通没有、多久）。现在重画成一张真正的卡片，四层信息各归其位：
 *   ① **左侧圆形徽标** —— 图标本身就区分状态：打通是听筒，没打通是挂断听筒（危险色）；
 *      v0.60 起视频通话走摄像机图标（未接通仍由危险色 + 副题表达）；
 *   ② **标题**「语音通话」/「视频通话」（v0.60 起按类型）+ 一枚方向标记（↑ 呼出 / ↓ 呼入）；
 *   ③ **副题** —— 时长；未接通时走危险色并写「未接通」，而不是显示"0分00秒"；
 *   ④ **右侧箭头** —— 点整卡看这通电话的文字转写。
 *
 * 类型不需要新增字段：`RealtimeCallRepository.saveCall` 早已把「语音通话 / 视频通话」
 * 写进消息内容（v0.58.0），调用方从内容前缀判出来传 [video] 即可。
 * 方向同理：约定「我打给 AI 的落用户侧、AI 打给我的落 AI 侧」，这里读 `isFromUser`。
 * 「未接通」也一样：时长为 0 分 0 秒即代表这通没说上话。
 *
 * 配色继续走黑白灰：呼入/呼出**不靠颜色区分**（那会引入第二套语义色），
 * 靠箭头方向 + 文字；只有"未接通"用 [NeuDanger]，与全应用"危险 = 橘红"一致。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CallRecordCard(
    /** 时长文案（「3分20秒」）；「0分00秒」会被判成未接通 */
    duration: String,
    /** true = 我打出去的（卡片在用户侧）；false = TA 打进来的 */
    outgoing: Boolean,
    /**
     * v0.60：这通是**视频通话**（记录内容写的就是「视频通话 · …」）。
     * 以前标题与图标写死成语音那套，视频通话的记录卡也显示「语音通话」（用户点名）。
     * 徽标图标表达"是什么通话"，未接通仍由危险色 + 副题文案表达。
     */
    video: Boolean = false,
    maxWidth: Dp,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    /** v0.89.0：长按菜单锚点（挂卡片自己这一层，见 [ImageBubble] 说明） */
    modifier: Modifier = Modifier,
) {
    val missed = duration.startsWith("0分00秒")
    val press = rememberNeuPressState()
    Row(
        modifier
            .widthIn(max = maxWidth)
            .scale(press.scale)
            .neuSurface(16.dp, color = NeuSurface, elevation = 2.dp)
            .combinedClickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ① 状态徽标
        Box(
            Modifier
                .size(34.dp)
                .neuSurface(999.dp, color = NeuTrack, inset = true),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                when {
                    video -> MIcons.Video
                    missed -> MIcons.PhoneOff
                    else -> MIcons.Phone
                },
                contentDescription = null,
                tint = if (missed) NeuDanger else TextPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(11.dp))
        // ②③ 标题（含方向标记） + 副题
        Column(Modifier.weight(1f, fill = false)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (video) "视频通话" else "语音通话",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    letterSpacing = (-0.1).sp,
                )
                Spacer(Modifier.width(7.dp))
                Row(
                    Modifier
                        .neuSurface(999.dp, color = NeuTrack, inset = true)
                        .padding(horizontal = 7.dp, vertical = 2.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (outgoing) MIcons.TrendUp else MIcons.TrendDown,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        if (outgoing) "呼出" else "呼入",
                        fontSize = 10.5.sp,
                        color = TextSecondary,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                if (missed) "未接通 · 点开看转写" else "$duration · 点开看转写",
                fontSize = 11.5.sp,
                color = if (missed) NeuDanger else MutedText,
            )
        }
        // ④ 可点提示
        Spacer(Modifier.width(10.dp))
        Icon(
            MIcons.ChevronRight,
            contentDescription = null,
            tint = FaintText,
            modifier = Modifier.size(14.dp),
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
    /** v0.89.0：长按菜单锚点（挂气泡自己这一层，见 [ImageBubble] 说明） */
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
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