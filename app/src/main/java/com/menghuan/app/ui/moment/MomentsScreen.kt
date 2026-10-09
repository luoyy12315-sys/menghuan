package com.menghuan.app.ui.moment

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.data.MomentMath
import com.menghuan.app.data.model.MomentEntry
import com.menghuan.app.data.model.Persona
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuMenu
import com.menghuan.app.ui.common.NeuMenuItem
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.figmaSelection
import com.menghuan.app.ui.common.neuRowPress
import com.menghuan.app.ui.common.raisedCard
import com.menghuan.app.ui.common.EaseOutQuart
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.BrandSalmon
import com.menghuan.app.ui.theme.CanvasGray
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import com.menghuan.app.ui.theme.personaBrush
import kotlinx.coroutines.launch

// ============================================================
// 时间线页
// ============================================================

/**
 * 列表**重排**的位移规格：发新动态 / 删动态时，旧卡片要被推开而不是瞬移。
 *
 * 命名hp表里躺平的 dp 值读不出意图，所以和任务页一样拎成具名常量。
 * ⚠️ 用 `animateItem(placementSpec = …)`：老 API `animateItemPlacement` 在本库已废弃，
 * 继续用只是每次编译白拿一行 warning（这是全工程最后一处用法）。
 */
private val MomentPlacementSpring = spring<IntOffset>(
    stiffness = Spring.StiffnessMediumLow,
    dampingRatio = Spring.DampingRatioNoBouncy,
)

