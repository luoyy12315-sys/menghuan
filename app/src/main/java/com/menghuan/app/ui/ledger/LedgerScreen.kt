package com.menghuan.app.ui.ledger

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.model.LedgerEntry
import com.menghuan.app.data.model.LedgerMath
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import com.menghuan.app.ui.data.DataViewModel
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.NeuCard
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuMenu
import com.menghuan.app.ui.common.NeuMenuItem
import com.menghuan.app.ui.common.NeuSectionTitle
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.rememberNeuPressState
import com.menghuan.app.ui.common.RollingAmountText
import com.menghuan.app.ui.common.EaseOutExpo
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import java.time.YearMonth

/**
 * 记账本全屏页（v0.42.0；v0.42.1 加周度统计 + 去说明性小字）。
 *
 * 结构：
 * 1. 现金余额大卡（Hero）——支出就少一笔、收入就多一笔，这是记账的因果核心
 * 2. 统计卡：**点左上角「月度统计 / 周度统计」标题切换档位**，左右箭头按当前档位翻页；
 *    收入/支出/结余三栏 + 每期净额走势柱
 * 3. 当期流水（按档位过滤；长按出菜单才能删）
 *    · 顶部「日期栏」（v0.93.0）：左侧日期点开可以切到具体某一天，右侧是该天的净额；
 *      选了某天就只看那一天的流水，旁边的「全部」回到按天分组浏览整期；
 *      换档位 / 翻期也回到整期（上回选的那天多半已经不在新的一期里）
 *
 * 无分类/无分组（用户明确不要）：只有「钱、方向、时间、备注」四个信息。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(
    entries: List<LedgerEntry>,
    cashCents: Long,
    scope: DataViewModel.LedgerStatsScope,
    month: YearMonth,
    weekStart: java.time.LocalDate,
    totals: LedgerMath.MonthTotals,
    chainNet: Map<Int, Long>,
    onToggleScope: () -> Unit,
    onShiftPeriod: (Long) -> Unit,
    onBack: () -> Unit,
    onDelete: (Long) -> Unit,
    /** 长按流水 → 菜单「编辑」：把这笔灌回记一笔弹层改（v0.63.0 用户点名） */
    onEdit: (LedgerEntry) -> Unit,
    onAdd: () -> Unit,
) {
    AdaptiveScreen {
        var menuFor by remember { mutableStateOf<LedgerEntry?>(null) }
        val haptic = LocalHapticFeedback.current
        // v0.93.0：顶部日期栏选中的那一天；null = 看整期（按天分组浏览）
        var selectedDay by remember { mutableStateOf<java.time.LocalDate?>(null) }
        var pickingDay by remember { mutableStateOf(false) }
        // 换档位 / 翻上一期下一期时回到"全部"：留着上回选的那天，多半已经不在新的一期里，
        // 一翻页就只剩一句"这一天没有记录"，看着像坏了
        LaunchedEffect(scope, month, weekStart) { selectedDay = null }

        NeuTopBar(
            title = "记账本",
            navigationIcon = {
                NeuCircleButton(onClick = onBack, size = 38.dp) {
                    Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
                }
            },
            actionsWidth = 56.dp,
            actions = {
                NeuCircleButton(onClick = onAdd, size = 38.dp) {
                    Icon(MIcons.Plus, contentDescription = "记一笔", tint = TextPrimary, modifier = Modifier.size(19.dp))
                }
            },
        )

        // 当前档位对应的流水（列表与统计同源，口径必须一致）
        val periodEntries = remember(entries, scope, month, weekStart) {
            val zone = java.time.ZoneId.systemDefault()
            entries.filter { e ->
                val d = java.time.Instant.ofEpochMilli(e.occurredAt).atZone(zone).toLocalDate()
                val inPeriod: Boolean = when (scope) {
                    DataViewModel.LedgerStatsScope.MONTH ->
                        d.year == month.year && d.monthValue == month.monthValue

                    DataViewModel.LedgerStatsScope.WEEK ->
                        !d.isBefore(weekStart) && d.isBefore(weekStart.plusDays(7))
                }
                inPeriod
            }
        }

        // v0.93.0：选中某天后，在**当期**里再按天筛一次。仍然套着当期这一层：
        // 统计卡与流水列表永远同一期，不会出现"列表是 9 月某天、上面统计卡还是 10 月"。
        val visibleEntries = remember(periodEntries, selectedDay) {
            val day = selectedDay ?: return@remember periodEntries
            val zone = java.time.ZoneId.systemDefault()
            periodEntries.filter {
                java.time.Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() == day
            }
        }
        // 顶部栏右侧那个数：**与列表同一批数据**算出来的净额（收入 − 支出）。
        // 同批数据是硬要求——列表 3 笔、旁边却写着整月的总额，等于在骗人。
        val visibleNetCents = remember(visibleEntries) { LedgerMath.balanceCents(visibleEntries) }

        val periodLabel: String = when (scope) {
            DataViewModel.LedgerStatsScope.MONTH -> "${month.monthValue} 月流水 · ${visibleEntries.size} 笔"
            DataViewModel.LedgerStatsScope.WEEK -> "本周流水 · ${visibleEntries.size} 笔"
        }

        // v0.91.0：按天分组（用户点名）。DAO 已按 occurredAt DESC 返回，groupBy 保持插入顺序
        // → 日期段天然从新到旧，每天内部也是从晚到早。别在 LazyListScope 里算：
        // 那不是 @Composable 作用域，里面调 remember 编译不过。
        val dayGroups = remember(visibleEntries) {
            val zone = java.time.ZoneId.systemDefault()
            visibleEntries.groupBy { java.time.Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
        }

        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(contentType = "cash") { CashCard(cashCents) }
            item(contentType = "stats") {
                StatsCard(
                    scope = scope,
                    month = month,
                    weekStart = weekStart,
                    totals = totals,
                    chainNet = chainNet,
                    onToggleScope = onToggleScope,
                    onShiftPeriod = onShiftPeriod,
                )
            }
            item(contentType = "section") {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    NeuSectionTitle(periodLabel)
                    Spacer(Modifier.height(10.dp))
                    LedgerDayBar(
                        day = selectedDay,
                        netCents = visibleNetCents,
                        onPickDay = { pickingDay = true },
                        onShowAll = { selectedDay = null },
                    )
                }
            }
            if (visibleEntries.isEmpty()) {
                item(contentType = "empty") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .neuSurface(16.dp, color = NeuTrack, inset = true)
                            .padding(vertical = 30.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            // 筛到某一天筛空了，要说清是"这一天没有"，不是"一笔都没记过"
                            if (selectedDay != null) "这一天没有记录" else "还没有记录",
                            fontSize = 13.5.sp,
                            color = TextSecondary,
                        )
                    }
                }
            } else {
                dayGroups.forEach { (day, dayEntries) ->
                    // 单日视图里日期已经写在顶部栏，分组头不再重复画一遍
                    if (selectedDay == null) {
                        item(key = "day-$day", contentType = "dayHeader") {
                            LedgerDayHeader(ledgerDayLabel(day))
                        }
                    }
                    items(dayEntries, key = { it.id }, contentType = { "entry" }) { entry ->
                        // 长按菜单锚点：菜单包在条目右下角的小 Box 里（Popup 锚定直接父节点）
                        Box(Modifier.fillMaxWidth()) {
                            LedgerRow(
                                entry = entry,
                                // 日期已在分组标题上，行内只留时分
                                showDate = false,
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    menuFor = entry
                                },
                            )
                            Box(Modifier.align(Alignment.BottomEnd).size(1.dp)) {
                                // v0.63.0：紧凑菜单（和任务页同一份），并补上「编辑这笔」
                                NeuMenu(expanded = menuFor?.id == entry.id, onDismissRequest = { menuFor = null }) {
                                    NeuMenuItem(
                                        "编辑这笔",
                                        onClick = {
                                            menuFor = null
                                            onEdit(entry)
                                        },
                                    )
                                    NeuMenuItem(
                                        "删除这笔",
                                        danger = true,
                                        onClick = {
                                            menuFor = null
                                            onDelete(entry.id)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (pickingDay) {
            // ⚠️ DatePicker 收发的毫秒是 **UTC 当日 0 点**，所以生成与解析都按 UTC：
            // 用本地时区生成会整整差一天（东八区 10 月 10 日 0 点 = UTC 10 月 9 日 16 点）。
            val pickerState = rememberDatePickerState(
                initialSelectedDateMillis = (selectedDay ?: java.time.LocalDate.now()).toEpochDay() * 86_400_000L,
            )
            DatePickerDialog(
                onDismissRequest = { pickingDay = false },
                // Figma 风：白底直角面板 + 黑色选中/边框，去 Material 默认紫蓝（与任务页同一份）
                shape = RoundedCornerShape(22.dp),
                tonalElevation = 0.dp,
                colors = DatePickerDefaults.colors(containerColor = NeuSurface),
                confirmButton = {
                    TextButton(onClick = {
                        pickerState.selectedDateMillis?.let { ms ->
                            selectedDay = java.time.Instant.ofEpochMilli(ms)
                                .atZone(java.time.ZoneId.of("UTC"))
                                .toLocalDate()
                        }
                        pickingDay = false
                    }) { Text("确定", color = NeuAccent, fontWeight = FontWeight.SemiBold) }
                },
                dismissButton = {
                    TextButton(onClick = { pickingDay = false }) { Text("取消", color = MutedText) }
                },
            ) {
                DatePicker(
                    state = pickerState,
                    title = null,
                    headline = null,
                    showModeToggle = false,
                    colors = DatePickerDefaults.colors(
                        containerColor = NeuSurface,
                        headlineContentColor = TextPrimary,
                        selectedDayContainerColor = NeuAccent,
                        todayContentColor = NeuAccent,
                        todayDateBorderColor = NeuAccent,
                        dayContentColor = TextPrimary,
                        weekdayContentColor = MutedText,
                        subheadContentColor = TextPrimary,
                        navigationContentColor = TextPrimary,
                        yearContentColor = TextPrimary,
                        selectedYearContainerColor = NeuAccent,
                        currentYearContentColor = NeuAccent,
                        dividerColor = NeuHairline,
                    ),
                )
            }
        }
    }
}

/**
 * 流水顶部的日期栏（v0.93.0）：左边是当前日期，点它弹日期选择器切到具体某一天；
 * 右边是**这一天的净额**（收入 − 支出，正数带 +、负数自带 −）。
 *
 * 选了某天后列表只显示那一天的流水，右侧金额由同一批数据算出来 —— 两个数字永远对得上。
 * 旁边的「全部」回到按天分组浏览整期（默认就停在这里，一进页面不会先被筛成一天）。
 */
@Composable
private fun LedgerDayBar(
    /** null = 看整期 */
    day: java.time.LocalDate?,
    netCents: Long,
    onPickDay: () -> Unit,
    onShowAll: () -> Unit,
) {
    val press = rememberNeuPressState(raised = 2.dp)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .scale(press.scale)
                .clickable(
                    interactionSource = press.interactionSource,
                    indication = null,
                    onClick = onPickDay,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (day == null) "全部" else ledgerDayLabel(day),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                letterSpacing = (-0.1).sp,
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                MIcons.ChevronDown,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(15.dp),
            )
        }
        if (day != null) {
            Spacer(Modifier.width(10.dp))
            NeuChip(text = "全部", selected = false, onClick = onShowAll)
        }
        Spacer(Modifier.weight(1f))
        Text(
            (if (netCents >= 0) "+" else "") + LedgerMath.formatCents(netCents),
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp,
            color = if (netCents >= 0) IncomeInkText else ExpenseInkText,
        )
    }
}

/**
 * 流水按天分组的日期小标题（v0.91.0）。
 *
 * 用户点名"日期做得更显眼"——所以它**不比照** `NeuSectionTitle` 那套淡灰小字，
 * 而是用正文尺度的深色字 + 下面一根发丝线：翻列表时一眼就能定位到"哪一天"。
 * 与上面「10 月流水 · N 笔」那个计数标题也就自然区分开了（那个才是 section 级的小字）。
 */
@Composable
private fun LedgerDayHeader(label: String) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                letterSpacing = (-0.1).sp,
            )
        }
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(NeuHairline))
    }
}

