package com.menghuan.app

import com.menghuan.app.agent.AgentPrompts
import com.menghuan.app.ui.chat.AGENT_CHAT_DEFAULT_STRATEGY
import com.menghuan.app.ui.chat.AGENT_CHAT_HINT_FRAMEWORK
import com.menghuan.app.ui.chat.agentChatHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.37.0 纯逻辑单测：对话内操控手机的 [操作手机:…] 标记协议
 * （解析 / 剥标记 / 分层提示词拼接）。与生图（V035LogicTest）同构覆盖。
 */
class V037AgentChatTest {

    // ===== findGoalIn =====

    @Test
    fun `标记解析提取目标`() {
        assertEquals(
            "打开备忘录新建一条「明天交稿」",
            AgentPrompts.findGoalIn("好的，交给我。\n[操作手机:打开备忘录新建一条「明天交稿」]\n马上就好。"),
        )
    }

    @Test
    fun `没有标记返回 null`() {
        assertNull(AgentPrompts.findGoalIn("普通的聊天回复，没有标记。"))
        assertNull(AgentPrompts.findGoalIn(""))
    }

    @Test
    fun `缺少右括号返回 null`() {
        assertNull(AgentPrompts.findGoalIn("我来处理 [操作手机:打开备忘录 然后没了"))
    }

    @Test
    fun `目标为空白返回 null`() {
        assertNull(AgentPrompts.findGoalIn("[操作手机:   ]"))
    }

    @Test
    fun `目标前后空白被裁掉`() {
        assertEquals("打开日历", AgentPrompts.findGoalIn("[操作手机: 打开日历 ]"))
    }

    @Test
    fun `多个标记取第一个`() {
        val raw = "[操作手机:第一个目标] 中间文字 [操作手机:第二个目标]"
        assertEquals("第一个目标", AgentPrompts.findGoalIn(raw))
    }

    // ===== stripMarker =====

    @Test
    fun `剥标记保留前后正文`() {
        // 标记独占一行 → 整行剥掉，剩余两行相邻（换行保留，不多出空行）
        assertEquals(
            "好的，交给我。\n马上就好。",
            AgentPrompts.stripMarker("好的，交给我。\n[操作手机:打开备忘录]\n马上就好。"),
        )
    }

    @Test
    fun `整条只有标记时剥成空白`() {
        assertTrue(AgentPrompts.stripMarker("[操作手机:打开备忘录]").isBlank())
    }

    @Test
    fun `没有标记时原文返回仅去首尾空白`() {
        val raw = "  正常回复  "
        assertEquals("正常回复", AgentPrompts.stripMarker(raw))
    }

    @Test
    fun `缺右括号时剥到标记起点为止`() {
        assertEquals("好的。", AgentPrompts.stripMarker("好的。\n[操作手机:打开备忘录"))
    }

    // ===== 分层提示词 =====

    @Test
    fun `框架含标记格式硬约束`() {
        assertTrue(AGENT_CHAT_HINT_FRAMEWORK.contains("[操作手机:"))
        assertTrue(AGENT_CHAT_HINT_FRAMEWORK.contains("一次回复最多一个标记"))
    }

    @Test
    fun `默认策略拼接在框架之后`() {
        val hint = agentChatHint(AGENT_CHAT_DEFAULT_STRATEGY)
        assertTrue(hint.startsWith(AGENT_CHAT_HINT_FRAMEWORK))
        assertTrue(hint.contains(AGENT_CHAT_DEFAULT_STRATEGY))
    }

    @Test
    fun `策略为空白时只发框架`() {
        assertEquals(AGENT_CHAT_HINT_FRAMEWORK, agentChatHint(""))
        assertEquals(AGENT_CHAT_HINT_FRAMEWORK, agentChatHint("   "))
    }

    @Test
    fun `框架不含策略内容（分层互不污染）`() {
        assertFalse(AGENT_CHAT_HINT_FRAMEWORK.contains(AGENT_CHAT_DEFAULT_STRATEGY))
    }
}
