package com.menghuan.app

import com.menghuan.app.data.AiPrompts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用助手手册的定点替换锚点测试。
 *
 * ## 为什么这批断言是"双向"的
 * 手册升级是在 v0.38 全文上做**子串替换**，子串对不上时 `replace` 静默落空——
 * 不报错、不崩溃，只是手册还在描述早已删除的功能，用户问一句就答错。
 * （真实踩过的坑：`trimIndent()` 之后段首没有缩进，搜索串多写 8 个空格就整段白改。）
 * 所以每条升级都要**同时**断言"新措辞在"和"旧措辞不在"，缺一不可。
 *
 * ## 加新版本时必须做两件事
 * 1. 在 [AiPrompts] 里补 `.replace(...)`；
 * 2. 在这个测试里补一对断言（新的在 / 旧的不在）。
 *    漏了第 2 步，下一次改动把搜索串写错就没人发现。
 */
class AiPromptsTest {

    private val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    @Test
    fun `v041 移除快捷打卡`() {
        assertFalse(manual.contains("快捷打卡"))
        assertFalse(manual.contains("✅ 打卡：任务名"))
        assertTrue(manual.contains("长按任意消息可复制全文或删除单条"))
    }

    @Test
    fun `v050 取消纯离线定位`() {
        assertFalse(manual.contains("单机版"))
        assertFalse(manual.contains("数据 100% 只存本机"))
        assertFalse(manual.contains("不上传任何服务器"))
        assertFalse(manual.contains("图片只存本机"))
    }

    @Test
    fun `v050 语音章已进手册`() {
        // 新措辞在
        assertTrue(manual.contains("═══ 语音（v0.50.0 重做）═══"))
        assertTrue(manual.contains("【教程 · 硅基流动语音合成（推荐，教用户走这条）】"))
        assertTrue(manual.contains("https://cloud.siliconflow.cn/account/ak"))
        assertTrue(manual.contains("https://mimo.mi.com/docs/zh-CN/api/audio/tts"))
        assertTrue(manual.contains("实名认证"))
        assertTrue(manual.contains("【语音条】"))
        // 提示词段数跟着改了
        assertTrue(manual.contains("八段提示词都可编辑"))
        assertFalse(manual.contains("七段提示词都可编辑"))
        assertTrue(manual.contains("AI 语音条策略"))
    }

    @Test
    fun `v050 朗读与喇叭不再出现在手册里`() {
        // 朗读功能已删除，手册不能还教用户去找那个喇叭
        assertFalse(manual.contains("喇叭打开后"))
        assertFalse(manual.contains("自动读出声音"))
        assertTrue(manual.contains("已经删除"))
    }

    @Test
    fun `v050 识别模型手册不再提手动导入`() {
        // 手动导入入口已删（普通人不知道该下哪个文件），手册不能还写着三条路
        assertFalse(manual.contains("手动导入"))
        assertTrue(manual.contains("ModelScope"))
        assertTrue(manual.contains("hf-mirror"))
    }

    @Test
    fun `v051 我的这条线是全屏页`() {
        assertTrue(manual.contains("全屏页"))
        // 单聊输入栏左侧现在是语音键，不再是"左侧按钮是发图"
        assertTrue(manual.contains("输入框左侧是**语音键**"))
        assertFalse(manual.contains("输入框左侧按钮是**发图**"))
        // 顶栏搜索图标早已删除
        assertFalse(manual.contains("单聊顶栏的搜索图标"))
        assertTrue(manual.contains("单聊顶栏右侧三个点是编辑人设"))
    }

    @Test
    fun `语音配置问题被明确要求给出口与步骤`() {
        assertTrue(manual.contains("问「语音怎么配置」时"))
        assertTrue(manual.contains("必须去服务商官网注册 Key"))
    }

    @Test
    fun `v038 旧文案保持原样作为升级锚点`() {
        // 旧文案是启动时"用户没改过"的识别依据，必须与 v0.38 发布时分毫不差
        val anchor = AiPrompts.DEFAULT_ASSISTANT_PROMPT_V038
        assertTrue(anchor.contains("快捷打卡面板"))
        assertTrue(anchor.contains("单机版"))
        assertTrue(anchor.contains("数据 100% 只存本机"))
        assertTrue(anchor.contains("图片只存本机"))
        assertTrue(anchor.contains("七段提示词都可编辑"))
        assertTrue(anchor.length > 2000)
    }
}
