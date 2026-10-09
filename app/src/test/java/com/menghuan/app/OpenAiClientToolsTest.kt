package com.menghuan.app

import com.menghuan.app.llm.OpenAiClient
import com.menghuan.app.llm.PendingToolCall
import com.menghuan.app.llm.StreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * OpenAiClient 的 tools 支持单测（P3-1，MockWebServer 纯 JVM）：
 * 请求体带/不带 tools 字段、流式 tool_calls 分片聚合成 ToolCalls 事件、
 * extraMessages 追加在 messages 尾部、tools 能力探测的成败两路。
 */
class OpenAiClientToolsTest {

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

    private fun dummyTools() = JSONArray().put(
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "mcp__srv001__get_time")
                    .put("description", "获取当前时间")
                    .put("parameters", JSONObject().put("type", "object")),
            ),
    )

    @Test
    fun `带 tools 时请求体含 tools 字段 不带时一个字段都不多`() = runBlocking {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))
        client().streamEvents("s", emptyList(), tools = dummyTools()).toList()
        client().streamEvents("s", emptyList()).toList()

        val withTools = server.takeRequest().body.readUtf8()
        assertTrue("应带 tools 字段：$withTools", withTools.contains("\"tools\""))
        assertTrue(withTools.contains("mcp__srv001__get_time"))

        val withoutTools = server.takeRequest().body.readUtf8()
        // 缓存纪律：没工具时请求体与 P3 之前逐字节一致，一个字段都不许多
        assertTrue("不应带 tools 字段：$withoutTools", !withoutTools.contains("\"tools\""))
    }

    @Test
    fun `流式 tool_calls 分片聚合成流末 ToolCalls 事件`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"mcp__srv001__get_time","arguments":""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"tz\":"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"Asia/Shanghai\"}"}}]}}]}

                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """.trimIndent(),
            ),
        )
        val events = client().streamEvents("s", emptyList(), tools = dummyTools()).toList()
        val toolEvents = events.filterIsInstance<StreamEvent.ToolCalls>()
        assertEquals("ToolCalls 事件应恰好一次", 1, toolEvents.size)
        val calls = toolEvents[0].calls
        assertEquals(
            listOf(PendingToolCall("call_1", "mcp__srv001__get_time", "{\"tz\":\"Asia/Shanghai\"}")),
            calls,
        )
    }

    @Test
    fun `正文与 tool_calls 同轮共存 先出 Delta 再出 ToolCalls`() = runBlocking {
        // 模型可能先说半句话再决定调工具——两个事件都要在
        server.enqueue(
            MockResponse().setBody(
                """
                data: {"choices":[{"delta":{"content":"我查一下"}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"f","arguments":"{}"}}]}}]}

                data: [DONE]
                """.trimIndent(),
            ),
        )
        val events = client().streamEvents("s", emptyList(), tools = dummyTools()).toList()
        assertEquals("我查一下", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
        assertEquals(1, events.filterIsInstance<StreamEvent.ToolCalls>().size)
    }

    @Test
    fun `extraMessages 追加在 messages 数组尾部`() = runBlocking {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))
        val extra = listOf(
            JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                .put("tool_calls", JSONArray()),
            JSONObject().put("role", "tool").put("tool_call_id", "c1").put("content", "结果"),
        )
        client().streamEvents("s", emptyList(), tools = dummyTools(), extraMessages = extra).toList()
        val body = server.takeRequest().body.readUtf8()
        val messages = JSONObject(body).getJSONArray("messages")
        // system + 两条 extra
        assertEquals(3, messages.length())
        assertEquals("tool", messages.getJSONObject(2).getString("role"))
        assertEquals("c1", messages.getJSONObject(2).getString("tool_call_id"))
    }

    @Test
    fun `tools 探测 200 通过 400 带回服务端原话`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"hi"}}]}"""))
        assertNull(client().probeToolSupport())
        // 探测请求必须真的带 tools 字段（否则探了个寂寞）
        assertTrue(server.takeRequest().body.readUtf8().contains("\"tools\""))

        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"tools is not supported"}}"""),
        )
        val err = client().probeToolSupport()
        assertNotNull(err)
        assertTrue("应带服务端原话：$err", err!!.contains("tools is not supported"))
    }
}
