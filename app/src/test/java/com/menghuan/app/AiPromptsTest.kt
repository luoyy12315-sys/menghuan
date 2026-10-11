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
 * 1. 在 [AiPrompts] 里补 `.manualPatch(...)`（**不许用裸 `.replace()`**，那会绕过
 *    末尾「P4 手册补丁链全部命中」总自检）——**不用再登记任何"历史版本全文"**：
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
        assertTrue(manual.contains("设置 → 音色（独立的全屏页）"))
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
        // v0.54.4：入口从「一行」改成**右上角红色「崩溃记录」**（措辞改本用例见 v0544）
        assertTrue(manual.contains("红色的「崩溃记录」"))
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
        // v0.54.1：设置页改成九板块后，这两条断言的措辞跟着改了
        // （音色现在是设置主页第 ③ 板块 → 独立的全屏音色页）。
        // 断言必须跟着实际措辞走，否则这里会一直红着骗人。
        assertTrue(manual.contains("v0.53.5 删掉了每日总结、AI 周报生成提醒、系统通知设置"))
        assertTrue(manual.contains("设置 → 音色（独立的全屏页）"))
        assertTrue(manual.contains("顶栏右侧就是「克隆音色」与「ID」两个按钮"))
        assertTrue(manual.contains("左右滑动**来挑"))
        // v0.56.7：措辞改成「两块环带选择区」并补了档位说明（见 v0567）
        assertTrue(manual.contains("两块环带选择区"))
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
        // v0.54.5：总开关删除，开关改在**每个角色**的整页设置卡片上（措辞见 v0545）
        assertTrue(manual.contains("**默认关、每个角色单独配**"))
        // 「卡片右边就是开关」那句措辞在 v0.54.6（改「电话」行 + 底部弹层）就被改掉了，
        // 现行形态由 v0546 / v0566 两处断言守着，这里不再重复钉一句已经不存在的话
        // v0.94：整句随「电话」段重写被移除（不再有「几天没聊」那套按人设差异化的说法），
        // 现行口径是「每个角色各有一套」——由 v0_94 那条测试钉，这里不再钉已不存在的话
        assertTrue(manual.contains("主动关心是**每个角色各有一套**时间段与每天最多几条"))
        assertTrue(manual.contains("接通后由 AI 先开口"))
        assertTrue(manual.contains("22:30–08:00 一律不打"))
        // v0.94：每天一通 → 可设「每天最多几通」1–9
        assertTrue(manual.contains("**每天最多几通**，同一个弹层里填，1–9"))
        assertTrue(manual.contains("没接会留一句话给你"))
        assertTrue(manual.contains("待会打给我"))
        assertTrue(manual.contains("全屏通知"))
        assertFalse(manual.contains("**总开关默认关**"))
        assertFalse(manual.contains("每个 AI 人设单独配"))
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
        // v0.54.4：点三个点改成开**整页**会话设置（措辞见 v0544），这里只留仍成立的两条
        // v0.68.0：这条旧事实已经作废 —— 立绘挪到编辑人设页当生图参考图、通话形象配了真的显示。
        // 旧措辞必须整句消失（留着就是助手在答旧话），新措辞由 `v0680 …` 那条断言。
        assertFalse(manual.contains("**角色立绘 / Live 2D 仍是只存不显**"))
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

    /** v0.53.7：AI 接听延迟进手册（v0.54.5：从手填秒数改成卡片 + 右侧直接选）。 */
    @Test
    fun `v0538 延迟接听进手册`() {
        assertTrue(manual.contains("AI 接听延迟"))
        // v0.56.6：固定档位改成"自己填秒数"（那条 replace 的完整断言见 v0566）
        assertTrue(manual.contains("那一页**自己填秒数**"))
        assertTrue(manual.contains("只影响你打给 AI 的那一通"))
        assertTrue(manual.contains("正在呼叫"))
        // 旧的"手填秒数"写法一次不剩（replace 落空是静默的，只能两头夹住）
        assertFalse(manual.contains("可选「我打给 AI 时，TA 过几秒接听」"))
        assertFalse(manual.contains("**直接填秒数，0 = 立刻接**"))
    }

    /** v0.53.8：设置里的「权限」板块进手册。 */
    @Test
    fun `v0538b 权限板块进手册`() {
        assertTrue(manual.contains("权限（入口 设置 → 权限）"))
        assertTrue(manual.contains("通知 / 麦克风 / 相机"))
        assertTrue(manual.contains("全屏通知（锁屏来电）"))
        assertTrue(manual.contains("手机操控（无障碍）"))
        assertTrue(manual.contains("还有 N 项没开启"))
    }

    /**
     * v0.53.9：铃声试听 / 播不出来明说 进手册。
     *
     * ⚠️ 第三条断言在 v0.62.0 **翻了方向**：那句"手机开免打扰 / 静音 / 振动时铃声一律不响"
     * 已经不成立——AI 主动打来的那通现在把铃声放到闹钟音轨上，勿扰下照样响。
     * 留着旧措辞就是让助手教用户"没声音是正常的"，所以改成 assertFalse（见 `v0620 AI 来电穿勿扰 进手册`）。
     */
    @Test
    fun `v0539 铃声试听与播不出来明说进手册`() {
        assertTrue(manual.contains("页面上点「试听」随时能听"))
        assertTrue(manual.contains("播不出来时页面会标红说明"))
        assertFalse(manual.contains("手机开免打扰 / 静音 / 振动时铃声一律不响"))
        // 旧措辞必须消失：搜"不设就用系统默认铃声。"（带句号）才守得住这次 replace
        assertFalse(manual.contains("不设就用系统默认铃声。"))
    }

    /** v0.54.0：通话页字幕改成可滚动完整记录 + 手动翻阅不被自动滚动打断（v0.54.1 补"最近四段"窗口与焦点高亮）。 */
    @Test
    fun `v0540 通话字幕可翻阅进手册`() {
        assertTrue(manual.contains("**通话页字幕可以往上翻**"))
        assertTrue(manual.contains("**整通电话的完整对话记录**"))
        assertTrue(manual.contains("**上下滑动即可回看**说过的话"))
        // v0.54.1 的"最近四段 / 每段最多两行 / 最亮越旧越淡"写法已在 v0.54.3
        // 换成"一屏最多 6 行 / 越旧越小"，断言随之移到 v0543 用例（这里不再重复钉旧词）
        assertTrue(manual.contains("一旦向上翻阅就**不再被新字幕拽回**"))
        // 防replace 跑两遍：新段落的锚点「点它直接去处理。」在结果里只剩一处，
        // 跑第二遍会再插一次（replace 链落空/重复都不报错，只能靠计数守）
        assertEquals(1, Regex(Regex.escape("**通话页字幕可以往上翻**")).findAll(manual).count().toInt())
        assertEquals(1, Regex(Regex.escape("点它直接去处理。\n")).findAll(manual).count().toInt())
    }

    /**
     * v0.54.1：设置页重构进手册 —— 手册里所有"设置 → XXX"路径必须与真机一致。
     *
     * v0.54.2 又把主页改成「方案二」（九张入口卡、点进去才是二级页、路径去编号），
     * 所以这里只断言**跨版本仍成立**的东西；v0.54.2 的具体改动见下一个用例。
     * 旧分组 / 旧页路径残留 = 助手会把用户指到一个已经不存在的页面上，比答不出来更糟。
     */
    @Test
    fun `v0541 设置页重构进手册`() {
        // 交互约定：主页有搜索行（点一下才输入），卡片点进去都是全屏页
        // v0.54.4：搜索框改成**搜索行**（点一下才可输入），措辞改本用例见 v0544
        assertFalse(manual.contains("设置主页顶部是一条**搜索行**"))  // v0.56.8：搜索框删掉，只留常驻放大镜（见 v0568b）
        assertTrue(manual.contains("每张卡点进去都是全屏页"))
        // 「AI 提示词」改名「提示词库」并搬家；「语音」这一层中转页取消
        assertTrue(manual.contains("「AI 提示词」已从这一页搬进 设置 → 提示词库"))
        assertTrue(manual.contains("不再有「语音」这一层中转页"))
        // 旧分组 / 旧页路径一次不剩（指过去就是死路）
        assertFalse(manual.contains("① AI 与模型"))
        assertFalse(manual.contains("② 语音与通话"))
        assertFalse(manual.contains("④ 数据与系统"))
        assertFalse(manual.contains("设置 → 语音 →"))
        assertFalse(manual.contains("我的 → 设置 → 语音"))
        assertFalse(manual.contains("设置 → 外观"))
        assertFalse(manual.contains("设置 → 通知与提醒"))
        assertFalse(manual.contains("设置 → 「权限」"))
        assertFalse(manual.contains("AI 提示词、设置。"))
    }

    /**
     * v0.54.2：设置主页再改「方案二」——**九张自题名入口卡**、点进去才是二级页、路径全部去编号；
     * **「联网搜索」从「定时主动消息」拎出**，成为「模型配置」的第 5 张子卡。
     *
     * 用户原话："不是手风琴，不是分组。折叠，是点进去。"
     * 这一组仍是**双侧断言**：新措辞全在 + 旧编号 / 旧位置一次不剩。
     */
    @Test
    fun `v0542 设置页方案二进手册`() {
        // 「设置页怎么用」的定稿版本号已随 v0.54.4 改成 v0.54.4（断言移去 v0544 用例，这里不再钉）
        // 主页就是九张卡，多条目卡点进去才是子卡
        assertTrue(manual.contains("主页就是这九张卡"))
        // v0.56.5 起「通话行为」里是两张卡（铃声 + 接听延迟），措辞随之更新
        assertTrue(manual.contains("模型配置点进去还有五张子卡，通话行为里是「语音通话铃声」与「接听延迟」两张"))
        assertFalse(manual.contains("模型配置 / 通话行为 / 提醒与通知这三张点进去还有几张子卡"))
        // 模型配置的第 5 张子卡 = 联网搜索
        assertTrue(manual.contains("**模型配置**（对话模型 / 生图模型 / 识别模型 / 语音模型 / 联网搜索）"))
        assertTrue(manual.contains("联网搜索在 模型配置 → 联网搜索"))
        assertTrue(manual.contains("设置 → 模型配置 → 联网搜索"))
        assertTrue(manual.contains("给 AI 配一双「上网的眼睛」"))
        // 提醒与通知是一张卡；v0.54.4 起「定时主动消息」已搬去会话设置页
        assertTrue(manual.contains("设置主页的**「提醒与通知」卡**点进去只剩两个开关"))
        // 路径去编号后的写法
        assertTrue(manual.contains("设置 → 模型配置 → 识别模型"))
        assertTrue(manual.contains("设置 → 通话行为 → 「语音通话铃声」"))
        assertTrue(manual.contains("设置 → 权限"))
        // 旧编号措辞一次不剩（replace 落空是不报错的，只能两头夹住）
        assertFalse(manual.contains("① 模型配置"))
        assertFalse(manual.contains("② 提示词库"))
        assertFalse(manual.contains("③ 音色"))
        assertFalse(manual.contains("④ 通话行为"))
        assertFalse(manual.contains("⑤ 提醒与通知"))
        assertFalse(manual.contains("⑥ 外观"))
        assertFalse(manual.contains("⑦ 权限"))
        assertFalse(manual.contains("第 ① 组"))
        assertFalse(manual.contains("第 ⑤ 组"))
        assertFalse(manual.contains("只有 ①④⑤ 这三个多条目板块带小标题"))
        assertFalse(manual.contains("设置主页第 ④ 组"))
        assertFalse(manual.contains("设置主页第 ⑤ 组"))
        assertFalse(manual.contains("设置主页第 ⑥ 板块"))
        // 联网搜索不能再挂在「定时主动消息」下
        assertFalse(manual.contains("设置 → ⑤ 提醒与通知 → 定时主动消息 → 联网搜索"))
        assertFalse(manual.contains("然后在「定时主动消息」页底部的「联网搜索」里选一家"))
    }

    /**
     * v0.54.3：字幕一屏 6 行 + 越远越小 / 长按消息菜单改微信式并加「多选」/ AI 图库可删记录。
     * 每条 replace 都配一对断言（新措辞 contains + 旧措辞 assertFalse）——
     * `String.replace` 落空是**静默不生效**的，只写一边守不住。
     */
    @Test
    fun `v0543 微信式长按菜单与图库删除进手册`() {
        // ① 通话字幕：一屏最多 6 行 + 越旧越淡且越小
        assertTrue(manual.contains("面板**一屏最多显示 6 行文字**"))
        assertTrue(manual.contains("**最新一句最亮最大，越旧越淡、越小**"))
        assertFalse(manual.contains("默认只露出**最近四段**"))
        assertFalse(manual.contains("每段最多两行"))
        assertFalse(manual.contains("**最新一句最亮、越旧越淡**"))
        // ② 长按消息菜单：改微信式「气泡旁图标菜单」+ 新增「多选」（批量复制 / 转发 / 删除）
        // v0.54.6：菜单改成"只显示图标"、思考过程改就地展开 —— 具体断言移到 v0546，这里只留仍成立的
        assertTrue(manual.contains("**不再是底部卡片**"))
        assertTrue(manual.contains("**点「多选」进入多选模式**"))
        assertTrue(manual.contains("**批量复制 / 转发到其它会话 / 删除**"))
        assertFalse(manual.contains("长按任意消息弹出菜单，点其中的项才执行"))
        assertFalse(manual.contains("（微信那种：图标在上、文字在下，你发的靠气泡右侧、AI 发的靠左侧，点空白处收起），"))
        // ③ AI 图库：长按一条记录可删除（连同本机图片文件）
        assertTrue(manual.contains("**长按一条记录可以删除**"))
        assertFalse(manual.contains("支持捏合缩放与双击放大）、设置（"))
    }

    /**
     * v0.54.4：会话设置整页化（含专属铃声 / 定时主动消息 / 人设主动关心）+ 设置页瘦身
     * + 铃声可多条/按角色 + 崩溃记录右上角红字 + 提示词库改版。
     * 每条 replace 都配一对断言（新措辞 contains + 旧措辞 assertFalse）——
     * `String.replace` 落空是**静默不生效**的，只写一边守不住。
     */
    @Test
    fun `v0544 会话设置整页化与设置页瘦身进手册`() {
        // ① 三个点 = 整页「会话设置」（原来那九项 + 专属铃声/定时消息/主动关心）
        assertTrue(manual.contains("右上角**三个点打开的是一个整页**"))
        // v0.54.6：整页改灰线行、三个入口改成底部弹层 —— 具体措辞见 v0546，这里只钉仍成立的
        // v0.56.8 第二批：措辞补上"每个角色各一套"（见 v0568c），这里跟着改成最终文案
        // v0.94：行更名「主动关心」+ 弹层改成三开关（见 v0_94 那条测试），旧枚举措辞作废
        assertFalse(manual.contains("**人设主动关心**（点开是从底部升上来的弹层：这个角色开不开、**这个角色的发送时间段**、**这个角色每天最多几条**——条数**填什么数字都行**"))
        assertTrue(manual.contains("不超过就行；以及**每天最多几通电话**，1–9）——最后三项只对这个角色生效。"))
        assertFalse(manual.contains("右上角**三个点**是一个菜单（共九项）"))
        assertFalse(manual.contains("另外还有**专属铃声**、**定时主动消息**、**人设主动关心**（这三项只对这个角色生效）。"))
        // 输入栏概述里那句"三个点 = 编辑人设"也跟着改成整页会话设置
        assertTrue(manual.contains("单聊顶栏右侧三个点打开的是**整页会话设置**"))
        assertFalse(manual.contains("单聊顶栏右侧三个点是编辑人设（名字/身份/头像/设定词/音色）"))
        // 联网 Key 的路径（由更早的链式替换落到「模型配置 → 联网搜索」，这里只钉最终形态）
        assertTrue(manual.contains("（用的是 设置 → 模型配置 → 联网搜索 里配好的 Key）；"))
        assertFalse(manual.contains("通知与提醒 → 定时主动消息 → 联网搜索"))
        // ② 设置里不再放「定时主动消息」与「人设主动关心」
        // v0.54.5：这两句因"主动打电话也进角色页"被改写 —— 新措辞断言在 v0545，这里只钉旧句消失
        assertFalse(manual.contains("**定时主动消息与专属铃声在每个角色会话右上角 ⋮ 打开的整页设置里**"))
        assertFalse(manual.contains("定时主动消息与专属铃声在每个角色会话右上角 ⋮ 那一页里"))
        assertTrue(manual.contains("**提醒与通知**（任务到点提醒 / 手机操作进行中）、"))
        // v0.89.0：会话设置页那一行现在的真名是「定时消息」（v0.58.1 起条目收进弹层），
        // 这里跟着改成最终文案（这次改动的缘由见 v0_89_0 那条测试）。
        // v0.94：那一行又更名「定时」（定时电话并进同一张列表），跟着改成最终文案
        assertTrue(manual.contains("入口 每个角色会话右上角 ⋮ → 整页设置里的「定时」（它和任务提醒是同一类「到点提醒」的行为；v0.94 起**定时电话也在这张列表里**"))
        assertTrue(manual.contains("设置主页的**「提醒与通知」卡**点进去只剩两个开关"))
        assertFalse(manual.contains("**提醒与通知**（定时主动消息 / 任务到点提醒 / 手机操作进行中 / 人设主动关心）、"))
        assertFalse(manual.contains("定时主动消息在「提醒与通知」里"))
        assertFalse(manual.contains("入口 设置 → 提醒与通知 → 「定时主动消息」"))
        assertFalse(manual.contains("最上面「定时主动消息」入口 + 下面三项开关）"))
        // ③ 铃声：可多条 + 选默认 + 每角色专属（回落链）
        assertTrue(manual.contains("铃声**可以加多条**、在设置里**选一条设为默认**"))
        assertTrue(manual.contains("**每个角色还能单独指定专属铃声**"))
        assertTrue(manual.contains("「角色专属 → 默认铃声 → 系统默认」的顺序回落"))
        // ④ 崩溃记录：右上角红色入口 + 独立整页
        assertTrue(manual.contains("这一页**右上角会多出一个红色的「崩溃记录」**"))
        assertTrue(manual.contains("点开是独立整页"))
        assertFalse(manual.contains("会出现「上次崩溃记录」一行"))
        // ⑤ 设置主页搜索行（点一下才输入）
        // v0.56.8：这一句被改写成"（v0.56.8 定稿）+ 常驻放大镜"（见 v0568b），这里跟着改成最终文案
        assertTrue(manual.contains("设置页怎么用（v0.56.8 定稿）"))
        assertFalse(manual.contains("设置页怎么用（v0.54.4 定稿）"))
        assertFalse(manual.contains("设置主页顶部是一条**搜索行**"))  // v0.56.8：搜索框删掉，只留常驻放大镜（见 v0568b）
        assertFalse(manual.contains("设置主页顶部是**搜索框**"))
        // ⑤ 提示词库：标题加大 / 只留「框架」「策略」/ 改动过的段才显示「恢复 / 保存」
        assertTrue(manual.contains("每段标题字号加大、只保留「框架」「策略」四个字"))
        assertTrue(manual.contains("**改动过的段，它的提示词下方才会出现「恢复 / 保存」两项**"))
        assertTrue(manual.contains("没改动的段不显示；页面底部不再有全局「保存」按钮"))
        assertFalse(manual.contains("改坏了每段都有「恢复默认」按钮兜底。"))
        assertFalse(manual.contains("（折叠列表，点一段展开编辑；改过的段标有「已自定义」）"))
        // ⑥ 联网入口改成"⋮ 打开的整页里"
        assertTrue(manual.contains("配好后：单聊页右上角 ⋮ 打开的整页里打开「联网」，"))
        assertFalse(manual.contains("配好后：单聊页右上角菜单打开「联网」，"))
    }

    /**
     * v0.54.5：AI 主动打电话从设置里删除改按角色 / 接听延迟卡片化 / 通话行为瘦身。
     * 每条 replace 都配一对断言（新措辞 contains + 旧措辞 assertFalse）——
     * `String.replace` 落空是**静默不生效**的，只写一边守不住。
     */
    @Test
    fun `v0545 主动打电话改按角色与接听延迟卡片进手册`() {
        // ① 入口与开关性质：总开关没了，配置在**每个角色**的整页设置里
        // （v0.54.6：卡片改叫「电话」、开关挪进底部弹层；v0.54.7：弹层内容与定时消息对齐 —— 见 v0547）
        // v0.94：入口改到「主动关心」行 → 弹层里的「主动打电话」开关
        assertTrue(manual.contains("入口 每个角色会话右上角 ⋮ → 整页设置里的「**主动关心**」行 → 弹层里「**主动打电话**」开关"))
        assertFalse(manual.contains("（入口 设置 → 通话行为 → 「AI 主动打电话」，**总开关默认关**）"))
        assertFalse(manual.contains("打开总开关后，**每个 AI 人设单独配**三样"))
        // 点名要的一通不再提"总开关"
        assertTrue(manual.contains("**你点名要的这一通不受规则限制**"))
        assertFalse(manual.contains("不受总开关与人设规则限制"))
        // 全屏通知的直达入口改指「权限」页
        assertTrue(manual.contains("设置 → 权限页里的「全屏通知（锁屏来电）」就有直达系统设置的按钮"))
        assertFalse(manual.contains("设置 → 通话行为 → AI 主动打电话那一页里就有直达系统设置的按钮"))
        // ② 语音章总述 / 设置页怎么用的路径清单：主动打电话归到角色页、通话行为里只剩铃声
        // v0.94：主动打电话/定时都已搬出这句话所在的位置，改钉新表述
        assertTrue(manual.contains("**语音通话铃声在「通话行为」里；专属铃声在每个角色会话右上角 ⋮ 打开的整页设置里"))
        assertFalse(manual.contains("允许主动打电话、定时主动消息与专属铃声都在每个角色会话右上角 ⋮ 打开的整页设置里"))
        assertTrue(manual.contains("**通话行为**（语音通话铃声）、"))
        // v0.56.5 起这句话里多了「接听延迟」——措辞已随该版 replace 改掉，这里对齐到新形态
        assertTrue(manual.contains("模型配置点进去还有五张子卡，通话行为里是「语音通话铃声」与「接听延迟」两张"))
        // ③ 子卡清单里不再说「通话行为」卡里挂 AI 主动打电话子卡
        // （v0.54.6：那句话已被改成「电话」行 —— 见 v0546）
        assertFalse(manual.contains("每个角色会话右上角 ⋮ 的整页设置里有一张**「允许主动打电话」**卡片"))
        assertFalse(manual.contains("设置主页的「通话行为」卡里有一张**「AI 主动打电话」**子卡"))
        assertFalse(manual.contains("**通话行为**（AI 主动打电话 / 语音通话铃声）、"))
        // ④ 接听延迟（v0.56.5 起**独立成项**；v0.56.6 起改成自己填秒数 —— 见 v0566）
        assertTrue(manual.contains("AI 接听延迟"))
        // v0.56.5 的固定档位措辞已随 v0.56.6 的 replace 改掉
        assertFalse(manual.contains("那一页直接列出 立即 / 2 / 3 / 5 / 10 / 15 / 30 秒"))
        assertFalse(manual.contains("卡片「接听延迟」，右边直接选"))
        assertFalse(manual.contains("——**直接填秒数，0 = 立刻接**（最多 30）。"))
        // ⑤ 手册里不该再有"设置 → 通话行为 → 「AI 主动打电话」"这类死路径
        assertFalse(manual.contains("设置 → 通话行为 → 「AI 主动打电话」"))
    }

    /**
     * v0.54.6：会话设置页去卡片改灰线行 + 电话/铃声/定时消息三个底部弹层 +
     * 长按菜单只留图标 + 思考过程就地展开 + 通话记录卡可长按。
     * 每条 replace 都配一对断言（新措辞 contains + 旧措辞 assertFalse）——
     * `String.replace` 落空是**静默不生效**的，只写一边守不住。
     */
    @Test
    fun `v0546 会话设置灰线行与长按菜单瘦身进手册`() {
        // ① 会话设置整页改灰线行；三个入口（电话 / 铃声 / 定时消息）
        assertTrue(manual.contains("整页是**一条条用灰线分隔的行**（不再用卡片）"))
        // v0.94：「电话」行已删（主动电话并入主动关心、定时电话并入定时），旧行名作废
        assertFalse(manual.contains("**电话**（点开是底部弹层：主动电话 / 定时电话，**点标题才展开细项**）"))
        assertTrue(manual.contains("**铃声**（点开是底部弹层的铃声列表）"))
        // v0.94：「定时消息」行更名「定时」，旧行名作废
        assertFalse(manual.contains("**定时消息**（点开就是添加弹层，"))
        assertFalse(manual.contains("联网（开/关）、音色、模型，另外还有**专属铃声**、**定时主动消息**、"))
        // 「允许主动打电话」卡片 → 「电话」行 + 弹层
        // v0.94：「电话」行没了，入口改到「主动关心」弹层（见 v0_94 那条测试）
        assertFalse(manual.contains("每个角色会话右上角 ⋮ 的整页设置里有一行**「电话」**（点开是底部弹层）"))
        assertFalse(manual.contains("允许主动打电话、定时主动消息与专属铃声都在每个角色会话右上角 ⋮ 打开的整页设置里"))
        assertFalse(manual.contains("卡片右边就是开关；点卡片右侧的箭头弹出详情"))
        assertFalse(manual.contains("① 每天几点检查（默认 21:00）、② 距上次跟 TA 聊天多久没交流才打"))
        // ② 长按菜单：只留图标、缩小
        assertTrue(manual.contains("**长按任意消息 = 在气泡旁边弹出一排小图标**"))
        assertTrue(manual.contains("**只显示图标、不再显示文字**"))
        assertFalse(manual.contains("（微信那种：图标在上、文字在下，你发的靠气泡右侧、AI 发的靠左侧，点空白处收起），"))
        // ③ 思考过程就地展开（可折叠、字小色淡）
        assertTrue(manual.contains("**点「思考过程」会在那条消息下方就地展开一段可折叠的思考内容**"))
        assertTrue(manual.contains("（字号小、颜色淡；点「思考过程 · 点此收起」那行即可收起）"))
        // ④ 语音通话记录卡长按也能弹菜单
        assertTrue(manual.contains("**语音通话记录卡长按也能弹出这个菜单**（点它仍是打开通话转写）。"))
    }

    /**
     * v0.54.7：电话弹层与「定时消息」对齐（几点打过来 + 说什么 + 天数自填）+
     * 聊天背景二选一且真正生效 + 长按补震动。
     * 每条 replace 都配一对断言（新措辞 contains + 旧措辞 assertFalse）——
     * `String.replace` 落空是**静默不生效**的，只写一边守不住。
     */
    @Test
    fun `v0547 电话弹层对齐定时消息与聊天背景进手册`() {
        // ① 电话：v0.54.7 的"几点打过来 + 说什么 + 天数自填"三栏已被
        //    **v0.56.6 的「允许主动打电话 + 定时电话」**取代（见 v0566），这里只留仍然成立的两条
        assertFalse(manual.contains("弹层里四样 —— ① 开关「允许主动打电话」；"))
        assertFalse(manual.contains("弹层里三样 —— ① 开关「允许主动打电话」；"))
        assertFalse(manual.contains("② **什么时候打过来**（时间表盘直接拨，默认 21:00，不用自己输）"))
        assertFalse(manual.contains("（时间表盘直接拨"))
        // ② 聊天背景：二选一 + 真正生效。
        //    ⚠️ 同批的「角色立绘 / Live 2D 仍是只存不显」到 v0.68.0 已作废（整句必须消失）；
        //    「③ 音色」这种带圈序号的写法也别往手册里加 —— 会与更早那条
        //    `assertFalse(manual.contains("③ 音色"))`（设置主页旧分组措辞的守卫）撞车。
        assertFalse(manual.contains("其中**角色立绘 / Live 2D 仍是只存不显**"))
        assertTrue(manual.contains("**聊天背景已经能用**：点它会从底部弹出两个选项 ——「原生背景」与「自定义」"))
        assertTrue(manual.contains("选完**这个角色的聊天页就会铺上这张图**"))
        assertFalse(manual.contains("**角色立绘 / Live 2D / 聊天背景现在只存不显**"))
        // ③ 长按补震动
        assertTrue(manual.contains("**长按还会有一记震动反馈**"))
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
        assertTrue(manual.contains("下面**两块环带选择区**「取消」「转文字」"))
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
        // 提示词段数跟着改过多次（v0.68.0 八→九、v0.87.0 九→八、v0.88.0 八→十）。
        // ⚠️ 这里**不钉具体数字**（下一版一改就必红），只钉"段数说明在、且旧数字不在"。
        assertTrue(manual.contains("段提示词都可编辑"))
        assertFalse(manual.contains("七段提示词都可编辑"))
        assertFalse(manual.contains("八段提示词都可编辑"))
        assertFalse(manual.contains("九段提示词都可编辑"))
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
        // v0.54.4：三个点打开的是整页会话设置（不再是"编辑人设"那一句）
        assertTrue(manual.contains("单聊顶栏右侧三个点打开的是**整页会话设置**"))
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

    /**
     * v0.56.5：**「接听延迟」从「语音通话铃声」那一页里拎出来**，
     * 成为「通话行为」页上与它同级的一张卡；「联网搜索」页补「测试搜索」。
     */
    @Test
    fun `v0565 接听延迟独立成项与联网搜索自测进手册`() {
        // ① 通话行为页现在是两张卡
        assertTrue(manual.contains("通话行为里是「语音通话铃声」与「接听延迟」两张，"))
        assertFalse(manual.contains("通话行为里是语音通话铃声，"))
        // ② 接听延迟的入口写法：不再挂在铃声页里，而是与它同级
        assertTrue(manual.contains("入口 设置 → 通话行为 → 「接听延迟」，与「语音通话铃声」**并排两张卡**"))
        assertFalse(manual.contains("就在「语音通话铃声」那一页里"))
        // ③ 联网搜索页的「测试搜索」（这条 replace 是**追加**式：目标串仍在新文案开头，
        //    所以只钉"追加的那段在"，旧措辞无从断言）
        assertTrue(manual.contains("那一页点一下「测试搜索」"))
        assertTrue(manual.contains("通了会告诉你取到几条、耗时多少"))
    }

    /**
     * v0.56.6：电话改成「允许主动打电话（总闸）+ 定时电话（按时间点添加）」/
     * 接听延迟改自填秒数 / 相册与文件先挂到输入框上方。
     */
    @Test
    fun `v0566 定时电话与接听延迟自填秒数进手册`() {
        // ① 电话弹层：两块（总闸 + 定时电话），不再有"多久没聊才打"
        // v0.94：「电话」行与弹层整个删除（主动电话并入主动关心、定时电话并入定时），旧措辞全作废
        assertFalse(manual.contains("**电话**（点开是底部弹层：主动电话 / 定时电话，**点标题才展开细项**）"))
        assertFalse(manual.contains("允许主动打电话 / 几点打过来 / 说什么 / 多久没聊才打"))
        assertFalse(manual.contains("弹层里两块（**点标题才展开**，页内没有小字说明）—— "))
        assertFalse(manual.contains("弹层里两块 —— ① 开关「**允许主动打电话**」（总闸，关掉则下面一条都不会打）；"))
        assertFalse(manual.contains("② 「**定时电话**」：展开后是一条条定时电话"))
        assertTrue(manual.contains("填**每天几点打给你**和**接通后说什么**"))
        // v0.56.7：这条"不再有"的话反过来 —— 用户要求把「X 天没聊就主动打」加回来（见 v0567）
        assertFalse(manual.contains("**已经不再有「多久没聊才打」这种触发方式**。"))
        assertFalse(manual.contains("④ **多久没聊才打**（**天数自己填 1–30**，默认 3 天；填 1 就是到点必打）。"))
        assertFalse(manual.contains("② **几点打过来**（手填 HH:mm，跟「定时消息」一个填法，默认 21:00）；"))
        // ② 接听延迟：自己填秒数
        assertTrue(manual.contains("那一页**自己填秒数**（0 = 立即接，最多 600 秒），填好点「保存」。"))
        assertFalse(manual.contains("那一页直接列出 立即 / 2 / 3 / 5 / 10 / 15 / 30 秒"))
        // ③ 相册 / 文件：先停在输入框上方
        assertTrue(manual.contains("**相册与文件选中后先停在输入框上方**，不再选完就发"))
        assertFalse(manual.contains("面板四格：相册（发照片给 AI 看"))
    }

    /**
     * v0.56.7：电话更名「主动电话」+ 点标题展开 + 「X 天没聊就主动打」回来 /
     * 「按住说话」按屏幕半区判档 / 用量按模型分别列。
     */
    @Test
    fun `v0567 主动电话与半区手势与按模型用量进手册`() {
        // ① 电话：更名 + 点标题展开
        // v0.94：「几天没聊就主动打」整条下线（并入主动关心的随机时刻），旧措辞全作废
        assertFalse(manual.contains("① 「**主动电话**」：展开后只填**几天没聊就主动打**（自己填 1–30 天，默认 3 天）—— "))
        assertFalse(manual.contains("与**每天几点检查**（默认 21:00）"))
        assertFalse(manual.contains("② 「**定时电话**」：展开后是一条条定时电话"))
        assertFalse(manual.contains("① 开关「**允许主动打电话**」"))
        assertFalse(manual.contains("**已经不再有「多久没聊才打」这种触发方式**。"))
        // ② 按住说话：按屏幕左右半区判档
        assertTrue(manual.contains("**左半区 = 取消、右半区 = 转文字、正中间那条灰带 = 录音中**"))
        assertTrue(manual.contains("两块环带选择区"))
        assertFalse(manual.contains("两块扇形选择区"))
        // ③ 用量：v0.56.8 起挪到模型卡片上（位置又在 v0.91.0 从"右侧"改成"下方"，见 v0820 那条）
        assertFalse(manual.contains("**本月 API 用量，按模型分别列出各自的 tokens**"))
        assertFalse(manual.contains("面板底部显示**本月 API 用量**（服务商返回用量数据时才统计）。"))
    }

    /** v0.56.8：主动电话删掉「每天几点检查」/ 用量改显示在模型卡片上。 */
    @Test
    fun `v0568 主动电话去检查时刻与用量进模型卡进手册`() {
        // v0.94：「几天没聊就主动打」与「每天固定检查一次」整套说法随那条触发链下线
        assertFalse(manual.contains("展开后只填**几天没聊就主动打**（自己填 1–30 天，默认 3 天）—— "))
        assertFalse(manual.contains("每天固定检查一次（时刻沿用这个角色存着的值，默认 21:00）"))
        assertFalse(manual.contains("与**每天几点检查**（默认 21:00）"))
        // ⚠️ 这里只钉"用量进卡片了"这个不随位置变的事实：措辞在 v0.91.0 由"右侧"改成"下方"，
        // 钉死方向词等于给下一版埋一颗必红的雷（手册断言只钉事实、不钉版本相关措辞）。
        assertTrue(manual.contains("**每个模型卡片的") && manual.contains("直接显示这个模型本月消耗了多少**"))
        assertFalse(manual.contains("面板底部显示**本月 API 用量，按模型分别列出各自的 tokens**"))
    }

    /** v0.56.8 第二批：主动关心改随机时刻/可设条数、开关进弹层；两处搜索框删掉只留放大镜。 */
    @Test
    fun `v0568b 主动关心随机时间与搜索框改造进手册`() {
        // ① 主动关心：开关进弹层 + 时间段 + 每天条数 + 贴合时间（最终措辞见 v0568c）
        // v0.94：行更名「主动关心」+ 弹层改三开关，旧行名措辞作废
        assertFalse(manual.contains("**人设主动关心**（点开是从底部升上来的弹层：这个角色开不开"))
        assertTrue(manual.contains("它是**上限不是定额**：当天在这个数以内随机发几条，不超过就行"))
        assertFalse(manual.contains("人设会在**你设的时间段里随机**发来主动问候"))
        assertFalse(manual.contains("人设每晚最多一条主动问候"))
        // ② 消息页：v0.56.8 第二批**连放大镜一起删掉**（见 v0568c），这里只钉旧句消失
        assertFalse(manual.contains("顶部只有标题右侧那颗**常驻放大镜**"))
        assertFalse(manual.contains("顶部有搜索框（拉到列表最顶部时出现"))
        // ③ 设置页：仍然只留常驻放大镜（不受消息页这次改动影响）
        assertTrue(manual.contains("设置页怎么用（v0.56.8 定稿）：设置主页**没有搜索框**，只有顶栏右边那颗**常驻放大镜**"))
        assertFalse(manual.contains("设置主页顶部是一条**搜索行**"))
    }

    /**
     * v0.56.8 第二批修正（用户点名）：
     * ① 每天最多几条**任何数字都行**，且它是**上限不是定额**（在这个数以内随机发几条）；
     * ② **每个角色各有一套**时间段与条数；③ 消息页**不做搜索**，放大镜一起删掉。
     */
    @Test
    fun `v0568c 主动关心按角色一套上限随机与消息页去搜索进手册`() {
        // ① 条数是上限不是定额 + 什么数字都能填
        assertTrue(manual.contains("条数**填什么数字都行**，它是**上限不是定额**：当天在这个数以内随机发几条，不超过就行"))
        // ② 每个角色各一套
        assertTrue(manual.contains("主动关心是**每个角色各有一套**时间段与每天最多几条"))
        // v0.94：「这个角色每天最多几条」→「每天最多几条」（前面已有「这个角色的发送时间段」）
        assertTrue(manual.contains("外加这个角色的**发送时间段**、**每天最多几条**"))
        assertFalse(manual.contains("**发送时间段**、**每天最多几条**）——最后三项"))
        // ③ 消息页没有搜索框也没有放大镜、连"可搜人设名/身份"那句尾巴也没有；设置页那只放大镜仍在
        assertTrue(manual.contains("顶部只有标题，**既没有搜索框也没有放大镜**——消息页**不做搜索**。"))
        assertFalse(manual.contains("顶部有搜索框（拉到列表最顶部时出现"))
        assertFalse(manual.contains("可搜人设名/身份/消息内容"))
        assertTrue(manual.contains("设置主页**没有搜索框**，只有顶栏右边那颗**常驻放大镜**"))
    }

    /**
     * v0.56.13（用户讨论拍板）：自定义端点的手填音色 ID 从**单条**升级成**多条音色库**——
     * 那家没有预置也没有克隆，「＋ 添加音色」是它唯一的音色来源，可起显示名、可试听、长按可删。
     */
    @Test
    fun `v05613 自定义端点多条音色库进手册`() {
        assertTrue(manual.contains("自定义端点那家没有预置也没有克隆"))
        assertTrue(manual.contains("在音色页点**「＋ 添加音色」**自己录入，想加几条加几条（可起显示名、可试听、长按可删）"))
        // 旧措辞消失：不再是"一条「手填音色 ID」"
        assertFalse(manual.contains("和一条「手填音色 ID」"))
    }

    /** v0.57：模型配置页新增「获取可用模型」（填好地址 + Key 后拉列表点选，不用手打模型名） */
    @Test
    fun `v057 获取可用模型进手册`() {
        assertTrue(manual.contains("也能**获取可用模型**"))
        assertTrue(manual.contains("点一个直接填入模型名，不用手打"))
        // 旧措辞消失：不再是"预设一键填入，支持…"
        assertFalse(manual.contains("预设一键填入，支持"))
    }

    /**
     * v0.57：退出对话页后 AI 继续生成（生成改挂应用级作用域，不再绑在聊天页上）。
     *
     * 这条 replace 是**在锚点后面追加**、锚点本身还在（"（点它仍是打开通话转写）。"仍是新句的前缀），
     * 所以只有"新句在"这一向能守：`replace` 一旦落空，下面第一条断言立刻红。
     */
    @Test
    fun `v057 退出对话继续生成进手册`() {
        assertTrue(manual.contains("**退出这个对话页 AI 也会继续生成**"))
        assertTrue(manual.contains("回到对话能看到它还在生成"))
        assertTrue(manual.contains("切回会话列表、去看别的角色都不打断"))
    }

    /**
     * v0.58：视频通话上线（第五格入口 + Live2D 模型包 + 伪视频状态指认）。
     *
     * 两条 replace 一条改写、一条追加，都锚在 v0.38 底稿「实时语音通话」那一行：
     * - 改写那条要**双向断言**：新写法在 + 旧写法"第四格「通话」"不在；
     *   加号面板已经从四格变五格，旧措辞留在手册里就是教用户去点不存在的位置。
     * - 追加那条只能断"新句在"（锚点本身还在），落空时第一条断言就红。
     */
    @Test
    fun `v058 视频通话进手册`() {
        assertTrue(manual.contains("- **视频通话**（v0.58.0）：加号面板第五格「视频」"))
        assertTrue(manual.contains("① 「Live2D 模型包」= 上传一个 **zip 模型包**"))
        assertTrue(manual.contains("② 「伪视频素材」= 上传几段 **mp4**"))
        // 只有待机必填、其余状态回退 —— 用户最容易在这里做错
        assertTrue(manual.contains("**只有「待机」必填**"))
        // 故障隔离是这功能最硬的一条承诺，手册必须能答"形象坏了电话会不会断"
        assertTrue(manual.contains("**只把画面退回头像，电话照打**"))
        // 新写法生效：通话格不再报序号
        assertTrue(manual.contains("加号面板 → 「通话」格，"))
        // 旧措辞必须消失
        assertFalse(manual.contains("第四格「通话」"))
    }

    /**
     * v0.58.1：会话设置里 Live 2D / 伪视频 **合成一行「视频通话形象」**，
     * 另外「角色立绘」在**编辑人设**页重新出现，用途是 AI 生图时的参考图（图生图）。
     *
     * 两条都是改写，所以照项目规矩**双向断言**：新措辞在 + 旧措辞不在
     * （"那里两行""旧的「角色立绘」入口已删除"都还在的话，说明 replace 静默落空了）。
     */
    @Test
    fun `v058_1 视频通话形象合并与立绘进手册`() {
        assertTrue(manual.contains("会话设置 → 「视频通话形象」** 里配"))
        assertTrue(manual.contains("不再是 Live 2D / 伪视频 两行"))
        // ⚠️ v0.60.1 把这一段的措辞改成了"（入口 **编辑人设** 页，位置在**「身份」下面那一栏**）"，
        // 而这条断言当时没跟着改 —— 于是它从 v0.60.1 起一直是红的（v0.64.0 顺手修正）。
        assertTrue(manual.contains("- **角色立绘**（入口 **编辑人设** 页，位置在**「身份」下面那一栏**）"))
        assertTrue(manual.contains("把它当参考图一并发给生图服务（图生图）"))
        // 不认参考图的家要能说出来，否则用户以为那张一定像 TA
        assertTrue(manual.contains("这张没按立绘生成"))
        // 旧措辞必须消失
        assertFalse(manual.contains("里传，那里两行："))
        assertFalse(manual.contains("旧的「角色立绘」入口已删除"))
    }

    /**
     * v0.59.0：朗读回来、语音识别可切在线接口、联网搜索可自定义。
     *
     * 三条都是**在锚点后追加**（锚点本身还在），所以只能断"新句在"——
     * replace 静默落空时第一条断言就红。
     * 用户问"怎么让 TA 念给我听 / 在线识别怎么配 / 搜索换成我自己的接口"时，
     * 手册必须答得上来（AGENTS §零.5：滞后比没有助手更糟）。
     */
    @Test
    fun `v059 朗读 在线识别 自定义搜索 进手册`() {
        assertTrue(manual.contains("- **朗读**（v0.59.0）：**长按任意一条消息 → 「朗读」**"))
        // 开关是**按角色**的，位置也变了（v0.59.0 用户拍板：会话设置第一组最后那行）。
        // 这是改写不是追加，所以双向断言：新位置在 + 旧位置"设置 → 语音模型"那句不在。
        assertTrue(manual.contains("**按角色各开各的**「自动朗读」开关"))
        assertTrue(manual.contains("**这个角色的会话设置第一组最后那行**"))
        assertFalse(manual.contains("另有「设置 → 语音模型 → **自动朗读 AI 的回复**」开关"))
        assertTrue(manual.contains("**默认关**——开着它每来一条回复都会发一次合成请求"))
        // 通话不跟着切在线，这条最容易答错，必须在手册里写死
        assertTrue(manual.contains("**实时语音通话始终用端侧离线识别**"))
        assertTrue(manual.contains("选了在线就只走在线，失败会把原因原样说出来"))
        assertTrue(manual.contains("- **联网搜索加了「自定义」**（v0.59.0）"))
        // ⚠️ 这句当年只对了一半（自定义那一家确实独立，博查与 Tavily 之间是共用的），
        // v0.66.0 整段改写成"三家各有一份"，所以旧句必须消失、新句必须在了 ——
        // 见 `v0660 联网搜索每家一份 Key 与通话背景 进手册`。
        assertFalse(manual.contains("自定义家有**独立的一份 Key**"))
        assertTrue(manual.contains("**三家各有一份 Key**"))
    }

    /**
     * v0.60.1（用户点名）：角色立绘的入口形状与位置都变了 —— 从「上传立绘」按钮
     * 变成"点输入框选图、框里显示文件名"，并且挪到「身份」下面。
     * 这条是**改写**不是追加，所以双向断言：新措辞在 + 旧那句"（v0.58.1，入口 编辑人设 页）：上传一张"必须不在
     * （replace 的子串对不上时是静默落空，只断新句守不住）。
     */
    @Test
    fun `v0601 角色立绘入口改写成输入框 进手册`() {
        assertTrue(manual.contains("位置在**「身份」下面那一栏**"))
        assertTrue(manual.contains("**点输入框**选一张这个角色的全身/半身图"))
        assertTrue(manual.contains("框里显示这张图的**文件名**"))
        assertTrue(manual.contains("「上传立绘」按钮已经删掉"))
        assertFalse(manual.contains("- **角色立绘**（v0.58.1，入口 **编辑人设** 页）：上传一张"))
        // 头像那颗钮：改名 + 常驻 + 细线胶囊（同一条 v0.60.1 里的第二处入口变化）
        assertTrue(manual.contains("头像下面**恒定有一颗小胶囊**"))
        assertTrue(manual.contains("还是默认头像时写「点击编辑」"))
        assertTrue(manual.contains("换过图之后写「恢复默认」"))
        assertFalse(manual.contains("换过图旁边会出现「恢复默认头像」"))
    }

    /**
     * v0.60.1（用户点名"会话删了就找不到了"）：人设管理页每张卡右边那颗气泡图标 = 进 TA 的会话，
     * 这是被 hidden 过滤掉的会话**唯一**的找回路（进去一次 ChatViewModel.init 就 setHidden(false)）。
     *
     * 底稿那句「发新消息即恢复」在实践里等于回不来（会话进不去，用户没法主动发那条新消息），
     * 手册要是还这么写，用户问"我删掉的聊天怎么找回来"就会得到一句做不到的话 —— 双向断言都上。
     */
    @Test
    fun `v0601 删除会话的找回路 进手册`() {
        assertTrue(manual.contains("**想再聊回来**：我的 → 人设管理 → 点那张卡**右边的聊天气泡图标**"))
        assertTrue(manual.contains("进去一次这个会话就回到消息列表了"))
        assertTrue(manual.contains("**每张卡右边那颗气泡图标 = 直接进 TA 的会话**"))
        assertFalse(manual.contains("人设保留，发新消息即恢复"))
    }

    /**
     * v0.62.0 建备忘本 → v0.63.0 按用户拍板定形：时间**自动**（记下的那一刻）、**地点删掉**、
     * **全程不弹窗**、卡里**只摊一条**。
     *
     * 双向断言在这里特别重要：v0.62.0 那一版写的是"可标的时间 + 可标的地点 + 点某条打开编辑弹层"，
     * 这句要是还留在手册里，用户问"怎么标地点"时助手会教一套已经不存在的手填表单。
     */
    @Test
    fun `v0620 备忘本 进手册`() {
        // ⚠️ v0.73.0：备忘本整块下线。这一格改成「任务」入口，手册里那段备忘的用法说明
        // **必须一句不剩** —— 留着就是让助手教用户用一个已经不存在的模块（§零.5 同一件事）。
        // 这一版的断言因此全部翻成 assertFalse；新写法的正向断言在 `v0_73_0 手册写明动态与备忘下线`。
        assertFalse(manual.contains("**备忘本**（v0.62.0 建，v0.63.0 定形）"))
        assertFalse(manual.contains("**没有弹窗、也没有要填的表单**"))
        assertFalse(manual.contains("时间**自动记成你按下回车的那一刻**"))
        assertFalse(manual.contains("**没有地点这一项**"))
        assertFalse(manual.contains("AI 平时**读不到**你的备忘"))
        // v0.63.0 记账本流水长按编辑（同一段 replace 里补的，锚点句丢了就是整段没换上）：
        // 这一句在备忘段落**之后**，v0.73.0 换段落时特意留在锚点之外，必须还在
        assertTrue(manual.contains("「编辑这笔」改收支方向/金额/备注/分类"))
        // 旧形状必须整段消失
        assertFalse(manual.contains("一条备忘 = 正文 + 可标的时间 + 可标的地点 + 可置顶"))
        assertFalse(manual.contains("点某条 = 打开编辑"))
        assertFalse(manual.contains("卡片只显示最近 3 条"))
        assertFalse(manual.contains("**标了时间不会到点提醒**"))
    }

    /**
     * v0.62.0（用户点名"任务页长按要像消息页那样呼出菜单，有编辑和删除"）：
     * 底稿那句「长按任务行可编辑或删除」太含糊——用户照着做会以为长按直接改任务，
     * 而现行形状是**长按弹菜单、点删除才真删且不叠确认框**。双向断言都上。
     */
    @Test
    fun `v0620 任务长按菜单 进手册`() {
        assertTrue(manual.contains("长按任务行**会震一下并弹出菜单**（编辑 / 删除"))
        assertTrue(manual.contains("**点菜单里的「删除」就直接删，不再叠一层确认框**"))
        assertFalse(manual.contains("长按任务行可编辑或删除"))
    }

    /**
     * v0.62.0（用户点名"未读不要黑点要红点""AI 发的消息要有提示音、勿扰时只震动"）。
     *
     * 两条都必须双向断言：
     * - 未读点：底稿只写"出现未读点"，那颗点原本是强调色（浅色下=纯黑），
     *   手册不写"红"，用户问"我没收到提醒啊"时助手会答不上来。
     * - 通知声音：普通聊天的 AI 回复**根本不发系统通知**，这是必须写清的事实，
     *   否则助手会让用户去等一条永远不会响的通知。
     */
    @Test
    fun `v0620 未读红点与消息提示音 进手册`() {
        assertTrue(manual.contains("AI 有新回复时行尾亮一颗**红点**"))
        assertTrue(manual.contains("底栏「消息」图标右上角那颗也一样是红的"))
        assertTrue(manual.contains("到点会**响提示音并震动**"))
        assertTrue(manual.contains("自动降成**只震动、不响铃**"))
        assertTrue(manual.contains("普通聊天里 AI 的回复**不发系统通知**"))
        assertFalse(manual.contains("AI 有新回复时出现未读点。"))
    }

    /**
     * v0.62.0（用户点名"主动打电话的时候就算是免打扰也要有铃声"）：
     * v0.53.9 写下的「免打扰时铃声一律不响」到这一版**对 AI 来电已经不成立**
     * （铃声改放闹钟音轨）。这条旧措辞留在手册里 = 助手会教用户"勿扰下没声音是正常的"，
     * 属于错答案，所以旧句必须整段消失、新句必须分清是哪一路。
     */
    @Test
    fun `v0620 AI 来电穿勿扰 进手册`() {
        assertTrue(manual.contains("**AI 主动打来的那一通不吃勿扰**"))
        assertTrue(manual.contains("它吃的是系统的**闹钟音量**那条滑杆"))
        assertTrue(manual.contains("你自己打给 AI 的回铃、以及铃声页的「试听」仍走铃声音量"))
        assertFalse(manual.contains("**手机开免打扰 / 静音 / 振动时铃声一律不响**"))
    }

    /**
     * v0.64.0：Live2D 动作系统上线（角色空间 / 通话动作 / 动作库 / 自己做 / AI 生成）。
     *
     * 这一条 replace 的**锚点是链里更早那条 replace 的产出**（v0.50.0 写进去的收尾句 +
     * 数据与隐私标题），所以它天然脆弱：谁改了那条的收尾措辞，这一整章就会静默落空。
     * 因此这里的断言必须把**每一小节**都点到 —— 只断言一个词的话，
     * 锚点被改坏时测试仍然是绿的，而手册已经少了整章（正是 v0.56.8 那两条的翻版）。
     */
    @Test
    fun `v0640 角色与动作整章 进手册`() {
        // 章节本身
        assertTrue(manual.contains("═══ 角色与动作（v0.64.0）═══"))
        assertTrue(manual.contains("看形象、做动作、眨眼呼吸、拖动、点击全部在本机跑，不联网、不花 token"))
        // 视频通话的形状变了：分区 + 右滑纯净态
        assertTrue(manual.contains("字幕与按钮**不会压到角色身上**"))
        assertTrue(manual.contains("**向右滑**：下面那块对话台整个滑到右边去"))
        assertTrue(manual.contains("**向左滑**把它叫回来"))
        // 通话动作（四态绑定）
        assertTrue(manual.contains("**会话设置 → 「通话动作」**"))
        assertTrue(manual.contains("默认那四套是**系统自带的动作**"))
        // ⚠️ 这句在 v0.67.0 被改写（那一页顶上多了一行「动作幅度」，"只做绑定"的说法不再完整）：
        // 守的是"这页不能编辑动作"这个事实还在，措辞见 `v0670 …` 那条的成对断言。
        assertTrue(manual.contains("这一页只做挑选，不做编辑动作"))
        // 角色空间：v0.92.0 已从会话设置搬到「我的数据」页，会话设置页本身已无此行
        assertTrue(manual.contains("「角色空间」那一行 v0.92.0 已从这一页删掉"))
        assertTrue(manual.contains("入口：**「我的数据」页 → 「角色空间」卡 → 选一个角色**"))
        assertTrue(manual.contains("点头部 → 摇头；点身体 → 摆手；点到旁边空白 → 随机播一个趣味动作"))
        // 动作库
        assertTrue(manual.contains("（四态默认 + 摆手 / 摇头 / 劈叉 / 翻跟斗）"))
        assertTrue(manual.contains("内置动作与模型自带动作**不能删**"))
        // 自己做
        assertTrue(manual.contains("角色空间底部「自己做」"))
        assertTrue(manual.contains("基于内置动作改的会**另存成一条新动作**"))
        // AI 生成
        assertTrue(manual.contains("角色空间底部「AI 生成」"))
        assertTrue(manual.contains("**播放完全在本机**，不会因为反复播放而继续花 token"))
        // 兼容性
        assertTrue(manual.contains("动作**不绑在具体模型上**"))
        assertTrue(manual.contains("**任何模型都演得出来**"))
    }

    /**
     * v0.65.0（用户点名四件事）：备忘本入口卡 / 视频通话对话台透明 / 角色空间背景两档 /
     * 会话设置行右侧显示当前选择。
     *
     * 这里的**旧措辞断言一条都不许省**：这四段全是"改掉旧形状"，
     * 旧句子留在手册里 = 助手会继续教用户点已经不存在的「全部」文字、
     * 或者在数据页卡里找那行根本搬走了的输入框。
     */
    @Test
    fun `v0650 备忘本入口卡与通话台透明与背景两档 进手册`() {
        // ① 备忘本（v0.65.0 形状）—— v0.73.0 整个模块下线，这段说明一句都不许剩
        assertFalse(manual.contains("**一样大小**、**表面不显示任何内容**（不摊条目、不写条数），**点卡片本体才进全屏页**"))
        assertFalse(manual.contains("下面**一条备忘一张卡片**"))
        assertFalse(manual.contains("进页面时卡片**逐条错峰展开**"))
        assertFalse(manual.contains("**点某张备忘卡 = 那张卡原地变成可编辑的文字**"))
        assertFalse(manual.contains("点卡里那行「记点什么…」**就在卡里直接打字**"))
        assertFalse(manual.contains("卡片上**只显示一条**（有置顶就显示置顶那条，没有就显示最新那条）"))
        assertFalse(manual.contains("**点某条备忘 = 那一行原地变成可编辑的文字**"))
        // ② 视频通话：有画面时对话台不再铺白底（旧手册没写过这块的颜色，只需断言新句在）
        assertTrue(manual.contains("**有画面时下面这块对话台不再有白底**"))
        assertTrue(manual.contains("字幕与两颗钮直接浮在画面上"))
        // ③ 角色空间背景 （v0.65.0 只剩两档；⚠️ v0.74.0 又加成三档，措辞改由 `v0_74_0` 断言）
        assertFalse("v0.65.0 的「只有两档」措辞早被 v0.74.0 改写掉", manual.contains("v0.65.0 起**只有两档**"))
        assertFalse(manual.contains("跟随主题底色 / 四张内置渐变 / 从相册选一张"))
        // ④ 会话设置行：音色 / 铃声 右侧写当前选择
        assertTrue(manual.contains("「音色」「铃声」两行的**右边直接写着当前用的是哪一个**"))
        // ⚠️ 这句到 v0.66.0 又被改写（新增「通话背景」一行，"四行"不再成立）：
        // 旧句必须消失，新句由 `v0660 联网搜索每家一份 Key 与通话背景 进手册` 断言。
        assertFalse(manual.contains("「视频通话形象」「角色空间」「通话动作」「聊天背景」四行都是**一行高的纯入口**"))
        // ⑤ 动作幅度放大这一条要说得出口（用户问"动作太小"时助手不能答"去调代码里的常数"）
        assertTrue(manual.contains("整体幅度又放大了一档"))
    }

    /**
     * v0.66.0（用户点名两件事）：联网搜索"切换国内国外 key 都不变"的真相要写进手册，
     * 以及视频通话新加的那行「通话背景」。
     *
     * 旧那句"自定义家有独立的一份 Key，切回博查 / Tavily 不会互相覆盖掉"**必须整段消失**：
     * 它当时只说了自定义那一家，博查与 Tavily 之间一直是共用的——用户照着这句会以为
     * "两家各填各的早就好了"，然后卡在同一个问题上找不到原因。
     */
    @Test
    fun `v0660 联网搜索每家一份 Key 与通话背景 进手册`() {
        assertTrue(manual.contains("**三家各有一份 Key**（v0.66.0 补齐：博查与 Tavily 过去**共用一把**"))
        assertTrue(manual.contains("自动算在**当时生效的那一家**名下，**不用重填**"))
        assertTrue(manual.contains("清空一家也不碰另外两家"))
        assertTrue(manual.contains("**通话背景**（v0.66.0）：入口在会话设置页那行「通话背景」，点开只有两档"))
        assertTrue(manual.contains("**只有视频通话铺背景**，语音通话维持现在的页面底色"))
        // v0.92.0：「角色空间」已不在会话设置这几行里（搬到「我的数据」页），会话设置只剩四行纯入口
        assertTrue(manual.contains("「视频通话形象」「通话背景」「通话动作」「聊天背景」这几行都是**一行高的纯入口**"))
        // 旧措辞（只给自定义那一家发过保证）
        assertFalse(manual.contains("自定义家有**独立的一份 Key**，切回博查 / Tavily 不会互相覆盖掉。"))
        assertFalse(manual.contains("「视频通话形象」「角色空间」「通话动作」「聊天背景」四行都是**一行高的纯入口**"))
    }

    /**
     * v0.67.0（用户点名"全面优化视频通话 / Live2D"）：通话里能点角色、多一排动作胶囊、
     * 会话设置「通话动作」页多一行「动作幅度」，以及这一轮修掉的四个观感问题要答得出口。
     *
     * 「只做绑定，不能编辑动作」那句是**改写**，所以配成对：旧句必须消失、新句必须在了
     * （AGENTS §零.5：只写一边守不住 replace 静默落空）。
     * 纯追加的那两条只需要断新句在了 —— 锚点没匹配上时新句就不会出现，一样抓得住。
     */
    @Test
    fun `v0670 通话里点角色与动作胶囊与动作幅度 进手册`() {
        // ① 通话页新增的两样（点角色 / 胶囊）
        assertTrue(manual.contains("- **点角色有反应**（v0.67.0）"))
        assertTrue(manual.contains("**角色脚边那一排胶囊**（v0.67.0）"))
        assertTrue(manual.contains("**只有 Live2D 形象有这一排**"))
        // ② 动作幅度那一行，以及它和每条动作「强度」的关系
        assertTrue(manual.contains("- **动作幅度**（v0.67.0）：这一页最上面那行，四档 **小 / 标准 / 大 / 特大**"))
        assertTrue(manual.contains("两个是相乘的关系"))
        // ③ 「只做绑定」整句被改写
        assertTrue(manual.contains("- ⚠️ 这一页只做挑选，不做编辑动作。"))
        assertFalse(manual.contains("- ⚠️ 这一页**只做绑定**，不能编辑动作。"))
        // ④ 角色空间那句点角色的说明，后面紧跟的是"同一套"而不是原来的括号注解
        assertTrue(manual.contains("**v0.67.0 起视频通话里也是同一套**"))
        assertFalse(manual.contains("随机播一个趣味动作。（模型自己画了点击区域"))
        // ⑤ 这一轮修掉的四个观感问题：助手要能说出为什么以前怪
        assertTrue(manual.contains("【v0.67.0：动作看起来比从前自然，也更有力】"))
        assertTrue(manual.contains("**切动作不再弹回原位**"))
        assertTrue(manual.contains("**翻跟斗绕的是角色自己**"))
        assertTrue(manual.contains("**画面封顶 60 帧**"))
    }

    /**
     * v0.68.0（全面验收查出四类滞后）：数据页真实顺序 + 今日建议整块缺失、
     * 会话设置那几行还停在 v0.54 且把"立绘 / Live 2D 只存不显"说死、
     * 提示词库段数（八→九）、平板双栏从来没写过。
     *
     * **四条全部配成对**（新措辞在 + 旧措辞不在）：这一类的失败形状是"replace 静默落空"
     * ——不报错、不崩、页面上什么都正常，只有助手在答旧话（AGENTS §零.5）。
     */
    @Test
    fun `v0680 数据页今日建议 会话设置行名 九段提示词 平板双栏 进手册`() {
        // ① 数据页：v0.42.0 起的真实五块 + 今日建议这条独立链路
        assertTrue(manual.contains("从上往下是**五块**（v0.42.0 大改版后用户指定的顺序"))
        assertTrue(manual.contains("② **今日建议**：AI 每天**早上 07:30 自动出一条**"))
        assertTrue(manual.contains("它和「AI 建议」是**两回事**"))
        assertTrue(manual.contains("设置 → 提示词库 → 每日建议策略"))
        assertFalse(manual.contains("2. 数据：顶部一张「本周卡」合并展示"))
        // ② 会话设置：行名跟着 v0.58.1 / v0.64 / v0.66 的结构走，"只存不显"必须作废
        // v0.92.0：「角色空间」从会话设置搬走，视觉形象这一组只剩视频通话形象 / 通话背景 / 通话动作 / 聊天背景
        assertTrue(manual.contains("视觉形象（视频通话形象 / 通话背景 / 通话动作 / 聊天背景）"))
        assertTrue(manual.contains("「角色立绘 / Live 2D 只存不显」这句话已经作废"))
        assertTrue(manual.contains("整页是五组平铺"))
        assertFalse(manual.contains("仍是只存不显"))
        assertFalse(manual.contains("）、角色立绘、Live 2D、聊天背景、联网（开/关）、音色、模型、"))
        // ③ 提示词库：段数与框架段数都在涨（v0.68.0 八→九、v0.87.0 九→八、v0.88.0 八→十）。
        // ⚠️ 不钉具体数字，只钉"每日建议策略已进列表"与"旧数字不在"。
        assertTrue(manual.contains("段提示词都可编辑"))
        assertTrue(manual.contains("每日建议策略**（v0.68.0 补上"))
        assertFalse(manual.contains("**八段提示词都可编辑**"))
        assertFalse(manual.contains("**九段提示词都可编辑**"))
        // ④ 平板 / 折叠屏：双栏与版心是自动的，没有开关
        assertTrue(manual.contains("⚠️ **平板 / 折叠屏 / 大屏**"))
        assertTrue(manual.contains("**点左边一行是切换右边，不是 push 新页面**"))
        assertTrue(manual.contains("内容宽度**封顶 840dp**"))
    }

    /**
     * v0.69.0：P0 那几条改动进手册。
     *
     * ⚠️ 这批全是"**在既有句子上追加**"的改动，多数没有"旧措辞"可以 assertFalse，
     * 所以配对的另一半用**只出现一次**的计数断言 ——
     * `.replace()` 的锚点若被别处复用，同一句会被追加两遍；锚点落空则一句都不出现。
     * 两种失效都是静默的、都不报错，只能这样钉（§零.5 立这条规矩的原因就在这）。
     */
    @Test
    fun `v0_69_0 手册补上打断收口_时间_语气读数_自换表情_缓存读数`() {
        val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
        // ① 打断收口：这条有真旧措辞，可以按老规矩成对钉
        assertTrue(manual.contains("打断后 TA 知道自己是被打断的"))
        assertFalse("旧句子形状必须已被改写", manual.contains("插话打断。入口恒显示"))
        // ② 每轮带日期时间星期
        assertTrue(manual.contains("现在的日期、时间与星期"))
        assertEquals(1, Regex("现在的日期、时间与星期").findAll(manual).count())
        // ③ 通话里的语气读数（含"不进屏幕、不进记录"这两条边界）
        assertTrue(manual.contains("听出用户的语气"))
        assertTrue(manual.contains("不会显示在屏幕上"))
        assertEquals(1, Regex("听出用户的语气").findAll(manual).count())
        // ④ Live2D 自己换表情，并说清候选是从模型包里扫出来的；伪视频要撇清
        assertTrue("表情段锚点落空了", manual.contains("TA 会在通话里自己换表情"))
        assertTrue(manual.contains("从你这个模型包里扫出来的"))
        assertTrue(manual.contains("只有伪视频形象时没有这一条"))
        // ⑤ 缓存命中读数：没有读数不等于坏了
        assertTrue("缓存读数那句锚点落空了", manual.contains("服务商未返回用量"))
        assertEquals(1, Regex("服务商未返回用量").findAll(manual).count())
    }

    /**
     * v0.70.0：记忆按人设分开之后，手册必须解释「TA 怎么不记得我说过的话了」。
     * 这一条同时钉住新加的开关入口（基础档案页那行「全部 AI 可读」）与它的默认值。
     */
    @Test
    fun `v0_70_0 手册说明记忆分角色_并给出全部AI可读开关`() {
        val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
        assertTrue("分角色这件事没写进手册", manual.contains("记忆是分角色的"))
        assertTrue("开关没写进手册", manual.contains("全部 AI 可读"))
        // AI 会自己记、也会自己想起：用户问「你怎么知道我不吃香菜」时必须答得上来
        assertTrue("记忆行为没写进手册", manual.contains("主动想起"))
        assertTrue(manual.contains("值得长期记住"))
        // 这条是接在上一条的产出句子后面的，重复应用一次就会多出一整段
        assertEquals(1, Regex("读不到基础档案了").findAll(manual).count())
        // 默认值必须写明白：用户升级完如果发现每个角色都不认识自己，第一反应是"坏了"
        assertTrue(manual.contains("默认开着"))
        assertEquals(1, Regex("全部 AI 可读").findAll(manual).count())
        assertEquals(1, Regex("记忆是分角色的").findAll(manual).count())
    }

    /**
     * v0.71.0：「TA 的记忆」页要问得出来。
     * 钉的是**入口路径 + 四段的名字**（用户照着念给助手听的就是这几个词），
     * 以及那条"复用前面产出句子当锚点"的写法没有被应用两次。
     */
    @Test
    fun `v0_71_0 手册写明记忆页入口与四段`() {
        val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
        assertTrue("入口路径没进手册", manual.contains("会话设置 →「TA 的记忆」"))
        assertTrue(manual.contains("N 条待确认"))
        assertTrue(manual.contains("**待入库**"))
        assertTrue(manual.contains("**已忽略**"))
        // 「不生效但保留」这句是防用户以为"已忽略"该能一键清空的误解
        assertTrue(manual.contains("不再生效但保留"))
        assertEquals(1, Regex("换角色就是另一份记忆").findAll(manual).count())
    }

    /**
     * v0.72.0：主动关心看的是状态，不是配额也不是骰子。
     * 钉住两个最容易误解的点：「她没找我 ≠ 额度用完」与「时间窗仍是硬上限」。
     */
    @Test
    fun `v0_72_0 手册说明主动关心看状态不摇骰子`() {
        val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
        assertTrue("状态引擎没进手册", manual.contains("她为什么会主动找我"))
        assertTrue(manual.contains("不是摇骰子"))
        assertTrue("必须说清时间窗仍是硬上限", manual.contains("时间窗外一律不发"))
        assertTrue(manual.contains("不会把状态本身念出来"))
        assertEquals(1, Regex("她为什么会主动找我").findAll(manual).count())
    }

    /**
     * v0.73.0：动态上线、备忘本整块下线、任务页从底栏搬进数据页。
     *
     * 三条都是**旧措辞不许还在**的成对断言（§零.5）：只写"新话在"守不住静默落空 ——
     * 旧的备忘说明如果还留在手册里，助手会一边教人用已经删掉的功能、一边说动态。
     */
    @Test
    fun `v0_73_0 手册写明动态与备忘下线`() {
        val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
        // 新：动态那一章 + 入口形状
        assertTrue("动态那一章没进手册", manual.contains("【动态】（v0.73.0，底栏第三格）"))
        assertTrue(manual.contains("右下角那颗黑色圆钮"))
        assertTrue("回复只有一层这件事要写明", manual.contains("回复只有一层"))
        assertTrue("「她不发动态不是 bug」这条必须在内", manual.contains("时间线空着是正常状态"))
        // 新：任务页换了位置
        assertTrue(manual.contains("任务改从**数据页那张入口卡**进"))
        assertTrue(manual.contains("④ **任务**：与记账本那张**一样大小**的入口卡"))
        // 旧：备忘本的整套操作说明一律不许还留着
        assertFalse("备忘的入口描述还在手册里", manual.contains("点进去才是卡片列表"))
        assertFalse(manual.contains("一条备忘就是一句话"))
        assertFalse(manual.contains("记点什么…"))
        assertFalse("置顶/发给 AI 那套旧说明不许回来", manual.contains("把这条备忘连同"))
        // 并且**明说**这个模块没了、数据没迁移（用户会问"我以前的备忘呢"）
        assertTrue(manual.contains("整个模块已经下线"))
        assertEquals(1, Regex("【动态】").findAll(manual).count())
    }

    /**
     * v0.74.0：ROADMAP 三项候选落进手册（点击互动可配置 / 动作编辑器高级模式 / 背景支持视频）。
     *
     * 成对断言（§零.5）：新的"三档背景"必须写上，旧的"只有两档"一句都不许剩。
     */
    @Test
    fun `v0_74_0 手册写明背景视频与点击互动与高级模式`() {
        val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
        // 新：背景三档（多出视频）
        assertTrue("背景视频没写进手册", manual.contains("**视频背景**"))
        assertTrue(manual.contains("不超过 30 秒"))
        assertTrue("视频背景只垫在角色背后这件事要说清", manual.contains("只垫在角色背后"))
        // 旧：v0.65.0 那版"只有两档"必须被改写掉
        assertFalse("旧的「只有两档」措辞还在", manual.contains("v0.65.0 起**只有两档**"))
        // 新：点击互动可配置
        assertTrue("「点击互动」入口没写进手册", manual.contains("「点击互动」按钮"))
        assertTrue(manual.contains("固定是随机趣味动作、不能指定"))
        // 新：编辑器高级模式
        assertTrue("编辑器高级模式没写进手册", manual.contains("v0.74.0 起编辑器底部多了一行**「高级」**"))
        assertTrue(manual.contains("不能逐帧改关键帧"))
    }

    /**
     * v0.76.0：动态页重做（卡片式）+ 多图 + 位置，这三件事必须进助手手册——
     * 用户在动态页提的问（"图怎么看大图"、"它怎么知道我在哪"）就是靠这段答的。
     *
     * 成对断言：新写出来的一句一句都在；**没有任何旧措辞需要留**，所以这里
     * 额外把"这条 replace 真的生效了"钉死 —— 锚点若对不上是**静默落空**，
     * 手册会停在 v0.73.0 那版且不报错，只有真断言新句子才能抓住。
     */
    /**
     * v0.80.0：聊天气泡推倒重来（色盘 + 明暗各一套）与「我的页档案卡」必须进手册 ——
     * 用户最常问的正是"气泡颜色在哪改""我的头像在哪设"，答错路径就是白跑一趟。
     *
     * 成对断言：新措辞一一在；**旧措辞一句不许剩**（色卡六选一 / 字号三档 / 同一页设头像都过时了）。
     */
    @Test
    fun `v0_80_0 手册写明气泡色盘与我的档案卡`() {
        val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
        // 新：色盘 + 明暗各一套
        assertTrue("气泡段没更新到 v0.80.0", manual.contains("v0.80.0 起，设置主页的「外观」卡点进去能调聊天气泡"))
        assertTrue("要写明明暗各存一套", manual.contains("两种主题各存一套"))
        assertTrue("要写明是色盘自定义", manual.contains("点色盘自己调"))
        assertTrue("边框与圆角要提到", manual.contains("边框颜色") && manual.contains("圆角"))
        assertTrue("字体族/字号/字重要提到", manual.contains("字体族") && manual.contains("12~22sp") && manual.contains("字重"))
        assertTrue("实时预览要提到", manual.contains("预览都实时跟着变"))
        // 新：我的档案卡（头像功能换了地方）
        assertTrue("要写明头像挪到我的页档案卡", manual.contains("我的」页**最上面那张档案卡**"))
        assertTrue("档案页要写明能改昵称与简介", manual.contains("改**昵称**和写一句**简介**"))
        // 新：「基础档案」行卡取消、并入个人档案页（三处旧入口描述都要改写）
        assertTrue("要写明基础档案并入个人档案页", manual.contains("画像条目与头像 / 昵称 / 简介合并在同一页"))
        assertTrue("行卡列表里的档案描述要更新", manual.contains("还有注入聊天的画像条目"))
        assertTrue("记忆章节的入口路径要更新", manual.contains("「我的 → 个人档案」页里「基础档案」那一段"))
        // 旧：v0.78.0 那套色卡/字号档/同页设头像的说法一个字都不能留
        assertFalse("旧的「六选一色卡」还在", manual.contains("气泡底色各自六选一"))
        assertFalse("旧的「字号三档」还在", manual.contains("字号三档"))
        assertFalse("旧的「同一页还能设你自己的头像」还在（头像已挪走）", manual.contains("同一页还能设**你自己的头像**"))
        assertFalse("旧的「基础档案」独立入口还在", manual.contains("基础档案（注入聊天的画像，只在这里编辑）"))
        assertFalse("旧的「我的 → 基础档案」路径还在", manual.contains("我的 → 基础档案"))
    }

    @Test
    fun `v0_76_0 手册写明动态多图与位置与卡片版面`() {
        val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
        // 新：多图
        assertTrue("动态多图没写进手册", manual.contains("能带最多 9 张图"))
        assertTrue("九宫格排布要说清", manual.contains("图按九宫格排在正文下面"))
        assertTrue("全屏查看器没写进手册", manual.contains("全屏查看"))
        // 新：位置 + 那条最容易被用户追问的"是不是在定位"
        assertTrue("位置没写进手册", manual.contains("能带位置"))
        assertTrue(
            "必须明说不去申请定位权限（用户会问「它怎么知道我在哪」）",
            manual.contains("App 不会去申请定位权限、也不会自动定位"),
        )
        // 新：卡片式版面 + 下拉刷新 / 上拉加载
        assertTrue("卡片式版面没写进手册", manual.contains("一条动态一张白卡片"))
        assertTrue("下拉刷新没写进手册", manual.contains("滑到底自动多加载一页"))
        // 旧：v0.73.0 那条末句仍要在（我们是在它后面接的，不能把它整段吞掉）
        assertTrue(manual.contains("「她怎么不发动态了」不是 bug"))
        assertEquals("动态那一章只许出现一次", 1, Regex("【动态】").findAll(manual).count())
    }

    /**
     * v0.78.0（用户点名三条会改变用户可见行为的）：
     * ① AI 也能点赞；② 消息页会话列表按"最近聊过的排最上面"排序；
     * ③ 聊天外观可自定义（气泡底色/字号/是否显示头像）+ 用户头像。
     *
     * 成对断言：新的句子在、旧的句子不在（或只剩被改写后的新句），
     * 并钉死"replace 真生效"（锚点对不上会静默落空，只有断言新句才抓得住）。
     */
    @Test
    fun `v0_78_0 手册写明AI点赞_会话排序_聊天外观与用户头像`() {
        val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
        // ① AI 点赞
        assertTrue("AI 点赞没写进手册", manual.contains("角色也会给你点赞"))
        // ② 会话列表排序
        assertTrue("会话列表排序没写进手册", manual.contains("最近聊过的排最上面"))
        assertTrue("排序要说清不是按创建时间", manual.contains("不是按创建时间"))
        // ③ 聊天外观 + 用户头像
        // ⚠️ v0.80.0 把这一整段推倒重做了（色盘取代色卡、明暗各一套、头像挪去我的页档案卡），
        // 所以原来那三条断言（六选一 / 字号三档 / 同页设头像）**全部作废** ——
        // 新措辞的断言在 `v0_80_0 手册写明气泡色盘与我的档案卡` 里，这里只守"入口路径仍在"。
        assertTrue("聊天外观没写进手册", manual.contains("设置主页的「外观」卡点进去能调聊天气泡"))
        assertFalse("用了过时的「设置 → 外观」路径", manual.contains("设置 → 外观"))
        assertTrue("显示头像开关仍要写清", manual.contains("显示头像仍然是这一页的开关"))
        // 旧措辞：点赞那条已经被改写（旧句不该原样留下）
        assertFalse(
            "旧点赞行应已被改写（末尾不再只有『再点取消；』）",
            manual.contains("**点赞**：那一行的心形，点一下红、再点取消；\n"),
        )
        // 动态那一章仍只许出现一次（防止误插第二处）
        assertEquals("动态那一章只许出现一次", 1, Regex("【动态】").findAll(manual).count())
    }

    /**
     * v0.79.0：两条用户点名 —— ① 改错（AI 会在不聊天的时候也看用户动态）、
     * ② 人设写作规范重写成"可复制给外部 AI"的那份，手册里的人设适配协议跟着对齐。
     *
     * ① 是**改自己上一版的错**：v0.77.0 把用户那句"AI 回复动态只能在跟 AI 聊天时回复"
     * 当成了产品要求，手册与提示词都写成"只能在聊天里做"。用户澄清是反的，
     * 所以这条断言**必须双侧**：新说法在、旧说法一次不剩。
     */
    @Test
    fun `v0_79_0 手册写明主动看动态_写作规范可复制`() {
        val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
        // ① 她会自己来看你的动态
        assertTrue("主动看动态没写进手册", manual.contains("她会自己来看你的动态"))
        assertTrue("要说清发生在什么时机", manual.contains("主动来找你**的那几次"))
        assertTrue("要为『没聊天却回了你动态』给出解释", manual.contains("这是正常的"))
        // ② 写作规范：可一键复制 + 要素与规范同源
        assertTrue("写作规范入口没写进手册", manual.contains("编辑人设页点「查看写作规范」"))
        assertTrue("没写清那份规范能复制", manual.contains("一键复制"))
        assertTrue("要素没对齐新规范", manual.contains("必须写到五样"))
        assertTrue("没写清关系优先于头衔", manual.contains("写关系，不写职业头衔"))
        // 旧口径（三节）必须被换掉，否则助手会给用户自相矛盾的写法建议
        assertFalse(
            "旧的三节口径应已被替换",
            manual.contains("包含【语气与风格】【职责】【约束】三节"),
        )
        // 动态那一章仍只许出现一次（防止误插第二处）
        assertEquals("动态那一章只许出现一次", 1, Regex("【动态】").findAll(manual).count())
    }

    /**
     * v0.81.0：个人档案页头像与字段改版 —— 手册"换头像"那句必须跟上新机制：
     * emoji 滚轮（框下横穿、左右露半截、滑到框心定格）+ 点框选图 + 细线胶囊；
     * 昵称 / 简介改成直接改的输入框、改完即时生效。
     *
     * 成对断言（§零.5）：新机制文案在、旧"选一张图或挑一个 emoji，有图就用图"描述一次不剩。
     * ⚠️ 设置页「你的头像」那段仍保留「选一张图或挑一个 emoji 都行（有图就用图）」的旧写法，
     * 那是另一处、本次不动；assertFalse 因此用带逗号的精确子串，避开那句带「都行」的。
     */
    @Test
    fun `v0_81_0 手册写明个人档案头像滚轮与字段改版`() {
        val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
        // 新：emoji 滚轮
        assertTrue("个人档案头像滚轮没写进手册", manual.contains("头像框下面一排人物 emoji 从框底下横穿"))
        assertTrue("要写明滑到框心定格", manual.contains("滑到**框心**那格就是选中的"))
        assertTrue("要写明越靠中心越大越清晰", manual.contains("越靠中心越大越清晰"))
        // 新：点框选图
        assertTrue("要写明点框选图", manual.contains("点一下头像框") && manual.contains("去选一张自己的图"))
        // 新：昵称 / 简介即时生效的输入框（同时守住 v0.80.0 那条断言要的连续子串「改**昵称**和写一句**简介**」）
        assertTrue("昵称/简介输入框没写进手册", manual.contains("都是直接改的输入框、改完即时生效"))
        // 新：细线胶囊
        assertTrue("细线胶囊说明没写进手册", manual.contains("没设过头像时写「点击编辑」"))
        // 旧：旧的"选一张图或挑一个 emoji，有图就用图"头像描述必须消失（精确子串，避开设置页那句带「都行」的）
        assertFalse("旧的头像选择描述还留在手册里", manual.contains("选一张图或挑一个 emoji，有图就用图"))
    }

/**
 * v0.82.0：用量口径补齐 + 记忆待审聊天内提示 —— 手册同步。
 *
 * ① 模型卡片文案从「共消耗 X tokens」扩成带缓存命中率，并写明统计口径含
 *    主动关心 / AI 来电 / 通话（v0.82.0 之前这些链路的消耗根本没进账）。
 * ② AI 新暂存记忆时聊天里会出现居中的提示气泡（用户选定的系统气泡方案），
 *    点它跳「TA 的记忆」页。
 *
 * 成对断言（§零.5）：新措辞在、被它顶掉的旧措辞一次不剩。
 */
@Test
fun `v0_82_0 手册写明模型卡命中率与记忆待审气泡`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
    // ① 新：缓存命中率上模型卡片
    assertTrue("模型卡片命中率没写进手册", manual.contains("「· 缓存 NN%」"))
    assertTrue("要写明命中率的用途", manual.contains("比较哪个模型缓存更友好"))
    // ① 新：统计口径补齐
    assertTrue("统计口径没写进手册", manual.contains("凡是模型跑过的地方都计入"))
    // ① 旧：「没消耗过就不显示这一句。」必须紧跟在命中率那句后面（一条 replace 的产物），
    //     旧版整句（以分号结尾、无命中率）一次不剩
    assertFalse(
        "旧的模型卡片用量句还留在手册里",
        manual.contains("（「共消耗 X tokens」，服务商返回用量数据时才统计）；没消耗过就不显示这一句。"),
    )
    // ① v0.91.0：用量从卡片「右侧」改成「下方」（行尾会挤成竖排单字）——
    //     成对断言：新措辞在、「右侧」的旧措辞一次不剩
    assertTrue(
        "v0.91.0 用量改挂卡片下方没写进手册",
        manual.contains("**每个模型卡片的下方直接显示这个模型本月消耗了多少**"),
    )
    assertFalse(
        "旧的「每个模型卡片的右侧直接显示…」还留在手册里",
        manual.contains("**每个模型卡片的右侧直接显示这个模型本月消耗了多少**"),
    )
    // ② 新：记忆待审气泡
    assertTrue("记忆待审气泡没写进手册", manual.contains("💭 我记住了 N 件新事"))
    assertTrue("要写明点气泡跳记忆页", manual.contains("点它直接跳「TA 的记忆」页"))
    // ② 旧：v0.71.0 那条「TA 的记忆」入口句本身保留（它是新句的前缀），
    //     但"待确认"的旧引导句不得被误删
    assertTrue("「N 条待确认」入口句仍要在", manual.contains("N 条待确认"))
}

