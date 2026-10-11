package com.menghuan.app

import com.menghuan.app.agent.ActionScript
import com.menghuan.app.agent.ActionScriptStore
import com.menghuan.app.agent.ScriptStep
import com.menghuan.app.agent.StepFingerprint
import com.menghuan.app.agent.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * v1.0 M3-H 动作脚本缓存（纯 JVM）：目标规范化 / 起始指纹 / 指纹匹配 / 落盘往返 / 上限。
 * 「只存成功的任务」「不匹配就放弃」在 `AgentLoop` 的控制流里，这里测的是它依赖的全部纯逻辑。
 */
class ActionScriptStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun el(
        index: Int,
        viewId: String = "",
        label: String = "按钮",
        type: String = "Button",
        clickable: Boolean = true,
    ) = UiElement(
        index = index, type = type, text = label, contentDesc = "",
        centerX = 100 + index, centerY = 200, clickable = clickable, editable = false,
        scrollable = false, viewId = viewId,
    )

    @Test
    fun `目标规范化只做保守处理`() {
        assertEquals("打开备忘录新建一条", ActionScriptStore.normalizeGoal("  打开备忘录新建一条  "))
        assertEquals("打开备忘录新建一条", ActionScriptStore.normalizeGoal("打开备忘录新建一条。"))
        assertEquals("打开备忘录 新建一条", ActionScriptStore.normalizeGoal("打开备忘录   新建一条"))
        // 文案不同 = 不同目标：不做模糊匹配（重放错目标比慢更糟）
        assertTrue(
            ActionScriptStore.normalizeGoal("打开备忘录") !=
                ActionScriptStore.normalizeGoal("打开计算器"),
        )
    }

    @Test
    fun `起始界面指纹含包名与元素形状`() {
        val a = ActionScriptStore.screenSignature("com.a", listOf(el(0, label = "保存"), el(1, label = "取消")))
        val b = ActionScriptStore.screenSignature("com.a", listOf(el(0, label = "保存"), el(1, label = "取消")))
        val c = ActionScriptStore.screenSignature("com.b", listOf(el(0, label = "保存"), el(1, label = "取消")))
        val d = ActionScriptStore.screenSignature("com.a", listOf(el(0, label = "取消"), el(1, label = "保存")))
        assertEquals(a, b)
        assertTrue("包名不同不算同一起点", a != c)
        assertTrue("顺序不同不算同一起点", a != d)
    }

    @Test
    fun `指纹匹配优先 resourceId 且要求唯一命中`() {
        val elements = listOf(
            el(0, viewId = "com.a:id/ok", label = "确定"),
            el(1, viewId = "com.a:id/cancel", label = "取消"),
        )
        // id 命中
        assertEquals(0, ActionScriptStore.matchByFingerprint(
            StepFingerprint(viewId = "com.a:id/ok", type = "Button", label = "确定"), elements,
        )?.index)
        // 无 id → 类型 + 标签命中
        assertEquals(1, ActionScriptStore.matchByFingerprint(
            StepFingerprint(type = "Button", label = "取消"), elements,
        )?.index)
        // 同屏多个相同 id（列表行）→ 不确定，必须放弃
        val dup = listOf(
            el(0, viewId = "com.a:id/row", label = "张三"),
            el(1, viewId = "com.a:id/row", label = "李四"),
        )
        assertNull(
            "同 id 多个命中要放弃（不能猜是哪一行）",
            ActionScriptStore.matchByFingerprint(StepFingerprint(viewId = "com.a:id/row"), dup),
        )
        // 找不到
        assertNull(ActionScriptStore.matchByFingerprint(
            StepFingerprint(viewId = "com.a:id/gone"), elements,
        ))
        assertNull(ActionScriptStore.matchByFingerprint(null, elements))
    }

    @Test
    fun `同目标同起点才算命中`() {
        val s = ActionScript(
            goalKey = ActionScriptStore.normalizeGoal("打开备忘录"),
            startPackage = "com.note",
            startSignature = "com.note#Button|新建",
            steps = listOf(
                ScriptStep("tap", fp = StepFingerprint("com.note:id/new", "Button", "新建")),
                ScriptStep("text", value = "明天交稿"),
            ),
        )
        val scripts = listOf(s)
        assertNotNull(ActionScriptStore.find(scripts, "打开备忘录。", "com.note", "com.note#Button|新建"))
        assertNull("起点不同不命中", ActionScriptStore.find(scripts, "打开备忘录", "com.note", "com.note#Button|设置"))
        assertNull("目标不同不命中", ActionScriptStore.find(scripts, "打开计算器", "com.note", "com.note#Button|新建"))
        assertNull("包不同不命中", ActionScriptStore.find(scripts, "打开备忘录", "com.other", "com.note#Button|新建"))
    }

    @Test
    fun `同目标同起点覆盖旧的且不超上限`() {
        val mk = { n: Int ->
            ActionScript(
                goalKey = "g$n", startPackage = "p", startSignature = "s$n",
                steps = listOf(ScriptStep("back"), ScriptStep("home")),
            )
        }
        var list = emptyList<ActionScript>()
        repeat(ActionScriptStore.MAX_SCRIPTS + 5) { list = ActionScriptStore.upsert(list, mk(it)) }
        assertEquals("超上限要截断", ActionScriptStore.MAX_SCRIPTS, list.size)
        assertEquals("丢的是最旧的", "g5", list.first().goalKey)

        // 同 goal + 同起点 → 覆盖，不新增
        val same = mk(0).copy(goalKey = "gz", startSignature = "sz")
        val once = ActionScriptStore.upsert(emptyList(), same)
        val twice = ActionScriptStore.upsert(once, same.copy(steps = listOf(ScriptStep("back"), ScriptStep("back"))))
        assertEquals(1, twice.size)
        assertEquals(2, twice[0].steps.size)
    }

    @Test
    fun `单步脚本不落盘`() {
        val one = ActionScript("g", "p", "s", listOf(ScriptStep("tap", fp = StepFingerprint("id", "Button", "确定"))))
        assertTrue("少于 MIN_STEPS 不存（重放价值低）", ActionScriptStore.upsert(emptyList(), one).isEmpty())
    }

    @Test
    fun `落盘与读回往返一致`() {
        val file = tmp.newFile("agent_scripts.json")
        val script = ActionScript(
            goalKey = "打开备忘录新建一条",
            startPackage = "com.note",
            startSignature = "com.note#Button|新建",
            steps = listOf(
                ScriptStep("tap", fp = StepFingerprint("com.note:id/new", "Button", "新建")),
                ScriptStep("text", value = "明天交稿"),
                ScriptStep("swipe", dir = "up"),
                ScriptStep("back"),
            ),
        )
        assertTrue(ActionScriptStore.save(file, listOf(script)))
        val back = ActionScriptStore.load(file)
        assertEquals(1, back.size)
        assertEquals(script, back[0])
        // 清除
        assertTrue(ActionScriptStore.clear(file))
        assertTrue(ActionScriptStore.load(file).isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `坏文件不影响使用`() {
        val file = tmp.newFile("bad.json")
        file.writeText("{ 这不是 JSON 数组")
        assertTrue("坏文件应读成空脚本，而不是崩", ActionScriptStore.load(file).isEmpty())
        assertTrue("清一个不存在的文件也算成功", ActionScriptStore.clear(java.io.File(tmp.root, "nope.json")))
    }
}
