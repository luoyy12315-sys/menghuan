package com.menghuan.app.ui.persona

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.ClonedVoice
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.data.TtsProvider
import com.menghuan.app.data.TtsVoices
import com.menghuan.app.data.VoiceCloner
import com.menghuan.app.data.VoiceRef
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.settings.settingsContainer
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.TextPrimary
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「这个 AI 用什么声音」的选择弹层（v0.53.6）。
 *
 * 从编辑人设页**抽出来共用**：编辑人设里那一项已按要求移除，
 * 现在统一从**对话页右上角菜单 → 音色**打开（谁的声音属于谁，跟人设绑定）。
 *
 * 名单与「设置 → 语音 → 音色」页同源：该家两个预置（一男一女）+ 我克隆的 + 手填 ID。
 * 这里**只挑不试听不添加**（试听与添加只留在音色页那一处）。
 */
@Composable
fun PersonaVoiceSheet(personaId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = settingsContainer()
    val scope = rememberCoroutineScope()

    var config by remember { mutableStateOf(TtsConfig()) }
    var current by remember { mutableStateOf<VoiceRef?>(null) }
    var boardDefaultId by remember { mutableStateOf("") }
    var cloned by remember { mutableStateOf<List<ClonedVoice>>(emptyList()) }
    var aliases by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var localSamples by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(personaId) {
        runCatching {
            config = app.settingsRepository.ttsConfig.first()
            current = app.settingsRepository.voiceRefFor(personaId).first()
            boardDefaultId = app.settingsRepository.ttsDefaultVoice(config.provider).first()?.voiceId.orEmpty()
            aliases = app.settingsRepository.cloneVoiceNames()
            localSamples = if (config.provider == TtsProvider.XIAOMI_MIMO) {
                VoiceCloner.voiceDir(context).listFiles()?.filter {
                    it.isFile && it.name.endsWith(".wav")
                }.orEmpty().map { it.name }
            } else {
                emptyList()
            }
            if (config.provider == TtsProvider.SILICONFLOW && config.isComplete) {
                cloned = runCatching { VoiceCloner.listVoices(config) }.getOrDefault(emptyList())
            }
        }
    }

    val options: List<Pair<VoiceRef, String>> = buildList {
        TtsVoices.presets(config.provider).forEach { preset ->
            val short = preset.label.substringAfterLast(" · ").ifBlank { preset.label }
            add(VoiceRef(config.provider, preset.id) to short)
        }
        when (config.provider) {
            TtsProvider.SILICONFLOW -> cloned.forEach { c ->
                add(VoiceRef(config.provider, c.uri) to (aliases[c.uri] ?: c.customName))
            }
            TtsProvider.XIAOMI_MIMO -> localSamples.forEach { s ->
                add(TtsVoices.localVoiceRef(config.provider, s) to (aliases[s] ?: s.substringBeforeLast('.')))
            }
            TtsProvider.CUSTOM -> Unit
        }
    }
    val effectiveId = current?.takeIf { it.provider == config.provider }?.voiceId?.takeIf { it.isNotBlank() }
        ?: boardDefaultId.ifBlank { TtsVoices.defaultVoiceId(config.provider) }

    NeuSheetScaffold(title = "音色", onDismiss = onDismiss) {
        Spacer(Modifier.height(6.dp))
        Text(
            "选这个 AI 用谁的声音。没单独选过就跟「设置 → 语音 → 音色」里的默认一致。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MutedText,
        )
        Spacer(Modifier.height(8.dp))
        if (options.isEmpty()) {
            Text("还没配语音合成，先去 设置 → 语音 → 语音合成 配一下。", fontSize = 13.sp, color = MutedText)
        }
        options.forEach { (ref, label) ->
            val selected = effectiveId == ref.voiceId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(NeuSurface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        scope.launch {
                            app.settingsRepository.setVoiceRefFor(personaId, ref)
                            onDismiss()
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (selected) Text("当前", fontSize = 12.sp, color = MutedText)
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(10.dp))
    }
}
