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

/** 本周任务完成度（真实数据驱动完成度圆环） */
data class WeekProgress(val done: Int, val total: Int) {
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
            val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
            // dueAt = 0 视为今天
            val inWeek = tasks.filter {
                val due = if (it.dueAt == 0L) todayStart else it.dueAt
                due >= weekStart && due < weekEnd
            }
            WeekProgress(inWeek.count { it.isDone }, inWeek.size)
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

    /** 手动「提炼」：所有会话立即补跑一次（仍只送增量）；期间按钮转"提炼中…" */
    fun refreshInsights() {
        if (_extracting.value) return
        viewModelScope.launch {
            _extracting.value = true
            runCatching { extractionEngine.extractAll() }
            _extracting.value = false
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
