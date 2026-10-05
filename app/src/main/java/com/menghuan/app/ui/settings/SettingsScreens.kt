package com.menghuan.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.BuildConfig
import com.menghuan.app.data.CrashLog
import com.menghuan.app.data.DEFAULT_THEME_MODE
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.profile.MeViewModel
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import com.menghuan.app.ui.theme.ThemeMode
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
        // v0.54.6：确认框也从底部弹（用户点名"所有弹窗改为从底下弹"）
        NeuSheetScaffold(title = "清除全部数据", onDismiss = { confirmClear = false }) {
            Text(
                "将删除：全部聊天消息、AI 洞察、AI 周报、用户档案、任务与打卡记录，且不可恢复。\n" +
                    "保留：人设、API 接口配置、提示词、主题与通知开关。确定继续吗？",
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = TextPrimary,
            )
            Spacer(Modifier.height(18.dp))
            NeuButton(
                text = "清除",
                kind = NeuButtonKind.Danger,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    vm.clearAllData(context)
                    confirmClear = false
                },
            )
            Spacer(Modifier.height(10.dp))
        }
    }
    if (confirmResetAll) {
        NeuSheetScaffold(title = "一键重置", onDismiss = { confirmResetAll = false }) {
            Text(
                "将清空本机的全部数据与设置：人设、聊天、任务、打卡、档案、洞察、周报、API 配置与各项开关，" +
                    "仅保留内置应用助手。此操作不可恢复，建议先导出备份。确定继续吗？",
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = TextPrimary,
            )
            Spacer(Modifier.height(18.dp))
            NeuButton(
                text = "重置",
                kind = NeuButtonKind.Danger,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    confirmResetAll = false
                    vm.resetAll(context)
                    onBack()
                },
            )
            Spacer(Modifier.height(10.dp))
        }
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

/** 设置 · 关于（v0.54.4：崩溃记录不再占一张卡，改成顶栏右上角的红字入口，点开是整页） */
@Composable
fun AboutScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val hasCrash = CrashLog.hasCrash(context)
    SettingsPage(
        title = "关于",
        onBack = onBack,
        actions = {
            if (hasCrash) {
                Text(
                    "崩溃记录",
                    fontSize = 14.sp,
                    color = NeuDanger,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onOpen(SettingsRoutes.CRASH_LOG) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        },
        actionsWidth = 76.dp,
    ) {
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
    }
}

/** 设置 · 崩溃记录（v0.54.4）：整页显示上次崩溃堆栈，可选中复制、可清除 */
@Composable
fun CrashLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val text = CrashLog.latest(context).orEmpty()
    SettingsPage(title = "崩溃记录", onBack = onBack) {
        Spacer(Modifier.height(14.dp))
        if (text.isBlank()) {
            Text("没有记录", fontSize = 13.sp, color = MutedText)
        } else {
            SelectionContainer {
                Text(text, fontSize = 11.sp, lineHeight = 16.sp, color = TextSecondary)
            }
            Spacer(Modifier.height(18.dp))
            NeuTextButton("清除这条记录", color = NeuDanger, onClick = {
                CrashLog.clear(context)
                onBack()
            })
        }
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * 设置 · 外观（v0.54.1 恢复独立页）。
 *
 * v0.54.0 曾把这三个胶囊并进设置主页第 ④ 组就地显示；用户要求「外观跟原来一样」，
 * 于是回到独立页 —— 设置主页只留一个入口，点进来再选主题模式。
 */
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val app = settingsContainer()
    val scope = rememberCoroutineScope()
    val themeModeName by app.settingsRepository.themeMode
        .collectAsState(initial = DEFAULT_THEME_MODE)

    SettingsPage(title = "外观", onBack = onBack) {
        Spacer(Modifier.height(14.dp))
        SectionLabel("主题")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                NeuChip(text = mode.label, selected = ThemeMode.fromName(themeModeName) == mode) {
                    scope.launch { app.settingsRepository.setThemeMode(mode.name) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}
