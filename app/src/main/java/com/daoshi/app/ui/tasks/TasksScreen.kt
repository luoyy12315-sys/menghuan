package com.daoshi.app.ui.tasks

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daoshi.app.data.model.TaskItem
import com.daoshi.app.data.model.isEffectivelyDone
import com.daoshi.app.ui.common.NeuButton
import com.daoshi.app.ui.common.NeuButtonKind
import com.daoshi.app.ui.common.NeuCard
import com.daoshi.app.ui.common.NeuCheckCircle
import com.daoshi.app.ui.common.NeuChip
import com.daoshi.app.ui.common.NeuCircleButton
import com.daoshi.app.ui.common.NeuSectionTitle
import com.daoshi.app.ui.common.NeuSheetScaffold
import com.daoshi.app.ui.common.NeuTextField
import com.daoshi.app.ui.common.NeuTextButton
import com.daoshi.app.ui.common.NeuTopBar
import com.daoshi.app.ui.common.neuSurface
import com.daoshi.app.ui.theme.FaintText
import com.daoshi.app.ui.theme.MIcons
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.NeuDanger
import com.daoshi.app.ui.theme.TextPrimary
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 任务清单页：Room 真数据 + 双板块（每日任务 / 基础任务）+ 勾选打卡。
 * 新建任务走右上角 ＋ → 底部弹层（可开「每日重复」）；长按任务行进编辑弹层；设提醒时申请通知权限。
 */
private fun dueDateOf(task: TaskItem): LocalDate =
    if (task.dueAt == 0L) LocalDate.now()
    else Instant.ofEpochMilli(task.dueAt).atZone(ZoneId.systemDefault()).toLocalDate()