/**
 * v0.82.0 P1：滚动摘要 + 记忆自动归档 + 记忆语义召回 —— 手册同步。
 *
 * 三个功能都改了用户能摸到的东西（会话设置多一行、记忆会自己归档、模型配置多一张卡），
 * 按 §零.5 必须同版本进手册。成对断言：新措辞在；被插入点顶开的旧衔接（"不会刷屏。"
 * 紧接着"用户说「她记住了"）一次不剩。
 */
@Test
fun `v0_82_0 手册写明滚动摘要自动归档与语义召回`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
    // ① 滚动摘要：是什么 + 会话设置的查看/删除入口 + 总开关位置与默认开
    assertTrue("滚动摘要没写进手册", manual.contains("最旧的那部分会被压缩成一段**对话摘要**"))
    assertTrue("摘要入口没写进手册", manual.contains("会话设置里多了一行「对话摘要」"))
    assertTrue("摘要开关位置没写进手册", manual.contains("提示词库 →「对话摘要策略」段，默认开着"))
    // ② 自动归档：90 天歇着 + 想起即回来 + 永久豁免
    assertTrue("自动归档没写进手册", manual.contains("放太久（90 天）没被聊起过，会自己挪进「已忽略」歇着"))
    assertTrue("想起即回来没写进手册", manual.contains("TA 一想起来它就自己回来"))
    assertTrue("永久豁免没写进手册", manual.contains("设为「永久」的永远不会被这样收起来"))
    // ③ 语义召回：默认关 + 配置入口 + 隐私语义（记忆内容发给服务商）
    assertTrue("语义召回没写进手册", manual.contains("**默认关着**的增强「记忆语义召回」"))
    assertTrue("配置入口没写进手册", manual.contains("模型配置 → 记忆语义召回"))
    assertTrue("隐私语义没写进手册", manual.contains("记忆的内容会发给你配的那家服务商去算语义向量"))
    // 旧衔接：「不会刷屏。」后面原本紧跟 v0.71.0 的「用户说…」，被新三段插入后这个相邻关系必须不存在
    assertFalse(
        "旧的相邻关系还留在手册里（replace 静默落空了）",
        manual.contains("不会刷屏。\n              用户说「她记住了"),
    )
}