/**
 * 动态时间线（v0.76.0，卡片式）。
 *
 * ## 版面
 * 这一页是从 v0.42.1「全应用去阴影」里**唯一的例外**（用户 2026-10-09 点名）：
 * 时间线是"一张张贴出来的卡片"，每张带柔和投影浮在浅灰画布上，
 * **卡片之间不再画发丝线** —— 上一版条目之间一条 0.7dp 的线、外面再一圈 1dp 的框，
 * 层次全靠那几条线撑着，线一多就变成了表格。现在层次来自三个台阶：
 * 画布灰 → 白卡 → 卡内留白分区。
 *
 * ## 一张卡里有什么
 * 抬头（头像 + 名字 + 时间）→ 正文 →（配图九宫格）→（位置）→ 动作行。
 * 整张卡可点 = 进详情看回复；**长按 = 震一下 + 弹菜单**，菜单里点「删除」才真删。
 *
 * ## 发帖入口仍是右下角悬浮圆钮
 * 顶栏右侧那格在窄屏只有 56–84dp，和标题挤；推特本人也是右下悬浮 compose，
 * 拇指的天然落点，滚动时也一直够得着。
 *
 * ## 三态（照抄会话列表 v0.43.1 的教训）
 * 没加载完 → 不显示任何"空"的东西；加载完且真的一条没有 → 虚线框一个动作；
 * 有内容 → 时间线。**顺序错了就是"冷启动先闪一下发帖引导"**，那个 bug 修过一次。
 *
 * @param visible 这一页当前是不是**停下来**的那一页（MainActivity 传 `settledPage == 2`）。
 *   入场动画必须由它驱动：四个 tab 是同时被组合着的（Pager），靠"被组合"触发的动画
 *   在这里必然失效 —— 动画在你划过来的路上早就放完了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsScreen(
    visible: Boolean,
    onOpenThread: (Long) -> Unit = {},
    onOpenChat: (Long) -> Unit = {},
    vm: MomentsViewModel = viewModel(factory = MomentsViewModel.Factory),
) {
    val page by vm.page.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val round by vm.round.collectAsState()
    val places by vm.placeSuggestions.collectAsState()
    val message by vm.message.collectAsState()
    var composing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(CanvasGray)) {
        NeuTopBar(title = "动态")

        Box(Modifier.weight(1f)) {
            when {
                !page.loaded -> Box(Modifier.fillMaxSize())
                page.threads.isEmpty() -> EmptyFeedHint { composing = true }
                else -> MomentsTimeline(
                    page = page,
                    round = round,
                    visible = visible,
                    refreshing = refreshing,
                    onRefresh = vm::refresh,
                    onLoadMore = vm::loadMore,
                    onOpen = onOpenThread,
                    onOpenAuthor = { id -> if (id != 0L) onOpenChat(id) },
                    onLike = vm::toggleLike,
                    onDelete = vm::delete,
                )
            }

            ComposeFab(
                modifier = Modifier.align(Alignment.BottomEnd),
                onClick = { composing = true },
            )
        }
    }

    if (composing) {
        MomentComposeSheet(
            places = places,
            onDismiss = { composing = false },
            onSubmit = { text, images, location ->
                vm.post(text, images, location)
                composing = false
            },
        )
    }

    FeedToast(message, vm::clearMessage)
}

/**
 * 列表 + 下拉刷新 + 上拉加载。
 *
 * ## 下拉 / 上拉分别 BST 由谁负责
 * - 下拉：[PullToRefreshBox]（Material3 官方实现，与 Pager 的横向手势天然互斥），
 *   **但指示器是我们自己的**（[RefreshBadge]）—— 官方那颗彩圆 + 白箭头与这套黑白语言不合；
 * - 上拉：不用第三方分页库，自己盯 `LazyListState` 的最后可见下标（这只有十几行代码，
 *   引一个库反而说不清它到底什么时候去多加载一页）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MomentsTimeline(
    page: MomentPage,
    round: Int,
    visible: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenAuthor: (Long) -> Unit,
    onLike: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    // 滑到倒数第三条时就伸手要下一页：**不要等到真的到底**，
    // 到底才请求 = 用户一定看得见一次停顿。
    val nearTail by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearTail, page.hasMore, page.loadingMore) {
        if (nearTail && page.hasMore && !page.loadingMore) onLoadMore()
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize().testTag("moments_list"),
        state = pullState,
        indicator = {
            // v0.76.0：badge 只在「往下拉了」或「正在刷」时出现 ——
            // 之前它无条件组合，静止时也以半透明常驻在第一张卡上（截图逮住过）。
            RefreshBadge(
                modifier = Modifier.align(Alignment.TopCenter),
                refreshing = refreshing,
                visible = refreshing || pullState.distanceFraction > 0f,
            )
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            // start/end 而不是 horizontal：Kotlin 的命名实参只能落在同一个重载上，
            // 而 PaddingValues 的「horizontal」与「top」是两个不同重载的参数
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(page.threads, key = { _, thread -> thread.post.id }) { index, thread ->
                MomentCard(
                    thread = thread,
                    author = page.authors[thread.post.personaId],
                    // 入场错峰只给首屏那几条（>8 的由 momentEnter 自己跳过）
                    index = index,
                    round = round,
                    visible = visible,
                    modifier = Modifier.animateItem(placementSpec = MomentPlacementSpring),
                    onOpen = { onOpen(thread.post.id) },
                    onOpenAuthor = onOpenAuthor,
                    onLike = { onLike(thread.post.id) },
                    onDelete = { onDelete(thread.post.id) },
                )
            }
            if (page.hasMore) {
                item(key = "tail") {
                    LoadMoreTail(loading = page.loadingMore, onLoadMore = onLoadMore)
                }
            }
        }
    }
}

// ============================================================
// 一张帖子
// ============================================================

/**
 * 帖子的入场（错峰抬起）。
 *
 * 两个要点：
 * - **只对首屏那几条做**（[index] <= 8）：滚动很久之后再往下新增的条目不该
 *   每次都飘一遍，那是"一直在新东西"的错觉，看着累；
 * - **[visible] 未 true 时不启动**：四个 tab 同时被组合，动画不能由"被组合"驱动。
 * - **[round] 变了就重播**：下拉刷新会把它 +1。
 */
