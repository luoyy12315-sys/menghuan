package com.menghuan.app

import com.menghuan.app.llm.ApiException
import com.menghuan.app.llm.OpenAiClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SSE 客户端单测（MockWebServer，纯 JVM）：
 * 验证 OpenAI 兼容流式协议的解析契约——data 行拆 token、[DONE] 终止、非 2xx 报 ApiException。
 */
class OpenAiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() = OpenAiClient(server.url("/v1").toString(), "test-key", "test-model")

    @Test
    fun `SSE 按 data 行拆 token 且 DONE 终止`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                data: {"choices":[{"delta":{"content":"你"}}]}

                data: {"choices":[{"delta":{"content":"好"}}]}

                data: {"choices":[{"delta":{}}]}

                data: [DONE]

                data: {"choices":[{"delta":{"content":"不应出现"}}]}
                """.trimIndent()
            )
        )
        val tokens = client().chat("system", emptyList()).toList()
        assertEquals(listOf("你", "好"), tokens)

        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("test-model"))
        assertTrue(body.contains("\"role\":\"system\""))
    }

    @Test
    fun `非 2xx 抛 ApiException 带状态码且不产生 token`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("invalid key"))
        val error = runCatching { client().chat("s", emptyList()).toList() }.exceptionOrNull()
        assertTrue("应为 ApiException，实为 $error", error is ApiException)
        assertEquals(401, (error as ApiException).code)
    }

    @Test
    fun `非 SSE 格式的 200 响应不产生 token 也不崩溃`() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>gateway error page</html>"))
        val tokens = client().chat("s", emptyList()).toList()
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `推理模型的 content null 增量被跳过 不产生字面量 null 垃圾`() = runBlocking {
        // deepseek-reasoner / GLM-4.5 等推理模型在思维链阶段发 "content": null
        // 的增量；org.json 的 optString 会把它读成字符串 "null"（历史 bug 的根因），
        // 契约：只有 String 类型的 content 才 emit
        server.enqueue(
            MockResponse().setBody(
                """
                data: {"choices":[{"delta":{"content":null,"reasoning_content":"思考中"}}]}

                data: {"choices":[{"delta":{"content":null}}]}

                data: {"choices":[{"delta":{"content":"正"}}]}

                data: {"choices":[{"delta":{"content":null,"reasoning_content":"还在想"}}]}

                data: {"choices":[{"delta":{"content":"常回复"}}]}

                data: [DONE]
                """.trimIndent()
            )
        )
    }

    @Test
    fun `思考链 reason_content 与正文分流 不混进 Delta`() = runBlocking {
        // v0.41.4：推理类模型先逐块吐 reasoning_content（思考链），再吐 content（正文）。
        // 契约：两者必须是不同事件——正文上屏，思考链只在长按菜单里看，绝不混进气泡。
        server.enqueue(
            MockResponse().setBody(
                """
                data: {"choices":[{"delta":{"content":null,"reasoning_content":"用户问的是天气"}}]}

                data: {"choices":[{"delta":{"content":null,"reasoning_content":"，我要提醒他带伞"}}]}

                data: {"choices":[{"delta":{"content":"今天有雨"}}]}

                data: {"choices":[{"delta":{"content":"，记得带伞"}}]}

                data: [DONE]
                """.trimIndent()
            )
        )
        val events = client().streamEvents("system", emptyList()).toList()
        val text = events.filterIsInstance<com.menghuan.app.llm.StreamEvent.Delta>().joinToString("") { it.text }
        val reasoning = events.filterIsInstance<com.menghuan.app.llm.StreamEvent.Reasoning>().joinToString("") { it.text }
        assertEquals("今天有雨，记得带伞", text)
        assertEquals("用户问的是天气，我要提醒他带伞", reasoning)
    }

    @Test
    fun `reasoning 字段名兼容 部分服务商不使用 reason_content`() = runBlocking {
        // 部分 OpenAI 兼容端把思考链放在 delta.reasoning 上（无 _content 后缀）
        server.enqueue(
            MockResponse().setBody(
                """
                data: {"choices":[{"delta":{"reasoning":"先看他有没有配图"}}]}

                data: {"choices":[{"delta":{"content":"好的"}}]}

                data: [DONE]
                """.trimIndent()
            )
        )
        val reasoning = client().streamEvents("s", emptyList()).toList()
            .filterIsInstance<com.menghuan.app.llm.StreamEvent.Reasoning>()
            .joinToString("") { it.text }
        assertEquals("先看他有没有配图", reasoning)
    }

    @Test
    fun `collectReply 同时返回正文与思考链`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                data: {"choices":[{"delta":{"reasoning_content":"想一下"}}]}

                data: {"choices":[{"delta":{"content":"答案"}}]}

                data: {"usage":{"total_tokens":42},"choices":[]}

                data: [DONE]
                """.trimIndent()
            )
        )
        val collected = client().collectReply("s", emptyList())
        assertEquals("答案", collected.text)
        assertEquals("想一下", collected.reasoning)
        assertEquals(42, collected.usageTokens)
    }

    @Test
    fun `助手消息的思考链不进请求体`() = runBlocking {
        // v0.41.4：reasoning 是给用户看的旁白，回送既费 token 又会把旧思路灌回上下文。
        // 契约：buildMessagesJson 只发 content，不带 reasoning。
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))
        val history = listOf(
            com.menghuan.app.data.model.ChatMessage(
                conversationId = 1L, isFromUser = false, content = "今天有雨",
                reasoning = "SECRET_CHAIN_OF_THOUGHT",
            )
        )
        client().chat("system", history).toList()
        val body = server.takeRequest().body.readUtf8()
        assertTrue("正文应发出", body.contains("今天有雨"))
        assertTrue(
            "思考链不应出现在请求体里：$body",
            !body.contains("SECRET_CHAIN_OF_THOUGHT"),
        )
    }
}