/**
 * v0.82.0 P2：Live2D 易用性收尾 —— 手册同步。
 *
 * 两条都改了用户能摸到的东西：导入选择器只认 zip 且带进度；角色空间能双指捏合缩放、
 * 双击复位。按 §零.5 同版本进手册。
 *
 * 断言写的是"新内容挂在锚点后面"，而不是"旧措辞不在" —— 后者在搜索串写错、
 * replace 静默落空时反而照样通过，正是这条纪律要防的情况。
 */
@Test
fun `v0_82_0 手册写明 Live2D 导入口径与角色空间缩放`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
    // ① P2-1 导入：只认 zip；选错当场提示而不是白等；进度分段报
    assertTrue("导入只认 zip 没写进手册", manual.contains("选文件那一步**只认 .zip 整包**"))
    assertTrue("选错格式的即时反馈没写进手册", manual.contains("不会让你干等"))
    assertTrue("解压进度没写进手册", manual.contains("再解压第几项"))
    // ② P2-2 缩放：双指捏合 + 范围 + 双击复位 + 不保存
    assertTrue("捏合缩放没写进手册", manual.contains("**双指捏合**把角色放大缩小"))
    assertTrue("缩放范围没写进手册", manual.contains("0.5×～2.0×"))
    assertTrue("双击复位没写进手册", manual.contains("**双击画面**就回到原来的大小"))
    assertTrue("缩放不持久化没写进手册", manual.contains("下次进来还是 1.0×"))
    // 衔接：两段新内容必须**各自挂在自己那条锚点后面**（而不是掉在别处）。
    //      搜索串一旦写错，replace 静默落空、新文案根本不会出现，断言立刻红。
    assertTrue(
        "导入条目没挂在记忆段后面（replace 静默落空了）",
        manual.contains("不开就是现在的纯词法召回。\n              导入 Live2D 模型包时"),
    )
    assertTrue(
        "缩放条目没紧跟在导入条目后面",
        manual.contains("包很大也知道走到哪儿了。\n              在**角色空间**里可以"),
    )
    // 段落顺序：导入在前、缩放在后
    assertTrue(
        "手册里两条新内容的顺序不对",
        manual.indexOf("导入 Live2D 模型包时") < manual.indexOf("在**角色空间**里可以"),
    )
}

