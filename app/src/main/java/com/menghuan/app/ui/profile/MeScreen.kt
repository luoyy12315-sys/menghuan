package com.menghuan.app.ui.profile

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import com.menghuan.app.data.UserAvatar
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.AiInsight
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.ProfileFact
import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.staggerEnter
import com.menghuan.app.ui.chat.ImageViewerOverlay
import com.menghuan.app.ui.chat.rememberLocalBitmap
import com.menghuan.app.ui.settings.SettingsPage
import com.menghuan.app.ui.settings.SettingsRoutes
import com.menghuan.app.ui.settings.DataStats
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
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

    /**
     * 「个人档案全部 AI 可读」开关（v0.70.0）。
     *
     * 这一页的条目全是 `personaId = 0` 那一档 —— 记忆按人设分开之后，只有它天然是跨角色的。
     * **默认开**：用户亲手填的姓名/职业本来就是"讲给我身边所有 AI"的，
     * 默认关掉会让 TA 一觉醒来发现每个角色都不认识自己了，那是静默倒退。
     * 想收成"只给某个角色看"的人，把具体那条挪进该角色的记忆就行（记忆页那一轮做）。
     */
    val profileSharedReadable: StateFlow<Boolean> = settingsRepository.profileSharedReadable
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setProfileSharedReadable(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setProfileSharedReadable(enabled)
    }

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

    /** 数据与隐私面板：各类数据条数 */
    val dataStats: StateFlow<DataStats> = combine(
        db.messageDao().observeCount(),
        db.taskDao().observeAll().map { it.size.toLong() },
        personaRepository.observePersonas().map { it.size.toLong() },
        db.profileFactDao().observeAll().map { it.size.toLong() },
    ) { messages, tasks, personas, facts ->
        DataStats(messages, tasks, personas, facts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataStats())

    /**
     * 清除全部数据（v0.36.5，原「清除全部聊天记录」升级）：
     * 删 聊天消息 / AI 洞察 / 用户档案 / 任务 / 打卡记录，
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
                db.profileFactDao().deleteAll()
                db.taskCompletionDao().deleteAll()
                db.taskDao().deleteAll()
                db.momentDao().deleteAll()
                // v0.82.0 P1：滚动摘要与记忆向量同样属于"全部数据"（留着就是指向空气的行）
                db.conversationSummaryDao().deleteAll()
                db.memoryEmbeddingDao().deleteAll()
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

    /** 导出全部数据为 JSON 文件（不含 API 密钥），经 FileProvider 分享 */
    fun exportData(context: android.content.Context) = viewModelScope.launch {
        runCatching {
            val json = DataBackup.buildExportJson(
                personas = personaRepository.observePersonas().first(),
                messages = db.messageDao().getAll(),
                tasks = db.taskDao().observeAll().first(),
                facts = db.profileFactDao().observeAll().first(),
                insights = db.aiInsightDao().observeLatest().first(),
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
     * 合并导入（非破坏性）：人设/档案/洞察按各自键去重跳过，消息与任务追加，
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

            _importMessage.value = "导入完成：人设 $personaCount · 消息 $msgCount · 任务 $taskCount · " +
                "档案 $factCount · 洞察 $insightCount"
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
 * 「我的」页（第 4 个 tab，类微信「我」）：人设管理 / 基础档案 / AI 图库 三张内容卡
 * + 一张「设置」入口卡。
 *
 * v0.54.0：「AI 提示词」从这里搬进**设置页第 ① 组**（它本质是配置，不是"我的"资料）。
 * 自此本页与设置页的分工清楚：**本页 = 我是谁**（人设/画像/产物），**设置页 = 怎么配**。
 * 设置链（对话模型 / 语音通话 / 提醒通知 / 数据与系统）的唯一入口在本页。
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
    val dataStats by vm.dataStats.collectAsState()
    val importMessage by vm.importMessage.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as MenghuanApp
    // v0.80.0：顶部档案卡读的就是这两个 —— 头像（图片优先于 emoji）+ 昵称/简介。
    // 首值没到时是 null = 「还没加载出来」，卡片走占位文案（不画半截真名）。
    val profile by app.settingsRepository.myProfile.collectAsState(initial = null)
    val avatar by app.settingsRepository.userAvatar.collectAsState(initial = null)

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
            // v0.80.0：顶部个人档案卡（点进个人档案页）—— 头像/昵称/简介是"我是谁"，
            // 放在最上面与下面四张内容卡（人设 / 档案 / 图库 / 设置）分开，
            // 也顺手把"点自己的头像能进档案"这条交互补齐（AI 的头像不动）。
            // v0.80.0：顶部这张就是「我的档案」卡 —— 原来下面那张「基础档案」行卡取消了，
            // 它的内容（给 AI 读的画像条目）并进这里点开的档案页里，入口只留一个。
            // 行框与图标槽跟下面四张行卡是**同一个组件**，头像按图标槽大小来（见 Neu.kt）。
            NeuSheetRow(
                icon = MIcons.Person,
                title = profile?.displayNickname ?: "我",
                subtitle = profile?.bio?.takeIf { it.isNotBlank() } ?: "设置一下自己的档案吧",
                iconSlot = { MeAvatar(avatar = avatar ?: UserAvatar(), size = 38.dp) },
                onClick = { onOpen(SettingsRoutes.ME_PROFILE) },
            )

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
                        icon = MIcons.ImageIcon,
                        title = "AI 图库",
                        subtitle = "AI 生成的图片合集 · 点开查看",
                        onClick = { onOpen(SettingsRoutes.GALLERY) },
                    )
                },
                {
                    // v0.54.0：「AI 提示词」从这里搬进设置页第① 组（它本质是配置，
                    // 不是"我的"资料）。本页自此只留人设 / 档案 / 图库三张内容卡 + 设置入口。
                    NeuSheetRow(
                        icon = MIcons.Sliders,
                        title = "设置",
                        subtitle = "AI 模型 · 语音通话 · 提醒通知 · 数据与系统",
                        onClick = { onOpen(SettingsRoutes.SETTINGS) },
                    )
                },
            )
            // v0.55.0：整页内容错峰入场（淡入 + 上滑）。这一页原来**一个动画都没有** ——
            // 三个 top tab 里只有它切过去是硬出现，与消息/数据两页的观感对不上。
            // 平板宽版式：行卡两列网格；手机单列
            if (com.menghuan.app.ui.common.isExpandedWindow()) {
                rows.chunked(2).forEachIndexed { rowIndex, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEachIndexed { colIndex, row ->
                            val order = rowIndex * 2 + colIndex
                            Box(Modifier.weight(1f).staggerEnter(order)) { row() }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                rows.forEachIndexed { index, row -> Box(Modifier.staggerEnter(index)) { row() } }
            }
        }
    }

}

