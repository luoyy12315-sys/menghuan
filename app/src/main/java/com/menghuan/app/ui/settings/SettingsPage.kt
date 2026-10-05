package com.menghuan.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.MenghuanApp
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary

/**
 * 设置链全屏页的统一骨架（v0.51.0）。
 *
 * **为什么从弹层改成整页**：设置链现在有三层（设置 → 语音 → 语音合成），
 * 弹层叠弹层在手机上会把整屏盖死、返回手势也说不清退到哪一层；
 * 而且识别模型下载要跑几分钟，用户需要能退出去干别的再回来——
 * 那正是"页面有返回栈"才成立的事。
 *
 * insets 仍由 [AdaptiveScreen] 统一处理，页面内部不要再写 statusBarsPadding。
 */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AdaptiveScreen {
        NeuTopBar(
            title = title,
            navigationIcon = {
                NeuCircleButton(onClick = onBack, size = 38.dp) {
                    Icon(
                        MIcons.Back,
                        contentDescription = "返回",
                        tint = TextPrimary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            },
            actionsWidth = 56.dp,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
            content = content,
        )
    }
}

/** 设置页共用的容器句柄（各页自己取仓库，路由不传参） */
@Composable
fun settingsContainer(): MenghuanApp =
    LocalContext.current.applicationContext as MenghuanApp

// ══════════════════════════════════════════════════════════════
// v0.54.0：设置主页分组重构后共用的一批小组件
//
// 放在 SettingsPage.kt 而不是 SettingsScreen.kt 里的理由：这几件
// 只服务"设置主页这一页"，但和SettingsPage 同属设置链骨架，
// 放一起改起来不用来回跳文件。
// ══════════════════════════════════════════════════════════════

/**
 * 分组标签：`① AI 与模型` + 一句"这组管什么"。
 *
 * **不可点、不折叠**（v0.54.0 用户明确要"全屏显示"）—— 它只做视觉分区，
 * 让人一眼分清"这个入口属于哪一类"，不承担任何交互。
 */
@Composable
fun SettingsGroupLabel(title: String, caption: String? = null) {
    Column(Modifier.padding(top = 20.dp, bottom = 9.dp)) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            letterSpacing = 0.2.sp,
        )
        if (!caption.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(caption, fontSize = 11.sp, lineHeight = 15.sp, color = MutedText)
        }
    }
}

/** 组内二级标签（如「合成与识别」）：比 [SettingsGroupLabel] 更轻，只隔开同组的两类内容 */
@Composable
fun SettingsSubLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = TextSecondary,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 7.dp),
    )
}

/** 组内「标题 + 同行内容」的标签（当前只有「外观」用到，所以留一个具名入口） */
@Composable
fun SettingsInlineLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = TextSecondary,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(bottom = 7.dp),
    )
}

/**
 * 一行入口卡。`show = false` 时**不占任何高度**（搜索过滤用）。
 *
 * 用 `if (!show) return` 而不是 alpha=0：留一个透明节点在语义树里，
 * 无障碍与 UI 测试都会把它当成真实存在的行。
 */
@Composable
fun SettingsRow(
    show: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    if (!show) return
    NeuSheetRow(icon = icon, title = title, subtitle = subtitle, onClick = onClick)
    Spacer(Modifier.height(8.dp))
}

/** 一行开关（就地显示，不用点进二级页才知道状态）。`show` 语义同 [SettingsRow] */
@Composable
fun SettingsSwitchRow(
    show: Boolean,
    title: String,
    caption: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    if (!show) return
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onToggle(!checked) }
            .padding(vertical = 7.dp),
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

/**
 * 设置主页顶部的搜索框。
 *
 * 为什么不上 `NeuTextField`：那个是**表单控件**（有 label、有 7px 间距、focus 时描边变黑），
 * 用在搜索上会显得像"下面要填一张表"。这里要的是一条安静的下沉式输入槽。
 *
 * `neuSurface(inset = true)` 才是"凹陷灰槽"（平涂 `NeuTrack`、无描边）；
 * 走默认分支会把 `color` 按 `intensity` 乘 alpha —— 传 0f 会**整条变全透明**，
 * 搜索框就只剩一个光标（写这个时踩过）。
 */
@Composable
fun SettingsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "搜索设置项",
) {
    val track = NeuTrack
    val cursorColor = NeuAccent
    Box(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .neuSurface(12.dp, color = track, inset = true)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary),
            cursorBrush = SolidColor(cursorColor),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = 14.sp, color = MutedText)
                }
                inner()
            },
        )
    }
}

/**
 * 居中详情弹窗（v0.53.0）：接口卡片点右箭头 → 中间弹出这条的详细信息，
 * 改、保存、删除都在里面。
 *
 * **为什么是居中弹窗而不是底部弹层**：用户明确要"从中间弹窗展开卡片详细信息"；
 * 底部弹层会把整页配置盖住，改一条要来回滚动，而弹窗只占屏幕中部一小块、
 * 背后那张卡片还在原位。
 */
@Composable
fun SettingsCardDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 6.dp)
                .neuSurface(22.dp, color = NeuSurface)
                .padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.2).sp,
                color = TextPrimary,
            )
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}
/**
 * 手风琴段行：标题 + 「已自定义」徽标 + 可选尾部控件 + 展开箭头。
 *
 * 现在只用在**能一次看完、不需要边看边改**的名单类分段（「AI 提示词」八段、「语音」页的手填音色 ID）。
 * API 配置 / 语音合成不用它——那些是"卡片列表 + 点卡片看详情"，见 [SettingsCardDialog]。
 */
@Composable
fun SettingsSectionRow(
    title: String,
    expanded: Boolean,
    customized: Boolean = false,
    caption: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            if (!caption.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(caption, fontSize = 11.sp, lineHeight = 15.sp, color = MutedText)
            }
        }
        if (customized) {
            Spacer(Modifier.width(8.dp))
            Text("已自定义", fontSize = 10.sp, color = TextSecondary, letterSpacing = 0.4.sp)
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = if (expanded) MIcons.ChevronUp else MIcons.ChevronDown,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MutedText,
            modifier = Modifier.size(20.dp),
        )
    }
}
