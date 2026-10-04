package com.menghuan.app

import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.agent.AgentLoop
import com.menghuan.app.agent.AgentRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v0.38.0：AgentRunner 对话内触发链路的状态机测试（Robolectric）。
 * JVM/Robolectric 里无障碍服务必然未连接，AgentLoop 会在前置检查就结束——
 * 正好用来验证「start → Finished → onFinish 回调 → reset 清发起者」整条链。
 */
@RunWith(RobolectricTestRunner::class)
class AgentRunnerTest {

    @After
    fun cleanup() {
        AgentRunner.reset()
    }

    @Test
    fun `start 后立即完成并回调 onFinish`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        var finished: AgentLoop.Result? = null
        AgentRunner.start(
            scope = CoroutineScope(Dispatchers.IO),
            settingsRepository = app.settingsRepository,
            goal = "测试目标",
            autoConfirm = true,
            personaId = 7L,
            onFinish = { finished = it },
        )
        // 前置检查（API/无障碍）不通过 → 0 步结束，不进入执行循环
        withTimeout(10_000) { AgentRunner.state.first { it is AgentRunner.State.Finished } }
        val state = AgentRunner.state.value as AgentRunner.State.Finished
        assertFalse(state.success)
        assertEquals(0, state.steps)
        assertEquals(0, finished?.steps)
        assertEquals(7L, AgentRunner.runningPersonaId)
    }

    @Test
    fun `reset 回 Idle 并清发起者标记`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        AgentRunner.start(
            scope = CoroutineScope(Dispatchers.IO),
            settingsRepository = app.settingsRepository,
            goal = "测试目标",
        )
        withTimeout(10_000) { AgentRunner.state.first { it is AgentRunner.State.Finished } }
        assertTrue(AgentRunner.runningPersonaId == null) // 未传 personaId 的手动入口保持 null
        AgentRunner.reset()
        assertTrue(AgentRunner.state.value is AgentRunner.State.Idle)
        assertNull(AgentRunner.runningPersonaId)
    }
}
