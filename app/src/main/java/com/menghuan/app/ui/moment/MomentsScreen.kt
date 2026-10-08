package com.menghuan.app.ui.moment

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.data.MomentMath
import com.menghuan.app.data.model.Persona
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuMenu
import com.menghuan.app.ui.common.NeuMenuItem
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.figmaSelection
import com.menghuan.app.ui.common.neuRowPress
import com.menghuan.app.ui.common.rememberNeuPressState
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.BrandSalmon
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import com.menghuan.app.ui.theme.personaBrush

/**
 * 动态时间线（v0.73.0，推特式帖子）。
 *
 * ## 形状
 * 一条帖子 = 「头像 + 名字 + 时间」抬头 + 正文 + 一排动作（回复 / 点赞），
 * 条目之间从文字起始处画一条发丝线（与会话列表同一条规矩：避开头像）。
 * 整行可点 = 进详情看回复；**长按 = 震一下 + 弹菜单**，菜单里点「删除」才真删（§四）。
 *
 * ## 发帖入口为什么是右下角悬浮圆钮
 * 顶栏右侧那格在窄屏只有 56–84dp，和标题挤；推特本人也是右下悬浮 compose ——
 * 拇指天然落点，滚动时也一直够得着。
 *
 * ## 三态（照抄会话列表 v0.43.1 的教训）
 * 没加载完 → 不显示任何"空"的东西；加载完且真的一条没有 → 虚线框一个动作；
 * 有内容 → 时间线。**顺序错了就是"冷启动先闪一下发帖引导"**，那个 bug 修过一次。
 */
@Composable
fun MomentsScreen(
    onOpenThread: (Long) -> Unit = {},
    onOpenChat: (Long) -> Unit = {},
    vm: MomentsViewModel = viewModel(factory = MomentsViewModel.Factory),
) {
    val feed by vm.feed.collectAsState()
    var composing by remember { mutableStateOf(false) }
    val message by vm.message.collectAsState()

    Column(Modifier.fillMaxSize().background(NeuSurface)) {
        NeuTopBar(title = "动态")

        Box(Modifier.weight(1f)) {
            when {
                !feed.loaded -> Box(Modifier.fillMaxSize())
                feed.threads.isEmpty() -> EmptyFeedHint { composing = true }
                else -> LazyColumn(
                    Modifier.fillMaxSize().testTag("moments_list"),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 92.dp),
                ) {
                    items(feed.threads, key = { it.post.id }) { thread ->
                        MomentRow(
                            thread = thread,
                            author = feed.authors[thread.post.personaId],
                            onOpen = { onOpenThread(thread.post.id) },
                            onOpenAuthor = { id -> if (id != 0L) onOpenChat(id) },
                            onLike = { vm.toggleLike(thread.post.id) },
                            onDelete = { vm.delete(thread.post.id) },
                        )
                    }
                }
            }

            ComposeFab(
                modifier = Modifier.align(Alignment.BottomEnd),
                onClick = { composing = true },
            )
        }
    }

    if (composing) {
        ComposeSheet(
            title = "发动态",
            placeholder = "此刻在想什么？",
            max = MomentMath.POST_MAX,
            onDismiss = { composing = false },
            onSubmit = {
                vm.post(it)
                composing = false
            },
        )
    }

    FeedToast(message, vm::clearMessage)
}

/**
 * 帖子详情页（点时间线任何一行进来）：原帖 + 全部回复 + 底部回复栏。
 *
 * 回复**只有一层**（[MomentMath] 的口径），所以这里不需要树形缩进，
 * 列表平铺就是最终形状 —— 与推特的帖子页一致。
 */
