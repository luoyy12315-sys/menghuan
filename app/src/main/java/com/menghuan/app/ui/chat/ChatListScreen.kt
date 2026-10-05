package com.menghuan.app.ui.chat

import android.content.Context
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.PersonaRepository
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.MediaKind
import com.menghuan.app.data.model.Persona
import com.menghuan.app.ui.common.EaseOutQuart
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuPressSpec
import com.menghuan.app.ui.common.figmaSelection
import com.menghuan.app.ui.common.neuRowPress
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.personaBrush
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import com.menghuan.app.ui.theme.NeuSurface

/** 会话行 UI：预览/时间取自真实消息记录 */
data class ConversationRowUi(
    val persona: Persona,
    val preview: String,
    val time: String,
    val showDot: Boolean,
)

/** 搜索结果行：人设 + 命中的最新一条消息（null = 仅名字/身份命中） */
data class SearchResultUi(
    val persona: Persona,
    val matched: ChatMessage?,
)

/**
 * 会话列表的加载态（v0.43.1 修"冷启动先闪『新建人设』"）。
 *
 * ⚠️ 以前列表只分「空 / 非空」两种，而 [ChatListViewModel.rows] 的初值是 `emptyList()`
 * —— 冷启动时 Room 的首个查询结果还没回来，列表就先按"空"渲染出居中的「新建人设」
 * 虚线框，等数据到了才换成真实会话列表。用户看到的就是**先闪一下新建人设**。
 *
 * 现在显式区分「还没加载出来」与「确实一条都没有」：只有前者用骨架屏，
 * 后者才显示新建入口。
 */
data class ChatListState(
    val rows: List<ConversationRowUi>,
    val loaded: Boolean,
)

/**
 * 会话列表页（Figma 风）：主入口。
 * - 每个 persona 是一个独立会话，点行进入单聊（onOpen）
 * - 预览 = MessageDao 最后一条消息；未读点 = AI 有新回复且晚于上次阅读时刻
 * - 顶栏只留标题与搜索（设置/人设管理入口在「我的」页）；
 *   双栏模式（平板）下 onSelect 非 null：点行不 push，改为切换右栏会话
 */
