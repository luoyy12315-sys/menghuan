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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daoshi.app.data.model.ChatMessage
import com.daoshi.app.data.model.TaskItem
import com.daoshi.app.data.model.ToneStyle
import com.daoshi.app.ui.common.LocalContentWidth
import com.daoshi.app.ui.common.MarkdownText
import com.daoshi.app.ui.common.NeuAvatar
import com.daoshi.app.ui.common.NeuChip
import com.daoshi.app.ui.common.NeuCircleButton
import com.daoshi.app.ui.common.NeuSheetRow
import com.daoshi.app.ui.common.NeuSheetScaffold
import com.daoshi.app.ui.common.NeuTextButton
import com.daoshi.app.ui.common.NeuTopBar
import com.daoshi.app.ui.common.neuRowPress
import com.daoshi.app.ui.common.neuSurface
import com.daoshi.app.ui.theme.MIcons
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.NeuDanger
import com.daoshi.app.ui.theme.TextPrimary
import com.daoshi.app.ui.theme.personaBrush
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn

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
    val quickTasks by vm.quickCheckTasks.collectAsState()
    val taskSuggestions by vm.taskSuggestions.collectAsState()
    val searchResults by vm.searchResults.collectAsState()
    var showToneSheet by remember { mutableStateOf(false) }
    var showQuickCheck by remember { mutableStateOf(false) }
    var messageMenu by remember { mutableStateOf<ChatMessage?>(null) }
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    // 气泡最大宽度 = 版心的 76%（比例随版心自适应）
    val bubbleMaxWidth = LocalContentWidth.current * 0.76f

    // 查看即已读：会话可见期间每条新消息到达都刷新阅读时刻，列表未读点立即消除
    LaunchedEffect(uiState.messages.lastOrNull()?.id) { vm.markRead() }

    // 外层容器：让搜索态覆盖层（第二个 fillMaxSize 子级）真正叠在聊天内容之上，
    // 而不是作为 AdaptiveScreen Column 的兄弟被挤成 0 高度
    Box(Modifier.fillMaxSize()) {
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
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            // 隐藏入口：长按头像（500ms），带触觉反馈确认触发
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showToneSheet = true
                            },
                        ),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NeuAvatar(
                            persona?.avatarEmoji ?: "🙂",
                            size = 38.dp,
                            ringBrush = personaBrush(personaId),
                        )
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
                actions = {
                    NeuCircleButton(onClick = { searchMode = true }, size = 38.dp) {
                        Icon(MIcons.Search, contentDescription = "搜索消息", tint = TextPrimary, modifier = Modifier.size(19.dp))
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
                                        .neuSurface(999.dp, inset = true)
                                        .padding(horizontal = 14.dp, vertical = 5.dp),
                                ) {
                                    Text(
                                        item.label,
                                        fontSize = 10.sp,
                                        letterSpacing = 1.sp,
                                        color = MutedText,
                                    )
                                }
                            }

                            is ChatListItem.Msg -> Box(Modifier.animateItem()) {
                                MessageBubble(
                                    item.message,
                                    bubbleMaxWidth,
                                    item.gapBefore,
                                    onLongPress = { messageMenu = item.message },
                                )
                            }
                        }
                    }

                    // AI 任务建议卡片：提炼引擎产出，确认「添加任务」后消失（API 未配置时引擎不产出，自然不出现）
                    if (taskSuggestions.isNotEmpty()) {
                        item(key = "taskSuggestions") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .animateItem(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                taskSuggestions.forEach { suggestion ->
                                    TaskSuggestionCard(
                                        suggestion = suggestion,
                                        onAdd = { vm.addSuggestedTask(suggestion) },
                                    )
                                }
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
                // 快捷打卡入口：从当前会话直接打卡，回执进对话流（导师下轮可感知）
                NeuCircleButton(onClick = { showQuickCheck = true }, size = 38.dp) {
                    Icon(MIcons.Checklist, contentDescription = "快捷打卡", tint = MutedText, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    NeuChatInput(
                        value = uiState.input,
                        onValueChange = vm::onInputChange,
                    )
                }
                Spacer(Modifier.width(10.dp))
                NeuCircleButton(
                    onClick = { vm.send() },
                    accent = true,
                    size = 38.dp,
                    // 空输入时禁用并弱化（阴影收缩），给出"没东西可发"的直觉
                    enabled = !uiState.isStreaming && uiState.input.isNotBlank(),
                ) {
                    Icon(MIcons.Plane, contentDescription = "发送", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    // 会话内搜索态：覆盖整页（顶栏搜索图标进入，返回退出），LIKE 命中实时过滤
    if (searchMode) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeuCircleButton(
                    onClick = {
                        searchMode = false
                        searchQuery = ""
                        vm.onSearchQueryChange("")
                    },
                    size = 38.dp,
                ) {
                    Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .neuSurface(22.dp, color = MaterialTheme.colorScheme.background, elevation = 5.dp, inset = true),
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            vm.onSearchQueryChange(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 11.dp)
                            .focusRequester(focusRequester)
                            .testTag("chat_search_field"),
                        textStyle = TextStyle(fontSize = 15.sp, color = TextPrimary),
                        singleLine = true,
                        cursorBrush = SolidColor(NeuAccent),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text("搜索消息…", fontSize = 15.sp, color = MutedText)
                                }
                                inner()
                            }
                        },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    searchQuery.isBlank() -> {}
                    searchResults.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("没有匹配结果", fontSize = 13.sp, color = com.daoshi.app.ui.theme.FaintText)
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(searchResults.size) { idx ->
                            ChatSearchRow(
                                message = searchResults[idx],
                                fromLabel = if (searchResults[idx].isFromUser) "我" else (persona?.name ?: "导师"),
                                onClick = {
                                    searchMode = false
                                    searchQuery = ""
                                    vm.onSearchQueryChange("")
                                },
                            )
                        }
                    }
                }
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

    if (showQuickCheck) {
        QuickCheckSheet(
            tasks = quickTasks,
            onCheckIn = { item ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                vm.sendText("✅ 打卡：${item.task.title}")
                showQuickCheck = false
            },
            onDismiss = { showQuickCheck = false },
        )
    }

    messageMenu?.let { message ->
        MessageMenuSheet(
            message = message,
            canDelete = message.id > 0 && !uiState.isStreaming,
            onCopy = {
                copyToClipboard(context, message.content)
                messageMenu = null
            },
            onDelete = {
                vm.deleteMessage(message)
                messageMenu = null
            },
            onDismiss = { messageMenu = null },
        )
    }
}

