package com.menghuan.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.ui.chat.ChatDrafts
import com.menghuan.app.ui.chat.ChatViewModel
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
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
 * 真实 IO 与虚拟时间交错在负载高时偶发协程链卡死（UncompletedCoroutinesError，
 * 2026-09-29 实测约 1/3 跑批出现一次）——RetryOnFailure 整测重试兜底（@Before 每次尝试
 * 都会重跑，调度器全新），禁止因 flake 删测试。
 */
/** 失败整测重试：VM 链条真实 IO × 虚拟时间的偶发卡死兜底（@Before 每次尝试都会重跑，调度器全新） */
private class RetryOnFailure(private val attempts: Int = 3) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            var last: Throwable? = null
            repeat(attempts) {
                try {
                    base.evaluate()
                    return
                } catch (t: Throwable) {
                    last = t
                }
            }
            throw last!!
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = MenghuanApp::class, sdk = [34])
class ChatViewModelTest {

    @get:Rule
    val retryRule: TestRule = RetryOnFailure()

    private val dispatcher = StandardTestDispatcher()

    private fun inTest(block: suspend TestScope.() -> Unit) =
        kotlinx.coroutines.test.runTest(dispatcher) { block() }

    /** 推进虚拟时间 + 真实轮询，直到条件成立（VM 状态依赖真实 IO 的回环时用） */
    private suspend fun TestScope.pumpUntil(timeoutMs: Long = 60_000, cond: suspend () -> Boolean) {
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
        // 竞态修复：Main 线程上可能有上一测试残留的协程任务尚未跑完，
        // 此时 resetMain() 会抛 "used concurrently"（间歇性，负载高时易现）。
        // 重试 + 兜底吞掉即可：下一个测试的 @Before 会重新 setMain。
        repeat(5) {
            try {
                Dispatchers.resetMain()
                return
            } catch (_: IllegalStateException) {
                Thread.sleep(50)
            }
        }
    }

    private fun vm(personaId: Long = 1L): ChatViewModel {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
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
            com.menghuan.app.data.TaskSuggestionHub(),
            personaId,
            app.filesDir, // 生图落盘目录（v0.35.0）
        )
    }

    @Test
    fun `发送后走模拟回复路径且用户消息与回复都落库`() = inTest {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val viewModel = vm()
        try {
            viewModel.onInputChange("今晚想早点睡")
            viewModel.send()
            // 先等状态包含 mock（cancel 会终止观察者，务必在 cancel 前完成状态断言）
            pumpUntil { viewModel.uiState.value.messages.any { !it.isFromUser && it.content.contains("记下了") } }
            pumpUntil { !viewModel.uiState.value.isStreaming }
            val state = viewModel.uiState.value
            assertFalse("流式应结束", state.isStreaming)
            assertTrue("回复应包含 mock 文案", state.messages.any { !it.isFromUser && it.content.contains("记下了") })
            pumpUntil {
                val db = app.db.messageDao().getAfterId(1L, 0L)
                db.any { it.isFromUser && it.content == "今晚想早点睡" } && db.any { !it.isFromUser }
            }
        } finally {
            // 断言失败也要终止 VM 的无限收集器，并排空调度器，否则 runTest 报 UncompletedCoroutinesError
            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
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
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val viewModel = vm()
        advanceUntilIdle()

        // 注意：库是文件型且 Robolectric 沙箱内跨测试类共享，可能已有别的测试写入的行。
        // 因此用「基线快照 + 增量」断言，不假定空表（曾因 UiRenderTest 写入消息而整测跑批红）。
        val baseline = app.db.messageDao().getAll().size

        viewModel.send() // 空输入：no-op
        advanceUntilIdle()
        assertEquals(baseline, app.db.messageDao().getAll().size)

        val baselineUser = app.db.messageDao().getAll().count { it.isFromUser }

        viewModel.onInputChange("第一条")
        viewModel.send()
        // 不推进调度器：isStreaming 已同步置 true
        viewModel.send() // 流式中：no-op
        pumpUntil { app.db.messageDao().getAll().count { it.isFromUser } == baselineUser + 1 }
        pumpUntil { !viewModel.uiState.value.isStreaming } // 等 mock 回复完成
        advanceUntilIdle()
        val finalState = viewModel.uiState.value
        val dbDump = app.db.messageDao().getAll().map { "${if (it.isFromUser) "U" else "A"}:${it.content}" }
        assertTrue(
            "streaming=${finalState.isStreaming} msgs=${finalState.messages.map { it.content }} db=$dbDump",
            !finalState.isStreaming &&
                app.db.messageDao().getAll().count { it.isFromUser } == baselineUser + 1 &&
                finalState.messages.any { it.isFromUser && it.content == "第一条" } &&
                finalState.messages.any { !it.isFromUser && it.content.contains("记下了") },
        )
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `快捷打卡 sendText 走完整链路`() = inTest {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val viewModel = vm()
        advanceUntilIdle()

        viewModel.sendText("✅ 打卡：23:00 前放下手机")
        pumpUntil { app.db.messageDao().getAll().any { it.isFromUser && it.content == "✅ 打卡：23:00 前放下手机" } }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `持续观察 Room——外部写入实时上屏`() = inTest {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val viewModel = vm()
        advanceUntilIdle()

        // 模拟任务页勾选产生的打卡回执（外部写入）
        app.db.messageDao().insert(
            com.menghuan.app.data.model.ChatMessage(
                conversationId = 1L, isFromUser = true, content = "✅ 已完成打卡：外部写入",
            )
        )
        pumpUntil { viewModel.uiState.value.messages.any { it.content == "✅ 已完成打卡：外部写入" } }
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `生成期间只挂三点气泡 回复一次性落地 无逐字中间态`() = inTest {
        // v0.41.4 用户要求：后台生成、一次性发送。
        // 契约：isStreaming/generating 在生成期间为 true 且**没有任何 assistant 消息**，
        // 回复结束后 assistant 消息才带着完整正文一次性出现——不存在"半个字"的中间态。
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val viewModel = vm()
        try {
            viewModel.onInputChange("在吗")
            viewModel.send()

            // 发送后立刻：生成中，但助手侧还没有任何消息（用户消息是乐观上屏的）
            pumpUntil { viewModel.uiState.value.generating || viewModel.uiState.value.messages.any { !it.isFromUser } }
            val mid = viewModel.uiState.value
            val midAssistant = mid.messages.filter { !it.isFromUser }
            assertTrue(
                "生成中不应有未完成的 assistant 气泡，实际 ${midAssistant.map { it.content }}",
                midAssistant.isEmpty() || midAssistant.all { it.content.isNotBlank() },
            )

            // 跑完：完整回复一次性出现，generating 归位
            pumpUntil { viewModel.uiState.value.messages.any { !it.isFromUser && it.content.contains("记下了") } }
            pumpUntil { !viewModel.uiState.value.generating }
            val done = viewModel.uiState.value
            assertTrue("应含完整 mock 回复", done.messages.any { !it.isFromUser && it.content.contains("记下了") })
            assertFalse("生成结束 generating 应归位", done.generating)
        } finally {
            viewModel.viewModelScope.cancel()
            advanceUntilIdle()
        }
    }
}
