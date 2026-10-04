package com.menghuan.app.ui.data

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.data.model.AiWeeklyReport
import com.menghuan.app.data.model.DailySuggestion
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuCard
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.rememberNeuPressState
import com.menghuan.app.ui.ledger.LedgerRow
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.common.EaseOutExpo
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 洞察卡日期：今天/昨天/具体日期 */
internal fun AiInsightDateLabel(createdAt: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
}

/** 周报期标签：「9月28日 – 10月4日」 */
internal fun weekLabelOf(report: AiWeeklyReport): String {
    val start = LocalDate.ofEpochDay(report.weekStart)
    val end = LocalDate.ofEpochDay(report.weekEnd)
    return "${start.monthValue}月${start.dayOfMonth}日 – ${end.monthValue}月${end.dayOfMonth}日"
}

/** 生成日期标签 */
internal fun dateLabelOf(epochMillis: Long): String {
    val d = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${d.monthValue}月${d.dayOfMonth}日"
}

/**
 * 我的数据页（v0.42.0 大改版）。
 *
 * 页面顺序（用户指定）：
 * 1. **本周回顾条**（置顶）：打卡与完成度合并成单一指标，点格子展开当日明细
 * 2. **今日建议**：独立生成链路产出的一条建议，长按转任务
 * 3. **最近流水**：记账本摘要 + 现金余额，点击进记账本全屏页（入口在页面底部区域）
 * 4. **AI 洞察 / 周报**：合并成一张入口卡 → 全屏详情页
 *
 * 页面底部 = 记账本入口行（用户要求"入口放在数据页底部"）。
 * 布局用 LazyColumn 之外的普通 Column + verticalScroll：本周回顾的展开明细是局部状态，
 * 用它驱动列表高度变化在 LazyColumn 里容易丢滚动锚点。
 */
@Composable
fun DataScreen(
    vm: DataViewModel = viewModel(factory = DataViewModel.Factory),
    onOpenLedger: () -> Unit = {},
    onOpenAiDetail: () -> Unit = {},
    /**
     * 数据页当前是否**真正显示在屏幕上**（v0.42.3）。
     *
     * 由 Pager 的 `settledPage == 1` 驱动。四个 tab 同在 Pager 里被预组合，
     * 屏外页的入场动画会在后台偷偷播完——所以动画不能靠自身"被组合"来触发，
     * 必须由外部喂"我现在可见了"这个信号。默认 true 是为了单页预览/测试不需要传。
     */
    visible: Boolean = true,
) {
    val weekReview by vm.weekReview.collectAsState()
    val todaySuggestion by vm.todaySuggestion.collectAsState()
    val generatingSuggestion by vm.generatingSuggestion.collectAsState()
    val suggestionMessage by vm.suggestionMessage.collectAsState()
    val insights by vm.insights.collectAsState()
    val weeklyReports by vm.weeklyReports.collectAsState()
    val apiReady by vm.apiReady.collectAsState()
    val extracting by vm.extracting.collectAsState()
    val extractMessage by vm.extractMessage.collectAsState()
    val ledgerEntries by vm.ledgerEntries.collectAsState()
    val cashCents by vm.cashCents.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    // 今日建议弹窗开关（v0.42.3）：提升到这里，让弹窗渲染在滚动内容之外
    var suggestionSheetOpen by remember { mutableStateOf(false) }

    // 反馈条自动清除（4 秒）
    LaunchedEffect(extractMessage) {
        if (extractMessage != null) {
            kotlinx.coroutines.delay(4000)
            vm.clearExtractMessage()
        }
    }
    LaunchedEffect(suggestionMessage) {
        if (suggestionMessage != null) {
            kotlinx.coroutines.delay(4000)
            vm.clearSuggestionMessage()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NeuTopBar(title = "我的数据")

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            // ① 本周回顾条（置顶）
            WeekReviewCard(weekReview, visible)

            // ② 今日建议
            DailySuggestionSection(
                suggestion = todaySuggestion,
                generating = generatingSuggestion,
                message = suggestionMessage,
                onOpenSheet = { suggestionSheetOpen = true },
                onConvert = { vm.convertSuggestionToTask(it) },
            )

            // ③ 记账本入口（单卡：现金余额 + 整卡可点，v0.42.1）
            LedgerEntryCard(
                cashCents = cashCents,
                onClick = onOpenLedger,
            )

            // ④ AI 洞察 / 周报合并入口
            AiEntryCard(
                insightCount = insights.size,
                reportCount = weeklyReports.size,
                apiReady = apiReady,
                extracting = extracting,
                message = extractMessage,
                onOpen = onOpenAiDetail,
            )
        }
    }

    // 今日建议弹窗（v0.42.3）：**屏幕正中央的对话框**（用户明确要求"从中间弹出"），
    // 且必须渲染在可滚动内容**之外**——塞进 verticalScroll 的 Column 里会让
    // 对话框定位异常（这正是上一版"看起来不是从中间弹"的原因）。
    if (suggestionSheetOpen) {
        DailySuggestionDialog(
            suggestion = todaySuggestion,
            generating = generatingSuggestion,
            apiReady = apiReady,
            onDismiss = { suggestionSheetOpen = false },
            onGenerate = {
                suggestionSheetOpen = false
                vm.generateSuggestion()
            },
        )
    }
}

