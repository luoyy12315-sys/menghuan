package com.menghuan.app.ui.realtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/** Edge TTS 中文音色预设（voice name 即微软声音名，均可用自定义导入替换） */
private val PRESET_VOICES = listOf(
    "zh-CN-XiaoxiaoNeural" to "晓晓 · 女",
    "zh-CN-XiaoyiNeural" to "晓伊 · 女",
    "zh-CN-YunxiNeural" to "云希 · 男",
    "zh-CN-YunjianNeural" to "云健 · 男",
    "zh-CN-YunyangNeural" to "云扬 · 播音",
    "zh-CN-YunxiaNeural" to "云夏 · 少年",
    "zh-CN-liaoning-XiaobeiNeural" to "晓北 · 东北",
    "zh-CN-shaanxi-XiaoniNeural" to "晓妮 · 陕西",
    "zh-TW-HsiaoChenNeural" to "晓臻 · 台湾",
    "zh-HK-HiuMaanNeural" to "曉曼 · 粤语",
)

/**
 * 人设通话设置（v0.47.0，从单聊页顶栏三点进入）：
 * AI 音色（预设 + 自定义导入）、通话模型覆盖、接通/挂断提示音、转写保存。
 * 全部即时写入 DataStore，下一次通话生效。
 */
@Composable
fun PersonaCallSettingsSheet(
    settings: SettingsRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentVoice by settings.callVoice.collectAsState(initial = "zh-CN-XiaoxiaoNeural")
    val toneEnabled by settings.callToneEnabled.collectAsState(initial = true)
    val saveTranscript by settings.callSaveTranscript.collectAsState(initial = true)
    var customVoice by remember { mutableStateOf("") }

    NeuSheetScaffold(title = "通话设置", onDismiss = onDismiss) {
        Spacer(Modifier.height(6.dp))
        Text("AI 音色", fontSize = 12.sp, color = MutedText)
        Spacer(Modifier.height(8.dp))
        PRESET_VOICES.forEach { (voice, label) ->
            NeuSheetRow(
                icon = MIcons.Mic,
                title = label,
                subtitle = voice,
                selected = currentVoice == voice,
                onClick = { scope.launch { settings.setCallVoice(voice) } },
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("自定义音色（粘贴任意 Edge TTS 声音名）", fontSize = 12.sp, color = MutedText)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeuTextField(
                value = customVoice,
                onValueChange = { customVoice = it },
                modifier = Modifier.weight(1f),
                placeholder = "如 zh-CN-YunyeNeural",
            )
            Spacer(Modifier.width(8.dp))
            NeuChip(
                text = "导入",
                selected = false,
                onClick = {
                    val v = customVoice.trim()
                    if (v.isNotEmpty()) {
                        scope.launch { settings.setCallVoice(v) }
                        customVoice = ""
                    }
                },
            )
        }
        if (!PRESET_VOICES.any { it.first == currentVoice }) {
            Spacer(Modifier.height(6.dp))
            Text("当前音色：$currentVoice", fontSize = 11.sp, color = FaintText)
        }
        Spacer(Modifier.height(16.dp))
        Text("通话模型（留空 = 用当前对话接口的模型）", fontSize = 12.sp, color = MutedText)
        Spacer(Modifier.height(6.dp))
        ModelField(settings)
        Spacer(Modifier.height(16.dp))
        SettingToggle(
            title = "接通 / 挂断提示音",
            checked = toneEnabled,
            onToggle = { scope.launch { settings.setCallToneEnabled(it) } },
        )
        SettingToggle(
            title = "通话后保存文字记录",
            checked = saveTranscript,
            onToggle = { scope.launch { settings.setCallSaveTranscript(it) } },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/** 模型覆盖输入：停顿 600ms 自动保存（防抖） */
@Composable
private fun ModelField(settings: SettingsRepository) {
    val current by settings.callModel.collectAsState(initial = "")
    var text by remember(current) { mutableStateOf(current) }
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(600)
        if (text.trim() != current) settings.setCallModel(text)
    }
    NeuTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = "如 deepseek-chat（避开 reasoner 深度思考的等待）",
    )
}

@Composable
private fun SettingToggle(title: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 14.sp, color = TextPrimary)
        NeuSwitch(checked = checked, onToggle = onToggle)
    }
}
