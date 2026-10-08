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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
 *
 * 无分类/无分组（用户明确不要）：只有「钱、方向、时间、备注」四个信息。
 */
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

        val periodLabel: String = when (scope) {
            DataViewModel.LedgerStatsScope.MONTH -> "${month.monthValue} 月流水 · ${periodEntries.size} 笔"
            DataViewModel.LedgerStatsScope.WEEK -> "本周流水 · ${periodEntries.size} 笔"
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
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeuSectionTitle(periodLabel, Modifier.weight(1f))
                }
            }
            if (periodEntries.isEmpty()) {
                item(contentType = "empty") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .neuSurface(16.dp, color = NeuTrack, inset = true)
                            .padding(vertical = 30.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("还没有记录", fontSize = 13.5.sp, color = TextSecondary)
                    }
                }
            } else {
                items(periodEntries, key = { it.id }, contentType = { "entry" }) { entry ->
                    // 长按菜单锚点：菜单包在条目右下角的小 Box 里（Popup 锚定直接父节点）
                    Box(Modifier.fillMaxWidth()) {
                        LedgerRow(
                            entry = entry,
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
