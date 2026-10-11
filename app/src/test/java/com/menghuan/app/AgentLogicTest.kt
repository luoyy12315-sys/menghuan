package com.menghuan.app

import com.menghuan.app.agent.AgentConfig
import com.menghuan.app.agent.AgentPrompts
import com.menghuan.app.agent.ScreenCapture
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
    fun `重试请求与首次请求不相等（不再原样重发）`() {
        // 同一输入必然得到同一错误答案：解析失败的重试必须带提示，不能花两次钱买同一个错
        val first = AgentPrompts.userPrompt("目标A", "[0] Button", listOf("点击 [0]"))
        val retry = AgentPrompts.userPrompt("目标A", "[0] Button", listOf("点击 [0]"), retryHint = "上一次的输出无法解析。只输出一个 JSON，不要任何其他文字。")
        assertTrue(first != retry)
        assertTrue(retry.startsWith(first))
        assertTrue(retry.contains("无法解析"))
        // 默认不传提示 = 行为与旧版完全一致
        assertEquals(first, AgentPrompts.userPrompt("目标A", "[0] Button", listOf("点击 [0]"), retryHint = ""))
    }

    // ===== M2-D 元素清单瘦身 =====

    @Test
    fun `瘦身只留可交互节点_纯文本仅在无可交互元素时兜底`() {
        val mixed = listOf(
            element(0, text = "标题文字"),
            element(1, text = "保存", clickable = true),
            element(2, text = "价格 ￥99"),
        )
        val slim = ScreenState.compact(mixed)
        assertEquals("纯文本应被剔掉，只留可交互", 1, slim.size)
        assertEquals("保存", slim[0].label)
        assertEquals("编号必须重排为 0..n-1", 0, slim[0].index)

        // 整屏没有任何可交互元素 → 纯文本作为兜底发出（否则模型无处可点）
        val fallback = ScreenState.compact(listOf(element(0, text = "只剩说明文字"), element(1, text = "第二行")))
        assertEquals(2, fallback.size)
    }

    @Test
    fun `成排相同按钮折叠为前三个并标总数`() {
        val raw = (0 until 6).map { element(it, text = "删除", clickable = true) }
        val slim = ScreenState.compact(raw)
        assertEquals("同形状只留 3 个", 3, slim.size)
        assertEquals("总数标在第一条上", 6, slim[0].repeatCount)
        assertEquals(1, slim[1].repeatCount)
        assertTrue("清单文本要能看出被折叠", ScreenState.toPrompt(slim).contains("×6"))
    }

    @Test
    fun `无标签节点不参与折叠（一排图标按钮作用不同）`() {
        val raw = (0 until 5).map { element(it, text = "", clickable = true) }
        val slim = ScreenState.compact(raw)
        assertEquals("无标签不能折叠，折叠掉会点错", 5, slim.size)
        assertEquals("也不该标出 ×N", 1, slim[0].repeatCount)
    }

    @Test
    fun `清单文本标签截断二十字`() {
        val long = "很长的按钮文案".repeat(6) // 42 字
        val prompt = ScreenState.toPrompt(listOf(element(0, text = long, clickable = true)))
        assertTrue("标签应被截断", prompt.contains("\"${long.take(20)}\""))
        assertFalse("截断后的余下内容不该出现", prompt.contains(long.take(30)))
    }

    // ===== M2-E 卡住判定口径 =====

    @Test
    fun `动作签名按动作本身判同类`() {
        val tap3 = AgentPrompts.parseAction("""{"action":"tap","element":3}""")!!
        val tap3Again = AgentPrompts.parseAction("""{"action":"tap","element":3}""")!!
        val tap4 = AgentPrompts.parseAction("""{"action":"tap","element":4}""")!!
        val text3 = AgentPrompts.parseAction("""{"action":"text","element":3,"value":"a"}""")!!
        val text3Other = AgentPrompts.parseAction("""{"action":"text","element":3,"value":"b"}""")!!
        assertEquals("同一动作重复 = 同一签名（失败也会被计入卡住）", AgentPrompts.actionSignature(tap3), AgentPrompts.actionSignature(tap3Again))
        assertTrue("点两个不同元素不算卡住", AgentPrompts.actionSignature(tap3) != AgentPrompts.actionSignature(tap4))
        assertTrue("点与输入不算同一动作", AgentPrompts.actionSignature(tap3) != AgentPrompts.actionSignature(text3))
        assertEquals("同一框反复灌不同内容仍算同一动作（要止损）", AgentPrompts.actionSignature(text3), AgentPrompts.actionSignature(text3Other))
    }

    // ===== M2-G 敏感词围栏补齐 =====

    @Test
    fun `要输入的内容也过敏感词表`() {
        assertTrue(AgentPrompts.isSensitiveValue("转账给张三 5000"))
        assertTrue(AgentPrompts.isSensitiveValue("帮我删除这条"))
        assertFalse(AgentPrompts.isSensitiveValue("明天上午十点开会"))
    }

    // ===== M4-L 观测层混合输入（截图 + 树）=====

    @Test
    fun `截图能力判定_低于 API 30 或服务未连接都不行`() {
        assertTrue(ScreenCapture.canCapture(30, true))
        assertTrue(ScreenCapture.canCapture(34, true))
        assertFalse("API 26（minSdk）拿不到无障碍截图", ScreenCapture.canCapture(26, true))
        assertFalse(ScreenCapture.canCapture(29, true))
        assertFalse("服务没连上就截不了", ScreenCapture.canCapture(34, false))
    }

    @Test
    fun `决策提示词写明截图只辅助_动作必须按编号`() {
        assertTrue("没写明截图的用途边界", AgentPrompts.SYSTEM.contains("截图只用来"))
        assertTrue("没写明动作只能按编号", AgentPrompts.SYSTEM.contains("**动作只能按元素编号**"))
        assertTrue("没写明冲突时以清单为准", AgentPrompts.SYSTEM.contains("以元素清单为准"))
        assertTrue("没写不要凭像素坐标猜", AgentPrompts.SYSTEM.contains("不要凭像素坐标猜"))
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
