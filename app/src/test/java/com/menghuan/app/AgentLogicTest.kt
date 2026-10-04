package com.menghuan.app

import com.menghuan.app.agent.AgentConfig
import com.menghuan.app.agent.AgentPrompts
import com.menghuan.app.agent.ScreenState
import com.menghuan.app.agent.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agent 决策解析与安全围栏（纯 JVM）：脏 JSON 容错 + 清单文本 + 敏感词 + 白名单 */
class AgentLogicTest {

    private fun element(
        index: Int,
        text: String = "",
        clickable: Boolean = false,
        editable: Boolean = false,
    ) = UiElement(
        index = index, type = "Button", text = text, contentDesc = "",
        centerX = 100, centerY = 200, clickable = clickable, editable = editable, scrollable = false,
    )

    @Test
    fun `正常动作 JSON 解析`() {
        val action = AgentPrompts.parseAction("""{"action":"tap","element":3}""")
        assertEquals("tap", action?.type)
        assertEquals(3, action?.element)
    }

    @Test
    fun `done 动作带 summary 与代码栅栏包裹都能解析`() {
        val raw = "好的\n```json\n{\"action\":\"done\",\"summary\":\"已新建备忘录\"}\n```"
        val action = AgentPrompts.parseAction(raw)
        assertEquals("done", action?.type)
        assertEquals("已新建备忘录", action?.summary)
    }

    @Test
    fun `缺 action 字段与完全非 JSON 返回 null`() {
        assertNull(AgentPrompts.parseAction("""{"element":3}"""))
        assertNull(AgentPrompts.parseAction("我觉得应该点一下发送按钮。"))
        assertNull(AgentPrompts.parseAction(""))
    }

    @Test
    fun `element 负数视为缺失`() {
        val action = AgentPrompts.parseAction("""{"action":"tap","element":-1}""")
        assertEquals("tap", action?.type)
        assertEquals(null, action?.element)
    }

    @Test
    fun `清单文本含编号类型标签与能力标记`() {
        val prompt = ScreenState.toPrompt(
            listOf(
                element(0, text = "发送", clickable = true),
                element(1, text = "输入内容", editable = true),
            ),
        )
        assertTrue(prompt.contains("[0] Button \"发送\""))
        assertTrue(prompt.contains("clickable"))
        assertTrue(prompt.contains("[1] Button \"输入内容\""))
        assertTrue(prompt.contains("editable"))
    }

    @Test
    fun `支付删除发送类元素命中敏感围栏`() {
        assertTrue(AgentPrompts.isSensitive(element(0, text = "确认支付")))
        assertTrue(AgentPrompts.isSensitive(element(0, text = "删除该条目")))
        assertTrue(AgentPrompts.isSensitive(element(0, text = "发送")))
        assertFalse(AgentPrompts.isSensitive(element(0, text = "保存草稿")))
    }

    @Test
    fun `白名单围栏语义`() {
        val restricted = AgentConfig(whitelist = setOf("com.android.calendar"))
        assertTrue("本应用永远放行", restricted.allowsPackage("com.menghuan.app"))
        assertTrue(restricted.allowsPackage("com.android.calendar"))
        assertFalse("白名单外包名拒绝", restricted.allowsPackage("com.other.app"))

        val open = AgentConfig(whitelist = emptySet())
        assertTrue("未启用白名单 = 不限制", open.allowsPackage("com.other.app"))
    }
}
