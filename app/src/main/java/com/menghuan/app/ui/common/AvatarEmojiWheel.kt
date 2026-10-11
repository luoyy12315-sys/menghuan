package com.menghuan.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 可循环滚动的 emoji 头像滚轮（v0.80.0 从「编辑人设」抽出来，两处共用一份）。
 *
 * ## 机制（编辑人设那套，用户点名要"一样"）
 * 头像框是一个**放大镜**：一排 emoji 从框底下横向穿过，**离框心最近的那一格就是选中的**；
 * 越靠近框心越大越清晰（0.64→1.0，layer 只缩小不放大 —— emoji 是位图字形，放大必糊）；
 * 落进框位的那一个会从放大态被**压回**框里（横向压得比纵向多），弹簧带过冲。
 * 每跨一格轻震一下（棘轮手感），点某一格它就自己滚回框位。
 *
 * ## 两个调用点只差几何
 * | | 编辑人设 | 个人档案 |
 * |---|---|---|
 * | 头像框 | 56dp，**靠左** | 88dp，**居中** |
 * | emoji 排 | 只画在框**右缘以外**（圆框盖不住四角，不裁会从角上漏出来） | 铺满整行，框的**左右两侧各露一截** |
 * | 环 | personaBrush 渐变环 | 同一个 personaBrush 渐变环（v0.93.0 起，取专属 id） |
 * | 落盘 | 只改草稿，由页面「保存」统一提交 | 改完即时落盘 |
 *
 * 差异全部收在参数里（`frameSize` / `cellSize` / `bothSides` / `ringBrush` / `commitInitialAnchor`），
 * 交互与动效只有这一份实现 —— 以后调手感，两处一起变，不会再各说各话。
 *
 * ## `commitInitialAnchor` 为什么要有
 * 首次定位会把滚轮对齐到"当前正在用的那个 emoji"，这一次布局反馈**不算用户选择**：
 * - 编辑人设：要它（新角色空 emoji 会被落成第一个预设，保存下来是个正经头像）；
 * - 个人档案：**不能要**（用户从没选过头像，框里本该是「我」，一进页面就被悄悄改成某个 emoji
 *   是实实在在的 bug 观感）。
 */
