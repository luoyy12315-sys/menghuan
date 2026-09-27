package com.daoshi.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.daoshi.app.data.SettingsRepository
import com.daoshi.app.data.model.ChatMessage
import com.daoshi.app.ui.chat.ChatDrafts
import com.daoshi.app.ui.chat.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * ChatViewModel 状态机测试（未配置 API 的演示路径——无需真实网络）：
 * 发送 → 乐观上屏 → 模拟回复 → 双双落库；草稿随输入写/随发送清；流式期间重复发送被拒。
 *
 * 调度要点：Main 替换为 StandardTestDispatcher；VM 逻辑跑在虚拟时间上，
 * 但 Room/DataStore 的真实 IO 完成后会把 Main 任务重新排队——
 * 因此用 pumpUntil（advanceUntilIdle + 真实短轮询）等待条件成立。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MentorApp::class, sdk = [34])
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private fun inTest(block: suspend TestScope.() -> Unit) =
        kotlinx.coroutines.test.runTest(dispatcher) { block() }

    /** 推进虚拟时间 + 真实轮询，直到条件成立（VM 状态依赖真实 IO 的回环时用） */
    private suspend fun TestScope.pumpUntil(timeoutMs: Long = 15_000, cond: suspend () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!cond()) {
            advanceUntilIdle()
            if (cond()) return
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("pumpUntil 超时（${timeoutMs}ms）")
            }
            Thread.sleep(25)
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(personaId: Long = 1L): ChatViewModel {
        val app = ApplicationProvider.getApplicationContext<MentorApp>()
        // 每个测试注入独立 DataStore：委托单例会跨 Robolectric 测试残留（坑之七）
        val settings = SettingsRepository(
            app,
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            ) {
                File(app.filesDir, "test-settings-${UUID.randomUUID()}.preferences_pb")
            },
        )
        return ChatViewModel(
            app.personaRepository,
            settings,
            app.db,
            app.extractionEngine,
            personaId,
        )
    }

    @Test
    fun `发送后走模拟回复路径且用户消息与回复都落库`() = inTest {
        val app = ApplicationProvider.getApplicationContext<MentorApp>()
        val viewModel = vm()

        viewModel.onInputChange("今晚想早点睡")
        viewModel.send()
        // 先等状态包含 mock（cancel 会终止观察者，务必在 cancel 前完成状态断言）
        pumpUntil { viewModel.uiState.value.messages.any { !it.isFromUser && it.content.contains("记下了") } }
        pumpUntil { !viewModel.uiState.value.isStreaming }
        viewModel.viewModelScope.cancel()

        val state = viewModel.uiState.value
        assertFalse("流式应结束", state.isStreaming)
        assertTrue("回复应包含 mock 文案", state.messages.any { !it.isFromUser && it.content.contains("记下了") })

        pumpUntil {
            val db = app.db.messageDao().getAfterId(1L, 0L)
            db.any { it.isFromUser && it.content == "今晚想早点睡" } && db.any { !it.isFromUser }
        }
    }

    @Test
    fun `草稿随输入写随发送清`() = inTest {
        val viewModel = vm()

        viewModel.onInputChange("还没发出去的话")
        pumpUntil { ChatDrafts.get(1L) == "还没发出去的话" }

        viewModel.send()
        pumpUntil { ChatDrafts.get(1L).isEmpty() }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `空文本与流式期间的发送是 no-op`() = inTest {
        val app = ApplicationProvider.getApplicationContext<MentorApp>()
        val viewModel = vm()
        advanceUntilIdle()

        viewModel.send() // 空输入：no-op
        advanceUntilIdle()
        assertEquals(0, app.db.messageDao().getAll().size)

        viewModel.onInputChange("第一条")
        viewModel.send()
        // 不推进调度器：isStreaming 已同步置 true
        viewModel.send() // 流式中：no-op
        pumpUntil { app.db.messageDao().getAll().count { it.isFromUser } == 1 }
        pumpUntil { !viewModel.uiState.value.isStreaming } // 等 mock 回复完成
        advanceUntilIdle()
        val finalState = viewModel.uiState.value
        val dbDump = app.db.messageDao().getAll().map { "${if (it.isFromUser) "U" else "A"}:${it.content}" }
        assertTrue(
            "streaming=${finalState.isStreaming} msgs=${finalState.messages.map { it.content }} db=$dbDump",
            !finalState.isStreaming &&
                app.db.messageDao().getAll().count { it.isFromUser } == 1 &&
                finalState.messages.any { it.isFromUser && it.content == "第一条" } &&
                finalState.messages.any { !it.isFromUser && it.content.contains("记下了") },
        )
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `快捷打卡 sendText 走完整链路`() = inTest {
        val app = ApplicationProvider.getApplicationContext<MentorApp>()
        val viewModel = vm()
        advanceUntilIdle()

        viewModel.sendText("✅ 打卡：23:00 前放下手机")
        pumpUntil { app.db.messageDao().getAll().any { it.isFromUser && it.content == "✅ 打卡：23:00 前放下手机" } }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `持续观察 Room——外部写入实时上屏`() = inTest {
        val app = ApplicationProvider.getApplicationContext<MentorApp>()
        val viewModel = vm()
        advanceUntilIdle()

        // 模拟任务页勾选产生的打卡回执（外部写入）
        app.db.messageDao().insert(
            com.daoshi.app.data.model.ChatMessage(
                conversationId = 1L, isFromUser = true, content = "✅ 已完成打卡：外部写入",
            )
        )
        pumpUntil { viewModel.uiState.value.messages.any { it.content == "✅ 已完成打卡：外部写入" } }
        viewModel.viewModelScope.cancel()
    }
}