private fun startOfDayMs(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(vm: TasksViewModel = viewModel(factory = TasksViewModel.Factory)) {
    val tasks by vm.tasks.collectAsState()
    var showTaskSheet by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<TaskItem?>(null) }
    var deleteTarget by remember { mutableStateOf<TaskItem?>(null) }

    val today = LocalDate.now()
    val tomorrow = today.plusDays(1)
    // 每日任务板块：无日期概念，当天未完成的排前面
    val dailyTasks = tasks.filter { it.repeatDaily }.sortedBy { it.isEffectivelyDone() }
    // 基础任务板块：一次性，按日期分组（现有逻辑）
    val basicTasks = tasks.filter { !it.repeatDaily }
    val overdue = basicTasks.filter { dueDateOf(it) < today }
    val todayTasks = basicTasks.filter { dueDateOf(it) == today }
    val tomorrowTasks = basicTasks.filter { dueDateOf(it) == tomorrow }
    val laterTasks = basicTasks.filter { dueDateOf(it) > tomorrow }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NeuTopBar(
            title = "任务",
            actions = {
                NeuCircleButton(
                    onClick = { editingTask = null; showTaskSheet = true },
                    size = 38.dp,
                ) {
                    Icon(MIcons.Plus, contentDescription = "新建任务", tint = TextPrimary, modifier = Modifier.size(19.dp))
                }
            },
        )

        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyTasksHint()
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 板块一：每日任务（无日期，每天重置；当天完成的排后面）
            if (dailyTasks.isNotEmpty()) {
                item { NeuSectionTitle("每日任务", modifier = Modifier.padding(top = 10.dp)) }
                itemsIndexed(dailyTasks, key = { _, t -> t.id }) { index, task ->
                    Box(Modifier.animateItem(fadeInSpec = tween(340, delayMillis = (index * 45).coerceAtMost(320)))) {
                        TaskRow(task, onToggle = { vm.toggle(task) }, onLongPress = { editingTask = task; showTaskSheet = true })
                    }
                }
            }
            // 板块二：基础任务（一次性，按日期分组；逾期组沉底）
            if (basicTasks.isNotEmpty()) {
                item { NeuSectionTitle("基础任务", modifier = Modifier.padding(top = 10.dp)) }
            }
            if (todayTasks.isNotEmpty()) {
                item { NeuSectionTitle("今天 · ${today.monthValue}月${today.dayOfMonth}日", modifier = Modifier.padding(start = 4.dp)) }
                itemsIndexed(todayTasks, key = { _, t -> t.id }) { index, task ->
                    Box(Modifier.animateItem(fadeInSpec = tween(340, delayMillis = (index * 45).coerceAtMost(320)))) {
                        TaskRow(task, onToggle = { vm.toggle(task) }, onLongPress = { editingTask = task; showTaskSheet = true })
                    }
                }
            }
            if (tomorrowTasks.isNotEmpty()) {
                item { NeuSectionTitle("明天", modifier = Modifier.padding(start = 4.dp)) }
                itemsIndexed(tomorrowTasks, key = { _, t -> t.id }) { index, task ->
                    Box(Modifier.animateItem(fadeInSpec = tween(340, delayMillis = (index * 45).coerceAtMost(320)))) {
                        TaskRow(task, onToggle = { vm.toggle(task) }, onLongPress = { editingTask = task; showTaskSheet = true })
                    }
                }
            }
            if (laterTasks.isNotEmpty()) {
                item { NeuSectionTitle("以后", modifier = Modifier.padding(start = 4.dp)) }
                itemsIndexed(laterTasks, key = { _, t -> t.id }) { index, task ->
                    Box(Modifier.animateItem(fadeInSpec = tween(340, delayMillis = (index * 45).coerceAtMost(320)))) {
                        TaskRow(task, onToggle = { vm.toggle(task) }, onLongPress = { editingTask = task; showTaskSheet = true })
                    }
                }
            }
            // 逾期组置底：红字分组头 + 卡片红色左边线
            if (overdue.isNotEmpty()) {
                item { NeuSectionTitle("已逾期", accent = true, modifier = Modifier.padding(start = 4.dp)) }
                itemsIndexed(overdue, key = { _, t -> t.id }) { index, task ->
                    Box(Modifier.animateItem(fadeInSpec = tween(340, delayMillis = (index * 45).coerceAtMost(320)))) {
                        TaskRow(task, onToggle = { vm.toggle(task) }, onLongPress = { editingTask = task; showTaskSheet = true })
                    }
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }

    if (showTaskSheet) {
        TaskSheet(
            task = editingTask,
            onSaved = { item ->
                if (editingTask == null) {
                    vm.addTask(item.title, item.note, item.dueAt, item.remindAt, item.repeatDaily)
                } else {
                    vm.updateTask(item)
                }
                showTaskSheet = false
            },
            onDelete = editingTask?.let { task ->
                {
                    showTaskSheet = false
                    deleteTarget = task
                }
            },
            onDismiss = { showTaskSheet = false },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = Color.White,
            title = { Text("删除任务", color = TextPrimary) },
            text = { Text("确定删除「${target.title}」吗？", color = TextPrimary) },
            confirmButton = {
                TextButton(onClick = { vm.delete(target); deleteTarget = null }) {
                    Text("删除", color = NeuDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("取消", color = MutedText)
                }
            },
        )
    }
}

/** 空态：虚线选中圆（Figma 编辑器手柄引用）+ 提示 */
@Composable
private fun EmptyTasksHint() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .drawBehind {
                    drawCircle(
                        color = Color.Black,
                        style = Stroke(
                            width = 1.6.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f)),
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(MIcons.Checklist, contentDescription = null, tint = MutedText, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "暂无任务",
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            color = MutedText,
        )
    }
}

/**
 * 新建/编辑共用弹层：内容 / 备注 / 日期（今天·明天·自选）/ 提醒时间（可选）。
 * 编辑模式（task != null）额外提供「删除任务」入口（外部走确认对话框）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskSheet(
    task: TaskItem?,
    onSaved: (TaskItem) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var title by remember { mutableStateOf(task?.title ?: "") }
    var note by remember { mutableStateOf(task?.note ?: "") }
    var repeatDaily by remember { mutableStateOf(task?.repeatDaily ?: false) }
    var dueDate by remember { mutableStateOf(task?.let { dueDateOf(it) } ?: LocalDate.now()) }
    var remindTime by remember {
        mutableStateOf(task?.remindAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() })
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    fun commit() {
        // 每日任务：提醒只取时刻，排下一次出现（今天已过则明天）；基础任务：绑定期限日
        val remindAt = remindTime?.let { time ->
            val anchor = if (repeatDaily) LocalDate.now() else dueDate
            var at = LocalDateTime.of(anchor, time)
                .atZone(zone).toInstant().toEpochMilli()
            if (at <= System.currentTimeMillis()) {
                at = LocalDateTime.of(anchor.plusDays(1), time)
                    .atZone(zone).toInstant().toEpochMilli()
            }
            at
        }
        onSaved(
            TaskItem(
                id = task?.id ?: 0,
                title = title.trim(),
                note = note.trim(),
                dueAt = if (repeatDaily) 0L else startOfDayMs(dueDate),
                remindAt = remindAt,
                isDone = task?.isDone ?: false,
                createdBy = task?.createdBy ?: "user",
                repeatDaily = repeatDaily,
                completionDate = task?.completionDate ?: 0L,
                createdAt = task?.createdAt ?: System.currentTimeMillis(),
            )
        )
    }

    // Android 13+ 需要运行时通知权限：授权回调里再真正保存
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { commit() }

    NeuSheetScaffold(title = if (task == null) "新建任务" else "编辑任务", onDismiss = onDismiss) {
        Spacer(Modifier.height(16.dp))
        NeuTextField(
            value = title,
            onValueChange = { title = it },
            label = "任务内容",
            placeholder = "例：23:00 前放下手机",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = note,
            onValueChange = { note = it },
            label = "备注（可选）",
            placeholder = "例：打卡后发消息给阿布",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        // 每日重复：开启后任务进入「每日任务」板块（无日期，每天重置）
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("每日重复", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
            com.daoshi.app.ui.common.NeuSwitch(checked = repeatDaily, onToggle = { repeatDaily = it })
        }
        if (!repeatDaily) {
            Spacer(Modifier.height(16.dp))
            com.daoshi.app.ui.common.NeuMuted(text = "日期")
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                NeuChip("今天", selected = dueDate == LocalDate.now()) { dueDate = LocalDate.now() }
                NeuChip("明天", selected = dueDate == LocalDate.now().plusDays(1)) {
                    dueDate = LocalDate.now().plusDays(1)
                }
                NeuChip(
                    text = if (dueDate == LocalDate.now() || dueDate == LocalDate.now().plusDays(1))
                        "选日期…" else "${dueDate.monthValue}月${dueDate.dayOfMonth}日",
                    selected = dueDate != LocalDate.now() && dueDate != LocalDate.now().plusDays(1),
                ) { showDatePicker = true }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { showTimePicker = true }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("提醒", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
            Text(
                remindTime?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "不提醒",
                fontSize = 14.sp,
                color = if (remindTime != null) NeuAccent else MutedText,
            )
            if (remindTime != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "×",
                    fontSize = 17.sp,
                    color = MutedText,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { remindTime = null },
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            NeuButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            NeuButton(
                text = if (task == null) "添加" else "保存",
                onClick = {
                    if (title.isBlank()) return@NeuButton
                    val needsPermission = remindTime != null && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) {
                        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        commit()
                    }
                },
                kind = NeuButtonKind.Primary,
                enabled = title.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
        if (onDelete != null) {
            Spacer(Modifier.height(12.dp))
            NeuTextButton("删除任务", onClick = onDelete, color = NeuDanger)
        }
        Spacer(Modifier.height(6.dp))
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = startOfDayMs(dueDate))
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            // Figma 风：白底直角面板 + 黑色选中/边框，去 Material 默认紫蓝
            shape = RoundedCornerShape(22.dp),
            tonalElevation = 0.dp,
            colors = DatePickerDefaults.colors(containerColor = Color.White),
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        // DatePicker 返回 UTC 当日 0 点，按 UTC 还原为所选日期
                        dueDate = Instant.ofEpochMilli(ms).atZone(ZoneId.of("UTC")).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("确定", color = NeuAccent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消", color = MutedText) }
            },
        ) {
            DatePicker(
                state = pickerState,
                title = null,
                headline = null,
                showModeToggle = false,
                colors = DatePickerDefaults.colors(
                    containerColor = Color.White,
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
                    dividerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f),
                ),
            )
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = remindTime?.hour ?: 22,
            initialMinute = remindTime?.minute ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(22.dp),
            title = { Text("提醒时间", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                TimePicker(
                    state = timeState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Color.White,
                        selectorColor = NeuAccent,
                        periodSelectorBorderColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.08f),
                        timeSelectorSelectedContainerColor = NeuAccent,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    remindTime = LocalTime.of(timeState.hour, timeState.minute)
                    showTimePicker = false
                }) { Text("确定", color = NeuAccent) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("取消", color = MutedText) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TaskRow(task: TaskItem, onToggle: () -> Unit, onLongPress: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val toggleWithHaptic = {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        onToggle()
    }
    val done = task.isEffectivelyDone()
    val isOverdue = !task.repeatDaily && !done && dueDateOf(task) < LocalDate.now()
    val tag = when {
        task.repeatDaily -> if (done) "已完成" else "每日"
        done -> "已完成"
        isOverdue -> "已逾期"
        task.createdBy == "ai" -> "导师安排"
        else -> "自建"
    }
    val tagColor = when (tag) {
        "已逾期" -> NeuDanger
        "导师安排" -> NeuAccent
        else -> MutedText
    }
    NeuCard(
        cornerRadius = 20.dp,
        contentPadding = 13.dp,
        modifier = Modifier
            .fillMaxWidth()
            // 逾期未完成：卡片左缘红色边线（clip 内层绘制，跟随圆角）
            .then(
                if (isOverdue) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = NeuDanger,
                            size = androidx.compose.ui.geometry.Size(2.5.dp.toPx(), size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            Modifier.combinedClickable(onClick = toggleWithHaptic, onLongClick = onLongPress),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeuCheckCircle(checked = done, onToggle = toggleWithHaptic)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    fontSize = 15.sp,
                    color = if (done) FaintText else TextPrimary,
                    textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                )
                task.note.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 12.sp, color = MutedText, maxLines = 1)
                }
            }
            Spacer(Modifier.width(8.dp))
            TaskTag(tag, tagColor)
        }
    }
}

/** 任务标签：凹陷小胶囊（逾期红字 / 导师安排黑字加粗，其余灰字） */
@Composable
private fun TaskTag(text: String, textColor: Color) {
    Box(
        Modifier.neuSurface(
            999.dp,
            inset = true,
        ).padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = textColor,
            fontWeight = if (textColor != MutedText) FontWeight_SemiBold else null,
        )
    }
}

private val FontWeight_SemiBold = androidx.compose.ui.text.font.FontWeight.SemiBold