class ChatListViewModel(
    private val personaRepository: PersonaRepository,
    private val settingsRepository: SettingsRepository,
    private val db: AppDatabase,
) : ViewModel() {

    val rows: StateFlow<List<ConversationRowUi>> =
        combine(
            personaRepository.observePersonas(),
            db.messageDao().observeLastMessages(),
            settingsRepository.readMarks,
        ) { personas, lastMessages, readMarks ->
            val byConversation = lastMessages.associateBy { it.conversationId }
            // 微信式已删除会话：清空消息后从列表隐藏；新消息到达（预览重现）即恢复显示
            personas
                .filter { !(it.hidden && byConversation[it.id] == null) }
                .map { persona ->
                    val last = byConversation[persona.id]
                    ConversationRowUi(
                        persona = persona,
                        preview = previewOf(persona, last),
                        time = timeOf(last?.timestamp),
                        showDot = last != null && !last.isFromUser &&
                            last.timestamp > (readMarks[persona.id] ?: 0L),
                    )
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 列表状态 = 数据 + **「是否已加载过一次」**（v0.43.1 修"冷启动先闪『新建人设』"）。
     *
     * ⚠️ [rows] 的初值是**空列表**（Room 首个结果还没回来），UI 若直接按"空 = 没有人设"
     * 渲染，就会先闪一下居中的「新建人设」入口，数据到了才换成真实列表 ——
     * 用户实测："先显示新建人设，然后对话列表才出现"。
     *
     * ⚠️⚠️ 这里**绝不能另开一条 `observePersonas().first()` 协程去探测"数据就绪"**：
     * v0.43.1 返工时这么写过，两个既有单测立刻 60s 超时 ——
     *   ① Room 的查询 executor 共享，启动期多开一条"扫全表"的协程会和 `rows` 的 `combine`
     *      首值争抢线程，把列表首值堵死（与 MEMORY 里 Application.onCreate 的教训同源）；
     *   ② `runTest` 会把未完成的子协程视为"测试未结束"，直接 UncompletedCoroutinesError。
     *
     * 正解 = **从 rows 自身派生**，额外查询 0 次：上游只要吐过一次就永久 `loaded = true`。
     */
    val screenState: StateFlow<ChatListState> =
        rows.map { ChatListState(it, loaded = true) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ChatListState(emptyList(), loaded = false),
            )

    fun savePersona(persona: Persona) = viewModelScope.launch {
        personaRepository.save(persona)
    }

    /** 长按会话 → 删除会话：清空全部消息 + 从列表隐藏（微信式），重新打开或新消息即恢复 */
    fun clearConversation(personaId: Long) = viewModelScope.launch {
        db.messageDao().clearConversation(personaId)
        personaRepository.setHidden(personaId, true)
    }

    /** 人设物理删除（级联清理消息/未读/水位线），从列表与设置页均可触发 */
    fun deletePersona(persona: Persona) = viewModelScope.launch {
        runCatching { personaRepository.delete(persona.id) }
    }

    /** 导出单个会话为 Markdown 文件（长按会话菜单 → 导出记录），经 FileProvider 分享 */
    fun exportConversation(context: Context, persona: Persona) = viewModelScope.launch {
        runCatching {
            val messages = db.messageDao().observeConversation(persona.id).first()
            val markdown = com.menghuan.app.data.ConversationExport.buildMarkdown(
                personaName = persona.name,
                personaTitle = persona.title,
                messages = messages,
            ) ?: return@launch
            val stamp = java.time.LocalDate.now().toString()
            com.menghuan.app.data.ShareFiles.share(
                context = context,
                fileName = "对话记录-${persona.name}-$stamp.md",
                content = markdown,
                mime = "text/markdown",
                chooserTitle = "导出记录",
            )
        }
    }

    // ===== 搜索：人设名/身份 + 消息内容。不做 delay 防抖：本地查询毫秒级，
    // 且 Robolectric 的 PAUSED 主线程时钟下 delay 永不恢复（截图测试实测），
    // 靠 cancel 旧任务保证只有最新一次输入的查询存活。 =====

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchResultUi>>(emptyList())
    val searchResults: StateFlow<List<SearchResultUi>> = _searchResults.asStateFlow()

    private var searchJob: Job? = null

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            _searchResults.value = runSearch(query.trim())
        }
    }

    private suspend fun runSearch(q: String): List<SearchResultUi> {
        val persons = personaRepository.observePersonas().first()
        val messages = runCatching { db.messageDao().searchMessages(q) }.getOrDefault(emptyList())
        val latestByPersona = messages.groupBy { it.conversationId }
            .mapValues { (_, list) -> list.maxByOrNull { it.timestamp } }
        return persons.mapNotNull { persona ->
            val nameMatch = persona.name.contains(q, ignoreCase = true) ||
                persona.title.contains(q, ignoreCase = true)
            val matched = latestByPersona[persona.id]
            if (nameMatch || matched != null) SearchResultUi(persona, matched) else null
        }
    }

    /** 会话置顶（长按会话行切换），置顶的排最前 */
    fun setPinned(personaId: Long, pinned: Boolean) = viewModelScope.launch {
        runCatching { personaRepository.setPinned(personaId, pinned) }
    }

    /** 标记已读（长按菜单）：未读点立即消除 */
    fun markConversationRead(personaId: Long) = viewModelScope.launch {
        runCatching { settingsRepository.markChatRead(personaId) }
    }

    private fun previewOf(persona: Persona, last: ChatMessage?): String {
        val msg = last ?: return "暂无消息"
        // 表情消息 content 为空，直接取会得到"你："；这里补一个可读摘要（v0.45.0）
        val content = when (MediaKind.fromLegacy(msg.mediaKind, msg.imagePath)) {
            MediaKind.STICKER -> "[表情]"
            MediaKind.CALL -> "📞 ${msg.content}" // 语音通话记录卡（v0.49.0）
            else -> msg.content.replace('\n', ' ')
        }
        return if (msg.isFromUser) "你：$content" else content
    }

    private fun timeOf(timestamp: Long?): String {
        if (timestamp == null) return ""
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(timestamp).atZone(zone)
        val today = LocalDate.now(zone)
        return when (time.toLocalDate()) {
            today -> time.format(DateTimeFormatter.ofPattern("HH:mm"))
            today.minusDays(1) -> "昨天"
            else ->
                if (time.toLocalDate().isAfter(today.minusDays(7))) weekdayLabel(time.dayOfWeek)
                else "${time.monthValue}月${time.dayOfMonth}日"
        }
    }

    private fun weekdayLabel(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MenghuanApp
                ChatListViewModel(app.personaRepository, app.settingsRepository, app.db)
            }
        }
    }
}