// 手册里 P2 那两条只有一处（别被后来的 replace 复制出第二份）
@Test
fun `v0_82_0 手册 P2 段落不重复`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
    assertEquals(
        "导入那条在手册里出现了不止一次",
        manual.indexOf("导入 Live2D 模型包时"),
        manual.lastIndexOf("导入 Live2D 模型包时"),
    )
    assertEquals(
        "缩放那条在手册里出现了不止一次",
        manual.indexOf("在**角色空间**里可以"),
        manual.lastIndexOf("在**角色空间**里可以"),
    )
}

/**
 * P3-1+P3-3：MCP 服务器（tools 回环 + 白名单外部工具）—— 手册同步。
 *
 * 新功能入口（设置 → 模型配置 → MCP 服务器）与行为变化（AI 可调用外部工具、
 * 非只读工具执行前弹确认），按 §零.5 进手册。
 * 断言写"新文案挂在锚点后面"整段子串，而不是"旧措辞不在"（假绿陷阱，见上文纪律）。
 */
@Test
fun `P3 手册写明 MCP 服务器入口与确认口径`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
    assertTrue("MCP 入口没写进手册", manual.contains("设置 → 模型配置 → MCP 服务器"))
    assertTrue("仅支持远程 HTTP 没写进手册", manual.contains("只支持远程 HTTP 的服务器"))
    assertTrue("只读免确认口径没写进手册", manual.contains("标着「只读」的直接执行"))
    assertTrue("执行前确认没写进手册", manual.contains("每次执行前都会先弹出来问你，你点头才算数"))
    assertTrue("不可信内容口径没写进手册", manual.contains("不会当成你的指令"))
    assertTrue("端点不支持自动隐藏没写进手册", manual.contains("不支持工具调用时，这个能力会自动藏起来"))
    assertTrue(
        "MCP 条目没挂在角色空间缩放那条后面（replace 静默落空了）",
        manual.contains("下次进来还是 1.0×。\n              还能给 TA 接**外部工具**"),
    )
    assertEquals(
        "MCP 条目在手册里出现了不止一次",
        manual.indexOf("还能给 TA 接**外部工具**"),
        manual.lastIndexOf("还能给 TA 接**外部工具**"),
    )
}