@Composable
private fun Modifier.momentEnter(round: Int, visible: Boolean, index: Int): Modifier {
    if (index > 8) return this
    var started by remember(round) { mutableStateOf(false) }
    LaunchedEffect(round, visible) {
        if (!visible) return@LaunchedEffect
        if (index > 0) kotlinx.coroutines.delay(index * 26L)
        started = true
    }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(300, easing = EaseOutQuart),
        label = "momentEnter$index",
    )
    val slide = with(LocalDensity.current) { 14.dp.toPx() }
    return this.graphicsLayer {
        alpha = progress.coerceIn(0f, 1f)
        translationY = (1f - progress) * slide
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MomentCard(
    thread: MomentMath.Thread,
    author: Persona?,
    index: Int,
    round: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onOpenAuthor: (Long) -> Unit,
    onLike: () -> Unit,
    onDelete: () -> Unit,
) {
    val post = thread.post
    val liked = MomentMath.likedBy(post.likedBy, MomentMath.USER_ID)
    val images = remember(post.images) { MomentMath.decodeImages(post.images) }
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        if (pressed) 0.985f else 1f,
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "momentCardPress",
    )
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf(-1) }

    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .momentEnter(round = round, visible = visible, index = index)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .raisedCard(cornerRadius = 20.dp, elevation = 12.dp)
                .testTag("moment_row_${post.id}")
                .combinedClickable(
                    interactionSource = press,
                    indication = null,
                    onClick = onOpen,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            FeedHead(post = post, author = author, avatarSize = 40.dp, onOpenAuthor = onOpenAuthor)
            if (post.text.isNotBlank()) {
                Spacer(Modifier.height(9.dp))
                Text(post.text, fontSize = 15.sp, lineHeight = 22.sp, color = TextPrimary)
            }
            if (images.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                MomentImageGrid(paths = images, onOpen = { viewerIndex = it })
            }
            MomentLocationChip(post.location)
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FeedAction(
                    icon = MIcons.Reply,
                    label = thread.replyCount,
                    tint = if (thread.replyCount > 0) TextSecondary else FaintText,
                    onClick = onOpen,
                )
                Spacer(Modifier.width(22.dp))
                LikeButton(liked = liked, count = thread.likeCount, onToggle = onLike)
                if (thread.replyCount > 0) {
                    Spacer(Modifier.weight(1f))
                    Text("查看 ${thread.replyCount} 条回复", fontSize = 12.sp, color = MutedText)
                }
            }
        }

        // 长按菜单锚点：DropdownMenu 的 Popup 锚定它的**直接父节点**，
        // 所以必须包进一个贴在卡右下角的 1dp Box（align/offset 直接加在菜单上无效）
        Box(Modifier.align(Alignment.BottomEnd).size(1.dp)) {
            NeuMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                NeuMenuItem("删除", danger = true, onClick = { menuOpen = false; onDelete() })
            }
        }
    }

    if (viewerIndex >= 0) {
        MomentImageViewer(
            paths = images,
            startIndex = viewerIndex,
            onDismiss = { viewerIndex = -1 },
        )
    }
}

/**
 * 点赞：按下去心形**弹一下**（比单纯变个色更明确的手感反馈）+ 变品牌橙红，
 * 数字换的时候上下翻一个。
 *
 * 用 [Animatable] 而不是 `animateFloatAsState`：这里要的不是"两个状态之间过渡"，
 * 而是"每点一次都从头炸一遍"，这种事情只能用命令式的 Animatable 手动播种。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LikeButton(
    liked: Boolean,
    count: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }
    val haptic = LocalHapticFeedback.current
    val tint = if (liked) BrandSalmon else FaintText

    Row(
        modifier.graphicsLayer { }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    // 总是先弹：取消赞也是一次"回应"，同样要给用户看得见的反馈
                    scope.launch {
                        pop.snapTo(0.68f)
                        pop.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 620f))
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onToggle()
                },
                onLongClick = {},
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MIcons.Heart,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
        )
        AnimatedCount(
            count = count,
            color = tint,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/**
 * 数字滚动：0 起步时不显示（推特口径，"没人赞过就别写着 0"），
 * 变化时旧的往上走、新的从下面上来 —— 比直接换字更能让人看到"是我点的生效了"。
 */
@Composable
private fun AnimatedCount(count: Int, color: Color, modifier: Modifier = Modifier) {
    if (count <= 0) return
    AnimatedContent(
        targetState = count,
        transitionSpec = {
            (slideInVertically(tween(180, easing = EaseOutQuart)) { it / 2 } + fadeIn(tween(180)))
                .togetherWith(slideOutVertically(tween(180, easing = EaseOutQuart)) { -it / 2 } + fadeOut(tween(180)))
        },
        label = "likeCount",
        modifier = modifier,
    ) { value ->
        Text("$value", fontSize = 12.sp, color = color)
    }
}

/** 普通动作（回复）：一颗按压会缩的图标 + 数量 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedAction(
    icon: ImageVector,
    label: Int,
    tint: Color,
    onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.86f else 1f,
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "feedActionPress",
    )
    Row(
        Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = src,
                indication = null,
                onClick = onClick,
                onLongClick = onClick,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        AnimatedCount(count = label, color = tint, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 抬头一行：头像 + 名字 +（身份小字）+ 相对时间 */
