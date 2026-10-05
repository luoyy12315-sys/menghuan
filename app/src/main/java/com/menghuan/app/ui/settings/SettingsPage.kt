package com.menghuan.app.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.MenghuanApp
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.neuRowPress
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary

/**
 * 设置链全屏页的统一骨架（v0.51.0）。
 *
 * **为什么从弹层改成整页**：设置链现在最深两层（设置 → 各功能页，如 设置 → 语音模型），
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
    actions: (@Composable () -> Unit)? = null,
    actionsWidth: Dp? = null,
    scrollState: ScrollState = rememberScrollState(),
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
            actions = actions,
            actionsWidth = actionsWidth,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
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
 * 顶部搜索**行**（点一下才进入搜索态）：与「消息」页会话列表顶部那条同款——
 * 凹陷灰槽 + 放大镜 + 「搜索」，不是可编辑输入框（v0.54.4）。
 *
 * 为什么不用 [SettingsSearchField] 常驻：设置主页是"上滑看卡片"的页面，
 * 一个真实输入框常驻会抢焦点、弹键盘，把卡片顶下去。改成"点一下才搜"，
 * 与消息页行为一致，用户不用学两套。
 */
@Composable
fun SettingsSearchRow(placeholder: String = "搜索", onClick: () -> Unit) {
    val track = NeuTrack
    Row(
        Modifier
            .fillMaxWidth()
            .neuSurface(16.dp, color = track, inset = true)
            .neuRowPress(onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MIcons.Search,
            contentDescription = null,
            tint = MutedText,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(placeholder, fontSize = 14.sp, color = MutedText)
    }
}

/**
 * 灰线分隔的一行（v0.54.6）。
 *
 * 用户点名"取消卡片，直接用灰线分隔开"：会话设置页整页都不再用 `NeuSheetRow` 那种
 * 白面圆角卡片，改成**一条条平铺的行**，行与行之间只用一条 1dp 发丝线（[NeuHairline]）分隔
 * ——这也是全应用"唯一分隔手段是细线"的既定语言。
 *
 * 与 [SettingsSwitchRow] 的区别：那个是"标题 + 右侧开关"的开关行；这个是通用行
 * （可带副标题、右侧控件、右侧箭头、可点/不可点）。
 */
@Composable
fun SettingsLineRow(
    title: String,
    subtitle: String = "",
    trailing: (@Composable () -> Unit)? = null,
    /** 右侧不挂控件时是否画那颗浅箭头（默认画：表明"点进去还有东西"） */
    showChevron: Boolean = true,
    /**
     * 点击回调。放在**最后一个参数**上是为了能用尾随 lambda 写
     * （`SettingsLineRow("音色") { … }`）；默认空实现 = 这一行点不动。
     */
    onClick: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, color = TextPrimary)
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = 12.sp, color = MutedText, maxLines = 1)
                }
            }
            when {
                trailing != null -> {
                    Spacer(Modifier.width(10.dp))
                    trailing()
                }
                showChevron -> {
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        MIcons.ChevronRight,
                        contentDescription = null,
                        tint = FaintText,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(NeuHairline))
    }
}

/**
 * 手风琴段行：标题 + 「已自定义」徽标 + 可选尾部控件 + 展开箭头。
 *
 * 现在只用在**能一次看完、不需要边看边改**的名单类分段（「AI 提示词」八段、「语音」页的手填音色 ID）。
 * API 配置 / 语音合成不用它——那些是"卡片列表 + 点卡片看详情"（详情走 `NeuSheetScaffold` 底部弹层）。
 */
@Composable
fun SettingsSectionRow(
    title: String,
    expanded: Boolean,
    customized: Boolean = false,
    caption: String? = null,
    /** v0.54.4：段标题字号（「AI 提示词」要求加大到 17sp，其它名单页沿用默认 13sp） */
    titleSize: TextUnit = 13.sp,
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
                fontSize = titleSize,
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
