package com.menghuan.app

import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.ASSISTANT_PERSONA_NAME
import com.menghuan.app.data.AiPrompts
import com.menghuan.app.data.settingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 内置「应用助手」手册的自动升版回归测试（v0.51.1）。
 *
 * 起因是真机反馈"应用助手不知道语音怎么配"：旧实现靠"库里这段是否**精确等于**某个历史默认文案"
 * 判升级，而历史文案表只登到 v0.38——每版都是在 DEFAULT_ASSISTANT_PROMPT 上原地追加 `.replace()`，
 * 中间态从未登记，于是 v0.41~v0.50 升级过的用户永远匹配不上，手册卡在旧版（v0.50.0 的整章语音
 * 教程根本没写进他们的数据库行）。本测试把"中间态也能升版"钉死，并钉住"用户自己写的不动"。
 *
 * 用 bypass dao.upsert 模拟历史行：这些行是**上个版本的 App 写的**，不该被本版本的
 * save() 归属标记逻辑影响（老装机走"手册形状"一次性判定）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MenghuanApp::class, sdk = [34])
class AssistantManualUpgradeTest {

    private lateinit var app: MenghuanApp

    /** 当前版手册（编译期常量，即"这次发版后用户应该看到的内容"） */
    private val current = AiPrompts.DEFAULT_ASSISTANT_PROMPT

    /** v0.38 全文：旧锚点表里登过的历史版 */
    private val stockV038 = AiPrompts.DEFAULT_ASSISTANT_PROMPT_V038

    /**
     * v0.41~v0.49 之间的"中间态"：只在 v0.38 全文上应用了**一条**替换。
     * 它既 ≠ v0.38 也不在旧锚点表里——旧机制正是在这种行上失效（本次修复的核心用例）。
     */
    private val intermediateStock = stockV038.replace(
        "「梦欢」是一款单机版 AI 陪伴应用：",
        "「梦欢」是一款 AI 陪伴应用：",
    )

