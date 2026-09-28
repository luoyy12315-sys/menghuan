package com.daoshi.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.daoshi.app.data.SettingsRepository
import com.daoshi.app.data.model.ChatMessage
import com.daoshi.app.ui.chat.ChatListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * 会话列表删除语义（Robolectric）：
 * - 「删除会话」（微信式）= 清空消息 + personas.hidden；列表只隐藏 hidden 且无消息的行
 *   （hidden 但有新消息的会话恢复显示）。
 * - 「删除人设」（v0.12.0）= 物理删除，级联清空该会话消息。
 *
 * 调度要点同 ChatViewModelTest：Main = StandardTestDispatcher + pumpUntil；
 * 裸 runBlocking 会让 stateIn 上游挂在 Robolectric 已暂停的 Main looper 上永不恢复（实测挂死）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MentorApp::class, sdk = [34])
class ChatListViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private fun inTest(block: suspend TestScope.() -> Unit) =
        kotlinx.coroutines.test.runTest(dispatcher) { block() }

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

    /** stateIn(WhileSubscribed) 必须有订阅者上游才启动：先挂收集器再轮询 */
    private suspend fun <T> TestScope.pumpValue(flow: StateFlow<T>, cond: (T) -> Boolean): T {
        val job = launch { flow.collect {} }
        try {
            pumpUntil { cond(flow.value) }
        } finally {
            job.cancel()
        }
        return flow.value
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        repeat(5) {
            try {
                Dispatchers.resetMain()
                return
            } catch (_: IllegalStateException) {
                Thread.sleep(50)
            }
        }
    }

    private fun vm(app: MentorApp): ChatListViewModel {
        val settings = SettingsRepository(
            app,
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            ) { File(app.filesDir, "chatlist-${UUID.randomUUID()}.preferences_pb") },
        )
        return ChatListViewModel(app.personaRepository, settings, app.db)
    }

    @Test
    fun `会话列表过滤 hidden 且无消息的人设`() = inTest {
        val app = ApplicationProvider.getApplicationContext<MentorApp>()
        val viewModel = vm(app)
        try {
            // 先等种子上屏（seedOnce 在订阅后异步执行）
            pumpValue(viewModel.rows) { it.size == 3 }

            app.personaRepository.setHidden(1L, true) // 隐藏且无消息 → 从列表消失
            app.personaRepository.setHidden(3L, true) // 隐藏但有消息 → 新消息恢复显示
            app.db.messageDao().insert(
                ChatMessage(conversationId = 3L, isFromUser = false, content = "又熬夜了？")
            )

            val rows = pumpValue(viewModel.rows) { it.map { r -> r.persona.name } == listOf("严格教练", "苏格拉底") }
            assertEquals(listOf("严格教练", "苏格拉底"), rows.map { it.persona.name })
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `物理删除人设后列表与会话消息一同消失`() = inTest {
        val app = ApplicationProvider.getApplicationContext<MentorApp>()
        val viewModel = vm(app)
        try {
            pumpValue(viewModel.rows) { it.size == 3 } // 种子已播
            app.db.messageDao().insert(ChatMessage(conversationId = 1L, isFromUser = true, content = "历史消息"))

            val abu = app.personaRepository.getPersona(1L)!!
            app.personaRepository.delete(abu.id) // v0.12.0 物理删除（级联消息）

            pumpUntil { app.db.messageDao().getAfterId(1L, 0L).isEmpty() }
            val rows = pumpValue(viewModel.rows) { it.size == 2 }
            assertEquals(listOf("严格教练", "苏格拉底"), rows.map { it.persona.name })
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