@Composable
fun AvatarEmojiWheel(
    /** 当前选中的 emoji（空 = 还没选过，框里显示 [placeholder]） */
    emoji: String,
    /** 头像图片路径；非空时框里显示图（**图片优先**，与 Persona 同一套口径） */
    imagePath: String,
    /** 滚到框里的那一格变了（初始定位要不要上报，见 [commitInitialAnchor]） */
    onEmojiChange: (String) -> Unit,
    /** 点头像框（调用方通常在这里拉起选图器） */
    onFrameClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: AvatarWheelState = rememberAvatarWheelState(),
    frameSize: Dp = 56.dp,
    cellSize: Dp = 44.dp,
    /** 从"最小"到"最大"的距离（≈1.2 个格子），越小焦点变化越陡 */
    lensSpan: Dp = 52.dp,
    emojiFontSize: TextUnit = 27.sp,
    /** false = 只画在框右缘以外（框靠左）；true = 铺满整行、框左右各露一截（框居中） */
    bothSides: Boolean = false,
    ringBrush: Brush? = null,
    /** 没选过 emoji 时框里显示的字（编辑人设是 🙂，档案是「我」） */
    placeholder: String = "🙂",
    /**
     * **完全没设置过**（emoji 与图片都空）时框里画什么。默认走 [NeuAvatar] 的白面 + [placeholder]；
     * 个人档案传黑底白「我」那张（与聊天页 / 动态页里"我"那张脸一致，也是手册里写明的样子）。
     */
    emptyFrame: (@Composable () -> Unit)? = null,
    /** 初始定位要不要上报给 [onEmojiChange]（见类注释） */
    commitInitialAnchor: Boolean = true,
) {
    val listState = rememberLazyListState()
    // 头像框的中心（root 坐标）：只给每格算"离框心多远 → 放大多少"用（focus）
    var anchorX by remember { mutableFloatStateOf(Float.NaN) }
    // 滚轮那一排自己的宽度（px）。⚠️ 取 **未变换的布局尺寸**，不取 root 坐标：见 [anchorInRowPx]
    var rowWidthPx by remember { mutableFloatStateOf(Float.NaN) }
    val density = LocalDensity.current
    val lensSpanPx = with(density) { lensSpan.toPx() }
    val cellPx = with(density) { cellSize.toPx() }
    val frameSizePx = with(density) { frameSize.toPx() }
    var anchorReady by remember { mutableStateOf(false) }
    // 滚轮棘轮震动的去重标记：上一次 tick 过的格子 raw index（null = 还没滚过，初始定位不震）
    var lastTicked by remember { mutableStateOf<Int?>(null) }
    /**
     * 我们**自己上报过、还没在 [emoji] 参数里回来的那些值**（滚动比落盘快时会攒下几个）。
     *
     * 用它把"自己滚出来的变化"与"外部换了个值"区分开：前者原样放行（否则每滚一格都被
     * `scrollToItem` 拽回同一格，动画根本走不完），后者才值得重新定位。
     * 改集合不触发重组 —— 只在下面的 LaunchedEffect 里读，正合适。
     */
    val selfReported = remember { mutableSetOf<String>() }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    /**
     * 框心在**滚轮自己那一排的坐标系**里距左缘多远（px）。
     *
     * ⚠️ 必须是"布局量"，不能写成 `框心 − 排左缘` 的 root 坐标相减：`boundsInRoot()`
     * 报的是**含祖先变换**的坐标，而退场转场带 `scaleOut(0.96)`（MainActivity 的
     * `pop + scalePop`）——整页一缩，框心到排左缘的距离跟着被压缩，列表却一格没滚，
     * 于是"离框心最近的那格"凭空挪了一格：**退出页面莫名震一下**，档案页还会顺手
     * 把头像存成旁边那个（它是即时落盘的）。v0.93.0。
     *
     * 框居中 = 半排宽；框靠左 = 半个框宽。两者都只跟布局有关，任何祖先变换都影响不到。
     */
    fun anchorInRowPx(): Float = if (bothSides) rowWidthPx / 2f else frameSizePx / 2f

    /** 此刻离框心最近的可见格子（选中判定与"保存兜底"用的是同一套算法） */
    fun indexAtAnchor(): Int? {
        val anchor = anchorInRowPx()
        if (anchor.isNaN()) return null
        return listState.layoutInfo.visibleItemsInfo
            .minByOrNull { abs(it.offset + it.size / 2f - anchor) }
            ?.index
    }

    fun emojiAtAnchor(): String? =
        indexAtAnchor()?.let { AvatarWheel.PRESETS.getOrNull(it % AvatarWheel.PRESETS.size) }

    /**
     * 让第 i 格的**中心正好落在框心**所需的 `scrollToItem` 偏移（px）。
     *
     * ⚠️ `scrollToItem(i, 0)` 落的是**视口最左边**，不是框心（v0.81.0 那行"框心上停的是第 4 个
     * 预设、不是第 1 个"就是这么来的）。所以点选与初始定位必须走**同一个**偏移：
     * 只有初始定位补了这个差值、点选却写死 0 —— 点某一格，滚到框心的其实是另一格，
     * 于是"点的是这个、存下来是那个"。v0.93.0 修的就是它。
     */
    fun alignOffsetPx(): Int {
        val anchor = anchorInRowPx()
        if (anchor.isNaN()) return 0
        return (cellPx / 2f - anchor).roundToInt()
    }

    // 暴露给调用方（编辑人设保存时兜底重算一次："滚到框上就点保存"也不丢）
    SideEffect { state.anchorLookup = { emojiAtAnchor() } }

    // ⚠️ v0.93.0：key 是 [emoji] 而**不是 Unit**。个人档案的头像是从 DataStore 读的，
    // 首帧还是空串，等真正的值到了必须**再定位一次**——不然滚轮停在第一个预设、
    // 框里画的却是库里存的那张脸（"重进页面看到的和选的不一样"就是这么来的）。
    // 自己滚出来的那次由 [selfReported] 认领，不会把正在进行的滚动动画拽回去。
    LaunchedEffect(emoji) {
        val key = emoji.trim()
        // 我们刚上报过这个值 → 是用户滚出来的，滚轮本来就在它上面，不许再动它
        if (selfReported.remove(key)) return@LaunchedEffect
        selfReported.clear()
        // 重新定位期间**不上报**：中途扫过的那些格子不是用户选的
        anchorReady = false
        lastTicked = null
        // 起点落在循环列表正中，并且对齐到当前正在用的那个 emoji
        val offset = AvatarWheel.PRESETS.indexOf(key).takeIf { it >= 0 } ?: 0
        val target = AvatarWheel.LOOP_START + offset
        listState.scrollToItem(target, alignOffsetPx())
        // ⚠️ v0.81.0：**初始对齐不能只靠 `scrollToItem`** —— 它的对齐基准跟视口宽度有关，
        // 框居中（bothSides）时能差出好几格（实测：框心上停的是第 4 个预设，不是第 1 个），
        // 那"框位上的 emoji"从一开始就是错的。人设那边框窄（56dp）只差 6dp，所以一直没暴露。
        // 做法：落定后实测一次这一格中心的实际位置，把差值补给 scrollOffset。
        withFrameNanos { }
        withFrameNanos { }
        val anchor = anchorInRowPx()
        if (!anchor.isNaN()) {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (info != null) {
                // 正 offset = 往前滚 = 这一格往左跑，所以"现在偏右多少"就补多少
                val delta = (info.offset + info.size / 2f) - anchor
                if (abs(delta) > 1f) {
                    listState.scrollToItem(target, scrollOffset = delta.toInt())
                }
            }
        }
        // 再等布局落定：`scrollToItem` 返回时首帧的 visibleItemsInfo 可能还停在滚动之前，
        // 那时发出的"锚点是第几格"是错的，紧跟着的那一发才会被当成"用户拨了一格"
        // （档案页是即时落盘的，实测不管的话一进页面就给人写上一个表情）。
        while (listState.isScrollInProgress) withFrameNanos { }
        withFrameNanos { }
        withFrameNanos { }
        anchorReady = true
    }

    LaunchedEffect(listState) {
        // "框上换了一格"只有在列表**真的滚过**时才算数：转场缩放、insets、键盘这类
        // 整页位移/缩放都会把量出来的框心带偏（见 [anchorInRowPx]）——那不是用户拨的，
        // 不震，更不能拿去落盘（档案页是即时落盘的，震一下 = 头像被改成旁边那个）。
        var lastScroll: Pair<Int, Int>? = null
        snapshotFlow {
            if (!anchorReady) return@snapshotFlow null
            indexAtAnchor()
        }.filterNotNull().collect { index ->
            val scroll = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            // null = 重新定位后的第一发，照旧认；之后"滚动位置一格没变"的一律丢掉
            if (lastScroll != null && scroll == lastScroll) return@collect
            lastScroll = scroll
            val first = lastTicked == null
            // 每变一格轻微震一下（TextHandleMove 是最轻的一档）——像真的在拨一个滚轮。
            // 首次发射（初始定位）只记录不震；点选自动滚过去时也会逐格 tick，一样自然。
            if (!first && index != lastTicked) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            lastTicked = index
            if (first && !commitInitialAnchor) return@collect
            AvatarWheel.PRESETS.getOrNull(index % AvatarWheel.PRESETS.size)?.let { picked ->
                // 先记账再上报：这个值回头会在 [emoji] 参数里回来，届时 [LaunchedEffect(emoji)]
                // 认出是自己人、直接返回 —— 否则每滚一格都被 `scrollToItem` 拽回同一起点，
                // 点选的滚动动画根本走不完（点哪个都停在原地）。
                selfReported.add(picked)
                onEmojiChange(picked)
            }
        }
    }

    // 框位上的那个被"挤进框里"：0 = 刚被塞进来（放大、横向更宽），1 = 落定（原大小）
    val squeeze = remember { Animatable(1f) }
    LaunchedEffect(emoji) {
        squeeze.snapTo(0f)
        squeeze.animateTo(
            1f,
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }

    Box(modifier.fillMaxWidth().height(frameSize)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 让"第一格的中心"正好落在框心上：框靠左时 = (框 - 格)/2；框居中时 = (整行 - 格)/2。
            // 静止时选中的那个恰好 focus = 1，点选滚回框位用的也是这个偏移。
            val startPad = if (bothSides) (maxWidth - cellSize) / 2 else (frameSize - cellSize) / 2
            LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // ⚠️ 取布局尺寸、不取 root 坐标：见 [anchorInRowPx]
                    .onGloballyPositioned { rowWidthPx = it.size.width.toFloat() }
                    .drawWithContent {
                        if (bothSides) {
                            // 框居中：左右两侧各露一截，**整块框都不画**（只裁圆是挡不住圆外那四个角的
                            // —— 编辑人设那边踩过同一个坑）
                            val fs = frameSize.toPx()
                            val left = (size.width - fs) / 2f
                            clipRect(right = left) { this@drawWithContent.drawContent() }
                            clipRect(left = left + fs) { this@drawWithContent.drawContent() }
                        } else {
                            // 框靠左：只画右缘以外（裁剪只管画，锚点/选中照旧）
                            clipRect(left = frameSize.toPx()) { this@drawWithContent.drawContent() }
                        }
                    },
                contentPadding = PaddingValues(start = startPad, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(Int.MAX_VALUE) { i ->
                    val e = AvatarWheel.PRESETS[i % AvatarWheel.PRESETS.size]
                    // focus：1 = 正好滑进头像框位，0 = 已经滑到远处
                    var focus by remember { mutableFloatStateOf(0f) }
                    Box(
                        Modifier
                            .size(cellSize)
                            .onGloballyPositioned { c ->
                                if (anchorX.isNaN()) return@onGloballyPositioned
                                val itemCenter = c.boundsInRoot().center.x
                                focus = (1f - abs(itemCenter - anchorX) / lensSpanPx).coerceIn(0f, 1f)
                            }
                            // 基准字号就是最大态，layer 只缩小不放大 —— 框位上那个以 scale=1.0 清晰落定
                            .graphicsLayer {
                                val s = 0.64f + 0.36f * focus
                                scaleX = s
                                scaleY = s
                                alpha = 0.45f + (1f - 0.45f) * focus
                            }
                            // 点选：点哪个就把哪个弹回框位（无 ripple，与输入区同一约定）
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                // 与初始定位同一个对齐偏移：点哪个，哪个就**停在框心里**
                                scope.launch { listState.animateScrollToItem(i, alignOffsetPx()) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(e, fontSize = emojiFontSize)
                    }
                }
            }
        }
        val frameModifier = Modifier
            .align(if (bothSides) Alignment.Center else Alignment.CenterStart)
            // 焦点锚点 = **头像框自己的中心**（"焦点就是头像框里面的头像"）
            .onGloballyPositioned { anchorX = it.boundsInRoot().center.x }
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onFrameClick() }
        if (emoji.trim().isBlank() && imagePath.isBlank() && emptyFrame != null) {
            Box(frameModifier) { emptyFrame() }
        } else {
            NeuAvatar(
                emoji = emoji.trim().ifBlank { placeholder },
                size = frameSize,
                ringBrush = ringBrush,
                imagePath = imagePath,
                // 挤压：0 = 刚被塞进来（放大、横向更宽），1 = 落定（原大小）。
                // emojiMaxScale=1.42 让框内 emoji 按**放大态**字号渲染、layer 只做 ≤1 的缩小
                // ——"挤进去"的动效不变，但位图字形不再被 graphicsLayer 放大拉糊。
                emojiMaxScale = 1.42f,
                emojiScaleX = 1.42f - 0.42f * squeeze.value,
                emojiScaleY = 1.16f - 0.16f * squeeze.value,
                modifier = frameModifier,
            )
        }
    }
}