@Composable
private fun FeedHead(
    post: MomentEntry,
    author: Persona?,
    avatarSize: Dp,
    onOpenAuthor: (Long) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MomentAuthorAvatar(
            personaId = post.personaId,
            author = author,
            size = avatarSize,
            onClick = { onOpenAuthor(post.personaId) },
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                author?.name ?: "我",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (author?.title?.isNotBlank() == true) {
                Text(
                    author.title,
                    fontSize = 11.sp,
                    color = MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(MomentMath.timeLabel(post.createdAt), fontSize = 12.sp, color = MutedText, maxLines = 1)
    }
}

/**
 * 作者头像：AI = emoji/自定义图 + 人设渐变环（与会话列表同一颗 [personaBrush]）；
 * 用户本人（personaId = 0）= 黑底白「我」字圆 —— 黑是本 App 的强调色，不是品牌色。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MomentAuthorAvatar(
    personaId: Long,
    author: Persona?,
    size: Dp,
    onClick: () -> Unit,
) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(110), label = "avatarPress")
    val shared = Modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = press,
            indication = null,
            onClick = onClick,
            onLongClick = onClick,
        )
    if (personaId == 0L) {
        // v0.77.0：用户自己的头像 —— 读「外观 → 我的头像」的设置。
        // 设了 emoji / 图片就用它；都没设才退回黑底白「我」字圆（保持原有观感，不空白）。
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val userAvatar by remember {
            (ctx.applicationContext as com.menghuan.app.MenghuanApp).settingsRepository.userAvatar
        }.collectAsState(initial = com.menghuan.app.data.UserAvatar())
        if (!userAvatar.isEmpty) {
            NeuAvatar(
                emoji = userAvatar.effectiveEmoji.ifBlank { "我" },
                size = size,
                imagePath = userAvatar.imagePath,
                modifier = shared,
            )
            return
        }
        Box(
            shared
                .size(size)
                .background(NeuAccent, RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("我", fontSize = (size.value * 0.34f).sp, color = AccentInk, fontWeight = FontWeight.Medium)
        }
        return
    }
    NeuAvatar(
        emoji = author?.avatarEmoji.takeUnless { it.isNullOrEmpty() } ?: "…",
        size = size,
        ringBrush = personaBrush(personaId),
        imagePath = author?.avatarImagePath ?: "",
        modifier = shared,
    )
}

// ============================================================
// 下拉刷新 / 上拉加载
// ============================================================

/** 自己画的一颗转圈（不用 Material 那颗 CircularProgressIndicator：它是系统彩蓝） */
@Composable
private fun MomentSpinner(size: Dp = 15.dp, color: Color = TextSecondary, running: Boolean = true) {
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "spinAngle",
    )
    Canvas(modifier = Modifier.size(size).rotate(if (running) angle else 0f)) {
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 300f,
            useCenter = false,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/**
 * 下拉指示器：一颗白底带投影的小圆胖子（与卡片同一套语言），不再是 Material 的彩圆。
 *
 * 三态透明度（v0.76.0）：[visible] 为假 → 完全隐身（静止时不许顶在第一张卡上）；
 * 拉着没松手 → 0.72，让人知道「松手就开始刷新」；[refreshing] 为真 → 实心且一直转。
 * 隐身走动画不走 if：拉到一半又推回去时它是淡出的，不会闪没。
 */
@Composable
private fun RefreshBadge(modifier: Modifier = Modifier, refreshing: Boolean, visible: Boolean) {
    val chroma by animateFloatAsState(
        if (!visible) 0f else if (refreshing) 1f else 0.72f,
        tween(200),
        label = "badgeAlpha",
    )
    Box(
        modifier
            .padding(top = 8.dp)
            .size(36.dp)
            .alpha(chroma)
            .raisedCard(cornerRadius = 999.dp, elevation = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        MomentSpinner(size = 15.dp, running = refreshing)
    }
}

/**
 * 尾部「加载更多」：转起来的时候显示它本身；没转的时候就把它变成可以点的一行，
 * 免得自动加载失效时用户抢不到出口。
 */
@Composable
private fun LoadMoreTail(loading: Boolean, onLoadMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .neuRowPress(onClick = { if (!loading) onLoadMore() })
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            MomentSpinner(running = true)
            Spacer(Modifier.width(9.dp))
        }
        Text(
            if (loading) "正在加载" else "加载更多",
            fontSize = 12.sp,
            color = MutedText,
        )
    }
}

// ============================================================
// 详情页
// ============================================================

/**
 * 帖子详情页（点时间线任何一张卡进来）：原帖 + 全部回复 + 底部回复栏。
 *
 * 回复**只有一层**（[MomentMath] 的口径），所以这里不需要树形缩进，
 * 列表平铺就是最终形状 —— 与推特的帖子页一致。
 *
 * 原帖与回复都沿用卡片的那一套：原帖一张白卡，回复贴在另一张卡里、
 * 条与条之间只用间距分开（**不画发丝线**，这一页也同样遵守）。
 */
@Composable
fun MomentThreadScreen(
    momentId: Long,
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit = {},
    vm: MomentsViewModel = viewModel(factory = MomentsViewModel.Factory),
) {
    val feed by vm.feed.collectAsState()
    val message by vm.message.collectAsState()
    var replying by remember { mutableStateOf(false) }

    val thread = remember(feed.threads, momentId) {
        feed.threads.firstOrNull { it.post.id == momentId }
    }

    // 帖子被删掉（或在另一处被清库）时不要画一个空白标题页：直接退回上一级
    LaunchedEffect(feed.loaded, thread) {
        if (feed.loaded && thread == null) onBack()
    }

    Column(Modifier.fillMaxSize().background(CanvasGray)) {
        NeuTopBar(
            title = "动态",
            navigationIcon = {
                NeuCircleButton(onClick = onBack, size = 38.dp) {
                    Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
                }
            },
        )

        if (thread != null) {
            val author = feed.authors[thread.post.personaId]
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    DetailPostCard(
                        post = thread.post,
                        author = author,
                        replyCount = thread.replyCount,
                        liked = MomentMath.likedBy(thread.post.likedBy, MomentMath.USER_ID),
                        likeCount = thread.likeCount,
                        onOpenAuthor = { id -> if (id != 0L) onOpenChat(id) },
                        onLike = { vm.toggleLike(thread.post.id) },
                        onReplyClick = { replying = true },
                    )
                }
                if (thread.replies.isNotEmpty()) {
                    item { RepliesCard(thread.replies, feed.authors) { id -> if (id != 0L) onOpenChat(id) } }
                }
            }

            ReplyBar(onSend = { vm.reply(momentId, it) })
        }
    }

    if (replying) {
        MomentComposeSheet(
            places = emptyList(),
            title = "回复",
            submitLabel = "回复",
            placeholder = "说点什么…",
            maxCharacters = MomentMath.REPLY_MAX,
            allowImages = false,
            allowLocation = false,
            onDismiss = { replying = false },
            onSubmit = { text, _, _ ->
                vm.reply(momentId, text)
                replying = false
            },
        )
    }

    FeedToast(message, vm::clearMessage)
}

