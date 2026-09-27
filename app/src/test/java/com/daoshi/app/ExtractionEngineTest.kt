package com.daoshi.app

import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.daoshi.app.data.ApiConfig
import com.daoshi.app.data.model.ChatMessage
import com.daoshi.app.data.settingsStore
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
@Config(application = MentorApp::class, sdk = [34])
class ExtractionEngineTest {

    private lateinit var app: MentorApp
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
            // extractAll 按 persona 逐会话提炼：先播一个主导师（id 自增为 1）
            app.db.personaDao().upsert(
                com.daoshi.app.data.model.Persona(
                    name = "阿布", title = "人生导师", avatarEmoji = "🖋️",
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

    @Test
    fun `force 提炼落库 facts 与 insights 并推进水位线`() = runBlocking {
        insertTurn()
        val ids = app.db.personaDao().observeAll().first().map { it.id }
        println("daoshi-test: personaIds=$ids")
        enqueueExtraction()

        app.extractionEngine.extractAll()

        println("daoshi-test: facts=${app.db.profileFactDao().observeAll().first().map { it.key }}")
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
}
