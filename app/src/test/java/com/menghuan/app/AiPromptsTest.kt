package com.menghuan.app

import com.menghuan.app.data.AiPrompts
import org.junit.Assert.assertEquals
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
 * 1. 在 [AiPrompts] 里补 `.replace(...)`——**不用再登记任何"历史版本全文"**：
 *    v0.51.1 起启动升级只看"用户有没有亲手改过设定词"，没改过的人启动即刷到最新
 *    （见 `AssistantManualUpgradeTest`）；
 * 2. 在这个测试里补一对断言（新的在 / 旧的不在）。
 *    漏了第 2 步，下一次改动把搜索串写错就没人发现。
 */
class AiPromptsTest {

    private val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    @Test
    fun `v041 移除快捷打卡`() {
        assertFalse(manual.contains("快捷打卡"))
        assertFalse(manual.contains("✅ 打卡：任务名"))
        assertTrue(manual.contains("长按任意消息"))
    }

    /**
     * v0.43.0 起发图/拍摄/文件收进「＋」面板、表情面板回归，v0.50.0 起输入栏左侧换成语音键。
     * 手册此前还在说"左侧按钮是发图"，用户照着找找不到（v0.51.1 按实况重写）。
     */
    @Test
    fun `v0511 输入栏描述与真机一致`() {
        assertTrue(manual.contains("左侧是语音键"))
        assertTrue(manual.contains("「＋」加号面板"))
        assertTrue(manual.contains("通话（实时语音通话"))
        assertTrue(manual.contains("输入框里有字时右侧换成**发送**键"))
        assertTrue(manual.contains("收藏页末尾那个加号格"))
        // 旧措辞必须一次不剩
        assertFalse(manual.contains("右侧依次是**发图**"))
        assertFalse(manual.contains("输入框左侧按钮是**发图**"))
        assertFalse(manual.contains("长按任意消息可复制全文或删除单条"))
    }

    /**
     * v0.52.0 语音配置整改：三家各存一份配置、音色板块、样本格式放开到 mp4、
     * 通话改要 WAV、API 配置按段收起。
     */
    @Test
    fun `v0520 语音配置整改进手册`() {
        // 新措辞在
        assertTrue(manual.contains("三家的 Key / 接口地址 / 模型各存一份"))
        assertTrue(manual.contains("「音色」卡片（点进去才是独立的音色页）"))
        assertTrue(manual.contains("mp3 / wav / m4a / mp4 都收"))
        assertTrue(manual.contains("板块里的两个预置音色（一男一女）"))
        assertTrue(manual.contains("点某一行 = 设为默认音色"))
        assertTrue(manual.contains("人设编辑页的候选就是这份名单"))
        assertTrue(manual.contains("回落顺序是 人设专属 → 板块默认 → 该家第一个预置"))
        assertTrue(manual.contains("通话向服务商要的是 **WAV**"))
        // 旧措辞一次不剩（replace 落空是不报错的，只能靠两头夹住）
        assertFalse(manual.contains("**mp3 或 wav**"))
        assertFalse(manual.contains("点「上传克隆」得到音色"))
        assertFalse(manual.contains("8 个，男女都有"))
        assertFalse(manual.contains("人设的专属音色在「编辑人设」里选"))
        assertFalse(manual.contains("带试听），通话与语音条共用这一个音色"))
    }

