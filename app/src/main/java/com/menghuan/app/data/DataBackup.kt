package com.menghuan.app.data

import com.menghuan.app.data.model.AiInsight
import com.menghuan.app.data.model.AiWeeklyReport
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.Persona
import com.menghuan.app.data.model.ProfileFact
import com.menghuan.app.data.model.TaskItem
import org.json.JSONArray
import org.json.JSONObject

/** 解析后的导入数据（纯数据；合并落库由仓库/VM 按去重规则执行） */
data class ImportedData(
    val personas: List<ImportedPersona>,
    val messages: List<ImportedMessage>,
    val tasks: List<ImportedTask>,
    val profileFacts: List<ImportedFact>,
    val aiInsights: List<ImportedInsight>,
    val aiWeeklyReports: List<ImportedReport>,
)

data class ImportedPersona(
    val name: String,
    val title: String,
    val avatarEmoji: String,
    val systemPrompt: String,
    val tone: String,
)

data class ImportedMessage(
    val personaName: String,
    val isFromUser: Boolean,
    val content: String,
    val timestamp: Long,
)

data class ImportedTask(
    val title: String,
    val note: String,
    val dueAt: Long,
    val remindAt: Long?,
    val isDone: Boolean,
    val repeatDaily: Boolean,
    val createdBy: String,
)

data class ImportedFact(val key: String, val value: String, val source: String)

data class ImportedInsight(val title: String, val detail: String, val createdAt: Long)

data class ImportedReport(
    val weekStart: Long,
    val weekEnd: Long,
    val content: String,
    val createdAt: Long,
)

/**
 * 数据导出/导入编解码（纯函数，JVM 单测覆盖）：
 * - 导出 = 全量快照 JSON（不含 API 密钥，数据自主权）
 * - 导入 = 非破坏性合并：人设/档案/洞察/周报按各自键去重跳过，消息与任务追加
 * - 任何解析失败返回 null（不抛出，由调用层给用户反馈）
 */
object DataBackup {

    /** 全量导出 JSON（缩进 2，便于人工查看） */
    fun buildExportJson(
        personas: List<Persona>,
        messages: List<ChatMessage>,
        tasks: List<TaskItem>,
        facts: List<ProfileFact>,
        insights: List<AiInsight>,
        weeklyReports: List<AiWeeklyReport>,
    ): String {
        val root = JSONObject()
        root.put("exportedAt", System.currentTimeMillis())
        root.put(
            "personas",
            JSONArray().apply {
                personas.forEach {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("name", it.name)
                            .put("title", it.title)
                            .put("systemPrompt", it.systemPrompt)
                            .put("tone", it.tone)
                    )
                }
            },
        )
        val nameById = personas.associate { it.id to it.name }
        root.put(
            "messages",
            JSONArray().apply {
                messages.forEach {
                    put(
                        JSONObject()
                            .put("persona", nameById[it.conversationId] ?: it.conversationId)
                            .put("from", if (it.isFromUser) "user" else "mentor")
                            .put("content", it.content)
                            .put("timestamp", it.timestamp)
                    )
                }
            },
        )
        root.put(
            "tasks",
            JSONArray().apply {
                tasks.forEach {
                    put(
                        JSONObject()
                            .put("title", it.title)
                            .put("note", it.note)
                            .put("dueAt", it.dueAt)
                            .put("remindAt", it.remindAt ?: JSONObject.NULL)
                            .put("isDone", it.isDone)
                            .put("repeatDaily", it.repeatDaily)
                            .put("createdBy", it.createdBy)
                    )
                }
            },
        )
        root.put(
            "profileFacts",
            JSONArray().apply {
                facts.forEach {
                    put(
                        JSONObject()
                            .put("key", it.key)
                            .put("value", it.value)
                            .put("source", it.source)
                    )
                }
            },
        )
        root.put(
            "aiInsights",
            JSONArray().apply {
                insights.forEach {
                    put(
                        JSONObject()
                            .put("title", it.title)
                            .put("detail", it.detail)
                            .put("createdAt", it.createdAt)
                    )
                }
            },
        )
        root.put(
            "aiWeeklyReports",
            JSONArray().apply {
                weeklyReports.forEach {
                    put(
                        JSONObject()
                            .put("weekStart", it.weekStart)
                            .put("weekEnd", it.weekEnd)
                            .put("content", it.content)
                            .put("createdAt", it.createdAt)
                    )
                }
            },
        )
        return root.toString(2)
    }

    /** 导入 JSON → 结构化数据；非法/空文件返回 null；缺失段落按空处理 */
    fun parseImport(text: String): ImportedData? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return ImportedData(
            personas = root.optJSONArray("personas").mapNotNullIndexed { o ->
                val name = o.optString("name").trim()
                if (name.isEmpty()) return@mapNotNullIndexed null
                ImportedPersona(
                    name = name,
                    title = o.optString("title").ifBlank { name },
                    avatarEmoji = o.optString("avatarEmoji").ifBlank { "🙂" },
                    systemPrompt = o.optString("systemPrompt"),
                    tone = o.optString("tone").ifBlank { com.menghuan.app.data.model.ToneStyle.GENTLE.name },
                )
            },
            messages = root.optJSONArray("messages").mapNotNullIndexed { o ->
                val content = o.optString("content")
                val persona = o.optString("persona")
                if (content.isBlank() || persona.isBlank()) return@mapNotNullIndexed null
                ImportedMessage(
                    personaName = persona,
                    isFromUser = o.optString("from") == "user",
                    content = content,
                    timestamp = o.optLong("timestamp", System.currentTimeMillis()),
                )
            },
            tasks = root.optJSONArray("tasks").mapNotNullIndexed { o ->
                val title = o.optString("title").trim()
                if (title.isEmpty()) return@mapNotNullIndexed null
                val remindAt = o.optLong("remindAt", 0L)
                ImportedTask(
                    title = title,
                    note = o.optString("note"),
                    dueAt = o.optLong("dueAt", 0L),
                    remindAt = if (remindAt > 0) remindAt else null,
                    isDone = o.optBoolean("isDone", false),
                    repeatDaily = o.optBoolean("repeatDaily", false),
                    createdBy = o.optString("createdBy", "user"),
                )
            },
            profileFacts = root.optJSONArray("profileFacts").mapNotNullIndexed { o ->
                val key = o.optString("key").trim()
                val value = o.optString("value")
                if (key.isEmpty() || value.isEmpty()) return@mapNotNullIndexed null
                ImportedFact(key = key, value = value, source = o.optString("source", "manual"))
            },
            aiInsights = root.optJSONArray("aiInsights").mapNotNullIndexed { o ->
                val title = o.optString("title").trim()
                val detail = o.optString("detail")
                if (title.isEmpty() || detail.isEmpty()) return@mapNotNullIndexed null
                ImportedInsight(
                    title = title,
                    detail = detail,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                )
            },
            aiWeeklyReports = root.optJSONArray("aiWeeklyReports").mapNotNullIndexed { o ->
                val content = o.optString("content").trim()
                val weekStart = o.optLong("weekStart", 0L)
                if (content.isEmpty() || weekStart == 0L) return@mapNotNullIndexed null
                ImportedReport(
                    weekStart = weekStart,
                    weekEnd = o.optLong("weekEnd", weekStart + 6),
                    content = content,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                )
            },
        )
    }

    private inline fun <T> JSONArray?.mapNotNullIndexed(transform: (JSONObject) -> T?): List<T> {
        if (this == null) return emptyList()
        val result = ArrayList<T>(length())
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            runCatching { transform(obj) }.getOrNull()?.let { result.add(it) }
        }
        return result
    }
}
