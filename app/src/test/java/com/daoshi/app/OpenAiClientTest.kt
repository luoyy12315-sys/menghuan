package com.daoshi.app

import com.daoshi.app.llm.ApiException
import com.daoshi.app.llm.OpenAiClient
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
}