/**
 * 本周回顾条：单一指标卡（合并原「打卡热力」与「完成度环」两套口径）。
 *
 * - 中部：近 7 天格子条，**点任意格子展开当日明细**（当天该做什么、做完哪些）
 * - 底部：进度条（本周合计）
 * （v0.42.3 起顶部 `done / total` 大数字已删）
 *
 * **入场动画的重播（v0.42.1 起两次修，结论在 v0.42.3）**：
 * 症状 = 动画只在"从 push 页返回"时看得到，切 tab 进数据页看不到。
 * 根因 = 四个顶级 tab 在 `HorizontalPager(beyondViewportPageCount = 3)` 里**同时组合**，
 * 数据页在后台就组合完并把动画一路播完了；等真正切过来时早已播完。
 *
 * v0.42.1 曾用 `Modifier.onPlaced` 试图区分"组合了"和"显示了"——**这招不行**：
 * `onPlaced` 只在节点**首次进入布局**时回调一次，屏外页同样会被布局（Pager 会预布局
 * 相邻页），所以它在后台照样触发，等于没修。
 *
 * v0.42.3 正解 = **由外部喂"我现在可见"信号**（`visible` 参数来自 `pagerState.settledPage`），
 * 它一变就把动画重新播一遍，切 tab 进来 / 从 push 页返回都能重播。
 * `armed` 只在 `visible` 为真时置位，动画靠 `visible` 本身作为 key 重跑。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WeekReviewCard(review: WeekReview, visible: Boolean) {
    // 展开的格子（近 7 天索引）；同一时刻只展开一个
    var expandedIndex by remember { mutableStateOf<Int?>(null) }
    val haptic = LocalHapticFeedback.current

    // 动画放行：只在页面真正可见时开始。false→true 的跳变会让下面的
    // animateFloatAsState 从 0 重新跑到目标值（每次切回本页都重播）。
    val sweep by animateFloatAsState(
        if (visible) review.fraction else 0f,
        tween(1000, easing = EaseOutExpo),
        label = "weekSweep",
    )

    NeuCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 18.dp,
        elevation = 3.dp,
    ) {
        Text(
            "本周回顾",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary,
            letterSpacing = 1.1.sp,
        )
        Spacer(Modifier.height(14.dp))

        // —— 近 7 天格子条（点击展开当日明细）——
        // v0.42.3：顶部的 `done / total` 大数字已删（用户认为无用）——
        // 7 天格子条本身就逐日呈现了完成情况，进度条则给出本周合计，无需再挂一个总数。
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            review.days.forEachIndexed { index, day ->
                val appear by animateFloatAsState(
                    if (visible) 1f else 0f,
                    tween(320, delayMillis = index * 40, easing = EaseOutExpo),
                    label = "dayChip$index",
                )
                DayChip(
                    day = day,
                    today = index == review.days.lastIndex,
                    expanded = expandedIndex == index,
                    appear = appear,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        expandedIndex = if (expandedIndex == index) null else index
                    },
                )
            }
        }

        // —— 展开的当日明细 ——
        val shown = expandedIndex?.let { review.days.getOrNull(it) }
        if (shown != null) {
            Spacer(Modifier.height(14.dp))
            DayDetailPanel(shown)
        }

        Spacer(Modifier.height(14.dp))

        // —— 本周进度条 ——（v0.42.1：「点格子看当天明细」这类说明小字已删）
        WeekProgressBar(fraction = sweep, enabled = visible && review.total > 0)
    }
}

/** 单日格子：有完成 = 强调色实心（按完成度深浅），全清加一个细环；今天多一个下划线点 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayChip(
    day: DayReview,
    today: Boolean,
    expanded: Boolean,
    appear: Float,
    onClick: () -> Unit,
) {
    val press = rememberNeuPressState(raised = 2.dp)
    val weekLabels = listOf("一", "二", "三", "四", "五", "六", "日")
    val label = weekLabels.getOrNull(day.date.dayOfWeek.value - 1) ?: ""
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .graphicsLayer {
                    alpha = appear
                    val s = 0.7f + 0.3f * appear
                    scaleX = s
                    scaleY = s
                }
                .size(36.dp)
                .scale(press.scale)
                .then(
                    when {
                        // 格子填充度：全清 = 纯黑实心；部分完成 = 黑 35% 面；无完成 = 凹陷灰
                        day.isFull -> Modifier
                            .neuSurface(12.dp, color = NeuAccent, elevation = 3.dp)
                            .combinedClickable(
                                interactionSource = press.interactionSource,
                                indication = null,
                                onClick = onClick,
                            )
                        day.done > 0 -> Modifier
                            .neuSurface(12.dp, color = NeuTrack)
                            .background(NeuAccent.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
                            .combinedClickable(
                                interactionSource = press.interactionSource,
                                indication = null,
                                onClick = onClick,
                            )
                        else -> Modifier
                            .neuSurface(12.dp, inset = true)
                            .combinedClickable(
                                interactionSource = press.interactionSource,
                                indication = null,
                                onClick = onClick,
                            )
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (day.isFull) {
                Icon(MIcons.Checklist, contentDescription = null, tint = AccentInk, modifier = Modifier.size(17.dp))
            } else if (day.total > 0) {
                Text(
                    "${day.done}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                )
            } else {
                Box(Modifier.size(6.dp).background(FaintText, CircleShape))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontSize = 10.5.sp,
            fontWeight = if (today) FontWeight.SemiBold else FontWeight.Normal,
            color = if (today || expanded) TextPrimary else MutedText,
        )
    }
}

/** 当日明细面板：日期 + 完成情况 + 任务清单（做没做一目了然） */
@Composable
private fun DayDetailPanel(day: DayReview) {
    var shown by remember(day.date) { mutableStateOf(false) }
    LaunchedEffect(day.date) { shown = true }
    val appear by animateFloatAsState(
        if (shown) 1f else 0f,
        tween(280, easing = EaseOutExpo),
        label = "dayDetail",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = appear; translationY = (1f - appear) * 8.dp.toPx() }
            .neuSurface(14.dp, color = NeuTrack, inset = true)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${day.date.monthValue} 月 ${day.date.dayOfMonth} 日",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (day.total == 0) "无安排" else "${day.done}/${day.total}",
                fontSize = 12.sp,
                color = TextSecondary,
            )
        }
        if (day.items.isEmpty()) {
            // v0.42.1：不再用「这天没有任务」这类说明小字占位，空就什么都不显示
            return@Column
        }
        Spacer(Modifier.height(10.dp))
        day.items.forEach { item ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(15.dp)
                        .then(
                            if (item.done) {
                                Modifier.background(NeuAccent, CircleShape)
                            } else {
                                Modifier.neuSurface(999.dp, shape = CircleShape, inset = true)
                            },
                        ),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    item.title,
                    fontSize = 12.5.sp,
                    color = if (item.done) TextSecondary else TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 本周进度条：黑填充 + 灰轨，EaseOutExpo 扫出 */
@Composable
private fun WeekProgressBar(fraction: Float, enabled: Boolean) {
    val fill = NeuAccent
    val track = NeuTrack
    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .height(6.dp),
    ) {
        val r = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = track, size = size, cornerRadius = r)
        if (enabled) {
            drawRoundRect(
                color = fill,
                topLeft = Offset(0f, 0f),
                size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
                cornerRadius = r,
            )
        }
    }
}