@Composable
fun ChatListScreen(
    onOpen: (Long) -> Unit,
    /** v0.53.5：编辑人设是独立整页，这里把 id 交出去（0 = 新建） */
    onOpenPersonaEditor: (Long) -> Unit = {},
    onSelect: ((Long) -> Unit)? = null,
    selectedPersonaId: Long = 0L,
    vm: ChatListViewModel = viewModel(factory = ChatListViewModel.Factory),
) {
    val screen by vm.screenState.collectAsState()
    val rows = screen.rows
    val loaded = screen.loaded
    val searchQuery by vm.searchQuery.collectAsState()
    val searchResults by vm.searchResults.collectAsState()
    var searchMode by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Persona?>(null) }
    val haptic = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // 列表滚动状态提前声明：顶部时搜索框常驻（标题下方），上滑离开后换成顶栏放大镜
    val listState = rememberLazyListState()
    val atTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶栏：标题 + 放大镜（无圆圈）。
        // 搜索框在列表顶部时隐藏这条图标，避免与搜索框两处入口并存（用户要求）
        NeuTopBar(
            title = "消息",
            actions = {
                if (rows.isNotEmpty() && !atTop) {
                    Icon(
                        MIcons.Search,
                        contentDescription = "搜索",
                        tint = TextPrimary,
                        modifier = Modifier
                            .size(21.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { searchMode = true },
                    )
                }
            },
        )

        if (!searchMode) {
            // 搜索行：只在列表拉到最顶部时出现；上滑离开顶部即收起（换成顶栏放大镜）。
            // 展开/收起加淡入淡出，列表内容跟着平滑推移，不再硬跳
            AnimatedVisibility(
                visible = atTop,
                enter = fadeIn(tween(200)) + expandVertically(tween(240, easing = EaseOutQuart)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(200)),
            ) {
                Box(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 10.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .neuSurface(16.dp, color = MaterialTheme.colorScheme.background, elevation = 5.dp, inset = true)
                            .neuRowPress { searchMode = true }
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            MIcons.Search,
                            contentDescription = null,
                            tint = MutedText,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("搜索", fontSize = 14.sp, color = MutedText)
                    }
                }
            }

            // 会话列表：级联淡入（每条 40ms，封顶 320ms）+ 位置重排动画；长按弹出微信式菜单
            //
            // ⚠️ 三态（v0.43.1 修"冷启动先闪新建人设"）：
            //   ① 首次数据还没到 → 骨架屏（占位行，**绝不放新建入口**）
            //   ② 数据到了但一条都没有 → 居中的「新建人设」
            //   ③ 有会话 → 真实列表
            // 以前只有 ②③，冷启动时 ② 会先闪一下。
            if (!loaded) {
                ChatListSkeleton()
            } else if (rows.isEmpty()) {
                // 人设删光后的极简空状态：虚线选中框 + 新建入口
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .figmaSelection(active = true, cornerRadius = 14.dp)
                            .neuRowPress { onOpenPersonaEditor(0L) }
                            .padding(vertical = 26.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(MIcons.Plus, contentDescription = "新增人设", tint = TextPrimary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("新建人设", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        }
                    }
                }
            } else {
                // 首次拿到列表直接落在顶部（不动画，避免长列表进页面横冲直撞）
                LaunchedEffect(rows.size) { if (rows.isNotEmpty()) listState.scrollToItem(0) }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    rows.forEachIndexed { index, row ->
                        // 会话之间的**发丝灰线**（v0.53.4 加回）：之前整批清灰线时连它一起删掉了。
                        // 从文字起始处起（16 左边距 + 46 头像 + 13 间距 = 75dp），避开头像。
                        if (index > 0) {
                            item(key = "divider-${row.persona.id}") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 75.dp, end = 16.dp)
                                        .height(1.dp)
                                        .background(NeuHairline),
                                )
                            }
                        }
                        item(key = row.persona.id) {
                            var menuOpen by remember { mutableStateOf(false) }
                            // 长按时手指在行内的位置（px），菜单就锚在这里（v0.53.4 微信式）
                            var menuAnchor by remember { mutableStateOf(Offset.Zero) }
                            Box(
                                Modifier.animateItem(
                                    fadeInSpec = tween(340, delayMillis = (index * 40).coerceAtMost(320)),
                                ),
                            ) {
                                ConversationRow(
                                    persona = row.persona,
                                    preview = row.preview,
                                    time = row.time,
                                    unread = row.showDot,
                                    selected = onSelect != null && row.persona.id == selectedPersonaId,
                                    onClick = {
                                        val open = onSelect ?: onOpen
                                        open(row.persona.id)
                                    },
                                    onLongClick = { pos ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        menuAnchor = pos
                                        menuOpen = true
                                    },
                                )
                                // 微信式长按菜单：置顶 / 导出 / 标记已读 / 删除。
                                // v0.53.4：锚点从"钉死在右下角"改成**手指按下的位置**——
                                // 锚点 Box 用 offset 挪到手指处，DropdownMenu 就贴着手指弹出；
                                // 靠近屏幕边缘时 M3 自带的定位器会把菜单夹回屏内，不会出屏。
                                Box(
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .offset {
                                            IntOffset(menuAnchor.x.roundToInt(), menuAnchor.y.roundToInt())
                                        },
                                ) {
                                    DropdownMenu(
                                        expanded = menuOpen,
                                        onDismissRequest = { menuOpen = false },
                                        // v0.53.4：菜单加一圈**灰线框**——全应用去阴影之后，白底菜单
                                        // 落在白底页面上边界糊成一片，靠这条发丝线把菜单"框"出来
                                        modifier = Modifier.border(1.dp, NeuHairline, RoundedCornerShape(14.dp)),
                                        containerColor = NeuSurface,
                                        shape = RoundedCornerShape(14.dp),
                                        shadowElevation = 0.dp, // v0.42.1 全应用去阴影
                                    ) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    if (row.persona.pinned) "取消置顶" else "置顶该聊天",
                                                    fontSize = 15.sp,
                                                    color = TextPrimary,
                                                )
                                            },
                                            onClick = {
                                                menuOpen = false
                                                vm.setPinned(row.persona.id, !row.persona.pinned)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("导出记录", fontSize = 15.sp, color = TextPrimary) },
                                            onClick = {
                                                menuOpen = false
                                                vm.exportConversation(context, row.persona)
                                            },
                                        )
                                        if (row.showDot) {
                                            DropdownMenuItem(
                                                text = { Text("标记已读", fontSize = 15.sp, color = TextPrimary) },
                                                onClick = {
                                                    menuOpen = false
                                                    vm.markConversationRead(row.persona.id)
                                                },
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("删除会话", fontSize = 15.sp, color = NeuDanger) },
                                            onClick = {
                                                menuOpen = false
                                                deleteTarget = row.persona
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            SearchOverlay(
                query = searchQuery,
                results = searchResults,
                onQueryChange = vm::onSearchQueryChange,
                onExit = { searchMode = false; vm.onSearchQueryChange("") },
                onOpen = { id ->
                    searchMode = false
                    vm.onSearchQueryChange("")
                    onOpen(id)
                },
            )
        }
    }

    // 删除会话二次确认：清空该会话消息，人设保留
    deleteTarget?.let { target ->
        AlertDialog(
            tonalElevation = 0.dp, // v0.42.1 全应用去阴影（该重载阴影来自 tonalElevation）
            onDismissRequest = { deleteTarget = null },
            containerColor = NeuSurface,
            shape = RoundedCornerShape(20.dp),
            title = { Text("删除会话", fontWeight = FontWeight.SemiBold, color = TextPrimary) },
            text = {
                Text(
                    "将删除「${target.name}」的全部聊天消息，人设保留。此操作不可恢复。",
                    color = MutedText,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearConversation(target.id)
                    deleteTarget = null
                }) { Text("删除", color = NeuDanger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消", color = MutedText) }
            },
        )
    }

    // v0.53.5：新建人设也走独立整页路由（onOpenPersonaEditor(0L)，见上面的空状态入口）
}