/**
 * v0.83.0：记账分类 —— 手册同步。
 *
 * 行为变化：① 记一笔表单里多了分类滚轮（用户点名要的分类回来了，v0.42.0 定的是"不要分类"）；
 * ② 流水长按「编辑这笔」现在也能改分类。按 §零.5 进手册。
 *
 * 断言写"新文案挂在锚点后面"整段子串（含 `\n` + 15 空格缩进），再补"分类进编辑项"——
 * 只写后者是假绿陷阱（见上文纪律）。
 */
@Test
fun `v0830 手册写明记账分类`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
    assertTrue("分类没写进记一笔那段", manual.contains("记一笔时还能给这笔挑个**分类**"))
    assertTrue("分类预设没举例", manual.contains("餐饮、购物、娱乐、交通这些"))
    // v0.91.0：滚轮换成一行点选 chips —— 成对断言：新措辞在、"滑到凹槽"的旧措辞一次不剩
    assertTrue("点选式分类没写进手册", manual.contains("点哪个哪个就选中"))
    assertFalse("旧的分类滚轮描述还留在手册里", manual.contains("**可以一直往两边滑的**"))
    assertFalse("旧的凹槽焦点描述还留在手册里", manual.contains("滑到正中那个凹槽里就是选中的"))
    assertFalse("旧的滑回措辞还留在手册里", manual.contains("滑回最前面那个「未分类」"))
    assertTrue("未分类出口没写进手册", manual.contains("点最前面那个「未分类」"))
    assertTrue("分类不影响算法没写进手册", manual.contains("分类只显示在流水行上，不影响收支与余额的算法"))
    // v0.91.0：流水按天分组 —— 新措辞在
    assertTrue("流水按天分组没写进手册", manual.contains("流水**按天分段显示**"))
    assertTrue("日期小标题的形态没写进手册", manual.contains("今天 · 10月10日"))
    assertTrue("行内只留时分没写进手册", manual.contains("行内只留时分"))
    assertTrue(
        "分类条目没挂在「删除这笔」后面（replace 静默落空了）",
        manual.contains("「删除这笔」点一下就删。\n              记一笔时还能给这笔挑个**分类**"),
    )
    assertEquals(
        "分类条目在手册里出现了不止一次",
        manual.indexOf("记一笔时还能给这笔挑个**分类**"),
        manual.lastIndexOf("记一笔时还能给这笔挑个**分类**"),
    )
    // 编辑那一项的措辞（v0.63.0 那句）必须扩到含分类
    assertTrue(manual.contains("「编辑这笔」改收支方向/金额/备注/分类（发生时间和录入来源不动）"))
}