    /**
     * v0.53.0：接口改成"卡片 + 点箭头看详情"，语音合成页只列已配好的模型卡片。
     * 上一版那句"两段默认收起、点标题展开"是误解，必须从手册里消失。
     */
    @Test
    fun `v0530 卡片与详情弹窗进手册`() {
        assertTrue(manual.contains("只列出**已经配好的模型卡片**"))
        assertTrue(manual.contains("「＋ 添加接入」"))
        assertTrue(manual.contains("删除这家配置"))
        assertTrue(manual.contains("中文只在本机显示，上传用的是自动生成的英文标识"))
        assertFalse(manual.contains("两段默认**收起**"))
        assertFalse(manual.contains("点标题才展开表单"))
        assertFalse(manual.contains("「语音合成」页里一家一段"))
        // v0.53.1：崩溃堆栈从「关于」页能看（真机没有 logcat，闪退过一次就查不到）
        assertTrue(manual.contains("「上次崩溃记录」"))
        assertFalse(manual.contains("关于：版本信息。\n"))
        // v0.53.3：克隆样本改成"挑一句完整的话"，手册不能再写"裁到 15 秒内"
        assertTrue(manual.contains("10 秒内一句完整的话"))
        assertTrue(manual.contains("干净的单人声"))
        assertFalse(manual.contains("15 秒内"))
    }

    /**
     * v0.53.4：API 配置按「模型」而非「接口」组织——列表点行**直接进详情**（去掉勾选/切换），
     * 生图模型也能配多条、可设默认，默认那条调不通时自动换别的模型继续生图。
     */
    @Test
    fun `v0534 API 配置按模型组织进手册`() {
        assertTrue(manual.contains("每条是一张**卡片**"))
        assertTrue(manual.contains("点卡片 = 切换当前生效的模型"))
        assertTrue(manual.contains("点卡片右侧的箭头 = 打开这条模型的详情"))
        assertTrue(manual.contains("设为默认模型、删除模型"))
        assertTrue(manual.contains("「＋ 添加模型」"))
        assertTrue(manual.contains("生图模型与对话模型行为一致"))
        assertTrue(manual.contains("自动换列表里其他生图模型继续生成"))
        assertTrue(manual.contains("长按头像切换当前生效的对话模型"))
        assertTrue(manual.contains("另有独立的**生图模型**配置"))
        assertTrue(manual.contains("点一条记录可全屏放大图片"))
        // 旧措辞必须一次不剩（replace 落空是不报错的，只能两头夹住）
        assertFalse(manual.contains("每条接口是一张**卡片**"))
        assertFalse(manual.contains("点卡片任意位置"))
        assertFalse(manual.contains("卡片下面没有「编辑」「删除」按钮"))
        assertFalse(manual.contains("「＋ 添加接口」"))
        assertFalse(manual.contains("另有独立的**生图接口**配置"))
        assertFalse(manual.contains("点一行即切换生效接口"))
    }

    /**
     * v0.53.4：语音页去掉「AI 语音条 / 语音通话」两个总开关（功能固定开启），
     * 音色板块改成与「语音合成」页一致的卡片形态。
     */
    @Test
    fun `v0534 语音页去掉两个开关`() {
        assertTrue(manual.contains("语音条与语音通话默认开着、没有开关"))
        assertTrue(manual.contains("功能默认开启、**没有开关**"))
        assertTrue(manual.contains("入口恒显示、**没有开关**"))
        // 旧措辞一次不剩（replace 落空是不报错的，只能两头夹住）
        assertFalse(manual.contains("四个开关/配置"))
        assertFalse(manual.contains("开关在 我的 → 设置 → 语音"))
        assertFalse(manual.contains("开关在同一页的「语音通话」"))
        assertFalse(manual.contains("关掉「语音通话」开关这一格不出现"))
        assertFalse(manual.contains("关掉后加号面板不再出现「通话」格"))
    }