/** 现金余额 Hero 卡：一笔一笔累出来的因果，不需要额外解释文字 */
@Composable
private fun CashCard(cashCents: Long) {
    NeuCard(contentPadding = 20.dp, modifier = Modifier.fillMaxWidth()) {
        Text("现金余额", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextSecondary, letterSpacing = 1.1.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("¥", fontSize = 20.sp, color = TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
            Spacer(Modifier.width(5.dp))
            // v0.56.14：里程表滚动**恢复**（v0.56.11 删多了——用户点名"记账本详细页的动效"要回；
            // 保持静态的是数据页入口卡那处，不在此列）
            RollingAmountText(
                text = LedgerMath.formatCents(cashCents),
                fontSize = 40.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-1).sp,
                color = TextPrimary,
            )
        }
    }
}

/**
 * 统计卡（v0.42.1）：**点左上角的档位标题在「月度统计 / 周度统计」之间切换**，
 * 左侧箭头按当前档位翻上一期、右侧箭头翻下一期。
 *
 * 用户指定"点击左上角那个月度统计切换"——所以可点的是标题本身（带下划点提示可点），
 * 不是另加一排分段按钮。旁边始终显示当前期的文字锚点（`2026 · 10 月` / `9月29日 – 10月5日`），
 * 让用户翻页时知道自己在看哪一期。
 */
