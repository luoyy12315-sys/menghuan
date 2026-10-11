package com.menghuan.app.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.CallVisualStore
import com.menghuan.app.data.CallVisualType
import com.menghuan.app.data.ExtractionEngine
import com.menghuan.app.data.ProfileRepository
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.AiInsight
import com.menghuan.app.data.model.Persona
import com.menghuan.app.data.model.ProfileFact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 近 7 天每日完成任务数。
 *
 * ⚠️ v0.97.0（审查 B41）：这里原来的 `WeekProgress`（完成度圆环口径）与紧挨着的
 * `weekProgress` / `weekDaily` 两个 StateFlow 都已**删除** —— 页面自 v0.42.0 起只用
 * `weekReview` 统一口径，那三个留在文件里是「同页两套周口径」的隐患：
 * 哪天有人顺手用了 `weekProgress.done`，同一张卡上就会出现两个对不上的分子。
 *
 * `DayCount` 与 [habitStreak] 保留：它们是 [weekReviewStreak] 之前的实现，
 * 由 `HabitStreakTest` 逐例钉着，作为"宽恕规则"的行为参照，删了会连带删掉那批断言。
 */
data class DayCount(val date: LocalDate, val done: Int)

/**
 * 一个角色 + 它有没有可用的 Live2D 形象（角色空间入口卡的选人列表用）。
 *
 * [hasLive2d] 为 false 的项在弹层里**仍然列出来**，只是标一行"未导入模型包"且不可点 ——
 * 用户看不到某个角色会以为 App 漏了 TA，而"为什么进不去"正是这条入口要回答的问题。
 */
data class PersonaSpaceEntry(val persona: Persona, val hasLive2d: Boolean)

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

/**
 * 冷启动首帧的稳定形状（v0.97.0，U3）：7 个"零值"格子（旧→新，最后一格是今天）。
 *
 * 只用于 StateFlow 的 `initialValue`：形状与 [buildWeekReview] 的产出完全同构，
 * 所以数据到达时不会发生"从零格变七格"的布局跳动，只是数字从 0 变成真值。
 */
fun emptyWeekReview(today: LocalDate = LocalDate.now()): WeekReview = WeekReview(
    days = (6 downTo 0).map { offset ->
        val date = today.minusDays(offset.toLong())
        DayReview(date = date, done = 0, total = 0, items = emptyList())
    },
    done = 0,
    total = 0,
    streak = 0,
)

/**
 * 连续「全清」天数：今天未全清不断签（从昨天往回数），对齐原 habitStreak 的宽恕规则。
 */
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
 * - AI 洞察 = [InsightEngine] 每晚 23:00 产出（按日/周/月分组）。
 *   记忆另由 [ExtractionEngine] 攒够轮数**自动**提炼 —— v0.99.0 起**没有**手动「提炼」入口（见类内注释）
 */
