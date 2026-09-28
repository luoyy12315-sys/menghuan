package com.daoshi.app.ui.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoshi.app.BuildConfig
import com.daoshi.app.ui.common.NeuAvatar
import com.daoshi.app.ui.common.NeuButton
import com.daoshi.app.ui.common.NeuButtonKind
import com.daoshi.app.ui.common.NeuSheetRow
import com.daoshi.app.ui.common.NeuSheetScaffold
import com.daoshi.app.ui.common.NeuSwitch
import com.daoshi.app.ui.theme.FaintText
import com.daoshi.app.ui.theme.MIcons
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.NeuDanger
import com.daoshi.app.ui.theme.TextPrimary

/** 本机数据统计快照（数据与隐私面板展示） */
data class DataStats(
    val messages: Long = 0,
    val tasks: Long = 0,
    val personas: Long = 0,
    val facts: Long = 0,
)

/** 设置 · 通知与提醒：任务提醒总开关 + 每日总结开关 + 系统通知设置入口 */
@Composable
fun NotificationSettingsSheet(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    dailySummaryEnabled: Boolean,
    onToggleDailySummary: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    NeuSheetScaffold(title = "通知与提醒", onDismiss = onDismiss) {
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("任务到点提醒", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            NeuSwitch(checked = enabled, onToggle = onToggle)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("每日总结 21:30", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            NeuSwitch(checked = dailySummaryEnabled, onToggle = onToggleDailySummary)
        }
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

/** 设置 · 数据与隐私：本机数据统计 + 导出/导入/清除聊天记录（二次确认） */
@Composable
fun DataPrivacySheet(
    stats: DataStats,
    onClearChats: () -> Unit,
    onExport: () -> Unit,
    onExportWeekly: () -> Unit = {},
    onImport: (android.net.Uri) -> Unit,
    importMessage: String?,
    onDismiss: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(onImport) }
    NeuSheetScaffold(title = "数据与隐私", onDismiss = onDismiss) {
        Spacer(Modifier.height(14.dp))
        StatRow("聊天消息", "${stats.messages} 条")
        StatRow("任务", "${stats.tasks} 条")
        StatRow("人设", "${stats.personas} 个")
        StatRow("档案条目", "${stats.facts} 项")
        Spacer(Modifier.height(16.dp))
        NeuButton(
            text = "导出周报（Markdown）",
            kind = NeuButtonKind.Secondary,
            onClick = onExportWeekly,
        )
        Spacer(Modifier.height(10.dp))
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
        Spacer(Modifier.height(10.dp))
        NeuButton(
            text = "清除全部聊天记录",
            kind = NeuButtonKind.Danger,
            onClick = { confirmClear = true },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = androidx.compose.ui.graphics.Color.White,
            title = { Text("清除聊天记录", color = TextPrimary) },
            text = { Text("将删除全部会话消息（人设与任务保留），且不可恢复。确定继续吗？", color = TextPrimary) },
            confirmButton = {
                TextButton(onClick = { onClearChats(); confirmClear = false }) {
                    Text("清除", color = NeuDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消", color = MutedText) }
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
            Text("导师", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(4.dp))
            Text("版本 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = FaintText)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "个人全能 AI 导师：与导师对话获得指导，档案自动注入上下文，" +
                "任务到点督促打卡。\nAI 接口由你自己配置，数据仅存本机。",
            fontSize = 13.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            color = MutedText,
        )
        Spacer(Modifier.height(6.dp))
    }
}