// ============================================================
// ② 今日建议
// ============================================================

/**
 * 今日建议：独立生成链路（每天早上 07:30 + 打开页面补生成）。
 *
 * v0.42.1：区块头的「重新生成」按钮已删——生成入口收敛到弹窗（点卡片弹出），
 * 首页只做展示，避免误触把当天建议洗掉。
 * v0.42.3：弹窗改成**屏幕正中央的对话框**，开关状态提升到 `DataScreen`
 * （弹窗必须渲染在滚动内容之外，否则定位异常）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DailySuggestionSection(
    suggestion: DailySuggestion?,
    generating: Boolean,
    message: String?,
    onOpenSheet: () -> Unit,
    onConvert: (DailySuggestion) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val appear by animateFloatAsState(if (revealed) 1f else 0f, tween(360, easing = EaseOutExpo), label = "suggestAppear")

    SectionHeader(title = "今日建议")
    if (message != null) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            fontSize = 11.sp,
            color = NeuAccent,
        )
    }

    if (suggestion == null) {
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = appear }
                .neuSurface(20.dp, color = NeuTrack, inset = true)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpenSheet,
                )
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).neuSurface(999.dp, color = NeuSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MIcons.Bulb, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    if (generating) "正在想今天该给你什么建议…" else "今天还没有建议",
                    fontSize = 13.5.sp,
                    color = TextSecondary,
                )
            }
        }
    } else {
        Box(Modifier.fillMaxWidth()) {
            NeuCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = appear; translationY = (1f - appear) * 10.dp.toPx() },
                contentPadding = 18.dp,
                elevation = 3.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(28.dp).neuSurface(999.dp, color = NeuAccent, elevation = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(MIcons.Bulb, contentDescription = null, tint = AccentInk, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (suggestion.converted) {
                        Text("已加入任务", fontSize = 11.sp, color = MutedText)
                    }
                }
                Spacer(Modifier.height(13.dp))
                Text(
                    suggestion.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.2).sp,
                    lineHeight = 23.sp,
                    color = TextPrimary,
                )
                if (suggestion.detail.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        suggestion.detail,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = TextSecondary,
                    )
                }
            }
            // 长按热区（铺满卡片）
            Box(
                Modifier
                    .matchParentSize()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenSheet,
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        },
                    ),
            )
            // 菜单锚点：卡片右下角
            Box(Modifier.align(Alignment.BottomEnd).size(1.dp)) {
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = NeuSurface,
                    shape = RoundedCornerShape(14.dp),
                    shadowElevation = 0.dp, // v0.42.1 全应用去阴影
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (suggestion.converted) "再转一次任务" else "转为任务",
                                fontSize = 15.sp,
                                color = TextPrimary,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onConvert(suggestion)
                        },
                    )
                }
            }
        }
    }
}

// ============================================================
// ② 记账本入口（单卡：只显示现金余额，整卡可点）
// ============================================================
/**
 * 今日建议弹窗（v0.42.3：**屏幕正中央的对话框**）。
 *
 * 用户明确要求"从中间弹出"——原来是底部面板（NeuSheetScaffold / ModalBottomSheet），
 * 这里改用 `Dialog` 居中呈现：白面圆角卡片 + 暗色遮罩，点遮罩或空白处关闭。
 * 完整建议 + 生成动作都在这里；未配置接口时生成按钮置灰。
 */