/** 长按气泡弹出：复制 / 删除（已落库消息才可删；种子演示消息仅可复制） */
@Composable
private fun MessageMenuSheet(
    message: ChatMessage,
    canDelete: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    NeuSheetScaffold(
        title = if (message.isFromUser) "我的消息" else "导师消息",
        onDismiss = onDismiss,
    ) {
        Spacer(Modifier.height(14.dp))
        NeuSheetRow(
            icon = MIcons.Copy,
            title = "复制全文",
            subtitle = "",
            onClick = onCopy,
        )
        Spacer(Modifier.height(10.dp))
        if (canDelete) {
            NeuSheetRow(
                icon = MIcons.Delete,
                title = "删除消息",
                subtitle = "",
                onClick = onDelete,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** AI 任务建议卡：白面细线 + 标题/备注 +「添加任务」胶囊，点击写入任务后整卡消失 */
@Composable
private fun TaskSuggestionCard(suggestion: com.daoshi.app.data.TaskSuggestion, onAdd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .neuSurface(18.dp, elevation = 2.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                suggestion.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            if (suggestion.note.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(suggestion.note, fontSize = 12.sp, color = MutedText, maxLines = 2)
            }
        }
        Spacer(Modifier.width(10.dp))
        NeuChip(text = "添加任务", selected = false, onClick = onAdd)
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
    onLongPress: () -> Unit,
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
            isUser -> UserBubble(message.content, maxWidth, onLongPress)
            else -> AiBubble(message.content, maxWidth, onLongPress)
        }
    }
}

/** AI 气泡：凹陷纸面；长按弹操作菜单 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiBubble(
    content: String,
    maxWidth: androidx.compose.ui.unit.Dp,
    onLongPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .widthIn(max = maxWidth)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .neuSurface(18.dp, color = MaterialTheme.colorScheme.surface, elevation = 2.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        if (content.isBlank()) {
            TypingDots()
        } else {
            MarkdownText(content)
        }
    }
}

/** 用户气泡：纯黑药丸 + 纸叠影（Figma 界面唯一强调色） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    content: String,
    maxWidth: androidx.compose.ui.unit.Dp,
    onLongPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .widthIn(max = maxWidth)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .neuSurface(22.dp, color = NeuAccent, elevation = 3.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(content, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = (-0.1).sp)
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("daoshi", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
}

/** 流式等待三点：错落呼吸（alpha 走 graphicsLayer，渲染期更新不重组） */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val pulse by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(480, delayMillis = i * 150),
                    RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(if (i == 1) 8.dp else 6.dp)
                    .graphicsLayer { alpha = pulse }
                    .background(Color.Black, CircleShape),
            )
        }
    }
}