/**
 * P4 总自检：补丁链全量命中（与 debug 包启动自检读同一份记录
 * `AiPrompts.assistantManualPatchMisses`，MenghuanApp.onCreate 失败时写崩溃日志页）。
 * 红了 = 某条 `.manualPatch` 的旧措辞没对上——多半是锚点被上游改动冲掉，
 * 或者新补丁错用了裸 `.replace()`（那样根本不会被记录，只能靠逐条断言兜底）。
 */
@Test
fun `P4 手册补丁链全部命中`() {
    val misses = AiPrompts.assistantManualPatchMisses()
    assertTrue(
        "手册补丁未命中 ${misses.size} 条：\n" + misses.joinToString("\n"),
        misses.isEmpty(),
    )
}

/**
 * v0.84.0：AI 引用任务的口径（用户点名「聊天时老是时不时提到无关紧要的任务，
 * 包括已经完成的和已经过时的和很久之后的」）必须写进助手手册。
 *
 * 断两条：① 新措辞挂在锚点后面（整段子串，含 `\n` + 12 空格缩进——只断"新措辞在"
 * 是假绿，replace 落在别处也能过）；② 旧措辞（「未来某天要做的事」）不在——
 * 它在补丁后仍作为锚点保留，所以这里只断新增句本身不重复。
 */
@Test
fun `v0_84_0 手册补上 AI 引用任务的口径`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
    assertTrue("引用口径没写进手册", manual.contains("**AI 在聊天里对任务的引用是收着的（v0.84.0）**"))
    assertTrue("只许提今天/明天没写进手册", manual.contains("只允许顺口提「今天该做」和「明天到期」的"))
    assertTrue("已完成不主动提没写进手册", manual.contains("已完成的任务不主动当话题"))
    assertTrue("逾期不主动催没写进手册", manual.contains("逾期没做的不主动催（你问起才说）"))
    assertTrue("很久以后不提没写进手册", manual.contains("3 天以后的提都不提"))
    assertTrue("用户反馈缘由没写进手册", manual.contains("完成的、过时的、很久以后的都有"))
    assertTrue("建议变严没写进手册", manual.contains("「多喝水」「加油」这种空泛愿望不会再进任务库"))
    assertTrue(
        "引用口径没挂在「3. 任务」段末（replace 静默落空了）",
        manual.contains("\n            **AI 在聊天里对任务的引用是收着的（v0.84.0）**"),
    )
    assertEquals(
        "引用口径在手册里出现了不止一次",
        manual.indexOf("**AI 在聊天里对任务的引用是收着的（v0.84.0）**"),
        manual.lastIndexOf("**AI 在聊天里对任务的引用是收着的（v0.84.0）**"),
    )
}

/**
 * v0.85.0：AI 分得清动态是谁发的（用户点名「AI 发动态分得清是谁发的吗」）必须写进助手手册。
 *
 * 断两条：① 新措辞挂在锚点后（整段子串，含 `\n` + `- ` 前缀）；② 不重复。
 */
@Test
fun `v0_85_0 手册补上动态作者身份`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
    assertTrue("动态作者身份没写进手册", manual.contains("**她分得清动态是谁发的**（v0.85.0）"))
    assertTrue("用户标记没写进手册", manual.contains("标「用户」的是你发的"))
    assertTrue("别的角色标记没写进手册", manual.contains("标「另一位角色」的是你别的角色（别的 AI）发的"))
    // 动态收口（动态只存于主动关心链）后：名单里根本没有它自己的帖，
    // 所以"标「你自己」""不会给自己回、也不会给自己点赞"换成了主动链口径
    assertTrue(
        "没写明她自己的帖不列进名单",
        manual.contains("**她自己的帖子根本不会列进名单**（既不会自问自答，也不会给自己点赞）"),
    )
    assertFalse("旧的「标你自己」还留在手册里", manual.contains("标「你自己」的是她之前发的"))
    assertFalse("旧的「不会给自己回、也不会给自己点赞」还留在手册里", manual.contains("她不会给自己回、也不会给自己点赞"))
    assertTrue(
        "动态作者身份没挂在 v0.79.0 那条产出句后（replace 静默落空了）",
        // v0.94 在这两条之间插了一条动态子弹，收口后它改叫「一切动态动作都只在主动时」，钉它的位置
        manual.contains("- **她的一切动态动作都只发生在主动来找你的时候**（v0.94）"),
    )
    assertTrue(
        "v0.85 动态作者身份被挤离了 v0.79 那条产出句",
        manual.indexOf("**她分得清动态是谁发的**（v0.85.0）") >
            manual.indexOf("不会对同一条反复评论。"),
    )
    assertEquals(
        "动态作者身份在手册里出现了不止一次",
        manual.indexOf("**她分得清动态是谁发的**（v0.85.0）"),
        manual.lastIndexOf("**她分得清动态是谁发的**（v0.85.0）"),
    )
    // v0.79.0 那段（「她会自己来看你的动态」）必须还在 —— 我在编辑时曾误覆盖，这条是回归钉
    assertTrue("v0.79.0 那段被覆盖了", manual.contains("**她会自己来看你的动态**（v0.79.0）"))
    // v0.95 把这句改全了（现在交给她的含其他角色的帖），钉新措辞；旧措辞的消失由 v0_95 那条测试钉
    assertTrue("v0.79.0 那段被覆盖了", manual.contains("App 会把你**和其他角色**最近发的动态一并交给她"))
}

/**
 * v0.85.0：记忆不会重复（用户点名「AI 的记忆老是重复的」）必须写进助手手册。
 *
 * ⚠️ 这条同时是**防覆盖回归钉**：写 v0.85.0 记忆补丁时锚点落在 v0.70.0 那条长串里，
 * 若 old 串选错就会把 v0.70.0 整条冲掉。所以这里同时校验 v0.70.0 的原文与新增句都在。
 */
@Test
fun `v0_85_0 手册补上记忆不重复`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT
    assertTrue("记忆不重复没写进手册", manual.contains("**记忆不会重复堆**（v0.85.0）"))
    assertTrue("没说清同一件事只更新原来那条", manual.contains("同一件事只会更新原来那一条"))
    assertTrue("没解释旧条目怎么办", manual.contains("那是这版之前留下的"))
    assertTrue(
        "记忆补丁没挂在 v0.70.0 那句之后（replace 静默落空了）",
        manual.contains("不是偷看了别的东西。**记忆不会重复堆**（v0.85.0）"),
    )
    // v0.70.0 那段必须还在（写这条补丁时曾差点把它冲掉）
    assertTrue("v0.70.0 的「不吃香菜」示例被覆盖了", manual.contains("你怎么知道我不吃香菜"))
    assertTrue("v0.70.0 那段被覆盖了", manual.contains("记住的内容**只属于当前这个角色**"))
    assertEquals(
        "记忆不重复在手册里出现了不止一次",
        manual.indexOf("**记忆不会重复堆**（v0.85.0）"),
        manual.lastIndexOf("**记忆不会重复堆**（v0.85.0）"),
    )
}

/**
 * v0.86.0：手册写明「上下文窗口」与「提炼间隔」两个按角色的新设置。
 *
 * 这两件事都是从**用户实际的成本质疑**里长出来的（「塞这么多上下文模型不会花费很多 token 吗」
 * 「记忆这么频繁触发不会花费很多 token 吗」），所以手册里必须写清：**入口在会话设置**、
 * **两个数各管什么**、以及**旧版那个 20 万是写死的、跟记忆容量无关**。
 *
 * ⚠️ 这条同时是**防覆盖回归钉**：v0.86.0 的补丁锚点落在 v0.85.0 那条的成品尾巴
 * （"新记的不会再重复。"）上，old 串选错就会把 v0.85.0 整条冲掉。
 */
@Test
fun `v0_86_0 手册写明上下文窗口与提炼间隔可配`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    assertTrue("上下文窗口没写进手册", manual.contains("**上下文窗口与提炼节奏现在都由你定**（v0.86.0"))
    assertTrue("没说清入口在会话设置", manual.contains("都在**会话设置**里，每个角色各配各的"))
    assertTrue("没写「记住最近多少字」", manual.contains("**记住最近多少字**"))
    assertTrue("没写「每隔几轮提炼一次」", manual.contains("**每隔几轮提炼一次**"))
    assertTrue("没说明越大越贵", manual.contains("**每一轮都要重发**（花更多钱、也更慢）"))
    // v0.99.0：这条原本断言「手册写明手动提炼不受门槛限制」—— 但**那颗按钮 v0.42.1 就被收敛掉了**，
    // 界面上一直不存在，手册那句属于对用户的假承诺。这版已更正，所以断言翻面。
    assertFalse("「不受这个轮数限制，点了就立刻跑」的假承诺又回来了", manual.contains("不受这个轮数限制，点了就立刻跑"))
    assertTrue(
        "没写明记忆是自动提炼、没有手动按钮",
        manual.contains("**记忆是自动提炼的，没有「马上提炼一下」的按钮**"),
    )
    assertTrue(
        "没解释旧的 20 万是写死的",
        manual.contains("以前这个窗口是**写死的 20 万字**"),
    )
    // v0.86.0 后续：摘要整页化（用户点名「对话摘要我也要全屏展示」）。
    // 入口从"弹层"变成"整页"，手册这句必须跟着改 —— 不改就是骗用户。
    assertTrue(
        "没写摘要已整页显示",
        manual.contains("那一行点进去是**一整个页面**（v0.86.0 起整页显示"),
    )
    assertTrue(
        "没说明页面上半截是窗口、下半截是正文",
        manual.contains("上半截是「**记住最近多少字**」") && manual.contains("下半截就是这个角色当前的摘要全文"),
    )
    assertTrue(
        "还在说是「半屏弹层」（措辞过期）",
        manual.contains("以前是个半屏弹层"),
    )
    assertTrue(
        "v0.86.0 补丁没挂在 v0.85.0 那句之后（replace 静默落空了）",
        manual.contains("新记的不会再重复。\n") && manual.contains("**上下文窗口与提炼节奏现在都由你定**"),
    )
    // v0.85.0 那两条必须还在（写这条补丁时最容易冲掉的就是它们）
    assertTrue("v0.85.0 记忆不重复被覆盖了", manual.contains("**记忆不会重复堆**（v0.85.0）"))
    assertTrue("v0.85.0 动态作者身份被覆盖了", manual.contains("**她分得清动态是谁发的**（v0.85.0）"))
    assertEquals(
        "上下文窗口说明在手册里出现了不止一次",
        manual.indexOf("**上下文窗口与提炼节奏现在都由你定**"),
        manual.lastIndexOf("**上下文窗口与提炼节奏现在都由你定**"),
    )
}

/**
 * v0.87.0：**AI 周报整个功能被删掉**（用户点名「如果表没有用就删掉」）。
 *
 * 删一个功能最难的不是删代码，是**手册里所有还在提它的句子**——漏一处，用户一问答不上来
 * 或者照着已删功能答。这条把两个入口（数据页第 ⑤ 块、提示词库段数）+ 三处顺带提法
 * （应用概览、任务长按菜单类比、清除数据范围、单条清理）全钉住。
 *
 * ⚠️ 锚点纪律：这几条的 target **必须从链上最终文案抄**，不能从 v0.38 底稿抄——
 * 底稿里「AI 洞察策略、AI 周报策略、生图触发策略…」那一处早被 v0.68.0 改写过，
 * 照底稿抄会 replace 落空（P4 总自检当场变红）。
 */
