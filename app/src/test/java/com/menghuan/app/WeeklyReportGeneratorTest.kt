package com.menghuan.app

import com.menghuan.app.data.WeeklyReportMath
import com.menghuan.app.data.model.AiInsight
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.Persona
import com.menghuan.app.data.model.ProfileFact
import com.menghuan.app.data.model.TaskCompletion
import com.menghuan.app.data.model.TaskItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * AI 周报纯函数单测：周区间 / 统计聚合（口径同 AGENTS §五：完成度只算基础任务、
 * 打卡只算每日任务）/ prompt 组装 / 宽容解析 / 聊天摘要截断。
 */
class WeeklyReportGeneratorTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    // 2026-09-30 是周三：本周 = 09-28（周一）..10-04（周日）
    private val anchor = LocalDate.of(2026, 9, 30)
    private val weekStart = LocalDate.of(2026, 9, 28)
    private val weekEnd = LocalDate.of(2026, 10, 4)

    private fun dayMs(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `周区间对齐周一到周日`() {
        val (start, end) = WeeklyReportMath.weekRange(anchor)
        assertEquals(LocalDate.of(2026, 9, 28), start)
        assertEquals(LocalDate.of(2026, 10, 4), end)

        val (lastStart, lastEnd) = WeeklyReportMath.lastWeekRange(anchor)
        assertEquals(LocalDate.of(2026, 9, 21), lastStart)
        assertEquals(LocalDate.of(2026, 9, 27), lastEnd)

        // 周日本身：区间不滚动到下一周
        val (sundayStart, sundayEnd) = WeeklyReportMath.weekRange(LocalDate.of(2026, 10, 4))
        assertEquals(LocalDate.of(2026, 9, 28), sundayStart)
        assertEquals(LocalDate.of(2026, 10, 4), sundayEnd)
    }

    @Test
    fun `聚合口径_基础任务完成度与每日打卡分离`() {
        val tasks = listOf(
            TaskItem(id = 1, title = "整理作品集", dueAt = dayMs(weekStart), isDone = true),
            TaskItem(id = 2, title = "回复邮件", dueAt = dayMs(weekStart.plusDays(2)), isDone = false),
            // dueAt = 0 视为 anchor（今天），anchor 在区间内 → 计入
            TaskItem(id = 5, title = "无日期任务", dueAt = 0L, isDone = false),
            // 下周的到期任务不计入
            TaskItem(id = 6, title = "下周任务", dueAt = dayMs(LocalDate.of(2026, 10, 6)), isDone = false),
            // 每日任务：不进完成度，只进打卡
            TaskItem(id = 3, title = "晨跑", repeatDaily = true),
            TaskItem(id = 4, title = "阅读", repeatDaily = true),
        )
        val completions = listOf(
            TaskCompletion(taskId = 3, day = weekStart.toEpochDay()),
            TaskCompletion(taskId = 3, day = weekStart.plusDays(1).toEpochDay()),
            TaskCompletion(taskId = 4, day = weekStart.toEpochDay()),
            // 基础任务的完成记录不算打卡
            TaskCompletion(taskId = 2, day = weekStart.toEpochDay()),
            // 区间外（上周）不算
            TaskCompletion(taskId = 3, day = weekStart.minusDays(8).toEpochDay()),
        )
        val stats = WeeklyReportMath.aggregate(
            tasks = tasks,
            completions = completions,
            facts = emptyList(),
            insights = emptyList(),
            weekStart = weekStart,
            weekEnd = weekEnd,
            zone = zone,
            anchor = anchor,
        )
        assertEquals(3, stats.basicTotal)
        assertEquals(1, stats.basicDone)
        assertEquals(3, stats.dailyCompletions)
        assertEquals(mapOf("晨跑" to 2, "阅读" to 1), stats.completionsByTitle)
    }

    @Test
    fun `聚合_档案与洞察按时间区间过滤`() {
        val insideFact = ProfileFact(key = "作息", value = "开始早睡", updatedAt = dayMs(weekStart) + 1)
        val outsideFact = ProfileFact(key = "职业", value = "设计师", updatedAt = dayMs(weekStart.minusDays(1)))
        val insideInsight = AiInsight(title = "晨跑稳定", detail = "连续两周保持", createdAt = dayMs(weekStart.plusDays(3)))
        val outsideInsight = AiInsight(title = "旧洞察", detail = "很久以前", createdAt = dayMs(weekEnd) + 86_400_000L)

        val stats = WeeklyReportMath.aggregate(
            tasks = emptyList(),
            completions = emptyList(),
            facts = listOf(insideFact, outsideFact),
            insights = listOf(insideInsight, outsideInsight),
            weekStart = weekStart,
            weekEnd = weekEnd,
            zone = zone,
            anchor = anchor,
        )
        assertEquals(listOf(insideFact), stats.changedFacts)
        assertEquals(listOf(insideInsight), stats.insights)
    }

    @Test
    fun `prompt 组装包含关键数字与周期`() {
        val stats = WeeklyReportMath.aggregate(
            tasks = listOf(
                TaskItem(id = 1, title = "整理作品集", dueAt = dayMs(weekStart), isDone = true),
                TaskItem(id = 2, title = "回复邮件", dueAt = dayMs(weekStart), isDone = false),
                TaskItem(id = 3, title = "晨跑", repeatDaily = true),
            ),
            completions = listOf(
                TaskCompletion(taskId = 3, day = weekStart.toEpochDay()),
                TaskCompletion(taskId = 3, day = weekStart.plusDays(1).toEpochDay()),
            ),
            facts = emptyList(),
            insights = emptyList(),
            weekStart = weekStart,
            weekEnd = weekEnd,
            zone = zone,
            anchor = anchor,
        )
        val prompt = WeeklyReportMath.buildReportPrompt(stats, transcript = "用户：这周好累")
        assertTrue(prompt.contains("9月28日 – 10月4日"))
        assertTrue(prompt.contains("完成 1/2 项"))
        assertTrue(prompt.contains("共 2 次"))
        assertTrue(prompt.contains("晨跑（2 次）"))
        assertTrue(prompt.contains("用户：这周好累"))
    }

    @Test
    fun `宽容解析_纯文本与栅栏与JSON`() {
        assertEquals("本周稳步推进。", WeeklyReportMath.parseReport("本周稳步推进。"))
        assertEquals(
            "栅栏内正文",
            WeeklyReportMath.parseReport("```text\n栅栏内正文\n```"),
        )
        assertEquals(
            "JSON 里的周报",
            WeeklyReportMath.parseReport("{\"content\":\"JSON 里的周报\"}"),
        )
        assertEquals(
            "带前缀的 JSON",
            WeeklyReportMath.parseReport("前缀 {\"content\":\"带前缀的 JSON\"} 尾随"),
        )
        // content 非字符串 / 空输入 → 失败返回空串
        assertEquals("", WeeklyReportMath.parseReport("{\"content\": 123}"))
        assertEquals("", WeeklyReportMath.parseReport(""))
        assertEquals("", WeeklyReportMath.parseReport("   \n  "))
    }

    @Test
    fun `聊天摘要_按会话名标注并截断`() {
        val personas = listOf(
            Persona(id = 1, name = "阿布", title = "人生导师", avatarEmoji = "🖋️", systemPrompt = ""),
        )
        val transcript = WeeklyReportMath.buildTranscript(
            messages = listOf(
                ChatMessage(conversationId = 1, isFromUser = true, content = "这周想调整作息"),
                ChatMessage(conversationId = 1, isFromUser = false, content = "好，先定一个 23:00 的闹钟"),
                // 未知会话的消息：导师名回退
                ChatMessage(conversationId = 9, isFromUser = false, content = "hello"),
            ),
            personas = personas,
        )
        assertTrue(transcript.contains("用户：这周想调整作息"))
        assertTrue(transcript.contains("阿布（AI）：好，先定一个 23:00 的闹钟"))
        assertTrue(transcript.contains("AI（AI）：hello"))
        assertFalse(transcript.contains('\n').not())

        // 单条截断：超长消息被裁到 maxMessageChars
        val long = WeeklyReportMath.buildTranscript(
            messages = listOf(
                ChatMessage(conversationId = 1, isFromUser = true, content = "啊".repeat(500)),
            ),
            personas = personas,
            maxMessageChars = 50,
        )
        assertTrue(long.length <= 50 + "用户：".length)
    }

    @Test
    fun `分享文本含期标签与正文`() {
        val text = WeeklyReportMath.shareText(
            com.menghuan.app.data.model.AiWeeklyReport(
                id = 1,
                weekStart = weekStart.toEpochDay(),
                weekEnd = weekEnd.toEpochDay(),
                content = "本周任务稳步推进，睡眠开始前移。",
            ),
        )
        assertTrue(text.startsWith("【梦欢 · AI 周报】9月28日 – 10月4日"))
        assertTrue(text.contains("本周任务稳步推进，睡眠开始前移。"))
    }

    @Test
    fun `聊天摘要可排除指定会话`() {
        val personas = listOf(
            Persona(id = 1, name = "阿布", title = "人生导师", avatarEmoji = "🖋️", systemPrompt = ""),
            Persona(id = 2, name = "应用助手", title = "使用指南", avatarEmoji = "🤖", systemPrompt = ""),
        )
        val transcript = WeeklyReportMath.buildTranscript(
            messages = listOf(
                ChatMessage(conversationId = 1, isFromUser = true, content = "这周想调整作息"),
                ChatMessage(conversationId = 2, isFromUser = true, content = "这个 App 怎么导出数据？"),
                ChatMessage(conversationId = 2, isFromUser = false, content = "我的 → 设置 → 数据与隐私。"),
            ),
            personas = personas,
            excludeConversationIds = setOf(2L),
        )
        assertTrue(transcript.contains("用户：这周想调整作息"))
        assertFalse(transcript.contains("导出数据"))
        assertFalse(transcript.contains("应用助手"))
        // 全部排除 → 空串
        assertEquals(
            "",
            WeeklyReportMath.buildTranscript(
                messages = listOf(ChatMessage(conversationId = 2, isFromUser = true, content = "hi")),
                personas = personas,
                excludeConversationIds = setOf(2L),
            ),
        )
    }

    @Test
    fun `prompt 注入上期周报要点`() {
        val stats = WeeklyReportMath.aggregate(
            tasks = emptyList(),
            completions = emptyList(),
            facts = emptyList(),
            insights = emptyList(),
            weekStart = weekStart,
            weekEnd = weekEnd,
            zone = zone,
            anchor = anchor,
        )
        // 无上期：不出现该段落
        assertFalse(WeeklyReportMath.buildReportPrompt(stats, "").contains("上一期周报要点"))
        // 有上期：摘要段落出现（连续数周的周报有承接感）
        val withDigest = WeeklyReportMath.buildReportPrompt(stats, "", previousDigest = "上周完成 5/6 项，晚睡 3 天。")
        assertTrue(withDigest.contains("[上一期周报要点]"))
        assertTrue(withDigest.contains("上周完成 5/6 项，晚睡 3 天。"))
    }
}
