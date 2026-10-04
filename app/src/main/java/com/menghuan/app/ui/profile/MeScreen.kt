package com.menghuan.app.ui.profile

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.DataBackup
import com.menghuan.app.data.PersonaRepository
import com.menghuan.app.data.ProfileRepository
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.AiInsight
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.ProfileFact
import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.chat.ImageViewerOverlay
import com.menghuan.app.ui.chat.rememberLocalBitmap
import com.menghuan.app.ui.settings.SettingsPage
import com.menghuan.app.ui.settings.SettingsRoutes
import com.menghuan.app.ui.settings.DataStats
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 编辑弹层的草稿行：id = null 表示新增 */
data class DraftFact(val id: Long?, val key: String, val value: String)

/**
 * 「我的」页 VM：设置链（通知开关 / 数据统计 / 导出导入 / 清记录）与档案编辑，
 * v0.21.0 起从会话列表页迁入（设置入口唯一化到本页）。
 */
class MeViewModel(
    private val profileRepository: ProfileRepository,
    private val personaRepository: PersonaRepository,
    private val settingsRepository: SettingsRepository,
    private val db: AppDatabase,
) : ViewModel() {

    /** 基础档案（编辑弹层真数据源；保存后注入聊天 system prompt） */
    val facts: StateFlow<List<ProfileFact>> = profileRepository.observeFacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 档案编辑保存：删被移除的行，非空行 upsert，保留已有来源标记 */
    fun saveEdits(items: List<DraftFact>, removedIds: List<Long>) = viewModelScope.launch {
        removedIds.forEach { profileRepository.delete(it) }
        items.forEach { draft ->
            val key = draft.key.trim()
            val value = draft.value.trim()
            if (key.isBlank() && value.isBlank()) return@forEach
            val existing = draft.id?.let { profileRepository.getFact(it) }
            profileRepository.save(
                ProfileFact(
                    id = draft.id ?: 0,
                    key = key.ifBlank { value },
                    value = value,
                    source = existing?.source ?: "manual",
                )
            )
        }
    }

    // ===== 设置链：通知开关 / 数据统计与清理 / 导出导入 =====

    val notificationsEnabled: StateFlow<Boolean> = settingsRepository.notificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setNotificationsEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setNotificationsEnabled(enabled)
    }

    val dailySummaryEnabled: StateFlow<Boolean> = settingsRepository.dailySummaryEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setDailySummaryEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setDailySummaryEnabled(enabled)
    }

    /** AI 周报生成提醒（默认关；生成在 Receiver 侧，开关只管通知） */
    val weeklyNotifyEnabled: StateFlow<Boolean> = settingsRepository.weeklyReportNotifyEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setWeeklyReportNotifyEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setWeeklyReportNotifyEnabled(enabled)
    }

    /** 数据与隐私面板：各类数据条数 */
    val dataStats: StateFlow<DataStats> = combine(
        db.messageDao().observeCount(),
        db.taskDao().observeAll().map { it.size.toLong() },
        personaRepository.observePersonas().map { it.size.toLong() },
        db.profileFactDao().observeAll().map { it.size.toLong() },
        db.aiWeeklyReportDao().observeAll().map { it.size.toLong() },
    ) { messages, tasks, personas, facts, reports ->
        DataStats(messages, tasks, personas, facts, reports)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataStats())

    /**
     * 清除全部数据（v0.36.5，原「清除全部聊天记录」升级）：
     * 删 聊天消息 / AI 洞察 / AI 周报 / 用户档案 / 任务 / 打卡记录，
     * 并级联清理 任务闹钟 / 提炼水位线 / 未读标记 / 草稿 / 待展示任务建议。
     * **保留**：人设、API 配置（对话+生图）、提示词覆盖、主题、通知开关、会话级接口覆盖。
     */
    fun clearAllData(context: android.content.Context) = viewModelScope.launch {
        runCatching {
            val app = context.applicationContext as MenghuanApp
            // 闹钟先取消：清库后无法再枚举任务
            val tasks = db.taskDao().observeAll().first()
            tasks.forEach { com.menghuan.app.notify.AlarmScheduler.cancel(context, it.id, it.title) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                db.messageDao().deleteAll()
                db.aiInsightDao().deleteAll()
                db.aiWeeklyReportDao().deleteAll()
                db.profileFactDao().deleteAll()
                db.taskCompletionDao().deleteAll()
                db.taskDao().deleteAll()
                // 聊天发图与 AI 生成图一并删掉：消息表清了，图留着就是孤儿文件（v0.38.0）
                runCatching { java.io.File(app.filesDir, "chat_images").deleteRecursively() }
                runCatching { java.io.File(app.filesDir, "images").deleteRecursively() }
            }
            settingsRepository.clearConversationScopedKeys()
            app.taskSuggestionHub.clear()
            com.menghuan.app.widget.TodayWidgetRefresher.refresh(context)
        }
    }

    /**
     * 一键重置（恢复出厂）：逐任务取消闹钟 → 清空全部数据库表 → 清空全部设置
     * （API 配置/开关/未读键/水位线/草稿/提示词覆盖）→ 重新播内置应用助手 → 刷小组件。
     */
    fun resetAll(context: android.content.Context) = viewModelScope.launch {
        runCatching {
            val app = context.applicationContext as MenghuanApp
            // 闹钟先取消：清库后无法再枚举任务
            val tasks = db.taskDao().observeAll().first()
            tasks.forEach { com.menghuan.app.notify.AlarmScheduler.cancel(context, it.id, it.title) }
            com.menghuan.app.notify.AlarmScheduler.cancelDailySummary(context)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                app.db.clearAllTables() // Room 要求非主线程
                runCatching { java.io.File(app.filesDir, "chat_images").deleteRecursively() }
                runCatching { java.io.File(app.filesDir, "images").deleteRecursively() }
            }
            settingsRepository.resetAll()
            personaRepository.ensureAssistant()
            com.menghuan.app.widget.TodayWidgetRefresher.refresh(context)
        }
    }

    /** 导出全部数据为 JSON 文件（含周报；不含 API 密钥），经 FileProvider 分享 */
    fun exportData(context: android.content.Context) = viewModelScope.launch {
        runCatching {
            val json = DataBackup.buildExportJson(
                personas = personaRepository.observePersonas().first(),
                messages = db.messageDao().getAll(),
                tasks = db.taskDao().observeAll().first(),
                facts = db.profileFactDao().observeAll().first(),
                insights = db.aiInsightDao().observeLatest().first(),
                weeklyReports = db.aiWeeklyReportDao().observeAll().first(),
            )
            val stamp = java.time.LocalDate.now().toString()
            com.menghuan.app.data.ShareFiles.share(
                context = context,
                fileName = "menghuan-export-$stamp.json",
                content = json,
                mime = "application/json",
                chooserTitle = "导出数据",
            )
        }
    }

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()

    /**
     * 合并导入（非破坏性）：人设/档案/洞察/周报按各自键去重跳过，消息与任务追加，
     * 绝不删除现有数据。消息按人设名映射到现有/新建会话，未知人设的消息跳过。
     */
    fun importData(context: android.content.Context, uri: Uri) = viewModelScope.launch {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("无法读取文件")
            val data = DataBackup.parseImport(text)
                ?: throw IllegalStateException("文件格式无效")
            var personaCount = 0
            var msgCount = 0
            var taskCount = 0
            var factCount = 0
            var insightCount = 0
            var reportCount = 0
            val nameToId = personaRepository.observePersonas().first()
                .associate { it.name to it.id }.toMutableMap()

            data.personas.forEach { p ->
                if (nameToId.containsKey(p.name)) return@forEach
                val id = personaRepository.save(
                    com.menghuan.app.data.model.Persona(
                        name = p.name,
                        title = p.title,
                        avatarEmoji = p.avatarEmoji,
                        systemPrompt = p.systemPrompt,
                        tone = p.tone,
                    )
                )
                nameToId[p.name] = id
                personaCount++
            }

            data.messages.forEach { m ->
                val conversationId = nameToId[m.personaName] ?: return@forEach
                db.messageDao().insert(
                    ChatMessage(
                        conversationId = conversationId,
                        isFromUser = m.isFromUser,
                        content = m.content,
                        timestamp = m.timestamp,
                    )
                )
                msgCount++
            }

            data.tasks.forEach { t ->
                db.taskDao().upsert(
                    TaskItem(
                        title = t.title,
                        note = t.note,
                        dueAt = t.dueAt,
                        remindAt = t.remindAt,
                        isDone = t.isDone,
                        repeatDaily = t.repeatDaily,
                        createdBy = t.createdBy,
                    )
                )
                taskCount++
            }

            data.profileFacts.forEach { f ->
                if (db.profileFactDao().getByKey(f.key) != null) return@forEach
                db.profileFactDao().upsert(ProfileFact(key = f.key, value = f.value))
                factCount++
            }

            data.aiInsights.forEach { i ->
                if (db.aiInsightDao().getByTitle(i.title) != null) return@forEach
                db.aiInsightDao().insert(AiInsight(title = i.title, detail = i.detail, createdAt = i.createdAt))
                insightCount++
            }

            data.aiWeeklyReports.forEach { r ->
                if (db.aiWeeklyReportDao().getByWeekStart(r.weekStart) != null) return@forEach
                db.aiWeeklyReportDao().upsert(
                    com.menghuan.app.data.model.AiWeeklyReport(
                        weekStart = r.weekStart,
                        weekEnd = r.weekEnd,
                        content = r.content,
                        createdAt = r.createdAt,
                    )
                )
                reportCount++
            }

            _importMessage.value = "导入完成：人设 $personaCount · 消息 $msgCount · 任务 $taskCount · " +
                "档案 $factCount · 洞察 $insightCount" + if (reportCount > 0) " · 周报 $reportCount" else ""
        }.onFailure {
            _importMessage.value = "导入失败：${it.message ?: "文件格式无效"}"
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MenghuanApp
                MeViewModel(app.profileRepository, app.personaRepository, app.settingsRepository, app.db)
            }
        }
    }
}