@Test
fun `v0_87_0 手册删掉 AI 周报`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    // ① 数据页第 ⑤ 块：只剩洞察一张卡，且要明说周报已删（免得助手按老结构答）
    // ⚠️ v0.88.0 在「⑤ **AI 洞察**：」和「一张入口卡」之间插了洞察三频率的说明，
    // 所以这里**不再断言那句连续的旧串**，改成只钉"周报没了"这个事实（数字/位置都会被下一版冲）。
    assertTrue("没写明周报已整个删掉", manual.contains("**「AI 周报」这个功能在 v0.87.0 整个删掉了**"))
    assertTrue("没列清删了哪些东西", manual.contains("周报全屏列表、每周日 21:30 的定时生成"))
    assertTrue("没提醒助手别再按老结构答", manual.contains("用户问起周报就直说已经去掉，**别再按老结构答**"))
    // 旧的合并入口卡措辞必须一次不剩（注意：新的说明段里仍会引用「AI 洞察 / AI 周报」这个旧名字，
    // 所以只能钉"旧的入口卡定义句"，不能笼统地禁这个词）
    assertFalse(
        "旧的合并入口卡定义句还在",
        manual.contains("⑤ **AI 洞察 / AI 周报**：v0.42.0 起**合并成一张入口卡**"),
    )
    assertFalse("旧的长按周报期次措辞还在", manual.contains("在周报历史里**长按某一期**"))

    // ② 提示词库段数：删掉「AI 周报撰写策略」那一段。
    // ⚠️ v0.88.0 起段数又变了（八 → 十，多了「AI 洞察策略」、原名改「记忆提炼策略」），
    // 所以这里**不再钉「八段」这个数字**，只钉"周报那段没了"这个事实 —— 钉数字必被下一版冲红。
    assertTrue("没说清删的是哪一段", manual.contains("删掉了原来的「AI 周报撰写策略」一段"))
    assertFalse(
        "提示词库那段里还残留「AI 周报策略」（replace 落空了）",
        manual.contains("AI 洞察策略、AI 周报策略、**每日建议策略**"),
    )

    // ③ 三处顺带提法
    assertTrue("应用概览里还留着「每周生成 AI 周报」", !manual.contains("并每周生成 AI 周报"))
    assertTrue("任务长按菜单还拿周报当类比", !manual.contains("与会话列表、洞察、周报那些长按菜单"))
    assertTrue("清除数据范围里还列着 AI 周报", !manual.contains("AI 洞察、AI 周报、用户档案"))
    assertTrue("单条清理里还写着周报历史列表", !manual.contains("在周报历史列表里长按某一期"))
    assertTrue("数据清单里还带着周报", !manual.contains("含人设/消息/任务/档案/洞察/周报"))
    assertTrue("提醒体系里还带着周报生成", !manual.contains("任务提醒闹钟、每日总结、周报生成"))

    // v0.87.0 补丁必须挂在 v0.86.0 那句之后（锚点选错会把上一条整段冲掉）
    assertTrue(
        "v0.87.0 补丁没挂在 v0.86.0 成品尾巴上（replace 静默落空了）",
        manual.contains("觉得不够就去那一行填大一点。\n") && manual.contains("删掉了原来的「AI 周报撰写策略」一段"),
    )
    // v0.86.0 那段必须还在（写这条补丁时最容易冲掉的就是它）
    assertTrue("v0.86.0 上下文窗口说明被覆盖了", manual.contains("**上下文窗口与提炼节奏现在都由你定**（v0.86.0"))
    assertTrue("v0.86.0 摘要整页化被覆盖了", manual.contains("那一行点进去是**一整个页面**（v0.86.0 起整页显示"))
}

/**
 * v0.88.0：**四条链路彻底拆开** —— 记忆 / 洞察 / 每日建议 / 任务建议。
 *
 * 这一版改的是"数据从哪来"：提炼只出记忆；洞察改按日/周/月从记忆+任务+记账归纳；
 * 每日建议只吃昨天的洞察；任务建议改成聊天里 AI 主动标记触发。
 * 手册里凡是描述这四条链路数据源的句子都得翻掉，漏一处助手就会照旧结构答。
 */
@Test
fun `v0_88_0 手册写明洞察建议任务建议三条链路拆分`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    // ① 记忆章：提炼只管记忆
    assertTrue(
        "记忆章的「提炼成记忆、洞察和任务建议」没被收窄成只出记忆",
        manual.contains("提炼成记忆。填 1 = 每轮都提炼（最及时也最费）"),
    )
    assertTrue("没写明提炼只管记忆", manual.contains("**提炼只管记忆**（v0.88.0 起）"))

    // ② 数据页 ②：每日建议只依据昨天的洞察
    assertTrue(
        "每日建议没改成「只依据昨天的洞察」",
        manual.contains("**它只依据「昨天那批洞察」来写**（v0.88.0 起统筹昨天的日/周/月洞察"),
    )
    assertTrue("没写明昨晚没洞察就不出建议", manual.contains("**昨晚没有洞察就当天不出建议**"))
    assertTrue(
        "旧的说法（AI 每天早上 07:30 自动出一条）没被排除",
        !manual.contains("今日建议是这条独立链路定时产出的一条。\n"),
    )

    // ③ 数据页 ⑤：洞察三频率 + 数据源 + 允许 0 条
    assertTrue("洞察没改成每晚 23:00 归纳", manual.contains("**每晚 23:00 自动归纳一次**（v0.88.0 起"))
    assertTrue("没说洞察数据源是记忆+任务+记账", manual.contains("数据源是**你的记忆 + 任务 + 记账**三样"))
    assertTrue("没写日/周/月三频率", manual.contains("**每周日出「本周」洞察**"))
    assertTrue("没写月洞察", manual.contains("**每月月末出「本月」洞察**"))
    assertTrue("没写允许 0 条", manual.contains("**允许 0 条**：那天没什么值得说的就**一条都不写**"))
    assertTrue("没写空态三态文案", manual.contains("还没有洞察"))
    // 旧的「AI 洞察 / AI 周报 合并卡」定义句必须彻底不在（v0.87.0 只搬走了一处）
    assertTrue("旧的合并卡措辞还在", !manual.contains("合并成一张入口卡"))

    // ④ 任务章：任务建议标记驱动、只在聊天页出卡
    assertTrue(
        "任务建议没写成 AI 主动标记触发",
        manual.contains("**任务建议现在由 AI 自己按需触发**（v0.88.0 起，像语音条那样）"),
    )
    assertTrue("没写明标记格式", manual.contains("`[建议任务:标题|备注|日期]`"))
    assertTrue("没写明只在聊天页出卡", manual.contains("**这个卡片只在聊天页出**"))

    // ⑤ 提示词库：新增 AI 洞察策略 + 记忆提炼策略（v0.98.0 又加「记忆记录策略」「主动关心策略」）
    // ⚠️ v0.98.0 起段数又变了（十 → 十二），所以这里**不再钉段数这个数字**（钉了下一版必红），
    // 只钉"段数说明在"与"新段内容在"；旧数字不在由下面 `v0980` 那条断言负责。
    assertTrue("提示词库段数说明丢了", manual.contains("段提示词都可编辑"))
    assertTrue("没写明新增记忆记录策略", manual.contains("**记忆记录策略**"))
    assertTrue("没写明新增主动关心策略", manual.contains("**主动关心策略**"))
    assertTrue("没写明原「AI 洞察提炼策略」改名", manual.contains("原来的「AI 洞察提炼策略」改名叫「记忆提炼策略」"))
    assertTrue("没写明新增 AI 洞察策略", manual.contains("**另加一段「AI 洞察策略」**"))

    // ⑥ 提醒体系：补上每晚 23:00 洞察归纳
    assertTrue("提醒体系里没补上洞察归纳", manual.contains("**每晚 23:00 的洞察归纳**"))

    // 每条新措辞在手册里必须只出现一次（防锚点命中两处后其中一处静默落空）
    assertEquals(
        "「提炼只管记忆」出现了不止一次",
        manual.indexOf("**提炼只管记忆**"),
        manual.lastIndexOf("**提炼只管记忆**"),
    )
    assertEquals(
        "「每晚 23:00 自动归纳一次」出现了不止一次",
        manual.indexOf("**每晚 23:00 自动归纳一次**"),
        manual.lastIndexOf("**每晚 23:00 自动归纳一次**"),
    )
}

/**
 * v0.98.0：提示词库 **十段 → 十二段**，新增两段可编提示词。
 *
 * ① 「**主动关心策略**」：那段文字原本是 `ProactiveChat` 里一句**零引用的死代码**
 *    （`SYSTEM_INSTRUCTION`）—— 页面上没有入口、改了也不生效；现在拆成
 *    `HINT_FRAMEWORK`（硬约束，不可编）+ `DEFAULT_STRATEGY`（取材/语气/长度，可编），
 *    由 `settingsRepository.proactiveStrategy`（键 `proactive_strategy`）覆盖。
 * ② 「**记忆记录策略**」：管**聊天时 AI 随手记**（`MemoryMarker`），与「记忆提炼策略」
 *    （攒够轮数后批量提炼）是**两条不同的入口** —— 手册必须把两者摆在一起，用户才分得清。
 *
 * ⚠️ 锚点全部从**真编译 + jshell dump** 出的成品手册（logs/v0980_prompt.txt）里逐字截，
 * 五个锚点各命中 1 次；不是照源文件或旧措辞猜的 —— 对不上是**静默落空**。
 * ⚠️ 这里**不钉"十二段"这个数字**（段数还会涨，钉了下一版必红），只钉"新内容在 + 旧数字不在"。
 */
@Test
fun `v0980 手册写明新增两段可编提示词`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    // 新在：两段新提示词都写进了手册
    assertTrue("没写明新增「记忆记录策略」", manual.contains("**记忆记录策略**"))
    assertTrue("没写明「记忆记录策略」管聊天时随手记", manual.contains("管**聊天时 AI 随手记**"))
    assertTrue("没写明它是聊天里那条灰色提示的来源", manual.contains("💭 我记住了 N 件新事"))
    assertTrue("没写明新增「主动关心策略」", manual.contains("**主动关心策略**"))
    assertTrue(
        "没写明「主动关心策略」管她主动来找你时那一条消息",
        manual.contains("**TA 主动来找你时那一条消息**"),
    )
    // 顶部那段"现在是几段"的说明也要跟着更新
    assertTrue("顶部段数说明没更新", manual.contains("**v0.98.0 又加两段**"))

    // 旧不在：段数说明已换成新的，旧数字「十段」不能还留在手册里
    // （整串断言，避免和「十二段」误撞）
    assertFalse(
        "提示词库段数还停在旧数字「十段」（replace 落空了）",
        manual.contains("**十段提示词都可编辑**"),
    )
    assertFalse(
        "提示词库顶部段数说明还停在旧数字（replace 落空了）",
        manual.contains("**提示词库现在是十段**"),
    )
}


/**
 * v0.89.0：**定时消息跟着角色走**。
 *
 * 用户实测反馈：「我把那个人设删了，但定时消息还是每天从应用助手发过来」。
 * 根因是 `ScheduledMessageSender.pickPersona` 的「指定人设 → 主人设 → 第一个人设」
 * 兜底（v0.53.5 早期遗留）在找不到目标人设时把消息**静默改嫁**给了内置助手。
 * 现在改成：目标人设没了就不发、删角色时把它的定时项一并摘掉。
 * 手册里那句「谁发」要能回答用户"删了角色它还会发吗"。
 */
@Test
fun `v0_89_0 手册写明定时消息跟着角色走`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    assertTrue(
        "没写明定时消息由选中的角色来说",
        manual.contains("**这条消息是发给谁，就由谁来说**"),
    )
    assertTrue(
        "没写明删角色会一并删掉它的定时消息",
        manual.contains("**删掉那个角色，它的定时消息会跟着一起删**"),
    )
    assertTrue(
        "没写明留着会变成删不掉的孤儿",
        manual.contains("留着它只会变成一条在设置里删不掉的孤儿"),
    )
    // 旧措辞（带 v0.53.5 版本号的入口写法）必须已经被收掉
    assertTrue(
        "入口句里还挂着 v0.53.5 的版本号（锚点没命中）",
        !manual.contains("- 定时主动消息（v0.53.5，入口"),
    )

    // 两条新措辞各只能出现一次（防锚点命中两处后其中一处静默落空）
    assertEquals(
        "「这条消息是发给谁」出现了不止一次",
        manual.indexOf("**这条消息是发给谁，就由谁来说**"),
        manual.lastIndexOf("**这条消息是发给谁，就由谁来说**"),
    )
    assertEquals(
        "「它的定时消息会跟着一起删」出现了不止一次",
        manual.indexOf("**删掉那个角色，它的定时消息会跟着一起删**"),
        manual.lastIndexOf("**删掉那个角色，它的定时消息会跟着一起删**"),
    )
}

/**
 * v0.92.0：**角色空间入口从会话设置页搬到数据页**（用户点名「把角色空间卡片入口移到数据页」）。
 *
 * 为什么手册必须跟着改：角色空间是**按角色**的功能，而数据页是全局页 ——
 * 中间多了一步选人。手册要是还写「会话设置 → 角色空间」，用户按它找过去
 * 会发现那一行**已经不在了**，得到的是一条指错路的死答案。
 *
 * ⚠️ 手册里有**四处**提到这个入口（入口句 / 会话设置页的串行名 / 五组平铺的括注 /
 * 「会话设置页这几行长什么样」那段描述），只改第一处会留下三处互相矛盾的说法。
 * 所以这四条各断一对「新措辞在 + 旧措辞不在」（只写后者是假绿）。
 */
@Test
fun `v0_92_0 手册写明角色空间入口搬到数据页`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    // ① 入口句：新写法（数据页 → 选角色）
    assertTrue(
        "没写明入口搬到了数据页",
        manual.contains("入口：**「我的数据」页 → 「角色空间」卡 → 选一个角色**"),
    )
    assertTrue(
        "没写明会话设置页已经没有这一行",
        manual.contains("会话设置页已经没有这一行了"),
    )
    // 旧写法（会话设置 → 角色空间）必须已经收掉
    assertTrue(
        "入口句还指着会话设置（锚点没命中）",
        !manual.contains("入口：单聊页右上角 ⋯ → **会话设置 → 「角色空间」**。"),
    )

    // ② 五组平铺的括注里不再挂「角色空间」
    assertTrue(
        "五组平铺没改成四项",
        manual.contains("（视频通话形象 / 通话背景 / 通话动作 / 聊天背景）→ 声音（音色 / 铃声）"),
    )
    assertTrue(
        "五组平铺里还挂着角色空间",
        !manual.contains("（视频通话形象 / 通话背景 / 角色空间 / 通话动作 / 聊天背景）"),
    )

    // ③ 会话设置页的串行名里不再挂「角色空间」
    assertTrue(
        "串行名没改成四项",
        manual.contains("**视频通话形象**、**通话背景**、**通话动作**、聊天背景、"),
    )
    assertTrue(
        "串行名里还挂着角色空间",
        !manual.contains("**视频通话形象**、**通话背景**、**角色空间**、**通话动作**、聊天背景、"),
    )

    // ④ 「这几行长什么样」那段描述：删掉角色空间 + 补一句"别再来这里找"
    assertTrue(
        "没写明角色空间那一行已从这一页删掉",
        manual.contains("**「角色空间」那一行 v0.92.0 已从这一页删掉，搬去了「我的数据」页**"),
    )
    assertTrue(
        "这几行的描述里还挂着角色空间",
        !manual.contains("「角色空间」「通话动作」「聊天背景」这几行都是**一行高的纯入口**"),
    )
    // 补的那一句不能插在句子中间把句子截成两段（v0.66.0 原句要完整保留）
    assertTrue(
        "v0.66.0 原句被截断了",
        manual.contains("行上不带小标题说明（「通话背景」「音色」「铃声」的**右边会直接写当前选的是哪个**）；"),
    )

    // 每条新措辞在手册里必须只出现一次（防锚点命中两处后其中一处静默落空）
    assertEquals(
        "「我的数据」页入口出现了不止一次",
        manual.indexOf("入口：**「我的数据」页 → 「角色空间」卡 → 选一个角色**"),
        manual.lastIndexOf("入口：**「我的数据」页 → 「角色空间」卡 → 选一个角色**"),
    )
    assertEquals(
        "「已从这一页删掉」出现了不止一次",
        manual.indexOf("**「角色空间」那一行 v0.92.0 已从这一页删掉，搬去了「我的数据」页**"),
        manual.lastIndexOf("**「角色空间」那一行 v0.92.0 已从这一页删掉，搬去了「我的数据」页**"),
    )
}

/**
 * v0.93.0：手册里两处会**把用户指去一个已经变了的地方**。
 *
 * ① 流水页顶部多了单日日期栏 —— 手册原来只写「按天分段」，用户想看某一天只能一路翻；
 *    现在得写明"点日期能挑某天、右边是那天的净额、有「全部」能回来"。
 * ② 语音识别切「在线接口」后，那页下面的离线模型一整块不显示了 ——
 *    手册要是还让人去那儿下模型，用户会以为没下就不能用在线识别。
 *
 * ⚠️ 两条都断一对「新措辞在 + 旧措辞不在」：只写"旧的不在"是假绿（改坏了也过）。
 * 另外各断一次「只出现一次」，防锚点命中两处后其中一处静默落空。
 */
