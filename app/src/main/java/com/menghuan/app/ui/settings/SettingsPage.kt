package com.menghuan.app.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.MenghuanApp
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.EaseOutQuart
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.neuRowPress
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.staggerEnter
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
fun SettingsGroupLabel(
    title: String,
    caption: String? = null,
    /** 错峰入场序号（v0.55.0）：与同页的 [SettingsLineRow] 连成一个序列 */
    index: Int = 0,
) {
    Column(Modifier.staggerEnter(index).padding(top = 20.dp, bottom = 9.dp)) {
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
    /**
     * 聚焦器（v0.56.8）：从放大镜进入搜索态时**立刻聚焦输入框**，
     * 让用户直接打字，不用再点一次输入框。
     */
    focusRequester: FocusRequester? = null,
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
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
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
    /**
     * 右侧的**当前选择**（v0.65.0，小标题尺寸贴在箭头左边）。
     *
     * 用在"这行选了什么就直接在行上写出来"的入口（音色 / 铃声）：
     * 用户要的是一眼看到当前值，而不是多点一次进弹层才知道现在用哪个。
     * 与 [subtitle] 互斥使用（那个是标题下面的一行，会把行撑高；这个不撑高）。
     */
    trailingText: String = "",
    trailing: (@Composable () -> Unit)? = null,
    /** 右侧不挂控件时是否画那颗浅箭头（默认画：表明"点进去还有东西"） */
    showChevron: Boolean = true,
    /** 标题字号（v0.56.0：会话设置页点名加大到 17sp，其它页沿用默认 15sp） */
    titleSize: TextUnit = 15.sp,
    /**
     * 错峰入场序号（v0.55.0）：同一页里按出现顺序传，整页内容依次抬起。
     * 与 [SettingsGroupLabel] 共用一套序列（分组标题也占一个序号）。
     */
    index: Int = 0,
    /**
     * 行下方是否画那条发丝线。**一组里的最后一行传 false** ——
     * 组与组之间靠 [SettingsGroupLabel] 的 20dp 上间距分区，再补一条线就成了"悬空的线"。
     */
    divider: Boolean = true,
    /**
     * 点击回调。放在**最后一个参数**上是为了能用尾随 lambda 写
     * （`SettingsLineRow("音色") { … }`）；默认空实现 = 这一行点不动。
     */
    onClick: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val track = NeuTrack
    val hairline = NeuHairline
    // ⚠️ v0.56.8（用户点名"不要那个点击之后的灰色条"，且说过不止一次）：
    // 这里原来在按下时**给整行铺一层灰**（当时的设计口径是"全应用无涟漪，灰行是唯一表达"）。
    // 现在**把灰条整个去掉**：按压缩放/高亮一律不做，只留下面那颗箭头在按压时轻微右移
    // （一行"能点进去"的动线，不铺色、不闪）。
    val highlight by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(if (pressed) 90 else 220, easing = EaseOutQuart),
        label = "lineRowPress",
    )
    Column(Modifier.fillMaxWidth().staggerEnter(index)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = titleSize, color = TextPrimary)
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = 12.sp, color = MutedText, maxLines = 1)
                }
            }
            // 右侧先给"当前选择"（宽度封顶 + 省略号：Row 里不带封顶的单行文字会把标题挤没），
            // 再给那颗箭头；挂了自定义控件（开关那类）就不显示文本，由控件自己说话。
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            } else {
                if (trailingText.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        trailingText,
                        fontSize = 12.sp,
                        color = MutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                }
                if (showChevron) {
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        MIcons.ChevronRight,
                        contentDescription = null,
                        tint = FaintText,
                        // 按压时箭头往右挪 2dp：一条"这行能点进去"的动线，比变色更含蓄
                        modifier = Modifier
                            .size(14.dp)
                            // 写成 `Dp * Float` 而不是 `Float.dp`：后者在 Float/Double/Int
                            // 三个 `.dp` 扩展之间会有重载歧义
                            .offset(x = 2.dp * highlight),
                    )
                }
            }
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(hairline))
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