class DataViewModel(
    private val app: MenghuanApp,
    private val repo: ProfileRepository,
    private val db: AppDatabase,
    private val dailySuggestionGenerator: com.menghuan.app.data.DailySuggestionGenerator,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val facts: StateFlow<List<ProfileFact>> = repo.observeFacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * AI 洞察真数据源（[InsightEngine] 产出）。
     *
     * v0.88.0 起按**周期分组**：洞察现在有日/周/月三条链，混在一个 `List` 里显示
     * 用户分不清"哪条是今天的、哪条是这一周的"。这里按 period 分好，UI 直接分组渲染。
     *
     * ⚠️ 用的是 `latest()`（suspend，非 Flow）→ 包一层手动刷新，因为 UI 要三态（见 [insightsLoaded]）。
     */
    private val _insights = MutableStateFlow<List<AiInsight>>(emptyList())
    val insights: StateFlow<List<AiInsight>> = _insights.asStateFlow()

    /**
     * 洞察是否**已经查过一次**（三态用）。
     *
     * `_insights` 初值空列表时 UI 分不清"还没查"和"查了确实没有"——
     * 前者该显示加载态、后者该显示"还没有洞察"的空态文案。这个标记就是那个分界。
     */
    private val _insightsLoaded = MutableStateFlow(false)
    val insightsLoaded: StateFlow<Boolean> = _insightsLoaded.asStateFlow()

    init {
        refreshInsightsList()
    }

    /** 重读洞察列表（生成完毕后、以及进数据页时各调一次） */
    fun refreshInsightsList() {
        viewModelScope.launch {
            runCatching { db.aiInsightDao().latest() }
                .onSuccess { _insights.value = it }
            _insightsLoaded.value = true
        }
    }

    // ===== 角色空间入口（v0.92.0）=====

    /**
     * 角色空间是**按角色**的，而数据页是全局页 —— 所以这里的卡片点开先弹一层选人列表。
     *
     * ⚠️ **不能直接进"最近聊过的那个角色"**：用户翻到数据页想玩的是谁，只有他自己知道；
     * 替他猜一个 = 猜错就得先回聊天页聊一句再来，那比多点一次选人更气人。
     *
     * 名单**读一次就缓存**，但 `hasLive2d` 要查文件系统（`CallVisualStore.read` + 模型文件是否存在），
     * 费用在于每次进弹层都要扫一遍各角色目录，所以由 UI 在**弹层打开那一刻**调 [refreshSpaceEntries]，
     * 而不是绑在 Flow 上每帧算。
     */
    private val _spaceEntries = MutableStateFlow<List<PersonaSpaceEntry>>(emptyList())
    val spaceEntries: StateFlow<List<PersonaSpaceEntry>> = _spaceEntries.asStateFlow()

    /** 进数据页 / 打开选人弹层时各调一次：重扫各角色有没有可用的 Live2D 模型 */
    fun refreshSpaceEntries() {
        viewModelScope.launch {
            val personas = runCatching { app.personaRepository.observePersonas().first() }
                .getOrDefault(emptyList())
            val entries = withContext(Dispatchers.IO) {
                personas.map { p ->
                    val usable = runCatching {
                        val asset = CallVisualStore.read(app, p.id)
                        asset.type == CallVisualType.LIVE2D &&
                            asset.isUsable &&
                            CallVisualStore.live2dModelFile(app, p.id, asset) != null
                    }.getOrDefault(false)
                    PersonaSpaceEntry(p, usable)
                }
            }
            _spaceEntries.value = entries
        }
    }

    // ===== v0.42.0 本周回顾（统一口径，置顶卡） =====

    /**
     * 本周回顾：近 7 天每日该做/已完成 + 本周合计 + 连续全清天数。
     *
     * 初值给 **[emptyWeekReview]（7 个空格子）而不是 `WeekReview(emptyList(), 0, 0, 0)`**（v0.97.0，U3）：
     * 旧初值的 `days` 是空表，卡片里那排 7 天格子的 `forEachIndexed` 一次都不执行 ——
     * 冷启动进来看到的是「标题底下什么都没有 + 进度条 0」，Room 首个结果回来才"长出"七格。
     * 用固定 7 个零值格子占位，形状从第一帧起就是对的，数据到了只是数字变化。
     */
    val weekReview: StateFlow<WeekReview> = combine(
        db.taskDao().observeAll(),
        db.taskCompletionDao().observeAll(),
    ) { tasks, completions ->
        buildWeekReview(tasks, completions, LocalDate.now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyWeekReview())

    // ===== v0.42.0 每日建议（独立生成链路） =====

    /**
     * 今日建议是否**已经查过一次**（三态用，v0.97.0 U4）。
     *
     * `todaySuggestion` 初值是 `null`，而"确实没有建议"也是 `null` —— 两者同一状态时，
     * 卡片会先把「今天还没有建议」闪一遍，数据到了再翻成建议内容。这个标记就是那个分界。
     */
    private val _suggestionLoaded = MutableStateFlow(false)
    val suggestionLoaded: StateFlow<Boolean> = _suggestionLoaded.asStateFlow()

    /** 今日建议（无 = null，卡片显示空态） */
    val todaySuggestion: StateFlow<com.menghuan.app.data.model.DailySuggestion?> =
        db.dailySuggestionDao().observeByDay(LocalDate.now().toEpochDay())
            .onEach { _suggestionLoaded.value = true }
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

    /**
     * 手动生成今天的建议。
     *
     * ⚠️ v0.88.0：建议的素材是**昨天的洞察**，所以"点一下就有"未必成立 ——
     * 昨晚没跑洞察（没联网/没配 API）时这里会返回 null。文案要把这层因果说清，
     * 不能笼统报"生成失败，稍后再试"（用户会一直点、一直失败，还以为坏了）。
     */
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
            val hadInsight = runCatching {
                db.aiInsightDao().forDay(today.minusDays(1).toEpochDay()).isNotEmpty()
            }.getOrDefault(false)
            _suggestionMessage.value = when {
                result != null -> "今天的建议已更新"
                !apiReady.value -> "未配置 API，建议不可用"
                !hadInsight -> "昨晚还没有洞察，今天暂时给不出建议"
                else -> "生成失败，稍后再试"
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
        category: String,
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
                        category = category,
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
        category: String = "",
    ) {
        viewModelScope.launch {
            runCatching {
                db.ledgerDao().upsert(
                    entry.copy(
                        isIncome = isIncome,
                        amountCents = amountCents,
                        note = note,
                        category = category,
                    )
                )
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

    // ⚠️ v0.99.0 删链：`_extracting`/`extracting`、`_extractMessage`/`extractMessage`、
    // `clearExtractMessage()` 连同下面的 `refreshInsights()` 一起删了 —— 原因见下方那段注释。

    /** 洞察卡长按删除（v0.36.5） */
    fun deleteInsight(id: Long) = viewModelScope.launch {
        runCatching { db.aiInsightDao().deleteById(id) }
        refreshInsightsList()
    }

    // ⚠️ v0.99.0 删掉整条「手动提炼」链（`refreshInsights()` + `_extracting`/`extracting` +
    // `_extractMessage`/`extractMessage`/`clearExtractMessage()`）：
    // - 起因：全工程「同款病」扫描发现 `refreshInsights()` **零调用**。
    // - 根因：数据页那颗「提炼」按钮在 **v0.42.1 就被收敛掉了**（见 `DataScreen.AiEntryCard`
    //   上方区块头注释），从那天起这条链没人能触发；`AiEntryCard` 收了 `extracting`/`apiReady`
    //   却从没用过，`_extractMessage` 永远是 null，`clearExtractMessage()` 只是在清空它。
    // - 而手册在 v0.86.0 / v0.88.0 两次改版里又把「手动点『提炼』」写了回去 → 对用户的假承诺。
    // - 用户拍板：「本来就没有的东西不要补回」→ 删链 + 改手册，不再提这颗按钮。
    // - 记忆提炼本身不受影响：照旧由 `ExtractionEngine` 攒够 `extract_every_<id>` 轮自动跑。

    /** 是否已配好 API（今日建议弹窗据此决定「生成」能否点） */
    val apiReady: StateFlow<Boolean> = settingsRepository.apiConfig
        .map { it.isComplete }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)


    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MenghuanApp
                DataViewModel(
                    app,
                    app.profileRepository,
                    app.db,
                    app.dailySuggestionGenerator,
                    app.settingsRepository,
                )
            }
        }
    }
}