/**
 * 会话列表骨架屏（v0.43.1）：首次数据到达前显示的占位行。
 *
 * 目的不是"好看"，而是**避免闪出错误内容**：以前冷启动会先渲染居中的
 * 「新建人设」入口（因为 `rows` 初值是空列表），数据一到又立刻被真实列表替换，
 * 用户感受就是"先显示新建人设，然后对话列表才出现"（实测反馈的卡顿感）。
 *
 * 骨架行的形状对齐真实会话行（46dp 头像 + 两行文字），所以数据到达时位置几乎不跳。
 */
@Composable
private fun ChatListSkeleton() {
    Column(Modifier.fillMaxSize()) {
        repeat(7) { index ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 头像占位（圆）
                Box(
                    Modifier
                        .size(46.dp)
                        .background(NeuTrack, CircleShape),
                )
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.42f)
                            .height(13.dp)
                            .background(NeuTrack, RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.height(7.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.72f)
                            .height(11.dp)
                            .background(NeuTrack, RoundedCornerShape(6.dp)),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .width(34.dp)
                        .height(10.dp)
                        .background(NeuTrack, RoundedCornerShape(5.dp)),
                )
            }
            // 行间不留额外空隙：与真实列表的紧凑节奏一致
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResultUi, query: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .neuRowPress(onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeuAvatar(result.persona.avatarEmoji, size = 46.dp, ringBrush = personaBrush(result.persona.id), imagePath = result.persona.avatarImagePath)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                result.persona.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            val preview = result.matched?.content?.replace('\n', ' ') ?: result.persona.title
            Text(
                highlight(preview, query, TextPrimary),
                fontSize = 13.sp,
                color = MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(MIcons.ChevronRight, contentDescription = null, tint = FaintText, modifier = Modifier.size(14.dp))
    }
}

/** 命中片段加粗提色（大小写不敏感，取第一处） */
private fun highlight(content: String, query: String, hitColor: Color): AnnotatedString =
    buildAnnotatedString {
        append(content)
        if (query.isBlank()) return@buildAnnotatedString
        val idx = content.indexOf(query, ignoreCase = true)
        if (idx < 0) return@buildAnnotatedString
        addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = hitColor), idx, idx + query.length)
    }

