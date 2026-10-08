package com.menghuan.app.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.ExtractionEngine
import com.menghuan.app.data.ProfileRepository
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.AiInsight
import com.menghuan.app.data.model.ProfileFact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 本周任务完成度（真实数据驱动完成度圆环） */
data class WeekProgress(val done: Int, val total: Int) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

/** 近 7 天每日完成任务数（习惯卡真数据：打卡热力） */
data class DayCount(val date: LocalDate, val done: Int)

/**
 * 本周回顾（v0.42.0）：**一张卡统一口径**，合并原先分裂的「打卡热力」与「完成度环」。
 *
 * 旧版两个指标口径不同（热力只算每日任务的 task_completions，完成度只算本周基础任务），
 * 同一张卡片上摆两个不同分母的数字，用户看不懂。新版统一为：
 * 「今天该做的事」= 每日任务 + 当天到期的基础任务（与任务页 / 每日总结通知同口径）。
 *
 * - [days] 近 7 天（旧→新）每天的「该做数 / 已完成数」——格子条与展开明细的数据源
 * - [done] / [total] 本周（周一起）合计，用于顶部进度条与百分数
 * - [streak] 连续「全清」天数（当天只要还有未完成就不算全清，今天未全清不断签，从昨天往回数）
 */
data class WeekReview(
    val days: List<DayReview>,
    val done: Int,
    val total: Int,
    val streak: Int,
) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

/** 单日回顾：日期 + 该做/已完成 + 当日任务清单（点格子展开明细用） */
data class DayReview(
    val date: LocalDate,
    val done: Int,
    val total: Int,
    val items: List<DayReviewItem>,
) {
    val isFull: Boolean get() = total > 0 && done == total
}

/** 单条任务在回顾里的展示态 */
data class DayReviewItem(val title: String, val done: Boolean)

/**
 * 本周回顾聚合（纯函数，JVM 单测）。
 * - 每日任务（repeatDaily）：只要创建于当天或更早就算「该做」，完成看 task_completions 当天有无记录
 * - 基础任务：dueAt 落在当天算「该做」（dueAt = 0 视为 today），完成看 isDone
 * items 里每日任务排在基础任务后面（先看一事一议的具体任务，再看习惯项）。
 */
fun buildWeekReview(
    tasks: List<com.menghuan.app.data.model.TaskItem>,
    completions: List<com.menghuan.app.data.model.TaskCompletion>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): WeekReview {
    val doneByDayAndTask = completions.map { it.day to it.taskId }.toSet()
    val days = (6 downTo 0).map { offset ->
        val date = today.minusDays(offset.toLong())
        val epochDay = date.toEpochDay()
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val basicItems = tasks.filter {
            !it.repeatDaily && (if (it.dueAt == 0L) date == today else it.dueAt >= dayStart && it.dueAt < dayEnd)
        }
        // 每日任务：当天之后创建的当天不算（否则补看历史会把未来的任务算进过去）
        val dailyItems = tasks.filter {
            it.repeatDaily && it.createdAt < dayEnd
        }
        val items = basicItems.map { DayReviewItem(it.title, it.isDone) } +
            dailyItems.map { DayReviewItem(it.title, (epochDay to it.id) in doneByDayAndTask) }
        val done = items.count { it.done }
        DayReview(date = date, done = done, total = items.size, items = items)
    }
    val monday = today.with(DayOfWeek.MONDAY)
    val weekDays = days.filter { !it.date.isBefore(monday) }
    return WeekReview(
        days = days,
        done = weekDays.sumOf { it.done },
        total = weekDays.sumOf { it.total },
        streak = weekReviewStreak(days),
    )
}

/** 连续「全清」天数：今天未全清不断签（从昨天往回数），对齐原 habitStreak 的宽恕规则 */
fun weekReviewStreak(days: List<DayReview>): Int {
    var streak = 0
    val today = days.lastOrNull() ?: return 0
    var i = if (today.total == 0 || !today.isFull) days.size - 2 else days.size - 1
    while (i >= 0) {
        val day = days[i]
        if (day.total == 0 || !day.isFull) break
        streak++
        i--
    }
    return streak
}

