package com.menghuan.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.BuildConfig
import com.menghuan.app.data.AsrModelManager
import com.menghuan.app.data.AsrModelRules
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import com.menghuan.app.ui.theme.NeuSurface
import kotlinx.coroutines.launch

/** 本机数据统计快照（数据与隐私面板展示） */
data class DataStats(
    val messages: Long = 0,
    val tasks: Long = 0,
    val personas: Long = 0,
    val facts: Long = 0,
    val reports: Long = 0,
)

/** 设置 · 通知与提醒：任务提醒总开关 + 每日总结/周报通知开关 + 系统通知设置入口 */
@Composable
fun NotificationSettingsSheet(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    dailySummaryEnabled: Boolean,
    onToggleDailySummary: (Boolean) -> Unit,
    weeklyNotifyEnabled: Boolean,
    onToggleWeeklyNotify: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    NeuSheetScaffold(title = "通知与提醒", onDismiss = onDismiss) {
        Spacer(Modifier.height(14.dp))
        SwitchRow(
            title = "任务到点提醒",
            caption = "任务设定的提醒时间到点发系统通知",
            checked = enabled,
            onToggle = onToggle,
        )
        Spacer(Modifier.height(6.dp))
        SwitchRow(
            title = "每日总结 21:30",
            caption = "汇总今天完成 x/y 的通知",
            checked = dailySummaryEnabled,
            onToggle = onToggleDailySummary,
        )
        Spacer(Modifier.height(6.dp))
        SwitchRow(
            title = "AI 周报生成提醒",
            caption = "周日 21:30 生成完成后通知，默认关",
            checked = weeklyNotifyEnabled,
            onToggle = onToggleWeeklyNotify,
        )
        Spacer(Modifier.height(10.dp))
        // v0.38.0：Agent 进度通知（默认开）与主动关心（默认关）——直接读写仓库，开关即时生效
        val app = context.applicationContext as com.menghuan.app.MenghuanApp
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val agentNotify by app.settingsRepository.agentNotifyEnabled.collectAsState(initial = true)
        SwitchRow(
            title = "手机操作进行中通知",
            caption = "AI 操控手机时的常驻进度条，点开回到会话",
            checked = agentNotify,
            onToggle = { scope.launch { app.settingsRepository.setAgentNotifyEnabled(it) } },
        )
        Spacer(Modifier.height(6.dp))
        val proactive by app.settingsRepository.proactiveEnabled.collectAsState(initial = false)
        SwitchRow(
            title = "人设主动关心",
            caption = "每天最多一条 · 晚间发送，基于你的档案与任务",
            checked = proactive,
            onToggle = {
                scope.launch {
                    app.settingsRepository.setProactiveEnabled(it)
                    if (it) com.menghuan.app.notify.AlarmScheduler.scheduleProactive(context)
                    else com.menghuan.app.notify.AlarmScheduler.cancelProactive(context)
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        NeuSheetRow(
            icon = MIcons.Bell,
            title = "系统通知设置",
            subtitle = "横幅 · 锁屏 · 声音",
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            },
        )
    }
}

/** 通知开关行：标题 + 一句功能说明（三个开关行为差异不小，说明小字免去猜） */
@Composable
private fun SwitchRow(
    title: String,
    caption: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(caption, fontSize = 11.sp, lineHeight = 15.sp, color = MutedText)
        }
        Spacer(Modifier.width(10.dp))
        NeuSwitch(checked = checked, onToggle = onToggle)
    }
}