@Test
fun `v0_93_0 手册写明流水页日期栏与在线模式不用下离线模型`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    // ① 流水页顶部日期栏
    assertTrue(
        "没写明流水页顶部有日期栏",
        manual.contains("流水页**顶部有一条日期栏**（v0.93.0）：**左边是日期、点它会弹出日历**"),
    )
    assertTrue(
        "没写明右侧金额是净额口径",
        manual.contains("右边是**这一天的净额**（收入 − 支出，带 +/−，"),
    )
    assertTrue(
        "没写明挑了某天后列表只显示那天",
        manual.contains("挑了某一天后**列表只显示那天的账**"),
    )
    // 旧说法（只能按天分组地翻）必须完整保留，不能被插进来的句子截断
    assertTrue(
        "v0.91.0 那句按天分段被截断了",
        manual.contains("行内只留时分（`14:05`），日期不重复写。"),
    )

    // ② 在线接口：离线模型那一整块不显示
    assertTrue(
        "没写明切在线后离线模型那块不显示",
        manual.contains("**切到「在线接口」后**（v0.93.0），这一页下面**离线模型那一整块都不显示了**"),
    )
    assertTrue(
        "没写明切回离线会原样回来",
        manual.contains("**切回「离线（本机）」，它们原样回来。**"),
    )
    // 旧说法（离线模型要在本机下载）仍然成立，不能被改没了
    assertTrue(
        "离线那条路的说法被改坏了",
        manual.contains("默认「离线（本机）」= SenseVoice，模型要在本机下载"),
    )

    // 每条新措辞在手册里必须只出现一次
    assertEquals(
        "「顶部有一条日期栏」出现了不止一次",
        manual.indexOf("流水页**顶部有一条日期栏**（v0.93.0）"),
        manual.lastIndexOf("流水页**顶部有一条日期栏**（v0.93.0）"),
    )
    assertEquals(
        "「离线模型那一整块都不显示了」出现了不止一次",
        manual.indexOf("**离线模型那一整块都不显示了**"),
        manual.lastIndexOf("**离线模型那一整块都不显示了**"),
    )
}

/**
 * v0.94：会话设置大收拢（用户拍板）——
 * ① 「电话」行删除（主动电话并进「主动关心」三开关、定时电话并进「定时」）；
 * ② 「人设主动关心」更名「主动关心」，弹层 = 三开关 + 每天几通电话；「几天没聊就主动打」下线；
 * ③ 「定时消息」更名「定时」，消息与电话同一张列表；
 * ④ AI 发新帖只在主动链；聊天链回过/赞过的帖子不再摆回它面前。
 *
 * ⚠️ 每条补丁都断一对「新措辞在 + 旧措辞不在」；关键新措辞各断一次「只出现一次」。
 */
@Test
fun `v0_94_0 手册写明主动关心三开关与定时合并与动态新规`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    // ① 主动电话并入主动关心（三开关 + 次数 + 电话优先）
    assertTrue(
        "没写明主动电话并入主动关心",
        manual.contains("- AI 主动打电话（v0.94 起**并进「主动关心」**"),
    )
    assertTrue(
        "没写明一次触发只做一件事（电话优先）",
        manual.contains("**同一时刻不会又打电话又发消息**（电话优先），次数用完自动只发消息"),
    )
    assertTrue(
        "没写明每天最多几通",
        manual.contains("**每天最多几通**，同一个弹层里填，1–9"),
    )
    assertTrue(
        "没写明旧机制下线",
        manual.contains("以前那套「几天没聊就主动打」已经下线"),
    )
    // 旧机制的说法必须整段消失
    assertFalse(
        "「几天没聊」那套还在手册里",
        manual.contains("几天没聊就主动打**（自己填 1–30 天"),
    )
    assertFalse(
        "「电话」弹层的说法还在手册里",
        manual.contains("弹层里两块（**点标题才展开**"),
    )

    // ② 主动关心弹层 = 三个独立开关
    assertTrue(
        "没写明三开关",
        manual.contains("**三个各自独立的开关**——主动发消息 / 主动发动态 / 主动打电话"),
    )
    assertTrue(
        "没写明全关即不触发",
        manual.contains("**全关 = 这个角色完全不主动找你**"),
    )

    // ③ 定时消息与定时电话合并
    assertTrue(
        "没写明定时合并",
        manual.contains("定时消息与定时电话**合在同一张列表里**——电话条目标着「电话」"),
    )
    assertTrue(
        "没写明分组枚举跟着改",
        manual.contains("→ 主动互动（主动关心 / 定时）→ 「查看聊天记录」在列表底部。"),
    )
    assertFalse(
        "旧分组枚举还在手册里",
        manual.contains("→ 电话与定时（电话 / 定时消息）→"),
    )

    // ④ 动态：一切动态动作都只在主动链（聊天链现已完全不碰动态）
    assertTrue(
        "没写明动态动作只在主动时",
        manual.contains("- **她的一切动态动作都只发生在主动来找你的时候**（v0.94）"),
    )
    assertTrue(
        "没写明聊天时她完全不碰动态",
        manual.contains("**跟你聊天时她完全不碰动态**"),
    )

    // 关键新措辞各只出现一次
    assertEquals(
        "「并进主动关心」出现了不止一次",
        manual.indexOf("v0.94 起**并进「主动关心」**"),
        manual.lastIndexOf("v0.94 起**并进「主动关心」**"),
    )
    assertEquals(
        "「合在同一张列表里」出现了不止一次",
        manual.indexOf("合在同一张列表里**"),
        manual.lastIndexOf("合在同一张列表里**"),
    )
}

/**
 * v0.95：人设之间也能互相看动态、回帖、点赞（用户点名「人设之间也能互相回复动态，跟真人一样」）
 * 必须写进助手手册。
 *
 * 两条补丁各钉一对：新措辞在 + 旧措辞不在。
 * ① v0.79.0 那句「把你最近发的动态一并交给她」改成「把你和其他角色最近发的动态一并交给她」；
 * ② v0.94 动态子弹后面补「角色之间也会互相看动态」那条说明。
 */
@Test
fun `v0_95 手册写明人设之间互相看动态回帖点赞`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    // ① 主动链看到的名单含其他角色的帖
    assertTrue(
        "没写明主动链含其他角色的动态",
        manual.contains("App 会把你**和其他角色**最近发的动态一并交给她，她想接话就回一句、觉得好就点个赞。"),
    )
    assertFalse(
        "「只看用户动态」的旧措辞还在手册里",
        manual.contains("App 会把你**最近发的动态**一并交给她"),
    )

    // ② 角色互相回帖是正常现象 + 不会互聊个没完
    assertTrue(
        "没写明角色之间互相回帖点赞",
        manual.contains("- **角色之间也会互相看动态、回帖、点赞**（v0.95）"),
    )
    assertTrue(
        "没写明同一条帖同一角色最多表态一次",
        manual.contains("同一条帖子同一个角色最多表态一次，它们不会在你的帖子下面聊个没完。"),
    )

    // 关键新措辞只出现一次
    assertEquals(
        "「角色之间也会互相看动态」出现了不止一次",
        manual.indexOf("**角色之间也会互相看动态、回帖、点赞**（v0.95）"),
        manual.lastIndexOf("**角色之间也会互相看动态、回帖、点赞**（v0.95）"),
    )
}

/**
 * 用户拍板「聊天链的参考上下文里不要再出现动态，动态只存于主动关心那条链」。
 *
 * 手册必须跟着改口：v0.79.0 / v0.94 / v0.85.0 那三条动态子弹原本描述的是
 * **聊天链也会看动态、回帖、点赞**，现在全部收口到主动关心链。
 *
 * 成对断言（§零.5）：新措辞在 + 旧措辞一次不剩 —— 只写"新话在"守不住静默落空。
 */
@Test
fun `手册写明聊天链不再碰动态`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    // ① 看动态的时机收成「只在主动时」
    assertTrue(
        "没写明只在主动时看动态",
        manual.contains("**只在**她**主动来找你**的那几次（就是你在角色的「主动关心」里设的时间段）"),
    )
    assertFalse("旧的「不只是在聊天的时候」还在手册里", manual.contains("不只是在聊天的时候"))

    // ② 聊天链完全不碰动态
    assertTrue(
        "没写明一切动态动作只在主动时",
        manual.contains("- **她的一切动态动作都只发生在主动来找你的时候**（v0.94）"),
    )
    assertTrue("没写明聊天时她完全不碰动态", manual.contains("**跟你聊天时她完全不碰动态**"))
    assertTrue(
        "没写明聊天里不会冒出动态动作",
        manual.contains("也不会在聊天里突然说「我刚回了你的动态」。"),
    )
    assertFalse(
        "旧的「聊天时她只会回你的帖、点赞」还在手册里",
        manual.contains("跟你聊天时她只会回你的帖、点赞"),
    )

    // ③ 作者身份的说明换成主动链口径（名单里没有它自己的帖）
    assertTrue("没写明她自己的帖不列进名单", manual.contains("**她自己的帖子根本不会列进名单**"))
    assertFalse("旧的「标你自己的是她之前发的」还在手册里", manual.contains("标「你自己」的是她之前发的"))
    assertFalse(
        "旧的「她不会给自己回、也不会给自己点赞」还在手册里",
        manual.contains("她不会给自己回、也不会给自己点赞"),
    )

    // 关键新措辞只出现一次
    assertEquals(
        "「她的一切动态动作都只发生在主动来找你的时候」出现了不止一次",
        manual.indexOf("- **她的一切动态动作都只发生在主动来找你的时候**（v0.94）"),
        manual.lastIndexOf("- **她的一切动态动作都只发生在主动来找你的时候**（v0.94）"),
    )
}

/**
 * v0.99.0：**删掉「手动提炼」那颗按钮的一切说法**（用户拍板「本来就没有的东西不要补回」）。
 *
 * 背景：数据页那颗「提炼」按钮在 **v0.42.1 就被收敛掉了**，界面上一直不存在 ——
 * `DataViewModel.refreshInsights()` 从那天起零调用、`AiEntryCard` 收了 `extracting`/`apiReady`
 * 也没用过，全工程「同款病」扫描时才被翻出来。但 v0.86.0 / v0.88.0 两次改版把
 * 「手动点『提炼』」写回了手册 → **对用户的假承诺**。这版代码链与手册措辞一起更正。
 *
 * ⚠️ 断言**故意不钉版本号**（更正句里也没写）：钉了下一版升号就当场红。
 */
@Test
fun `v0_99_0 手册不再提手动提炼按钮`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    // 新说法在
    assertTrue(
        "没写明记忆是自动提炼、没有手动按钮",
        manual.contains("**记忆是自动提炼的，没有「马上提炼一下」的按钮**"),
    )
    assertTrue(
        "没写清「想更及时就把轮数填小」",
        manual.contains("想让它更及时就**把这个数填小**（填 1 = 每轮都提炼）"),
    )
    assertTrue("没写明「记忆里只会多出或改掉条目」", manual.contains("所以记忆里只会多出或改掉条目"))

    // 旧的两处假承诺必须彻底不在
    assertFalse("「手动点『提炼』」的假承诺还在", manual.contains("手动点「提炼」"))
    assertFalse("「不受这个轮数限制，点了就立刻跑」还在", manual.contains("不受这个轮数限制，点了就立刻跑"))
    assertFalse("「数据页那颗按钮」还在", manual.contains("数据页那颗按钮"))
    assertFalse("「你点『提炼』」还在", manual.contains("你点「提炼」"))

    // 新措辞只出现一次
    assertEquals(
        "提炼更正说明出现了不止一次",
        manual.indexOf("**记忆是自动提炼的，没有「马上提炼一下」的按钮**"),
        manual.lastIndexOf("**记忆是自动提炼的，没有「马上提炼一下」的按钮**"),
    )
}

@Test
fun `手册写明 Agent 后台保活与电池白名单`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    // 新说法在：保活开关 + 电池白名单引导
    assertTrue(
        "没写明「后台保活」开关与用途",
        manual.contains("「**后台保活**」开关（默认开）：首次用过 Agent 后会挂一条静默常驻通知把进程钉住"),
    )
    assertTrue(
        "没写明电池白名单引导",
        manual.contains("点弹层里的「**电池白名单**」把它加进系统电池优化白名单"),
    )
    // 旧的围栏句必须还完整保留（整句保留、句末补新内容）
    assertTrue(
        "原有围栏描述被改丢了",
        manual.contains("悬浮「■ 停止」按钮、15 步上限、执行期间的进度通知（通知设置里可关）。"),
    )
    // 旧段落不再以那句直接收尾（后面必须接上了保活说明）
    assertFalse(
        "保活说明没接上（旧句仍是段尾）",
        manual.contains("（通知设置里可关）。\n"),
    )
    // 新措辞只出现一次
    assertEquals(
        "保活说明出现了不止一次",
        manual.indexOf("「**后台保活**」开关"),
        manual.lastIndexOf("「**后台保活**」开关"),
    )
}

@Test
fun `手册写明 Agent 执行模型与已学脚本`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    // 新说法在：执行模型配置项
    assertTrue(
        "没写明「执行模型」配置项与留空语义",
        manual.contains("同一弹层里还能指定「**执行模型**」：留空 = 跟随所选接口自带的模型"),
    )
    // 新说法在：已学脚本 + 清除入口 + 失效不盲重放
    assertTrue(
        "没写明已学脚本与「清除」入口",
        manual.contains("下面还有「**已学脚本**」一行显示学到的条数、并可点「清除」删掉"),
    )
    assertTrue(
        "没写明本地重放能省掉模型往返",
        manual.contains("直接本地重放上次成功的步骤，**不再逐步问模型**"),
    )
    assertTrue(
        "没写明脚本失配不会瞎点",
        manual.contains("**绝不会照着旧坐标瞎点**"),
    )

    // 旧内容必须仍完整保留（本次是句末追加，不是改写）
    assertTrue(
        "M1 的保活说明被改丢了",
        manual.contains("「**后台保活**」开关（默认开）：首次用过 Agent 后会挂一条静默常驻通知把进程钉住"),
    )
    assertTrue(
        "M1 的白名单引导被改丢了",
        manual.contains("点弹层里的「**电池白名单**」把它加进系统电池优化白名单"),
    )
    // 旧段落不再以 M1 那句收尾（后面必须接上了 M3 的说明）
    assertFalse(
        "M3 说明没接上（M1 那句仍是段尾）",
        manual.contains("（国产 ROM 杀后台是无障碍总被关的主因）。\n"),
    )
    // 新措辞只出现一次
    assertEquals(
        "已学脚本说明出现了不止一次",
        manual.indexOf("下面还有「**已学脚本**」一行"),
        manual.lastIndexOf("下面还有「**已学脚本**」一行"),
    )
}

@Test
fun `手册写明 Agent 屏幕截图开关与隐私口径`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    // 新说法在：开关 + 能力 + 代价 + 生效条件
    assertTrue(
        "没写明「发送屏幕截图」开关",
        manual.contains("同一个弹层里还有个「**发送屏幕截图**」开关（默认开）"),
    )
    assertTrue(
        "没写明截图带来的能力提升",
        manual.contains("于是它看得懂图标、卡片和布局（准确率明显更高）"),
    )
    assertTrue(
        "没写明要多花图片 token / 需要模型支持看图 / 低版本不生效",
        manual.contains("代价是**每步多花一点图片 token**，并且**需要所用模型支持看图**，Android 11 以下系统不生效"),
    )
    // 隐私口径必须在（用户会问"会不会偷偷截屏"）
    assertTrue(
        "没写明图只发给用户自己配置的接口",
        manual.contains("图只发给**用户自己配置的那个接口**"),
    )
    assertTrue(
        "没写明关掉开关就完全不截图",
        manual.contains("关掉开关就完全不再截图"),
    )

    // 旧内容仍完整保留（本次是句末追加）
    assertTrue(
        "M3 的已学脚本说明被改丢了",
        manual.contains("下面还有「**已学脚本**」一行显示学到的条数、并可点「清除」删掉"),
    )
    // M3 那句不再以"脚本只存在手机本地。"收尾（后面必须接上 M4 的说明）
    assertFalse(
        "M4 说明没接上（M3 末尾仍是段尾）",
        manual.contains("脚本只存在手机本地。\n"),
    )
    // 新措辞只出现一次
    assertEquals(
        "截图说明出现了不止一次",
        manual.indexOf("「**发送屏幕截图**」开关"),
        manual.lastIndexOf("「**发送屏幕截图**」开关"),
    )
}

@Test
fun `手册写明音色可克隆多个与已隐藏可恢复`() {
    val manual = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    // 新说法在：可以克隆多个（用户报"只能克隆一个"）
    assertTrue(
        "没写明克隆音色可以克隆多个",
        manual.contains("克隆音色**可以克隆多个**：每克隆一次都是新的一条"),
    )
    // 新说法在：已隐藏可恢复
    assertTrue(
        "没写明已隐藏音色的恢复入口",
        manual.contains("音色页下面有「查看已隐藏的音色（N）」可以逐条**恢复**回来"),
    )
    assertTrue(
        "没写明「删除」只是本机隐藏",
        manual.contains("「删除」只是从本机名单里藏起来，服务商账号里的音色并没有被删除"),
    )
    // 新说法在：重听样本 + 逐字稿对不上会跑调
    assertTrue(
        "没写明可以重听样本核对逐字稿",
        manual.contains("**可以点「▶ 重听样本」边听边核对**"),
    )

    // 旧内容必须仍完整保留（本次是整句保留 + 前后补句）
    assertTrue(
        "音色板块那句被改丢了",
        manual.contains("人设编辑页的候选就是这份名单（那边不再有试听与添加）。"),
    )
    assertTrue(
        "克隆步骤那句被改丢了",
        manual.contains("点「存入我的音色」得到音色；"),
    )
    // 新措辞只出现一次
    assertEquals(
        "克隆多个说明出现了不止一次",
        manual.indexOf("克隆音色**可以克隆多个**"),
        manual.lastIndexOf("克隆音色**可以克隆多个**"),
    )
}
}