/**
 * 「我的」页（第 4 个 tab，类微信「我」）：人设管理 / 基础档案 / 设置 三组行卡。
 * 设置链（API 配置 / 通知与提醒 / 数据与隐私 / 关于）的唯一入口在本页。
 */
@Composable
fun MeScreen(
    onManagePersonas: () -> Unit,
    /** v0.51.0：「我的」这条线全部改成全屏页，行卡点击一律交路由名给 NavHost */
    onOpen: (String) -> Unit,
    vm: MeViewModel = viewModel(factory = MeViewModel.Factory),
) {
    val facts by vm.facts.collectAsState()
    val notificationsEnabled by vm.notificationsEnabled.collectAsState()
    val dailySummaryEnabled by vm.dailySummaryEnabled.collectAsState()
    val weeklyNotifyEnabled by vm.weeklyNotifyEnabled.collectAsState()
    val dataStats by vm.dataStats.collectAsState()
    val importMessage by vm.importMessage.collectAsState()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NeuTopBar(title = "我的")

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val rows = listOf<@Composable () -> Unit>(
                {
                    NeuSheetRow(
                        icon = MIcons.Person,
                        title = "人设管理",
                        subtitle = "名字、身份、设定词 · 可增删改",
                        onClick = onManagePersonas,
                    )
                },
                {
                    NeuSheetRow(
                        icon = MIcons.FileText,
                        title = "基础档案",
                        subtitle = "注入聊天的用户画像 · 仅在此维护",
                        onClick = { onOpen(SettingsRoutes.PROFILE) },
                    )
                },
                {
                    NeuSheetRow(
                        icon = MIcons.ImageIcon,
                        title = "AI 图库",
                        subtitle = "AI 生成的图片合集 · 点开查看",
                        onClick = { onOpen(SettingsRoutes.GALLERY) },
                    )
                },
                {
                    // v0.45.0：AI 提示词从设置面板提出来，单独成一级行卡
                    NeuSheetRow(
                        icon = MIcons.ChatBubble,
                        title = "AI 提示词",
                        subtitle = "人设设定词 · 基础提示词 · 档案注入说明",
                        onClick = { onOpen(SettingsRoutes.PROMPTS) },
                    )
                },
                {
                    NeuSheetRow(
                        icon = MIcons.Sliders,
                        title = "设置",
                        subtitle = "API · 语音 · 外观 · 通知 · 数据与隐私 · 关于",
                        onClick = { onOpen(SettingsRoutes.SETTINGS) },
                    )
                },
            )
            // 平板宽版式：行卡两列网格；手机单列
            if (com.menghuan.app.ui.common.isExpandedWindow()) {
                rows.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { row -> Box(Modifier.weight(1f)) { row() } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                rows.forEach { row -> row() }
            }
        }
    }

}

