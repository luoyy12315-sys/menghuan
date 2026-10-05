package com.menghuan.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
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
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
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
    var reloadKey by remember { mutableStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }
    var busyVoice by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var showClone by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var manualDraft by remember { mutableStateOf(manualId) }
    LaunchedEffect(manualId) { manualDraft = manualId }

    val localSamples = remember(provider, reloadKey) {
        if (provider != TtsProvider.XIAOMI_MIMO) emptyList()
        else VoiceCloner.voiceDir(context).listFiles()?.filter {
            it.isFile && it.name.endsWith(".wav")
        }.orEmpty().map { it.name }
    }

    // 克隆音色是服务商账号里的东西（硅基）/ 本机样本（MiMo），进页面直接读
    LaunchedEffect(provider, config.isComplete, reloadKey) {
        problem = null
        aliases = runCatching { app.settingsRepository.cloneVoiceNames() }.getOrDefault(emptyMap())
        cloned = if (provider == TtsProvider.SILICONFLOW && config.isComplete) {
            runCatching { VoiceCloner.listVoices(config) }.getOrElse {
                problem = it.message ?: "拉取克隆音色失败"
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    val library = TtsVoices.library(provider, cloned, localSamples, manualId, aliases)
    val selectedId = defaultRef?.takeIf { it.provider == provider }?.voiceId
        ?: TtsVoices.defaultVoiceId(provider)

    AdaptiveScreen {
        NeuTopBar(
            title = "音色",
            navigationIcon = {
                NeuCircleButton(onClick = onBack, size = 38.dp) {
                    Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
                }
            },
            actions = {
                // 顶栏右侧：克隆音色 + ID（手填音色 ID 的缩写）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (provider != TtsProvider.CUSTOM) {
                        NeuTextButton("克隆音色", onClick = { showClone = true })
                        Spacer(Modifier.width(12.dp))
                    }
                    NeuTextButton("ID", onClick = { manualDraft = manualId; showManual = true })
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
            Text(
                "点一行 = 设为默认音色（人设没单独配音色时用它）；行尾可试听。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MutedText,
            )
            Spacer(Modifier.height(10.dp))
            library.forEach { item ->
                VoiceRow(
                    item = item,
                    selected = item.ref.voiceId == selectedId,
                    busy = busyVoice == item.ref.voiceId,
                    onSelect = { scope.launch { app.settingsRepository.setTtsDefaultVoice(item.ref) } },
                    onPreview = {
                        scope.launch {
                            busyVoice = item.ref.voiceId
                            status = null
                            problem = null
                            val client = TtsClients.from(config)
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
            if (provider == TtsProvider.CUSTOM && library.isEmpty()) {
                Text("该端点没有预置音色，用顶部「ID」手填一个。", fontSize = 12.sp, color = MutedText)
            }
            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = MutedText)
            }
            problem?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = NeuDanger)
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
        SettingsCardDialog(title = "手填音色 ID", onDismiss = { showManual = false }) {
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
}

/** 一行一个音色：点整行选为默认，右侧「试听」 */
@Composable
private fun VoiceRow(
    item: VoiceItem,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .figmaSelection(selected, 12.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
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
                "MiMo 没有音色上传接口：样本留在本机，每次合成随请求内联。"
            } else {
                "硅基流动的音色克隆需先在官网完成实名认证。" +
                    "样本用 8~10 秒**干净单人声**：带背景音乐、或只截到半句话，" +
                    "克隆出来的声音会发抖变调。"
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
                            runCatching {
                                VoiceCloner.upload(
                                    config,
                                    file,
                                    VoiceCloner.asciiVoiceName(display),
                                    transcript.trim(),
                                )
                            }.onSuccess { uri ->
                                app.settingsRepository.setCloneVoiceName(uri, display)
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