/** 设置 · 数据与隐私：本机数据统计 + 导出/导入/清除全部数据/一键重置（二次确认） */
@Composable
fun DataPrivacySheet(
    stats: DataStats,
    onClearData: () -> Unit,
    onExport: () -> Unit,
    onImport: (android.net.Uri) -> Unit,
    importMessage: String?,
    onResetAll: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onImport) }
    NeuSheetScaffold(title = "数据与隐私", onDismiss = onDismiss) {
        Spacer(Modifier.height(14.dp))
        SectionLabel("本机数据")
        StatRow("聊天消息", "${stats.messages} 条")
        StatRow("任务", "${stats.tasks} 条")
        StatRow("人设", "${stats.personas} 个")
        StatRow("档案条目", "${stats.facts} 项")
        if (stats.reports > 0) StatRow("AI 周报", "${stats.reports} 期")
        Spacer(Modifier.height(16.dp))
        SectionLabel("备份与恢复")
        NeuButton(
            text = "导出全部数据（JSON）",
            kind = NeuButtonKind.Secondary,
            onClick = onExport,
        )
        Spacer(Modifier.height(10.dp))
        NeuButton(
            text = "导入数据（JSON 合并）",
            kind = NeuButtonKind.Secondary,
            onClick = { filePicker.launch("*/*") },
        )
        importMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (message.startsWith("导入失败")) NeuDanger else MutedText,
            )
        }
        Spacer(Modifier.height(16.dp))
        SectionLabel("危险操作")
        NeuButton(
            text = "清除全部数据（保留人设与接口）",
            kind = NeuButtonKind.Danger,
            onClick = { confirmClear = true },
        )
        Spacer(Modifier.height(10.dp))
        NeuButton(
            text = "一键重置（清空全部数据与设置）",
            kind = NeuButtonKind.Danger,
            onClick = { confirmResetAll = true },
        )
        Spacer(Modifier.height(6.dp))
    }
    if (confirmClear) {
        AlertDialog(
            tonalElevation = 0.dp, // v0.42.1 全应用去阴影（该重载阴影来自 tonalElevation）
            onDismissRequest = { confirmClear = false },
            containerColor = NeuSurface,
            title = { Text("清除全部数据", color = TextPrimary) },
            text = {
                Text(
                    "将删除：全部聊天消息、AI 洞察、AI 周报、用户档案、任务与打卡记录，且不可恢复。\n" +
                        "保留：人设、API 接口配置、提示词、主题与通知开关。确定继续吗？",
                    color = TextPrimary,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { onClearData(); confirmClear = false }) {
                    Text("清除", color = NeuDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消", color = MutedText) }
            },
        )
    }
    if (confirmResetAll) {
        AlertDialog(
            tonalElevation = 0.dp, // v0.42.1 全应用去阴影（该重载阴影来自 tonalElevation）
            onDismissRequest = { confirmResetAll = false },
            containerColor = NeuSurface,
            title = { Text("一键重置", color = TextPrimary) },
            text = {
                Text(
                    "将清空本机的全部数据与设置：人设、聊天、任务、打卡、档案、洞察、周报、API 配置与各项开关，" +
                        "仅保留内置应用助手。此操作不可恢复，建议先导出备份。确定继续吗？",
                    color = TextPrimary,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmResetAll = false; onResetAll() }) {
                    Text("重置", color = NeuDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) { Text("取消", color = MutedText) }
            },
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = MutedText, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

/** 面板内分组小标签（与 ApiConfigSheet 的 section 头同一规格） */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = TextSecondary,
        letterSpacing = 0.6.sp,
    )
    Spacer(Modifier.height(8.dp))
}

/** 设置 · 关于 */
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    NeuSheetScaffold(title = "关于", onDismiss = onDismiss) {
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NeuAvatar("🖋️", size = 64.dp, accentRing = true)
            Spacer(Modifier.height(12.dp))
            Text("梦欢", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("版本 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = FaintText)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "AI 陪伴应用：与你自定义的人设对话，风格由你决定；档案自动注入上下文，" +
                "任务到点提醒。\nAI 接口自配。",
            fontSize = 13.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            color = MutedText,
        )
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * 设置 · 语音（v0.50.0）：两个总开关 + 语音合成 / 识别模型两个二级入口。
 *
 * 为什么单独收一个面板：语音这块现在有"能不能说"（TTS）、"能不能听"（识别模型）、
 * "要不要说"（语音条）、"要不要通话"四件事，摊在设置一级会占掉四行；
 * 收进一个「语音」入口，一级列表还是原来那么短。
 */
@Composable
fun VoiceSettingsSheet(
    onDismiss: () -> Unit,
    onTtsConfig: () -> Unit,
    onAsrModel: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as com.menghuan.app.MenghuanApp
    val scope = rememberCoroutineScope()
    val voiceNoteOn by app.settingsRepository.voiceNoteEnabled.collectAsState(initial = true)
    val voiceCallOn by app.settingsRepository.voiceCallEnabled.collectAsState(initial = true)
    val ttsConfig by app.settingsRepository.ttsConfig.collectAsState(initial = TtsConfig())
    // 模型状态只在面板打开时读一次：磁盘 stat 不贵，但也不该每帧重组都去碰
    val asrReady = remember { AsrModelManager.isReady(app) }
    val asrPending = remember { AsrModelManager.pendingBytes(app) }

    NeuSheetScaffold(title = "语音", onDismiss = onDismiss) {
        Spacer(Modifier.height(14.dp))
        SwitchRow(
            title = "AI 语音条",
            caption = "允许 AI 自己决定用语音回复，气泡可长按转文字",
            checked = voiceNoteOn,
            onToggle = { scope.launch { app.settingsRepository.setVoiceNoteEnabled(it) } },
        )
        SwitchRow(
            title = "语音通话",
            caption = "关掉后加号面板不再出现「通话」",
            checked = voiceCallOn,
            onToggle = { scope.launch { app.settingsRepository.setVoiceCallEnabled(it) } },
        )
        Spacer(Modifier.height(12.dp))
        NeuSheetRow(
            icon = MIcons.VoiceWave,
            title = "语音合成",
            subtitle = if (ttsConfig.isComplete) {
                "${ttsConfig.provider.label} · ${ttsConfig.effectiveModel()}"
            } else {
                "未配置 · AI 与通话都不会出声"
            },
            onClick = onTtsConfig,
        )
        Spacer(Modifier.height(10.dp))
        NeuSheetRow(
            icon = MIcons.Mic,
            title = "识别模型",
            subtitle = if (asrReady) {
                "已就绪 · SenseVoice 离线"
            } else {
                "未下载 · 需 ${AsrModelRules.humanSize(asrPending)}"
            },
            onClick = onAsrModel,
        )
        Spacer(Modifier.height(6.dp))
    }
}
