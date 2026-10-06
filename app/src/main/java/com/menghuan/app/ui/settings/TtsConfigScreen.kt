package com.menghuan.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.TtsClients
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.data.TtsProvider
import com.menghuan.app.data.TtsTarget
import com.menghuan.app.data.TtsVoices
import com.menghuan.app.data.VoiceRef
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 试听文案：语音合成页与音色板块共用同一句，听得出音色差别就够了 */
internal const val VOICE_PREVIEW_TEXT = "你好，这是我的声音。"

/** 展示用：去掉协议头，一行放得下 */
private fun shortUrl(url: String): String =
    url.trim().removePrefix("https://").removePrefix("http://")

/**
 * 设置 · 语音合成（v0.53.0：与「API 配置」同一套卡片交互）。
 *
 * 用户点名：**不要**把"硅基流动 / 小米 MiMo / 自定义端点"三段摊在页面上，
 * 而是**只显示已经配好的模型卡片**，卡片下面一个「＋ 添加接入」，点了再选是哪家——
 * 原理与对话接口一模一样。点卡片 = 切换当前生效，点卡片右侧箭头 = 中间弹窗改这条（含删除）。
 *
 * 每家一份独立配置（v0.52.0 起）：切到哪家看到的就是那家自己填过的 Key/地址/模型。
 */
@Composable
fun TtsConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = settingsContainer()
    val scope = rememberCoroutineScope()

    val saved by app.settingsRepository.ttsConfig.collectAsState(initial = TtsConfig())
    var configs by remember { mutableStateOf<Map<TtsProvider, TtsConfig>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<TtsConfig?>(null) }
    var picking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        configs = TtsProvider.entries.associateWith { app.settingsRepository.ttsConfigOf(it).first() }
        loaded = true
    }
    // 删掉当前生效那条之后要跟着刷新，否则卡片列表与"当前生效"会各说各话
    LaunchedEffect(saved.provider) {
        if (loaded) configs = TtsProvider.entries.associateWith { app.settingsRepository.ttsConfigOf(it).first() }
    }

    val configured = TtsProvider.entries.filter { configs[it]?.isComplete == true }

    SettingsPage(title = "语音合成", onBack = onBack) {
        Spacer(Modifier.height(14.dp))
        if (!loaded) {
            Text("读取中…", fontSize = 12.sp, color = MutedText)
            Spacer(Modifier.height(24.dp))
            return@SettingsPage
        }
        if (configured.isEmpty()) {
            Text(
                "还没有接入语音模型",
                fontSize = 13.sp,
                color = MutedText,
                modifier = Modifier.height(30.dp),
            )
        }
        configured.forEach { provider ->
            val config = configs.getValue(provider)
            NeuSheetRow(
                icon = MIcons.VoiceWave,
                title = config.effectiveModel(),
                subtitle = shortUrl(config.effectiveBaseUrl()),
                selected = provider == saved.provider,
                onClick = {
                    scope.launch {
                        app.settingsRepository.setActiveTtsProvider(provider)
                        message = "已切换到「${provider.label}」"
                    }
                },
                onChevronClick = { message = null; editor = config },
            )
            Spacer(Modifier.height(8.dp))
        }
        NeuTextButton("＋ 添加接入", onClick = { message = null; picking = true })
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 13.sp, color = NeuAccent)
        }
        Spacer(Modifier.height(6.dp))
    }

    // 先选服务商，再填那一份配置
    if (picking) {
        NeuSheetScaffold(title = "添加到哪一家", onDismiss = { picking = false }) {
            Row(Modifier.fillMaxWidth()) {
                TtsProvider.entries.forEach { p ->
                    NeuChip(
                        text = p.label,
                        selected = false,
                        onClick = {
                            picking = false
                            editor = configs[p] ?: TtsConfig(provider = p)
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "已接入的：${if (configured.isEmpty()) "无" else configured.joinToString("、") { it.label }}",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MutedText,
            )
        }
    }

    editor?.let { initial ->
        var draft by remember { mutableStateOf(initial) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        NeuSheetScaffold(
            title = "${draft.provider.label} · 语音合成",
            onDismiss = { editor = null },
        ) {
            NeuTextField(
                value = draft.apiKey,
                onValueChange = { draft = draft.copy(apiKey = it) },
                label = "API Key",
                placeholder = "必填",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            NeuTextField(
                value = draft.baseUrl,
                onValueChange = { draft = draft.copy(baseUrl = it) },
                label = "接口地址",
                placeholder = draft.provider.defaultBaseUrl.ifBlank { "https://api.xxx.com/v1" },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            NeuTextField(
                value = draft.model,
                onValueChange = { draft = draft.copy(model = it) },
                label = "模型",
                placeholder = draft.provider.defaultModel.ifBlank { "例：tts-1" },
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = NeuDanger)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                NeuButton(
                    text = if (busy) "合成中…" else "试听",
                    enabled = !busy && draft.isComplete,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            val client = TtsClients.from(draft, context)
                            val voice = VoiceRef(
                                draft.provider,
                                app.settingsRepository.ttsDefaultVoice(draft.provider).first()?.voiceId
                                    ?: TtsVoices.defaultVoiceId(draft.provider),
                            )
                            val audio = client.synthesize(VOICE_PREVIEW_TEXT, voice, TtsTarget.NOTE)
                            if (audio == null) {
                                error = "试听失败：${client.lastFailure ?: "未知原因"}"
                            } else {
                                runCatching {
                                    playTtsPreview(context, audio.bytes, audio.format.extension)
                                }.onFailure { error = "播放失败：${it.javaClass.simpleName}" }
                            }
                            busy = false
                        }
                    },
                )
                Spacer(Modifier.width(10.dp))
                NeuButton(
                    text = "保存",
                    kind = NeuButtonKind.Primary,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        scope.launch {
                            app.settingsRepository.saveTtsConfig(draft)
                            editor = null
                            message = if (draft.isComplete) {
                                "已保存 · 当前生效：${draft.provider.label}"
                            } else {
                                "已保存（还没配齐，生效的仍是 ${saved.provider.label}）"
                            }
                        }
                    },
                )
            }
            // 删除 = 把这家清空（配齐的卡片因此消失）
            if (draft.isComplete) {
                Spacer(Modifier.height(12.dp))
                NeuTextButton("删除这家配置", color = NeuDanger, onClick = {
                    scope.launch {
                        app.settingsRepository.saveTtsConfig(
                            TtsConfig(provider = draft.provider, baseUrl = "", apiKey = "", model = "")
                        )
                        editor = null
                        message = "已删除「${draft.provider.label}」的配置"
                    }
                })
            }
        }
    }
}

/**
 * 试听：TTS 产物落成临时文件交给 MediaPlayer（它认 mp3 / wav，不用我们自己解 PCM）。
 * 通话链路才需要 PCM，见 [com.menghuan.app.realtime.TtsAudioDecoder]。
 */
internal fun playTtsPreview(
    context: android.content.Context,
    bytes: ByteArray,
    ext: String,
) {
    val file = java.io.File(context.cacheDir, "tts_preview.$ext")
    file.writeBytes(bytes)
    android.media.MediaPlayer().apply {
        setDataSource(file.absolutePath)
        setOnCompletionListener { runCatching { it.release() } }
        setOnErrorListener { _, _, _ -> runCatching { release() }; true }
        prepare()
        start()
    }
}
