package com.daoshi.app.ui.chat

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.daoshi.app.MentorApp
import com.daoshi.app.data.PersonaRepository
import com.daoshi.app.data.SettingsRepository
import com.daoshi.app.data.db.AppDatabase
import com.daoshi.app.data.model.ChatMessage
import com.daoshi.app.data.model.Persona
import com.daoshi.app.ui.common.EaseOutQuart
import com.daoshi.app.ui.common.NeuAvatar
import com.daoshi.app.ui.common.NeuCircleButton
import com.daoshi.app.ui.common.NeuTopBar
import com.daoshi.app.ui.common.NeuTextButton
import com.daoshi.app.ui.common.NeuPressSpec
import com.daoshi.app.ui.common.neuRowPress
import org.json.JSONArray
import org.json.JSONObject
import com.daoshi.app.ui.common.neuSurface
import com.daoshi.app.ui.persona.PersonaEditorSheet
import com.daoshi.app.ui.settings.ApiConfigSheet
import com.daoshi.app.ui.settings.DataPrivacySheet
import com.daoshi.app.ui.settings.DataStats
import com.daoshi.app.ui.settings.NotificationSettingsSheet
import com.daoshi.app.ui.settings.SettingsSheet
import com.daoshi.app.ui.settings.AboutSheet
import com.daoshi.app.ui.theme.FaintText
import com.daoshi.app.ui.theme.MIcons
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.TextPrimary
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
 * 会话列表页（新拟态）：主入口。
 * - 每个 persona 是一个独立会话，点行进入单聊（onOpen）
 * - 预览 = MessageDao 最后一条消息；未读点 = 导师有新回复且晚于上次阅读时刻
 * - 设置入口在本页右上角（仅此一处）；＋ 直接新增人设
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
            personas.map { persona ->
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

    fun savePersona(persona: Persona) = viewModelScope.launch {
        personaRepository.save(persona)
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

    // ===== 设置面板补全：通知开关 / 数据统计与清理 =====

    val notificationsEnabled: StateFlow<Boolean> = settingsRepository.notificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setNotificationsEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setNotificationsEnabled(enabled)
    }

    /** 数据与隐私面板：各类数据条数 */
    val dataStats: StateFlow<DataStats> = combine(
        db.messageDao().observeCount(),
        db.taskDao().observeAll().map { it.size.toLong() },
        personaRepository.observePersonas().map { it.size.toLong() },
        db.profileFactDao().observeAll().map { it.size.toLong() },
    ) { messages, tasks, personas, facts ->
        DataStats(messages, tasks, personas, facts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataStats())

    /** 清除全部聊天记录（人设与任务保留）；清除后各会话回到种子演示消息 */
    fun clearAllChats() = viewModelScope.launch {
        runCatching { db.messageDao().deleteAll() }
    }

    /** 导出全部数据为 JSON，走系统分享（数据自主权：仅存本机，但随时可带走） */
    fun exportData(context: Context) = viewModelScope.launch {
        runCatching {
            val json = buildExportJson()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "导师 · 数据导出")
                putExtra(Intent.EXTRA_TEXT, json)
            }
            context.startActivity(Intent.createChooser(send, "导出数据"))
        }
    }

    private suspend fun buildExportJson(): String {
        val personas = personaRepository.observePersonas().first()
        val messages = db.messageDao().getAll()
        val tasks = db.taskDao().observeAll().first()
        val facts = db.profileFactDao().observeAll().first()
        val insights = db.aiInsightDao().observeLatest().first()
        val root = JSONObject()
        root.put("exportedAt", System.currentTimeMillis())
        root.put(
            "personas",
            JSONArray().apply {
                personas.forEach {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("name", it.name)
                            .put("title", it.title)
                            .put("systemPrompt", it.systemPrompt)
                            .put("tone", it.tone)
                    )
                }
            },
        )
        val nameById = personas.associate { it.id to it.name }
        root.put(
            "messages",
            JSONArray().apply {
                messages.forEach {
                    put(
                        JSONObject()
                            .put("persona", nameById[it.conversationId] ?: it.conversationId)
                            .put("from", if (it.isFromUser) "user" else "mentor")
                            .put("content", it.content)
                            .put("timestamp", it.timestamp)
                    )
                }
            },
        )
        root.put(
            "tasks",
            JSONArray().apply {
                tasks.forEach {
                    put(
                        JSONObject()
                            .put("title", it.title)
                            .put("note", it.note)
                            .put("dueAt", it.dueAt)
                            .put("remindAt", it.remindAt ?: JSONObject.NULL)
                            .put("isDone", it.isDone)
                            .put("createdBy", it.createdBy)
                    )
                }
            },
        )
        root.put(
            "profileFacts",
            JSONArray().apply {
                facts.forEach {
                    put(
                        JSONObject()
                            .put("key", it.key)
                            .put("value", it.value)
                            .put("source", it.source)
                    )
                }
            },
        )
        root.put(
            "aiInsights",
            JSONArray().apply {
                insights.forEach {
                    put(
                        JSONObject()
                            .put("title", it.title)
                            .put("detail", it.detail)
                            .put("createdAt", it.createdAt)
                    )
                }
            },
        )
        return root.toString(2)
    }

    private fun previewOf(persona: Persona, last: ChatMessage?): String {
        val content = last?.content?.replace('\n', ' ') ?: return persona.title
        return if (last.isFromUser) "你：$content" else content
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
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MentorApp
                ChatListViewModel(app.personaRepository, app.settingsRepository, app.db)
            }
        }
    }
}

