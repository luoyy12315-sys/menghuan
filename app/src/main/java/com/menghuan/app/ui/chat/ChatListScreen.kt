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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.menghuan.app.ui.theme.BrandPink
import com.menghuan.app.ui.theme.BrandSalmon
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
    /**
     * 心情计（v0.77.0：爱心 + 粉色进度条，进度 = 心情好坏，由愉悦度归一化而来）。
     *
     * 负数 = 这个角色还没算过心情（没聊过、也没到过主动关心的检查点），
     * 此时**不占位** —— 画一条空的进度条会让用户以为她在生气，
     * 而真相只是"数据还没产生"（同 SessionList 的「未加载 vs 空」纪律）。
     */
    val moodProgress: Float = -1f,
    /** 心情的无障碍说明（界面上不显示，只给读屏） */
    val moodLabel: String = "",
)

/**
 * 会话排序（v0.77.0，用户点名「最新的消息放在最上面」）。
 *
 * 纯函数，JVM 可测（见 ChatListOrderTest）。规则：
 *   ① **置顶的永远在最前**（置顶组内部再按最后消息时间倒序）；
 *   ② 其余按**最后一条消息的时间**倒序 —— 最近聊过的浮到最上；
 *   ③ 没有任何消息的会话（从没聊过 / 被清空）统一沉到底部，内部按创建时间**由新到旧**
 *      （`createdAt DESC`），避免一串空会话插在活跃会话中间把列表冲散。
 *
 * ⚠️ 不能把顺序交给 SQL 的 `ORDER BY` 一次做完：置顶/最后消息/创建时间分属两张表
 * （personas 与 messages），这里在内存里合并是既有写法，别改成联表查询。
 *
 * @param personas 已过滤掉"隐藏且无消息"的会话
 * @param lastByConversation personaId → 该会话最后一条消息（没有则不在此表里）
 */
