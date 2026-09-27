package com.daoshi.app.ui.data

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daoshi.app.data.model.ProfileFact
import com.daoshi.app.ui.common.EaseOutExpo
import com.daoshi.app.ui.common.NeuButton
import com.daoshi.app.ui.common.NeuButtonKind
import com.daoshi.app.ui.common.NeuCard
import com.daoshi.app.ui.common.NeuSectionTitle
import com.daoshi.app.ui.common.NeuSheetScaffold
import com.daoshi.app.ui.common.NeuTextButton
import com.daoshi.app.ui.common.NeuTextField
import com.daoshi.app.ui.common.NeuTopBar
import com.daoshi.app.ui.common.neuSurface
import com.daoshi.app.ui.theme.AccentBrush
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.NeuBackground
import com.daoshi.app.ui.theme.NeuTrack
import com.daoshi.app.ui.theme.TextPrimary
import com.daoshi.app.ui.theme.TextSecondary

/**
 * 我的数据页（新拟态）：目标完成度 + 基础档案 + 习惯追踪 + AI 洞察。
 * 基础档案 = ProfileFact 真实数据（可编辑，编辑后注入聊天 system prompt）；
 * 完成度/习惯/洞察仍为演示数据，二期接 AI 提炼流水线。
 */
@Composable
fun DataScreen(vm: DataViewModel = viewModel(factory = DataViewModel.Factory)) {
    val facts by vm.facts.collectAsState()
    var showEditSheet by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶栏（设置入口只在会话列表页）
        NeuTopBar(
            title = "我的数据",
            actions = { NeuTextButton("编辑", onClick = { showEditSheet = true }) },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            OverviewCard(scrollFraction = scrollState.value)

            NeuSectionTitle("基础档案", modifier = Modifier.padding(horizontal = 4.dp))
            NeuCard(cornerRadius = 22.dp) {
                if (facts.isEmpty()) {
                    Text(
                        "暂无档案，点右上角「编辑」添加",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                        fontSize = 14.sp,
                        color = MutedText,
                    )
                } else {
                    facts.forEachIndexed { i, fact ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        ProfileRow(
                            label = fact.key,
                            value = fact.value,
                            onClick = { showEditSheet = true },
                        )
                    }
                }
            }

            NeuSectionTitle("追踪中的习惯", modifier = Modifier.padding(horizontal = 4.dp))
            NeuCard(cornerRadius = 22.dp) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("睡前刷手机", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text("追踪 21 天", fontSize = 12.sp, color = MutedText)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    repeat(7) { i ->
                        val filled = i < 5
                        Box(
                            Modifier
                                .size(24.dp)
                                .then(
                                    if (filled) {
                                        Modifier.background(AccentBrush, CircleShape)
                                    } else {
                                        Modifier.neuSurface(
                                            999.dp,
                                            color = NeuBackground,
                                            elevation = 4.dp,
                                            inset = true,
                                            shape = CircleShape,
                                        )
                                    },
                                ),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("近 7 天 5 天超标 · 平均时长 1h 40m", fontSize = 12.sp, color = MutedText)
            }

            NeuSectionTitle("AI 洞察", modifier = Modifier.padding(horizontal = 4.dp))
            InsightCard(
                "熬夜与短视频强相关",
                "过去 14 天，睡前刷短视频的日子平均晚睡 82 分钟。建议把「睡前刷手机」替换为固定的放松流程。",
                "今天",
            )
            InsightCard(
                "上午效率最高",
                "你的深度工作集中在 9:00–11:30，重要任务建议安排在这个时段，下午适合处理琐事。",
                "昨天",
            )
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

/** 本周目标完成度：凹陷轨道圆环 + 强调弧（扫入动画 + 数字滚动）+ 轻微滚动视差 */
@Composable
private fun OverviewCard(scrollFraction: Int) {
    // 滚动视差：环随上滚轻微下移，制造纵深
    val parallax = scrollFraction * 0.15f
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { revealed = true }
    val sweep by animateFloatAsState(
        if (revealed) 0.62f else 0f,
        tween(1000, easing = EaseOutExpo),
    )
    val percentText = (sweep * 100).toInt().toString() + "%"

    NeuCard(cornerRadius = 24.dp, contentPadding = 18.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.graphicsLayer { translationY = parallax },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(96.dp)) {
                    val stroke = 10.dp.toPx()
                    val inset = stroke / 2 + 4.dp.toPx()
                    val diameter = size.minDimension - inset * 2
                    // 凹陷轨道：底色环 + 内缘暗线
                    drawCircle(
                        color = NeuTrack,
                        radius = diameter / 2 + stroke / 2,
                        style = Stroke(width = stroke),
                    )
                    drawArc(
                        brush = AccentBrush,
                        startAngle = -90f,
                        sweepAngle = 360f * sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(diameter, diameter),
                        style = Stroke(width = stroke),
                    )
                }
                Text(percentText, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Text("本周目标完成度", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(Modifier.height(5.dp))
                Text("较上周 +13% · 继续保持", fontSize = 12.sp, color = MutedText)
            }
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = MutedText,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            fontSize = 14.sp,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = 2,
        )
        Spacer(Modifier.width(6.dp))
        NeuTextButton("改", onClick = onClick)
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
                        .neuSurface(999.dp, color = NeuBackground, elevation = 4.dp, inset = true),
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

@Composable
private fun InsightCard(title: String, desc: String, date: String) {
    NeuCard(cornerRadius = 22.dp, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .background(AccentBrush, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("✦", fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.White)
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
}
