package com.menghuan.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.ui.chat.ChatListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
@Config(application = MenghuanApp::class, sdk = [34])
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

    private fun vm(app: MenghuanApp): ChatListViewModel {
        val settings = SettingsRepository(
            app,
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            ) { File(app.filesDir, "chatlist-${UUID.randomUUID()}.preferences_pb") },
        )
        return ChatListViewModel(app.personaRepository, settings, app.db)
    }

    /**
     * v0.31.0 起无预置导师种子：测试自建三个人设（首次订阅前插入，种子因此不再触发）。
     *
     * ⚠️ `createdAt` **显式给值**（2026-10-09）：会话列表 v0.78.0 起"没聊过的沉底、
     * 内部按创建时间倒序"，三个 `System.currentTimeMillis()` 很可能撞在同一毫秒上，
     * 排出来的次序就随 Room 的返回顺序飘 —— 测试必须自己把时间钉死。
     */
    private suspend fun seedPersonas(app: MenghuanApp) {
        app.db.personaDao().upsert(
            com.menghuan.app.data.model.Persona(
                name = "阿布", title = "人生导师", avatarEmoji = "🖋️",
                systemPrompt = "", isBuiltIn = true, createdAt = 1_700_000_000_000L,
            )
        )
        app.db.personaDao().upsert(
            com.menghuan.app.data.model.Persona(
                name = "严格教练", title = "严格教练", avatarEmoji = "🎯",
                systemPrompt = "", isBuiltIn = true, createdAt = 1_700_000_001_000L,
            )
        )
        app.db.personaDao().upsert(
            com.menghuan.app.data.model.Persona(
                name = "苏格拉底", title = "苏格拉底式导师", avatarEmoji = "💭",
                systemPrompt = "", isBuiltIn = true, createdAt = 1_700_000_002_000L,
            )
        )
    }

    @Test
    fun `会话列表过滤 hidden 且无消息的人设`() = inTest {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val viewModel = vm(app)
        try {
            // 等启动补建的内置「应用助手」落定（真实 IO 线程异步），再插测试人设
            pumpUntil { app.db.personaDao().getByName("应用助手") != null }
            seedPersonas(app)
            // 4 行 = 3 个测试人设 + 应用助手
            pumpValue(viewModel.rows) { it.size == 4 }

            val abu = app.db.personaDao().getByName("阿布")!!
            val socratic = app.db.personaDao().getByName("苏格拉底")!!
            app.personaRepository.setHidden(abu.id, true) // 隐藏且无消息 → 从列表消失
            app.personaRepository.setHidden(socratic.id, true) // 隐藏但有消息 → 新消息恢复显示
            app.db.messageDao().insert(
                ChatMessage(conversationId = socratic.id, isFromUser = false, content = "又熬夜了？")
            )

            // 断言只看三个导师人设（内置应用助手是常驻行，不参与本测试）
            // v0.78.0 起列表按"最近聊过"排：苏格拉底有消息 → 排在前面；
            // 严格教练没聊过 → 沉底。旧断言里的"插入顺序"不再成立。
            val rows = pumpValue(viewModel.rows) {
                it.filter { r -> r.persona.name != "应用助手" }.map { r -> r.persona.name } ==
                    listOf("苏格拉底", "严格教练")
            }
            assertEquals(
                listOf("苏格拉底", "严格教练"),
                rows.filter { r -> r.persona.name != "应用助手" }.map { r -> r.persona.name },
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `物理删除人设后列表与会话消息一同消失`() = inTest {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val viewModel = vm(app)
        try {
            pumpUntil { app.db.personaDao().getByName("应用助手") != null }
            seedPersonas(app)
            pumpValue(viewModel.rows) { it.size == 4 } // 人设已上屏
            val abu = app.db.personaDao().getByName("阿布")!!
            app.db.messageDao().insert(ChatMessage(conversationId = abu.id, isFromUser = true, content = "历史消息"))

            app.personaRepository.delete(abu.id) // v0.12.0 物理删除（级联消息）

            pumpUntil { app.db.messageDao().getAfterId(abu.id, 0L).isEmpty() }
            val rows = pumpValue(viewModel.rows) {
                it.filter { r -> r.persona.name != "应用助手" }.size == 2
            }
            // v0.78.0 起"没聊过的沉底、内部按创建时间**倒序**"：苏格拉底建得比严格教练晚，
            // 于是排在前面（旧断言是插入顺序 [严格教练, 苏格拉底]）。
            assertEquals(
                listOf("苏格拉底", "严格教练"),
                rows.filter { r -> r.persona.name != "应用助手" }.map { r -> r.persona.name },
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    /**
     * v0.43.1 修「冷启动先闪『新建人设』」：`listState.loaded` 必须在首个数据到达前为 false，
     * 之后置 true 并**不再回退**（UI 靠它区分"骨架屏"与"真的没有人设"）。
     *
     * 这条守的是个具体回归：以前列表只有"空/非空"两态，`rows` 初值是 `emptyList()`，
     * 冷启动于是先渲染居中的新建入口、数据到了才切换 —— 用户看到的就是闪一下。
     *
     * ⚠️ 同时守第二条铁律：`loaded` **必须从 rows 派生**，不能另发一条 Room 查询协程
     * （会与 combine 争抢共享 query executor，并把 runTest 卡在未完成子协程上）。
     */
    @Test
    fun `loaded 在数据到达前置 true 且不回退`() = inTest {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val viewModel = vm(app)
        try {
            // 初值：还没加载出来 → UI 应显示骨架屏而不是「新建人设」
            assertEquals(false, viewModel.screenState.value.loaded)

            // 数据层可观测后转为已加载
            val state = pumpValue(viewModel.screenState) { it.loaded }
            assertEquals(true, state.loaded)

            // 人设被删光也不该把 loaded 打回 false（否则会再闪一次骨架屏）
            app.personaRepository.observePersonas().first()
                .forEach { app.personaRepository.delete(it.id) }
            pumpValue(viewModel.screenState) { it.rows.isEmpty() }
            assertEquals(true, viewModel.screenState.value.loaded)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }
}
