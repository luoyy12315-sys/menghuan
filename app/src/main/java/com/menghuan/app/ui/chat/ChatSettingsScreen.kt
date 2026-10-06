package com.menghuan.app.ui.chat

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.AiCallLogic
import com.menghuan.app.data.AiCallRule
import com.menghuan.app.data.CallRingtone
import com.menghuan.app.data.DEFAULT_PROACTIVE_WINDOW_END
import com.menghuan.app.data.DEFAULT_PROACTIVE_WINDOW_START
import com.menghuan.app.data.ProactiveChat
import com.menghuan.app.data.KIND_SCHEDULED_CALL
import com.menghuan.app.data.ScheduledMessage
import com.menghuan.app.data.TtsProvider
import com.menghuan.app.data.TtsVoices
import com.menghuan.app.data.VoiceCloner
import com.menghuan.app.data.VoiceRef
import com.menghuan.app.notify.AlarmScheduler
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.persona.PersonaVoiceSheet
import com.menghuan.app.ui.settings.SettingsLineRow
import com.menghuan.app.ui.settings.SettingsPage
import com.menghuan.app.ui.settings.settingsContainer
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 「电话」里的默认来电时刻（用户不填就用它） */
private const val DEFAULT_CALL_TIME = "21:00"

/**
 * 某个会话（人设）的**整页设置**（v0.54.4 建页；v0.54.5 卡片化；v0.54.6 再去卡片改灰线行）。
 *
 * 用户要求：对话框右上角「⋮」**不再弹小菜单，而是整页显示**；在这一页里配这个角色自己的东西：
 * 编辑人设 / 查看聊天记录 / 电话（允许主动打电话 + 来电时刻 + 多久没聊才打）/
 * 联网搜索 / 人设主动关心 / 角色立绘 / Live 2D / 聊天背景 / 音色 / 模型 / 铃声 / 定时消息。
 *
 * **v0.54.5**：页内条目一律卡片形式，开关放卡片右侧。
 *
 * **v0.54.6**：
 * - **整页取消卡片，改用灰线分隔的行**（`SettingsLineRow`：一条 1dp 发丝线隔开两行）；
 * - 「铃声」行 → 点开**底部弹层**选铃声（不再把列表摊在页面上）；
 * - 「定时消息」行 → 点开就是**添加定时消息**的弹层（已有的一条条列在下面，点一条改它）；
 * - 「允许主动打电话」卡片名改**「电话」**，开关**挪进弹层**；
 * - 所有弹层**从屏幕底部弹出**（`NeuSheetScaffold`）。
 *
 * **v0.54.7（用户澄清）**：
 * - 「电话」弹层跟**「定时消息」**一个路子：**几点打过来**（手填 HH:mm，默认 21:00）+
 *   **说什么**（开场内容要求）+ **多久没聊才打（天数自己填 1–30）**——
 *   上一版把"多久没聊"做成了固定几选一、把时间做成了表盘，正好弄反了；
 * - 「聊天背景」点开是弹层**二选一**：原生背景 / 自定义图片（选自定义才去相册挑图）；
 * - 聊天背景**开始真的生效**（在这个角色的聊天页铺一张背景图），不再是"只存不显"。
 *
 * 角色立绘 / Live 2D 仍是"只存不显"（待接打电话时展示，展示方式未定）。
 *
 * **v0.55.0（动效与视觉打磨）**：
 * - 整页内容按序号**错峰入场**（淡入 + 上滑 10dp），与 push 滑入叠成"铺开"而不是"弹出"；
 * - 行按压 = 整行铺灰（90ms 上 / 220ms 退）+ 右侧箭头右移 2dp，反馈即时且不用涟漪。
 *
 * **v0.56.0（用户拍板）**：
 * - **整页取消分组标题**（不要分组），恢复一张平铺列表，发丝线一直分隔到底；
 * - 行标题统一加大到 17sp（[SettingsLineRow] 新增 titleSize 参数，其它设置页不受影响），
 *   **行高不加高**；副标题只留有信息量的（开关行的"已开启/已关闭"交给开关本身，
 *   "这个角色说话的声音"这类说明性小字删掉）。入口顺序一行未动。
 *
 * **v0.56.1（用户拍板）**：
 * - 所有行**统一单行、小标题全部去掉**（行高完全一致）；
 * - **带开关的行（联网搜索 / 人设主动关心）与「查看聊天记录」挪到列表底部**，其余入口顺序不变。
 */
