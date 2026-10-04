package com.menghuan.app.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.ClonedVoice
import com.menghuan.app.data.TtsClients
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.data.TtsProvider
import com.menghuan.app.data.TtsVoices
import com.menghuan.app.data.VoiceCloner
import com.menghuan.app.data.VoiceRef
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuSectionTitle
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import java.io.File

/** 试听文案：TTS 配置面板与人设编辑器共用同一句，听得出音色差别就够了 */
internal const val VOICE_PREVIEW_TEXT = "你好，这是我的声音。"

/**
 * 设置 · 语音合成（v0.50.0）：三家 provider 可切 + 试听 + 音色克隆。
 *
 * 通话与 AI 语音条**共用这一套配置**（以前 Edge TTS 朗读与通话各一套，是"语音栈越搞越乱"的一半原因）。
 *
 * 克隆放在这里而不是人设编辑器：上传是**账号级**操作（要实名认证、产出的 `uri` 谁都能用），
 * 人设那边只负责"挑一个音色"。
 */
@Composable
fun TtsConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MenghuanApp
    val scope = rememberCoroutineScope()

    val saved by app.settingsRepository.ttsConfig.collectAsState(initial = TtsConfig())
    var provider by remember { mutableStateOf(saved.provider) }
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var model by remember { mutableStateOf(saved.model) }
    LaunchedEffect(saved) {
        provider = saved.provider
        apiKey = saved.apiKey
        baseUrl = saved.baseUrl
        model = saved.model
    }

    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val current = TtsConfig(provider = provider, baseUrl = baseUrl, apiKey = apiKey, model = model)

    // ===== 克隆流程状态 =====
    var sample by remember { mutableStateOf<File?>(null) }
    var transcript by remember { mutableStateOf("") }
    var voiceName by remember { mutableStateOf("") }
    var cloned by remember { mutableStateOf<List<ClonedVoice>>(emptyList()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = null
            message = null
            val name = voiceName.trim().ifBlank { "我的音色" }
            runCatching { VoiceCloner.prepareSample(context, uri, name) }
                .onSuccess { file ->
                    sample = file
                    voiceName = name
                    // 上传接口把 text（样本逐字稿）标成必填 → 用端侧 SenseVoice 自动转写。
                    // 转不出来（识别模型没下载 / 样本没人声）就把输入框留给用户手填，不传空串去赌。
                    transcript = VoiceCloner.transcribeSample(context, file).orEmpty()
                    message = if (transcript.isBlank()) {
                        "样本已裁到 ${VoiceCloner.MAX_SAMPLE_SECONDS} 秒内。请补一句这段样本说了什么（服务端必填）"
                    } else {
                        "样本已就绪，逐字稿由端侧识别自动填好，可修改后上传"
                    }
                }
                .onFailure { error = it.message ?: "处理样本失败" }
            busy = false
        }
    }

    SettingsPage(title = "语音合成", onBack = onBack) {
        Spacer(Modifier.height(14.dp))

        NeuSectionTitle("服务商")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            TtsProvider.entries.forEach { p ->
                NeuChip(text = p.label, selected = provider == p, onClick = { provider = p })
                Spacer(Modifier.width(8.dp))
            }
        }

        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = "API Key",
            placeholder = "必填",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        NeuTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = "接口地址",
            placeholder = provider.defaultBaseUrl.ifBlank { "https://api.xxx.com/v1" },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        NeuTextField(
            value = model,
            onValueChange = { model = it },
            label = "模型",
            placeholder = provider.defaultModel.ifBlank { "例：tts-1" },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            NeuButton(
                text = "保存",
                kind = NeuButtonKind.Primary,
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    scope.launch {
                        app.settingsRepository.saveTtsConfig(current)
                        message = "已保存"
                        error = null
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            NeuButton(
                text = if (busy) "处理中…" else "试听",
                kind = NeuButtonKind.Secondary,
                enabled = !busy && current.isComplete,
                modifier = Modifier.weight(1f),
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        message = null
                        val client = TtsClients.from(current)
                        val voice = VoiceRef(provider, TtsVoices.defaultVoiceId(provider))
                        val audio = client.synthesize(VOICE_PREVIEW_TEXT, voice)
                        if (audio == null) {
                            error = "试听失败：${client.lastFailure ?: "未知原因"}"
                        } else {
                            runCatching { playTtsPreview(context, audio.bytes, audio.format.extension) }
                                .onFailure { error = "播放失败：${it.javaClass.simpleName}" }
                                .onSuccess { message = "试听已播放（${audio.format.extension}）" }
                        }
                        busy = false
                    }
                },
            )
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = MutedText)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = NeuDanger)
        }

        // ===== 音色克隆 =====
        Spacer(Modifier.height(18.dp))
        NeuSectionTitle("音色克隆")
        Spacer(Modifier.height(8.dp))
        if (provider != TtsProvider.SILICONFLOW) {
            Text(
                if (provider == TtsProvider.XIAOMI_MIMO) {
                    "MiMo 没有音色上传接口，克隆要在每次合成时内联样本，暂不支持在这里管理。"
                } else {
                    "自定义端点的克隆协议各家不同，请直接填音色 ID。"
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MutedText,
            )
        } else {
            NeuButton(
                text = if (sample == null) "选择样本音频（mp3 / wav）" else "重选样本",
                kind = NeuButtonKind.Secondary,
                enabled = !busy,
                onClick = { picker.launch("audio/*") },
            )
            sample?.let { file ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "样本：${file.name} · ${file.length() / 1024} KB",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                NeuTextField(
                    value = voiceName,
                    onValueChange = { voiceName = it },
                    label = "音色名",
                    placeholder = "例：绘梨衣",
                    modifier = Modifier.fillMaxWidth(),
                )
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
                Spacer(Modifier.height(10.dp))
                NeuButton(
                    text = if (busy) "上传中…" else "上传克隆",
                    kind = NeuButtonKind.Primary,
                    enabled = !busy && current.isComplete && transcript.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            message = null
                            runCatching {
                                VoiceCloner.upload(current, file, voiceName.trim().ifBlank { "我的音色" }, transcript.trim())
                            }.onSuccess { uri ->
                                message = "克隆成功：$uri（去人设编辑里选它）"
                                loadClonedVoices(current) { cloned = it }
                            }.onFailure {
                                // 未实名认证的错误码官方没公开 → 原文透传 + 指一条路，不自己编判断
                                error = "克隆失败：${it.message ?: it.javaClass.simpleName}" +
                                    "（硅基流动的音色克隆需先在官网完成实名认证）"
                            }
                            busy = false
                        }
                    },
                )
            }

            Spacer(Modifier.height(14.dp))
            NeuButton(
                text = "刷新我的克隆音色",
                kind = NeuButtonKind.Secondary,
                enabled = !busy && current.isComplete,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        runCatching { VoiceCloner.listVoices(current) }
                            .onSuccess {
                                cloned = it
                                message = if (it.isEmpty()) "账号下暂无克隆音色" else "已加载 ${it.size} 个克隆音色"
                            }
                            .onFailure { error = "拉取音色列表失败：${it.message ?: it.javaClass.simpleName}" }
                        busy = false
                    }
                },
            )
            cloned.forEach { voice ->
                Spacer(Modifier.height(6.dp))
                Text(voice.customName, fontSize = 13.sp, color = TextPrimary)
                Text(voice.uri, fontSize = 10.sp, color = MutedText)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

private suspend fun loadClonedVoices(config: TtsConfig, onResult: (List<ClonedVoice>) -> Unit) {
    runCatching { VoiceCloner.listVoices(config) }.onSuccess { onResult(it) }
}

/** 试听：TTS 产物落成临时文件交给 MediaPlayer（它认 mp3 / wav，不用我们自己解 PCM） */
internal fun playTtsPreview(context: Context, bytes: ByteArray, ext: String) {
    val file = File(context.cacheDir, "tts_preview.$ext")
    file.writeBytes(bytes)
    android.media.MediaPlayer().apply {
        setDataSource(file.absolutePath)
        setOnCompletionListener { runCatching { it.release() } }
        setOnErrorListener { _, _, _ -> runCatching { release() }; true }
        prepare()
        start()
    }
}
