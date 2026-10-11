package com.menghuan.app

import com.menghuan.app.data.DataBackup
import com.menghuan.app.data.model.AiInsight
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.Persona
import com.menghuan.app.data.model.ProfileFact
import com.menghuan.app.data.model.TaskItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据导出/导入编解码（纯 JVM）：build → parse 往返一致；畸形输入返回 null；
 * 洞察段落参与导出与解析。
 */
class DataBackupTest {

    @Test
    fun `导出解析往返一致`() {
        val personas = listOf(
            Persona(id = 1, name = "阿布", title = "人生导师", avatarEmoji = "🖋️", systemPrompt = "你是导师", tone = "STRICT"),
        )
        val messages = listOf(
            ChatMessage(id = 1, conversationId = 1, isFromUser = true, content = "最近想早睡", timestamp = 1000L),
            ChatMessage(id = 2, conversationId = 1, isFromUser = false, content = "定个 23:00 闹钟", timestamp = 2000L),
        )
        val tasks = listOf(
            TaskItem(id = 1, title = "23:00 前入睡", note = "打卡", dueAt = 500L, remindAt = 400L, isDone = true, createdBy = "ai"),
            TaskItem(id = 2, title = "背单词", repeatDaily = true),
        )
        val facts = listOf(ProfileFact(id = 1, key = "作息", value = "00:30-07:45", source = "ai", updatedAt = 9L))
        val insights = listOf(AiInsight(id = 1, title = "熬夜与短视频相关", detail = "晚睡 82 分钟", createdAt = 8L))

        val json = DataBackup.buildExportJson(personas, messages, tasks, facts, insights)
        val parsed = DataBackup.parseImport(json)

        assertNotNull(parsed)
        parsed!!.let { data ->
            assertEquals(1, data.personas.size)
            assertEquals("阿布", data.personas[0].name)
            assertEquals("STRICT", data.personas[0].tone)
            assertEquals("人生导师", data.personas[0].title)

            assertEquals(2, data.messages.size)
            assertEquals("阿布", data.messages[0].personaName)
            assertTrue(data.messages[0].isFromUser)
            assertFalse(data.messages[1].isFromUser)
            assertEquals(2000L, data.messages[1].timestamp)

            assertEquals(2, data.tasks.size)
            assertEquals("23:00 前入睡", data.tasks[0].title)
            assertEquals(400L, data.tasks[0].remindAt)
            assertTrue(data.tasks[0].isDone)
            assertEquals("ai", data.tasks[0].createdBy)
            assertTrue(data.tasks[1].repeatDaily)

            assertEquals(1, data.profileFacts.size)
            assertEquals("作息" to "00:30-07:45", data.profileFacts[0].key to data.profileFacts[0].value)
            assertEquals("ai", data.profileFacts[0].source)

            assertEquals(1, data.aiInsights.size)
            assertEquals("熬夜与短视频相关", data.aiInsights[0].title)
        }
    }

    @Test
    fun `畸形与空缺段落容错`() {
        // 非 JSON → null
        assertNull(DataBackup.parseImport("这不是 JSON"))
        assertNull(DataBackup.parseImport("[]"))
        // 空 JSON → 全空结构而非 null（旧版本导出的文件缺段落，仍可导入）
        val empty = DataBackup.parseImport("{}")
        assertNotNull(empty)
        assertTrue(empty!!.personas.isEmpty() && empty.aiInsights.isEmpty() && empty.tasks.isEmpty())
        // 缺段落的旧导出文件：其余段落正常解析
        val legacy = DataBackup.parseImport(
            "{\"personas\":[{\"name\":\"严格教练\",\"title\":\"教练\"}],\"tasks\":[]}",
        )
        assertNotNull(legacy)
        assertEquals(1, legacy!!.personas.size)
        assertEquals("严格教练", legacy.personas[0].name)
        assertEquals("教练", legacy.personas[0].title)
        assertEquals("🙂", legacy.personas[0].avatarEmoji) // 缺省回填
        // 畸形行被跳过（name 缺失的人设不入列表）
        val partial = DataBackup.parseImport("{\"personas\":[{\"title\":\"无名\"},{\"name\":\"阿布\"}]}")
        assertEquals(listOf("阿布"), partial!!.personas.map { it.name })
    }
}
