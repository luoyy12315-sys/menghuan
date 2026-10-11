package com.menghuan.app

import com.menghuan.app.mcp.McpClient
import com.menghuan.app.mcp.McpException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 手撸 MCP Streamable HTTP 客户端的单测（P3-3，MockWebServer 纯 JVM）：
 * 握手与会话头、SSE 响应里捞 id 匹配的消息、分页、结果扁平化、两类失败。
 */
class McpClientTest {

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

    private fun client(token: String? = "tok") = McpClient(server.url("/mcp").toString(), token)

    private fun initResponse(sessionId: String? = "sess-1"): MockResponse {
        val resp = MockResponse().setBody(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"test-server","version":"0.1"}}}""",
        )
        if (sessionId != null) resp.setHeader("Mcp-Session-Id", sessionId)
        return resp
    }

    @Test
    fun `握手成功拿到服务器名片 且之后请求带回 session 与协议版本头`() = runBlocking {
        server.enqueue(initResponse())
        server.enqueue(MockResponse().setResponseCode(202)) // notifications/initialized
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"get_time","description":"报时","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}]}}""",
            ),
        )
        val c = client()
        val info = c.initialize()
        assertEquals("test-server", info.name)
        assertEquals("0.1", info.version)
        assertEquals("2025-06-18", info.protocolVersion)

        val tools = c.listTools()
        assertEquals(1, tools.size)
        assertEquals("get_time", tools[0].name)
        assertEquals("报时", tools[0].description)
        assertTrue(tools[0].readOnly)

        val listReq = server.takeRequest() // initialize
        server.takeRequest() // notifications/initialized
        val toolsReq = server.takeRequest()
        assertEquals("Bearer tok", listReq.getHeader("Authorization"))
        assertEquals("sess-1", toolsReq.getHeader("Mcp-Session-Id"))
        assertEquals("2025-06-18", toolsReq.getHeader("MCP-Protocol-Version"))
        assertTrue(
            toolsReq.getHeader("Accept")!!.contains("text/event-stream"),
        )
    }

    @Test
    fun `SSE 响应跳过通知 捞 id 匹配的那条`() = runBlocking {
        server.enqueue(initResponse(sessionId = null))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    """
                    event: message
                    data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}

                    event: message
                    data: {"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"ping"}]}}

                    """.trimIndent(),
                ),
        )
        val c = client()
        c.initialize()
        val tools = c.listTools()
        assertEquals(listOf("ping"), tools.map { it.name })
    }

    @Test
    fun `tools list 跟随 nextCursor 翻页`() = runBlocking {
        server.enqueue(initResponse(sessionId = null))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"t1"}],"nextCursor":"page2"}}""",
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"t2"}]}}""",
            ),
        )
        val c = client()
        c.initialize()
        val tools = c.listTools()
        assertEquals(listOf("t1", "t2"), tools.map { it.name })
        server.takeRequest(); server.takeRequest(); server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("page2"))
    }

    @Test
    fun `callTool 文本扁平化 多段拼接 参数原样发出`() = runBlocking {
        server.enqueue(initResponse(sessionId = null))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}],"isError":false}}""",
            ),
        )
        val c = client()
        c.initialize()
        val result = c.callTool("do_thing", """{"x":1}""")
        assertEquals("第一段\n第二段", result.text)
        assertTrue(!result.isError)
        server.takeRequest(); server.takeRequest()
        val callBody = server.takeRequest().body.readUtf8()
        assertTrue(callBody.contains("tools/call"))
        assertTrue(callBody.contains("do_thing"))
        // 参数以 **嵌套 JSON 对象** 发出（org.json 序列化时不转义内层引号）：
        // 请求体里是 `"arguments":{"x":1}`，所以断 `"x":1` 而不是 `\"x\":1`。
        // （v0.84.0：原断言写的是 `\\\"x\\\":1`，把参数当成了"再套一层 JSON 字符串"，恒不成立。）
        assertTrue("参数没按嵌套对象原样发出：$callBody", callBody.contains("\"x\":1"))
    }

    @Test
    fun `callTool 参数不是合法 JSON 时按调用失败返回 不发请求`() = runBlocking {
        server.enqueue(initResponse(sessionId = null))
        server.enqueue(MockResponse().setResponseCode(202))
        val c = client()
        c.initialize()
        val result = c.callTool("f", "{坏掉的JSON")
        assertTrue(result.isError)
        assertEquals(2, server.requestCount) // 只有 initialize + initialized 两条
    }

    @Test
    fun `JSON-RPC 错误抛 McpException 带 rpcCode`() = runBlocking {
        server.enqueue(initResponse(sessionId = null))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":2,"error":{"code":-32601,"message":"Method not found"}}""",
            ),
        )
        val c = client()
        c.initialize()
        val err = runCatching { c.listTools() }.exceptionOrNull()
        assertTrue("应为 McpException，实为 $err", err is McpException)
        assertEquals(-32601, (err as McpException).rpcCode)
        assertTrue(err.message!!.contains("Method not found"))
    }

    @Test
    fun `HTTP 非 2xx 抛 McpException 带 httpCode 与服务端原话`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad token"}}"""))
        val err = runCatching { client().initialize() }.exceptionOrNull()
        assertTrue(err is McpException)
        assertEquals(401, (err as McpException).httpCode)
        assertTrue(err.message!!.contains("bad token"))
    }

    @Test
    fun `未握手就调用直接拒绝`() = runBlocking {
        val err = runCatching { client().listTools() }.exceptionOrNull()
        assertTrue(err is McpException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `extractResponse 三种形状`() {
        // JSON 单体
        assertNotNull(McpClient.extractResponse("application/json", """{"id":1,"result":{}}""", 1))
        assertNull(McpClient.extractResponse("application/json", """{"id":9,"result":{}}""", 1))
        // JSON 数组（批量响应的网关）
        assertNotNull(
            McpClient.extractResponse(
                "application/json",
                """[{"id":9,"result":{}},{"id":1,"result":{}}]""",
                1,
            ),
        )
        // SSE：多块、id 不匹配的跳过、data 多行拼接
        val sse = """
            data: {"id":9,"result":{}}

            data: {"id":1,
            data: "result":{}}

            """.trimIndent()
        assertNotNull(McpClient.extractResponse("text/event-stream", sse, 1))
        // 空体（202）
        assertNull(McpClient.extractResponse("application/json", "", 1))
    }
}
