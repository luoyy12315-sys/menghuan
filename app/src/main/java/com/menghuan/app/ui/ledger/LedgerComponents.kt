package com.menghuan.app.ui.ledger

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.model.LedgerEntry
import com.menghuan.app.data.model.LedgerMath
import com.menghuan.app.data.model.LedgerSource
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.rememberNeuPressState
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.common.EaseOutExpo
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary

/** 收入金额色（中国习惯：红 = 进账）。只在金额数字上点缀，不上界面骨架。 */
private val IncomeInk = Color(0xFFE5484D)

/** 支出金额色（绿 = 出账） */
private val ExpenseInk = Color(0xFF2E9E6B)

/**
 * 记一笔弹层（v0.42.0）：手动 / 语音 / 识图三种录入共用同一个表单。
 *
 * 交互设计：
 * - 顶部「支出 / 收入」二选一（默认支出）——记账场景八成是支出
 * - 金额是最重要的字段：最大字号 + 独立凹陷面，语音/识图结果直接灌进这个框
 * - 三个入口横排：语音、识图、保存（Primary 黑底白字收尾）
 * - 识别失败不瞎猜：解析不到金额就不填金额，只把文本放进备注并提示用户手填
 */
@Composable
fun LedgerEntrySheet(
    cashCents: Long,
    onDismiss: () -> Unit,
    onSave: (isIncome: Boolean, amountCents: Long, note: String, source: String) -> Unit,
    voice: VoiceLedgerController,
    onNeedPermission: () -> Unit,
    hasPermission: () -> Boolean,
    onPickImage: () -> Unit,
    imageParsing: Boolean,
    imageMessage: String?,
    onClearImageMessage: () -> Unit,
    initialDraft: LedgerParser.LedgerDraft? = null,
    initialSource: String = LedgerSource.MANUAL,
) {
    var isIncome by remember { mutableStateOf(initialDraft?.isIncome ?: false) }
    var amountText by remember {
        mutableStateOf(initialDraft?.amountCents?.let { LedgerMath.formatCents(it) } ?: "")
    }
    var note by remember { mutableStateOf(initialDraft?.note.orEmpty()) }
    var source by remember { mutableStateOf(initialSource) }
    var hint by remember { mutableStateOf<String?>(null) }

    val listening by voice.listening.collectAsState()
    val recognizing by voice.recognizing.collectAsState()

    // 外部（识图）灌进来的草稿：同步到表单并记下来源
    LaunchedEffect(initialDraft) {
        initialDraft?.let {
            isIncome = it.isIncome
            it.amountCents?.let { cents -> amountText = LedgerMath.formatCents(cents) }
            if (it.note.isNotBlank()) note = it.note
            source = initialSource
            if (!it.isValid) hint = "没识别出金额，请手动填写"
        }
    }
    LaunchedEffect(imageMessage) {
        imageMessage?.let {
            hint = it
            onClearImageMessage()
        }
    }
    // v0.56.14：端侧离线识别**没有逐字 partial**（语音条/通话同款），
    // 识别完一次性经 onResult 灌表单，不再有"边说边上屏"。

    NeuSheetScaffold(title = "记一笔", onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NeuChip("支出", !isIncome, Modifier.weight(1f)) { isIncome = false }
            NeuChip("收入", isIncome, Modifier.weight(1f)) { isIncome = true }
        }
        Spacer(Modifier.height(18.dp))

        Text("金额", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .neuSurface(14.dp, inset = true)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "¥",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Light,
                    color = if (amountText.isEmpty()) MutedText else TextPrimary,
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = amountText,
                    onValueChange = { input -> amountText = sanitizeAmount(input) },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                        letterSpacing = (-0.6).sp,
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(NeuAccent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    decorationBox = { inner ->
                        Box {
                            if (amountText.isEmpty()) {
                                Text("0.00", fontSize = 30.sp, color = MutedText, fontWeight = FontWeight.Light)
                            }
                            inner()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        NeuTextField(
            value = note,
            onValueChange = { note = it },
            label = "备注",
            placeholder = if (listening) "正在听…" else "花在哪 / 从哪来（可留空）",
            singleLine = true,
        )

        // 识别反馈条：录音中 / 转写中 / 识图中 / 成功失败都在这条线上说清
        val statusText = when {
            listening -> "正在听你说…（说完点「停止」）"
            recognizing -> "正在识别…"
            imageParsing -> "正在识别图片…"
            else -> hint
        }
        if (statusText != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .neuSurface(10.dp, color = NeuTrack, inset = true)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(statusText, fontSize = 12.5.sp, color = TextSecondary)
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LedgerActionButton(
                label = when {
                    listening -> "停止"
                    recognizing -> "识别中"
                    else -> "语音"
                },
                icon = MIcons.Mic,
                active = listening || recognizing,
                modifier = Modifier.weight(1f),
                onClick = {
                    when {
                        listening -> voice.stopListening()
                        // 端侧转写中（1~3 秒）：不可点，等结果
                        recognizing -> Unit
                        !hasPermission() -> {
                            // 未授权：先申请（进页面时不突袭用户，点语音才弹）
                            onNeedPermission()
                            hint = "允许录音后即可语音记账"
                        }
                        else -> {
                            hint = null
                            voice.start(
                                onResult = { text ->
                                    val draft = LedgerParser.fromText(text)
                                    isIncome = draft.isIncome
                                    draft.amountCents?.let { amountText = LedgerMath.formatCents(it) }
                                    note = draft.note
                                    source = LedgerSource.VOICE
                                    hint = if (draft.isValid) "已识别，核对后保存" else "没听出金额，请手动填写"
                                },
                                onError = { hint = it },
                            )
                        }
                    }
                },
            )
            LedgerActionButton(
                label = "识图",
                icon = MIcons.ScanFrame,
                active = imageParsing,
                modifier = Modifier.weight(1f),
                onClick = {
                    if (listening) voice.stopListening()
                    hint = null
                    onPickImage()
                },
            )
            Box(Modifier.weight(1f)) {
                val amountCents = LedgerMath.parseAmountToCents(amountText)
                NeuButton(
                    text = "保存",
                    onClick = {
                        val cents = LedgerMath.parseAmountToCents(amountText)
                        if (cents != null) {
                            onSave(isIncome, cents, note.trim(), source)
                            onDismiss()
                        } else {
                            hint = "请先填写金额"
                        }
                    },
                    kind = NeuButtonKind.Primary,
                    enabled = amountCents != null,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("现金余额", fontSize = 12.sp, color = MutedText)
            Spacer(Modifier.width(8.dp))
            // v0.56.11（用户点名"金额不需要跳动"）：改回静态显示
            Text(
                "¥${LedgerMath.formatCents(cashCents)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
            )
        }
    }
}

/** 金额输入净化：只留数字与一个小数点，小数最多两位 */
internal fun sanitizeAmount(input: String): String {
    val filtered = input.filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    if (firstDot < 0) return filtered
    val intPart = filtered.substring(0, firstDot)
    val decPart = filtered.substring(firstDot + 1).filter { it.isDigit() }.take(2)
    return "$intPart.$decPart"
}

/** 语音/识图按钮：图标 + 文字，激活态（录音中/识别中）换强调色实心 */
@Composable
private fun LedgerActionButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val press = rememberNeuPressState(raised = 4.dp)
    val bg by animateColorAsState(
        if (active) NeuAccent else com.menghuan.app.ui.theme.NeuSurface,
        tween(220, easing = EaseOutExpo),
        label = "ledgerActionBg",
    )
    val ink = if (active) AccentInk else TextPrimary
    Box(
        modifier
            .scale(press.scale)
            .height(52.dp)
            .neuSurface(999.dp, color = bg, elevation = if (active) 4.dp else 0.dp)
            .clickable(interactionSource = press.interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ink)
        }
    }
}

/**
 * 一条流水的行（记账本全屏页 / 最近记录共用）。
 * 金额符号区分收支、颜色遵循中国习惯（进账红 / 出账绿）；
 * 长按只弹菜单（删除在菜单里点，需二次确认的那一下就是菜单项）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LedgerRow(
    entry: LedgerEntry,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    showDate: Boolean = true,
) {
    val press = rememberNeuPressState(raised = 4.dp)
    val dateLabel = remember(entry.occurredAt) { formatLedgerTime(entry.occurredAt) }
    Row(
        Modifier
            .fillMaxWidth()
            .scale(press.scale)
            .combinedClickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = { onClick?.invoke() },
                onLongClick = { onLongClick?.invoke() },
            )
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .neuSurface(999.dp, color = NeuTrack, inset = true),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (entry.isIncome) MIcons.TrendUp else MIcons.TrendDown,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.note.ifBlank { if (entry.isIncome) "一笔收入" else "一笔支出" },
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showDate) {
                    Text(dateLabel, fontSize = 11.5.sp, color = MutedText)
                    Text(" · ", fontSize = 11.5.sp, color = MutedText)
                }
                Text(LedgerSource.label(entry.source), fontSize = 11.5.sp, color = MutedText)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            (if (entry.isIncome) "+" else "-") + LedgerMath.formatCents(entry.amountCents),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.3).sp,
            color = if (entry.isIncome) IncomeInk else ExpenseInk,
        )
    }
}

/** 时间 → "M/d HH:mm"（本机时区） */
internal fun formatLedgerTime(epochMillis: Long): String {
    val t = java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDateTime()
    return "${t.monthValue}/${t.dayOfMonth} ${"%02d".format(t.hour)}:${"%02d".format(t.minute)}"
}