/** 单聊搜索结果行：来源 + 时间 + 内容预览（点击退出搜索态） */
@Composable
private fun ChatSearchRow(message: ChatMessage, fromLabel: String, onClick: () -> Unit) {
    val time = Instant.ofEpochMilli(message.timestamp).atZone(ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))
    Column(
        Modifier
            .fillMaxWidth()
            .neuRowPress(onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fromLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.width(8.dp))
            Text(time, fontSize = 11.sp, color = com.daoshi.app.ui.theme.FaintText)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            message.content.replace('\n', ' '),
            fontSize = 14.sp,
            color = MutedText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
    }
}

/** 输入栏左键弹出：快捷打卡——勾选未完成任务，以消息形式发进当前会话；右侧 7 天完成小圆点（黑=完成） */
@Composable
private fun QuickCheckSheet(
    tasks: List<ChatViewModel.QuickCheckTask>,
    onCheckIn: (ChatViewModel.QuickCheckTask) -> Unit,
    onDismiss: () -> Unit,
) {
    NeuSheetScaffold(title = "快捷打卡", onDismiss = onDismiss) {
        Spacer(Modifier.height(14.dp))
        if (tasks.isEmpty()) {
            Text(
                "暂无任务",
                modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                fontSize = 14.sp,
                color = MutedText,
            )
        } else {
            Column(
                Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                tasks.forEach { item ->
                    val task = item.task
                    val dueTag = when {
                        task.dueAt == 0L -> "今天"
                        else -> {
                            val date = Instant.ofEpochMilli(task.dueAt)
                                .atZone(ZoneId.systemDefault()).toLocalDate()
                            when {
                                date < LocalDate.now() -> "已逾期"
                                date == LocalDate.now() -> "今天"
                                else -> "${date.monthValue}月${date.dayOfMonth}日"
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .neuRowPress { onCheckIn(item) }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            MIcons.Checklist,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(task.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                            Spacer(Modifier.height(2.dp))
                            Text(dueTag, fontSize = 12.sp, color = MutedText)
                        }
                        // 近 7 天完成轨迹：左→右 = 6 天前 → 今天，黑=完成、灰=未
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (6 downTo 0).forEach { offset ->
                                val day = LocalDate.now().minusDays(offset.toLong()).toEpochDay()
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .background(
                                            if (day in item.doneDays) NeuAccent else Color(0xFFE3E3E3),
                                            CircleShape,
                                        ),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}