    /** 「导师」时代（v0.31）的开场白，那版手册没有 ═══ 分节 */
    private val stockV031 =
        "你是「导师」App 内置的应用助手，帮助用户了解和使用本应用。\n" +
            "- 这是一款单机版 AI 导师应用：数据只保存在本机，不上传云端。"

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        // settingsStore 委托是进程级单例：不清掉会残留上一条测试的"用户改过"标记（Robolectric 坑之七）
        app.settingsStore.edit { it.clear() }
        app.personaRepository.ensureAssistant()
    }

    /** 绕开仓库，直接写库——模拟历史版本 App 留下来的那行数据 */
    private suspend fun writeAssistantPrompt(text: String) {
        val dao = app.db.personaDao()
        val row = dao.getByName(ASSISTANT_PERSONA_NAME)!!
        dao.upsert(row.copy(systemPrompt = text))
    }

    private suspend fun assistantPrompt(): String {
        val dao = app.db.personaDao()
        return dao.getByName(ASSISTANT_PERSONA_NAME)!!.systemPrompt
    }

    /** 走仓库保存 = 模拟用户在「AI 提示词」页/人设编辑里点保存（带归属标记，且不能多插一行） */
    private suspend fun saveAssistantPrompt(text: String) {
        val row = app.db.personaDao().getByName(ASSISTANT_PERSONA_NAME)!!
        app.personaRepository.save(row.copy(systemPrompt = text))
    }

    @Test
    fun registeredLegacyAnchorUpgrades() = runBlocking {
        writeAssistantPrompt(stockV038)
        app.personaRepository.refreshAssistantPromptIfNeeded()
        assertEquals(current, assistantPrompt())
    }

    @Test
    fun unregisteredIntermediateStockStillUpgrades() = runBlocking {
        // 前置：中间态确实既不是当前版、也不是任何登过册的历史版（否则本用例测不到修复点）
        assertNotEquals(current, intermediateStock)
        assertNotEquals(stockV038, intermediateStock)
        writeAssistantPrompt(intermediateStock)

        app.personaRepository.refreshAssistantPromptIfNeeded()

        assertEquals(current, assistantPrompt())
    }

    @Test
    fun ancientTutorEraManualUpgrades() = runBlocking {
        writeAssistantPrompt(stockV031)
        app.personaRepository.refreshAssistantPromptIfNeeded()
        assertEquals(current, assistantPrompt())
    }

    @Test
    fun handWrittenPersonaIsNeverOverwritten() = runBlocking {
        val mine = "你是我自己写的助手，只负责每天提醒我喝水，别的一概不管。"
        writeAssistantPrompt(mine)

        app.personaRepository.refreshAssistantPromptIfNeeded()

        assertEquals(mine, assistantPrompt())
    }

    /** 用户在提示词页改过 → 手册归用户，后续版本不得覆盖（哪怕内容还是手册形状） */
    @Test
    fun userEditedManualIsNotOverwritten() = runBlocking {
        val edited = current + "\n\n（我加的：回答末尾提醒我早睡。）"
        saveAssistantPrompt(edited)
        assertEquals(edited, assistantPrompt())

        app.personaRepository.refreshAssistantPromptIfNeeded()

        assertEquals(edited, assistantPrompt())
    }

    /** 「全部恢复默认」= 把手册重新交还 App 维护；此后导入进来的旧版手册也会被刷成最新 */
    @Test
    fun restoringDefaultReEnrollsAutoUpgrade() = runBlocking {
        saveAssistantPrompt(current)
        assertNull(app.settingsRepository.assistantPromptCustomized())

        // 模拟"导入了一份旧版手册"（导入直接写库，不经过 save）
        writeAssistantPrompt(intermediateStock)
        app.personaRepository.refreshAssistantPromptIfNeeded()

        assertEquals(current, assistantPrompt())
    }

    /**
     * **改过名字的内置助手**也要照常升级，且绝不多建一条（v0.68.0，全面验收查出的断链）。
     *
     * 老逻辑三处都写死 `getByName("应用助手")`：用户一旦给助手改名 ——
     * ① 提示词页查不到 → 显示的是**出厂手册**而不是他那份；
     * ② 点保存走 `?: Persona(…)` → **再建一条内置助手**，人设列表里出现两个；
     * ③ 启动升级也按名字找 → 他那本手册**永远停在改名那一刻**。
     * ③ 是最难发现的：不报错、不崩，界面一切正常，只是助手从此答旧话。
     *
     * 现在统一走 [com.menghuan.app.data.PersonaRepository.findAssistant]：
     * 名字找不到就认"内置人设里身上还挂着内置手册形状的那一份"。
     */
    @Test
    fun renamedAssistantStillUpgradesAndNeverDuplicates() = runBlocking {
        val dao = app.db.personaDao()
        val seeded = dao.getByName(ASSISTANT_PERSONA_NAME)
            ?: error("setUp 的 ensureAssistant() 应当已经有一条内置助手")
        val renamed = seeded.copy(name = "我的向导", systemPrompt = stockV038)
        dao.upsert(renamed)
        // **把场景做成确定的**：Robolectric 整轮共享同一个内存库，前面用例（尤其本类这条改名用例）
        // 会让"按默认名字补种"再插一条进来。这里做出"库里只有改名这一条内置助手"的场景，
        // 测的才是断链本身，而不是别人的残留。
        dao.getBuiltIns().filter { it.id != renamed.id }.forEach { dao.deleteById(it.id) }

        app.personaRepository.refreshAssistantPromptIfNeeded()

        val found = app.personaRepository.findAssistant()
        assertEquals("改过名也必须被找回来并升到当前版", current, found?.systemPrompt)
        assertEquals("升的得是他那一本，不是按默认名字新建的那本", "我的向导", found?.name)

        // ensureAssistant 同样不能因为名字对不上就补一条（它每启动都跑）
        app.personaRepository.ensureAssistant()
        assertEquals("不该按默认名字再建第二条内置助手", 1, dao.getBuiltIns().size)
        assertNull("按默认名字查仍然查不到 —— 老逻辑正是断在这里", dao.getByName(ASSISTANT_PERSONA_NAME))

        // 还原名字，别把"库里没有默认名助手"这个状态留给后面的用例（它们的 helper 按名字取 !!）。
        // 用 if 而不是 `found?.let { … }` 收尾：后者会把表达式类型带成非 Unit，
        // JUnit 于是报"Method … should be void"整个类跑不起来。
        if (found != null) dao.upsert(found.copy(name = ASSISTANT_PERSONA_NAME))
    }
}
