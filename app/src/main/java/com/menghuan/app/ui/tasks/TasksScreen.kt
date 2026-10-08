package com.menghuan.app.ui.tasks

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.draw.scale
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
import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.data.model.isEffectivelyDone
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuCard
import com.menghuan.app.ui.common.NeuCheckCircle
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuMenu
import com.menghuan.app.ui.common.NeuMenuItem
import com.menghuan.app.ui.common.NeuSectionTitle
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.NeuPressSpec
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface

/**
 * 任务清单页：Room 真数据 + 双板块（每日任务 / 基础任务）+ 勾选打卡。
 * 新建任务走右上角 ＋ → 底部弹层（可开「每日重复」）；**长按任务行 = 震一下 + 弹出菜单**
 * （编辑 / 删除，锚在行右下角，与会话列表那几处同形状）；点「删除」直接删、不再叠确认框；
 * 设提醒时申请通知权限。
 */
private fun dueDateOf(task: TaskItem): LocalDate =
    if (task.dueAt == 0L) LocalDate.now()
    else Instant.ofEpochMilli(task.dueAt).atZone(ZoneId.systemDefault()).toLocalDate()

private fun startOfDayMs(date: LocalDate): Long =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** 任务行跨板块移动（勾选/改日期）时的弹簧重排（NeuSnappySpring 参数） */
private val TaskPlacementSpring = spring<androidx.compose.ui.unit.IntOffset>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMedium,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(vm: TasksViewModel = viewModel(factory = TasksViewModel.Factory)) {
    val tasks by vm.tasks.collectAsState()
    val hideDone by vm.hideDone.collectAsState()
    var showTaskSheet by remember { mutableStateOf(false) }
    var showAgentSheet by remember { mutableStateOf(false) }
    var editingTask by remember { mutableStateOf<TaskItem?>(null) }

    val today = LocalDate.now()
    val tomorrow = today.plusDays(1)
    // 「隐藏已完成」筛选：勾掉的（含当日已完成的每日任务）全部退出各分组
    fun List<TaskItem>.dropDone() = if (hideDone) filter { !it.isEffectivelyDone() } else this
    // 每日任务板块：无日期概念，当天未完成的排前面
    val dailyTasks = tasks.filter { it.repeatDaily }.sortedBy { it.isEffectivelyDone() }.dropDone()
    // 基础任务板块：一次性，按日期分组（现有逻辑）
    val basicTasks = tasks.filter { !it.repeatDaily }
    val overdue = basicTasks.filter { dueDateOf(it) < today }.dropDone()
    val todayTasks = basicTasks.filter { dueDateOf(it) == today }.dropDone()
    val tomorrowTasks = basicTasks.filter { dueDateOf(it) == tomorrow }.dropDone()
    val laterTasks = basicTasks.filter { dueDateOf(it) > tomorrow }.dropDone()
    // 「基础任务」分组头随筛选联动：四个子分组全空就不再出现
    val basicVisible = todayTasks.isNotEmpty() || tomorrowTasks.isNotEmpty() ||
        laterTasks.isNotEmpty() || overdue.isNotEmpty()
    val visibleAny = dailyTasks.isNotEmpty() || basicVisible

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NeuTopBar(
            title = "任务",
            // 三颗按钮（隐藏已完成 / Agent / 新建）超出默认槽宽，显式加宽避免「＋」被裁掉
            actionsWidth = 150.dp,
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 隐藏已完成：右上角开关按钮（选中 = 黑底白图标）
                    NeuCircleButton(
                        onClick = { vm.toggleHideDone() },
                        size = 38.dp,
                        accent = hideDone,
                    ) {
                        Icon(
                            MIcons.EyeOff,
                            contentDescription = "隐藏已完成",
                            tint = if (hideDone) AccentInk else TextPrimary,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    NeuCircleButton(
                        onClick = { showAgentSheet = true },
                        size = 38.dp,
                    ) {
                        Icon(MIcons.Play, contentDescription = "自动执行", tint = TextPrimary, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    NeuCircleButton(
                        onClick = { editingTask = null; showTaskSheet = true },
                        size = 38.dp,
                    ) {
                        Icon(MIcons.Plus, contentDescription = "新建任务", tint = TextPrimary, modifier = Modifier.size(19.dp))
                    }
                }
            },
        )

        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyTasksHint()
            }
            return@Column
        }

        if (!visibleAny) {
            // 筛选后无任何分组有内容：事实短语空态
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("全部完成", fontSize = 14.sp, color = MutedText)
            }
            return@Column
        }

        // 平板宽版式：两列网格（分组头跨列）；手机保持单列
        if (com.menghuan.app.ui.common.isExpandedWindow()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (dailyTasks.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        NeuSectionTitle("每日任务", modifier = Modifier.padding(top = 10.dp))
                    }
                    itemsIndexed(dailyTasks, key = { _, t -> t.id }) { index, task ->
                        Box(Modifier.animateItem(fadeInSpec = tween(200, delayMillis = (index * 16).coerceAtMost(120)), placementSpec = TaskPlacementSpring)) {
                            TaskRow(task, onToggle = { vm.toggle(task) }, onEdit = { editingTask = it; showTaskSheet = true }, onDelete = { vm.delete(task) })
                        }
                    }
                }
                if (basicVisible) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        NeuSectionTitle("基础任务", modifier = Modifier.padding(top = 10.dp))
                    }
                }
                if (todayTasks.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        NeuSectionTitle("今天 · ${today.monthValue}月${today.dayOfMonth}日", modifier = Modifier.padding(start = 4.dp))
                    }
                    itemsIndexed(todayTasks, key = { _, t -> t.id }) { index, task ->
                        Box(Modifier.animateItem(fadeInSpec = tween(200, delayMillis = (index * 16).coerceAtMost(120)), placementSpec = TaskPlacementSpring)) {
                            TaskRow(task, onToggle = { vm.toggle(task) }, onEdit = { editingTask = it; showTaskSheet = true }, onDelete = { vm.delete(task) })
                        }
                    }
                }
                if (tomorrowTasks.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        NeuSectionTitle("明天", modifier = Modifier.padding(start = 4.dp))
                    }
                    itemsIndexed(tomorrowTasks, key = { _, t -> t.id }) { index, task ->
                        Box(Modifier.animateItem(fadeInSpec = tween(200, delayMillis = (index * 16).coerceAtMost(120)), placementSpec = TaskPlacementSpring)) {
                            TaskRow(task, onToggle = { vm.toggle(task) }, onEdit = { editingTask = it; showTaskSheet = true }, onDelete = { vm.delete(task) })
                        }
                    }
                }
                if (laterTasks.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        NeuSectionTitle("以后", modifier = Modifier.padding(start = 4.dp))
                    }
                    itemsIndexed(laterTasks, key = { _, t -> t.id }) { index, task ->
                        Box(Modifier.animateItem(fadeInSpec = tween(200, delayMillis = (index * 16).coerceAtMost(120)), placementSpec = TaskPlacementSpring)) {
                            TaskRow(task, onToggle = { vm.toggle(task) }, onEdit = { editingTask = it; showTaskSheet = true }, onDelete = { vm.delete(task) })
                        }
                    }
                }
                // 逾期组置底：红字分组头 + 卡片红色左边线
                if (overdue.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        NeuSectionTitle("已逾期", accent = true, modifier = Modifier.padding(start = 4.dp))
                    }
                    itemsIndexed(overdue, key = { _, t -> t.id }) { index, task ->
                        Box(Modifier.animateItem(fadeInSpec = tween(200, delayMillis = (index * 16).coerceAtMost(120)), placementSpec = TaskPlacementSpring)) {
                            TaskRow(task, onToggle = { vm.toggle(task) }, onEdit = { editingTask = it; showTaskSheet = true }, onDelete = { vm.delete(task) })
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(18.dp)) }
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
                    Box(Modifier.animateItem(fadeInSpec = tween(200, delayMillis = (index * 16).coerceAtMost(120)), placementSpec = TaskPlacementSpring)) {
                        TaskRow(task, onToggle = { vm.toggle(task) }, onEdit = { editingTask = it; showTaskSheet = true }, onDelete = { vm.delete(task) })
                    }
                }
            }
            // 板块二：基础任务（一次性，按日期分组；逾期组沉底）
            if (basicVisible) {
                item { NeuSectionTitle("基础任务", modifier = Modifier.padding(top = 10.dp)) }
            }
            if (todayTasks.isNotEmpty()) {
                item { NeuSectionTitle("今天 · ${today.monthValue}月${today.dayOfMonth}日", modifier = Modifier.padding(start = 4.dp)) }
                itemsIndexed(todayTasks, key = { _, t -> t.id }) { index, task ->
                    Box(Modifier.animateItem(fadeInSpec = tween(200, delayMillis = (index * 16).coerceAtMost(120)), placementSpec = TaskPlacementSpring)) {
                        TaskRow(task, onToggle = { vm.toggle(task) }, onEdit = { editingTask = it; showTaskSheet = true }, onDelete = { vm.delete(task) })
                    }
                }
            }
            if (tomorrowTasks.isNotEmpty()) {
                item { NeuSectionTitle("明天", modifier = Modifier.padding(start = 4.dp)) }
                itemsIndexed(tomorrowTasks, key = { _, t -> t.id }) { index, task ->
                    Box(Modifier.animateItem(fadeInSpec = tween(200, delayMillis = (index * 16).coerceAtMost(120)), placementSpec = TaskPlacementSpring)) {
                        TaskRow(task, onToggle = { vm.toggle(task) }, onEdit = { editingTask = it; showTaskSheet = true }, onDelete = { vm.delete(task) })
                    }
                }
            }
            if (laterTasks.isNotEmpty()) {
                item { NeuSectionTitle("以后", modifier = Modifier.padding(start = 4.dp)) }
                itemsIndexed(laterTasks, key = { _, t -> t.id }) { index, task ->
                    Box(Modifier.animateItem(fadeInSpec = tween(200, delayMillis = (index * 16).coerceAtMost(120)), placementSpec = TaskPlacementSpring)) {
                        TaskRow(task, onToggle = { vm.toggle(task) }, onEdit = { editingTask = it; showTaskSheet = true }, onDelete = { vm.delete(task) })
                    }
                }
            }
            // 逾期组置底：红字分组头 + 卡片红色左边线
            if (overdue.isNotEmpty()) {
                item { NeuSectionTitle("已逾期", accent = true, modifier = Modifier.padding(start = 4.dp)) }
                itemsIndexed(overdue, key = { _, t -> t.id }) { index, task ->
                    Box(Modifier.animateItem(fadeInSpec = tween(200, delayMillis = (index * 16).coerceAtMost(120)), placementSpec = TaskPlacementSpring)) {
                        TaskRow(task, onToggle = { vm.toggle(task) }, onEdit = { editingTask = it; showTaskSheet = true }, onDelete = { vm.delete(task) })
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
                // 弹层里这颗「删除任务」也**直接删**，不再弹确认框：
                // 与长按菜单同一条口径（单条删不叠确认框）
                {
                    showTaskSheet = false
                    vm.delete(task)
                }
            },
            onDismiss = { showTaskSheet = false },
        )
    }

    if (showAgentSheet) {
        AgentSheet(vm = vm, onDismiss = { showAgentSheet = false })
    }
}

