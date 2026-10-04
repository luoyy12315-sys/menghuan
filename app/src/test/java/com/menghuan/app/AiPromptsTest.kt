package com.menghuan.app

import com.menghuan.app.data.AiPrompts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用助手手册 v0.41.0（移除快捷打卡）的定点替换锚点测试：
 * 替换基于 v0.38 全文的子串匹配实现，子串一旦对不上 replace 会静默落空，
 * 手册就还在描述已删除的快捷打卡按钮——这里锁死两个方向。
 */
class AiPromptsTest {

    @Test
    fun `新手册不再描述快捷打卡面板`() {
        assertFalse(AiPrompts.DEFAULT_ASSISTANT_PROMPT.contains("快捷打卡"))
        assertFalse(AiPrompts.DEFAULT_ASSISTANT_PROMPT.contains("✅ 打卡：任务名"))
    }

    @Test
    fun `新手册保留发图按钮描述`() {
        assertTrue(AiPrompts.DEFAULT_ASSISTANT_PROMPT.contains("输入框左侧按钮是**发图**"))
        assertTrue(AiPrompts.DEFAULT_ASSISTANT_PROMPT.contains("长按任意消息可复制全文或删除单条"))
    }

    @Test
    fun `v038 旧文案保持原样作为升级锚点`() {
        // 旧文案是启动时"用户没改过"的识别依据，必须与 v0.38 发布时分毫不差
        assertTrue(AiPrompts.DEFAULT_ASSISTANT_PROMPT_V038.contains("快捷打卡面板"))
        assertTrue(AiPrompts.DEFAULT_ASSISTANT_PROMPT_V038.length > 2000)
    }
}
