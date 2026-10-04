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
 * LLM 返回预设 JSON → 引擎应把 facts/insights 落库、推进水位线；同名 fact 覆盖而非新增。
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
        val json = "{\"facts\":[{\"key\":\"作息\",\"value\":\"$factValue\"}]," +
            "\"insights\":[{\"title\":\"作息趋于规律\",\"detail\":\"连续多日 23 点前入睡，睡眠习惯正在建立\"}]}"
        val payload = "data: {\"choices\":[{\"delta\":{\"content\":" +
            JSONObject.quote(json) + "}}]}\n\ndata: [DONE]\n\n"
        server.enqueue(MockResponse().setBody(payload))
    }

    private fun enqueueExtractionWithSuggestion(title: String, daily: Boolean = true) {
        val json = "{\"facts\":[],\"insights\":[]," +
            "\"taskSuggestions\":[{\"title\":\"$title\",\"note\":\"由对话提炼\",\"daily\":$daily}]}"
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
        assertEquals(0, summary.newFacts + summary.newInsights + summary.newSuggestions)
    }

    @Test
    fun `force 提炼落库 facts 与 insights 并推进水位线`() = runBlocking {
        insertTurn()
        val ids = app.db.personaDao().observeAll().first().map { it.id }
        println("menghuan-test: personaIds=$ids")
        enqueueExtraction()

        app.extractionEngine.extractAll()

        println("menghuan-test: facts=${app.db.profileFactDao().observeAll().first().map { it.key }}")
        val fact = app.db.profileFactDao().getByKey("作息")
        assertEquals("通常 23:00 前入睡", fact?.value)
        assertEquals("ai", fact?.source)

        val insights = app.db.aiInsightDao().observeLatest().first()
        assertEquals(1, insights.size)
        assertEquals("作息趋于规律", insights[0].title)

        assertTrue(app.settingsRepository.extractionWatermark(1L) > 0)

        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue("增量消息应进入 prompt", body.contains("23 点前就睡"))
    }

    @Test
    fun `同名 fact 覆盖原条目且新增 insight 去重`() = runBlocking {
        insertTurn()
        enqueueExtraction()
        app.extractionEngine.extractAll()

        // 第二轮：同样的 fact key（值更新）+ 同标题 insight
        app.db.messageDao().insert(
            ChatMessage(conversationId = 1, isFromUser = true, content = "现在改成 22:30 睡了。")
        )
        enqueueExtraction(factValue = "22:30 前入睡")
        app.extractionEngine.extractAll()

        val facts = app.db.profileFactDao().observeAll().first()
        assertEquals("同名 key 应覆盖而非新增", 1, facts.count { it.key == "作息" })
        assertEquals("22:30 前入睡", facts.first { it.key == "作息" }.value)

        val insights = app.db.aiInsightDao().observeLatest().first()
        assertEquals("同标题 insight 去重", 1, insights.count { it.title == "作息趋于规律" })
    }

    @Test
    fun `提炼产出任务建议进 hub 且同标题当日不重复建议`() = runBlocking {
        insertTurn()
        enqueueExtractionWithSuggestion("睡前把手机放客厅")
        app.extractionEngine.extractAll()

        val suggestions = app.taskSuggestionHub.suggestions.value[1L].orEmpty()
        assertEquals(listOf("睡前把手机放客厅"), suggestions.map { it.title })
        assertTrue("daily=true 应透传", suggestions.first().daily)

        // 第二轮：同 title 建议 → 当日已记录，不再重复出现
        app.db.messageDao().insert(
            ChatMessage(conversationId = 1, isFromUser = true, content = "我再想想手机放哪儿的事。")
        )
        enqueueExtractionWithSuggestion("睡前把手机放客厅")
        app.extractionEngine.extractAll()

        assertEquals("同 title 当日不重复建议", 1, app.taskSuggestionHub.suggestions.value[1L].orEmpty().size)
    }

    @Test
    fun `同名任务已存在时建议被过滤`() = runBlocking {
        // 用户已有同名任务：LLM 再建议同标题不应进 hub
        app.db.taskDao().upsert(
            com.menghuan.app.data.model.TaskItem(title = "睡前把手机放客厅")
        )
        insertTurn()
        enqueueExtractionWithSuggestion("睡前把手机放客厅")
        app.extractionEngine.extractAll()

        assertTrue(app.taskSuggestionHub.suggestions.value[1L].orEmpty().isEmpty())
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
}
