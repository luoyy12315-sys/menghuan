package com.menghuan.app

import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.KIND_SCHEDULED_CALL
import com.menghuan.app.data.KIND_SCHEDULED_MESSAGE
import com.menghuan.app.data.MENTOR_PERSONA_ID
import com.menghuan.app.data.ScheduledMessage
import com.menghuan.app.data.model.Persona
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.89.0：**定时消息跟着角色走**。
 *
 * 用户实测反馈：「我把那个人设删了，但定时消息还是每天从应用助手发过来」。
 * 根因是 [com.menghuan.app.data.ScheduledMessageSender] 的 `pickPersona` 在找不到目标人设时
 * 会「指定人设 → 主人设 → 第一个人设」逐级兜底 —— 那个 `主人设` 就是内置**应用助手**（id=1），
 * 于是这条消息被静默改嫁给了助手，而不是失效。
 *
 * 本测试钉两件事：
 * ① 删角色时，写给那个角色的定时项（消息 + 定时电话两种 kind）被一并摘掉；
 * ② 别的角色与「主人设」的定时项不受牵连（删一个不能顺手清光）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MenghuanApp::class, sdk = [34])
class ScheduledMessagePersonaTest {

    private fun app() = ApplicationProvider.getApplicationContext<MenghuanApp>()

    @Test
    fun `删角色时它的定时消息与定时电话一并被摘掉`() = runBlocking {
        val a = app()
        val settings = a.settingsRepository

        val victim = a.db.personaDao().upsert(
            Persona(name = "要被删的角色", title = "陪聊", avatarEmoji = "🙂", systemPrompt = "你是"),
        )
        val keeper = a.db.personaDao().upsert(
            Persona(name = "要留下的角色", title = "教练", avatarEmoji = "🙂", systemPrompt = "你是"),
        )

        settings.upsertScheduledMessage(
            ScheduledMessage(id = "sm-victim", personaId = victim, time = "07:30", instruction = "早安"),
        )
        settings.upsertScheduledMessage(
            ScheduledMessage(
                id = "call-victim", personaId = victim, time = "22:00",
                instruction = "问我今天练了没", kind = KIND_SCHEDULED_CALL,
            ),
        )
        settings.upsertScheduledMessage(
            ScheduledMessage(id = "sm-keeper", personaId = keeper, time = "08:00", instruction = "打卡"),
        )
        // 「主人设」（personaId = 0）不该被任何一次删角色连带清掉
        settings.upsertScheduledMessage(
            ScheduledMessage(id = "sm-owner", personaId = 0L, time = "09:00", instruction = "主人设的"),
        )

        assertEquals(4, settings.scheduledMessages.first().size)

        a.personaRepository.delete(victim)

        val left = settings.scheduledMessages.first()
        assertFalse("被删角色的定时消息还在", left.any { it.id == "sm-victim" })
        assertFalse("被删角色的定时电话还在", left.any { it.id == "call-victim" })
        assertTrue("别的角色的定时消息被误删了", left.any { it.id == "sm-keeper" })
        assertTrue("主人设的定时消息被误删了", left.any { it.id == "sm-owner" })
        assertEquals(2, left.size)
    }

    @Test
    fun `内置应用助手被删时不影响已存在条目`() = runBlocking {
        val a = app()
        val settings = a.settingsRepository
        // 助手由种子建出来
        a.personaRepository.ensureAssistant()
        val assistant = a.db.personaDao().getByName("应用助手")!!
        assertEquals(MENTOR_PERSONA_ID, assistant.id)

        settings.upsertScheduledMessage(
            ScheduledMessage(id = "sm-owner", personaId = 0L, time = "09:00", instruction = "主人设的"),
        )
        settings.upsertScheduledMessage(
            ScheduledMessage(id = "sm-assistant", personaId = assistant.id, time = "10:00", instruction = "问助手"),
        )
        assertEquals(2, settings.scheduledMessages.first().size)

        a.personaRepository.delete(assistant.id)

        val left = settings.scheduledMessages.first()
        assertFalse("写给助手的定时项没有跟着删", left.any { it.id == "sm-assistant" })
        assertTrue("主人设的定时项被误删", left.any { it.id == "sm-owner" })
    }

    @Test
    fun `编解码把 kind 一起往返_删角色筛的是 personaId`() = runBlocking {
        val a = app()
        val settings = a.settingsRepository
        settings.upsertScheduledMessage(
            ScheduledMessage(id = "x", personaId = 55L, time = "07:00", instruction = "a", kind = KIND_SCHEDULED_MESSAGE),
        )
        settings.upsertScheduledMessage(
            ScheduledMessage(id = "y", personaId = 55L, time = "08:00", instruction = "b", kind = KIND_SCHEDULED_CALL),
        )
        val removed = settings.removeScheduledMessagesForPersona(55L)
        assertEquals(setOf("x", "y"), removed.toSet())
        assertTrue(settings.scheduledMessages.first().isEmpty())
    }
}
