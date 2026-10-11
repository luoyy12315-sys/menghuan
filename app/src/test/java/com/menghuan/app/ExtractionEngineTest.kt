package com.menghuan.app

import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.ApiConfig
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.settingsStore
import kotlinx.coroutines.flow.first
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
import org.robolectric.annotation.Config

/**
 * 提炼引擎集成测试（Robolectric + MockWebServer）：
 * LLM 返回预设 JSON → 引擎应把 facts 落库、推进水位线；同名 fact 覆盖而非新增。
 *
 * ⚠️ **v0.88.0 职责收窄**：引擎现在**只产 facts**。insights 挪去 [InsightEngine]、
 * taskSuggestions 挪去 [TaskSuggestionMarker]（聊天链标记驱动），
 * 所以本文件里"提炼产出洞察/任务建议"的用例整组删除，改由
 * `InsightEngineTest` / `TaskSuggestionMarkerTest` 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MenghuanApp::class, sdk = [34])
class ExtractionEngineTest {

    private lateinit var app: MenghuanApp
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        runBlocking {
            app = ApplicationProvider.getApplicationContext()
            server = MockWebServer()
            server.start()
            // settingsStore 委托是进程级单例：跨 Robolectric 测试残留（Robolectric 坑之七），
            // 必须显式清空，否则上一测试的水位线会让本测试"无增量"而跳过
            app.settingsStore.edit { it.clear() }
            // JVM 无 Keystore → 走明文回退路径（v0.2.3 设计），恰好覆盖回退分支
            app.settingsRepository.saveApiConfig(
                ApiConfig(baseUrl = server.url("/v1").toString(), apiKey = "test-key", model = "test-model")
            )
            // extractAll 按 persona 逐会话提炼：先播一个主导师
            // 显式 id=1：启动补建的「应用助手」可能先占 id 1，显式 id 保证会话 id 与断言对齐
            app.db.personaDao().upsert(
                com.menghuan.app.data.model.Persona(
                    id = 1, name = "阿布", title = "人生导师", avatarEmoji = "🖋️",
                    systemPrompt = "你是用户的个人全能指导导师。", isBuiltIn = true,
                )
            )
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun insertTurn() = runBlocking {
        app.db.messageDao().insert(
            ChatMessage(conversationId = 1, isFromUser = true, content = "我最近每天 23 点前就睡了，坚持得不错。")
        )
        app.db.messageDao().insert(
            ChatMessage(conversationId = 1, isFromUser = false, content = "很好，保持。")
        )
    }

    private fun enqueueExtraction(factValue: String = "通常 23:00 前入睡") {
        val json = "{\"facts\":[{\"key\":\"作息\",\"value\":\"$factValue\"}]}"
        val payload = "data: {\"choices\":[{\"delta\":{\"content\":" +
            JSONObject.quote(json) + "}}]}\n\ndata: [DONE]\n\n"
        server.enqueue(MockResponse().setBody(payload))
    }

    /** v0.34.0：内置应用助手是使用指南会话，不参与提炼也不该发 LLM 请求 */
    @Test
    fun `应用助手会话不参与提炼`() = runBlocking {
        // 清掉夹具人设，只留一个内置应用助手
        app.db.personaDao().observeAll().first().forEach { app.db.personaDao().deleteById(it.id) }
        val assistantId = app.db.personaDao().upsert(
            com.menghuan.app.data.model.Persona(
                name = com.menghuan.app.data.ASSISTANT_PERSONA_NAME,
                title = "使用指南 · 答疑", avatarEmoji = "🤖",
                systemPrompt = "你是应用助手。", isBuiltIn = true,
            )
        )
        app.db.messageDao().insert(
            ChatMessage(conversationId = assistantId, isFromUser = true, content = "这个 App 怎么导出数据？")
        )
        app.db.messageDao().insert(
            ChatMessage(conversationId = assistantId, isFromUser = false, content = "我的 → 设置 → 数据与隐私 → 导出全部数据。")
        )

        // 不发任何 mock 响应：若引擎误发请求会挂起等超时，requestCount 断言即失败
        val summary = app.extractionEngine.extractAll()

        assertEquals(0, server.requestCount)
        assertTrue(app.db.profileFactDao().observeAll().first().isEmpty())
        assertEquals(0, summary.newFacts + summary.updatedFacts)
    }

    @Test
    fun `force 提炼落库 facts 并推进水位线`() = runBlocking {
        insertTurn()
        val ids = app.db.personaDao().observeAll().first().map { it.id }
        println("menghuan-test: personaIds=$ids")
        enqueueExtraction()

        app.extractionEngine.extractAll()

        println("menghuan-test: facts=${app.db.profileFactDao().observeAll().first().map { it.key }}")
        val fact = app.db.profileFactDao().getByKey("作息")
        assertEquals("通常 23:00 前入睡", fact?.value)
        assertEquals("ai", fact?.source)

        assertTrue(app.settingsRepository.extractionWatermark(1L) > 0)

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue("增量消息应进入 prompt", body.contains("23 点前就睡"))
    }

    @Test
    fun `同名 fact 覆盖原条目而非新增`() = runBlocking {
        insertTurn()
        enqueueExtraction()
        app.extractionEngine.extractAll()

        // 第二轮：同样的 fact key（值更新）
        app.db.messageDao().insert(
            ChatMessage(conversationId = 1, isFromUser = true, content = "现在改成 22:30 睡了。")
        )
        enqueueExtraction(factValue = "22:30 前入睡")
        app.extractionEngine.extractAll()

        val facts = app.db.profileFactDao().observeAll().first()
        assertEquals("同名 key 应覆盖而非新增", 1, facts.count { it.key == "作息" })
        assertEquals("22:30 前入睡", facts.first { it.key == "作息" }.value)
    }

    /**
     * v0.88.0：提炼链路**不再往 ai_insights 写任何东西**。
     *
     * 这条用例是"拆开记忆与洞察"这件事的守门人 —— 只要有人把洞察写回提炼链，
     * 这里立刻变红。洞察现在只由 [InsightEngine] 在 23:00 落库。
     */
    @Test
    fun `提炼不再产出洞察`() = runBlocking {
        insertTurn()
        // 故意让模型吐 insights：解析器应无视，引擎也不该写库
        val json = "{\"facts\":[{\"key\":\"作息\",\"value\":\"22:30 前入睡\"}]," +
            "\"insights\":[{\"title\":\"作息趋于规律\",\"detail\":\"说明\"}]}"
        val payload = "data: {\"choices\":[{\"delta\":{\"content\":" +
            JSONObject.quote(json) + "}}]}\n\ndata: [DONE]\n\n"
        server.enqueue(MockResponse().setBody(payload))

        app.extractionEngine.extractAll()

        assertTrue("提炼不应写 ai_insights", app.db.aiInsightDao().latest().isEmpty())
        assertEquals("22:30 前入睡", app.db.profileFactDao().getByKey("作息")?.value)
    }

    @Test
    fun `提炼 prompt 注入近 7 天任务完成情况`() = runBlocking {
        val taskId = app.db.taskDao().upsert(
            com.menghuan.app.data.model.TaskItem(title = "每日阅读", repeatDaily = true)
        )
        app.db.taskCompletionDao().insert(
            com.menghuan.app.data.model.TaskCompletion(
                taskId = taskId,
                day = java.time.LocalDate.now().toEpochDay(),
            )
        )
        insertTurn()
        enqueueExtraction()
        app.extractionEngine.extractAll()

        val body = server.takeRequest().body.readUtf8()
        assertTrue("任务完成情况应注入 prompt", body.contains("近 7 天任务完成情况"))
        assertTrue("应带任务名与次数", body.contains("每日阅读（1 次）"))
    }

    /**
     * v0.85.0：提炼时必须把**已有的记忆条目名**给模型（用户点名「AI 的记忆老是重复的」）。
     *
     * 根因是模型不知道你记过什么，每次重新起名（工作/职业/工作情况），
     * 而合并是按 key 精确匹配的 —— 措辞一变就成新条目。
     * 这条用例钉住"现有条目名确实进了 prompt"，否则重复问题会悄悄回归。
     */
    @Test
    fun `提炼 prompt 注入已有记忆的条目名`() = runBlocking {
        // 先放一条已有记忆（personaId=1 即 insertTurn 用的会话角色）
        app.db.profileFactDao().upsert(
            com.menghuan.app.data.model.ProfileFact(
                key = "工作",
                value = "在一家互联网公司做产品",
                personaId = 1L,
            )
        )
        insertTurn()
        enqueueExtraction()
        app.extractionEngine.extractAll()

        val body = server.takeRequest().body.readUtf8()
        assertTrue("应有个已有条目名那一段", body.contains("【已经记住的条目名】"))
        assertTrue("应带已有条目的名字", body.contains("工作"))
        assertTrue("应要求同义复用、不要新建", body.contains("复用那个名字"))
    }

    /**
     * v0.86.0：**提炼门槛真正接上了**。
     *
     * 以前 `extractEvery` 是死代码（定义但从未被读取），于是每插一条消息就提炼一次 ——
     * 一次提炼要重发完整框架提示词 + 任务清单 + 现有记忆条目名，成本高得离谱。
     * 现在默认攒够 6 轮（= 6 条用户发言）才提炼一次。
     *
     * 这条用例钉住"轮数不够就不发请求"：`maybeExtract` 走的就是 `force = false` 这条路径。
     */
    @Test
    fun `轮数不足时不触发提炼`() = runBlocking {
        insertTurn() // 只攒了 1 轮（默认门槛 6）
        enqueueExtraction()

        // maybeExtract 是异步 fire-and-forget（内部 scope.launch），直接调 runExtract 的等价路径：
        // 这里用 extractAll 的反面 —— 手动提炼才 force，自动路径必须被门槛挡住。
        app.extractionEngine.maybeExtract(1L)
        // 给 scope 里的协程一点时间；若门槛失效，请求会立刻打到 MockWebServer
        kotlinx.coroutines.delay(600)

        assertEquals("轮数不够就不该发请求", 0, server.requestCount)
        assertTrue(app.db.profileFactDao().observeAll().first().isEmpty())
    }

    /**
     * v0.86.0：**攒够轮数就提炼**（门槛不是"永不提炼"）。
     * 默认 6 轮 → 插 6 条用户发言 + 若干 AI 回复，第 6 条落下就该发一次请求。
     */
    @Test
    fun `攒够配置的轮数就提炼`() = runBlocking {
        repeat(6) { i ->
            app.db.messageDao().insert(
                ChatMessage(conversationId = 1, isFromUser = true, content = "第 ${i + 1} 条用户发言")
            )
            app.db.messageDao().insert(
                ChatMessage(conversationId = 1, isFromUser = false, content = "第 ${i + 1} 条回复")
            )
        }
        enqueueExtraction()

        app.extractionEngine.maybeExtract(1L)
        // 轮询等结果，避免固定 delay 在慢机器上偶发失败
        var waited = 0
        while (server.requestCount == 0 && waited < 5_000) {
            kotlinx.coroutines.delay(50)
            waited += 50
        }

        assertEquals("攒够 6 轮应发一次请求", 1, server.requestCount)
        assertEquals("通常 23:00 前入睡", app.db.profileFactDao().getByKey("作息")?.value)
    }

    /**
     * v0.86.0：**手动提炼（force）无视门槛**。
     *
     * 数据页那颗「提炼」按钮走 `extractAll()` → `force = true`。用户点了就应该立刻跑，
     * 不能被"才聊了 2 轮"挡住 —— 否则这个按钮在冷启动后会看起来是坏的。
     */
    @Test
    fun `force 提炼无视轮数门槛`() = runBlocking {
        insertTurn() // 只有 1 轮，远低于默认门槛 6
        enqueueExtraction()

        app.extractionEngine.extractAll()

        assertEquals("force 应无视门槛直接请求", 1, server.requestCount)
        assertEquals("通常 23:00 前入睡", app.db.profileFactDao().getByKey("作息")?.value)
    }

    /**
     * v0.86.0：门槛**按角色可配**。
     *
     * 把提炼间隔设成 2 轮，2 条用户发言就该提炼（用默认 6 是跑不起来的）。
     * 这条同时守护 `SettingsRepository.extractEveryTurns` 的读写与引擎侧读取没接错键。
     *
     * 写法上刻意**先把两条都插好再触发** —— 分两次调用一次 `maybeExtract` 会踩到
     * "第一轮把水位线推到第 2 条，第二次跑时增量只有 1 条用户发言，又不够 2 轮"的逻辑坑，
     * 那是引擎的正确行为，不是本用例要测的东西。
     */
    @Test
    fun `提炼间隔可按角色配置`() = runBlocking {
        app.settingsRepository.setExtractEveryTurns(1L, 2)
        assertEquals(2, app.settingsRepository.extractEveryTurns(1L).first())

        // 只有 1 轮 → 不够门槛 2
        insertTurn()
        enqueueExtraction()
        app.extractionEngine.maybeExtract(1L)
        kotlinx.coroutines.delay(600)
        assertEquals("1 轮 < 门槛 2，不该发请求", 0, server.requestCount)

        // 再补 1 轮（累计 2 条用户发言都还没被提炼过，水位线仍在 0）
        app.db.messageDao().insert(
            ChatMessage(conversationId = 1, isFromUser = true, content = "再加一条用户发言。")
        )
        app.extractionEngine.maybeExtract(1L)
        var waited = 0
        while (server.requestCount == 0 && waited < 5_000) {
            kotlinx.coroutines.delay(50)
            waited += 50
        }
        assertEquals("攒够 2 轮应发一次请求", 1, server.requestCount)
    }

    /**
     * v0.86.0：**上下文窗口按角色可配**，且没设过的角色读默认值。
     * 这两个键必须能读能写、范围钳制生效，否则设置页填了等于没填。
     */
    @Test
    fun `上下文窗口可按角色配置且默认一万二`() = runBlocking {
        assertEquals(12_000, app.settingsRepository.historyCharBudget(1L).first())

        app.settingsRepository.setHistoryCharBudget(1L, 30_000)
        assertEquals(30_000, app.settingsRepository.historyCharBudget(1L).first())
        // 另一个角色不受影响（按角色存的意义就在这儿）
        assertEquals(12_000, app.settingsRepository.historyCharBudget(2L).first())

        // 超范围钳制到边界
        app.settingsRepository.setHistoryCharBudget(1L, 999_999)
        assertEquals(200_000, app.settingsRepository.historyCharBudget(1L).first())

        // 改回默认值 → 键被摘掉，回到"没设过"的状态（改默认值不必迁移数据）
        app.settingsRepository.setHistoryCharBudget(1L, 12_000)
        assertEquals(12_000, app.settingsRepository.historyCharBudget(1L).first())
    }
}