    /**
     * v0.53.5：通知页只剩三项；音色独立成页（顶栏 克隆音色 + ID）；
     * 编辑人设头像只靠滚动挑；录音的选择区改成扇形。
     */
    @Test
    fun `v0535 通知精简与音色独立成页`() {
        // v0.54.0：设置页分组重构后，这两条断言的措辞跟着改了
        // （「通知与提醒」页已取消 → 手册改说"提醒与通知"三项开关在设置主页第 ③ 组；
        //  「音色」卡片点进去现在明确叫"独立的全屏音色页"）。
        // 断言必须跟着实际措辞走，否则这里会一直红着骗人。
        assertTrue(manual.contains("v0.53.5 删掉了每日总结、AI 周报生成提醒、系统通知设置"))
        assertTrue(manual.contains("「音色」卡片（点进去才是独立的全屏音色页）"))
        assertTrue(manual.contains("顶栏右侧就是「克隆音色」与「ID」两个按钮"))
        assertTrue(manual.contains("左右滑动**来挑"))
        assertTrue(manual.contains("两块扇形选择区"))
        // 旧措辞一次不剩（replace 落空是不报错的，只能两头夹住）
        assertFalse(manual.contains("每日总结通知（每晚 21:30"))
        assertFalse(manual.contains("系统通知设置跳转"))
        assertFalse(manual.contains("「音色」板块右上角点「克隆音色」"))
        assertFalse(manual.contains("「音色」卡片（点进去才是独立的音色页）"))
        assertFalse(manual.contains("两颗圆按钮"))
        // v0.54.0：「通知与提醒」页已取消，旧句不得复活
        assertFalse(manual.contains("通知与提醒：只剩**三项**"))
    }

    /** v0.53.5：AI 主动打电话进手册（三路触发 / 接通先开口 / 默认关 / 全屏权限）。 */
    @Test
    fun `v0536 AI 主动打电话进手册`() {
        assertTrue(manual.contains("AI 主动打电话"))
        assertTrue(manual.contains("**总开关默认关**"))
        assertTrue(manual.contains("每个 AI 人设单独配"))
        assertTrue(manual.contains("各人设互不影响"))
        assertTrue(manual.contains("接通后由 AI 先开口"))
        assertTrue(manual.contains("22:30–08:00 一律不打"))
        assertTrue(manual.contains("每个 AI 每天最多一通"))
        assertTrue(manual.contains("没接会留一句话给你"))
        assertTrue(manual.contains("待会打给我"))
        assertTrue(manual.contains("全屏通知"))
    }

    /** v0.53.5：定时主动消息 + 联网搜索（含两家官方地址）进手册。 */
    @Test
    fun `v0537 定时主动消息进手册`() {
        assertTrue(manual.contains("定时主动消息"))
        assertTrue(manual.contains("谁发、几点发、发什么"))
        assertTrue(manual.contains("先联网搜一遍再写"))
        assertTrue(manual.contains("https://open.bochaai.com"))
        assertTrue(manual.contains("https://tavily.com"))
        assertTrue(manual.contains("如实说查不到"))
    }

    /** v0.53.6：对话页三点九项菜单 / 立绘·Live2D·背景只存不显 / 会话联网 / 音色搬家。 */
    @Test
    fun `v0536b 对话页菜单进手册`() {
        assertTrue(manual.contains("右上角**三个点**是一个菜单"))
        assertTrue(manual.contains("角色立绘 / Live 2D / 聊天背景现在只存不显"))
        assertTrue(manual.contains("联网**打开后"))
        assertTrue(manual.contains("音色不在这里（改到对话页右上角菜单 → 音色里选）"))
    }

    /** v0.53.7：通话三修（谁先开口 / 记录卡朝哪侧 / 全屏）+ 铃声设置进手册。 */
    @Test
    fun `v0537 通话修复与铃声进手册`() {
        assertTrue(manual.contains("语音通话铃声"))
        assertTrue(manual.contains("来电与去电共用这一个铃声"))
        assertTrue(manual.contains("自动把音轨抽出来存成音频"))
        assertTrue(manual.contains("由 TA 先开口"))
        assertTrue(manual.contains("它知道这通电话是自己打的"))
        assertTrue(manual.contains("通话记录卡**朝哪一侧看谁打的**"))
        assertTrue(manual.contains("来电界面是**真全屏**"))
    }

    /** v0.53.7：AI 接听延迟进手册。 */
    @Test
    fun `v0538 延迟接听进手册`() {
        assertTrue(manual.contains("AI 接听延迟"))
        assertTrue(manual.contains("我打给 AI 时，TA 过几秒接听"))
        assertTrue(manual.contains("只影响你打给 AI 的那一通"))
        assertTrue(manual.contains("正在呼叫"))
    }