/**
 * 我的 · 基础档案（v0.51.0 起全屏页）：每行 key/value 可改、可增删，
 * 保存后写入 Room 并注入聊天 system prompt。
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    vm: MeViewModel = viewModel(factory = MeViewModel.Factory),
) {
    val facts by vm.facts.collectAsState()
    val drafts = remember { mutableStateListOf<DraftFact>() }
    val removedIds = remember { mutableStateListOf<Long>() }
    // 档案是 Flow，首帧还是空表；只在**第一次拿到真实数据**时播种一次，
    // 之后绝不再覆盖——否则用户正在编辑的内容会被 Flow 的下一帧冲掉。
    var seeded by remember { mutableStateOf(false) }
    LaunchedEffect(facts) {
        if (!seeded && facts.isNotEmpty()) {
            facts.forEach { drafts.add(DraftFact(it.id, it.key, it.value)) }
            seeded = true
        }
    }

    SettingsPage(title = "基础档案", onBack = onBack) {
        Spacer(Modifier.height(14.dp))
        drafts.forEachIndexed { index, draft ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeuTextField(
                    value = draft.key,
                    onValueChange = { drafts[index] = draft.copy(key = it) },
                    placeholder = "项目",
                    modifier = Modifier.width(104.dp),
                )
                Spacer(Modifier.width(8.dp))
                NeuTextField(
                    value = draft.value,
                    onValueChange = { drafts[index] = draft.copy(value = it) },
                    placeholder = "内容",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(38.dp)
                        .neuSurface(999.dp, inset = true),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "×",
                        fontSize = 17.sp,
                        color = MutedText,
                        modifier = Modifier
                            .clickable {
                                draft.id?.let { removedIds.add(it) }
                                drafts.removeAt(index)
                            }
                            .padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        NeuTextButton("＋ 添加一项", onClick = { drafts.add(DraftFact(null, "", "")) })
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            NeuButton(
                text = "取消",
                onClick = onBack,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            NeuButton(
                text = "保存",
                onClick = {
                    vm.saveEdits(drafts.toList(), removedIds.toList())
                    onBack()
                },
                kind = NeuButtonKind.Primary,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * 我的 · AI 图库（v0.40.0）：完整生成记录列表——成功的带缩略图，失败的带标记；
 * 每条记录展示生成时间、画面提示词、使用的生图模型、发起会话。
 * 数据源 = image_generations 表（成功/失败都留痕）；成功但文件已不在磁盘的标「已丢失」。
 */