@Composable
private fun DailySuggestionDialog(
    suggestion: DailySuggestion?,
    generating: Boolean,
    apiReady: Boolean,
    onDismiss: () -> Unit,
    onGenerate: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .neuSurface(24.dp, color = NeuSurface)
                .padding(horizontal = 22.dp, vertical = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(30.dp).neuSurface(999.dp, color = NeuAccent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MIcons.Bulb, contentDescription = null, tint = AccentInk, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(11.dp))
                Text(
                    "今日建议",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.2).sp,
                    color = TextPrimary,
                )
            }
            Spacer(Modifier.height(16.dp))

            when {
                generating -> {
                    Text(
                        "正在想今天该给你什么建议…",
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(20.dp))
                    NeuButton(
                        text = "生成中…",
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                        kind = NeuButtonKind.Primary,
                    )
                }
                suggestion != null -> {
                    Text(
                        suggestion.title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.24).sp,
                        lineHeight = 25.sp,
                        color = TextPrimary,
                    )
                    if (suggestion.detail.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            suggestion.detail,
                            fontSize = 13.5.sp,
                            lineHeight = 21.sp,
                            color = TextSecondary,
                        )
                    }
                    Spacer(Modifier.height(22.dp))
                    if (apiReady) {
                        NeuButton(
                            text = "重新生成",
                            onClick = onGenerate,
                            modifier = Modifier.fillMaxWidth(),
                            kind = NeuButtonKind.Primary,
                        )
                    }
                }
                else -> {
                    Text(
                        "今天还没有建议",
                        fontSize = 14.sp,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(20.dp))
                    NeuButton(
                        text = "生成今日建议",
                        onClick = onGenerate,
                        enabled = apiReady,
                        modifier = Modifier.fillMaxWidth(),
                        kind = NeuButtonKind.Primary,
                    )
                }
            }
        }
    }
}

