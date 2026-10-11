package com.menghuan.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.ClonedVoice
import com.menghuan.app.data.TtsClients
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.data.TtsProvider
import com.menghuan.app.data.TtsVoices
import com.menghuan.app.data.VoiceCloner
import com.menghuan.app.data.VoiceItem
import com.menghuan.app.data.VoiceRef
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.figmaSelection
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File

/**
 * 「音色」页（v0.53.5 从「语音」页里拆出来，成为独立整页）。
 *
 * 用户点名的形状：
 * - 「语音」页里「音色」**就是一张卡片**，点进去才到这一页；
 * - 这一页的**顶部栏右边**放两个按钮：**「克隆音色」**与**「ID」**（手填音色 ID 缩写成 ID）；
 * - 正文就是这份名单：该家两个预置（一男一女）+ 我克隆的 + 手填的那个；
 *   **点一行 = 设为默认音色**（人设没单独配音色时用它），行尾「试听」。
 *
 * 人设编辑页的音色候选就是这份名单（那边只挑、不试听不添加）。
 */
@Composable
fun VoiceLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = settingsContainer()
    val scope = rememberCoroutineScope()

    val config by app.settingsRepository.ttsConfig.collectAsState(initial = TtsConfig())
    val provider = config.provider

    val defaultRef by app.settingsRepository.ttsDefaultVoice(provider)
        .collectAsState(initial = null)
    val manualId by app.settingsRepository.ttsManualVoiceId(provider)
        .collectAsState(initial = "")

    var cloned by remember { mutableStateOf<List<ClonedVoice>>(emptyList()) }
    var aliases by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // 自定义端点的多条手填音色库（v0.56.13，id → 显示名）；读取会顺带完成旧单值键的迁移
    var customs by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    // 被用户长按删掉的硅基克隆音色 uri（本机隐藏表）：名单两处（缓存 + 网络刷新）都按它过滤
    var hidden by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reloadKey by remember { mutableStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busyVoice by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var showClone by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showAddCustom by remember { mutableStateOf(false) }
    var manualDraft by remember { mutableStateOf(manualId) }
    LaunchedEffect(manualId) { manualDraft = manualId }

    val localSamples = remember(provider, reloadKey) {
        if (provider != TtsProvider.XIAOMI_MIMO) emptyList()
        else VoiceCloner.voiceDir(context).listFiles()?.filter {
            it.isFile && it.name.endsWith(".wav")
        }.orEmpty().map { it.name }
    }

    // 克隆音色（v0.53.5）：服务商账号里的东西（硅基）/ 本机样本（MiMo）。
    //
    // v0.56.5（用户点名"克隆音色总是最后才加载出来，像卡了一样突然弹出来"）：
    // 拆成**两步**，让整份名单一次性出现 ——
    // ① 先用**本机缓存**把整页渲染齐（毫秒级，与预置音色同一帧到）；
    // ② 再后台向服务商拉一次，拿到就覆盖，并落回缓存供下次用。
    // 以前只有网络这一份，克隆那几条必然晚一个来回才补进来。
    //
    // v0.56.12：两条路径都过滤 [hidden]（用户长按删除过的不再出现）。
    LaunchedEffect(provider) {
        aliases = runCatching { app.settingsRepository.cloneVoiceNames() }.getOrDefault(emptyMap())
        hidden = runCatching { app.settingsRepository.hiddenVoiceIds(provider) }.getOrDefault(emptySet())
        customs = runCatching { app.settingsRepository.customVoices() }.getOrDefault(emptyList())
        cloned = runCatching { app.settingsRepository.cachedClonedVoices(provider) }.getOrDefault(emptyList())
    }
    LaunchedEffect(provider, config.isComplete, reloadKey) {
        // 只有硅基有"账号里的音色列表"这回事；MiMo 用本机样本、自定义端点用手填 ID
        if (provider != TtsProvider.SILICONFLOW || !config.isComplete) return@LaunchedEffect
        problem = null
        val fresh = runCatching { VoiceCloner.listVoices(config) }.getOrElse {
            problem = it.message ?: "拉取克隆音色失败"
            null
        }
        if (fresh != null) {
            cloned = fresh.filter { it.uri !in hidden }
            aliases = runCatching { app.settingsRepository.cloneVoiceNames() }.getOrDefault(emptyMap())
            runCatching { app.settingsRepository.setCachedClonedVoices(provider, fresh) }
        }
    }

    val library = TtsVoices.library(
        provider,
        cloned.filter { it.uri !in hidden },
        localSamples,
        manualId,
        aliases,
        customVoices = customs,
    )
    val selectedId = defaultRef?.takeIf { it.provider == provider }?.voiceId
        ?: TtsVoices.defaultVoiceId(provider)

    // 长按删除（v0.56.12，v0.56.13 加自定义端点分支）：预置音色不可删；手填 ID 真删（清键）、
    // MiMo 本机样本删文件、自定义端点的录入行从库里移除、硅基克隆做**本机隐藏**
    // （服务商账号里的实体不动——App 不调服务商删除接口，删了就找不回）。
    // 删的是当前默认音色时，回落到该家第一个预置（自定义端点没有预置 = 清掉默认）。
    suspend fun deleteVoice(item: VoiceItem) {
        val vid = item.ref.voiceId
        when {
            provider == TtsProvider.CUSTOM -> {
                runCatching { app.settingsRepository.removeCustomVoice(vid) }
                customs = customs.filter { it.first != vid }
            }
            vid == manualId.trim() -> app.settingsRepository.setTtsManualVoiceId(provider, "")
            vid.startsWith(TtsVoices.MIMO_LOCAL_PREFIX) -> {
                val name = TtsVoices.localSamplePath(vid).orEmpty().substringAfterLast('/')
                if (name.isNotBlank()) {
                    runCatching { File(VoiceCloner.voiceDir(context), name).delete() }
                    runCatching { app.settingsRepository.removeCloneVoiceName(name) }
                }
                reloadKey++
            }
            else -> {
                hidden = hidden + vid
                runCatching { app.settingsRepository.hideVoice(provider, vid) }
                runCatching { app.settingsRepository.removeCloneVoiceName(vid) }
                runCatching {
                    val rest = app.settingsRepository.cachedClonedVoices(provider).filter { it.uri != vid }
                    app.settingsRepository.setCachedClonedVoices(provider, rest)
                }
                cloned = cloned.filter { it.uri != vid }
            }
        }
        if (vid == selectedId) {
            app.settingsRepository.setTtsDefaultVoice(VoiceRef(provider, TtsVoices.defaultVoiceId(provider)))
        }
        status = "已删除「${item.label}」"
    }

    AdaptiveScreen {
        NeuTopBar(
            // v0.56.13：标明当前生效的服务商——名单、克隆、试听、默认音色都只对生效那家有意义，
            // 不标出来用户配了两家之后就分不清"这排是谁家的、克隆会进谁家"
            title = "音色 · ${provider.label}",
            navigationIcon = {
                NeuCircleButton(onClick = onBack, size = 38.dp) {
                    Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
                }
            },
            actions = {
                // 顶栏右侧：克隆音色 + ID（手填音色 ID 的缩写）。
                // v0.56.13：自定义端点的「ID」撤掉——手填升级成多条音色库，入口在正文的「＋ 添加音色」。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (provider != TtsProvider.CUSTOM) {
                        NeuTextButton("克隆音色", onClick = { showClone = true })
                        Spacer(Modifier.width(12.dp))
                        NeuTextButton("ID", onClick = { manualDraft = manualId; showManual = true })
                    }
                }
            },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            // v0.56.13：自定义端点的添加入口从顶栏小按钮升级成**正文首行的行卡**——
            // 那家没有预置也没有克隆，这张卡就是唯一的音色来源，值得一等公民的位置
            if (provider == TtsProvider.CUSTOM) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .neuSurface(12.dp, color = NeuTrack, inset = true)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { showAddCustom = true },
                        )
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(26.dp).neuSurface(999.dp, color = NeuSurface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MIcons.Plus, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("添加音色", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                }
                Spacer(Modifier.height(12.dp))
            }
            library.forEach { item ->
                // v0.56.12：预置音色（一男一女）不可删；克隆与手填的都支持长按删除
                val deletable = TtsVoices.presets(provider).none { it.id == item.ref.voiceId }
                VoiceRow(
                    item = item,
                    selected = item.ref.voiceId == selectedId,
                    busy = busyVoice == item.ref.voiceId,
                    onDelete = if (deletable) {
                        {
                            scope.launch { deleteVoice(item) }
                        }
                    } else null,
                    onSelect = { scope.launch { app.settingsRepository.setTtsDefaultVoice(item.ref) } },
                    onPreview = {
                        scope.launch {
                            busyVoice = item.ref.voiceId
                            status = null
                            problem = null
                            val client = TtsClients.from(config, context)
                            val audio = client.synthesize(VOICE_PREVIEW_TEXT, item.ref)
                            if (audio == null) {
                                problem = "试听失败：${client.lastFailure ?: "未知原因"}"
                            } else {
                                runCatching { playTtsPreview(context, audio.bytes, audio.format.extension) }
                                    .onFailure { problem = "播放失败：${it.javaClass.simpleName}" }
                                status = "正在试听「${item.label}」（${audio.format.extension}）"
                            }
                            busyVoice = null
                        }
                    },
                )
            }
            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = MutedText)
            }
            problem?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = NeuDanger)
            }
            // v1.0.1：隐藏表原来是**只进不出**的（长按删一条就再也找不回来，界面上无入口）。
            // 这里给一条回来的路：列出被隐藏的、可逐条恢复。
            if (hidden.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { showHidden = true },
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "查看已隐藏的音色（${hidden.size}）",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    Text("›", fontSize = 14.sp, color = TextSecondary)
                }
            }
            if (!config.isComplete) {
                Spacer(Modifier.height(14.dp))
                NeuButton(
                    text = "去配置语音合成",
                    kind = NeuButtonKind.Secondary,
                    onClick = onBack,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }

    // 手填音色 ID（顶部「ID」按钮打开）
    if (showManual) {
        NeuSheetScaffold(title = "手填音色 ID", onDismiss = { showManual = false }) {
            NeuTextField(
                value = manualDraft,
                onValueChange = { manualDraft = it },
                label = "服务商那边的音色 ID",
                placeholder = if (provider == TtsProvider.SILICONFLOW) {
                    "speech:…（上传克隆后返回的那串）"
                } else {
                    "例：alloy"
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                NeuButton(
                    text = "保存",
                    kind = NeuButtonKind.Primary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            app.settingsRepository.setTtsManualVoiceId(provider, manualDraft)
                            showManual = false
                            status = "已保存手动音色"
                        }
                    },
                )
                if (manualId.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    NeuButton(
                        text = "清除",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch { app.settingsRepository.setTtsManualVoiceId(provider, "") }
                        },
                    )
                }
            }
        }
    }

    if (showClone) {
        CloneVoiceSheet(
            config = config,
            onDismiss = { showClone = false },
            onDone = { reloadKey++ },
        )
    }

    // 「已隐藏的音色」（v1.0.1）：隐藏只影响本机名单，服务商账号里的音色从未被删。
    // 恢复 = 从隐藏表里摘掉，名单立刻重新包含它（列表里那条还在就能直接再用）。
    if (showHidden) {
        NeuSheetScaffold(title = "已隐藏的音色", onDismiss = { showHidden = false }) {
            Spacer(Modifier.height(4.dp))
            if (hidden.isEmpty()) {
                Text("没有已隐藏的音色", fontSize = 13.sp, color = MutedText)
            }
            hidden.toList().sorted().forEach { vid ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        aliases[vid] ?: vid,
                        fontSize = 13.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    NeuTextButton(
                        "恢复",
                        onClick = {
                            hidden = hidden - vid
                            scope.launch {
                                runCatching { app.settingsRepository.unhideVoice(provider, vid) }
                                status = "已恢复「${aliases[vid] ?: vid}」"
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "「删除」只是把这条从本机名单里藏起来（服务商账号里的音色没有被删除），所以随时可以恢复。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MutedText,
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    // 自定义端点的「添加音色」弹层（v0.56.13）：ID 必填（服务商侧的音色标识），
    // 显示名选填（空则直接显示 ID）。已有一个默认音色时不抢默认；
    // 第一次添加（还没有默认）自动设为默认，用户立即可用——与硅基克隆完的行为一致。
    if (showAddCustom) {
        var idDraft by remember { mutableStateOf("") }
        var nameDraft by remember { mutableStateOf("") }
        NeuSheetScaffold(title = "添加音色", onDismiss = { showAddCustom = false }) {
            Spacer(Modifier.height(6.dp))
            NeuTextField(
                value = idDraft,
                onValueChange = { idDraft = it },
                label = "音色 ID",
                placeholder = "服务商文档里的那串音色标识（如 alloy / FishAudio:xxx）",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            NeuTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it },
                label = "显示名（可选）",
                placeholder = "例：温柔女声",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            NeuButton(
                text = "添加",
                kind = NeuButtonKind.Primary,
                enabled = idDraft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        val vid = idDraft.trim()
                        app.settingsRepository.addCustomVoice(vid, nameDraft)
                        customs = runCatching { app.settingsRepository.customVoices() }.getOrDefault(customs)
                        if (selectedId.isBlank()) {
                            app.settingsRepository.setTtsDefaultVoice(VoiceRef(provider, vid))
                        }
                        status = "已添加音色「${nameDraft.trim().ifBlank { vid }}」"
                        showAddCustom = false
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 一行一个音色：点整行选为默认，右侧「试听」。
 *
 * **v0.56.12 长按删除**：[onDelete] 非空（克隆 / 手填）时长按弹菜单，点菜单里的「删除」才真删
 * （2026-10-02 拍板的破坏性操作惯例：长按只弹菜单、菜单那一下就是确认）+ LongPress 震动。
 * 菜单锚定在条目右下角的小锚点 Box 上。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VoiceRow(
    item: VoiceItem,
    selected: Boolean,
    busy: Boolean,
    onDelete: (() -> Unit)?,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .figmaSelection(selected, 12.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSelect,
                    onLongClick = onDelete?.let { onDel -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    } },
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.label,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text("默认", fontSize = 10.sp, color = TextSecondary, letterSpacing = 0.4.sp)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                if (busy) "…" else "试听",
                fontSize = 12.sp,
                color = if (busy) MutedText else TextPrimary,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !busy,
                        onClick = onPreview,
                    )
                    .padding(6.dp),
            )
        }
        // 长按菜单锚点：包在条目右下角的小 Box 里（DropdownMenu 锚定其直接父节点）。
        // v0.56.15：补上发丝线描边（记账本同款，之前漏了）+ 整体改小（字 13sp、项高 36dp）。
        if (onDelete != null) {
            Box(Modifier.align(Alignment.BottomEnd).size(1.dp)) {
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.border(1.dp, NeuHairline, RoundedCornerShape(10.dp)),
                    containerColor = NeuSurface,
                    shape = RoundedCornerShape(10.dp),
                    shadowElevation = 0.dp, // v0.42.1 全应用去阴影
                ) {
                    DropdownMenuItem(
                        text = { Text("删除", fontSize = 13.sp, color = NeuDanger) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    )
                }
            }
        }
    }
}

/**
 * 克隆音色弹层（v0.52.0，v0.53.0 修好中文名）：选样本 → 端侧识别自动出逐字稿 →
 * 上传（硅基）/ 留本地（MiMo）。
 *
 * 样本格式不再限死 mp3/wav：解码统一走
 * [com.menghuan.app.realtime.MediaAudioDecoder]，mp4 / m4a / aac 也能直接选。
 * 上传是**账号级**操作（硅基要求先完成实名认证），所以放在这里而不是人设编辑器里。
 *
 * ⚠️ 服务端 `customName` **只收字母/数字/`_`/`-`**（真机 HTTP 400），所以发出去的是
 * [VoiceCloner.asciiVoiceName]；用户起的中文名存进 `cloneVoiceNames` 表，只在名单里显示。
 */
@Composable
private fun CloneVoiceSheet(
    config: TtsConfig,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val app = settingsContainer()
    val scope = rememberCoroutineScope()
    var sample by remember { mutableStateOf<File?>(null) }
    var transcript by remember { mutableStateOf("") }
    var voiceName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            val name = voiceName.trim().ifBlank { "我的音色" }
            runCatching { VoiceCloner.prepareSample(context, uri, name) }
                .onSuccess { file ->
                    sample = file
                    voiceName = name
                    // 上传接口把 text（样本逐字稿）标成必填 → 用端侧 SenseVoice 自动转写；
                    // 转不出来就把输入框留给用户手填，不传空串去赌服务端容忍
                    transcript = VoiceCloner.transcribeSample(context, file).orEmpty()
                }
                .onFailure { error = it.message ?: "处理样本失败" }
            busy = false
        }
    }

    NeuSheetScaffold(title = "克隆音色", onDismiss = onDismiss) {
        Spacer(Modifier.height(6.dp))
        Text(
            if (config.provider == TtsProvider.XIAOMI_MIMO) {
                "MiMo 没有音色上传接口：样本留在本机（可以克隆多个），每次合成随请求内联。"
            } else {
                "克隆效果几乎全看样本。服务商的要求：只有一个人说话、吐字清楚、" +
                    "音量与情绪平稳、没有背景音乐和回声，长度 8~10 秒。" +
                    "带 BGM、多人对话、或只截到半句话的样本，克隆出来必然发抖变调——这不是 App 能修的。" +
                    "首次使用需先在硅基流动官网完成实名认证。"
            },
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MutedText,
        )
        Spacer(Modifier.height(12.dp))
        NeuButton(
            text = if (sample == null) "选择样本（mp3 / wav / m4a / mp4）" else "重选样本：${sample?.name}",
            kind = NeuButtonKind.Secondary,
            enabled = !busy,
            onClick = { picker.launch("*/*") },
        )
        sample?.let { file ->
            Spacer(Modifier.height(12.dp))
            NeuTextField(
                value = voiceName,
                onValueChange = { voiceName = it },
                label = "音色名",
                placeholder = "例：绘梨衣",
                modifier = Modifier.fillMaxWidth(),
            )
            if (config.provider == TtsProvider.SILICONFLOW) {
                Spacer(Modifier.height(10.dp))
                NeuTextField(
                    value = transcript,
                    onValueChange = { transcript = it },
                    label = "样本逐字稿（服务端必填）",
                    placeholder = "这段音频里说了什么",
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    // v1.0.1：逐字稿是端侧识别自动填的，**ITN 会把口语数字改写成阿拉伯数字**
                    // （"一千二" → "1200"），而服务商明确要求"文本必须与音频内容一致" ——
                    // 对不上就会跑调。含数字时单独提一句，让用户自己核对。
                    if (transcript.any { it.isDigit() }) {
                        "⚠️ 稿子里有数字：自动识别会把「一千二」这类读法写成 1200，请对着录音确认一致"
                    } else {
                        "逐字稿是端侧识别自动填的，与录音不一致会让克隆跑调，请核对（可改）"
                    },
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = MutedText,
                )
            }
            Spacer(Modifier.height(10.dp))
            // v1.0.1：边听边核对逐字稿；样本本来就在本地，直接播这一份裁剪后的 WAV
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NeuButton(
                    text = "▶ 重听样本",
                    kind = NeuButtonKind.Secondary,
                    onClick = {
                        runCatching { playTtsPreview(context, file.readBytes(), "wav") }
                            .onFailure { error = "播放失败：${it.javaClass.simpleName}" }
                    },
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "上传用的是这段裁剪后的样本",
                    fontSize = 11.sp,
                    color = MutedText,
                )
            }
            Spacer(Modifier.height(12.dp))
            NeuButton(
                text = if (busy) "处理中…" else "存入我的音色",
                kind = NeuButtonKind.Primary,
                enabled = !busy && config.isComplete &&
                    (config.provider != TtsProvider.SILICONFLOW || transcript.isNotBlank()),
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        // 用户起的名字（可以中文）只在本机显示；服务端那侧必须用 ASCII 名
                        val display = voiceName.trim().ifBlank { "我的音色" }
                        if (config.provider == TtsProvider.SILICONFLOW) {
                            // ⚠️ v1.0.1：**只算一次**。asciiVoiceName 现在带随机后缀，算两次就是两个名字，
                            // 缓存里存的名字会跟服务端实际那个对不上。
                            val asciiName = VoiceCloner.asciiVoiceName(display)
                            runCatching {
                                VoiceCloner.upload(
                                    config,
                                    file,
                                    asciiName,
                                    transcript.trim(),
                                )
                            }.onSuccess { uri ->
                                app.settingsRepository.setCloneVoiceName(uri, display)
                                // v1.0.1：**顺手写进本机名单缓存**。旧实现只写了显示名，
                                // 而名单只有"列表接口成功"这一条来源 —— 那次请求一失败（403/网络），
                                // 刚克隆的音色在名单里就查无此音（看着像"只能克隆一个"）。
                                runCatching {
                                    val rest = app.settingsRepository.cachedClonedVoices(config.provider)
                                        .filter { it.uri != uri }
                                    app.settingsRepository.setCachedClonedVoices(
                                        config.provider,
                                        rest + ClonedVoice(asciiName, uri, config.effectiveModel()),
                                    )
                                }
                                // 克隆完直接把它设成默认音色，用户立刻能听到效果
                                app.settingsRepository.setTtsDefaultVoice(VoiceRef(config.provider, uri))
                                onDone()
                                onDismiss()
                            }.onFailure {
                                error = "克隆失败：${it.message ?: it.javaClass.simpleName}"
                            }
                        } else {
                            // MiMo：prepareSample 已经把样本落在 filesDir/voices/ 里
                            app.settingsRepository.setCloneVoiceName(file.name, display)
                            onDone()
                            onDismiss()
                        }
                        busy = false
                    }
                },
            )
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = NeuDanger)
        }
        Spacer(Modifier.height(16.dp))
    }
}