/** 搜索态：输入框自动聚焦 + 实时结果（人设名/身份 + 消息内容） */
@Composable
private fun SearchOverlay(
    query: String,
    results: List<SearchResultUi>,
    onQueryChange: (String) -> Unit,
    onExit: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .neuSurface(16.dp, color = MaterialTheme.colorScheme.background, elevation = 5.dp, inset = true)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MIcons.Search, contentDescription = null, tint = MutedText, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("搜索人设或消息", fontSize = 14.sp, color = MutedText)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary),
                        singleLine = true,
                        cursorBrush = SolidColor(NeuAccent),
                    )
                }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "×",
                        fontSize = 16.sp,
                        color = MutedText,
                        modifier = Modifier
                            .clickable { onQueryChange("") }
                            .padding(2.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            NeuTextButton("取消", onClick = onExit, color = TextPrimary)
        }

        when {
            query.isBlank() -> Spacer(Modifier.fillMaxSize())
            results.isEmpty() -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("没有匹配结果", fontSize = 13.sp, color = FaintText)
                Spacer(Modifier.height(10.dp))
                Text(
                    "清除搜索",
                    fontSize = 13.sp,
                    color = NeuAccent,
                    modifier = Modifier.clickable { onQueryChange("") },
                )
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(results, key = { _, r -> r.persona.id }) { _, result ->
                    SearchResultRow(result, query) { onOpen(result.persona.id) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    persona: Persona,
    preview: String,
    time: String,
    unread: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit,
    /** 长按回调带上**手指在行内的位置**（px）——菜单要出现在手指那儿，不是固定角落 */
    onLongClick: (Offset) -> Unit,
) {
    val dotScale by animateFloatAsState(if (unread) 1f else 0f, tween(220, easing = EaseOutQuart))
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, NeuPressSpec)
    // 只观察、不消费指针事件：与下面的 combinedClickable 并存，单纯记下手指最近的位置
    var pressPos by remember { mutableStateOf(Offset.Zero) }
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.lastOrNull()?.let { pressPos = it.position }
                    }
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = { onLongClick(pressPos) },
            )
            // 双栏模式：当前右栏会话的列表行加凹陷灰底（选中标识）
            .then(if (selected) Modifier.background(NeuTrack) else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeuAvatar(persona.avatarEmoji, size = 46.dp, ringBrush = personaBrush(persona.id), imagePath = persona.avatarImagePath)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                persona.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                preview,
                fontSize = 13.sp,
                color = MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (persona.pinned) "📌 $time" else time,
                fontSize = 11.sp,
                color = if (persona.pinned) NeuAccent else FaintText,
            )
            // 未读点 = 实心圆 + 淡光晕（与预览 box-shadow 0 0 0 3px 一致）
            Box(
                Modifier.size(9.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (dotScale > 0.01f) {
                    Box(
                        Modifier
                            .scale(dotScale)
                            .size(15.dp)
                            .background(NeuAccent.copy(alpha = 0.18f), CircleShape),
                    )
                    Box(
                        Modifier
                            .scale(dotScale)
                            .size(9.dp)
                            .background(NeuAccent, CircleShape),
                    )
                }
            }
        }
    }
}
