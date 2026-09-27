package com.daoshi.app.ui.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daoshi.app.data.model.ChatMessage
import com.daoshi.app.data.model.ToneStyle
import com.daoshi.app.ui.common.LocalContentWidth
import com.daoshi.app.ui.common.NeuAvatar
import com.daoshi.app.ui.common.NeuCircleButton
import com.daoshi.app.ui.common.NeuSheetRow
import com.daoshi.app.ui.common.NeuSheetScaffold
import com.daoshi.app.ui.common.NeuTextButton
import com.daoshi.app.ui.common.NeuTopBar
import com.daoshi.app.ui.common.neuSurface
import com.daoshi.app.ui.theme.AccentBrush
import com.daoshi.app.ui.theme.MIcons
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.NeuDanger
import com.daoshi.app.ui.theme.TextPrimary
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 单聊页（新拟态）：从会话列表 push 进入，左上返回即退出。
 * - AI 气泡 = 凹陷纸面；用户气泡 = 强调渐变 + 柔和投影
 * - 长按头像（500ms）= 隐藏的风格切换入口
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    personaId: Long,
    onBack: () -> Unit,
    vm: ChatViewModel = viewModel(key = "chat_$personaId", factory = ChatViewModel.factory(personaId)),
) {
    val uiState by vm.uiState.collectAsState()
    val persona by vm.activePersona.collectAsState()
    val tone by vm.tone.collectAsState()
    var showToneSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 气泡最大宽度 = 版心的 76%（比例随版心自适应）
    val bubbleMaxWidth = LocalContentWidth.current * 0.76f

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // 顶部导航：返回键（退出）+ 居中头像区（长按换风格）
            NeuTopBar(
                navigationIcon = {
                    NeuCircleButton(onClick = onBack, size = 38.dp) {
                        Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
                    }
                },
                center = {
                    Row(
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { showToneSheet = true }, // 隐藏入口：长按头像
                        ),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NeuAvatar(persona?.avatarEmoji ?: "🙂", size = 38.dp, accentRing = true)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                persona?.name ?: "…",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${persona?.title ?: ""} · ${tone.label}",
                                fontSize = 11.sp,
                                color = MutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
            )

            // 消息列表（按天分组）
            val listState = rememberLazyListState()
            val chatItems = remember(uiState.messages) { buildChatItems(uiState.messages) }
            LaunchedEffect(uiState.messages.size) {
                listState.animateScrollToItem(chatItems.lastIndex.coerceAtLeast(0))
            }
            // 流式期间跟随尾部气泡增高：仅当用户已在底部附近时按增高量滚动
            val isStreaming = uiState.isStreaming
            LaunchedEffect(chatItems.size, isStreaming) {
                if (!isStreaming) return@LaunchedEffect
                var lastTailHeight = -1
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: 0 }
                    .collect { height ->
                        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        val nearBottom = lastVisible >= chatItems.lastIndex - 1
                        if (nearBottom && lastTailHeight in 1 until height) {
                            listState.scrollBy((height - lastTailHeight).toFloat())
                        }
                        lastTailHeight = height
                    }
            }
            // 是否贴底：控制回底按钮显隐
            // key = chatItems：derivedStateOf 闭包在首帧捕获 chatItems 引用，
            // 不加 key 会永远对着旧列表判断（消息更新后显隐错乱）
            val nearBottom by remember(chatItems) {
                derivedStateOf {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    last >= chatItems.lastIndex - 1
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // 用 index 作 key：流式占位消息与乐观插入的用户消息尚未拿到 DB id，
                    // 用 it.id 会导致多个 id=0 冲突
                    items(chatItems.size) { idx ->
                        when (val item = chatItems[idx]) {
                            is ChatListItem.DayHeader -> Box(
                                Modifier.fillMaxWidth().animateItem(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .padding(vertical = 8.dp)
                                        .neuSurface(
                                            999.dp,
                                            color = MaterialTheme.colorScheme.background,
                                            elevation = 4.dp,
                                            inset = true,
                                        )
                                        .padding(horizontal = 14.dp, vertical = 5.dp),
                                ) {
                                    Text(item.label, fontSize = 11.sp, color = MutedText)
                                }
                            }

                            is ChatListItem.Msg -> Box(Modifier.animateItem()) {
                                MessageBubble(item.message, bubbleMaxWidth, item.gapBefore)
                            }
                        }
                    }
                }

                // 回底按钮：上翻历史时浮出
                // 注意：此处必须写全限定名——Box 作用域里裸名 AnimatedVisibility 会被解析成
                // ColumnScope 扩展而编译失败（Kotlin 隐式接收者优先于顶层函数）
                androidx.compose.animation.AnimatedVisibility(
                    visible = !nearBottom,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    enter = scaleIn(tween(220)) + fadeIn(tween(220)),
                    exit = scaleOut(tween(160)) + fadeOut(tween(160)),
                ) {
                    val scope = rememberCoroutineScope()
                    NeuCircleButton(
                        onClick = {
                            scope.launch { listState.animateScrollToItem(chatItems.lastIndex.coerceAtLeast(0)) }
                        },
                        accent = true,
                        size = 40.dp,
                    ) {
                        Text("↓", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 请求失败提示 + 重试（失败消息不写入历史）
            uiState.error?.let { error ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .neuSurface(16.dp, color = MaterialTheme.colorScheme.background, elevation = 4.dp, inset = true)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        error,
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        color = NeuDanger,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(10.dp))
                    NeuTextButton("重试", onClick = { vm.retry() })
                }
            }

            // 输入栏：凹陷相机键 + 凹陷输入胶囊 + 渐变发送键
            Row(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .background(Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .neuSurface(13.dp, color = MaterialTheme.colorScheme.background, elevation = 4.dp, inset = true)
                        .combinedClickable(onClick = {
                            Toast.makeText(context, "开发中", Toast.LENGTH_SHORT).show()
                        }, onLongClick = {}),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MIcons.Camera, contentDescription = "相机", tint = MutedText, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    NeuChatInput(
                        value = uiState.input,
                        onValueChange = vm::onInputChange,
                    )
                }
                Spacer(Modifier.width(10.dp))
                NeuCircleButton(onClick = { vm.send() }, accent = true, size = 38.dp) {
                    Icon(MIcons.Plane, contentDescription = "发送", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    if (showToneSheet) {
        ToneSwitchSheet(
            currentTone = tone,
            onSelect = { vm.selectTone(it); showToneSheet = false },
            onDismiss = { showToneSheet = false },
        )
    }
}

/** 凹陷输入胶囊 */
@Composable
private fun NeuChatInput(value: String, onValueChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .neuSurface(22.dp, color = MaterialTheme.colorScheme.background, elevation = 5.dp, inset = true),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 11.dp),
            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, color = TextPrimary),
            maxLines = 5,
            cursorBrush = SolidColor(NeuAccent),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text("发消息…", fontSize = 15.sp, color = MutedText)
                    }
                    inner()
                }
            },
        )
    }
}