@Composable
private fun StatsCard(
    scope: DataViewModel.LedgerStatsScope,
    month: YearMonth,
    weekStart: java.time.LocalDate,
    totals: LedgerMath.MonthTotals,
    chainNet: Map<Int, Long>,
    onToggleScope: () -> Unit,
    onShiftPeriod: (Long) -> Unit,
) {
    val isMonth = scope == DataViewModel.LedgerStatsScope.MONTH
    val togglePress = rememberNeuPressState(raised = 2.dp)

    NeuCard(contentPadding = 18.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 档位标题：点它切月/周（下面那根短下划线是"可点"的视觉提示）
            Column(
                Modifier
                    .testTag("ledger_scope_toggle")
                    .scale(togglePress.scale)
                    .clickable(
                        interactionSource = togglePress.interactionSource,
                        indication = null,
                        onClick = onToggleScope,
                    ),
            ) {
                Text(
                    if (isMonth) "月度统计" else "周度统计",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    letterSpacing = 1.1.sp,
                )
                Spacer(Modifier.height(3.dp))
                Box(Modifier.width(28.dp).height(1.5.dp).background(NeuAccent))
            }
            Spacer(Modifier.weight(1f))
            PeriodNavButton(MIcons.Back, onShiftPeriod, -1)
            Text(
                if (isMonth) "${month.year} · ${month.monthValue} 月" else weekRangeLabel(weekStart),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            PeriodNavButton(MIcons.ChevronRight, onShiftPeriod, +1)
        }
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth()) {
            StatCell("收入", "+" + LedgerMath.formatCents(totals.incomeCents), IncomeInkText, Modifier.weight(1f))
            StatCell("支出", "-" + LedgerMath.formatCents(totals.expenseCents), ExpenseInkText, Modifier.weight(1f))
            StatCell(
                "结余",
                (if (totals.netCents >= 0) "+" else "") + LedgerMath.formatCents(totals.netCents),
                TextPrimary,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(18.dp))
        NetBars(isMonth = isMonth, month = month, weekStart = weekStart, chainNet = chainNet)
    }
}

