package com.menghuan.app

import com.menghuan.app.data.UsageTracker
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.llm.OpenAiClient
import com.menghuan.app.llm.parseUsage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用量读数单测（v0.69.0，纯 JVM）：
 * `usage` 块里"输入 token / 缓存命中"这两个数怎么认、认不出来时必须**是 null 而不是 0%**。
 *
 * 为什么这条要有测试：命中率是"前缀稳不稳"的唯一读数，而各家兼容端点把这个数放在
 * 三个不同的位置。认错了不会报错，只会一直显示 0% —— 用户会去改一个本来就对的东西。
 */
class UsageCacheTest {

    @Test
    fun `OpenAI 口径 prompt_tokens_details_cached_tokens`() {
        val json = JSONObject(
            """{"total_tokens":1000,"prompt_tokens":800,
               "prompt_tokens_details":{"cached_tokens":600}}"""
        )
        val readout = parseUsage(json)!!
        assertEquals(1000, readout.totalTokens)
        assertEquals(800, readout.promptTokens)
        assertEquals(600, readout.cachedPromptTokens)
    }

    @Test
    fun `摊平在 usage 根上的 cached_tokens 也认`() {
        val readout = parseUsage(JSONObject("""{"total_tokens":10,"prompt_tokens":8,"cached_tokens":5}"""))!!
        assertEquals(5, readout.cachedPromptTokens)
    }

    @Test
    fun `Anthropic 方言 cache_read_input_tokens 也认`() {
        val readout = parseUsage(JSONObject("""{"total_tokens":10,"prompt_tokens":8,"cache_read_input_tokens":7}"""))!!
        assertEquals(7, readout.cachedPromptTokens)
    }

    @Test
    fun `嵌套里是 0 时才回落到其他方言_嵌套有值时不抢`() {
        val nestedWins = parseUsage(
            JSONObject(
                """{"total_tokens":1,"prompt_tokens":1,"cached_tokens":9,
                   "prompt_tokens_details":{"cached_tokens":3}}"""
            )
        )!!
        assertEquals("嵌套口径优先", 3, nestedWins.cachedPromptTokens)
    }

    @Test
    fun `没有 usage 块返回 null`() {
        assertNull(parseUsage(null))
    }

    @Test
    fun `三个数全为 0 返回 null——拿不到的不猜`() {
        assertNull(parseUsage(JSONObject("""{"total_tokens":0,"prompt_tokens":0}""")))
        assertNull(parseUsage(JSONObject("""{}""")))
    }

    @Test
    fun `只有分项没有 total 也算有读数`() {
        // 有些兼容网关只把分项转出来（total 缺省为 0）。这时命中率照样要能算，别把整块丢
        val readout = parseUsage(JSONObject("""{"prompt_tokens":100,"cached_tokens":40}"""))!!
        assertEquals(100, readout.promptTokens)
    }

    @Test
    fun `输入为 0 时命中率是 null 而不是 0 百分比`() {
        assertNull(UsageTracker.cacheHitRate(0, 0))
        assertNull(UsageTracker.cacheHitRate(0, 50))
        assertTrue(UsageTracker.cacheHitLabel(0, 0).contains("未返回"))
    }

    @Test
    fun `命中率算对了并且夹在 0 到 1`() {
        assertEquals(0.75, UsageTracker.cacheHitRate(800, 600)!!, 1e-9)
        // 脏数据：命中比输入还多——夹到 1.0，不显示 120% 这种鬼数
        assertEquals(1.0, UsageTracker.cacheHitRate(100, 500)!!, 1e-9)
        // 负数读数（服务商乱回）当 0
        assertEquals(0.0, UsageTracker.cacheHitRate(100, -5)!!, 1e-9)
    }

    @Test
    fun `读数文案带上两个数与百分比`() {
        val label = UsageTracker.cacheHitLabel(800, 600)
        assertTrue(label.contains("75%"))
        assertTrue(label.contains("600"))
        assertTrue(label.contains("800"))
    }

    // ===== 易变上下文的位置（这一族的前半段：前缀到底稳不稳）=====

    @Test
    fun `空易变上下文时正文一字不动`() {
        assertEquals("今晚吃什么", OpenAiClient.withRuntimeContext("今晚吃什么", ""))
        assertEquals("今晚吃什么", OpenAiClient.withRuntimeContext("今晚吃什么", "   "))
    }

    @Test
    fun `易变上下文贴在正文之前且原文不被改写`() {
        val merged = OpenAiClient.withRuntimeContext("今晚吃什么", "当前时间：2026年10月8日 20:15（星期四）")
        assertTrue(merged.startsWith(OpenAiClient.RUNTIME_CONTEXT_MARK))
        assertTrue("原文必须整段还在末尾", merged.endsWith("今晚吃什么"))
        assertTrue(merged.contains("当前时间"))
    }

    @Test
    fun `只有最后一条用户消息带易变块_system 与更早历史保持干净`() = kotlinx.coroutines.runBlocking {
        val history = listOf(
            ChatMessage(conversationId = 1, isFromUser = true, content = "上周我说过什么"),
            ChatMessage(conversationId = 1, isFromUser = false, content = "你说过你喜欢火锅"),
            ChatMessage(conversationId = 1, isFromUser = true, content = "今晚吃什么"),
        )
        val json = OpenAiClient.buildMessagesJson(
            "你是梦欢", history, imageDataUrl = { null }, runtimeContext = "当前时间：2026年10月8日 20:15（星期四）",
        )
        val system = json.getJSONObject(0).getString("content")
        assertEquals("你是梦欢", system)  // 时间要是进了 system，每分钟作废一次缓存
        val earlierUser = json.getJSONObject(1).getString("content")
        assertFalse("历史里的老消息不能被补上时间块", earlierUser.contains(OpenAiClient.RUNTIME_CONTEXT_MARK))
        val latestUser = json.getJSONObject(3).getString("content")
        assertTrue(latestUser.contains(OpenAiClient.RUNTIME_CONTEXT_MARK))
        assertTrue(latestUser.endsWith("今晚吃什么"))
    }

    @Test
    fun `带图那条也走得通_文本段拿到易变块而不是丢图`() = kotlinx.coroutines.runBlocking {
        val history = listOf(
            ChatMessage(conversationId = 1, isFromUser = true, content = "好看吗", imagePath = "/tmp/a.jpg"),
        )
        val json = OpenAiClient.buildMessagesJson(
            "系统", history, "当前时间：2026年10月8日"
        ) { _ -> "data:image/jpeg;base64,AA" }
        val parts = json.getJSONObject(1).getJSONArray("content")
        assertEquals("image_url", parts.getJSONObject(0).getString("type"))
        assertTrue(parts.getJSONObject(1).getString("text").contains(OpenAiClient.RUNTIME_CONTEXT_MARK))
    }
}
