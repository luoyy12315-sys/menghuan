package com.menghuan.app

import com.menghuan.app.data.ProfileExtractor
import com.menghuan.app.data.UsageTracker
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.llm.OpenAiClient
import com.menghuan.app.ui.chat.AgentChatGate
import com.menghuan.app.ui.chat.ErrorBar
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v0.38.0 纯逻辑单测：对话触发手机操作的前置守卫分级、日期型记忆的宽容日期解析、
 * 用量记账的月份键与格式化、vision 多段 content 请求体组装。
 */
class V038LogicTest {

    // ===== AgentChatGate（错误条分级，防止"重试"重复执行手机操作） =====

    @Test
    fun `全部满足时放行`() {
        assertNull(AgentChatGate.decide(enabled = true, serviceRunning = true, alreadyRunning = false, apiComplete = true))
    }

    @Test
    fun `开关关闭是纯提示不给动作`() {
        val (msg, kind) = AgentChatGate.decide(false, true, false, true)!!
        assertTrue(msg.contains("关闭"))
        assertEquals(ErrorBar.KIND_INFO, kind)
    }

    @Test
    fun `无障碍未开启给去开启动作`() {
        val (msg, kind) = AgentChatGate.decide(true, false, false, true)!!
        assertTrue(msg.contains("无障碍"))
        assertEquals(ErrorBar.KIND_ACCESSIBILITY, kind)
    }

    @Test
    fun `执行中是纯提示`() {
        val (_, kind) = AgentChatGate.decide(true, true, true, true)!!
        assertEquals(ErrorBar.KIND_INFO, kind)
    }

    @Test
    fun `API 未配置给去配置动作`() {
        val (_, kind) = AgentChatGate.decide(true, true, false, false)!!
        assertEquals(ErrorBar.KIND_API, kind)
    }

    // ===== normalizeDueDate（日期型记忆） =====

    @Test
    fun `ISO 日期原样归一`() {
        assertEquals("2026-10-05", ProfileExtractor.normalizeDueDate("2026-10-05"))
    }

    @Test
    fun `斜杠日期归一为 ISO`() {
        assertEquals("2026-10-05", ProfileExtractor.normalizeDueDate("2026/10/5"))
    }

    @Test
    fun `中文月日补当前年`() {
        val today = java.time.LocalDate.of(2026, 10, 2)
        assertEquals("2026-12-01", ProfileExtractor.normalizeDueDate("12月1日", today))
    }

    @Test
    fun `已过去超过 30 天的月日视为明年`() {
        val today = java.time.LocalDate.of(2026, 10, 2)
        assertEquals("2027-01-05", ProfileExtractor.normalizeDueDate("1月5日", today))
    }

    @Test
    fun `解析不出返回空`() {
        assertEquals("", ProfileExtractor.normalizeDueDate(""))
        assertEquals("", ProfileExtractor.normalizeDueDate("下周三"))
        assertEquals("", ProfileExtractor.normalizeDueDate("2026-13-40"))
    }

    @Test
    fun `建议任务标记能带出到期日`() {
        // v0.88.0：日期从「提炼 JSON 的 date 字段」挪到标记第三段
        // 格式 [建议任务:标题|备注|日期]，日期由 ProfileExtractor.normalizeDueDate 宽容解析
        val raw = "面试加油[建议任务:周五面试|带简历|2026-10-09]"
        val s = com.menghuan.app.data.TaskSuggestionMarker.parse(raw).suggestions.first()
        assertEquals("周五面试", s.title)
        assertEquals("带简历", s.note)
        assertEquals("2026-10-09", s.dueDate)
    }

    @Test
    fun `建议任务标记不带日期时为空`() {
        val raw = "[建议任务:早睡]"
        val s = com.menghuan.app.data.TaskSuggestionMarker.parse(raw).suggestions.first()
        assertEquals("", s.dueDate)
    }

    // ===== UsageTracker =====

    @Test
    fun `月份键格式`() {
        val cal = Calendar.getInstance().apply { set(2026, 9, 5, 10, 0) } // 月份 0 起，9 = 10 月
        assertEquals("2026-10", UsageTracker.monthKey(cal.timeInMillis))
    }

    @Test
    fun `token 格式化分档`() {
        assertEquals("500", UsageTracker.formatTokens(500))
        assertEquals("约 1.2 万", UsageTracker.formatTokens(12_345))
        assertEquals("约 1 万", UsageTracker.formatTokens(1_234_567))
    }

    // ===== vision 多段 content 组装 =====

    @Test
    fun `用户带图消息转多段 content 且助手图忽略`() = runBlocking {
        val history = listOf(
            ChatMessage(conversationId = 1, isFromUser = true, content = "看看这是什么", imagePath = "/tmp/a.jpg"),
            ChatMessage(conversationId = 1, isFromUser = false, content = "这是一只猫", imagePath = "/tmp/ai.png"),
            ChatMessage(conversationId = 1, isFromUser = true, content = "", imagePath = "/tmp/b.jpg"),
        )
        val json: JSONArray = OpenAiClient.buildMessagesJson("系统", history) { path ->
            "data:image/jpeg;base64,AAAA_" + path
        }
        assertEquals(4, json.length()) // system + 3
        // 第一条：图在前、文本在后
        val first = json.getJSONObject(1)
        assertEquals("user", first.getString("role"))
        val parts = first.getJSONArray("content")
        assertEquals("image_url", parts.getJSONObject(0).getString("type"))
        assertEquals("data:image/jpeg;base64,AAAA_/tmp/a.jpg", parts.getJSONObject(0).getJSONObject("image_url").getString("url"))
        assertEquals("text", parts.getJSONObject(1).getString("type"))
        // 第二条：助手消息带 imagePath → 仍是纯文本
        assertTrue(json.getJSONObject(2).getString("content") is String || json.getJSONObject(2).get("content") is String)
        // 第三条：无字幕纯图 → 只有 image_url 一段
        val third = json.getJSONObject(3).getJSONArray("content")
        assertEquals(1, third.length())
    }
}
