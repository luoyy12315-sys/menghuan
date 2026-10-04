package com.menghuan.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/**
 * AI 任务建议中心（进程内存级，不落库）：
 * 提炼引擎产出 taskSuggestions 后按 personaId 分发，聊天页在 AI 气泡下方渲染卡片，
 * 用户点「添加任务」写入 Room 后 consume 消失；进程被杀未消费的建议随之丢弃（可接受：
 * 建议是即时引导，不是持久数据）。
 * 「同 title 当日不重复建议」的状态也在这里维护（跨提炼轮次、按天滚动重置）。
 */
class TaskSuggestionHub {

    private val _suggestions = MutableStateFlow<Map<Long, List<TaskSuggestion>>>(emptyMap())

    /** personaId → 该会话当前待展示的建议（卡片确认后减少） */
    val suggestions: StateFlow<Map<Long, List<TaskSuggestion>>> = _suggestions.asStateFlow()

    fun forPersona(personaId: Long): Flow<List<TaskSuggestion>> =
        _suggestions.map { it[personaId].orEmpty() }

    /** 引擎用：过滤掉当日已建议过 / 已存在同名任务的；记录本次建议（按天滚动） */
    fun filterAndRecord(
        incoming: List<TaskSuggestion>,
        existingTaskTitles: Set<String>,
    ): List<TaskSuggestion> {
        val today = LocalDate.now()
        if (today != recordedDay) {
            recordedDay = today
            recordedTitles = emptySet()
        }
        val fresh = ProfileExtractor.filterSuggestions(incoming, existingTaskTitles, recordedTitles)
        recordedTitles = recordedTitles + fresh.map { it.title }
        return fresh
    }

    /** 提炼产出新建议：追加到对应会话（同轮同标题去重） */
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