/**
 * 连续打卡天数（纯函数，JVM 单测）。
 * 宽恕规则与打卡 App 惯例一致：今天还没打卡不断签，从昨天往回数；
 * 当天有打卡则从今天起数。days 须旧→新。
 */
fun habitStreak(days: List<DayCount>): Int {
    var streak = 0
    val start = if (days.lastOrNull()?.done?.compareTo(0) == 0) days.size - 2 else days.size - 1
    var i = start
    while (i >= 0 && days[i].done > 0) {
        streak++
        i--
    }
    return streak
}

/**
 * 我的数据页 VM：
 * - 基础档案接 ProfileFact（只读展示，编辑入口在「我的」页）
 * - 习惯卡 = 近 7 天每日完成任务数（打卡热力）+ 连续打卡天数
 * - AI 洞察 = 提炼流水线产物（ai_insights 最新两条）；手动「提炼」调引擎全量补跑
 * - AI 周报 = 每周日 21:30 定时生成（WeeklyReportGenerator），本页提供手动「生成」与历史切换
 */
class DataViewModel(
    private val repo: ProfileRepository,
    private val db: AppDatabase,
    private val extractionEngine: ExtractionEngine,
    private val weeklyReportGenerator: com.menghuan.app.data.WeeklyReportGenerator,
    private val dailySuggestionGenerator: com.menghuan.app.data.DailySuggestionGenerator,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val facts: StateFlow<List<ProfileFact>> = repo.observeFacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 本周目标完成度（圆环）：只统计基础任务（一次性、带日期），每日任务进打卡热力 */
    val weekProgress: StateFlow<WeekProgress> = db.taskDao().observeAll()
        .map { tasks ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val monday = today.with(DayOfWeek.MONDAY)
            val weekStart = monday.atStartOfDay(zone).toInstant().toEpochMilli()
            val weekEnd = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val basic = tasks.filter { !it.repeatDaily }
            // dueAt = 0 视为今天
            fun dueOf(task: com.menghuan.app.data.model.TaskItem): Long =
                if (task.dueAt == 0L) todayStart else task.dueAt
            val inWeek = basic.filter { val due = dueOf(it); due >= weekStart && due < weekEnd }
            WeekProgress(inWeek.count { it.isDone }, inWeek.size)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeekProgress(0, 0))

    /**
     * 近 7 天（含今天）每日完成任务数，旧→新。
     * 只统计每日任务（repeatDaily）的打卡记录（task_completions），基础任务不计入热力。
     */
    val weekDaily: StateFlow<List<DayCount>> = combine(
        db.taskCompletionDao().observeAll(),
        db.taskDao().observeAll(),
    ) { completions, tasks ->
        val dailyIds = tasks.filter { it.repeatDaily }.map { it.id }.toSet()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        (6 downTo 0).map { offset ->
            val day = today.minusDays(offset.toLong())
            DayCount(day, completions.count { it.day == day.toEpochDay() && it.taskId in dailyIds })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** AI 洞察真数据源（提炼引擎产出），最新两条 */
    val insights: StateFlow<List<AiInsight>> = db.aiInsightDao().observeLatest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ===== v0.42.0 本周回顾（统一口径，置顶卡） =====

    /** 本周回顾：近 7 天每日该做/已完成 + 本周合计 + 连续全清天数 */
    val weekReview: StateFlow<WeekReview> = combine(
        db.taskDao().observeAll(),
        db.taskCompletionDao().observeAll(),
    ) { tasks, completions ->
        buildWeekReview(tasks, completions, LocalDate.now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeekReview(emptyList(), 0, 0, 0))

    // ===== v0.42.0 每日建议（独立生成链路） =====

    /** 今日建议（无 = null，卡片显示空态） */
    val todaySuggestion: StateFlow<com.menghuan.app.data.model.DailySuggestion?> =
        db.dailySuggestionDao().observeByDay(LocalDate.now().toEpochDay())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 历史建议（详情页列表用） */
    val suggestions: StateFlow<List<com.menghuan.app.data.model.DailySuggestion>> =
        db.dailySuggestionDao().observeRecent()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _generatingSuggestion = MutableStateFlow(false)
    val generatingSuggestion: StateFlow<Boolean> = _generatingSuggestion.asStateFlow()

    private val _suggestionMessage = MutableStateFlow<String?>(null)
    val suggestionMessage: StateFlow<String?> = _suggestionMessage.asStateFlow()

    fun clearSuggestionMessage() {
        _suggestionMessage.value = null
    }

    private val _suggestionApiReady = MutableStateFlow(true)
    val suggestionApiReady: StateFlow<Boolean> = _suggestionApiReady.asStateFlow()

    /** 手动「重新生成」今天的建议（覆盖当天旧建议） */
    fun generateSuggestion() {
        if (_generatingSuggestion.value) return
        viewModelScope.launch {
            _generatingSuggestion.value = true
            val today = LocalDate.now()
            val result = runCatching {
                db.dailySuggestionDao().deleteByDay(today.toEpochDay())
                dailySuggestionGenerator.generateForDay(today)
            }.getOrNull()
            _generatingSuggestion.value = false
            _suggestionMessage.value = when {
                result != null -> "今天的建议已更新"
                apiReady.value -> "生成失败，稍后再试"
                else -> "未配置 API，建议不可用"
            }
        }
    }

    /** 长按转任务：写入 tasks（createdBy = ai）+ 标记 converted，避免重复转 */
    fun convertSuggestionToTask(suggestion: com.menghuan.app.data.model.DailySuggestion) {
        viewModelScope.launch {
            runCatching {
                db.taskDao().upsert(
                    com.menghuan.app.data.model.TaskItem(
                        title = suggestion.title,
                        note = suggestion.detail,
                        dueAt = LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                            .toInstant().toEpochMilli(),
                        createdBy = "ai",
                    )
                )
                db.dailySuggestionDao().setConverted(suggestion.day, true)
            }
            _suggestionMessage.value = "已加入今天的任务"
        }
    }

    // ===== v0.42.0 记账本 =====

    val ledgerEntries: StateFlow<List<com.menghuan.app.data.model.LedgerEntry>> =
        db.ledgerDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 现金余额（分）= 全部流水累加 */
    val cashCents: StateFlow<Long> = ledgerEntries
        .map { com.menghuan.app.data.model.LedgerMath.balanceCents(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /**
     * 统计档位（v0.42.1）：月 / 周两档，由记账本页的「月度统计」标题点击切换。
     * 周口径 = 周一为一周起点，与数据页「本周回顾条」完全一致。
     */
    enum class LedgerStatsScope { MONTH, WEEK }

    private val _ledgerScope = MutableStateFlow(LedgerStatsScope.MONTH)
    val ledgerScope: StateFlow<LedgerStatsScope> = _ledgerScope.asStateFlow()

    /** 月度统计选中的年月（默认当月） */
    private val _ledgerMonth = MutableStateFlow(java.time.YearMonth.now())
    val ledgerMonth: StateFlow<java.time.YearMonth> = _ledgerMonth.asStateFlow()

    /** 周度统计选中的周一（默认本周一） */
    private val _ledgerWeekStart = MutableStateFlow(
        com.menghuan.app.data.model.LedgerMath.weekStartOf(java.time.LocalDate.now())
    )
    val ledgerWeekStart: StateFlow<java.time.LocalDate> = _ledgerWeekStart.asStateFlow()

    /** 切换统计档位；切档时把锚点归位到今天，避免"看着 3 月却切到不知哪一周"的困惑 */
    fun setLedgerScope(scope: LedgerStatsScope) {
        if (_ledgerScope.value == scope) return
        _ledgerScope.value = scope
        _ledgerMonth.value = java.time.YearMonth.now()
        _ledgerWeekStart.value = com.menghuan.app.data.model.LedgerMath.weekStartOf(java.time.LocalDate.now())
    }

    /** 按当前档位前后翻页：月 → 加减月，周 → 加减周（周一起点，语义比加 7 天清楚） */
    fun shiftLedgerPeriod(delta: Long) {
        when (_ledgerScope.value) {
            LedgerStatsScope.MONTH -> _ledgerMonth.value = _ledgerMonth.value.plusMonths(delta)
            LedgerStatsScope.WEEK -> _ledgerWeekStart.value =
                com.menghuan.app.data.model.LedgerMath.shiftWeek(_ledgerWeekStart.value, delta)
        }
    }

    /**
     * 当前档位的收支合计 + 每根柱子的净额。
     * 月档：key = 当月第几天（1..31）；周档：key = 周内第几天（周一=1 … 周日=7）。
     */
    val periodStats: StateFlow<Pair<com.menghuan.app.data.model.LedgerMath.MonthTotals, Map<Int, Long>>> =
        combine(ledgerEntries, _ledgerMonth, _ledgerWeekStart, _ledgerScope) { entries, month, weekStart, scope ->
            when (scope) {
                LedgerStatsScope.MONTH ->
                    com.menghuan.app.data.model.LedgerMath.monthTotals(entries, month.year, month.monthValue) to
                        com.menghuan.app.data.model.LedgerMath.dailyNetCents(entries, month.year, month.monthValue)

                LedgerStatsScope.WEEK ->
                    com.menghuan.app.data.model.LedgerMath.weekTotals(entries, weekStart) to
                        com.menghuan.app.data.model.LedgerMath.weeklyNetCents(entries, weekStart)
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            com.menghuan.app.data.model.LedgerMath.MonthTotals(0, 0) to emptyMap(),
        )

    private val _ledgerMessage = MutableStateFlow<String?>(null)
    val ledgerMessage: StateFlow<String?> = _ledgerMessage.asStateFlow()

    fun clearLedgerMessage() {
        _ledgerMessage.value = null
    }

    /** 记一笔（手动 / 语音 / 识图共用入口） */
    fun addLedgerEntry(
        isIncome: Boolean,
        amountCents: Long,
        note: String,
        source: String,
        occurredAt: Long = System.currentTimeMillis(),
    ) {
        viewModelScope.launch {
            runCatching {
                db.ledgerDao().upsert(
                    com.menghuan.app.data.model.LedgerEntry(
                        isIncome = isIncome,
                        amountCents = amountCents,
                        note = note,
                        source = source,
                        occurredAt = occurredAt,
                    )
                )
            }
            _ledgerMessage.value = if (isIncome) {
                "已记收入 ${com.menghuan.app.data.model.LedgerMath.formatCents(amountCents)} 元"
            } else {
                "已记支出 ${com.menghuan.app.data.model.LedgerMath.formatCents(amountCents)} 元"
            }
        }
    }

    /**
     * 改一笔（v0.63.0：流水长按 → 编辑）。
     *
     * 只换方向/金额/备注，**发生时间与录入来源保住不动**——写错的通常是数字，
     * 不是"这笔什么时候发生的"；来源（手动/语音/识图）是留痕，改它等于抹掉证据。
     */
    fun editLedgerEntry(
        entry: com.menghuan.app.data.model.LedgerEntry,
        isIncome: Boolean,
        amountCents: Long,
        note: String,
    ) {
        viewModelScope.launch {
            runCatching {
                db.ledgerDao().upsert(entry.copy(isIncome = isIncome, amountCents = amountCents, note = note))
            }
            _ledgerMessage.value = "这笔已更新"
        }
    }

    fun deleteLedgerEntry(id: Long) {
        viewModelScope.launch {
            runCatching { db.ledgerDao().deleteById(id) }
            _ledgerMessage.value = "已删除该笔记录"
        }
    }

    // ===== v0.62.0 备忘本 =====

    /** 全部备忘（置顶优先，其次新建优先）——排序口径只在 MemoMath 里定一次 */
    val memos: StateFlow<List<com.menghuan.app.data.model.MemoEntry>> =
        db.memoDao().observeAll()
            .map { com.menghuan.app.data.model.MemoMath.sorted(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _memoMessage = MutableStateFlow<String?>(null)
    val memoMessage: StateFlow<String?> = _memoMessage.asStateFlow()

    /** 人设名单：备忘「发给 AI」时选给谁（数据页卡片和全屏页共用这一份观察） */
    val personas: StateFlow<List<com.menghuan.app.data.model.Persona>> =
        db.personaDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearMemoMessage() {
        _memoMessage.value = null
    }

    /**
     * 新增 / 编辑共用。v0.63.0 起只剩正文与置顶：时间就是 `createdAt`（自动标注），
     * 没有可填字段了，所以也不再有弹层表单。
     * id=0 由 Room 自动生成；编辑时保住原 `createdAt`，别让改一次文字就把时间刷到最新。
     */
    fun saveMemo(id: Long, content: String, pinned: Boolean) {
        val text = content.trim()
        if (text.isEmpty()) {
            _memoMessage.value = "内容不能为空"
            return
        }
        viewModelScope.launch {
            val existing = if (id > 0L) db.memoDao().getById(id) else null
            runCatching {
                db.memoDao().upsert(
                    com.menghuan.app.data.model.MemoEntry(
                        id = if (id > 0L) id else 0L,
                        content = text,
                        pinned = pinned,
                        createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    )
                )
            }
            _memoMessage.value = if (existing == null) "已记下" else "备忘已更新"
        }
    }

    /** 卡内那一行「＋ 记点什么…」：只要正文 */
    fun quickAddMemo(content: String) {
        saveMemo(id = 0L, content = content, pinned = false)
    }

    fun toggleMemoPin(id: Long, pinned: Boolean) {
        viewModelScope.launch {
            runCatching { db.memoDao().setPinned(id, pinned) }
            _memoMessage.value = if (pinned) "已置顶" else "已取消置顶"
        }
    }

    fun deleteMemo(id: Long) {
        viewModelScope.launch {
            runCatching { db.memoDao().deleteById(id) }
        }
    }

    /**
     * 发给 AI：只在这一次把这条备忘带进 TA 的输入框（填草稿、不自动发）。
     *
     * 刻意**不做**"备忘常驻注入 system prompt"——那会让 AI 满嘴备忘，
     * 和当年档案注入被删是同一个毛病；用户点哪条、发给谁，才带哪条过去。
     * 发法沿用会话草稿：写 [com.menghuan.app.ui.chat.ChatDrafts]，
     * 导航到 `chat/{personaId}` 时 ChatViewModel 用它当输入框初值（见其 _uiState 初始化）。
     * 原草稿非空时**接在后面**，不顶掉用户还没发出去的话。
     */
    fun sendMemoToPersona(personaId: Long, memo: com.menghuan.app.data.model.MemoEntry) {
        val text = com.menghuan.app.data.model.MemoMath.toChatText(memo)
        val draft = com.menghuan.app.ui.chat.ChatDrafts.get(personaId)
        com.menghuan.app.ui.chat.ChatDrafts.set(
            personaId,
            if (draft.isBlank()) text else draft.trimEnd() + "\n" + text,
        )
        _memoMessage.value = "已放进 TA 的输入框"
    }

    private val _extracting = MutableStateFlow(false)
    val extracting: StateFlow<Boolean> = _extracting.asStateFlow()

    /** 手动提炼的结果反馈（展示数秒后自动清除） */
    private val _extractMessage = MutableStateFlow<String?>(null)
    val extractMessage: StateFlow<String?> = _extractMessage.asStateFlow()

    fun clearExtractMessage() {
        _extractMessage.value = null
    }

    /** 洞察卡长按删除（v0.36.5） */
    fun deleteInsight(id: Long) = viewModelScope.launch {
        runCatching { db.aiInsightDao().deleteById(id) }
    }

    /** 周报历史长按删除（v0.36.5） */
    fun deleteWeeklyReport(id: Long) = viewModelScope.launch {
        runCatching { db.aiWeeklyReportDao().deleteById(id) }
    }

    /** 手动「提炼」：所有会话立即补跑一次（仍只送增量）；期间按钮转"提炼中…"，完成后给统计反馈 */
    fun refreshInsights() {
        if (_extracting.value) return
        viewModelScope.launch {
            _extracting.value = true
            val summary = runCatching { extractionEngine.extractAll() }.getOrNull()
            _extracting.value = false
            _extractMessage.value = when {
                summary == null -> "提炼失败，稍后再试"
                summary.skippedNoApi && !summary.hasChanges -> "未配置 API，提炼不可用"
                !summary.hasChanges -> "提炼完成：暂无新的洞察与档案更新"
                else -> buildString {
                    append("提炼完成：")
                    val parts = mutableListOf<String>()
                    if (summary.newInsights > 0) parts.add("新洞察 ${summary.newInsights} 条")
                    if (summary.newFacts > 0) parts.add("新档案 ${summary.newFacts} 项")
                    if (summary.updatedFacts > 0) parts.add("更新档案 ${summary.updatedFacts} 项")
                    if (summary.newSuggestions > 0) parts.add("新任务建议 ${summary.newSuggestions} 条")
                    append(parts.joinToString(" · "))
                }
            }
        }
    }

    /** API 未配置时隐藏提炼入口 */
    val apiReady: StateFlow<Boolean> = settingsRepository.apiConfig
        .map { it.isComplete }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ===== AI 周报（每周日 21:30 自动生成；此处提供展示 / 手动生成 / 历史切换） =====

    /** 全部周报（新→旧）；空表时数据页整个隐藏该板块 */
    val weeklyReports: StateFlow<List<com.menghuan.app.data.model.AiWeeklyReport>> =
        db.aiWeeklyReportDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 历史切换选中的周报 id；null = 展示最新一期 */
    private val _selectedReportId = MutableStateFlow<Long?>(null)
    val selectedReportId: StateFlow<Long?> = _selectedReportId.asStateFlow()

    fun selectReport(id: Long) {
        _selectedReportId.value = id
    }

    private val _generatingReport = MutableStateFlow(false)
    val generatingReport: StateFlow<Boolean> = _generatingReport.asStateFlow()

    /** 周报生成结果反馈（展示数秒后自动清除） */
    private val _reportMessage = MutableStateFlow<String?>(null)
    val reportMessage: StateFlow<String?> = _reportMessage.asStateFlow()

    fun clearReportMessage() {
        _reportMessage.value = null
    }

    /** 手动「生成」：生成本周（周一起）周报，同周重复生成 = 覆盖旧报告；成功后自动选中新报告 */
    fun generateWeeklyReport() {
        if (_generatingReport.value) return
        viewModelScope.launch {
            _generatingReport.value = true
            val zone = ZoneId.systemDefault()
            val monday = LocalDate.now(zone).with(DayOfWeek.MONDAY)
            val report = runCatching { weeklyReportGenerator.generateForWeek(monday, zone) }.getOrNull()
            _generatingReport.value = false
            _reportMessage.value = when {
                report != null -> {
                    _selectedReportId.value = report.id
                    "周报已更新"
                }
                apiReady.value -> "生成失败，稍后再试"
                else -> "未配置 API，周报不可用"
            }
        }
    }

    /** 周报分享（卡长按 / 历史行长按共用）：写 .md 文件经 FileProvider 分享 */
    fun shareWeeklyReport(context: android.content.Context, report: com.menghuan.app.data.model.AiWeeklyReport) {
        viewModelScope.launch {
            val start = LocalDate.ofEpochDay(report.weekStart)
            runCatching {
                com.menghuan.app.data.ShareFiles.share(
                    context = context,
                    fileName = "AI周报-${start.monthValue}月${start.dayOfMonth}日.md",
                    content = com.menghuan.app.data.WeeklyReportMath.shareText(report),
                    mime = "text/markdown",
                    chooserTitle = "分享周报",
                )
            }
        }
    }

    

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MenghuanApp
                DataViewModel(
                    app.profileRepository,
                    app.db,
                    app.extractionEngine,
                    app.weeklyReportGenerator,
                    app.dailySuggestionGenerator,
                    app.settingsRepository,
                )
            }
        }
    }
}