/** 详情页的原帖：一张白卡，比时间线那张更松一点（+2dp），正文也更大 */
@Composable
private fun DetailPostCard(
    post: MomentEntry,
    author: Persona?,
    replyCount: Int,
    likeCount: Int,
    liked: Boolean,
    onOpenAuthor: (Long) -> Unit,
    onLike: () -> Unit,
    onReplyClick: () -> Unit,
) {
    val images = remember(post.images) { MomentMath.decodeImages(post.images) }
    var viewerIndex by remember { mutableStateOf(-1) }
    Column(
        Modifier
            .fillMaxWidth()
            .raisedCard(cornerRadius = 20.dp, elevation = 12.dp)
            .padding(horizontal = 15.dp, vertical = 15.dp),
    ) {
        FeedHead(post = post, author = author, avatarSize = 44.dp, onOpenAuthor = onOpenAuthor)
        if (post.text.isNotBlank()) {
            Spacer(Modifier.height(11.dp))
            Text(post.text, fontSize = 17.sp, lineHeight = 26.sp, color = TextPrimary)
        }
        if (images.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            MomentImageGrid(paths = images, onOpen = { viewerIndex = it })
        }
        MomentLocationChip(post.location)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FeedAction(MIcons.Reply, replyCount, if (replyCount > 0) TextSecondary else FaintText, onReplyClick)
            Spacer(Modifier.width(22.dp))
            LikeButton(liked = liked, count = likeCount, onToggle = onLike)
            Spacer(Modifier.weight(1f))
            Text(MomentMath.absoluteLabel(post.createdAt), fontSize = 11.sp, color = FaintText)
        }
    }
    if (viewerIndex >= 0) {
        MomentImageViewer(paths = images, startIndex = viewerIndex, onDismiss = { viewerIndex = -1 })
    }
}

