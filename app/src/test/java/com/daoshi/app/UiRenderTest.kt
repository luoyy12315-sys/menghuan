package com.daoshi.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM 截图测试（无真机/模拟器环境下"亲眼"检查 UI）：
 * 直接启动真实的 MainActivity（MentorApp + Room 种子 + NavHost + 底栏），
 * 用真实用户路径点击导航到各页面，Roborazzi 以真机同款 Skia 渲染管线输出 PNG。
 * 产物：app/build/screenshots 目录下的 PNG。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = MentorApp::class, sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class UiRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** 截当前窗口画面存盘 */
    private fun snap(name: String) {
        compose.waitForIdle()
        Thread.sleep(600) // 等页面转场/进场动画走完
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    /** 等待包含指定文本的节点出现（异步数据已上屏） */
    private fun awaitText(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitDesc(desc: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription(desc).fetchSemanticsNodes().isNotEmpty()
        }
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

    @Test
    fun render1ChatList() {
        awaitText("阿布") // 会话行（种子人设）已上屏
        snap("1-chatlist")
    }

    @Test
    fun render2Data() {
        awaitText("阿布")
        clickText("数据") // 底栏 tab（chatlist 顶栏是「消息」，此处唯一）
        awaitText("自由职业") // 档案种子数据已上屏
        snap("2-data")
    }

    @Test
    fun render3Tasks() {
        awaitText("阿布")
        clickText("任务") // 底栏 tab（chatlist 顶栏是「消息」，此处唯一）
        awaitText("暂无任务") // 空库空态
        snap("3-tasks")
    }

    @Test
    fun render4Chat() {
        awaitText("阿布")
        clickText("阿布") // 会话行 → push 单聊
        awaitText("你先别骂我") // 种子消息上屏
        snap("4-chat")
    }

    /** 长内容压测：超长消息 / ❤️ 大表情 / 任务页有数据态（逾期·今天·明天·已完成标签） */
    @Test
    fun render5FilledAndLong() {
        val app = compose.activity.application as MentorApp
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        kotlinx.coroutines.runBlocking {
            app.db.messageDao().insert(
                com.daoshi.app.data.model.ChatMessage(
                    conversationId = 1, isFromUser = true,
                    content = "最近两周都在熬夜，平均一点半才睡，白天效率特别低，下午基本废掉。我也试过早点上床，但躺下就忍不住刷手机，越刷越精神。帮我拆一个能落地的方案吧，要具体到每一步的那种，不要再说早点睡了这种正确的废话。",
                )
            )
            app.db.messageDao().insert(
                com.daoshi.app.data.model.ChatMessage(
                    conversationId = 1, isFromUser = false,
                    content = "好，不谈道理，只谈方案。第一步：今晚 22:30 把手机放到客厅充电，卧室不带——这是唯一重要的一步，其他都是它的附件。第二步：卧室换一本纸质书，翻 10 分钟，允许自己只看垃圾小说。第三步：前三天允许失败一次，但第二次失败就要复盘是哪一步断了。先把今晚的 22:30 定成闹钟，我现在就把这条发给你当凭证：22:30，手机出卧室。做到了明天告诉我，做不到也告诉我——我要的是数据，不是借口。",
                )
            )
            app.db.messageDao().insert(
                com.daoshi.app.data.model.ChatMessage(conversationId = 1, isFromUser = false, content = "❤️")
            )
            // 严格教练的新回复：会话列表未读点 + 光晕的渲染证据
            app.db.messageDao().insert(
                com.daoshi.app.data.model.ChatMessage(conversationId = 2, isFromUser = false, content = "今晚 23:00 前睡觉，做到没有？")
            )
            app.db.taskDao().upsert(
                com.daoshi.app.data.model.TaskItem(title = "整理作品集并更新主页", dueAt = today - 3L * 86_400_000, createdBy = "user")
            )
            app.db.taskDao().upsert(
                com.daoshi.app.data.model.TaskItem(title = "23:00 前放下手机", note = "打卡后发消息给阿布", dueAt = today, createdBy = "ai")
            )
            app.db.taskDao().upsert(
                com.daoshi.app.data.model.TaskItem(title = "画 1 小时商稿草图", dueAt = today, isDone = true, createdBy = "ai")
            )
            app.db.taskDao().upsert(
                com.daoshi.app.data.model.TaskItem(title = "08:00 起床 + 晨跑 20 分钟", dueAt = today + 86_400_000, createdBy = "ai")
            )
        }
        awaitText("阿布")
        snap("0-chatlist-unread") // 未读点光晕 + 打卡回执消息的列表预览
        // 任务页（有数据）：分组标题 + 标签 + 完成划线
        clickText("任务")
        awaitText("已逾期")
        snap("5-tasks-filled")
        // 单聊页（长内容）：气泡宽度折行 + ❤️ 大表情 + 未读点光晕
        clickText("消息") // 底栏 tab（此页顶栏是「任务」，不冲突）
        awaitText("❤️") // 列表预览已更新为最后一条消息
        clickText("阿布")
        awaitText("22:30，手机出卧室")
        snap("6-chat-long")
    }

    /** 搜索态：输入关键字 → 人设/消息命中结果上屏 */
    @Test
    fun render6Search() {
        // 搜索走真实 DB（种子演示消息不入库），先预置一条可命中的消息
        val app = compose.activity.application as MentorApp
        kotlinx.coroutines.runBlocking {
            app.db.messageDao().insert(
                com.daoshi.app.data.model.ChatMessage(
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
        // 调试用：等待"命中结果"或"空结果提示"任一上屏，超时都能区分两种路径
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("睡前刷手机", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("没有匹配结果", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        snap("7-search")
        awaitText("睡前刷手机") // 最终仍要求命中结果上屏
    }
}
