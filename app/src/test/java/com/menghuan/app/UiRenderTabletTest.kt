package com.menghuan.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 平板宽屏（>=600dp）截图：双栏消息（左列表 + 右单聊占位）、数据页 / 我的页宽版心两列网格。
 * 与 UiRenderTest 同一套真实导航路径，仅窗口 qualifiers 不同。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = MenghuanApp::class, sdk = [34], qualifiers = "w900dp-h1280dp-420dpi")
class UiRenderTabletTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** v0.31.0 起无预置导师种子：测试自建夹具（同 UiRenderTest.seedFixtures） */
    @Before
    fun seedFixtures() {
        // 关掉冷启动开屏（同 UiRenderTest）：必须在碰 compose.activity 之前关
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
            app.db.profileFactDao().upsert(com.menghuan.app.data.model.ProfileFact(key = "职业", value = "自由职业"))
        }
    }

    private fun snap(name: String) {
        compose.waitForIdle()
        Thread.sleep(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private fun awaitText(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** 消息页（双栏）：左列表 360dp + 发丝线 + 右「未选择会话」占位 */
    @Test
    fun tablet1ChatTwoPane() {
        awaitText("阿布")
        awaitText("未选择会话")
        snap("20-tablet-chat-twopane")
    }

    /** 数据页（宽版心 840dp）：本周回顾条置顶 + 今日建议 + 记账本入口 + AI 洞察入口（v0.42.0 / v0.87.0） */
    @Test
    fun tablet2DataWide() {
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_1").performClick()
        awaitText("本周回顾")
        awaitText("今日建议")
        awaitText("AI 洞察")
        snap("21-tablet-data")
    }

    /** 我的页（宽版心）：行卡两列网格 */
    @Test
    fun tablet3MeWide() {
        awaitText("阿布")
        compose.onNodeWithTag("bottom_tab_3").performClick()
        // v0.80.0：顶部的「基础档案」行卡已取消（画像条目并入点开的「个人档案」页），
        // 这一页现在只剩顶部档案卡 + 人设管理 / AI 图库 / 设置 三张行卡。
        // 旧断言等的就是那张已取消的卡 —— 它从改版那天起就红着，本批顺手改掉。
        awaitText("人设管理")
        awaitText("AI 图库")
        snap("22-tablet-me")
    }

    /** 双栏点选：点左栏会话行 → 右栏切到该会话（不 push） */
    @Test
    fun tablet4ChatSwitch() {
        awaitText("阿布")
        compose.onAllNodesWithText("阿布")[0].performClick()
        awaitText("暂无消息") // 右栏会话已切换（种子会话无持久化消息时的空态）
        snap("23-tablet-chat-selected")
    }
}