/** 整组回复装在第二张卡里；点与点之间**只用间距**，不画发丝线 */
@Composable
private fun RepliesCard(
    replies: List<MomentEntry>,
    authors: Map<Long, Persona>,
    onOpenAuthor: (Long) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .raisedCard(cornerRadius = 20.dp, elevation = 12.dp)
            .padding(horizontal = 15.dp, vertical = 13.dp),
    ) {
        Text("回复 ${replies.size}", fontSize = 12.sp, color = MutedText, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(10.dp))
        replies.forEach { r ->
            Row(Modifier.fillMaxWidth()) {
                MomentAuthorAvatar(
                    personaId = r.personaId,
                    author = authors[r.personaId],
                    size = 30.dp,
                    onClick = { onOpenAuthor(r.personaId) },
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        authors[r.personaId]?.name ?: "我",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(r.text, fontSize = 15.sp, lineHeight = 21.sp, color = TextPrimary)
                }
                Spacer(Modifier.width(8.dp))
                Text(MomentMath.timeLabel(r.createdAt), fontSize = 11.sp, color = FaintText)
            }
            // 最后一条后面不加距离（卡内 padding 已经够）
            if (r !== replies.last()) Spacer(Modifier.height(14.dp))
        }
    }
}

// ============================================================
// 发帖入口 / 空态 / 回复栏 / 吐司
// ============================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposeFab(modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.9f else 1f,
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "fabPress",
    )
    Box(
        modifier
            .padding(end = 20.dp, bottom = 20.dp)
            .size(54.dp)
            .testTag("moment_compose_fab")
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .raisedCard(cornerRadius = 999.dp, elevation = 16.dp)
            .combinedClickable(
                interactionSource = press,
                indication = null,
                onClick = onClick,
                onLongClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 悬浮钮是这一页唯一用纯色填满的地方：它是"入口"，不是"内容"
        Box(
            Modifier
                .fillMaxSize()
                .background(NeuAccent, RoundedCornerShape(999.dp)),
        )
        Icon(MIcons.Plus, contentDescription = "发动态", tint = AccentInk, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun EmptyFeedHint(onClick: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .figmaSelection(active = true, cornerRadius = 16.dp)
                .neuRowPress(onClick = onClick)
                .padding(vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(MIcons.Plus, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("发第一条动态", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            }
        }
    }
}

/** 底部回复栏：凹陷输入面 + 发送钮（一行高，随内容不撑高——回复上限本来就短） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReplyBar(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .background(CanvasGray)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        NeuTextField(
            value = text,
            onValueChange = { if (it.length <= MomentMath.REPLY_MAX) text = it },
            placeholder = "回复…",
            singleLine = false,
            minLines = 1,
            maxLines = 4,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(9.dp))
        val canSend = text.isNotBlank()
        val alpha by animateFloatAsState(if (canSend) 1f else 0.35f, tween(160), label = "sendAlpha")
        Box(
            Modifier
                .size(40.dp)
                .alpha(alpha)
                .background(NeuAccent, RoundedCornerShape(999.dp))
                .then(
                    if (canSend) {
                        Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                onSend(text)
                                text = ""
                            },
                            onLongClick = {},
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MIcons.Plane, contentDescription = "发送", tint = AccentInk, modifier = Modifier.size(17.dp))
        }
    }
}

/** 一次性反馈小胶囊（"已发布"/"已删除"）：两秒自己走 */
@Composable
private fun FeedToast(message: String?, onExpire: () -> Unit) {
    if (message == null) return
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(2000)
        onExpire()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            message,
            fontSize = 13.sp,
            color = AccentInk,
            modifier = Modifier
                .background(NeuAccent, RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}