@Composable
fun MomentThreadScreen(
    momentId: Long,
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit = {},
    vm: MomentsViewModel = viewModel(factory = MomentsViewModel.Factory),
) {
    val feed by vm.feed.collectAsState()
    var replying by remember { mutableStateOf(false) }
    val message by vm.message.collectAsState()

    val thread = remember(feed.threads, momentId) {
        feed.threads.firstOrNull { it.post.id == momentId }
    }

    // 帖子被删掉（或在另一处被清库）时不要画一个空白标题页：直接退回上一级
    LaunchedEffect(feed.loaded, thread) {
        if (feed.loaded && thread == null) onBack()
    }

    Column(Modifier.fillMaxSize().background(NeuSurface)) {
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
                Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item {
                    MomentDetailPost(
                        post = thread.post,
                        author = author,
                        replyCount = thread.replyCount,
                        likeCount = thread.likeCount,
                        liked = MomentMath.likedBy(thread.post.likedBy, MomentMath.USER_ID),
                        onOpenAuthor = { id -> if (id != 0L) onOpenChat(id) },
                        onLike = { vm.toggleLike(thread.post.id) },
                        onReplyClick = { replying = true },
                    )
                }
                if (thread.replies.isNotEmpty()) {
                    item { SectionRule("回复 ${thread.replies.size} 条") }
                    items(thread.replies, key = { it.id }) { r ->
                        MomentReplyRow(
                            reply = r,
                            author = feed.authors[r.personaId],
                            onOpenAuthor = { id -> if (id != 0L) onOpenChat(id) },
                        )
                    }
                }
            }

            // 底部回复栏：一行凹陷输入框 + 发送圆钮（推特式，点发送才展开成弹层太绕）
            ReplyBar(onSend = { vm.reply(momentId, it) })
        }
    }

    FeedToast(message, vm::clearMessage)
}

// ============================================================
// 行组件
// ============================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MomentRow(
    thread: MomentMath.Thread,
    author: Persona?,
    onOpen: () -> Unit,
    onOpenAuthor: (Long) -> Unit,
    onLike: () -> Unit,
    onDelete: () -> Unit,
) {
    val post = thread.post
    val liked = MomentMath.likedBy(post.likedBy, MomentMath.USER_ID)
    val press = remember { MutableInteractionSource() }
    val pressed by animateFloatAsState(
        if (press.collectIsPressedAsState().value) 0.985f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "momentRowPress",
    )
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().testTag("moment_row_${post.id}")) {
        Column(
            Modifier
                .fillMaxWidth()
                .scale(pressed)
                .combinedClickable(
                    interactionSource = press,
                    indication = null,
                    onClick = onOpen,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            FeedHead(post = post, author = author, avatarSize = 40.dp, onOpenAuthor = onOpenAuthor)
            Spacer(Modifier.height(6.dp))
            Text(post.text, fontSize = 15.sp, color = TextPrimary, lineHeight = 22.sp)
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeedAction(MIcons.Reply, thread.replyCount, if (thread.replyCount > 0) TextSecondary else FaintText, onOpen)
                Spacer(Modifier.width(20.dp))
                FeedAction(MIcons.Heart, thread.likeCount, if (liked) BrandSalmon else FaintText, onLike)
                if (thread.replyCount > 0) {
                    Spacer(Modifier.weight(1f))
                    Text("查看 ${thread.replyCount} 条回复", fontSize = 12.sp, color = MutedText)
                }
            }
        }

        // 长按菜单锚点：DropdownMenu 的 Popup 锚定它的**直接父节点**，
        // 所以必须包进一个贴在行右下角的 1dp Box（align/offset 直接加在菜单上无效）
        Box(Modifier.align(Alignment.BottomEnd).size(1.dp)) {
            NeuMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                NeuMenuItem("删除", danger = true, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

/** 详情页的原帖：抬头 + 全文 + 绝对时间 + 动作，动作行下面接一条通栏发丝线 */
@Composable
private fun MomentDetailPost(
    post: com.menghuan.app.data.model.MomentEntry,
    author: Persona?,
    replyCount: Int,
    likeCount: Int,
    liked: Boolean,
    onOpenAuthor: (Long) -> Unit,
    onLike: () -> Unit,
    onReplyClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        FeedHead(post = post, author = author, avatarSize = 44.dp, onOpenAuthor = onOpenAuthor)
        Spacer(Modifier.height(10.dp))
        Text(post.text, fontSize = 18.sp, color = TextPrimary, lineHeight = 27.sp)
        Spacer(Modifier.height(10.dp))
        Text(MomentMath.absoluteLabel(post.createdAt), fontSize = 12.sp, color = MutedText)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FeedAction(MIcons.Reply, replyCount, if (replyCount > 0) TextSecondary else FaintText, onReplyClick)
            Spacer(Modifier.width(20.dp))
            FeedAction(MIcons.Heart, likeCount, if (liked) BrandSalmon else FaintText, onLike)
        }
    }
    Box(Modifier.fillMaxWidth().height(0.7.dp).background(NeuHairline))
}

@Composable
private fun MomentReplyRow(
    reply: com.menghuan.app.data.model.MomentEntry,
    author: Persona?,
    onOpenAuthor: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MomentAuthorAvatar(
                personaId = reply.personaId,
                author = author,
                size = 30.dp,
                onClick = { onOpenAuthor(reply.personaId) },
            )
            Spacer(Modifier.width(9.dp))
            Text(author?.name ?: "我", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.width(6.dp))
            Text(MomentMath.timeLabel(reply.createdAt), fontSize = 11.sp, color = FaintText)
        }
        Spacer(Modifier.height(5.dp))
        // 缩进到文字起始处（30 头像 + 9 间距），与抬头的文字左缘对齐
        Text(reply.text, fontSize = 15.sp, color = TextPrimary, lineHeight = 21.sp, modifier = Modifier.padding(start = 39.dp))
    }
    Box(Modifier.fillMaxWidth().height(0.7.dp).padding(start = 55.dp).background(NeuHairline))
}

