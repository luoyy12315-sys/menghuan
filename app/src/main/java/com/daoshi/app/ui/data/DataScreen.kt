package com.daoshi.app.ui.data

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daoshi.app.data.model.AiInsight
import com.daoshi.app.data.model.ProfileFact
import com.daoshi.app.ui.common.EaseOutExpo
import com.daoshi.app.ui.common.NeuButton
import com.daoshi.app.ui.common.NeuButtonKind
import com.daoshi.app.ui.common.NeuCard
import com.daoshi.app.ui.common.NeuSheetScaffold
import com.daoshi.app.ui.common.NeuTextField
import com.daoshi.app.ui.common.NeuTextButton
import com.daoshi.app.ui.common.NeuTopBar
import com.daoshi.app.ui.common.neuRowPress
import com.daoshi.app.ui.common.neuSurface
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.TextPrimary
import com.daoshi.app.ui.theme.TextSecondary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 洞察卡日期：今天/昨天/具体日期 */
private fun AiInsight.dateLabel(): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(createdAt).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
}

/**
 * 我的数据页：目标完成度 + 基础档案 + 近 7 天打卡 + AI 洞察，全部真数据驱动。
 * 基础档案 = ProfileFact（可编辑，编辑后注入聊天 system prompt）。
 */
@Composable
fun DataScreen(vm: DataViewModel = viewModel(factory = DataViewModel.Factory)) {
    val facts by vm.facts.collectAsState()
    val weekProgress by vm.weekProgress.collectAsState()
    val weekDaily by vm.weekDaily.collectAsState()
    val weekRates by vm.weekRates.collectAsState()
    val insights by vm.insights.collectAsState()
    val extracting by vm.extracting.collectAsState()
    val extractMessage by vm.extractMessage.collectAsState()
    val apiReady by vm.apiReady.collectAsState()
    var showEditSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // 提炼结果反馈：展示数秒后自动清除
    LaunchedEffect(extractMessage) {
        if (extractMessage != null) {
            kotlinx.coroutines.delay(4000)
            vm.clearExtractMessage()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶栏（编辑入口在基础档案栏，页面骨架保持极简）
        NeuTopBar(title = "我的数据")

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            OverviewCard(progress = weekProgress, weekRates = weekRates, scrollProvider = { scrollState.value })

            // 基础档案：标题栏即编辑入口（点整行档案也能进编辑弹层）
            SectionHeader("基础档案", actionText = "编辑", onAction = { showEditSheet = true })
            NeuCard(cornerRadius = 22.dp) {
                if (facts.isEmpty()) {
                    Text(
                        "暂无档案",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                        fontSize = 14.sp,
                        color = MutedText,
                    )
                } else {
                    facts.forEachIndexed { i, fact ->
                        if (i > 0) Spacer(Modifier.height(8.dp))
                        ProfileRow(
                            label = fact.key,
                            value = fact.value,
                            aiSource = fact.source == "ai",
                            onClick = { showEditSheet = true },
                        )
                    }
                }
            }

            // 习惯卡（真数据）：近 7 天每日完成任务数 = 打卡热力
            SectionHeader("近 7 天打卡")
            HabitCard(weekDaily)

            // AI 洞察（真数据）；API 已配置时提供手动「提炼」入口
            SectionHeader(
                "AI 洞察",
                actionText = if (apiReady) (if (extracting) "提炼中…" else "提炼") else null,
                onAction = { vm.refreshInsights() },
            )
            extractMessage?.let { message ->
                Text(
                    message,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    fontSize = 11.sp,
                    color = NeuAccent,
                )
            }
            insights.forEach { insight ->
                InsightCard(
                    insight.title,
                    insight.detail,
                    insight.dateLabel(),
                    onConvert = {
                        vm.convertInsightToTask(insight)
                        Toast.makeText(context, "已转为任务", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    if (showEditSheet) {
        ProfileEditSheet(
            facts = facts,
            onSave = { items, removed ->
                vm.saveEdits(items, removed)
                showEditSheet = false
            },
            onDismiss = { showEditSheet = false },
        )
    }
}

/**
 * 区块头（全页唯一规格）：13sp SemiBold 黑标题 + 可选 13sp Medium 动作，基线对齐。
 * 基础档案 / 近 7 天打卡 / AI 洞察 三处复用，无需动作时只渲染标题。
 */
@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(modifier.padding(horizontal = 4.dp)) {
        Text(
            title,
            modifier = Modifier.alignByBaseline(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(Modifier.weight(1f))
        if (actionText != null && onAction != null) {
            Text(
                actionText,
                modifier = Modifier
                    .alignByBaseline()
                    .neuRowPress(onAction)
                    .padding(start = 10.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = NeuAccent,
            )
        }
    }
}

/** 习惯卡（真数据）：近 7 天打卡热力——每天完成任务数，>0 记一格；圆点级联浮现（40ms 间隔） */
@Composable
private fun HabitCard(weekDaily: List<DayCount>) {
    val totalDone = weekDaily.sumOf { it.done }
    val note = if (totalDone == 0) "" else "共完成 $totalDone 项"
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    NeuCard(cornerRadius = 22.dp) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("每日任务打卡", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text("6 天前 → 今天", fontSize = 12.sp, color = MutedText)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            weekDaily.forEachIndexed { index, day ->
                val filled = day.done > 0
                // 逐点级联浮现：动画 spec 自带 delay（非协程 delay，Robolectric 安全）
                val appear by animateFloatAsState(
                    if (revealed) 1f else 0f,
                    tween(340, delayMillis = index * 40, easing = EaseOutExpo),
                )
                Box(
                    Modifier
                        .graphicsLayer {
                            alpha = appear
                            val scale = 0.6f + 0.4f * appear
                            scaleX = scale
                            scaleY = scale
                        }
                        .size(24.dp)
                        .then(
                            if (filled) {
                                Modifier.background(NeuAccent, CircleShape)
                            } else {
                                Modifier.neuSurface(999.dp, inset = true, shape = CircleShape)
                            },
                        ),
                )
            }
        }
            if (note.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(note, fontSize = 12.sp, color = MutedText)
            }
    }
}

/** 本周目标完成度：英雄渐变卡（产品内容彩）+ 白色进度环扫入 + 数字滚动 + 轻微滚动视差 */
@Composable
private fun OverviewCard(progress: WeekProgress, weekRates: List<Float>, scrollProvider: () -> Int) {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val sweep by animateFloatAsState(
        if (revealed) progress.fraction else 0f,
        tween(1100, easing = EaseOutExpo),
    )
    val percentText = "${(sweep * 100).toInt()}%"
    val subtitle = if (progress.total == 0) "" else "本周完成 ${progress.done}/${progress.total} 项"

    Box(
        Modifier
            .fillMaxWidth()
            .neuSurface(24.dp)
            .padding(18.dp),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 视差：滚动位置延迟到渲染阶段读取（lambda 内读取 State），
                // 滚动每一帧只更新图层属性，不再触发整个数据页重组
                Box(
                    Modifier.graphicsLayer { translationY = scrollProvider() * 0.15f },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(96.dp)) {
                        val stroke = 10.dp.toPx()
                        val inset = stroke / 2 + 4.dp.toPx()
                        val diameter = size.minDimension - inset * 2
                        // 黑 8% 轨道 + 纯黑进度弧（黑白骨架，进度即内容）
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.08f),
                            radius = diameter / 2 + stroke / 2,
                            style = Stroke(width = stroke),
                        )
                        drawArc(
                            color = Color.Black,
                            startAngle = -90f,
                            sweepAngle = 360f * sweep,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = Size(diameter, diameter),
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    Text(
                        percentText,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.5).sp,
                        color = TextPrimary,
                    )
                }
                Spacer(Modifier.width(20.dp))
                Column {
                    Text(
                        "本周目标完成度",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.14).sp,
                        color = TextPrimary,
                    )
                    if (subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(5.dp))
                        Text(subtitle, fontSize = 12.sp, color = MutedText)
                    }
                }
            }
            // 7 天完成率趋势（每日任务口径）；无每日任务不渲染
            if (weekRates.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Sparkline(weekRates, Modifier.fillMaxWidth())
            }
        }
    }
}

/** 7 天完成率 Sparkline：纯 Canvas 折线（黑 1.6dp 圆帽，末端实心圆点），左→右 = 6 天前 → 今天 */
@Composable
private fun Sparkline(rates: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier.height(36.dp)) {
        if (rates.size < 2) return@Canvas
        val padY = 5.dp.toPx()
        val usable = size.height - padY * 2
        val stepX = if (rates.size > 1) size.width / (rates.size - 1) else size.width
        val points = rates.mapIndexed { i, rate ->
            Offset(i * stepX, padY + (1f - rate) * usable)
        }
        for (i in 0 until points.lastIndex) {
            drawLine(
                color = Color.Black,
                start = points[i],
                end = points[i + 1],
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawCircle(color = Color.Black, radius = 3.dp.toPx(), center = points.last())
    }
}

@Composable
private fun ProfileRow(label: String, value: String, aiSource: Boolean, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().neuRowPress(onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 14.sp,
                color = MutedText,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // AI 提炼来源徽标：与手填条目区分（手填的编辑更新后仍标记 manual）
            if (aiSource) {
                Spacer(Modifier.width(4.dp))
                Text(
                    "AI",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NeuAccent,
                )
            }
        }
        Text(
            value,
            fontSize = 14.sp,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = 2,
        )
        Spacer(Modifier.width(6.dp))
    }
}

/** 档案编辑弹层：每行 key/value 可改，可增删，保存后写入 Room 并注入聊天 prompt */
@Composable
private fun ProfileEditSheet(
    facts: List<ProfileFact>,
    onSave: (items: List<DraftFact>, removedIds: List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val drafts = remember {
        androidx.compose.runtime.mutableStateListOf<DraftFact>().apply {
            facts.forEach { add(DraftFact(it.id, it.key, it.value)) }
        }
    }
    val removedIds = remember { androidx.compose.runtime.mutableStateListOf<Long>() }

    NeuSheetScaffold(title = "编辑档案", onDismiss = onDismiss) {
        Spacer(Modifier.height(14.dp))
        drafts.forEachIndexed { index, draft ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeuTextField(
                    value = draft.key,
                    onValueChange = { drafts[index] = draft.copy(key = it) },
                    placeholder = "项目",
                    modifier = Modifier.width(104.dp),
                )
                Spacer(Modifier.width(8.dp))
                NeuTextField(
                    value = draft.value,
                    onValueChange = { drafts[index] = draft.copy(value = it) },
                    placeholder = "内容",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(38.dp)
                        .neuSurface(999.dp, inset = true),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "×",
                        fontSize = 17.sp,
                        color = MutedText,
                        modifier = Modifier
                            .clickable {
                                draft.id?.let { removedIds.add(it) }
                                drafts.removeAt(index)
                            }
                            .padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        NeuTextButton("＋ 添加一项", onClick = { drafts.add(DraftFact(null, "", "")) })
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth()) {
            NeuButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            NeuButton(
                text = "保存",
                onClick = { onSave(drafts.toList(), removedIds.toList()) },
                kind = NeuButtonKind.Primary,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun InsightCard(
    title: String,
    desc: String,
    date: String,
    onConvert: () -> Unit,
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    // 进场：EaseOutExpo 340ms（淡入 + 轻微上浮）
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val enterProgress by animateFloatAsState(
        if (appeared) 1f else 0f,
        tween(340, easing = EaseOutExpo),
    )
    Box {
        NeuCard(
            cornerRadius = 22.dp,
            contentPadding = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = enterProgress
                    translationY = (1f - enterProgress) * 12.dp.toPx()
                }
                .combinedClickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onLongClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(30.dp)
                        .background(NeuAccent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✦", fontSize = 13.sp, color = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(date, fontSize = 11.sp, color = com.daoshi.app.ui.theme.FaintText)
            }
            Spacer(Modifier.height(9.dp))
            Text(desc, fontSize = 13.sp, lineHeight = 21.sp, color = TextSecondary)
        }
        // 长按菜单：洞察 → 任务（复用 AI 建议的落库语义，createdBy=ai）
        androidx.compose.material3.DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = Color.White,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        ) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("转为任务", fontSize = 15.sp, color = TextPrimary) },
                onClick = {
                    menuOpen = false
                    onConvert()
                },
            )
        }
    }
}