/**
 * 滚轮用到的预设 emoji（v0.56.8 定稿：笑脸 / 人物 / 职业 / 角色幻想生物四组）。
 * 放在共享组件里 —— 编辑人设与个人档案从此是**同一份名单**。
 */
object AvatarWheel {
    val PRESETS: List<String> = listOf(
        "🙂", "😊", "😄", "😎", "🤗", "😌",
        "👦", "👧", "🧑", "👨", "👩", "🧓", "👴", "👵",
        "🧑‍🎓", "👨‍🏫", "👩‍🏫", "👨‍⚕️", "👩‍⚕️", "👨‍🍳", "👩‍🍳",
        "🧑‍💻", "👮", "👷", "🧘", "🏃",
        // ↓ v0.56.8 第三批：角色 / 幻想 / 生物
        "👽", "👾", "🤖", "👻", "👹", "👺", "💀", "🤡", "👼", "🧙",
        "🧚", "🧜", "🧞", "🧟", "🥷", "🦸", "🦹", "🕵️", "🧝", "🧛",
        "👨‍🚀", "👩‍🚀", "🧑‍🎤", "🧑‍🎨", "🤴", "👸", "🦊", "🐱", "🐼", "🦄",
    )

    /**
     * 循环滚动的起点：取 `Int.MAX_VALUE / 2` 附近、且是 [PRESETS] 长度整数倍的那一格
     * —— 这样 `index % size == 0`，往前滑再远也不会滑出界（两个方向都能一直滑）。
     */
    val LOOP_START: Int = (Int.MAX_VALUE / 2 / PRESETS.size) * PRESETS.size
}

/** 滚轮对外暴露的句柄：只为了保存时能问一句"此刻框上是哪个 emoji"。 */
class AvatarWheelState {
    internal var anchorLookup: (() -> String?)? = null

    /** 锚点还没测出来（首帧）时返回 null，调用方回落到自己的草稿值。 */
    fun emojiAtAnchor(): String? = anchorLookup?.invoke()
}

@Composable
fun rememberAvatarWheelState(): AvatarWheelState = remember { AvatarWheelState() }

/**
 * 头像下面那颗**细线胶囊**（v0.60.1 用户点名，v0.80.0 抽到共享）。
 *
 * 走全应用统一的"白面细线 + 无涟漪缩放"语言。**宽度与头像框同宽**（编辑人设 56dp、
 * 个人档案 88dp），于是它自动和头像左右对齐。10sp / 22dp 高。
 */
@Composable
fun AvatarPill(text: String, danger: Boolean, width: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .width(width)
            .height(22.dp)
            .neuRowPress(onClick)
            .neuSurface(999.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            color = if (danger) NeuDanger else TextSecondary,
        )
    }
}