@Composable
fun ChatSettingsScreen(
    personaId: Long,
    onBack: () -> Unit,
    onOpen: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val app = settingsContainer()
    val repo = app.settingsRepository
    val scope = rememberCoroutineScope()

    val personas by app.personaRepository.observePersonas().collectAsState(initial = emptyList())
    val persona = personas.firstOrNull { it.id == personaId }

    // 主动打电话（按角色）
    val callRules by repo.aiCallRules.collectAsState(initial = emptyList())
    val callRule = callRules.firstOrNull { it.personaId == personaId }
    // 联网搜索（按角色）
    val chatWeb by repo.chatWebSearch.collectAsState(initial = emptyMap())
    val webOn = chatWeb[personaId] == true
    // 人设主动关心（按角色）
    val proactive by repo.personaProactive.collectAsState(initial = emptyMap())
    val proactiveOn = proactive[personaId] == true
    // 主动关心的随机时间段与每天条数（v0.56.8 第二批：**每个角色一套**，弹层里改）
    val proactiveWindow by repo.proactiveWindow(personaId)
        .collectAsState(initial = DEFAULT_PROACTIVE_WINDOW_START to DEFAULT_PROACTIVE_WINDOW_END)
    val proactiveLimit by repo.proactiveDailyLimit(personaId).collectAsState(initial = 1)
    // 会话级资产
    val arts by repo.personaArt.collectAsState(initial = emptyMap())
    val live2ds by repo.personaLive2d.collectAsState(initial = emptyMap())
    val backgrounds by repo.chatBackground.collectAsState(initial = emptyMap())
    // 铃声
    val library by repo.callRingtoneLibrary.collectAsState(initial = emptyList())
    val defaultRingtone by repo.callRingtonePath.collectAsState(initial = "")
    val personaRingtones by repo.personaRingtone.collectAsState(initial = emptyMap())
    val myRingtone = personaRingtones[personaId] ?: ""
    // 定时消息（只列这个角色的；v0.56.6 起定时电话是同一条存储的另一种 kind，不混进这一串）
    val allScheduled by repo.scheduledMessages.collectAsState(initial = emptyList())
    val myScheduled = allScheduled.filter {
        it.personaId == personaId && it.kind != KIND_SCHEDULED_CALL
    }
    /** 这个角色的**定时电话**（在「电话」弹层里管，不挂在下面的定时消息串里） */
    val myScheduledCalls = allScheduled.filter {
        it.personaId == personaId && it.kind == KIND_SCHEDULED_CALL
    }
    // 接口（本会话覆盖）
    val apiProfiles by repo.apiProfiles.collectAsState(initial = emptyList())
    val globalProfileId by repo.activeProfileId.collectAsState(initial = "")
    val overrideId by repo.personaApiProfileId(personaId).collectAsState(initial = null)

    var showVoiceSheet by remember { mutableStateOf(false) }
    var showApiSwitch by remember { mutableStateOf(false) }
    var showRingtoneSheet by remember { mutableStateOf(false) }
    var showCallSheet by remember { mutableStateOf(false) }
    var showBackgroundSheet by remember { mutableStateOf(false) }
    var showProactiveSheet by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduledMessage?>(null) }

    // 音色候选（v0.56.6）：**在弹层打开之前**就把名单备好 —— 详见 PersonaVoiceSheet 的说明。
    // 克隆音色走本机缓存（毫秒级），所以弹层一出现就是完整名单，不会"先弹、后补内容"。
    var voiceOptions by remember { mutableStateOf<List<Pair<VoiceRef, String>>>(emptyList()) }
    var voiceEffectiveId by remember { mutableStateOf("") }
    LaunchedEffect(personaId) {
        runCatching {
            val config = repo.ttsConfig.first()
            val currentRef = repo.voiceRefFor(personaId).first()
            val boardDefaultId = repo.ttsDefaultVoice(config.provider).first()?.voiceId.orEmpty()
            val aliases = repo.cloneVoiceNames()
            val samples = if (config.provider == TtsProvider.XIAOMI_MIMO) {
                VoiceCloner.voiceDir(context).listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".wav") }
                    .orEmpty().map { it.name }
            } else {
                emptyList()
            }
            val cloned = if (config.provider == TtsProvider.SILICONFLOW && config.isComplete) {
                // v0.56.12：过滤掉在「音色」页被长按删除（本机隐藏）的克隆音色
                val hidden = repo.hiddenVoiceIds(config.provider)
                repo.cachedClonedVoices(config.provider).filter { it.uri !in hidden }
            } else {
                emptyList()
            }
            voiceOptions = buildList {
                TtsVoices.presets(config.provider).forEach { preset ->
                    val short = preset.label.substringAfterLast(" · ").ifBlank { preset.label }
                    add(VoiceRef(config.provider, preset.id) to short)
                }
                when (config.provider) {
                    TtsProvider.SILICONFLOW -> cloned.forEach { c ->
                        add(VoiceRef(config.provider, c.uri) to (aliases[c.uri] ?: c.customName))
                    }
                    TtsProvider.XIAOMI_MIMO -> samples.forEach { s ->
                        add(TtsVoices.localVoiceRef(config.provider, s) to (aliases[s] ?: s.substringBeforeLast('.')))
                    }
                    TtsProvider.CUSTOM -> Unit
                }
            }
            voiceEffectiveId = currentRef?.takeIf { it.provider == config.provider }?.voiceId
                ?.takeIf { it.isNotBlank() }
                ?: boardDefaultId.ifBlank { TtsVoices.defaultVoiceId(config.provider) }
        }
    }

    /**
     * 落盘这个角色的打电话规则，并按开关状态重排/撤销闹钟（只动这一个人设）。
     *
     * ① **主动电话**：每天那个"检查"闹钟（到点 + X 天未聊天才打，见 `AiCallReceiver`）；
     * ② **定时电话**：每条一个闹钟（与「定时消息」同一套机制），开关一变就把已配的逐条排上/撤掉。
     */
    fun applyCallRule(next: AiCallRule) {
        scope.launch {
            repo.upsertAiCallRule(next)
            if (next.enabled) {
                AlarmScheduler.scheduleAiCallForPersona(context, personaId, next.time)
            } else {
                AlarmScheduler.cancelAiCallForPersona(context, personaId)
            }
            myScheduledCalls.forEach { call ->
                if (next.enabled && next.scheduledEnabled && call.enabled) {
                    AlarmScheduler.scheduleScheduledMessage(context, call.id, call.time)
                } else {
                    AlarmScheduler.cancelScheduledMessage(context, call.id)
                }
            }
        }
    }

    fun importAsset(uri: Uri, subDir: String, ext: String, hint: String, onDone: (String) -> Unit) {
        scope.launch {
            val path = runCatching {
                val dir = java.io.File(context.filesDir, subDir).apply { mkdirs() }
                val target = java.io.File(dir, "asset_${System.currentTimeMillis()}.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.takeIf { it.length() > 0 }?.absolutePath
            }.getOrNull()
            if (path == null) Toast.makeText(context, "这个文件读不出来", Toast.LENGTH_SHORT).show()
            else {
                onDone(path)
                Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val artPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            importAsset(it, "persona_art", "jpg", "立绘已保存") { p ->
                scope.launch { repo.setPersonaArt(personaId, p) }
            }
        }
    }
    val live2dPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            importAsset(it, "persona_live2d", "bin", "Live 2D 资源已保存") { p ->
                scope.launch { repo.setPersonaLive2d(personaId, p) }
            }
        }
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            importAsset(it, "chat_background", "jpg", "聊天背景已保存") { p ->
                scope.launch { repo.setChatBackground(personaId, p) }
            }
        }
    }

    // v0.56.1（用户拍板）：所有行**统一单行、全部去掉小标题**；
    // 带开关的行（联网搜索 / 人设主动关心）与「查看聊天记录」挪到列表**底部**，其余入口顺序不变。
    // 行标题仍 17sp，行高与发丝线分隔保持一致。错峰入场序列接新行号。
    SettingsPage(title = "会话设置", onBack = onBack) {
        Spacer(Modifier.height(6.dp))

        SettingsLineRow("编辑人设", titleSize = 17.sp, index = 0) {
            onOpen("persona_editor/$personaId")
        }
        // 电话：开关、来电时刻、阈值都在底部弹层里
        SettingsLineRow("电话", titleSize = 17.sp, index = 1) { showCallSheet = true }
        SettingsLineRow(
            "角色立绘",
            titleSize = 17.sp,
            index = 2,
            onClick = {
                val current = arts[personaId]
                if (current.isNullOrBlank()) artPicker.launch("image/*") else scope.launch {
                    repo.setPersonaArt(personaId, "")
                }
            },
        )
        SettingsLineRow(
            "Live 2D",
            titleSize = 17.sp,
            index = 3,
            onClick = {
                val current = live2ds[personaId]
                if (current.isNullOrBlank()) live2dPicker.launch("*/*") else scope.launch {
                    repo.setPersonaLive2d(personaId, "")
                }
            },
        )
        SettingsLineRow(
            "聊天背景",
            titleSize = 17.sp,
            index = 4,
            onClick = { showBackgroundSheet = true },
        )
        SettingsLineRow("音色", titleSize = 17.sp, index = 5) { showVoiceSheet = true }
        SettingsLineRow("模型", titleSize = 17.sp, index = 6) { showApiSwitch = true }
        // 铃声：点开是底部弹层里的铃声列表
        SettingsLineRow("铃声", titleSize = 17.sp, index = 7) { showRingtoneSheet = true }

        SettingsLineRow(
            "定时消息",
            titleSize = 17.sp,
            index = 8,
            onClick = {
                editing = ScheduledMessage(id = "sm" + System.currentTimeMillis(), personaId = personaId)
            },
        )
        myScheduled.forEachIndexed { i, m ->
            SettingsLineRow(
                m.time,
                titleSize = 17.sp,
                index = 9 + i,
                onClick = { editing = m },
            )
        }

        // 底部三行（用户点名往下放）：两个开关行 + 查看聊天记录
        val bottomBase = 9 + myScheduled.size
        SettingsLineRow(
            "联网搜索",
            titleSize = 17.sp,
            index = bottomBase,
            onClick = { scope.launch { repo.setChatWebSearchEnabled(personaId, !webOn) } },
            trailing = {
                NeuSwitch(checked = webOn, onToggle = { on ->
                    scope.launch { repo.setChatWebSearchEnabled(personaId, on) }
                })
            },
        )
        // v0.56.8（用户点名）：开关**挪进弹层**，这一行点开就是「主动关心」的设置
        SettingsLineRow(
            "人设主动关心",
            titleSize = 17.sp,
            index = bottomBase + 1,
            onClick = { showProactiveSheet = true },
        )
        SettingsLineRow("查看聊天记录", titleSize = 17.sp, index = bottomBase + 2, divider = false) {
            onOpen("chat_history/$personaId")
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showVoiceSheet) {
        PersonaVoiceSheet(
            options = voiceOptions,
            effectiveId = voiceEffectiveId,
            onPick = { ref ->
                scope.launch { repo.setVoiceRefFor(personaId, ref) }
                voiceEffectiveId = ref.voiceId
                showVoiceSheet = false
            },
            onDismiss = { showVoiceSheet = false },
        )
    }
    if (showApiSwitch) {
        ApiSwitchSheet(
            profiles = apiProfiles,
            // 没单独选过时显示"全局默认那条"打勾，点了就钉在这个会话上（v0.56.6 去掉「跟随全局」）
            activeId = overrideId ?: globalProfileId,
            onSelect = { id ->
                scope.launch { repo.setPersonaApiProfile(personaId, id) }
                showApiSwitch = false
            },
            onDismiss = { showApiSwitch = false },
        )
    }

    // ===== 铃声选择（底部弹层，v0.54.6）=====
    if (showRingtoneSheet) {
        NeuSheetScaffold(title = "铃声", onDismiss = { showRingtoneSheet = false }) {
            Spacer(Modifier.height(6.dp))
            RingtoneOption(
                title = "跟随默认铃声",
                subtitle = if (defaultRingtone.isBlank()) "系统默认"
                else CallRingtone.displayNameOf(defaultRingtone),
                selected = myRingtone.isBlank(),
                onClick = {
                    scope.launch { repo.setPersonaRingtone(personaId, "") }
                    showRingtoneSheet = false
                },
            )
            library.forEach { p ->
                RingtoneOption(
                    title = CallRingtone.displayNameOf(p),
                    subtitle = "",
                    selected = myRingtone == p,
                    onClick = {
                        scope.launch { repo.setPersonaRingtone(personaId, p) }
                        showRingtoneSheet = false
                    },
                )
            }
            if (library.isEmpty()) {
                Text(
                    "还没有添加铃声，去「设置 → 通话行为 → 语音通话铃声」里添加。",
                    fontSize = 12.sp,
                    color = MutedText,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    // ===== 电话（底部弹层）=====
    //
    // v0.56.7（用户点名）：「允许主动打电话」更名「主动电话」；两行点标题才展开详细设置；
    // 弹层里不再有小标题/说明小字。
    // **v0.56.8（用户点名）**：① 删掉「每天几点检查」（触发时刻固定用规则里存的值，默认 21:00）；
    // ② 两行各加一颗**展开箭头**（收起朝右、展开朝下，带旋转过渡）当提示；
    // ③ 行上**不要点击后的灰色条** —— 展开/收起用 `indication = null`，不画 ripple。
    if (showCallSheet) {
        val rule = callRule ?: AiCallRule(personaId = personaId)
        // 哪一段被展开了（null = 两段都收起）
        var expanded by remember { mutableStateOf<String?>(null) }
        var idleDraft by remember(rule.idleDays) { mutableStateOf(rule.idleDays.toString()) }

        NeuSheetScaffold(title = "电话", onDismiss = { showCallSheet = false }) {
            Spacer(Modifier.height(6.dp))

            // ① 主动电话：标题（点一下展开/收起）+ 展开箭头 + 开关
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "主动电话",
                    fontSize = 15.sp,
                    color = TextPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { expanded = if (expanded == "call") null else "call" },
                )
                ExpandArrow(expanded = expanded == "call")
                Spacer(Modifier.width(10.dp))
                NeuSwitch(checked = rule.enabled, onToggle = { on -> applyCallRule(rule.copy(enabled = on)) })
            }
            if (expanded == "call") {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    NeuTextField(
                        value = idleDraft,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(2)
                            idleDraft = digits
                            digits.toIntOrNull()?.coerceIn(1, 30)?.let { d ->
                                applyCallRule(rule.copy(idleDays = d))
                            }
                        },
                        label = "几天没聊就主动打（1–30）",
                        placeholder = "3",
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            // ② 定时电话：标题（点一下展开/收起）+ 展开箭头 + 开关
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "定时电话",
                    fontSize = 15.sp,
                    color = TextPrimary,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { expanded = if (expanded == "scheduled") null else "scheduled" },
                )
                ExpandArrow(expanded = expanded == "scheduled")
                Spacer(Modifier.width(10.dp))
                NeuSwitch(
                    checked = rule.scheduledEnabled,
                    onToggle = { on -> applyCallRule(rule.copy(scheduledEnabled = on)) },
                )
            }
            if (expanded == "scheduled") {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    myScheduledCalls.forEach { call ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(call.time, fontSize = 15.sp, color = TextPrimary)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                call.instruction.ifBlank { "（没写要说什么）" },
                                fontSize = 12.sp,
                                color = MutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (!call.enabled) {
                                Text("已停用", fontSize = 11.sp, color = MutedText)
                                Spacer(Modifier.width(8.dp))
                            }
                            NeuTextButton("编辑", onClick = { editing = call })
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    NeuTextButton("＋ 添加定时电话", onClick = {
                        editing = ScheduledMessage(
                            id = "call" + System.currentTimeMillis(),
                            personaId = personaId,
                            kind = KIND_SCHEDULED_CALL,
                            time = DEFAULT_CALL_TIME,
                        )
                    })
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // ===== 主动关心（底部弹层，v0.56.8）：开关 + 随机时间段 + 每天最多几条 =====
    //
    // 用户要求：
    // - 开关放进弹层；发送时刻在**用户自定义的时间段里随机取**；
    // - 「每天最多几条」是**上限不是定额** —— 实际发几条在这个数以内随机，不超过就行；
    // - **每个角色各一套**时间段与条数（v0.56.8 第二批），各自一颗闹钟；
    // - 弹层容器就是「模型选择」那一个（NeuSheetScaffold：从底部升上来的底部弹层）。
    if (showProactiveSheet) {
        var startDraft by remember { mutableStateOf(proactiveWindow.first) }
        var endDraft by remember { mutableStateOf(proactiveWindow.second) }
        var limitDraft by remember { mutableStateOf(proactiveLimit.toString()) }
        val timeOk = AiCallLogic.minuteOfDayOf(startDraft) != null &&
            AiCallLogic.minuteOfDayOf(endDraft) != null
        NeuSheetScaffold(title = "主动关心", onDismiss = { showProactiveSheet = false }) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("这个角色主动发消息", fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                NeuSwitch(checked = proactiveOn, onToggle = { on ->
                    scope.launch {
                        repo.setPersonaProactive(personaId, on)
                        // 开就按这个角色自己的窗口排上，关就把它的闹钟摘掉
                        runCatching { ProactiveChat.schedulePersona(repo, context, personaId) }
                    }
                })
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    NeuTextField(
                        value = startDraft,
                        onValueChange = { startDraft = it },
                        label = "从（HH:mm）",
                        placeholder = DEFAULT_PROACTIVE_WINDOW_START,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    NeuTextField(
                        value = endDraft,
                        onValueChange = { endDraft = it },
                        label = "到（HH:mm）",
                        placeholder = DEFAULT_PROACTIVE_WINDOW_END,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            NeuTextField(
                value = limitDraft,
                onValueChange = { raw -> limitDraft = raw.filter { it.isDigit() } },
                label = "每天最多几条",
                placeholder = "1",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            NeuButton(
                text = "保存",
                kind = NeuButtonKind.Primary,
                enabled = timeOk,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        repo.setProactiveWindow(personaId, startDraft, endDraft)
                        repo.setProactiveDailyLimit(personaId, limitDraft.toIntOrNull() ?: 1)
                        // 时间段一改就**立刻按这个角色的新窗口重排下一次**（否则要等下一枪才用上新配置）
                        runCatching { ProactiveChat.schedulePersona(repo, context, personaId) }
                    }
                    showProactiveSheet = false
                },
            )
            Spacer(Modifier.height(10.dp))
        }
    }

    // ===== 聊天背景（底部弹层，v0.54.7）：原生背景 / 自定义图片 二选一 =====
    if (showBackgroundSheet) {
        val custom = backgrounds[personaId]
        NeuSheetScaffold(title = "聊天背景", onDismiss = { showBackgroundSheet = false }) {
            Spacer(Modifier.height(6.dp))
            NeuSheetRow(
                icon = MIcons.ImageIcon,
                title = "原生背景",
                subtitle = "App 自己的底色",
                onClick = {
                    scope.launch { repo.setChatBackground(personaId, "") }
                    showBackgroundSheet = false
                },
                selected = custom.isNullOrBlank(),
            )
            Spacer(Modifier.height(8.dp))
            NeuSheetRow(
                icon = MIcons.Palette,
                title = "自定义",
                subtitle = if (custom.isNullOrBlank()) "从相册选一张图" else "已设置 · 点此换一张",
                onClick = {
                    showBackgroundSheet = false
                    backgroundPicker.launch("image/*")
                },
                selected = !custom.isNullOrBlank(),
            )
            Spacer(Modifier.height(10.dp))
        }
    }

    // ===== 定时消息 / 定时电话（添加 / 编辑，同一套弹层；v0.56.6 起按 kind 只换措辞）=====
    editing?.let { initial ->
        var draft by remember(initial.id) { mutableStateOf(initial) }
        val isNew = allScheduled.none { it.id == initial.id }
        val isCall = draft.kind == KIND_SCHEDULED_CALL
        NeuSheetScaffold(
            title = when {
                isCall && isNew -> "添加定时电话"
                isCall -> "编辑定时电话"
                isNew -> "添加定时消息"
                else -> "编辑定时消息"
            },
            onDismiss = { editing = null },
        ) {
            NeuTextField(
                value = draft.time,
                onValueChange = { draft = draft.copy(time = it) },
                label = if (isCall) "每天几点打给你（HH:mm）" else "每天几点（HH:mm）",
                placeholder = if (isCall) DEFAULT_CALL_TIME else "08:00",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            NeuTextField(
                value = draft.instruction,
                onValueChange = { draft = draft.copy(instruction = it) },
                label = if (isCall) "接通后说什么" else "发什么",
                placeholder = if (isCall) "例：问我今天任务完成没，语气轻松点"
                else "例：给我发今天的重要新闻，5 条以内",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            // 「先联网搜一遍」只对"发消息"有意义（打电话是实时对话，搜不搜由模型自己决定）
            if (!isCall) {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("先联网搜一遍再写", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                    NeuSwitch(checked = draft.withSearch, onToggle = { draft = draft.copy(withSearch = it) })
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("启用", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                NeuSwitch(checked = draft.enabled, onToggle = { draft = draft.copy(enabled = it) })
            }
            Spacer(Modifier.height(16.dp))
            NeuButton(
                text = "保存",
                kind = NeuButtonKind.Primary,
                enabled = draft.instruction.isNotBlank() && AiCallLogic.minuteOfDayOf(draft.time) != null,
                onClick = {
                    val saved = draft.copy(
                        personaId = personaId,
                        time = draft.time.trim(),
                        instruction = draft.instruction.trim(),
                    )
                    scope.launch {
                        repo.upsertScheduledMessage(saved)
                        // 定时电话还要看那两个开关：都开着才排闹钟，否则只存不排
                        val rule = callRule ?: AiCallRule(personaId = personaId)
                        val shouldRing = isCall && saved.enabled && rule.enabled && rule.scheduledEnabled
                        if (saved.enabled && (!isCall || shouldRing)) {
                            AlarmScheduler.scheduleScheduledMessage(context, saved.id, saved.time)
                        } else {
                            AlarmScheduler.cancelScheduledMessage(context, saved.id)
                        }
                    }
                    editing = null
                },
            )
            if (!isNew) {
                Spacer(Modifier.height(12.dp))
                NeuTextButton("删除这条", color = NeuDanger, onClick = {
                    scope.launch {
                        AlarmScheduler.cancelScheduledMessage(context, draft.id)
                        repo.deleteScheduledMessage(draft.id)
                    }
                    editing = null
                })
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * 「点标题展开」的提示箭头（v0.56.8）：收起朝右、展开朝下，90° 旋转带 180ms 过渡。
 * 用户点名"给个箭头提示展开"——之前两行只有标题和开关，看不出点标题能展开细项。
 */
@Composable
private fun ExpandArrow(expanded: Boolean, size: Dp = 16.dp) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(180),
        label = "expandArrow",
    )
    Icon(
        MIcons.ChevronRight,
        contentDescription = if (expanded) "收起" else "展开",
        tint = MutedText,
        modifier = Modifier.size(size).rotate(rotation),
    )
}

/** 铃声弹层里的一行（点一条即选中，选中打勾） */
@Composable
private fun RingtoneOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NeuSheetRow(
        icon = MIcons.Bell,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        selected = selected,
    )
    Spacer(Modifier.height(8.dp))
}
