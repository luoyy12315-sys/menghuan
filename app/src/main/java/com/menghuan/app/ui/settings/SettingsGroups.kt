package com.menghuan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.data.AsrModelManager
import com.menghuan.app.data.AsrModelRules
import com.menghuan.app.data.DEFAULT_IMAGE_PROFILE_ID
import com.menghuan.app.data.DEFAULT_PROFILE_ID
import com.menghuan.app.data.SEARCH_BOCHA
import com.menghuan.app.data.SEARCH_TAVILY
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.data.WebSearchClient
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.profile.MeViewModel
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import kotlinx.coroutines.launch

/**
 * 设置主页的**二级页**（v0.54.2「方案二」）：主页那些多条目板块（模型配置 / 通话行为 /
 * 提醒与通知）现在都是**一张入口卡**，点进来才是这一页里的几张子卡。
 *
 * 不是手风琴、不是分组——用户原话："折叠，是点进去。"
 */

/** 模型配置（v0.54.2）：对话 / 生图 / 识别 / 语音模型 + 联网搜索，五张卡 */
@Composable
fun ModelConfigScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val app = settingsContainer()

    val profiles by app.settingsRepository.apiProfiles.collectAsState(initial = emptyList())
    val activeProfileId by app.settingsRepository.activeProfileId
        .collectAsState(initial = DEFAULT_PROFILE_ID)
    val imageProfiles by app.settingsRepository.imageProfiles.collectAsState(initial = emptyList())
    val imageActiveId by app.settingsRepository.imageActiveProfileId
        .collectAsState(initial = DEFAULT_IMAGE_PROFILE_ID)
    val ttsConfig by app.settingsRepository.ttsConfig.collectAsState(initial = TtsConfig())
    val searchProvider by app.settingsRepository.searchProvider.collectAsState(initial = SEARCH_BOCHA)
    val searchKey by app.settingsRepository.searchApiKey.collectAsState(initial = "")
    // 模型状态只在进页时读一次：磁盘 stat 不贵，但也不该每帧重组都去碰
    val asrReady = remember { AsrModelManager.isReady(app) }

    val activeProfileName = profiles.firstOrNull { it.id == activeProfileId }?.name
    val imageActiveName = imageProfiles.firstOrNull { it.id == imageActiveId }?.name
    val asrSubtitle = if (asrReady) {
        "已就绪 · SenseVoice 离线"
    } else {
        "未下载 · 需 ${AsrModelRules.humanSize(AsrModelManager.pendingBytes(app))}"
    }

    SettingsPage(title = "模型配置", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        SettingsRow(
            show = true,
            icon = MIcons.Code,
            title = "对话模型",
            subtitle = chatSubtitle(activeProfileName, profiles.size),
            onClick = { onOpen(SettingsRoutes.CHAT_MODEL) },
        )
        SettingsRow(
            show = true,
            icon = MIcons.ImageIcon,
            title = "生图模型",
            subtitle = imageSubtitle(imageActiveName, imageProfiles.size),
            onClick = { onOpen(SettingsRoutes.IMAGE_MODEL) },
        )
        SettingsRow(
            show = true,
            icon = MIcons.Mic,
            title = "识别模型",
            subtitle = asrSubtitle,
            onClick = { onOpen(SettingsRoutes.ASR_MODEL) },
        )
        SettingsRow(
            show = true,
            icon = MIcons.VoiceWave,
            title = "语音模型",
            subtitle = ttsSubtitle(ttsConfig),
            onClick = { onOpen(SettingsRoutes.TTS_CONFIG) },
        )
        SettingsRow(
            show = true,
            icon = MIcons.Search,
            title = "联网搜索",
            subtitle = searchSubtitle(searchProvider, searchKey),
            onClick = { onOpen(SettingsRoutes.SEARCH_CONFIG) },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/** 通话行为（v0.54.2）：语音通话铃声（v0.54.5：AI 主动打电话已从设置里删掉 —— 它按角色配，
 *  在**每个角色会话右上角 ⋮ → 整页设置**里的「允许主动打电话」卡片上，含时间与阈值） */
@Composable
fun CallBehaviorScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    SettingsPage(title = "通话行为", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        SettingsRow(
            show = true,
            icon = MIcons.Bell,
            title = "语音通话铃声",
            subtitle = "来电与去电共用 · 可传音频或视频",
            onClick = { onOpen(SettingsRoutes.CALL_RINGTONE) },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/** 提醒与通知（v0.54.2）：三个开关（联网搜索已挪去「模型配置」；v0.54.4 定时主动消息与
 *  人设主动关心按角色移到「会话设置」页，这里不再放） */
@Composable
fun RemindersScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val app = settingsContainer()
    val scope = rememberCoroutineScope()
    val vm: MeViewModel = viewModel(factory = MeViewModel.Factory)

    val notifyOn by vm.notificationsEnabled.collectAsState()
    val agentNotify by app.settingsRepository.agentNotifyEnabled.collectAsState(initial = true)

    SettingsPage(title = "提醒与通知", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        SettingsSwitchRow(
            show = true,
            title = "任务到点提醒",
            caption = "任务设定的提醒时间到点发系统通知",
            checked = notifyOn,
            onToggle = { vm.setNotificationsEnabled(it) },
        )
        SettingsSwitchRow(
            show = true,
            title = "手机操作进行中通知",
            caption = "AI 操控手机时的常驻进度条，点开回到会话",
            checked = agentNotify,
            onToggle = { on -> scope.launch { app.settingsRepository.setAgentNotifyEnabled(on) } },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * 联网搜索（v0.54.2）：从「定时主动消息」里**拎出来独立成页**（用户明确要求：
 * "博查那个联网搜索你不能提出来吗？放到定时主动消息那里干嘛？"），入口挂在「模型配置」下。
 */
@Composable
fun WebSearchConfigScreen(onBack: () -> Unit) {
    val app = settingsContainer()
    val scope = rememberCoroutineScope()

    val provider by app.settingsRepository.searchProvider.collectAsState(initial = SEARCH_BOCHA)
    val searchKey by app.settingsRepository.searchApiKey.collectAsState(initial = "")

    // 草稿跟着已存值走：DataStore 首次读到真实值时会刷新一次，之后用户编辑不再被覆盖
    var providerDraft by remember(provider) { mutableStateOf(provider) }
    var keyDraft by remember(searchKey) { mutableStateOf(searchKey) }

    SettingsPage(title = "联网搜索", onBack = onBack) {
        Spacer(Modifier.height(14.dp))
        Text("服务商", fontSize = 12.sp, color = MutedText)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeuChip(text = "博查（国内）", selected = providerDraft == SEARCH_BOCHA) {
                providerDraft = SEARCH_BOCHA
            }
            NeuChip(text = "Tavily（国际）", selected = providerDraft == SEARCH_TAVILY) {
                providerDraft = SEARCH_TAVILY
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "去 ${WebSearchClient.consoleUrlOf(providerDraft)} 注册并在控制台创建 API Key，粘贴到下面。" +
                "接口：${WebSearchClient.endpointOf(providerDraft)}",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MutedText,
        )
        Spacer(Modifier.height(12.dp))
        NeuTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = "API Key",
            placeholder = "粘贴控制台里的 Key",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        NeuButton(
            text = "保存",
            kind = NeuButtonKind.Primary,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    app.settingsRepository.setSearchProvider(providerDraft)
                    app.settingsRepository.setSearchApiKey(keyDraft.trim())
                }
                onBack()
            },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/** 对话模型副标题：当前生效那条的名字；一条都没有就明说聊天不可用 */
private fun chatSubtitle(activeName: String?, profileCount: Int): String = when {
    profileCount == 0 -> "未配置 · 聊天暂不可用"
    else -> "当前：${activeName ?: "默认模型"}"
}

/** 生图模型副标题：当前生效那条的名字；一条都没有就明说不会画图 */
private fun imageSubtitle(activeName: String?, profileCount: Int): String = when {
    profileCount == 0 -> "未配置 · 不会画图"
    else -> "当前：${activeName ?: "默认生图模型"}"
}

/** 语音模型副标题：哪家 + 哪个模型；没配就明说"不出声" */
private fun ttsSubtitle(cfg: TtsConfig): String =
    if (cfg.isComplete) "${cfg.provider.label} · ${cfg.effectiveModel()}" else "未配置 · AI 与通话都不会出声"

/** 联网搜索副标题：哪家 + 配没配 Key（不识别的别名交给上层搜索） */
private fun searchSubtitle(provider: String, key: String): String =
    if (key.isBlank()) "${WebSearchClient.labelOf(provider)} · 未配置 Key" else WebSearchClient.labelOf(provider)