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
        assertTrue(manual.contains("各人设互不影响"))
        assertTrue(manual.contains("接通后由 AI 先开口"))
        assertTrue(manual.contains("22:30–08:00 一律不打"))
        assertTrue(manual.contains("每个 AI 每天最多一通"))
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
        assertTrue(manual.contains("**人设主动关心**（点开是从底部升上来的弹层：这个角色开不开、**这个角色的发送时间段**、**这个角色每天最多几条**——条数**填什么数字都行**"))
        assertTrue(manual.contains("不超过就行）——最后三项只对这个角色生效。"))
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
        assertTrue(manual.contains("入口 每个角色会话右上角 ⋮ → 整页设置里的「定时主动消息」"))
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
        assertTrue(manual.contains("（入口 **每个角色**会话右上角 ⋮ → 整页设置里的「**电话**」行，点开是**底部弹层**"))
        assertFalse(manual.contains("（入口 设置 → 通话行为 → 「AI 主动打电话」，**总开关默认关**）"))
        assertFalse(manual.contains("打开总开关后，**每个 AI 人设单独配**三样"))
        // 点名要的一通不再提"总开关"
        assertTrue(manual.contains("**你点名要的这一通不受规则限制**"))
        assertFalse(manual.contains("不受总开关与人设规则限制"))
        // 全屏通知的直达入口改指「权限」页
        assertTrue(manual.contains("设置 → 权限页里的「全屏通知（锁屏来电）」就有直达系统设置的按钮"))
        assertFalse(manual.contains("设置 → 通话行为 → AI 主动打电话那一页里就有直达系统设置的按钮"))
        // ② 语音章总述 / 设置页怎么用的路径清单：主动打电话归到角色页、通话行为里只剩铃声
        assertTrue(manual.contains("**语音通话铃声在「通话行为」里；允许主动打电话、定时主动消息与专属铃声都在"))
        assertTrue(manual.contains("语音通话铃声在「通话行为」里、允许主动打电话与定时主动消息还有专属铃声"))
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
        assertTrue(manual.contains("**电话**（点开是底部弹层：主动电话 / 定时电话，**点标题才展开细项**）"))
        assertTrue(manual.contains("**铃声**（点开是底部弹层的铃声列表）"))
        assertTrue(manual.contains("**定时消息**（点开就是添加弹层，"))
        assertFalse(manual.contains("联网（开/关）、音色、模型，另外还有**专属铃声**、**定时主动消息**、"))
        // 「允许主动打电话」卡片 → 「电话」行 + 弹层
        assertTrue(manual.contains("每个角色会话右上角 ⋮ 的整页设置里有一行**「电话」**（点开是底部弹层）"))
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
        // 提示词段数跟着改了（v0.68.0 补每日建议：八段 → 九段）
        assertTrue(manual.contains("九段提示词都可编辑"))
        assertFalse(manual.contains("七段提示词都可编辑"))
        assertFalse(manual.contains("八段提示词都可编辑"))
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
        assertTrue(manual.contains("**电话**（点开是底部弹层：主动电话 / 定时电话，**点标题才展开细项**）"))
        assertFalse(manual.contains("允许主动打电话 / 几点打过来 / 说什么 / 多久没聊才打"))
        assertTrue(manual.contains("弹层里两块（**点标题才展开**，页内没有小字说明）—— "))
        assertFalse(manual.contains("弹层里两块 —— ① 开关「**允许主动打电话**」（总闸，关掉则下面一条都不会打）；"))
        assertTrue(manual.contains("② 「**定时电话**」：展开后是一条条定时电话"))
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
        assertTrue(manual.contains("弹层里两块（**点标题才展开**，页内没有小字说明）—— "))
        // v0.56.8：措辞改成"展开后**只填**几天没聊"（「每天几点检查」已删，见 v0568）
        assertTrue(manual.contains("① 「**主动电话**」：展开后只填**几天没聊就主动打**（自己填 1–30 天，默认 3 天）—— "))
        assertFalse(manual.contains("与**每天几点检查**（默认 21:00）"))
        assertTrue(manual.contains("② 「**定时电话**」：展开后是一条条定时电话"))
        assertFalse(manual.contains("① 开关「**允许主动打电话**」"))
        assertFalse(manual.contains("**已经不再有「多久没聊才打」这种触发方式**。"))
        // ② 按住说话：按屏幕左右半区判档
        assertTrue(manual.contains("**左半区 = 取消、右半区 = 转文字、正中间那条灰带 = 录音中**"))
        assertTrue(manual.contains("两块环带选择区"))
        assertFalse(manual.contains("两块扇形选择区"))
        // ③ 用量：v0.56.8 起挪到模型卡片右侧（见 v0568）
        assertFalse(manual.contains("**本月 API 用量，按模型分别列出各自的 tokens**"))
        assertFalse(manual.contains("面板底部显示**本月 API 用量**（服务商返回用量数据时才统计）。"))
    }

    /** v0.56.8：主动电话删掉「每天几点检查」/ 用量改显示在模型卡片上。 */
    @Test
    fun `v0568 主动电话去检查时刻与用量进模型卡进手册`() {
        assertTrue(manual.contains("展开后只填**几天没聊就主动打**（自己填 1–30 天，默认 3 天）—— "))
        assertTrue(manual.contains("每天固定检查一次（时刻沿用这个角色存着的值，默认 21:00）"))
        assertFalse(manual.contains("与**每天几点检查**（默认 21:00）"))
        assertTrue(manual.contains("**每个模型卡片的右侧直接显示这个模型本月消耗了多少**"))
        assertFalse(manual.contains("面板底部显示**本月 API 用量，按模型分别列出各自的 tokens**"))
    }

    /** v0.56.8 第二批：主动关心改随机时刻/可设条数、开关进弹层；两处搜索框删掉只留放大镜。 */
    @Test
    fun `v0568b 主动关心随机时间与搜索框改造进手册`() {
        // ① 主动关心：开关进弹层 + 时间段 + 每天条数 + 贴合时间（最终措辞见 v0568c）
        assertTrue(manual.contains("**人设主动关心**（点开是从底部升上来的弹层：这个角色开不开"))
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
        assertTrue(manual.contains("**这个角色的发送时间段**、**这个角色每天最多几条**"))
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
        assertTrue(manual.contains("「编辑这笔」改收支方向/金额/备注"))
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
        // 角色空间
        assertTrue(manual.contains("**会话设置 → 「角色空间」**"))
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
        // ③ 角色空间背景只剩两档
        assertTrue(manual.contains("**只有两档**：**系统预设**（App 给的那张渐变）与**自定义背景**"))
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
        assertTrue(manual.contains("「视频通话形象」「通话背景」「角色空间」「通话动作」「聊天背景」这几行"))
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
        assertTrue(manual.contains("**视频通话形象**、**通话背景**、**角色空间**、**通话动作**"))
        assertTrue(manual.contains("「角色立绘 / Live 2D 只存不显」这句话已经作废"))
        assertTrue(manual.contains("整页是五组平铺"))
        assertFalse(manual.contains("仍是只存不显"))
        assertFalse(manual.contains("）、角色立绘、Live 2D、聊天背景、联网（开/关）、音色、模型、"))
        // ③ 提示词库：八段 → 九段，框架段数五 → 六
        assertTrue(manual.contains("**九段提示词都可编辑**"))
        assertTrue(manual.contains("每日建议策略**（v0.68.0 补上"))
        assertTrue(manual.contains("后六段的**输出格式框架由代码硬编码不可改**"))
        assertFalse(manual.contains("**八段提示词都可编辑**"))
        assertFalse(manual.contains("后五段的**输出格式框架"))
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
}
