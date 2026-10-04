package com.menghuan.app

import androidx.datastore.preferences.core.edit
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.menghuan.app.data.settingsStore
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM 截图测试（无真机/模拟器环境下"亲眼"检查 UI）：
 * 直接启动真实的 MainActivity（MenghuanApp + Room 种子 + NavHost + 底栏），
 * 用真实用户路径点击导航到各页面，Roborazzi 以真机同款 Skia 渲染管线输出 PNG。
 * 产物：app/build/screenshots 目录下的 PNG。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = MenghuanApp::class, sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class UiRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /**
     * v0.31.0 起无预置导师种子（新用户仅有一个「应用助手」）：
     * 测试自建固定 id 的人设与档案夹具（显式 id upsert 会覆盖可能已播的应用助手种子，
     * 保证 personaId 1/2/3 与各测试写库的 conversationId 对齐）。
     */
    @Before
    fun seedFixtures() {
        val app = compose.activity.application as MenghuanApp
        kotlinx.coroutines.runBlocking {
            app.db.personaDao().upsert(
                com.menghuan.app.data.model.Persona(
                    id = 1, name = "阿布", title = "人生导师", avatarEmoji = "🖋️",
                    systemPrompt = "", isBuiltIn = true,
                )
            )
            app.db.personaDao().upsert(
                com.menghuan.app.data.model.Persona(
                    id = 2, name = "严格教练", title = "严格教练", avatarEmoji = "🎯",
                    systemPrompt = "", isBuiltIn = true,
                )
            )
            app.db.personaDao().upsert(
                com.menghuan.app.data.model.Persona(
                    id = 3, name = "苏格拉底", title = "苏格拉底式导师", avatarEmoji = "💭",
                    systemPrompt = "", isBuiltIn = true,
                )
            )
            app.db.profileFactDao().upsert(com.menghuan.app.data.model.ProfileFact(key = "当前目标", value = "每天 23:00 前睡觉"))
            app.db.profileFactDao().upsert(com.menghuan.app.data.model.ProfileFact(key = "作息", value = "通常 00:30–07:45"))
            app.db.profileFactDao().upsert(com.menghuan.app.data.model.ProfileFact(key = "职业", value = "自由职业"))
            app.db.profileFactDao().upsert(com.menghuan.app.data.model.ProfileFact(key = "关注领域", value = "睡眠 · 英语 · 健身"))
        }
    }

    /** 截当前窗口画面存盘；弹层（ModalBottomSheet/Dialog）打开时有多个 root，取最后一个（弹层窗口） */
    private fun snap(name: String) {
        compose.waitForIdle()
        Thread.sleep(600) // 等页面转场/进场动画走完
        compose.waitForIdle()
        val roots = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes()
        val target = if (roots.size > 1) {
            compose.onAllNodes(androidx.compose.ui.test.isRoot())[roots.size - 1]
        } else {
            compose.onRoot()
        }
        target.captureRoboImage("build/screenshots/$name.png")
    }

    /** 等待包含指定文本的节点出现：waitForIdle 泵轮询（纯 waitUntil 在 Robolectric 下会漏掉滞留的 invalidation） */
    private fun awaitText(text: String) {
        val deadline = System.currentTimeMillis() + 45_000
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                if (compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) return
                compose.waitForIdle()
            } catch (e: AssertionError) {
                lastError = e // waitForIdle 在动画未收敛时可能超时：继续轮询
            }
            Thread.sleep(120)
        }
        throw AssertionError("等待文本超时（45s）：$text", lastError)
    }

    private fun awaitDesc(desc: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 等待数据层状态成立：点击只把 `scope.launch` 排进主线程，DataStore 的实际写落在 IO 线程，
     * `waitForIdle` 泵不到它，读完立刻断言会偶发拿到旧值。
     */
    private fun awaitState(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            try {
                compose.waitForIdle()
            } catch (e: AssertionError) {
                // 动画未收敛：继续轮询
            }
            if (condition()) return
            Thread.sleep(80)
        }
        throw AssertionError("等待状态超时（15s）：$message")
    }

    /** 点击 + 重试：节点可能在数据流重组/进场动画瞬间不可注入触摸 */
    private fun clickText(text: String) {
        repeat(3) { attempt ->
            try {
                compose.onNodeWithText(text).performClick()
                return
            } catch (e: AssertionError) {
                if (attempt == 2) throw e
                compose.waitForIdle()
                Thread.sleep(400)
            }
        }
    }

    private fun clickDesc(desc: String) = compose.onNodeWithContentDescription(desc).performClick()

    /**
     * 滚到指定 tag 的节点再点击。
     * 数据页底部入口（记账本 / AI 洞察与周报）在首屏之外，直接 performClick 会被
     * 测试框架裁到可视区外而不生效——且页面自身的文案会"骗过"后续断言。
     */
    private fun scrollToTagAndClick(tag: String) {
        repeat(3) { attempt ->
            try {
                compose.onNodeWithTag(tag).performScrollTo().performClick()
                return
            } catch (e: AssertionError) {
                if (attempt == 2) throw e
                compose.waitForIdle()
                Thread.sleep(400)
            }
        }
    }

    @Test
    fun render1ChatList() {
        awaitText("阿布") // 会话行（种子人设）已上屏
        snap("1-chatlist")
    }

    /** 数据页（v0.42.0 改版 / v0.42.1 做减法）：本周回顾条置顶 + 今日建议 + 记账本入口 + AI 合并入口 */
    @Test
    fun render2Data() {
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_1").performClick() // 底栏 tab
        awaitText("本周回顾") // 置顶条（合并原打卡与完成度）
        awaitText("今日建议") // 独立生成链路
        awaitText("记账本") // 页面底部入口
        awaitText("AI 洞察与周报") // 合并入口卡
        // v0.42.1：区块头的「重新生成」「提炼」动作文字已删（生成动作收敛进弹窗）
        compose.onNodeWithText("重新生成").assertDoesNotExist()
        compose.onNodeWithText("提炼").assertDoesNotExist()
        snap("2-data")
    }

    /**
     * v0.42.1：今日建议改成点击弹窗。
     * 空建议态点一下应弹出面板（标题「今日建议」+ 面板里的生成按钮），
     * 而不是像以前那样靠区块头的「重新生成」。
     */
    @Test
    fun render22SuggestionSheet() {
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_1").performClick()
        awaitText("今天还没有建议")
        // 点空态卡片 → 弹层
        compose.onNodeWithText("今天还没有建议").performClick()
        awaitText("生成今日建议")
        snap("43-suggestion-sheet")
    }

    /**
     * v0.42.3：用户发的图片必须靠右（自己那侧），AI 生图靠左。
     * 此前 ImageBubble 无脑左对齐，用户发的图会跑到 AI 那一侧。
     * 夹具：写两张真实 PNG 到临时文件，插一条用户图消息 + 一条 AI 图消息。
     */
    @Test
    fun render23ImageBubbleSides() {
        val app = compose.activity.application as MenghuanApp
        val dir = compose.activity.cacheDir
        fun writePng(name: String, color: Int): String {
            val f = java.io.File(dir, name)
            val bmp = android.graphics.Bitmap.createBitmap(120, 120, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(color)
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            return f.absolutePath
        }
        val userImg = writePng("user_img.png", android.graphics.Color.parseColor("#1ABCFE"))
        val aiImg = writePng("ai_img.png", android.graphics.Color.parseColor("#A259FF"))
        kotlinx.coroutines.runBlocking {
            app.db.messageDao().insert(
                com.menghuan.app.data.model.ChatMessage(
                    conversationId = 1, isFromUser = true, content = "我拍的", imagePath = userImg,
                )
            )
            app.db.messageDao().insert(
                com.menghuan.app.data.model.ChatMessage(
                    conversationId = 1, isFromUser = false, content = "a purple square", imagePath = aiImg,
                )
            )
        }
        awaitText("阿布")
        clickText("阿布")
        awaitText("我拍的") // 用户图注（靠右那条）
        // v0.42.4 位置断言：用户发的图必须落在版心右半边，AI 的图落在左半边。
        // 上一版只在 ImageBubble 内部切 horizontalAlignment，而外层 Box 默认 TopStart，
        // 结果图片一律贴左（用户反馈"我发的照片还是显示在左侧"）——加断言防止再次静默跑偏。
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val userX = compose.onNodeWithText("我拍的").fetchSemanticsNode().boundsInRoot.center.x
        val aiX = compose.onNodeWithText("a purple square").fetchSemanticsNode().boundsInRoot.center.x
        check(userX > root.center.x) { "用户图注应在右半边，实际 x=$userX 版心中线=${root.center.x}" }
        check(aiX < root.center.x) { "AI 图注应在左半边，实际 x=$aiX 版心中线=${root.center.x}" }
        snap("44-image-sides")
    }

    @Test
    fun render3Tasks() {
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_2").performClick() // 底栏 tab
        awaitText("暂无任务") // 空库空态
        snap("3-tasks")
    }

    /**
     * v0.43.0 微信风输入栏：表情面板（emoji 页 / 我的表情页）+ 加号面板。
     * 三个面板共用底部同一块高度，所以必须逐个截图确认布局没被挤压/错位。
     */
    @Test
    fun render24InputPanels() {
        awaitText("阿布")
        clickText("阿布") // 进单聊

        // 空输入时右侧是「表情 + 加号」两颗键（有输入才变发送键）
        awaitDesc("表情")
        compose.onNodeWithContentDescription("表情").performClick()
        // v0.43.1：去掉了「常用 / 情绪 / 手势」分区标题与中间灰线，
        // emoji 现在是一整片扁平网格 —— 断言改成"网格本身出现了"（网格里有具体 emoji）。
        awaitText("😀")
        snap("45-emoji-panel")

        // 切到「收藏」页：v0.45.0 改名（原「我的表情」），空态只剩网格末尾一个加号格
        // （说明文字已删，唯一入口 = 那个加号格）
        clickText("收藏")
        awaitDesc("从相册添加表情")
        snap("46-sticker-panel-empty")

        // 加号面板：相册 / 拍摄 / 文件
        compose.onNodeWithContentDescription("更多").performClick()
        awaitDesc("拍摄")
        awaitText("相册")
        awaitText("拍摄")
        awaitText("文件")
        snap("47-plus-panel")
    }

    /**
     * v0.50.0：识别模型不随包（228MB 装后下载）。没模型时点语音键**不进语音态**，
     * 而是直接拉起「语音识别模型」下载面板——让用户按住说完才发现转写为空，比不发声更糟。
     */
    @Test
    fun render25VoiceModeBlockedByMissingModel() {
        awaitText("阿布")
        clickText("阿布") // 进单聊

        awaitDesc("切换到语音")
        compose.onNodeWithContentDescription("切换到语音").performClick()
        awaitText("语音识别模型") // 下载面板上屏
        awaitText("未就绪")
        awaitText("model.int8.onnx")
        awaitText("下载模型")
        snap("48-asr-model-sheet")
        // 语音态没进去：输入栏仍是打字态占位符
        awaitText("发消息…")
    }

    /**
     * v0.50.0 AI 语音条气泡：AI 侧走「面色 + 细线」（与 AI 文字气泡同一视觉体系），
     * 用户侧保持黑底药丸——两边必须一眼分得清谁在说话。
     */
    @Test
    fun render26AiVoiceBubble() {
        val app = compose.activity.application as MenghuanApp
        val fake = java.io.File(app.filesDir, "voice").apply { mkdirs() }
        java.io.File(fake, "ai_demo.mp3").writeBytes(ByteArray(1024))
        kotlinx.coroutines.runBlocking {
            app.db.messageDao().insert(
                com.menghuan.app.data.model.ChatMessage(
                    conversationId = 1, isFromUser = false,
                    content = "别熬了，明天还要早起。去睡。",
                    audioPath = java.io.File(fake, "ai_demo.mp3").absolutePath,
                    audioDurationMs = 5_000,
                    mediaKind = com.menghuan.app.data.model.MediaKind.AUDIO.name,
                )
            )
            app.db.messageDao().insert(
                com.menghuan.app.data.model.ChatMessage(
                    conversationId = 1, isFromUser = true,
                    content = "知道了知道了",
                    audioPath = java.io.File(fake, "ai_demo.mp3").absolutePath,
                    audioDurationMs = 3_000,
                    mediaKind = com.menghuan.app.data.model.MediaKind.AUDIO.name,
                )
            )
        }
        awaitText("阿布")
        clickText("阿布")
        awaitText("5\"") // AI 侧时长（formatDuration 用的是直引号）
        awaitText("3\"") // 用户侧时长
        snap("50-voice-bubbles")
    }

    /** v0.50.0 语音面板：两个总开关 + 语音合成 / 识别模型两个二级入口 */
    @Test
    fun render27VoiceSettingsSheet() {
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_3").performClick() // 我的
        awaitText("设置")
        clickText("设置")
        awaitText("语音")
        clickText("语音")
        awaitText("AI 语音条")
        awaitText("语音通话")
        awaitText("语音合成")
        awaitText("识别模型")
        snap("51-voice-settings")
    }

    /** v0.50.0 语音合成面板：三家 provider 可切 + Key/地址/模型 + 试听 + 克隆 */
    @Test
    fun render28TtsConfigSheet() {
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_3").performClick() // 我的
        awaitText("设置")
        clickText("设置")
        awaitText("语音")
        clickText("语音")
        awaitText("语音合成")
        clickText("语音合成")
        awaitText("服务商")
        awaitText("硅基流动")
        awaitText("小米 MiMo")
        awaitText("自定义端点")
        awaitText("API Key")
        awaitText("音色克隆")
        snap("52-tts-config")
    }


    @Test
    fun render4Chat() {
        val app = compose.activity.application as MenghuanApp
        // v0.31.0 起聊天无演示种子消息：测试自插对话内容
        kotlinx.coroutines.runBlocking {
            app.db.messageDao().insert(
                com.menghuan.app.data.model.ChatMessage(
                    conversationId = 1, isFromUser = true, content = "本来打算睡了，结果刷短视频没刹住……",
                )
            )
            app.db.messageDao().insert(
                com.menghuan.app.data.model.ChatMessage(
                    conversationId = 1, isFromUser = false, content = "你先别骂我",
                )
            )
        }
        awaitText("阿布")
        clickText("阿布") // 会话行 → push 单聊
        awaitText("你先别骂我") // 预置消息上屏
        snap("4-chat")
    }

    /** 长内容压测：超长消息 / ❤️ 大表情 / 任务页有数据态（逾期·今天·明天·已完成标签） */
    @Test
    fun render5FilledAndLong() {
        val app = compose.activity.application as MenghuanApp
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        // 先等种子人设上屏：seedIfEmpty 是异步播种，若种子未就绪，
        // 下面的 setPinned(2L) 会对空表做 no-op（v0.21 起启动更重、竞态窗口更明显）
        awaitText("阿布")
        kotlinx.coroutines.runBlocking {
            app.db.messageDao().insert(
                com.menghuan.app.data.model.ChatMessage(
                    conversationId = 1, isFromUser = true,
                    content = "最近两周都在熬夜，平均一点半才睡，白天效率特别低，下午基本废掉。我也试过早点上床，但躺下就忍不住刷手机，越刷越精神。帮我拆一个能落地的方案吧，要具体到每一步的那种，不要再说早点睡了这种正确的废话。",
                )
            )
            app.db.messageDao().insert(
                com.menghuan.app.data.model.ChatMessage(
                    conversationId = 1, isFromUser = false,
                    content = "好，不谈道理，只谈方案。\n\n" +
                        "**第一步：今晚 22:30 把手机放到客厅充电**，卧室不带——这是唯一重要的一步，其他都是它的附件。\n\n" +
                        "- 卧室换一本纸质书，翻 `10 分钟`\n" +
                        "- 前三天允许失败一次，第二次失败就要复盘是哪一步断了\n\n" +
                        "1. 22:30 定成闹钟\n" +
                        "2. 手机出卧室\n\n" +
                        "把今晚的执行卡贴在最显眼的地方：\n\n" +
                        "```\n22:30 手机放客厅\n23:00 熄灯睡觉\n```\n\n" +
                        "做到了明天告诉我，做不到也告诉我——我要的是**数据**，不是借口。",
                )
            )
            app.db.messageDao().insert(
                com.menghuan.app.data.model.ChatMessage(conversationId = 1, isFromUser = false, content = "❤️")
            )
            // 严格教练的新回复：会话列表未读点 + 光晕的渲染证据
            app.db.messageDao().insert(
                com.menghuan.app.data.model.ChatMessage(conversationId = 2, isFromUser = false, content = "今晚 23:00 前睡觉，做到没有？")
            )
            app.db.taskDao().upsert(
                com.menghuan.app.data.model.TaskItem(title = "整理作品集并更新主页", dueAt = today - 3L * 86_400_000, createdBy = "user")
            )
            app.db.taskDao().upsert(
                com.menghuan.app.data.model.TaskItem(title = "23:00 前放下手机", note = "打卡后发消息给阿布", dueAt = today, createdBy = "ai")
            )
            app.db.taskDao().upsert(
                com.menghuan.app.data.model.TaskItem(title = "画 1 小时商稿草图", dueAt = today, isDone = true, createdBy = "ai")
            )
            app.db.taskDao().upsert(
                com.menghuan.app.data.model.TaskItem(title = "08:00 起床 + 晨跑 20 分钟", dueAt = today + 86_400_000, createdBy = "ai")
            )
            // 每日任务板块：一条未完成 + 一条今天已完成（划线 +「已完成」标签）
            app.db.taskDao().upsert(
                com.menghuan.app.data.model.TaskItem(title = "背 20 个单词", repeatDaily = true, createdBy = "ai")
            )
            app.db.taskDao().upsert(
                com.menghuan.app.data.model.TaskItem(
                    title = "阅读 30 分钟",
                    repeatDaily = true,
                    isDone = true,
                    completionDate = java.time.LocalDate.now(zone).toEpochDay(),
                )
            )
            // 置顶严格教练（id=2）：列表行排到首位，时间前带 📌 标识
            app.db.personaDao().setPinned(2L, true)
        }
        awaitText("阿布")
        // 置顶写入的确定性校验（种子已就绪，此断言不应再失败）
        kotlinx.coroutines.runBlocking {
            org.junit.Assert.assertEquals(
                true,
                app.db.personaDao().getById(2L)?.pinned,
            )
        }
        compose.waitForIdle()
        snap("0-chatlist-unread") // 未读点光晕 + 打卡回执消息的列表预览 + 置顶标识
        // 任务页（有数据）：分组标题 + 标签 + 完成划线
        compose.onNodeWithTag("bottom_tab_2").performClick()
        awaitText("已逾期")
        snap("5-tasks-filled")
        // 单聊页（长内容）：气泡宽度折行 + ❤️ 大表情 + 未读点光晕
        compose.onNodeWithTag("bottom_tab_0").performClick() // 底栏 tab
        awaitText("❤️") // 列表预览已更新为最后一条消息
        clickText("阿布")
        awaitText("第一步") // Markdown 长消息上屏（粗体行）
        snap("6-chat-long")
    }

    /** 搜索态：输入关键字 → 人设/消息命中结果上屏 */
    @Test
    fun render6Search() {
        // 搜索走真实 DB（种子演示消息不入库），先预置一条可命中的消息
        val app = compose.activity.application as MenghuanApp
        kotlinx.coroutines.runBlocking {
            app.db.messageDao().insert(
                com.menghuan.app.data.model.ChatMessage(
                    conversationId = 1, isFromUser = false,
                    content = "把「睡前刷手机」单独拆成追踪目标。",
                )
            )
        }
        awaitText("阿布")
        // DAO 直查：证明搜索 SQL 本身能命中（失败则问题在查询；成功则问题在 UI 链路）
        val hits = kotlinx.coroutines.runBlocking { app.db.messageDao().searchMessages("睡前") }
        assert(hits.isNotEmpty()) { "searchMessages 应命中预置消息，实得 ${hits.size} 条" }
        clickText("搜索") // 搜索行（列表态唯一）
        // 搜索态输入框是主窗口 BasicTextField，可直接输入
        compose.onNode(androidx.compose.ui.test.hasSetTextAction())
            .performTextInput("睡前")
        awaitText("睡前刷手机") // 命中结果上屏
        snap("7-search")
    }

    /** 数据页真数据态：AI 洞察（提炼产物）+ 近 7 天打卡热力 + 真实完成度圆环 */
    @Test
    fun render7DataReal() {
        val app = compose.activity.application as MenghuanApp
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        fun dayStart(offsetDays: Long) =
            today.minusDays(offsetDays).atStartOfDay(zone).toInstant().toEpochMilli()
        // 先等种子上屏（seedIfEmpty 异步）：末尾的 setPinned(2L) 需要人设 2 已存在
        awaitText("阿布")
        kotlinx.coroutines.runBlocking {
            app.db.aiInsightDao().insert(
                com.menghuan.app.data.model.AiInsight(
                    title = "熬夜与短视频强相关",
                    detail = "过去 14 天，睡前刷短视频的日子平均晚睡 82 分钟。建议把「睡前刷手机」替换为固定的放松流程。",
                )
            )
            app.db.aiInsightDao().insert(
                com.menghuan.app.data.model.AiInsight(
                    title = "上午效率最高",
                    detail = "你的深度工作集中在 9:00–11:30，重要任务建议安排在这个时段，下午适合处理琐事。",
                    createdAt = System.currentTimeMillis() - 86_400_000,
                )
            )
            // 近 7 天打卡：每日任务（本周内天天该做）+ 5 天完成记录
            // createdAt 回填到本周一之前，否则「当天之后创建的当天不算」会把周一到昨天都排除掉
            val mondayStart = today.with(java.time.DayOfWeek.MONDAY)
                .atStartOfDay(zone).toInstant().toEpochMilli() - 86_400_000L
            val dailyId = app.db.taskDao().upsert(
                com.menghuan.app.data.model.TaskItem(
                    title = "每日阅读 30 分钟",
                    repeatDaily = true,
                    isDone = true,
                    completionDate = today.toEpochDay(),
                    createdAt = mondayStart,
                )
            )
            listOf(0L, 1L, 3L, 4L, 6L).forEach { offset ->
                app.db.taskCompletionDao().insert(
                    com.menghuan.app.data.model.TaskCompletion(
                        taskId = dailyId,
                        day = today.minusDays(offset).toEpochDay(),
                    )
                )
            }
            // 统一口径后的本周回顾：本周每天 1 项每日任务 + 今天 3 项基础任务；
            // 完成 = 每周任务里落在本周的打卡数 + 基础任务 2 项（具体百分比随今天是周几变化，测试里动态算）
            app.db.taskDao().upsert(
                com.menghuan.app.data.model.TaskItem(title = "整理作品集主页", dueAt = dayStart(0), isDone = true)
            )
            app.db.taskDao().upsert(
                com.menghuan.app.data.model.TaskItem(title = "回复合作邮件", dueAt = dayStart(0), isDone = true)
            )
            app.db.taskDao().upsert(
                com.menghuan.app.data.model.TaskItem(title = "今日未完成的任务", dueAt = dayStart(0))
            )
            // 置顶严格教练：列表行时间前出现 📌 并排到首位
            app.db.personaDao().setPinned(2L, true)
        }
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_1").performClick() // 底栏 tab
        // v0.42.0：洞察详情移入全屏页，数据页只显示合并入口卡上的数量摘要
        awaitText("2 条洞察")
        // 本周回顾：v0.42.1 起百分比文案与连续天数徽标都已删（用户要求），
        // 卡片只剩 x/y 主指标 + 格子条 + 进度条，所以只断言卡片标题在
        awaitText("本周回顾")
        snap("8-data-real")
    }

    /** AI 任务建议卡：提炼引擎产出（测试直接注入 hub）→ AI 气泡下方白面细线卡 +「添加任务」 */
    @Test
    fun render8TaskSuggestions() {
        val app = compose.activity.application as MenghuanApp
        app.taskSuggestionHub.publish(
            1L,
            listOf(
                com.menghuan.app.data.TaskSuggestion(
                    title = "23:00 前把手机放客厅充电",
                    note = "卧室不带手机，闹钟用实体钟",
                    daily = true,
                ),
            ),
        )
        awaitText("阿布")
        clickText("阿布")
        awaitText("添加任务") // 建议卡上屏
        snap("9-chat-suggestion")
    }

    /** 人设物理删除后的空状态：全部删光 → 虚线选中框「＋ 新建人设」 */
    @Test
    fun render9EmptyAfterPersonaDelete() {
        val app = compose.activity.application as MenghuanApp
        kotlinx.coroutines.runBlocking {
            app.db.personaDao().observeAll().first().forEach { app.personaRepository.delete(it.id) }
        }
        awaitText("新建人设") // 虚线框空状态上屏（种子不再回填：seedOnce 已消耗）
        snap("10-chatlist-empty")
    }

    /** 「我的」页：人设管理 / 基础档案 / 设置 三组行卡 */
    @Test
    fun render11Me() {
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_3").performClick()
        awaitText("基础档案") // 行卡上屏
        snap("12-me")
    }

    /**
     * 深色模式（v0.35.0）：设置 · 外观 切到深色后，主页与数据页整体反相。
     * 用例核心不是"截图好看"，而是确认深色真的生效（不再是一张白底页面）。
     */
    @Test
    fun render14DarkMode() {
        val app = compose.activity.application as MenghuanApp
        awaitText("阿布")
        // 直接写 DataStore：切 tab → 进设置 → 点 chip 的路径太长，底层是同一个键
        kotlinx.coroutines.runBlocking { app.settingsRepository.setThemeMode("DARK") }
        compose.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                compose.onAllNodesWithText("消息").fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        snap("25-dark-chatlist")
        compose.onNodeWithTag("bottom_tab_1").performClick()
        awaitText("本周回顾")
        snap("26-dark-data")
        kotlinx.coroutines.runBlocking { app.settingsRepository.setThemeMode("SYSTEM") }
        compose.waitForIdle()
    }

    /**
     * 外观弹层（v0.35.1）：主题三选一只在「设置 → 外观」里出现，
     * 设置面板底部不再有快捷 chips。断言弹层打开且能选到「深色」。
     */
    @Test
    fun render15AppearanceSheet() {
        val app = compose.activity.application as MenghuanApp
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_3").performClick() // 我的
        awaitText("设置")
        clickText("设置")
        awaitText("外观") // 设置列表里的入口
        snap("27-settings-list")
        clickText("外观")
        awaitText("外观") // 外观弹层上屏
        awaitText("跟随系统")
        awaitText("浅色")
        awaitText("深色")
        snap("28-appearance")
        // 切到深色确认生效，然后还原（settingsStore 是进程级单例）
        clickText("浅色")
        awaitState("点了「浅色」后 themeMode 应为 LIGHT") {
            kotlinx.coroutines.runBlocking { app.settingsRepository.themeMode.first() } == "LIGHT"
        }
        kotlinx.coroutines.runBlocking { app.settingsRepository.setThemeMode("SYSTEM") }
        compose.waitForIdle()
    }

    /**
     * AI 周报（v0.42.0 移入全屏页 / v0.42.1 默认折叠）：
     * 数据页只留合并入口卡；进全屏页后周报**默认折叠**（用户要求），
     * 点一下标题行才展开全文。
     */
    @Test
    fun render16WeeklyReportCardCollapse() {
        val app = compose.activity.application as MenghuanApp
        val today = java.time.LocalDate.now()
        val monday = today.with(java.time.DayOfWeek.MONDAY)
        kotlinx.coroutines.runBlocking {
            app.db.aiWeeklyReportDao().upsert(
                com.menghuan.app.data.model.AiWeeklyReport(
                    weekStart = monday.toEpochDay(),
                    weekEnd = monday.plusDays(6).toEpochDay(),
                    content = (1..8).joinToString("") { "第${it}段周报正文，讲的是这周真实发生过的任务与打卡情况，以及值得注意的规律。" },
                )
            )
        }
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_1").performClick()
        awaitText("1 期周报") // 合并入口卡上的数量摘要
        scrollToTagAndClick("ai_entry_card")
        awaitText("AI 洞察 · 0 条") // 只存在于全屏详情页的区块标题（本用例没插洞察）
        awaitText("1 期") // 周报历史区块标题（v0.42.1 默认折叠）
        // v0.42.1：默认折叠 —— 正文不该出现
        compose.onNodeWithText("第8段周报正文，讲的是这周真实发生过的任务与打卡情况，以及值得注意的规律。").assertDoesNotExist()
        snap("29-weekly-collapsed")
        // 点标题行进卡片展开 → 全文直出（clickable 挂在外层卡片上，点期标签即可）
        val weekLabel = "${monday.monthValue}月${monday.dayOfMonth}日 – " +
            "${monday.plusDays(6).monthValue}月${monday.plusDays(6).dayOfMonth}日"
        awaitText(weekLabel)
        compose.onNodeWithText(weekLabel).performClick()
        awaitText("第8段周报正文")
        snap("29-weekly-fulltext")
    }

    /**
     * 清除全部数据（v0.36.5）：确认框把"将删除"与"保留"都写清楚；点「清除」后
     * 六类数据全空，而人设与接口配置必须活着——这是"保留人设与接口"那句话的唯一保障。
     */
    @Test
    fun render17ClearAllData() {
        val app = compose.activity.application as MenghuanApp
        kotlinx.coroutines.runBlocking {
            app.settingsRepository.saveApiConfig(
                com.menghuan.app.data.ApiConfig("https://api.test.com/v1", "sk-keep", "test-model"),
            )
            app.db.messageDao().insert(
                com.menghuan.app.data.model.ChatMessage(
                    conversationId = 1, isFromUser = true, content = "今天想早点睡",
                ),
            )
            app.db.taskDao().upsert(com.menghuan.app.data.model.TaskItem(title = "23:00 前睡觉"))
            app.db.aiInsightDao().insert(
                com.menghuan.app.data.model.AiInsight(title = "入睡时间偏晚", detail = "近 7 天有 5 天超过 1 点"),
            )
        }
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_3").performClick() // 我的
        awaitText("设置")
        clickText("设置")
        awaitText("数据与隐私")
        clickText("数据与隐私")
        awaitText("清除全部数据（保留人设与接口）")
        snap("31-data-privacy")
        clickText("清除全部数据（保留人设与接口）")
        awaitText("将删除：全部聊天消息")
        awaitText("保留：人设、API 接口配置")
        snap("32-clear-confirm")
        clickText("清除")
        awaitState("清除后六类数据全空、人设与接口保留") {
            kotlinx.coroutines.runBlocking {
                app.db.messageDao().observeCount().first() == 0L &&
                    app.db.taskDao().observeAll().first().isEmpty() &&
                    app.db.profileFactDao().observeAll().first().isEmpty() &&
                    app.db.aiInsightDao().observeLatest().first().isEmpty() &&
                    app.db.aiWeeklyReportDao().observeAll().first().isEmpty() &&
                    app.db.personaDao().count() > 0 &&
                    app.settingsRepository.apiConfig.first().isComplete
            }
        }
        snap("33-after-clear")
        // settingsStore 是进程级单例：清掉接口配置，避免泄漏到同类后续测试
        kotlinx.coroutines.runBlocking { app.settingsStore.edit { it.clear() } }
    }

    /**
     * 单条清理（v0.36.5）：洞察卡长按 → 弹「删除」菜单 → 点菜单项删除；
     * 周报历史列表里长按某一期走同一套菜单流程。
     */
    @Test
    fun render18LongPressDeleteInsightAndReport() {
        val app = compose.activity.application as MenghuanApp
        val monday = java.time.LocalDate.now().with(java.time.DayOfWeek.MONDAY)
        val content = "这周的周报正文比较长，专门用来验证在历史列表里长按删除单期周报的交互。"
        kotlinx.coroutines.runBlocking {
            app.db.aiInsightDao().insert(
                com.menghuan.app.data.model.AiInsight(title = "入睡时间偏晚", detail = "近 7 天有 5 天超过 1 点"),
            )
            app.db.aiWeeklyReportDao().upsert(
                com.menghuan.app.data.model.AiWeeklyReport(
                    weekStart = monday.toEpochDay(),
                    weekEnd = monday.plusDays(6).toEpochDay(),
                    content = content,
                ),
            )
        }
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_1").performClick() // 数据
        awaitText("1 条洞察") // 合并入口卡的数量摘要
        scrollToTagAndClick("ai_entry_card") // 进全屏详情页
        awaitText("AI 洞察 · 1 条") // 只存在于全屏详情页的区块标题
        awaitText("入睡时间偏晚") // 洞察详情直接可见
        snap("34-insight-card")
        compose.onNodeWithText("入睡时间偏晚").performTouchInput { longClick() }
        awaitText("删除这条洞察") // 长按只弹菜单，不直接删
        snap("35-insight-menu")
        clickText("删除这条洞察")
        awaitState("点了菜单里的「删除」后该洞察应被删除") {
            kotlinx.coroutines.runBlocking { app.db.aiInsightDao().observeLatest().first().isEmpty() }
        }
        compose.waitForIdle()
        snap("36-insight-deleted")

        // 周报：全屏页里默认折叠（v0.42.1），长按标题行 → 菜单 → 删除
        val mondayWeekStart = monday
        val sunday = monday.plusDays(6)
        val weekLabel = "${mondayWeekStart.monthValue}月${mondayWeekStart.dayOfMonth}日 – " +
            "${sunday.monthValue}月${sunday.dayOfMonth}日"
        awaitText(weekLabel) // 周报期标签（默认折叠态，标题行可见）
        snap("37-report-detail")
        compose.onNodeWithText(weekLabel).performTouchInput { longClick() }
        awaitText("删除这期")
        snap("38-report-menu")
        clickText("删除这期")
        awaitState("点了菜单里的「删除」后该期周报应被删除") {
            kotlinx.coroutines.runBlocking { app.db.aiWeeklyReportDao().observeAll().first().isEmpty() }
        }
        compose.waitForIdle()
        snap("39-report-deleted")
    }

    /** 周报空态：API 已配置但还没有周报 → 板块显示「暂无周报」+ 生成入口（第一份周报的入口保障） */
    @Test
    fun render12WeeklyPlaceholder() {
        val app = compose.activity.application as MenghuanApp
        kotlinx.coroutines.runBlocking {
            app.settingsRepository.saveApiConfig(
                com.menghuan.app.data.ApiConfig("https://api.test.com/v1", "sk-test", "test-model"),
            )
        }
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_1").performClick()
        awaitText("AI 洞察与周报") // 合并入口卡上屏（apiReady 即显示）
        // v0.42.1 去说明性小字：洞察与周报皆空时不再挂「还没有产出，聊聊就有」副标题，
        // 卡片只剩标题 + 右箭头
        compose.onNodeWithText("还没有产出，聊聊就有").assertDoesNotExist()
        snap("13-data-weekly-empty")
        // settingsStore 是进程级单例：清掉 API 配置，避免泄漏到同类后续测试
        kotlinx.coroutines.runBlocking { app.settingsStore.edit { it.clear() } }
    }

    /**
     * 人设编辑器：点头像换图（无「自定义」按钮）+ emoji 快选（28 预设）+ 写作规范弹层。
     * v0.35.1 起编辑器不再有「头像 emoji」输入框，弹层上屏的锚点改为「点头像换图」。
     */
    @Test
    fun render13PersonaEditorGuide() {
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_3").performClick() // 我的
        awaitText("人设管理")
        clickText("人设管理") // 进入人设管理页
        awaitDesc("新增人设") // 顶栏 ＋（新建入口）待上屏
        clickDesc("新增人设")
        awaitText("点头像换图") // 编辑弹层上屏
        awaitText("查看写作规范")
        snap("14-persona-editor")
        clickText("查看写作规范")
        awaitText("设定词写作规范")
        awaitText("示例：健身教练")
        snap("15-persona-guide")
    }

    /**
     * 记账本全屏页（v0.42.0）：从数据页底部入口进入，验证
     * 现金余额 Hero、月度统计三栏、流水行与空态。
     */
    @Test
    fun render19LedgerPage() {
        val app = compose.activity.application as MenghuanApp
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        fun ts(daysAgo: Long, hour: Int): Long =
            today.minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        kotlinx.coroutines.runBlocking {
            app.db.ledgerDao().upsert(
                com.menghuan.app.data.model.LedgerEntry(
                    isIncome = true, amountCents = 800_00, note = "月薪到账",
                    source = com.menghuan.app.data.model.LedgerSource.MANUAL, occurredAt = ts(1, 9),
                )
            )
            app.db.ledgerDao().upsert(
                com.menghuan.app.data.model.LedgerEntry(
                    isIncome = false, amountCents = 35_80, note = "午餐",
                    source = com.menghuan.app.data.model.LedgerSource.IMAGE, occurredAt = ts(0, 12),
                )
            )
            app.db.ledgerDao().upsert(
                com.menghuan.app.data.model.LedgerEntry(
                    isIncome = false, amountCents = 128_00, note = "打车",
                    source = com.menghuan.app.data.model.LedgerSource.VOICE, occurredAt = ts(2, 20),
                )
            )
        }
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_1").performClick()
        awaitText("记账本")
        scrollToTagAndClick("ledger_entry_row") // 底部入口 → 全屏记账页
        awaitText("月度统计") // 只存在于记账本全屏页：用于确认真的跳走了（数据页也有「现金余额」）
        awaitText("636.20") // 800 - 35.8 - 128 = 636.2
        awaitText("午餐") // 流水行（含来源标签）
        awaitText("识图")
        snap("40-ledger")
    }

    /** 记一笔弹层（v0.42.0）：手动 / 语音 / 识图三个入口 + 收支切换 */
    @Test
    fun render20LedgerEntrySheet() {
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_1").performClick()
        awaitText("记账本")
        scrollToTagAndClick("ledger_entry_row")
        awaitText("月度统计") // 再次确认已进入记账本全屏页（顶栏 ＋ 只在那一页有）
        clickDesc("记一笔") // 顶栏 ＋
        awaitText("记一笔")
        awaitText("支出")
        awaitText("收入")
        awaitText("语音")
        awaitText("识图")
        snap("41-ledger-sheet")
    }

    /**
     * 周度统计切换（v0.42.1，用户要求）：点左上角「月度统计」标题 → 切成「周度统计」，
     * 三栏合计改按本周（周一为起点）聚合，流水区标题也变成「本周流水 · N 笔」。
     *
     * 夹具设计：一条收入落在**本月但不在本周**（把日期推到本月最后一个周日之后的下一天；
     * 若本周末已到月末，则退到本月 1 号——1 号不可能落在含今天的这一周里，因为今天在周内）。
     * 这样月档含它、周档不含它，两档数字必然不同，才能证明切换真的生效。
     */
    @Test
    fun render21LedgerWeekScope() {
        val app = compose.activity.application as MenghuanApp
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val weekStart = today.with(java.time.DayOfWeek.MONDAY)
        fun ts(daysAgo: Long, hour: Int): Long =
            today.minusDays(daysAgo).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        // 本月内、但落在本周之外的一天
        val outsideWeek = run {
            val candidate = weekStart.plusDays(7) // 下周一
            if (candidate.month == today.month && candidate.year == today.year) candidate
            else today.withDayOfMonth(1) // 本周末已到月末：退回本月 1 号
        }
        kotlinx.coroutines.runBlocking {
            app.db.ledgerDao().upsert(
                com.menghuan.app.data.model.LedgerEntry(
                    isIncome = true, amountCents = 800_00, note = "本月非本周收入",
                    source = com.menghuan.app.data.model.LedgerSource.MANUAL,
                    occurredAt = outsideWeek.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
                )
            )
            app.db.ledgerDao().upsert(
                com.menghuan.app.data.model.LedgerEntry(
                    isIncome = false, amountCents = 100_00, note = "本周支出",
                    source = com.menghuan.app.data.model.LedgerSource.MANUAL, occurredAt = ts(0, 12),
                )
            )
        }
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_1").performClick()
        awaitText("记账本")
        scrollToTagAndClick("ledger_entry_row")
        // 月档：含「本月非本周」那笔收入 800
        awaitText("月度统计")
        awaitText("+800.00")
        // 切到周档
        compose.onNodeWithTag("ledger_scope_toggle").performClick()
        awaitText("周度统计")
        awaitText("本周流水 · 1 笔") // 周档只含本周那一笔（非本周那笔被排除）
        snap("42-ledger-week")
        // 再点一次切回月档
        compose.onNodeWithTag("ledger_scope_toggle").performClick()
        awaitText("月度统计")
        awaitText("+800.00") // 月档重新把非本周那笔算进来
    }
}
