package com.menghuan.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.mcp.McpClient
import com.menghuan.app.mcp.McpManager
import com.menghuan.app.mcp.McpServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/**
 * McpManager 聚合与路由的单测（P3-3，Robolectric + MockWebServer）：
 * 命名空间聚合、路由执行、停用服务器跳过、未知工具不炸链路。
 */
@RunWith(RobolectricTestRunner::class)
class McpManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        // 独立 DataStore：委托单例会跨测试残留（与 ChatViewModelTest 同款处理）
        settings = SettingsRepository(
            app,
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            ) {
                File(app.filesDir, "test-mcp-${UUID.randomUUID()}.preferences_pb")
            },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun manager() = McpManager(settings) { url, token -> McpClient(url, token) }

    private fun enqueueHandshake() {
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"t","version":"1"}}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(202))
    }

    @Test
    fun `聚合加命名空间 路由执行回到正确的服务器工具`() = runBlocking {
        settings.saveMcpServer(
            McpServerConfig("srv001", "工具箱", server.url("/mcp").toString()),
        )
        enqueueHandshake()
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"get.time","description":"报时","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}},{"name":"delete_file","description":"删文件"}]}}""",
            ),
        )
        val m = manager()
        val tools = m.resolveTools()
        assertEquals(2, tools.size)
        // 工具名的点号被消毒成下划线，命名空间前缀是服务器 id（显示名是中文也不进工具名）
        assertEquals("mcp__srv001__get_time", tools[0].openAiName)
        assertTrue(tools[0].readOnly)
        assertTrue(!tools[1].readOnly)

        // buildToolsJson 的形状 = OpenAI function 格式
        val json = m.buildToolsJson(tools)
        assertEquals(2, json.length())
        val fn = json.getJSONObject(0).getJSONObject("function")
        assertEquals("mcp__srv001__get_time", fn.getString("name"))
        assertEquals("object", fn.getJSONObject("parameters").getString("type"))

        // 路由执行：回的是包过不可信层的结果
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"现在九点"}]}}""",
            ),
        )
        val result = m.execute("mcp__srv001__get_time", "{}")
        assertTrue(result.contains("不是用户指令"))
        assertTrue(result.contains("现在九点"))
        server.takeRequest(); server.takeRequest(); server.takeRequest()
        val callBody = server.takeRequest().body.readUtf8()
        assertTrue(callBody.contains("get.time")) // 路由回服务器的**原始**工具名
    }

    @Test
    fun `停用的服务器不进聚合 配置原样保留`() = runBlocking {
        settings.saveMcpServer(
            McpServerConfig("srv001", "工具箱", server.url("/mcp").toString(), enabled = false),
        )
        val m = manager()
        assertTrue(m.resolveTools().isEmpty())
        assertEquals(0, server.requestCount) // 停用的连握手都不发
        assertEquals(1, settings.mcpServers().size) // 配置一个标点不动
        assertTrue(!settings.mcpServers()[0].enabled)
    }

    @Test
    fun `服务器连不上本轮跳过 不影响其它服务器也不炸`() = runBlocking {
        settings.saveMcpServer(McpServerConfig("dead01", "死的", "http://127.0.0.1:1/mcp"))
        settings.saveMcpServer(
            McpServerConfig("live01", "活的", server.url("/mcp").toString()),
        )
        enqueueHandshake()
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"ping"}]}}""",
            ),
        )
        val m = manager()
        val tools = m.resolveTools()
        assertEquals(listOf("mcp__live01__ping"), tools.map { it.openAiName })
    }

    @Test
    fun `未知工具名返回错误文本而不是抛异常`() = runBlocking {
        val result = manager().execute("mcp__nope__ghost", "{}")
        assertTrue(result.contains("未知工具"))
        assertTrue(result.contains("不是用户指令")) // 错误文本同样包不可信层
    }

    @Test
    fun `消毒后撞名的工具追加序号区分 路由不串`() = runBlocking {
        settings.saveMcpServer(
            McpServerConfig("srv001", "工具箱", server.url("/mcp").toString()),
        )
        enqueueHandshake()
        server.enqueue(
            MockResponse().setBody(
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"a.b"},{"name":"a:b"}]}}""",
            ),
        )
        val m = manager()
        val tools = m.resolveTools()
        val names = tools.map { it.openAiName }
        assertEquals(2, names.toSet().size) // 两个名字必须能区分开
        assertTrue(names[0].startsWith("mcp__srv001__a_b"))
    }
}
