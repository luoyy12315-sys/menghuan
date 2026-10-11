package com.menghuan.app

import androidx.datastore.preferences.core.edit
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // 关掉冷启动开屏再起 Activity：截图要的是页面本身，糊一层开屏就没意义了
        // （必须在碰 compose.activity 之前关——访问它就等于把 Activity 起来了）
        val app = ApplicationProvider.getApplicationContext<MenghuanApp>()
        app.coldStartSplashEnabled = false
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

            // v0.77.0：预置两条「心情计」读数，让消息列表截图同时覆盖三种状态 ——
            // 阿布 = 心情好（进度条偏满）、严格教练 = 心情差（进度条见底）、苏格拉底不写 = 不占位
            // （没聊过的角色没有心情数据，画一条空条反而像"她在生气"）。
            val store = app.settingsRepository
            store.setCompanionStateRaw(
                1L,
                com.menghuan.app.data.CompanionLogic.encode(
                    com.menghuan.app.data.CompanionState(valence = 0.7f, arousal = 0.3f),
                ),
            )
            store.setCompanionStateRaw(
                2L,
                com.menghuan.app.data.CompanionLogic.encode(
                    com.menghuan.app.data.CompanionState(valence = -0.7f, arousal = 0.1f),
                ),
            )
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

    /**
     * 等一个无障碍描述上屏。
     *
     * v0.79.0：改成与 [awaitText] 同一套**轮询 + 重试**。以前裸 `waitUntil` 在 Robolectric 下
     * 会漏掉滞留的 invalidation 直接超时；而且滚动金额这类节点在动画收敛前根本不进语义树。
     */
    private fun awaitDesc(desc: String) {
        val deadline = System.currentTimeMillis() + 45_000
        var lastError: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                if (compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()) {
                    return
                }
                compose.waitForIdle()
            } catch (e: AssertionError) {
                lastError = e // 动画未收敛时 waitForIdle 可能超时：继续轮询
            }
            Thread.sleep(120)
        }
        throw AssertionError("等待描述超时（45s）：$desc", lastError)
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
     * 点设置二级页顶栏的「返回」。
     * 推进/回退转场期间**新旧两个 destination 会同时在组合树里**，
     * 此时 `onNodeWithContentDescription("返回")` 会匹配到多个节点直接抛异常
     * （设置主页与二级页各有一颗）。这里等到只剩一颗再点。
     */
    private fun clickBack() {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            try {
                compose.waitForIdle()
            } catch (e: AssertionError) {
                // 动画未收敛：继续轮询
            }
            val nodes = compose.onAllNodesWithContentDescription("返回").fetchSemanticsNodes()
            if (nodes.size == 1) {
                compose.onNodeWithContentDescription("返回").performClick()
                runCatching { compose.waitForIdle() } // 等回退转场走完再交给下一步
                Thread.sleep(300)
                return
            }
            Thread.sleep(80)
        }
        throw AssertionError("等待唯一「返回」按钮超时（15s）")
    }

    /**
     * 滚到指定 tag 的节点再点击。
     * 数据页底部入口（记账本 / AI 洞察）在首屏之外，直接 performClick 会被
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

    /** 同上，但按文案找（会话设置页最底部那几行没有 tag）。 */
    private fun scrollToTextAndClick(text: String) {
        repeat(3) { attempt ->
            try {
                compose.onNodeWithText(text).performScrollTo().performClick()
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
        awaitText("AI 洞察") // 入口卡（v0.87.0 起只留洞察）
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
        // v0.88.0：空态文案补了来路说明（"今天还没有建议（依据昨晚的洞察）"），
        // 所以点击要用子串匹配，不能再按整串精确匹配。
        awaitText("今天还没有建议")
        // 点空态卡片 → 弹层
        compose.onNodeWithText("今天还没有建议", substring = true).performClick()
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
        // v0.73.0：任务从底栏第 3 格搬进了**数据页那张入口卡**（第 3 格换成了「动态」），
        // 所以这里不再是点 tab，而是「数据 → 任务卡 → 全屏任务页」这条真实路径。
        compose.onNodeWithTag("bottom_tab_1").performClick()
        awaitText("记账本")
        scrollToTagAndClick("tasks_entry_row")
        awaitText("暂无任务") // 空库空态
        snap("3-tasks")
    }

    /**
     * 动态页（v0.73.0，底栏第 3 格）：空态 → 发一条 → 时间线上出现。
     *
     * 这条同时钉住两件事：**发帖入口在右下那颗圆钮**（不是顶栏），
     * 以及帖子落库后时间线会自己刷出来（Room Flow，不靠手动 reload）。
     */
    @Test
    fun render41Moments() {
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_2").performClick() // 底栏「动态」
        awaitText("发第一条动态") // 空态就是这一个动作，没有客套话
        snap("60-moments-empty")
        compose.onNodeWithTag("moment_compose_fab").performClick()
        compose.onNodeWithText("此刻在想什么？").performTextInput("今晚的风很大")
        clickText("发布")
        awaitText("今晚的风很大")
        snap("61-moments-one")
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
     * 而是跳去「语音识别模型」全屏页——让用户按住说完才发现转写为空，比不发声更糟。
     */
    @Test
    fun render25VoiceModeBlockedByMissingModel() {
        awaitText("阿布")
        clickText("阿布") // 进单聊

        awaitDesc("切换到语音")
        compose.onNodeWithContentDescription("切换到语音").performClick()
        awaitText("语音识别模型") // 已跳到全屏下载页
        awaitText("未就绪")
        awaitText("model.int8.onnx")
        awaitText("下载模型")
        snap("48-asr-model-sheet")
        // 语音态没进去：此刻人在下载页，"按住 说话"那条不该存在
        compose.onNodeWithText("按住 说话").assertDoesNotExist()
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

    /**
     * v0.54.2 设置主页「方案二」：主页只有**九张自题名入口卡**（无分组小标题、无编号），
     * 多条目卡点进去才是那几张子卡 —— 模型配置（5 项，含联网搜索）/ 通话行为（2 项，
     * v0.56.5 起「接听延迟」从铃声页拎出、与铃声同级）/ 提醒与通知（2 项）；
     * 「语音」「通知与提醒」两个中转页已取消，外观恢复成独立页。
     */
    @Test
    fun render27SettingsGroups() {
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_3").performClick() // 我的
        awaitText("设置")
        clickText("设置")
        // 顶部是**搜索行**（点一下才进入搜索态，v0.54.4 起与消息页同款；不再是常驻输入框）
        awaitText("搜索")
        // 主页九张入口卡
        awaitText("模型配置")
        awaitText("提示词库")
        awaitText("音色")
        awaitText("通话行为")
        awaitText("提醒与通知")
        awaitText("外观")
        awaitText("权限")
        awaitText("数据与隐私")
        awaitText("关于")
        // 子卡内容不该直接出现在主页（v0.54.1 的平铺形态已推翻）
        assertFalse(compose.onAllNodesWithText("对话模型").fetchSemanticsNodes().isNotEmpty())
        assertFalse(compose.onAllNodesWithText("语音通话铃声").fetchSemanticsNodes().isNotEmpty())
        snap("51-settings-home")

        // 「模型配置」是一张卡 → 点进去才是 5 张子卡（第 5 张 = 联网搜索）
        clickText("模型配置")
        awaitText("对话模型")
        awaitText("生图模型")
        awaitText("识别模型")
        awaitText("语音模型")
        awaitText("联网搜索")
        snap("51b-settings-model-config")
        clickBack()

        // 「通话行为」点进去是 2 张子卡：语音通话铃声 + 接听延迟（v0.56.5 起两项同级）
        clickText("通话行为")
        awaitText("语音通话铃声")
        awaitText("接听延迟")
        assertFalse(compose.onAllNodesWithText("AI 主动打电话").fetchSemanticsNodes().isNotEmpty())
        snap("51d-settings-call-behavior")
        clickBack()

        // 「提醒与通知」点进去只剩 2 个开关（v0.54.4：「定时主动消息」「人设主动关心」已搬到会话设置页）
        clickText("提醒与通知")
        awaitText("任务到点提醒")
        awaitText("手机操作进行中通知")
        snap("51c-settings-reminders")
        clickBack()
        assertFalse(compose.onAllNodesWithText("人设主动关心").fetchSemanticsNodes().isNotEmpty())

        // 「语音」中转页已取消：不该再有一个正好叫「语音」的入口行
        assertFalse(compose.onAllNodesWithText("语音").fetchSemanticsNodes().isNotEmpty())
        // 点「音色」卡片 → 独立全屏音色页：名单 + 顶栏两个按钮
        clickText("音色")
        awaitText("Alex · 男声")
        awaitText("Anna · 女声")
        awaitText("克隆音色")
        awaitText("ID")
        assertFalse(compose.onAllNodesWithText("Bella · 激情女声").fetchSemanticsNodes().isNotEmpty())
        snap("55-voice-library")
    }

    /**
     * 语音合成页（v0.53.0 起的形状，2026-10-09 改写）。
     *
     * ⚠️ 本用例原来断言"三家平铺在页面上"（硅基流动 / 小米 MiMo / 自定义端点 + 当前生效那家
     * 默认展开），那是**旧版**布局——用户点名"**不要**把三段摊在页面上"，改成
     * **只列已配好的卡片 + 一个「＋ 添加接入」**（点开才选家）。旧断言从此一直红。
     * 现在守的是新形状：只硅基配齐 → 页面上只有它一张卡（标题=模型名、副标题=去协议的地址），
     * 另两家只在「＋ 添加接入」的弹层里出现。
     */
    @Test
    fun render28TtsConfigScreen() {
        val app = compose.activity.application as MenghuanApp
        kotlinx.coroutines.runBlocking {
            app.settingsRepository.saveTtsConfig(
                com.menghuan.app.data.TtsConfig(
                    provider = com.menghuan.app.data.TtsProvider.SILICONFLOW,
                    baseUrl = "http://127.0.0.1:9/v1", // 不可达：「语音」页的音色板块会试着拉克隆列表，别让它打真服务
                    apiKey = "sk-sf",
                    model = "FunAudioLLM/CosyVoice2-0.5B",
                )
            )
        }
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_3").performClick() // 我的
        awaitText("设置")
        clickText("设置")
        // v0.54.2：「语音」中转页早已取消，语音模型在设置主页「模型配置」卡的第 4 张子卡
        clickText("模型配置")
        awaitText("语音模型")
        clickText("语音模型")
        // 只有硅基配齐了 → 页面上只有它这一张卡（模型名 + 去协议的地址）
        awaitText("FunAudioLLM/CosyVoice2-0.5B")
        awaitText("127.0.0.1:9/v1")
        assertFalse(
            "没配的家不该摊在页面上",
            compose.onAllNodesWithText("小米 MiMo").fetchSemanticsNodes().isNotEmpty(),
        )
        assertFalse(
            "没配的家不该摊在页面上",
            compose.onAllNodesWithText("自定义端点").fetchSemanticsNodes().isNotEmpty(),
        )
        // 克隆与音色管理已经搬去独立的「音色」页，这一页只管配置
        assertFalse(compose.onAllNodesWithText("音色克隆").fetchSemanticsNodes().isNotEmpty())
        snap("52-tts-config")

        // 「＋ 添加接入」弹层才是选家：三家芯片 + 已接入清单
        clickText("＋ 添加接入")
        awaitText("添加到哪一家")
        awaitText("硅基流动")
        awaitText("小米 MiMo")
        awaitText("自定义端点")
        awaitText("已接入的：硅基流动")
        snap("53-tts-config-add")
    }

    /** v0.53.4 API 配置：按模型列表展示，点一行直接进详情（列表里没有勾选、没有输入框）；v0.54.1 独立成「对话模型」页 */
    @Test
    fun render29ApiConfigModels() {
        val app = compose.activity.application as MenghuanApp
        kotlinx.coroutines.runBlocking {
            app.settingsRepository.saveApiConfig(
                com.menghuan.app.data.ApiConfig("https://api.deepseek.com/v1", "sk-ds", "deepseek-chat")
            )
        }
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_3").performClick() // 我的
        awaitText("设置")
        clickText("设置")
        // v0.54.2：对话 / 生图是「模型配置」卡里的两张子卡，这条只验对话模型页
        clickText("模型配置")
        awaitText("对话模型")
        clickText("对话模型")
        awaitText("＋ 添加模型")
        // 列表页只有卡片，没有输入框
        assertFalse(compose.onAllNodesWithText("接口地址").fetchSemanticsNodes().isNotEmpty())
        // 点模型卡片 = 切换生效（保留），不是进详情
        clickText("默认接口")
        awaitText("已切换到")
        // 点右侧箭头才进详情
        compose.onNodeWithContentDescription("详情").performClick()
        awaitText("编辑模型")
        awaitText("接口地址")
        snap("54-api-config-model-detail")
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
        // v0.73.0：任务不在底栏第 3 格了，走「数据 → 任务卡」这条真实路径进去
        compose.onNodeWithTag("bottom_tab_1").performClick()
        scrollToTagAndClick("tasks_entry_row")
        awaitText("已逾期")
        snap("5-tasks-filled")
        // 任务页是全屏页，后面还要用底栏 → 先按 ← 退回数据页
        compose.onNodeWithContentDescription("返回").performClick()
        awaitText("记账本")
        // 单聊页（长内容）：气泡宽度折行 + ❤️ 大表情 + 未读点光晕
        compose.onNodeWithTag("bottom_tab_0").performClick() // 底栏 tab
        awaitText("❤️") // 列表预览已更新为最后一条消息
        clickText("阿布")
        awaitText("第一步") // Markdown 长消息上屏（粗体行）
        snap("6-chat-long")
    }

    /**
     * 聊天记录页搜索：输入关键字 → 命中消息上屏。
     *
     * ⚠️ v0.79.0 改写：本用例原来点的是**消息页那行「搜索」**，而消息页搜索整条链路
     * 在 v0.56.8 就按用户点名删掉了（用户："消息页不要搜索，顶栏放大镜也不要"），
     * 此后一直是一条红——守的是个不存在的入口。
     * 搜索现在住在「会话设置 → 查看聊天记录」（v0.53.6），守的是同一件事：
     * 命中消息能上屏。
     */
    @Test
    fun render6ChatHistorySearch() {
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

        clickText("阿布") // 进这个会话
        clickDesc("会话设置") // 右上角三点 → 整页会话设置
        awaitText("会话设置")
        scrollToTextAndClick("查看聊天记录") // 这一行在列表最底部
        awaitText("与 阿布 的聊天记录")
        // 搜索框是那一页的 BasicTextField，可直接输入
        compose.onNode(androidx.compose.ui.test.hasSetTextAction())
            .performTextInput("睡前")
        awaitText("睡前刷手机") // 命中结果上屏
        snap("7-chat-history-search")
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
        // v0.42.0：洞察详情移入全屏页，数据页只显示入口卡上的数量摘要。
        // v0.88.0：摘要改成按周期分「今日 x · 本周 y · 本月 z」（默认 period = DAILY，所以是今日）。
        awaitText("今日 2")
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
        val app = compose.activity.application as MenghuanApp
        awaitText("阿布")
        // v0.80.0：预置一份自己的档案 —— 顶部档案卡要能看到"有内容"的形状，
        // 而不是只看"没设置过"的兜底态（兜底态另有断言覆盖：昵称显示「我」）
        kotlinx.coroutines.runBlocking {
            app.settingsRepository.setMyProfile(
                com.menghuan.app.data.MyProfile(nickname = "小舟", bio = "晚睡患者，正在改"),
            )
            app.settingsRepository.setUserAvatar(com.menghuan.app.data.UserAvatar(emoji = "\uD83C\uDF19"))
        }
        compose.onNodeWithTag("bottom_tab_3").performClick()
        // v0.80.0：「基础档案」那张行卡已取消、内容并入顶部档案卡，
        // 所以这里断言的是档案卡的昵称与下方三张行卡（人设管理 / AI 图库 / 设置）。
        awaitText("小舟")
        awaitText("人设管理")
        awaitText("AI 图库")
        snap("12-me")
        // 点档案卡 → 个人档案页（头像 / 昵称 / 简介 + 基础档案条目都在这儿改）
        clickText("小舟")
        awaitText("个人档案")
        // v0.80.0：头像选择换成编辑人设那一套（emoji 滚轮 + 细线胶囊），
        // 「更换头像」按钮与选头像弹层都撤了；昵称/简介也换成同款输入框（值是"小舟"/那句简介）。
        awaitText("点击编辑") // 头像下面那颗细线胶囊（没选图时写这个）
        awaitText("昵称")
        awaitText("简介")
        awaitText("晚睡患者，正在改")
        awaitText("基础档案 · 注入 AI 的用户画像")
        // emoji 滚轮确实铺上了：预设立的表情出现在语义树里
        // （框里是预置的月亮 🌙，不在预设名单里 → 滚轮落在第一个预设上）
        assertTrue(
            "emoji 滚轮没渲染出来",
            compose.onAllNodesWithText("🙂").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("😊").fetchSemanticsNodes().isNotEmpty(),
        )
        snap("70-me-profile")
    }

    /**
     * v0.81.0 守门：**没设过任何头像**的人点进「个人档案」页，逛一圈出来，
     * 头像必须还是"没设过"（框里是黑底白「我」）—— 滚轮首次定位那一下不算用户选择。
     *
     * 这条守的是 `AvatarEmojiWheel.commitInitialAnchor = false`：档案页是**改完即时落盘**的，
     * 要是把首帧锚点当成选择写下去，用户一进页面「我」就被换成某个表情了（bug 观感）。
     */
    @Test
    fun render12ProfileKeepsEmptyAvatar() {
        val app = compose.activity.application as MenghuanApp
        // 只设昵称、**不设头像** —— 要验的就是"头像从没设过"这一态。
        // ⚠️ 头像要**显式清空**：settings 的 DataStore 是**进程级单例**，别的用例设过的
        // 头像会漏到这里来（第一版就是被 render11Me 的 🌙 漏进来，看着像"页面自己改了头像"）。
        kotlinx.coroutines.runBlocking {
            app.settingsRepository.setMyProfile(com.menghuan.app.data.MyProfile(nickname = "小舟"))
            app.settingsRepository.setUserAvatar(com.menghuan.app.data.UserAvatar())
        }
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_3").performClick()
        awaitText("小舟")
        clickText("小舟") // 顶部档案卡
        awaitText("个人档案")
        awaitText("点击编辑") // 头像下面那颗细线胶囊在（滚轮也铺上了）
        val after = kotlinx.coroutines.runBlocking { app.settingsRepository.userAvatar.first() }
        assertTrue(
            "没选过头像的人，进页面后仍该是「没设过」（实得 emoji='" + after.emoji + "' image='" + after.imagePath + "'）",
            after.emoji.isBlank() && after.imagePath.isBlank(),
        )
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
     * 外观（v0.35.1 起在设置里；v0.54.0 曾并入设置主页，**v0.54.1 起恢复成独立全屏页**）：
     * 设置主页的「外观」卡点进去，主题三选一的胶囊在这一页里。
     */
    @Test
    fun render15AppearanceScreen() {
        val app = compose.activity.application as MenghuanApp
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_3").performClick() // 我的
        awaitText("设置")
        clickText("设置")
        awaitText("外观")
        snap("27-settings-list")
        // 「外观」是设置主页的一张入口卡 → 点进去是独立全屏页（主题胶囊在这页里）
        clickText("外观")
        awaitText("跟随系统")
        awaitText("浅色")
        awaitText("深色")
        // v0.80.0：外观页现在有"聊天气泡"整段（明暗切换 + 实时预览 + 自己/对方两套 + 字体三件套）
        awaitText("聊天气泡")
        awaitText("深色主题")
        awaitText("自己发的")
        snap("28-appearance")
        // 切到浅色确认生效，然后还原（settingsStore 是进程级单例）
        clickText("浅色")
        awaitState("点了「浅色」后 themeMode 应为 LIGHT") {
            kotlinx.coroutines.runBlocking { app.settingsRepository.themeMode.first() } == "LIGHT"
        }
        kotlinx.coroutines.runBlocking { app.settingsRepository.setThemeMode("SYSTEM") }
        compose.waitForIdle()
    }

    /**
     * AI 洞察入口卡与全屏页（v0.42.0 建入口卡 / v0.87.0 删掉周报、只留洞察）：
     * 数据页入口卡显示洞察条数，点进全屏页后列出洞察全文。
     */
    @Test
    fun render16InsightCardAndDetail() {
        val app = compose.activity.application as MenghuanApp
        kotlinx.coroutines.runBlocking {
            app.db.aiInsightDao().insert(
                com.menghuan.app.data.model.AiInsight(
                    title = "晚间效率更高",
                    detail = "近两周有 9 天在 21 点后完成主要任务，白天推进较慢。",
                )
            )
        }
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_1").performClick()
        awaitText("今日 1") // 入口卡上的数量摘要（v0.88.0 起按周期给，默认 DAILY）
        scrollToTagAndClick("ai_entry_card")
        awaitText("AI 洞察 · 1 条") // 只存在于全屏详情页的区块标题
        awaitText("晚间效率更高") // 洞察标题
        snap("29-insight-detail")
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
                    app.db.personaDao().count() > 0 &&
                    app.settingsRepository.apiConfig.first().isComplete
            }
        }
        snap("33-after-clear")
        // settingsStore 是进程级单例：清掉接口配置，避免泄漏到同类后续测试
        kotlinx.coroutines.runBlocking { app.settingsStore.edit { it.clear() } }
    }

    /** 单条清理（v0.36.5）：洞察卡长按 → 弹「删除」菜单 → 点菜单项删除。 */
    @Test
    fun render18LongPressDeleteInsight() {
        val app = compose.activity.application as MenghuanApp
        kotlinx.coroutines.runBlocking {
            app.db.aiInsightDao().insert(
                com.menghuan.app.data.model.AiInsight(title = "入睡时间偏晚", detail = "近 7 天有 5 天超过 1 点"),
            )
        }
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_1").performClick() // 数据
        awaitText("今日 1") // 入口卡的数量摘要（v0.88.0 起按周期给，默认 DAILY）
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
    }

    /** 洞察空态：API 已配置但还没有产出 → 入口卡照常显示（apiReady 即显示），不挂说明性小字。 */
    @Test
    fun render12InsightPlaceholder() {
        val app = compose.activity.application as MenghuanApp
        kotlinx.coroutines.runBlocking {
            app.settingsRepository.saveApiConfig(
                com.menghuan.app.data.ApiConfig("https://api.test.com/v1", "sk-test", "test-model"),
            )
        }
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_1").performClick()
        awaitText("AI 洞察") // 入口卡上屏（apiReady 即显示）
        // 去说明性小字：洞察为空时不再挂「还没有产出，聊聊就有」副标题，卡片只剩标题 + 右箭头
        compose.onNodeWithText("还没有产出，聊聊就有").assertDoesNotExist()
        snap("13-data-insight-empty")
        // settingsStore 是进程级单例：清掉 API 配置，避免泄漏到同类后续测试
        kotlinx.coroutines.runBlocking { app.settingsStore.edit { it.clear() } }
    }

    /**
     * 人设编辑器（v0.53.4 起为**全屏页**）：点头像框可换图但不写提示文字、人物 emoji 与头像同一排、
     * 音色只留男/女/克隆、设定词无「插入模板」；写作规范仍是弹层。
     */
    @Test
    fun render13PersonaEditorGuide() {
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_3").performClick() // 我的
        awaitText("人设管理")
        clickText("人设管理") // 进入人设管理页
        awaitDesc("新增人设") // 顶栏 ＋（新建入口）待上屏
        clickDesc("新增人设")
        awaitText("查看写作规范") // 编辑页上屏
        awaitText("设定词")
        awaitText("身份")
        // v0.53.4：不再有「点头像换图」提示，也没有「插入模板」按钮
        assertFalse(compose.onAllNodesWithText("点头像换图").fetchSemanticsNodes().isNotEmpty())
        assertFalse(compose.onAllNodesWithText("插入模板").fetchSemanticsNodes().isNotEmpty())
        assertFalse(compose.onAllNodesWithText("system prompt").fetchSemanticsNodes().isNotEmpty())
        snap("14-persona-editor")
        clickText("查看写作规范")
        awaitText("设定词写作规范")
        // v0.79.0：面板改成"一份可以复制给别的 AI 的规范" —— 主体是规范原文 + 复制按钮；
        // 「示例：健身教练 / 学习教练」那两段按用户点名删掉了（"不要模板，也不要例子"）。
        awaitText("复制规范")
        awaitText("角色真实拥有的能力")
        assertFalse(
            "旧示例不该还在",
            compose.onAllNodesWithText("示例：健身教练").fetchSemanticsNodes().isNotEmpty(),
        )
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
        // v0.79.0：余额是滚动金额（每一位一个 Text）。整串要按**无障碍描述**找 ——
        // `RollingAmountText` 现在把完整金额合成一个节点（读屏也不再一位一位念）。
        // 800 - 35.8 - 128 = 636.2
        awaitDesc("636.20")
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
