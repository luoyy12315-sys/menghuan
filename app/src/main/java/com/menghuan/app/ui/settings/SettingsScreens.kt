package com.menghuan.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.BuildConfig
import com.menghuan.app.data.CrashLog
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.profile.MeViewModel
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** 本机数据统计快照（数据与隐私页展示） */
data class DataStats(
    val messages: Long = 0,
    val tasks: Long = 0,
    val personas: Long = 0,
    val facts: Long = 0,
    val reports: Long = 0,
)

/** 设置 · 数据与隐私：本机数据统计 + 导出/导入/清除全部数据/一键重置（二次确认） */
@Composable
fun DataPrivacyScreen(
    onBack: () -> Unit,
    vm: MeViewModel = viewModel(factory = MeViewModel.Factory),
) {
    val context = LocalContext.current
    val stats by vm.dataStats.collectAsState()
    val importMessage by vm.importMessage.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmResetAll by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.importData(context, it) }
    }

    SettingsPage(title = "数据与隐私", onBack = onBack) {
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
            onClick = { vm.exportData(context) },
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
                TextButton(onClick = { vm.clearAllData(context); confirmClear = false }) {
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
                TextButton(
                    onClick = {
                        confirmResetAll = false
                        vm.resetAll(context)
                        onBack()
                    },
                ) { Text("重置", color = NeuDanger) }
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

/** 页面内分组小标签（与 API 配置页的 section 头同一规格） */
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
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var showCrash by remember { mutableStateOf(false) }
    SettingsPage(title = "关于", onBack = onBack) {
        Spacer(Modifier.height(24.dp))
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
        Spacer(Modifier.height(18.dp))
        // 崩溃现场（v0.53.1）：真机没有 logcat，闪退后什么线索都不留。
        // 现在崩溃前会写 filesDir/crash.log，这里给一个入口看到它——截图发过来就能定位。
        if (CrashLog.hasCrash(context)) {
            NeuSheetRow(
                icon = MIcons.Bell,
                title = "上次崩溃记录",
                subtitle = "点这里查看堆栈",
                onClick = { showCrash = true },
            )
        }
        Spacer(Modifier.height(6.dp))
    }
    if (showCrash) {
        SettingsCardDialog(title = "上次崩溃记录", onDismiss = { showCrash = false }) {
            SelectionContainer {
                Text(
                    CrashLog.latest(context).orEmpty(),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.height(14.dp))
            NeuTextButton("清除这条记录", color = NeuDanger, onClick = {
                CrashLog.clear(context)
                showCrash = false
            })
        }
    }
}
