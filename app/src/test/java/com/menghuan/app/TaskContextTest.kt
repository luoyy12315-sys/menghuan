package com.menghuan.app

import com.menghuan.app.data.TaskContext
import com.menghuan.app.data.model.TaskItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * v0.84.0：给模型看的「任务现状」分桶与引用纪律。
 *
 * 变红的含义：AI 又会开始提已完成 / 已逾期 / 很久以后的任务
 * （用户点名「聊天时老是时不时提到无关紧要的任务」）。
 *
 * 纯 JVM 测试：`TaskContext` 没有 Android 依赖，`today` 与 `zone` 都当参数传。
 */
class TaskContextTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val today: LocalDate = LocalDate.of(2026, 10, 10)
    private val todayDay: Long = today.toEpochDay()
    private fun ms(d: LocalDate): Long = d.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun task(
        title: String,
        due: LocalDate? = null,
        done: Boolean = false,
        failed: Boolean = false,
        daily: Boolean = false,
        completionDay: Long = 0L,
    ) = TaskItem(
        title = title,
        dueAt = due?.let(::ms) ?: 0L,
        isDone = done,
        isFailed = failed,
        repeatDaily = daily,
        completionDate = completionDay,
    )

    @Test
    fun `今天到期与明天到期各归各桶`() {
        val b = TaskContext.bucket(
            listOf(
                task("今天写周报", today),
                task("明天交稿", today.plusDays(1)),
                task("dueAt 为 0 视为今天"),
            ),
            today, zone,
        )
        assertEquals(listOf("今天写周报", "dueAt 为 0 视为今天"), b.today.map { it.title })
        assertEquals(listOf("明天交稿"), b.tomorrow.map { it.title })
        assertEquals(0, b.overdueCount)
    }

    @Test
    fun `逾期只计件数不给名字`() {
        val b = TaskContext.bucket(
            listOf(
                task("上个月就该交的表", today.minusDays(30)),
                task("昨天没做的", today.minusDays(1)),
                task("今天要做的", today),
            ),
            today, zone,
        )
        assertEquals(2, b.overdueCount)
        // 逾期任务的名字绝不能出现在任何桶里（一给名字模型就有理由提）
        val names = (b.today + b.tomorrow + b.done + b.failed).map { it.title }
        assertFalse("逾期任务名漏进了分桶", names.any { it.contains("上个月") || it.contains("昨天") })
    }

    @Test
    fun `三天以后的整块不进上下文`() {
        val b = TaskContext.bucket(
            listOf(task("下周一的面试", today.plusDays(3)), task("下个月的纪念日", today.plusDays(30))),
            today, zone,
        )
        assertTrue("3 天以后的任务不该进任何桶", b.isEmpty)
    }

    @Test
    fun `已完成归 done 不进 today`() {
        val b = TaskContext.bucket(
            listOf(task("今天已完成的事", today, done = true), task("还没做的", today)),
            today, zone,
        )
        assertEquals(listOf("还没做的"), b.today.map { it.title })
        assertEquals(listOf("今天已完成的事"), b.done.map { it.title })
    }

    @Test
    fun `每日任务跨天重置 昨天完成的今天回到未完成`() {
        // 每日任务：昨天打了卡（completionDate = 昨天），今天应回到「今天该做」
        val b = TaskContext.bucket(
            listOf(task("每日喝水", done = true, daily = true, completionDay = todayDay - 1)),
            today, zone,
        )
        assertEquals(listOf("每日喝水"), b.today.map { it.title })
        assertTrue("昨天的打卡不该算今天完成", b.done.isEmpty())
    }

    @Test
    fun `每日任务今天完成的算今天完成`() {
        val b = TaskContext.bucket(
            listOf(task("每日喝水", done = true, daily = true, completionDay = todayDay)),
            today, zone,
        )
        assertTrue(b.today.isEmpty())
        assertEquals(listOf("每日喝水"), b.done.map { it.title })
    }

    @Test
    fun `今天标失败的归 failed 不催`() {
        val b = TaskContext.bucket(
            listOf(task("今天放弃的事", today, failed = true, completionDay = todayDay)),
            today, zone,
        )
        assertEquals(listOf("今天放弃的事"), b.failed.map { it.title })
        assertTrue("标了失败的不该出现在今天该做里", b.today.isEmpty())
    }

    @Test
    fun `没有任何任务时块为空串`() {
        assertEquals("", TaskContext.block(emptyList(), today, zone))
    }

    @Test
    fun `块里带上引用纪律 只许提今天和明天`() {
        val block = TaskContext.block(listOf(task("今天要做的", today)), today, zone)
        assertTrue("没带引用纪律", block.contains(TaskContext.DISCIPLINE))
        assertTrue("没限定只提今天/明天", block.contains("**只许提「今天该做」和「明天到期」的**"))
        assertTrue("没说不提已完成的", block.contains("**已完成的任务不要拿来当话题**"))
        assertTrue("没说不主动提逾期", block.contains("**不要主动提**"))
    }

    @Test
    fun `不附纪律时只出事实`() {
        val block = TaskContext.block(listOf(task("今天要做的", today)), today, zone, includeDiscipline = false)
        assertTrue(block.contains("今天该做"))
        assertFalse("includeDiscipline=false 时不该带纪律", block.contains("不是待办播报稿"))
    }

    @Test
    fun `逾期块只说件数不说名字`() {
        val block = TaskContext.block(listOf(task("早就该做的事", today.minusDays(10))), today, zone)
        assertTrue("没提示逾期件数", block.contains("另有 1 件早该做但没做"))
        assertFalse("逾期任务名漏进了给模型的文本", block.contains("早就该做的事"))
    }

    @Test
    fun `提炼用清单只给今天明天和已完成`() {
        val text = TaskContext.forExtraction(
            listOf(
                task("今天要做的", today),
                task("明天要做的", today.plusDays(1)),
                task("今天做完的", today, done = true),
                task("早就该做的", today.minusDays(10)),
            ),
            today, zone,
        )
        assertTrue(text.contains("今天要做的"))
        assertTrue(text.contains("明天要做的"))
        assertTrue(text.contains("今天做完的"))
        assertFalse("逾期任务不该进提炼清单", text.contains("早就该做的"))
        assertTrue("没提醒别重复建议", text.contains("已经列在上面的不要再建议一遍"))
    }
}