/** 空态：虚线选中圆（Figma 编辑器手柄引用）+ 提示 */
@Composable
private fun EmptyTasksHint() {
    val ink = NeuAccent
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .drawBehind {
                    drawCircle(
                        color = ink,
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
 * 编辑模式（task != null）额外提供「删除任务」入口，**点了直接删**——
 * 与长按菜单同一条口径（单条删不叠确认框，v0.62.0 起原来那层确认对话框已删）。
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
            com.menghuan.app.ui.common.NeuSwitch(checked = repeatDaily, onToggle = { repeatDaily = it })
        }
        if (!repeatDaily) {
            Spacer(Modifier.height(16.dp))
            com.menghuan.app.ui.common.NeuMuted(text = "日期")
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
        // 提醒：单一入口。未点击 = 不提醒；点击 = 弹时间选择器；已设后可用 × 清除。
        // 去掉 Material 默认 ripple（那个方形灰色框），保持面板整体的凹陷拟物观感。
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { showTimePicker = true }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("提醒", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
            // remindTime 是 delegated property（by mutableStateOf），Kotlin 不允许对它 smart cast，
            // 先绑到本地 val 再判空使用
            val remind = remindTime
            if (remind != null) {
                Text(
                    remind.format(DateTimeFormatter.ofPattern("HH:mm")),
                    fontSize = 14.sp,
                    color = NeuAccent,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "×",
                    fontSize = 17.sp,
                    color = MutedText,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) { remindTime = null },
                )
            } else {
                // 未设时用细箭头作可点提示，不再显示"不提醒"三个字（不点就是不提醒，语义自明）
                Text("›", fontSize = 18.sp, color = MutedText)
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
            colors = DatePickerDefaults.colors(containerColor = NeuSurface),
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

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = remindTime?.hour ?: 22,
            initialMinute = remindTime?.minute ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            containerColor = NeuSurface,
            shape = RoundedCornerShape(22.dp),
            title = { Text("提醒时间", color = TextPrimary, fontWeight = FontWeight.SemiBold) },
            text = {
                TimePicker(
                    state = timeState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = NeuSurface,
                        selectorColor = NeuAccent,
                        periodSelectorBorderColor = NeuHairline,
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
/**
 * 任务行：**整行点一下 = 打卡**，**长按 = 震一下并弹出菜单**（编辑 / 删除）。
 *
 * 菜单形状与会话列表 / 洞察 / 周报 / 记账本流水完全一致：锚在行的右下角。
 * ⚠️ 点「删除」就**直接删**，不再叠一层确认框——长按呼出菜单那一下本身就是确认
 * （2026-10-02 定下的规矩；原来是长按直接开编辑弹层、删除藏在弹层里还要再确认一次，
 * 两处口径和别的地方都不一样）。
 */
@Composable
private fun TaskRow(
    task: TaskItem,
    onToggle: () -> Unit,
    onEdit: (TaskItem) -> Unit,
    onDelete: (TaskItem) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val toggleWithHaptic = {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        onToggle()
    }
    val callMenu = {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        menuOpen = true
    }
    val done = task.isEffectivelyDone()
    val isOverdue = !task.repeatDaily && !done && dueDateOf(task) < LocalDate.now()
    val tag = when {
        task.repeatDaily -> if (done) "已完成" else "每日"
        done -> "已完成"
        isOverdue -> "已逾期"
        task.createdBy == "ai" -> "AI 建议"
        else -> "自建"
    }
    val tagColor = when (tag) {
        "已逾期" -> NeuDanger
        "AI 建议" -> NeuAccent
        else -> MutedText
    }
    Box(Modifier.fillMaxWidth()) {
        NeuCard(
            cornerRadius = 20.dp,
            contentPadding = 13.dp,
            modifier = Modifier
                .fillMaxWidth()
                // 逾期未完成：卡片左缘红色边线（clip 内层绘制，跟随圆角）
                .then(
                    if (isOverdue) {
                        val danger = NeuDanger
                        Modifier.drawBehind {
                            drawRoundRect(
                                color = danger,
                                size = androidx.compose.ui.geometry.Size(2.5.dp.toPx(), size.height),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            // v0.55.0：任务行补**按压缩放**。注释里写着"反馈靠卡片按压与勾选动画"，
            // 但按压那半个从来没做 —— 点一下只有勾选圈动，整行是"死"的。
            // 现在整行跟着按下缩到 0.975（与会话列表行同一手感），点哪都算数。
            val rowPress = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val rowScale by animateFloatAsState(
                targetValue = if (rowPress.collectIsPressedAsState().value) 0.975f else 1f,
                animationSpec = NeuPressSpec,
                label = "taskRowPress",
            )
            Row(
                Modifier
                    .scale(rowScale)
                    .combinedClickable(
                        interactionSource = rowPress,
                        indication = null, // 无涟漪：长按/点击反馈靠卡片按压与勾选动画
                        onClick = toggleWithHaptic,
                        onLongClick = callMenu,
                    ),
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
        // 菜单锚点：DropdownMenu 的 Popup 锚定它的**直接父节点**，
        // 所以必须把它包进一个贴在行右下角的 1dp Box（align/offset 直接加在菜单上无效）
        Box(Modifier.align(Alignment.BottomEnd).size(1.dp)) {
            // v0.63.0：换成全应用统一的紧凑菜单（13sp / 行高约 33dp）。
            // M3 的 DropdownMenuItem 最小行高写死 48dp、Modifier 压不动，
            // 用户点名"菜单太大、和字体大小不匹配"
            NeuMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                NeuMenuItem(
                    "编辑",
                    onClick = {
                        menuOpen = false
                        onEdit(task)
                    },
                )
                NeuMenuItem(
                    "删除",
                    danger = true,
                    onClick = {
                        menuOpen = false
                        onDelete(task)
                    },
                )
            }
        }
    }
}

/** 任务标签：凹陷小胶囊（逾期红字 / AI 建议黑字加粗，其余灰字） */
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