@Composable
fun GalleryScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as MenghuanApp
    var records by remember { mutableStateOf<List<com.menghuan.app.data.model.ImageGenerationRecord>>(emptyList()) }
    var viewerPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        records = runCatching {
            // 懒回填（v0.40.1）：记录表为空时，把老版本生成的图片从消息表补录为"成功"记录
            // （老版本没记模型名，显示"未知模型"）。放这里而不是启动期：低频、串行、
            // 不与启动协程争抢 Room 查询线程池（启动期并发回填曾把会话列表流堵死）
            val dao = app.db.imageGenerationDao()
            if (dao.getAll().isEmpty()) {
                val imagesDirPrefix = java.io.File(app.filesDir, "images").absolutePath + "/"
                val personaNames = app.personaRepository.observePersonas().first().associate { it.id to it.name }
                app.db.messageDao().getAll()
                    .filter { it.imagePath.startsWith(imagesDirPrefix) && java.io.File(it.imagePath).exists() }
                    .sortedBy { it.timestamp }
                    .forEach { m ->
                        runCatching {
                            dao.insert(
                                com.menghuan.app.data.model.ImageGenerationRecord(
                                    timestamp = m.timestamp,
                                    personaId = m.conversationId,
                                    personaName = personaNames[m.conversationId] ?: "未知会话",
                                    model = "",
                                    prompt = m.content,
                                    success = true,
                                    imagePath = m.imagePath,
                                )
                            )
                        }
                    }
            }
            dao.getAll()
        }.getOrDefault(emptyList())
    }
    SettingsPage(title = "AI 图库", onBack = onBack) {
        if (records.isEmpty()) {
            Text(
                "还没有生成记录",
                fontSize = 13.sp,
                color = MutedText,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        } else {
            Spacer(Modifier.height(8.dp))
            records.forEach { record ->
                GenerationRecordRow(
                    record = record,
                    onClick = if (record.success && java.io.File(record.imagePath).exists()) {
                        { viewerPath = record.imagePath }
                    } else null,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    viewerPath?.let { path ->
        ImageViewerOverlay(path = path, onDismiss = { viewerPath = null })
    }
}

/** 单条生成记录：缩略图（失败=灰面标记）+ 提示词 + 时间 / 模型 / 会话 + 成败标签 */
@Composable
private fun GenerationRecordRow(record: com.menghuan.app.data.model.ImageGenerationRecord, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val fileOk = record.success && java.io.File(record.imagePath).exists()
            val bitmap = if (fileOk) rememberLocalBitmap(record.imagePath) else null
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = record.prompt.ifBlank { "AI 生成的图片" },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .neuSurface(10.dp, inset = true),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            !record.success -> "失败"
                            else -> "已丢失"
                        },
                        fontSize = 10.sp,
                        color = if (!record.success) NeuDanger else MutedText,
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                record.prompt.ifBlank { "（无提示词）" },
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = TextPrimary,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                galleryTime(record.timestamp),
                fontSize = 10.sp,
                color = MutedText,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${record.model.ifBlank { "未知模型" }} · ${record.personaName}",
                fontSize = 10.sp,
                color = MutedText,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (record.success) "成功" else "失败",
            fontSize = 10.sp,
            color = if (record.success) MutedText else NeuDanger,
        )
    }
}

private fun galleryTime(ts: Long): String {
    val z = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault())
    return "${z.monthValue}月${z.dayOfMonth}日 ${z.hour.toString().padStart(2, '0')}:${z.minute.toString().padStart(2, '0')}"
}