    /** v0.53.8：设置里的「权限」板块进手册。 */
    @Test
    fun `v0538b 权限板块进手册`() {
        assertTrue(manual.contains("权限（入口 设置 → 「权限」）"))
        assertTrue(manual.contains("通知 / 麦克风 / 相机"))
        assertTrue(manual.contains("全屏通知（锁屏来电）"))
        assertTrue(manual.contains("手机操控（无障碍）"))
        assertTrue(manual.contains("还有 N 项没开启"))
    }

    /** v0.53.9：铃声试听 / 播不出来明说 / 免打扰静音不响 进手册。 */
    @Test
    fun `v0539 铃声试听与播不出来明说进手册`() {
        assertTrue(manual.contains("页面上点「试听」随时能听"))
        assertTrue(manual.contains("播不出来时页面会标红说明"))
        assertTrue(manual.contains("手机开免打扰 / 静音 / 振动时铃声一律不响"))
        // 旧措辞必须消失：搜"不设就用系统默认铃声。"（带句号）才守得住这次 replace
        assertFalse(manual.contains("不设就用系统默认铃声。"))
    }

    /** v0.54.0：通话页字幕改成可滚动完整记录 + 手动翻阅不被自动滚动打断。 */
    @Test
    fun `v0540 通话字幕可翻阅进手册`() {
        assertTrue(manual.contains("**通话页字幕可以往上翻**"))
        assertTrue(manual.contains("**整通电话的完整对话记录**"))
        assertTrue(manual.contains("**上下滑动即可回看**说过的话"))
        assertTrue(manual.contains("一旦向上翻阅就**不再被新字幕拽回**"))
        // 防replace 跑两遍：新段落的锚点「点它直接去处理。」在结果里只剩一处，
        // 跑第二遍会再插一次（replace 链落空/重复都不报错，只能靠计数守）
        assertEquals(1, Regex(Regex.escape("**通话页字幕可以往上翻**")).findAll(manual).count().toInt())
        assertEquals(1, Regex(Regex.escape("点它直接去处理。\n")).findAll(manual).count().toInt())
    }

    /**
     * v0.54.0：设置页收纳重构 —— 手册里所有"设置 → XXX"路径必须全部改成新分组写法。
     *
     * 这一组是**双侧断言**：新措辞在（4 个分组名都要出现）+ 旧路径一次不剩。
     * 旧路径残留 = 助手会把用户指到一个已经不存在的页面上（"设置 → 语音 → 识别模型"
     * 那一页 v0.54.0 已经删了），比答不出来更糟。
     */
    @Test
    fun `v0540b 设置页分组重构进手册`() {
        // 新分组名
        assertTrue(manual.contains("① AI 与模型"))
        assertTrue(manual.contains("② 语音与通话"))
        assertTrue(manual.contains("③ 提醒与通知"))
        assertTrue(manual.contains("④ 数据与系统"))
        // 交互约定：分组标签不可点、卡片进去仍是全屏页、有搜索框
        assertTrue(manual.contains("分组标题只是标签、点不动"))
        assertTrue(manual.contains("每张卡片点进去仍然是全屏页"))
        assertTrue(manual.contains("顶部还有一个**搜索框**"))
        // 取消的三个页面 + 「AI 提示词」搬家，手册要说明白
        assertTrue(manual.contains("**取消了三个页面**"))
        assertTrue(manual.contains("「AI 提示词」从「我的」页搬进了第 ① 组"))
        assertTrue(manual.contains("不再有「语音」这一层中转页"))
        assertTrue(manual.contains("不再单独占一页"))
        assertTrue(manual.contains("不再有「通知与提醒」这一页"))
        // 新路径写法
        assertTrue(manual.contains("设置 → ② 语音与通话 → 识别模型"))
        assertTrue(manual.contains("设置 → ④ 数据与系统 → 「权限」"))
        // 旧路径一次不剩（三页都删了，指过去就是死路）
        assertFalse(manual.contains("设置 → 语音 →"))
        assertFalse(manual.contains("我的 → 设置 → 语音"))
        assertFalse(manual.contains("设置 → 外观"))
        assertFalse(manual.contains("设置 → 通知与提醒"))
        assertFalse(manual.contains("设置 → 「权限」"))
        assertFalse(manual.contains("AI 提示词、设置。"))
    }

