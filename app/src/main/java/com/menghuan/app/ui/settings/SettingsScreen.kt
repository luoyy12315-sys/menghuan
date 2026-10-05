package com.menghuan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.BuildConfig
import com.menghuan.app.data.AsrModelManager
import com.menghuan.app.data.AsrModelRules
import com.menghuan.app.data.DEFAULT_PROFILE_ID
import com.menghuan.app.data.DEFAULT_THEME_MODE
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.notify.AlarmScheduler
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.profile.MeViewModel
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * 设置主页（v0.54.0 重构）。
 *
 * ## 改了什么
 * 以前是 **7 个入口一列平铺、没有小标题** ——「API 配置」和「关于」是同一种卡片，
 * 视觉权重完全一致，用户看不出哪个是天天要改的、哪个只是查一下版本号。
 * 现在按**职责分四组**，组标题带一句"这组管什么"：
 *
 * ① **AI 与模型** —— 喂给模型的东西（对话 / 提示词），改完立刻影响每一次对话
 * ② **语音与通话** —— 合成与识别（能力）、打电话与铃声（行为）
 * ③ **提醒与通知** —— 全部**开关就地显示**，不用点进二级页才知道当前状态
 * ④ **数据与系统** —— 低频项；外观只有 3 个胶囊，就地平铺，不再单独占一页
 *
 * ## 三条结构决定（v0.54.0 用户拍板）
 * - **组标题只是视觉标签，不可点、不折叠** —— 用户要的是"全屏显示"：
 *   每张卡片点进去仍然是**全屏页** + 顶部返回按钮。
 * - **「语音」这个中转层取消了**：原来 5 个子项要先点「语音」才看得到，
 *   现在直接上提到第 ② 组（设置链最深 4 层 → 2 层）。
 * - **「外观」「通知与提醒」两页取消**：三个胶囊与三个开关都搬回主页就地显示。
 *
 * ## 搜索
 * 顶部搜索框按标题 / 副标题过滤下面所有行卡与开关。分组标签**不参与过滤也不隐藏**，
 * 命中项照常显示在自己那一组下面 —— 避免"搜出来的东西飘在组外、看不出归属"。
 * 空查询时恢复完整列表。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val app = settingsContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: MeViewModel = viewModel(factory = MeViewModel.Factory)

    var query by remember { mutableStateOf("") }

    val profiles by app.settingsRepository.apiProfiles.collectAsState(initial = emptyList())
    val activeProfileId by app.settingsRepository.activeProfileId
        .collectAsState(initial = DEFAULT_PROFILE_ID)
    val imageProfiles by app.settingsRepository.imageProfiles.collectAsState(initial = emptyList())
    val themeModeName by app.settingsRepository.themeMode.collectAsState(initial = DEFAULT_THEME_MODE)
    val ttsConfig by app.settingsRepository.ttsConfig.collectAsState(initial = TtsConfig())
    // 模型状态只在进页时读一次：磁盘 stat 不贵，但也不该每帧重组都去碰
    val asrReady = remember { AsrModelManager.isReady(app) }

    // 通知类开关：就地显示，状态一眼可见（v0.54.0 取消了「通知与提醒」二级页）
    val notifyOn by vm.notificationsEnabled.collectAsState()
    val agentNotify by app.settingsRepository.agentNotifyEnabled.collectAsState(initial = true)
    val proactive by app.settingsRepository.proactiveEnabled.collectAsState(initial = false)

    val activeProfileName = profiles.firstOrNull { it.id == activeProfileId }?.name
    val asrSubtitle = if (asrReady) {
        "已就绪 · SenseVoice 离线"
    } else {
        "未下载 · 需 ${AsrModelRules.humanSize(AsrModelManager.pendingBytes(app))}"
    }
    val notifySubtitle = "已开 " +
        listOf(notifyOn, agentNotify, proactive).count { it } + "/3 项"

    // 权限汇总（v0.53.8）：进页与从系统设置返回时都重查一次
    var permTick by remember { mutableStateOf(0) }
    val permLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(permLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permTick++
        }
        permLifecycle.lifecycle.addObserver(observer)
        onDispose { permLifecycle.lifecycle.removeObserver(observer) }
    }
    val permSubtitle = remember(permTick, context) {
        val (missingRequired, missingOptional) = permissionSummary(context)
        when {
            missingRequired > 0 -> "还有 $missingRequired 项必开权限未开启"
            missingOptional > 0 -> "已就绪 · $missingOptional 项可选未开启"
            else -> "全部已开启"
        }
    }

    // 搜索命中判据 = 标题或副标题含关键词（不区分大小写）。
    // 每行都额外塞了几个**用户真会搜的别名**（如"AI 接口"→ 对话模型、"ASR" → 识别模型），
    // 否则搜"接口"会一条都搜不到。
    fun hit(vararg parts: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return parts.any { it.contains(q, ignoreCase = true) }
    }

    SettingsPage(title = "设置", onBack = onBack) {
        Spacer(Modifier.height(10.dp))
        SettingsSearchField(value = query, onValueChange = { query = it })
        if (query.isNotBlank()) {
            Text(
                "搜索「${query.trim()}」",
                fontSize = 11.sp,
                color = MutedText,
                modifier = Modifier.padding(top = 8.dp, bottom = 0.dp),
            )
        }

        // ───────────── ① AI 与模型 ─────────────
        SettingsGroupLabel("① AI 与模型", "喂给模型的东西，改完立刻影响每一次对话")
        SettingsRow(
            show = hit("对话模型", activeProfileName ?: "未配置", "AI 接口", "聊天模型"),
            icon = MIcons.Code,
            title = "对话模型",
            subtitle = apiSubtitle(activeProfileName, profiles.size, imageProfiles.any { it.isComplete }),
            onClick = { onOpen(SettingsRoutes.API_CONFIG) },
        )
        SettingsRow(
            show = hit("AI 提示词", "人设设定词 基础提示词 档案注入", "提示词"),
            icon = MIcons.ChatBubble,
            title = "AI 提示词",
            subtitle = "人设设定词 · 基础提示词 · 档案注入说明",
            onClick = { onOpen(SettingsRoutes.PROMPTS) },
        )

        // ───────────── ② 语音与通话 ─────────────
        SettingsGroupLabel("② 语音与通话", "让 AI 出声、听懂你，并接听你打去的电话")
        SettingsSubLabel("合成与识别")
        SettingsRow(
            show = hit("语音合成", ttsSubtitle(ttsConfig), "TTS", "出声"),
            icon = MIcons.VoiceWave,
            title = "语音合成",
            subtitle = ttsSubtitle(ttsConfig),
            onClick = { onOpen(SettingsRoutes.TTS_CONFIG) },
        )
        SettingsRow(
            show = hit("音色", "男声 女声 克隆", "声音", "TTS"),
            icon = MIcons.Person,
            title = "音色",
            subtitle = "男声 / 女声 / 我克隆的声音 · 可试听",
            onClick = { onOpen(SettingsRoutes.VOICE_LIBRARY) },
        )
        SettingsRow(
            show = hit("识别模型", asrSubtitle, "ASR", "听懂"),
            icon = MIcons.Mic,
            title = "识别模型",
            subtitle = asrSubtitle,
            onClick = { onOpen(SettingsRoutes.ASR_MODEL) },
        )
        SettingsSubLabel("通话行为")
        SettingsRow(
            show = hit("AI 主动打电话", "主动打给我 到点", "来电"),
            icon = MIcons.Phone,
            title = "AI 主动打电话",
            subtitle = "到点、或太久没聊，TA 会打给你（默认关）",
            onClick = { onOpen(SettingsRoutes.AI_CALL) },
        )
        SettingsRow(
            show = hit("语音通话铃声", "来电 去电 铃声 响铃", "接听延迟"),
            icon = MIcons.Bell,
            title = "语音通话铃声",
            subtitle = "来电与去电共用 · 可传音频或视频",
            onClick = { onOpen(SettingsRoutes.CALL_RINGTONE) },
        )
        SettingsRow(
            show = hit("定时主动消息", "到点主动发 联网搜索", "主动消息"),
            icon = MIcons.ChatBubble,
            title = "定时主动消息",
            subtitle = "指定人设到点主动发给你 · 可配多条、可联网搜最新",
            onClick = { onOpen(SettingsRoutes.SCHEDULED_MESSAGE) },
        )

        // ───────────── ③ 提醒与通知 ─────────────
        SettingsGroupLabel("③ 提醒与通知", "什么情况下让 App 主动打扰你")
        SettingsSwitchRow(
            show = hit("任务到点提醒", "任务提醒"),
            title = "任务到点提醒",
            caption = "任务设定的提醒时间到点发系统通知",
            checked = notifyOn,
            onToggle = { vm.setNotificationsEnabled(it) },
        )
        SettingsSwitchRow(
            show = hit("手机操作进行中通知", "操作通知 进度条"),
            title = "手机操作进行中通知",
            caption = "AI 操控手机时的常驻进度条，点开回到会话",
            checked = agentNotify,
            onToggle = { on -> scope.launch { app.settingsRepository.setAgentNotifyEnabled(on) } },
        )
        SettingsSwitchRow(
            show = hit("人设主动关心", "主动关心 问候"),
            title = "人设主动关心",
            caption = "每天最多一条 · 晚间发送，基于你的档案与任务",
            checked = proactive,
            onToggle = { on ->
                scope.launch {
                    app.settingsRepository.setProactiveEnabled(on)
                    if (on) {
                        AlarmScheduler.scheduleProactive(context)
                    } else {
                        AlarmScheduler.cancelProactive(context)
                    }
                }
            },
        )

        // ───────────── ④ 数据与系统 ─────────────
        SettingsGroupLabel("④ 数据与系统", "低频项：备份、授权、主题与版本")
        SettingsInlineLabel("外观")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                NeuChip(text = mode.label, selected = ThemeMode.fromName(themeModeName) == mode) {
                    scope.launch { app.settingsRepository.setThemeMode(mode.name) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        SettingsRow(
            show = hit("权限", permSubtitle, "授权", "通知 麦克风 相机 闹钟"),
            icon = MIcons.Shield,
            title = "权限",
            subtitle = permSubtitle,
            onClick = { onOpen(SettingsRoutes.PERMISSIONS) },
        )
        SettingsRow(
            show = hit("数据与隐私", "导出 导入 清除 重置 备份", "数据"),
            icon = MIcons.FileText,
            title = "数据与隐私",
            subtitle = "导出 · 导入 · 清除数据",
            onClick = { onOpen(SettingsRoutes.DATA_PRIVACY) },
        )
        SettingsRow(
            show = hit("关于", "版本 ${BuildConfig.VERSION_NAME}", "崩溃 记录"),
            icon = MIcons.Info,
            title = "关于",
            subtitle = "版本 ${BuildConfig.VERSION_NAME}",
            onClick = { onOpen(SettingsRoutes.ABOUT) },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * 设置链的路由名（MainActivity 注册、各页跳转都引这里，避免字符串字面量散落各处写错）。
 *
 * v0.54.0 删掉三个：`APPEARANCE`（三个胶囊并入主页第 ④ 组）、
 * `NOTIFICATIONS`（三个开关并入主页第 ③ 组）、`VOICE`（5 个子项上提，取消中转层）。
 */
object SettingsRoutes {
    const val SETTINGS = "settings"
    const val API_CONFIG = "api_config"
    const val DATA_PRIVACY = "data_privacy"
    const val PERMISSIONS = "permissions"
    const val ABOUT = "about"
    const val TTS_CONFIG = "tts_config"
    const val VOICE_LIBRARY = "voice_library"
    const val AI_CALL = "ai_call"
    const val CALL_RINGTONE = "call_ringtone"
    const val SCHEDULED_MESSAGE = "scheduled_message"
    const val ASR_MODEL = "asr_model"
    const val PROMPTS = "prompts"
    const val PROFILE = "profile"
    const val GALLERY = "gallery"
}

/** 对话模型副标题：默认模型名 + 生图模型状态，一眼看清聊天用的是谁 */
private fun apiSubtitle(activeName: String?, profileCount: Int, imageReady: Boolean): String = when {
    profileCount == 0 -> "未配置 · AI 暂不可用"
    else -> buildString {
        append("当前：").append(activeName ?: "默认模型")
        append(" · 生图").append(if (imageReady) "已配置" else "未配置")
    }
}

/** 语音合成副标题：哪家 +哪个模型；没配就明说"不出声" */
private fun ttsSubtitle(cfg: TtsConfig): String =
    if (cfg.isComplete) "${cfg.provider.label} · ${cfg.effectiveModel()}" else "未配置 · AI 与通话都不会出声"