/** 抬头一行：头像 + 名字 +（身份小字）+ 相对时间 */
@Composable
private fun FeedHead(
    post: com.menghuan.app.data.model.MomentEntry,
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
        Text(
            author?.name ?: "我",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (author?.title?.isNotBlank() == true) {
            Spacer(Modifier.width(7.dp))
            Text(
                author.title,
                fontSize = 11.sp,
                color = FaintText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.width(8.dp))
        Text(MomentMath.timeLabel(post.createdAt), fontSize = 12.sp, color = MutedText, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FeedAction(icon: ImageVector, count: Int, tint: Color, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by animateFloatAsState(
        if (src.collectIsPressedAsState().value) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "feedActionPress",
    )
    Row(
        Modifier.scale(pressed).padding(4.dp).combinedClickable(
            interactionSource = src,
            indication = null,
            onClick = onClick,
            onLongClick = onClick,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        if (count > 0) {
            Spacer(Modifier.width(5.dp))
            Text("$count", fontSize = 12.sp, color = tint)
        }
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
    val press = rememberNeuPressState()
    if (personaId == 0L) {
        Box(
            Modifier
                .size(size)
                .scale(press.scale)
                .background(NeuAccent, RoundedCornerShape(999.dp))
                .combinedClickable(
                    interactionSource = press.interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = onClick,
                ),
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
        modifier = Modifier
            .scale(press.scale)
            .combinedClickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onClick,
            ),
    )
}

// ============================================================
// 发帖 / 回复 / 空态
// ============================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComposeFab(modifier: Modifier, onClick: () -> Unit) {
    val press = rememberNeuPressState()
    Box(
        modifier
            .padding(end = 20.dp, bottom = 20.dp)
            .size(54.dp)
            .scale(press.scale)
            .background(NeuAccent, RoundedCornerShape(999.dp))
            .testTag("moment_compose_fab")
            .combinedClickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MIcons.Plus, contentDescription = "发动态", tint = AccentInk, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun EmptyFeedHint(onClick: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .figmaSelection(active = true, cornerRadius = 14.dp)
                .neuRowPress(onClick = onClick)
                .padding(vertical = 26.dp),
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

@Composable
private fun SectionRule(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, color = MutedText, letterSpacing = 1.1.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(0.7.dp).background(NeuHairline))
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
            .background(NeuSurface)
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
        val alpha by animateFloatAsState(if (canSend) 1f else 0.35f, label = "sendAlpha")
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

/** 发帖 / 回复共用的输入弹层（两页只差标题、占位与字数上限） */
@Composable
private fun ComposeSheet(
    title: String,
    placeholder: String,
    max: Int,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    NeuSheetScaffold(title = title, onDismiss = onDismiss) {
        Spacer(Modifier.height(6.dp))
        NeuTextField(
            value = text,
            onValueChange = { if (it.length <= max) text = it },
            placeholder = placeholder,
            singleLine = false,
            minLines = 3,
            maxLines = 8,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${text.length}/$max",
                fontSize = 11.sp,
                color = if (text.length >= max) NeuDanger else FaintText,
            )
            Spacer(Modifier.weight(1f))
            NeuButton(
                text = if (title == "发动态") "发布" else "回复",
                onClick = { onSubmit(text) },
                kind = NeuButtonKind.Primary,
                enabled = text.isNotBlank(),
            )
        }
        Spacer(Modifier.height(6.dp))
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
