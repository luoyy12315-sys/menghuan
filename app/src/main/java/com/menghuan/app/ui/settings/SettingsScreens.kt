package com.menghuan.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.toArgb
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
import com.menghuan.app.ui.common.NeuSwitch
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
                "将删除：全部聊天消息、AI 洞察、用户档案、任务与打卡记录，且不可恢复。\n" +
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
                "将清空本机的全部数据与设置：人设、聊天、任务、打卡、档案、洞察、API 配置与各项开关，" +
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
 *
 * v0.77.0（用户点名「自定义聊天框的形式、字体、颜色、是否展示头像」+「用户头像功能」）：
 * 追加两段 —— **聊天气泡外观**（全局一套）与**我的头像**。都放这里而不是会话设置页：
 * 这两样是"整个 App 的观感 / 我是谁"，跟具体某个角色无关，会话设置页按角色进去会误导。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val app = settingsContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeModeName by app.settingsRepository.themeMode
        .collectAsState(initial = DEFAULT_THEME_MODE)
    val appearance by app.settingsRepository.chatAppearance
        .collectAsState(initial = com.menghuan.app.data.ChatAppearance())
    // v0.80.0：头像设置已移出外观页 → 「我的页顶部档案卡 → 个人档案」。
    // 头像是"我是谁"的一部分，和"整个 App 的观感"不是一回事，不再混在这一页里。

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
        // ===== 聊天气泡（v0.80.0 推倒重做：色盘自由配色，明暗各存一套）=====
        SectionLabel("聊天气泡")
        // 编辑对象：明暗两套分开调（用户拍板）。切过去时预览底也跟着换，所见即所得。
        var editingDark by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeuChip(text = "浅色主题", selected = !editingDark) { editingDark = false }
            NeuChip(text = "深色主题", selected = editingDark) { editingDark = true }
        }
        Spacer(Modifier.height(10.dp))
        BubblePreviewCard(appearance, editingDark)
        Spacer(Modifier.height(14.dp))

        val editingTheme = if (editingDark) appearance.dark else appearance.light
        BubbleStyleEditor(
            title = "自己发的",
            style = editingTheme.mine,
            isMine = true,
            appearance = appearance,
            onChange = { next ->
                scope.launch {
                    val updated = if (editingDark) {
                        appearance.copy(dark = appearance.dark.copy(mine = next))
                    } else {
                        appearance.copy(light = appearance.light.copy(mine = next))
                    }
                    app.settingsRepository.setChatAppearance(updated)
                }
            },
        )
        Spacer(Modifier.height(14.dp))
        BubbleStyleEditor(
            title = "对方发的",
            style = editingTheme.other,
            isMine = false,
            appearance = appearance,
            onChange = { next ->
                scope.launch {
                    val updated = if (editingDark) {
                        appearance.copy(dark = appearance.dark.copy(other = next))
                    } else {
                        appearance.copy(light = appearance.light.copy(other = next))
                    }
                    app.settingsRepository.setChatAppearance(updated)
                }
            },
        )
        Spacer(Modifier.height(16.dp))

        // ===== 字体（全局一套，不分明暗）=====
        SectionLabel("字体")
        Text("字体族", fontSize = 13.sp, color = TextPrimary, modifier = Modifier.padding(bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.menghuan.app.data.ChatAppearance.FontFamilyKey.entries.forEach { f ->
                NeuChip(text = f.label, selected = appearance.fontFamily == f) {
                    scope.launch {
                        app.settingsRepository.setChatAppearance(appearance.copy(fontFamily = f))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "字号 ${appearance.fontSizeSp}sp",
            fontSize = 13.sp,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        androidx.compose.material3.Slider(
            value = appearance.fontSizeSp.toFloat(),
            onValueChange = {
                scope.launch {
                    app.settingsRepository.setChatAppearance(appearance.copy(fontSizeSp = it.toInt()))
                }
            },
            valueRange = com.menghuan.app.data.ChatAppearance.MIN_FONT_SP.toFloat()
                ..com.menghuan.app.data.ChatAppearance.MAX_FONT_SP.toFloat(),
            // 步进 = 区间长度：落点全是整数 sp，不出现 15.4sp 这种值
            steps = com.menghuan.app.data.ChatAppearance.MAX_FONT_SP - com.menghuan.app.data.ChatAppearance.MIN_FONT_SP - 1,
        )
        Text("字重", fontSize = 13.sp, color = TextPrimary, modifier = Modifier.padding(bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.menghuan.app.data.ChatAppearance.FontWeightKey.entries.forEach { w ->
                NeuChip(text = w.label, selected = appearance.fontWeightKey == w) {
                    scope.launch {
                        app.settingsRepository.setChatAppearance(appearance.copy(fontWeightKey = w))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("显示头像", fontSize = 13.sp, color = TextPrimary, modifier = Modifier.weight(1f))
            com.menghuan.app.ui.common.NeuSwitch(
                checked = appearance.showAvatar,
                onToggle = { on ->
                    scope.launch { app.settingsRepository.setChatAppearance(appearance.copy(showAvatar = on)) }
                },
            )
        }
        Spacer(Modifier.height(10.dp))
        // v0.80.0：旧「我的头像」段从外观页移除 —— 头像归「我的页 → 个人档案」管
        // （头像是谁的一部分就该在哪儿设置，外观页只剩"观感"）。
    }
}

/**
 * 实时预览卡（v0.80.0）：一段示例对话按当前正在编辑的那套配置渲染，
 * 每次改色 / 改圆角 / 改字体立即生效 —— 不用切去聊天页核对。
 * 预览底色取对应主题的 track（明 #F2F3F5 / 暗 #24242B），跟真实聊天页画布同一颗灰。
 */
@Composable
private fun BubblePreviewCard(
    appearance: com.menghuan.app.data.ChatAppearance,
    editingDark: Boolean,
) {
    val theme = if (editingDark) appearance.dark else appearance.light
    val mine = com.menghuan.app.ui.theme.resolveBubble(theme.mine, isMine = true, appearance = appearance)
    val other = com.menghuan.app.ui.theme.resolveBubble(theme.other, isMine = false, appearance = appearance)
    val previewBg = if (editingDark) androidx.compose.ui.graphics.Color(0xFF24242B)
    else androidx.compose.ui.graphics.Color(0xFFF2F3F5)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(previewBg)
            .padding(14.dp),
    ) {
        Text(
            "预览 · ${if (editingDark) "深色" else "浅色"}主题",
            fontSize = 11.sp,
            color = if (editingDark) androidx.compose.ui.graphics.Color(0xFF85858F)
            else androidx.compose.ui.graphics.Color(0xFF595959),
        )
        Spacer(Modifier.height(10.dp))
        BubbleSample(
            resolved = other,
            text = "嗨，我在",
            alignEnd = false,
        )
        Spacer(Modifier.height(8.dp))
        BubbleSample(
            resolved = mine,
            text = "你好呀，看看这个新配色",
            alignEnd = true,
        )
    }
}

/** 预览里的一条气泡（真实气泡的同款画法：圆角 + 可选边框 + 配置字体） */
@Composable
private fun BubbleSample(
    resolved: com.menghuan.app.ui.theme.ResolvedBubble,
    text: String,
    alignEnd: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(resolved.radiusDp.coerceAtLeast(0).dp))
                .background(resolved.bg)
                .then(
                    resolved.border?.let {
                        Modifier.border(1.dp, it, RoundedCornerShape(resolved.radiusDp.coerceAtLeast(0).dp))
                    } ?: Modifier
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text,
                color = resolved.ink,
                fontSize = resolved.fontSizeSp.sp,
                fontFamily = resolved.fontFamily,
                fontWeight = resolved.fontWeight,
            )
        }
    }
}

/**
 * 一方气泡的编辑区（v0.80.0）：背景 / 字色 / 边框三行色盘入口 + 圆角滑条 + 恢复默认。
 * 「默认」= 写回哨兵（背景跟主题、字色自动对比、无边框、圆角 18）——
 * 明暗主题下的可读性由这套保底负责，用户自己选的色则两版通用。
 */
@Composable
private fun BubbleStyleEditor(
    title: String,
    style: com.menghuan.app.data.ChatAppearance.BubbleStyle,
    isMine: Boolean,
    appearance: com.menghuan.app.data.ChatAppearance,
    onChange: (com.menghuan.app.data.ChatAppearance.BubbleStyle) -> Unit,
) {
    val resolved = com.menghuan.app.ui.theme.resolveBubble(style, isMine, appearance)
    // 正在编辑哪个颜色："bg" / "ink" / "border"；null = 没开色盘
    var picking by remember { mutableStateOf<String?>(null) }

    Column {
        Text(title, fontSize = 13.sp, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        ColorFieldRow(
            label = "背景色",
            raw = style.bg,
            previewColor = resolved.bg,
            onReset = { onChange(style.copy(bg = 0L)) },
            onOpen = { picking = "bg" },
        )
        ColorFieldRow(
            label = "文字颜色",
            raw = style.ink,
            previewColor = resolved.ink,
            onReset = { onChange(style.copy(ink = 0L)) },
            onOpen = { picking = "ink" },
        )
        ColorFieldRow(
            label = "边框颜色",
            raw = style.border,
            previewColor = if (style.border == 0L) androidx.compose.ui.graphics.Color.Transparent
            else androidx.compose.ui.graphics.Color(style.border),
            onReset = { onChange(style.copy(border = 0L)) },
            onOpen = { picking = "border" },
        )
        // 圆角：0（直角）~ 28dp；默认（哨兵 -1）显示为 18。
        // radiusValue 提到 Column 层：Slider 在 Row 外面，Row lambda 里的局部变量它够不着
        val radiusValue = if (style.radiusDp == com.menghuan.app.data.ChatAppearance.BubbleStyle.NO_RADIUS) {
            com.menghuan.app.data.ChatAppearance.BubbleStyle.DEFAULT_RADIUS_DP
        } else style.radiusDp
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("圆角", fontSize = 14.sp, color = TextPrimary)
            Spacer(Modifier.width(12.dp))
            Text("${radiusValue}dp", fontSize = 12.sp, color = MutedText, modifier = Modifier.width(42.dp))
            if (style.radiusDp != com.menghuan.app.data.ChatAppearance.BubbleStyle.NO_RADIUS) {
                NeuTextButton("默认", { onChange(style.copy(radiusDp = com.menghuan.app.data.ChatAppearance.BubbleStyle.NO_RADIUS)) })
            }
        }
        androidx.compose.material3.Slider(
            value = radiusValue.toFloat(),
            onValueChange = { onChange(style.copy(radiusDp = it.toInt())) },
            valueRange = com.menghuan.app.data.ChatAppearance.MIN_RADIUS_DP.toFloat()
                ..com.menghuan.app.data.ChatAppearance.MAX_RADIUS_DP.toFloat(),
            steps = com.menghuan.app.data.ChatAppearance.MAX_RADIUS_DP
                - com.menghuan.app.data.ChatAppearance.MIN_RADIUS_DP - 1,
        )
    }

    if (picking != null) {
        val initialColor = when (picking) {
            "bg" -> resolved.bg
            "ink" -> resolved.ink
            else -> if (style.border == 0L) androidx.compose.ui.graphics.Color.White
            else androidx.compose.ui.graphics.Color(style.border)
        }
        com.menghuan.app.ui.common.NeuColorPickerSheet(
            title = when (picking) {
                "bg" -> "背景色 · $title"
                "ink" -> "文字颜色 · $title"
                else -> "边框颜色 · $title"
            },
            initial = initialColor,
            onPick = { c ->
                // Int 可能是负数（alpha ≥ 0x80），先掩码成正的 ARGB long 再落库
                val argb = c.toArgb().toLong() and 0xFFFFFFFFL
                onChange(
                    when (picking) {
                        "bg" -> style.copy(bg = argb)
                        "ink" -> style.copy(ink = argb)
                        else -> style.copy(border = argb)
                    },
                )
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** 一行颜色字段：当前色小样 + 名称 + （已自定义时才有的）「默认」+「选色」 */
@Composable
private fun ColorFieldRow(
    label: String,
    raw: Long,
    previewColor: androidx.compose.ui.graphics.Color,
    onReset: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(previewColor)
                .border(1.dp, com.menghuan.app.ui.theme.NeuHairline, RoundedCornerShape(7.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        if (raw != 0L) {
            NeuTextButton("默认", onReset)
            Spacer(Modifier.width(4.dp))
        }
        NeuTextButton("选色", onOpen)
    }
}