@Composable
fun ChatListScreen(
    onOpen: (Long) -> Unit,
    onManagePersonas: () -> Unit,
    vm: ChatListViewModel = viewModel(factory = ChatListViewModel.Factory),
) {
    val rows by vm.rows.collectAsState()
    val searchQuery by vm.searchQuery.collectAsState()
    val searchResults by vm.searchResults.collectAsState()
    val notificationsEnabled by vm.notificationsEnabled.collectAsState()
    val dataStats by vm.dataStats.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showApiConfig by remember { mutableStateOf(false) }
    var showNewPersona by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var showDataPrivacy by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶栏：标题 + 新增人设 + 设置（仅此一处设置入口）
        NeuTopBar(
            title = "消息",
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeuCircleButton(
                        onClick = { showNewPersona = true },
                        size = 38.dp,
                    ) {
                        Icon(MIcons.Plus, contentDescription = "新增人设", tint = TextPrimary, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    NeuCircleButton(
                        onClick = { showSettings = true },
                        size = 38.dp,
                    ) {
                        Icon(MIcons.Sliders, contentDescription = "设置", tint = TextPrimary, modifier = Modifier.size(19.dp))
                    }
                }
            },
        )

        if (!searchMode) {
            // 搜索行：点击进入搜索态（凹陷胶囊，二期起为真实搜索）
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

            // 会话列表：级联淡入（每条 40ms，封顶 320ms）+ 位置重排动画；长按置顶
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(rows, key = { _, row -> row.persona.id }) { index, row ->
                    Box(Modifier.animateItem(fadeInSpec = tween(340, delayMillis = (index * 40).coerceAtMost(320)))) {
                        ConversationRow(
                            persona = row.persona,
                            preview = row.preview,
                            time = row.time,
                            unread = row.showDot,
                            onClick = { onOpen(row.persona.id) },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val target = !row.persona.pinned
                                vm.setPinned(row.persona.id, target)
                                Toast.makeText(
                                    context,
                                    if (target) "已置顶，会话将排在最前" else "已取消置顶",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
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

    if (showSettings) {
        SettingsSheet(
            onDismiss = { showSettings = false },
            onApiConfig = {
                showSettings = false
                showApiConfig = true
            },
            onPersonaManage = {
                showSettings = false
                onManagePersonas()
            },
            onNotifications = {
                showSettings = false
                showNotifications = true
            },
            onDataPrivacy = {
                showSettings = false
                showDataPrivacy = true
            },
            onAbout = {
                showSettings = false
                showAbout = true
            },
        )
    }
    if (showNotifications) {
        NotificationSettingsSheet(
            enabled = notificationsEnabled,
            onToggle = { vm.setNotificationsEnabled(it) },
            onDismiss = { showNotifications = false },
        )
    }
    if (showDataPrivacy) {
        DataPrivacySheet(
            stats = dataStats,
            onClearChats = { vm.clearAllChats() },
            onExport = { vm.exportData(context) },
            onDismiss = { showDataPrivacy = false },
        )
    }
    if (showAbout) {
        AboutSheet(onDismiss = { showAbout = false })
    }
    if (showApiConfig) {
        ApiConfigSheet(onDismiss = { showApiConfig = false })
    }
    if (showNewPersona) {
        PersonaEditorSheet(
            persona = null,
            onSave = { vm.savePersona(it); showNewPersona = false },
            onDismiss = { showNewPersona = false },
        )
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
        NeuAvatar(result.persona.avatarEmoji, size = 46.dp)
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
                highlight(preview, query),
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
private fun highlight(content: String, query: String): AnnotatedString = buildAnnotatedString {
    append(content)
    if (query.isBlank()) return@buildAnnotatedString
    val idx = content.indexOf(query, ignoreCase = true)
    if (idx < 0) return@buildAnnotatedString
    addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = TextPrimary), idx, idx + query.length)
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
            query.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("搜索人设名字或聊天内容", fontSize = 13.sp, color = FaintText)
            }
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val dotScale by animateFloatAsState(if (unread) 1f else 0f, tween(220, easing = EaseOutQuart))
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, NeuPressSpec)
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeuAvatar(persona.avatarEmoji, size = 46.dp)
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