/**
 * 我的 · AI 图库（v0.40.0）：完整生成记录列表——成功的带缩略图，失败的带标记；
 * 每条记录展示生成时间、画面提示词、使用的生图模型、发起会话。
 * 数据源 = image_generations 表（成功/失败都留痕）；成功但文件已不在磁盘的标「已丢失」。
 *
 * v0.54.3：**长按一条记录 → 右下角菜单 → 删除**（删记录 + 删本机图片文件）。
 */
@Composable
fun GalleryScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as MenghuanApp
    val context = LocalContext.current
    var records by remember { mutableStateOf<List<com.menghuan.app.data.model.ImageGenerationRecord>>(emptyList()) }
    var viewerPath by remember { mutableStateOf<String?>(null) }
    var menuRecord by remember { mutableStateOf<com.menghuan.app.data.model.ImageGenerationRecord?>(null) }
    // v0.56.15：提示词从卡片挪进长按菜单——点「提示词」打开这条的完整提示词（可复制）
    var promptRecord by remember { mutableStateOf<com.menghuan.app.data.model.ImageGenerationRecord?>(null) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
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
                // 长按菜单锚点：菜单包在条目右下角的小 Box 里（Popup 锚定直接父节点）
                Box(Modifier.fillMaxWidth()) {
                    GenerationRecordRow(
                        record = record,
                        onClick = if (record.success && java.io.File(record.imagePath).exists()) {
                            { viewerPath = record.imagePath }
                        } else null,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuRecord = record
                        },
                    )
                    Box(Modifier.align(Alignment.BottomEnd).size(1.dp)) {
                        DropdownMenu(
                            expanded = menuRecord?.id == record.id,
                            onDismissRequest = { menuRecord = null },
                            // v0.56.15：整体改小（字 13sp、项高 36dp、圆角收到 10dp），描线保留
                            modifier = Modifier.border(1.dp, NeuHairline, RoundedCornerShape(10.dp)),
                            containerColor = NeuSurface,
                            shape = RoundedCornerShape(10.dp),
                            shadowElevation = 0.dp, // v0.42.1 全应用去阴影
                        ) {
                            DropdownMenuItem(
                                text = { Text("提示词", fontSize = 13.sp, color = TextPrimary) },
                                onClick = {
                                    menuRecord = null
                                    promptRecord = record
                                },
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            )
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(NeuHairline),
                            )
                            DropdownMenuItem(
                                text = { Text("删除", fontSize = 13.sp, color = NeuDanger) },
                                onClick = {
                                    menuRecord = null
                                    // 删记录 + 删本机文件；成功后重新拉取列表
                                    scope.launch {
                                        runCatching {
                                            java.io.File(record.imagePath).takeIf { it.exists() }?.delete()
                                            app.db.imageGenerationDao().deleteById(record.id)
                                            records = app.db.imageGenerationDao().getAll()
                                        }
                                    }
                                },
                                modifier = Modifier.height(36.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    viewerPath?.let { path ->
        ImageViewerOverlay(path = path, onDismiss = { viewerPath = null })
    }
    // v0.56.15：提示词查看弹层（从长按菜单进来）——完整提示词 + 一键复制
    promptRecord?.let { record ->
        NeuSheetScaffold(title = "提示词", onDismiss = { promptRecord = null }) {
            Spacer(Modifier.height(8.dp))
            Text(
                record.prompt.ifBlank { "（这条记录没有提示词）" },
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = TextPrimary,
            )
            Spacer(Modifier.height(18.dp))
            NeuButton(
                text = "复制提示词",
                kind = NeuButtonKind.Primary,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("menghuan", record.prompt))
                    com.menghuan.app.ui.common.AppToast.ok("已复制")
                    promptRecord = null
                },
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** 单条生成记录：缩略图（失败=灰面标记）+ 提示词 + 时间 / 模型 / 会话 + 成败标签 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GenerationRecordRow(
    record: com.menghuan.app.data.model.ImageGenerationRecord,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = { onClick?.invoke() },
                onLongClick = { onLongClick?.invoke() },
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
        // v0.56.15：提示词不再上卡片（挪进长按菜单查看）；剩下的信息分层加强——
        // 模型提为主行、人设次行、时间垫底，三级灰阶一眼分清主次
        Column(Modifier.weight(1f)) {
            Text(
                record.model.ifBlank { "未知模型" },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                record.personaName.ifBlank { "未关联人设" },
                fontSize = 11.5.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                galleryTime(record.timestamp),
                fontSize = 10.5.sp,
                color = MutedText,
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
