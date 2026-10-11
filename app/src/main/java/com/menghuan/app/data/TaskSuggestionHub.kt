package com.menghuan.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/**
 * AI 任务建议中心（进程内存级，不落库）。
 *
 * ## v0.88.0 换驱动
 * 以前是**提炼引擎批量产出** → hub 去重分发 → 聊天页出卡。现在改成 [TaskSuggestionMarker]：
 * AI 在回复里写 `[建议任务:标题|备注]`，聊天链解析出来经这里入队、出卡。
 * 所以 [publish] 的调用点从 [ExtractionEngine] 挪到了聊天链。
 *
 * 去重仍在这里维护（「同 title 当日不重复建议」，跨轮次、按天滚动重置）——
 * 因为去重状态本来就该是"用户今天已经看过哪些建议"，与产出者是谁无关。
 */
class TaskSuggestionHub {

    private val _suggestions = MutableStateFlow<Map<Long, List<TaskSuggestion>>>(emptyMap())

    /** personaId → 该会话当前待展示的建议（卡片确认后减少） */
    val suggestions: StateFlow<Map<Long, List<TaskSuggestion>>> = _suggestions.asStateFlow()

    fun forPersona(personaId: Long): Flow<List<TaskSuggestion>> =
        _suggestions.map { it[personaId].orEmpty() }

    /**
     * 聊天链用：过滤掉当日已建议过 / 已存在同名任务的，并记下本次建议（按天滚动）。
     *
     * ⚠️ 以前这里转调 `ProfileExtractor.filterSuggestions`，随记忆链收窄一起删了 ——
     * 那个函数只是"过滤 + 截断"几行，放在这里反而让记忆提炼器背了任务建议的职责。
     */
    fun filterAndRecord(
        incoming: List<TaskSuggestion>,
        existingTaskTitles: Set<String>,
    ): List<TaskSuggestion> {
        val today = LocalDate.now()
        if (today != recordedDay) {
            recordedDay = today
            recordedTitles = emptySet()
        }
        val fresh = incoming
            .filter { it.title.isNotBlank() }
            .filter { it.title !in existingTaskTitles }
            .filter { it.title !in recordedTitles }
            .distinctBy { it.title }
        recordedTitles = recordedTitles + fresh.map { it.title }
        return fresh
    }

    /** 新建议入队：追加到对应会话（同轮同标题去重） */
    fun publish(personaId: Long, suggestions: List<TaskSuggestion>) {
        if (suggestions.isEmpty()) return
        _suggestions.update { current ->
            current + (personaId to (current[personaId].orEmpty() + suggestions).distinctBy { it.title })
        }
    }

    /** 用户点击「添加任务」：卡片消失 */
    fun consume(personaId: Long, suggestion: TaskSuggestion) {
        _suggestions.update { current ->
            current + (personaId to (current[personaId].orEmpty() - suggestion))
        }
    }

    /** 清除全部数据时清空待展示建议与"当日已建议"状态（v0.36.5） */
    fun clear() {
        _suggestions.update { emptyMap() }
        recordedTitles = emptySet()
    }

    private var recordedDay: LocalDate = LocalDate.now()
    private var recordedTitles: Set<String> = emptySet()
}
