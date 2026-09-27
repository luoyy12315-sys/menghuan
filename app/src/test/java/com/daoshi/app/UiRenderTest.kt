package com.daoshi.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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

    private fun clickText(text: String) = compose.onNodeWithText(text).performClick()
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
}