/** 周区间标签：`9月29日 – 10月5日`（跨月才是常态，两段都要带月） */
internal fun weekRangeLabel(weekStart: java.time.LocalDate): String {
    val end = weekStart.plusDays(6)
    return "${weekStart.monthValue}月${weekStart.dayOfMonth}日 – ${end.monthValue}月${end.dayOfMonth}日"
}

private val IncomeInkText = Color(0xFFE5484D)
private val ExpenseInkText = Color(0xFF2E9E6B)

@Composable
private fun StatCell(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = MutedText)
        Spacer(Modifier.height(5.dp))
        // v0.56.14：三个统计数的滚动**恢复**（v0.56.11 删多了）
        RollingAmountText(
            text = value,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.34).sp,
            color = valueColor,
            durationMs = 520,
        )
    }
}

@Composable
private fun PeriodNavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onShiftPeriod: (Long) -> Unit,
    delta: Long,
) {
    val press = rememberNeuPressState(raised = 2.dp)
    Box(
        Modifier
            .size(26.dp)
            .scale(press.scale)
            .neuSurface(999.dp, color = NeuTrack, inset = true)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = { onShiftPeriod(delta) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
    }
}

/**
 * 每期净额走势：以 0 为基线的双向细柱（收入向上、支出向下），峰值归一化——
 * 只表达形状，绝对数额由上面三栏给。
 *
 * 月档画 `[1, 当月天数]` 共 N 根；周档固定画 7 根（key 1..7 = 周一…周日），
 * 位置恒定，翻周时柱子不会横向乱跳。
 */
@Composable
private fun NetBars(
    isMonth: Boolean,
    month: YearMonth,
    weekStart: java.time.LocalDate,
    chainNet: Map<Int, Long>,
) {
    val hairline = NeuHairline
    val up = IncomeInkText
    val down = ExpenseInkText
    val slotCount = if (isMonth) month.lengthOfMonth() else 7
    // 换期要重新播一次生长动画：key 带上期锚点
    var revealed by remember(month, weekStart) { mutableStateOf(false) }
    LaunchedEffect(month, weekStart) { revealed = true }
    val grow by animateFloatAsState(
        if (revealed) 1f else 0f,
        tween(560, easing = EaseOutExpo),
        label = "netBars",
    )
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        val slot = size.width / slotCount
        val barWidth = (slot * 0.52f).coerceAtLeast(1.5f)
        val midY = size.height / 2
        val maxAbs = (chainNet.values.maxOfOrNull { kotlin.math.abs(it) } ?: 0L).coerceAtLeast(1L)
        // 0 基线：极细发丝线（空档期也有基线，不会看着像坏掉）
        drawRect(
            color = hairline,
            topLeft = Offset(0f, midY - 0.5f),
            size = Size(size.width, 1f),
        )
        for (i in 1..slotCount) {
            val net = chainNet[i] ?: continue
            val ratio = (kotlin.math.abs(net).toFloat() / maxAbs).coerceIn(0f, 1f)
            val h = (midY - 3f) * ratio * grow
            if (h <= 0.5f) continue
            val left = (i - 1) * slot + (slot - barWidth) / 2f
            val radius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
            if (net >= 0) {
                drawRoundRect(
                    color = up,
                    topLeft = Offset(left, midY - h),
                    size = Size(barWidth, h),
                    cornerRadius = radius,
                )
            } else {
                drawRoundRect(
                    color = down,
                    topLeft = Offset(left, midY),
                    size = Size(barWidth, h),
                    cornerRadius = radius,
                )
            }
        }
    }
}
