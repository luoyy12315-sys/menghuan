package com.menghuan.app

import com.menghuan.app.data.PersonaPromptSpec
import com.menghuan.app.data.PersonaRewriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人设提示词写作规范（v0.79.0）。
 *
 * 这份 [PersonaPromptSpec.TEXT] 是**用户会复制出去发给别的 AI 的产品文案**，
 * 所以它守的是三条用户点名的约定：
 * ① 必须讲清「角色在这个 App 里能做什么」（用户原话"记住要告诉 AI 可以有什么能力"）；
 * ② **不给示例、不给填空模板**（用户原话"不要模板，也不要例子""不要健身教练那些玩意"）；
 * ③ 与 App 内「AI 优化设定词」那份**同源**，不许各说各话。
 */
class PersonaPromptSpecTest {

    @Test
    fun `规范讲清了角色真实拥有的能力`() {
        val t = PersonaPromptSpec.TEXT
        assertTrue("要有能力清单这一节", t.contains("角色真实拥有的能力"))
        // 抽几条最能体现"它真的能动手"的能力：漏了它们用户就写不出"让它用起来"的设定词
        listOf("语音通话", "动态", "任务", "主动联系", "记忆", "联网搜索", "生成图片", "操作手机")
            .forEach { assertTrue("能力清单漏了「$it」", t.contains(it)) }
    }

    @Test
    fun `规范里没有示例也没有填空模板`() {
        val t = PersonaPromptSpec.TEXT
        assertFalse("不许再出现健身教练那类示例", t.contains("健身教练"))
        assertFalse("不许再出现学习教练那类示例", t.contains("学习教练"))
        assertFalse("不许给可有样学样的示例段", t.contains("示例："))
        assertFalse("不许给【】式填空模板", t.contains("【你的名字】") || t.contains("【关系】"))
    }

    @Test
    fun `规范要求写关系而不是职业头衔_并点名要避免的写法`() {
        val t = PersonaPromptSpec.TEXT
        assertTrue(t.contains("写关系，不写职业头衔"))
        assertTrue("要说清为什么不能当客服写", t.contains("那是客服，不是陪伴"))
        assertTrue("要禁止出戏措辞", t.contains("作为 AI 助手"))
        assertTrue("要禁止形容词堆砌", t.contains("叠在一起等于什么都没说"))
        assertTrue("要禁止许诺 App 没有的能力", t.contains("不要许诺 App 做不到的事"))
    }

    @Test
    fun `App 内一键优化与规范同源`() {
        val sys = PersonaRewriter.SYSTEM_PROMPT
        assertTrue("改写器必须复用规范正文", sys.startsWith(PersonaPromptSpec.TEXT))
        assertTrue("改写器要说明这次输出 JSON", sys.contains("只输出 JSON"))
        // 旧口径（三节 + 健身教练示例）不许留在改写器里
        assertFalse(sys.contains("包含【语气与风格】【职责】【约束】三节"))
        assertFalse(sys.contains("健身教练"))
    }
}