// ============================================================
// ③ 记账本入口（单卡：只显示现金余额，整卡可点）
// ============================================================

/**
 * 记账本入口卡（v0.42.1 用户要求"不用两个卡片，直接显示现金余额，点进去就是记账本"）。
 *
 * 原来是「现金余额卡（含最近 3 笔流水）+ 独立入口行」两块，现在合并成**一张卡**：
 * 左边钱包图标 + 卡名，右边现金余额大数字 + 右箭头。整张卡可点，直接进记账本全屏页。
 * 流水明细只在记账本页看——数据页不重复展示，免得两处各删一次还容易不一致。
 */
@Composable
private fun LedgerEntryCard(cashCents: Long, onClick: () -> Unit) {
    val press = rememberNeuPressState(raised = 4.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("ledger_entry_row")
            .scale(press.scale)
            .neuSurface(16.dp, color = NeuSurface)
            .clickable(interactionSource = press.interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).neuSurface(999.dp, color = NeuTrack, inset = true),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MIcons.Wallet, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(13.dp))
        Text("记账本", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
        Spacer(Modifier.weight(1f))
        Text(
            "¥${com.menghuan.app.data.model.LedgerMath.formatCents(cashCents)}",
            fontSize = 19.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.4).sp,
            color = TextPrimary,
        )
        Spacer(Modifier.width(6.dp))
        Icon(MIcons.ChevronRight, contentDescription = null, tint = MutedText, modifier = Modifier.size(18.dp))
    }
}

// ============================================================
// ④ AI 洞察 / 周报合并入口
// ============================================================

/**
 * AI 洞察 + 周报 合并成一张入口卡（用户要求"合并成一个入口，点击进去才是详细信息"）。
 * 卡上只给数量与最近一条摘要，详细内容进全屏页。
 */
@Composable
private fun AiEntryCard(
    insightCount: Int,
    reportCount: Int,
    apiReady: Boolean,
    extracting: Boolean,
    message: String?,
    onOpen: () -> Unit,
) {
    SectionHeader(title = "AI 洞察 / 周报")
    if (message != null) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            fontSize = 11.sp,
            color = NeuAccent,
        )
    }
    val press = rememberNeuPressState(raised = 4.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("ai_entry_card") // 卡片内标题文本与区块头一致，点击必须靠 tag 消歧
            .scale(press.scale)
            .neuSurface(16.dp, color = NeuSurface, elevation = 2.dp)
            .clickable(interactionSource = press.interactionSource, indication = null, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).neuSurface(999.dp, color = NeuTrack, inset = true),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MIcons.Brain, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text("AI 洞察与周报", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            // v0.42.1：空态不再挂「还没有产出，聊聊就有」这句说明（纯废话），没数据就不显示副标题
            val summary = when {
                insightCount == 0 && reportCount == 0 -> null
                reportCount == 0 -> "$insightCount 条洞察"
                insightCount == 0 -> "$reportCount 期周报"
                else -> "$insightCount 条洞察 · $reportCount 期周报"
            }
            if (summary != null) {
                Text(summary, fontSize = 12.sp, color = MutedText)
            }
        }
        Icon(MIcons.ChevronRight, contentDescription = null, tint = MutedText, modifier = Modifier.size(18.dp))
    }
}

// ============================================================
// 共用小组件
// ============================================================

/** 区块头（全页统一规格）：13sp SemiBold。v0.42.1 起不再挂右侧动作文字（生成/提炼入口已收敛） */
@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.padding(horizontal = 4.dp)) {
        Text(
            title,
            modifier = Modifier.alignByBaseline(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
    }
}