    /** v0.53.4：编辑人设改成全屏页，输入项精简（人物头像 emoji、只留男/女/克隆音色、无模板按钮）。 */
    @Test
    fun `v0534 编辑人设改全屏页`() {
        assertTrue(manual.contains("编辑人设是**全屏页**"))
        assertTrue(manual.contains("直接点头像框"))
        assertTrue(manual.contains("人物 emoji"))
        // v0.53.6：音色已从编辑人设里移走（改到对话页菜单），所以这句反过来断言"不在了"
        assertFalse(manual.contains("音色只列 男声 / 女声 / 我克隆的声音"))
        assertTrue(manual.contains("没有模板按钮"))
        // 旧措辞一次不剩（replace 落空是不报错的，只能两头夹住）
        assertFalse(manual.contains("编辑时**直接点头像**换自定义图片，渐变头像框保留；"))
    }

    /** v0.53.4：按住说话改成微信式录音蒙层（波形气泡 + 松手发送 + 取消/转文字）。 */
    @Test
    fun `v0534 录音蒙层进手册`() {
        assertTrue(manual.contains("中间气泡显示**实时波形 + 秒数**"))
        assertTrue(manual.contains("下面**两块扇形选择区**「取消」「转文字」"))
        assertTrue(manual.contains("左滑到「取消」松手 = 丢掉"))
        assertTrue(manual.contains("右滑到「转文字」松手 = 只发文字"))
        assertTrue(manual.contains("识别不出来就照发语音"))
        // 旧措辞：录音只剩"松手发出"这一句就该消失了
        assertFalse(manual.contains("→ 中间变「按住 说话」→ 松手发出；"))
    }

    /** 升级判定改看"手册形状"，所以形状识别必须认得历代手册、又不把用户自己写的当成手册 */
    @Test
    fun `内置手册形状识别`() {
        assertTrue(AiPrompts.isBuiltinAssistantManual(manual))
        assertTrue(AiPrompts.isBuiltinAssistantManual(AiPrompts.DEFAULT_ASSISTANT_PROMPT_V038))
        assertTrue(
            AiPrompts.isBuiltinAssistantManual(
                "你是「导师」App 内置的应用助手，帮助用户了解和使用本应用。"
            )
        )
        assertFalse(AiPrompts.isBuiltinAssistantManual("你是我自己写的助手，只提醒我喝水。"))
        assertFalse(AiPrompts.isBuiltinAssistantManual(""))
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
        // 单聊输入栏左侧是语音键（v0.51.1 起这句按真机实况重写，加粗位置也变了）
        assertTrue(manual.contains("输入框**左侧是语音键**"))
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
    fun `v038 底稿保持原样，否则 replace 静默落空`() {
        // 当前版手册是在这份底稿上定点替换出来的：底稿措辞一变，搜索串就对不上，
        // 而 String.replace 落空不报错——这里把底稿钉死，等于给每条 replace 上了保险
        val anchor = AiPrompts.DEFAULT_ASSISTANT_PROMPT_V038
        assertTrue(anchor.contains("快捷打卡面板"))
        assertTrue(anchor.contains("单机版"))
        assertTrue(anchor.contains("数据 100% 只存本机"))
        assertTrue(anchor.contains("图片只存本机"))
        assertTrue(anchor.contains("七段提示词都可编辑"))
        assertTrue(anchor.length > 2000)
    }
}