/** 消息列表条目：按天分组的日期头 or 消息气泡（gapBefore = 换人时的呼吸间距） */
private sealed interface ChatListItem {
    data class DayHeader(val label: String) : ChatListItem
    data class Msg(val message: ChatMessage, val gapBefore: Boolean) : ChatListItem
}

private fun dayLabel(date: LocalDate): String = when (date) {
    LocalDate.now() -> "今天"
    LocalDate.now().minusDays(1) -> "昨天"
    else -> "${date.monthValue}月${date.dayOfMonth}日"
}

private fun buildChatItems(messages: List<ChatMessage>): List<ChatListItem> {
    val zone = ZoneId.systemDefault()
    val items = mutableListOf<ChatListItem>()
    var lastDay: LocalDate? = null
    var lastFromUser: Boolean? = null
    messages.forEach { message ->
        val day = Instant.ofEpochMilli(message.timestamp).atZone(zone).toLocalDate()
        if (day != lastDay) {
            items += ChatListItem.DayHeader(dayLabel(day))
            lastDay = day
        }
        val gap = lastFromUser != null && lastFromUser != message.isFromUser
        items += ChatListItem.Msg(message, gap)
        lastFromUser = message.isFromUser
    }
    return items
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    maxWidth: androidx.compose.ui.unit.Dp,
    gapBefore: Boolean,
) {
    val isUser = message.isFromUser
    val isHeart = message.content == "❤️"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (gapBefore) 8.dp else 0.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        when {
            isHeart -> Text("❤️", fontSize = 44.sp)
            isUser -> UserBubble(message.content, maxWidth)
            else -> AiBubble(message.content, maxWidth)
        }
    }
}

/** AI 气泡：凹陷纸面 */
@Composable
private fun AiBubble(content: String, maxWidth: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .widthIn(max = maxWidth)
            .neuSurface(
                22.dp,
                color = MaterialTheme.colorScheme.background,
                elevation = 5.dp,
                inset = true,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        if (content.isBlank()) {
            TypingDots()
        } else {
            Text(content, color = TextPrimary, fontSize = 15.sp, lineHeight = 21.sp)
        }
    }
}

/** 用户气泡：强调渐变 + 柔光投影 */
@Composable
private fun UserBubble(content: String, maxWidth: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .widthIn(max = maxWidth)
            .drawUserBubble()
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(content, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp)
    }
}

/** 用户气泡渐变填充 + 同色系柔光投影 */
private fun Modifier.drawUserBubble(): Modifier = this.then(
    Modifier.drawBehind {
        val e = 5.dp.toPx()
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(
                    NeuAccent.copy(alpha = 0.5f),
                    NeuAccent.copy(alpha = 0.28f),
                ),
            ),
            topLeft = Offset(e * 0.5f, e * 0.9f),
            size = Size(size.width + e * 0.8f, size.height + e * 1.2f),
            cornerRadius = CornerRadius(22.dp.toPx() + e),
        )
        drawRoundRect(
            AccentBrush,
            size = size,
            cornerRadius = CornerRadius(22.dp.toPx()),
        )
    }.clip(RoundedCornerShape(22.dp)),
)

/** 流式等待三点：错落呼吸 */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val pulse by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(500, delayMillis = i * 160),
                    RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .alpha(pulse)
                    .background(MutedText, CircleShape),
            )
        }
    }
}

/** 长按头像弹出：对话风格快速切换（隐藏入口） */
@Composable
private fun ToneSwitchSheet(
    currentTone: ToneStyle,
    onSelect: (ToneStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    NeuSheetScaffold(title = "对话风格", onDismiss = onDismiss) {
        Spacer(Modifier.height(14.dp))
        ToneStyle.entries.forEach { style ->
            NeuSheetRow(
                icon = when (style) {
                    ToneStyle.GENTLE -> MIcons.ToneGentle
                    ToneStyle.STRICT -> MIcons.ToneStrict
                    ToneStyle.SOCRATIC -> MIcons.ToneSocratic
                },
                title = style.label,
                subtitle = style.promptSnippet.take(16) + "…",
                selected = style == currentTone,
                onClick = { onSelect(style) },
            )
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "人设（名字 / 身份 / 设定词）可在「设置」中自由修改或新增，不止于「导师」。",
            fontSize = 12.sp,
            color = MutedText,
        )
    }
}