internal fun orderConversations(
    personas: List<Persona>,
    lastByConversation: Map<Long, ChatMessage>,
): List<Persona> {
    // 最后消息时间：没有消息的给 Long.MIN_VALUE，保证沉底且不影响同组内其它比较
    fun lastAt(p: Persona): Long = lastByConversation[p.id]?.timestamp ?: Long.MIN_VALUE
    return personas.sortedWith(
        compareByDescending<Persona> { it.pinned }
            // 有消息的排前面：先按"有没有消息"分桶（有=0 无=1），再在桶内比时间
            .thenBy { if (lastAt(it) == Long.MIN_VALUE) 1 else 0 }
            .thenByDescending { lastAt(it) }
            // 无消息的一组内部按创建时间由新到旧
            .thenByDescending { it.createdAt },
    )
}

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
            // v0.74.0：全部角色的五轴状态（一次读全表，见 SettingsRepository.companionStates）
            settingsRepository.companionStates,
        ) { personas, lastMessages, readMarks, companionStates ->
            val byConversation = lastMessages.associateBy { it.conversationId }
            // 微信式已删除会话：清空消息后从列表隐藏；新消息到达（预览重现）即恢复显示
            //
            // v0.77.0（用户点名「最新的消息放在最上面」）：排序规则从"创建时间从旧到新"
            // 改成**按最后一条消息的时间倒序** —— 最近聊过的会话浮到最上面。
            // 规则由纯函数 [orderConversations] 决定（可单测，见 ChatListOrderTest）：
            //   ① 置顶的永远在最前（置顶内部再按最后消息时间倒序）；
            //   ② 其余按"最后消息时间"倒序；
            //   ③ 没有消息的会话（从没聊过 / 被清空）沉到最底，内部按创建时间由新到旧，
            //      免得一堆空会话插在活跃会话中间。
            val visible = personas.filter { !(it.hidden && byConversation[it.id] == null) }
            orderConversations(visible, byConversation)
                .map { persona ->
                    val last = byConversation[persona.id]
                    // 心情表情：**只读已落盘的状态，不在列表里实时 tick**。
                    // 列表每帧 tick 是纯浪费（状态是几小时尺度的慢变量），而且 tick 会写盘，
                    // 在这里写等于"用户一滑列表就在改数据"。状态的真实推进归 ProactiveChat
                    // 与 ChatViewModel 的既有检查点。
                    val mood = companionStates[persona.id]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { com.menghuan.app.data.CompanionLogic.badgeOf(
                            com.menghuan.app.data.CompanionLogic.decode(it)
                        ) }
                    ConversationRowUi(
                        persona = persona,
                        preview = previewOf(persona, last),
                        time = timeOf(last?.timestamp),
                        showDot = last != null && !last.isFromUser &&
                            last.timestamp > (readMarks[persona.id] ?: 0L),
                        moodProgress = mood?.progress ?: -1f,
                        moodLabel = mood?.label ?: "",
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
                // v0.82.0：记忆待审系统气泡是界面提示，导出的对话记录里不含它
                .filter { it.mediaKind != com.menghuan.app.data.model.MediaKind.MEMORY_NOTICE.name }
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

    // 搜索整条链路（输入框 / 结果流 / runSearch）在 v0.56.8 第二批**整体删除** —— 用户点名：
    // 消息页不要搜索，顶栏放大镜也不要。设置页那边的搜索不受影响。

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
        // 媒体消息的预览一律用类别标签，不摊开它的文本（v0.57）：
        // 图片消息的 content 是图注 / AI 生图的**英文提示词**，语音条的 content 是转写文字，
        // 文件消息的 content 是抽出来的**整篇正文**——直接把 content 打上去，用户看到的就是
        // "一条照片预览写着英文""一条语音预览写着整段话""一条文件预览糊了一大段字"。
        // 标签口径与聊天记录页（ChatHistoryScreen）一致：[图片] / [语音] / [表情] / [文件]。
        val content = when (MediaKind.fromLegacy(msg.mediaKind, msg.imagePath)) {
            MediaKind.STICKER -> "[表情]"
            MediaKind.IMAGE -> "[图片]"
            MediaKind.AUDIO -> "[语音]"
            MediaKind.FILE -> "[文件]"
            MediaKind.CALL -> {
                // 通话记录卡（v0.49.0；v0.60 起按类型换图标）：内容里已经写明是哪种通话
                if (msg.content.trimStart().startsWith("视频通话")) "📹 ${msg.content}" else "📞 ${msg.content}"
            }
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
    var deleteTarget by remember { mutableStateOf<Persona?>(null) }
    val haptic = LocalHapticFeedback.current
    val context = androidx.compose.ui.platform.LocalContext.current

    val listState = rememberLazyListState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶栏：只有标题（v0.56.8 第二批：消息页不做搜索，放大镜一并去掉）
        NeuTopBar(title = "消息")

        // v0.56.8 第二批（用户点名）：消息页**不做搜索** —— 顶栏放大镜、搜索框、
        // 搜索态整条链路一并去掉，进来就是干净的一列会话。
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
                        item(key = "divider-${row.persona.id}", contentType = "divider") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 75.dp, end = 16.dp)
                                    .height(1.dp)
                                    .background(NeuHairline),
                            )
                        }
                    }
                    item(key = row.persona.id, contentType = "conversation") {
                        var menuOpen by remember { mutableStateOf(false) }
                        // 长按时手指在行内的位置（px），菜单就锚在这里（v0.53.4 微信式）
                        var menuAnchor by remember { mutableStateOf(Offset.Zero) }
                        Box(
                            // v0.55.0：淡入从 340ms + 最多 320ms 延迟压到 200ms + 最多 120ms。
                            // 原值在**滚动**时同样生效 —— 新进视口的条目要等最多 320ms 才开始淡入，
                            // 快滑时就是"一片条目陆续浮现"（对话页 v0.41.4 正是因此把淡入整个关掉）。
                            // 留一点点错峰是给首屏用的：120ms 内铺完，快滑时基本无感。
                            Modifier.animateItem(
                                fadeInSpec = tween(200, delayMillis = (index * 16).coerceAtMost(120)),
                            ),
                        ) {
                            ConversationRow(
                                persona = row.persona,
                                preview = row.preview,
                                time = row.time,
                                unread = row.showDot,
                                moodProgress = row.moodProgress,
                                moodLabel = row.moodLabel,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    persona: Persona,
    preview: String,
    time: String,
    unread: Boolean,
    /** 心情计（v0.77.0：爱心 + 粉色进度条）；progress < 0 = 没算过，不占位 */
    moodProgress: Float = -1f,
    moodLabel: String = "",
    selected: Boolean = false,
    onClick: () -> Unit,
    /** 长按回调带上**手指在行内的位置**（px）——菜单要出现在手指那儿，不是固定角落 */
    onLongClick: (Offset) -> Unit,
) {
    val dotScale by animateFloatAsState(if (unread) 1f else 0f, tween(220, easing = EaseOutQuart))
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, NeuPressSpec)
    // 只观察、不消费指针事件：与下面的 combinedClickable 并存，单纯记下手指最近的位置。
    //
    // v0.55.0：位置存进**普通 FloatArray**，不再用 mutableStateOf ——
    // 原来每个指针事件都写一次 state，而滑动时一帧就有好几个事件，
    // 于是列表每一行的可见项都在按帧重组。这份坐标只在**长按那一刻**读一次
    // （菜单要锚在手指处），压根不需要可观测。
    val pressPos = remember { floatArrayOf(0f, 0f) }
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.lastOrNull()?.let {
                            pressPos[0] = it.position.x
                            pressPos[1] = it.position.y
                        }
                    }
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = { onLongClick(Offset(pressPos[0], pressPos[1])) },
            )
            // 双栏模式：当前右栏会话的列表行加凹陷灰底（选中标识）
            .then(if (selected) Modifier.background(NeuTrack) else Modifier)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeuAvatar(persona.avatarEmoji, size = 46.dp, ringBrush = personaBrush(persona.id), imagePath = persona.avatarImagePath)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            // 名字 + 心情表情（v0.74.0）：表情紧贴在名字右边，名字过长时**先挤压表情之外的
            // 空间**（表情自身 weight 不参与，避免把表情压扁）——用 Row + 名字 Text 带 weight(1f, fill=false)
            // 让名字自己按需占宽，表情始终留在末尾。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    persona.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (moodProgress >= 0f) {
                    Spacer(Modifier.width(5.dp))
                    MoodGauge(progress = moodProgress, label = moodLabel)
                }
            }
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
            // 未读点 = 实心红点 + 淡红光晕（v0.62.0 用户点名：**黑点看着像"选中/已读"的记号，
            // 未读要的就是那一点红**）。用的是品牌点缀色 BrandSalmon，不是 NeuDanger ——
            // 那个色在 App 里是"删除/危险"语义，拿来当未读会让人以为出了事。
            Box(
                Modifier.size(9.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (dotScale > 0.01f) {
                    Box(
                        Modifier
                            .scale(dotScale)
                            .size(15.dp)
                            .background(BrandSalmon.copy(alpha = 0.18f), CircleShape),
                    )
                    Box(
                        Modifier
                            .scale(dotScale)
                            .size(9.dp)
                            .background(BrandSalmon, CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * 心情计（v0.77.0）：一颗粉色爱心 + 右边一条粉色进度条，进度 = 心情好坏。
 *
 * 取代 v0.74.0 的心情 emoji（用户拍板：表情包太吵，而且同一颗 🥺 在不同人手机上字形差很多，
 * 线性进度条在任何设备上都是同一个形状）。爱心本身不带信息（它就是"这是心情"的图例），
 * 真正读数在条子上：愉悦度 valence 归一化到 0~1，0 = 心情最差、1 = 最好。
 *
 * 尺寸按"名字旁边半秒视线"定：心 13dp、条 40×4dp 圆角 pill，轨道用浅灰
 * （与全应用轨道同一颗灰），填充 [BrandPink] —— 粉在这里是"心情"语义，不与危险红（NeuDanger）混。
 * 读屏念"心情：xxx"，与原 emoji 的口径一致。
 */
@Composable
private fun MoodGauge(progress: Float, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "心情：$label" },
    ) {
        Icon(
            MIcons.Heart,
            contentDescription = null,
            tint = BrandPink,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(width = 40.dp, height = 4.dp)
                .background(NeuTrack, RoundedCornerShape(999.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(BrandPink, RoundedCornerShape(999.dp)),
            )
        }
    }
}
