package com.daoshi.app.ui.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.daoshi.app.MentorApp
import com.daoshi.app.data.ExtractionEngine
import com.daoshi.app.data.ProfileRepository
import com.daoshi.app.data.SettingsRepository
import com.daoshi.app.data.db.AppDatabase
import com.daoshi.app.data.model.AiInsight
import com.daoshi.app.data.model.ProfileFact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 编辑弹层的草稿行：id = null 表示新增 */
data class DraftFact(val id: Long?, val key: String, val value: String)

/** 本周任务完成度（真实数据驱动完成度圆环）；上周有数据时附对比 */
data class WeekProgress(val done: Int, val total: Int, val lastWeekFraction: Float? = null) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

/** 近 7 天每日完成任务数（习惯卡真数据：打卡热力） */
data class DayCount(val date: LocalDate, val done: Int)

/**
 * 我的数据页 VM：
 * - 基础档案接 ProfileFact（编辑保存：删被移除的行，非空行 upsert，保留已有 source）
 * - 完成度圆环 = 本周（周一起）到期任务的真实完成率
 * - 习惯卡 = 近 7 天每日完成任务数（打卡热力）
 * - AI 洞察 = 提炼流水线产物（ai_insights 最新两条）；手动「提炼」调引擎全量补跑
 */
class DataViewModel(
    private val repo: ProfileRepository,
    private val db: AppDatabase,
    private val extractionEngine: ExtractionEngine,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val facts: StateFlow<List<ProfileFact>> = repo.observeFacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val weekProgress: StateFlow<WeekProgress> = db.taskDao().observeAll()
        .map { tasks ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val monday = today.with(DayOfWeek.MONDAY)
            val weekStart = monday.atStartOfDay(zone).toInstant().toEpochMilli()
            val weekEnd = monday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            val lastWeekStart = monday.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
            // dueAt = 0 视为今天
            fun dueOf(task: com.daoshi.app.data.model.TaskItem): Long =
                if (task.dueAt == 0L) todayStart else task.dueAt
            val inWeek = tasks.filter { val due = dueOf(it); due >= weekStart && due < weekEnd }
            val lastWeek = tasks.filter { val due = dueOf(it); due >= lastWeekStart && due < weekStart }
            val lastFraction = if (lastWeek.isEmpty()) null
            else lastWeek.count { it.isDone }.toFloat() / lastWeek.size
            WeekProgress(inWeek.count { it.isDone }, inWeek.size, lastFraction)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeekProgress(0, 0))

    /** 近 7 天（含今天）每日完成任务数，旧→新 */
    val weekDaily: StateFlow<List<DayCount>> = db.taskDao().observeAll()
        .map { tasks ->
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            (6 downTo 0).map { offset ->
                val day = today.minusDays(offset.toLong())
                val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val done = tasks.count { task ->
                    val due = if (task.dueAt == 0L) {
                        LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
                    } else {
                        task.dueAt
                    }
                    task.isDone && due >= dayStart && due < dayEnd
                }
                DayCount(day, done)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** AI 洞察真数据源（提炼引擎产出），最新两条 */
    val insights: StateFlow<List<AiInsight>> = db.aiInsightDao().observeLatest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _extracting = MutableStateFlow(false)
    val extracting: StateFlow<Boolean> = _extracting.asStateFlow()

    /** 手动提炼的结果反馈（展示数秒后自动清除） */
    private val _extractMessage = MutableStateFlow<String?>(null)
    val extractMessage: StateFlow<String?> = _extractMessage.asStateFlow()

    fun clearExtractMessage() {
        _extractMessage.value = null
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
                    append(parts.joinToString(" · "))
                }
            }
        }
    }

    /** API 未配置时隐藏提炼入口 */
    val apiReady: StateFlow<Boolean> = settingsRepository.apiConfig
        .map { it.isComplete }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun saveEdits(items: List<DraftFact>, removedIds: List<Long>) = viewModelScope.launch {
        removedIds.forEach { repo.delete(it) }
        items.forEach { draft ->
            val key = draft.key.trim()
            val value = draft.value.trim()
            if (key.isBlank() && value.isBlank()) return@forEach
            val existing = draft.id?.let { repo.getFact(it) }
            repo.save(
                ProfileFact(
                    id = draft.id ?: 0,
                    key = key.ifBlank { value },
                    value = value,
                    source = existing?.source ?: "manual",
                )
            )
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MentorApp
                DataViewModel(app.profileRepository, app.db, app.extractionEngine, app.settingsRepository)
            }
        }
    }
}
