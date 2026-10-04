package com.menghuan.app

import com.menghuan.app.data.DailyContext
import com.menghuan.app.data.DailySuggestionMath
import com.menghuan.app.data.model.LedgerEntry
import com.menghuan.app.data.model.LedgerMath
import com.menghuan.app.data.model.LedgerSource
import com.menghuan.app.data.model.TaskCompletion
import com.menghuan.app.data.model.TaskItem
import com.menghuan.app.ui.data.buildWeekReview
import com.menghuan.app.ui.ledger.LedgerParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * v0.42.0 记账本 + 每日建议 + 本周回顾 纯逻辑单测。
 *
 * 覆盖的不变式（这些是 UI 直接依赖的语义，错了会体现在界面上）：
 * - 现金余额 = 全量流水有符号累加；支出为负、收入为正
 * - 月度聚合只统计当月（跨月/跨年边界、结余符号）
 * - 金额解析容忍口语与全角；抓不到就返回 null（绝不猜）
 * - 收支方向识别按关键词打分，默认支出
 * - 本周回顾口径统一：「该做」= 每日任务 + 当天到期基础任务；连续全清有宽恕规则
 * - 每日建议解析宽容（JSON / 代码栅栏 / 纯文本）且抓不到 title 视为失败
 */
class V0420LogicTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    // ===== 记账：余额 =====

    @Test
    fun `余额等于收入减支出`() {
        val entries = listOf(
            entry(isIncome = true, cents = 500_00),
            entry(isIncome = false, cents = 120_50),
            entry(isIncome = false, cents = 30_00),
        )
        // 500 - 120.5 - 30 = 349.5 元
        assertEquals(349_50L, LedgerMath.balanceCents(entries))
    }

    @Test
    fun `空流水余额为零`() {
        assertEquals(0L, LedgerMath.balanceCents(emptyList()))
    }

    @Test
    fun `余额可以为负`() {
        val entries = listOf(
            entry(isIncome = false, cents = 100_00),
            entry(isIncome = true, cents = 20_00),
        )
        assertEquals(-80_00L, LedgerMath.balanceCents(entries))
    }

    @Test
    fun `单笔有符号金额符号正确`() {
        assertEquals(100L, LedgerMath.signedCents(entry(isIncome = true, cents = 100)))
        assertEquals(-100L, LedgerMath.signedCents(entry(isIncome = false, cents = 100)))
    }

    // ===== 记账：月度聚合 =====

    @Test
    fun `月度聚合只统计当月并区分收支`() {
        val sep = day(2026, 9, 15)
        val oct = day(2026, 10, 3)
        val entries = listOf(
            LedgerEntry(isIncome = true, amountCents = 800_00, occurredAt = oct),
            LedgerEntry(isIncome = false, amountCents = 200_00, occurredAt = oct),
            LedgerEntry(isIncome = false, amountCents = 999_00, occurredAt = sep),
        )
        val totals = LedgerMath.monthTotals(entries, 2026, 10, zone)
        assertEquals(800_00L, totals.incomeCents)
        assertEquals(200_00L, totals.expenseCents)
        assertEquals(600_00L, totals.netCents)
    }

    @Test
    fun `月度结余可以为负`() {
        val oct = day(2026, 10, 3)
        val totals = LedgerMath.monthTotals(
            listOf(LedgerEntry(isIncome = false, amountCents = 500_00, occurredAt = oct)),
            2026, 10, zone,
        )
        assertEquals(-500_00L, totals.netCents)
    }

    @Test
    fun `跨年边界按年份区分`() {
        val dec = day(2025, 12, 31)
        val jan = day(2026, 1, 1)
        val entries = listOf(
            LedgerEntry(isIncome = true, amountCents = 100_00, occurredAt = dec),
            LedgerEntry(isIncome = true, amountCents = 200_00, occurredAt = jan),
        )
        assertEquals(100_00L, LedgerMath.monthTotals(entries, 2025, 12, zone).incomeCents)
        assertEquals(200_00L, LedgerMath.monthTotals(entries, 2026, 1, zone).incomeCents)
    }

    @Test
    fun `日聚合按当月第几天归组`() {
        val entries = listOf(
            LedgerEntry(isIncome = false, amountCents = 30_00, occurredAt = day(2026, 10, 3)),
            LedgerEntry(isIncome = false, amountCents = 20_00, occurredAt = day(2026, 10, 3)),
            LedgerEntry(isIncome = true, amountCents = 100_00, occurredAt = day(2026, 10, 4)),
        )
        val daily = LedgerMath.dailyNetCents(entries, 2026, 10, zone)
        assertEquals(-50_00L, daily[3])
        assertEquals(100_00L, daily[4])
        assertNull(daily[5])
    }

    // ===== 记账：金额与方向解析 =====

    @Test
    fun `金额解析支持小数与货币符号`() {
        assertEquals(3580L, LedgerMath.parseAmountToCents("今天买菜花了 35.8 元"))
        assertEquals(3500L, LedgerMath.parseAmountToCents("¥35"))
        assertEquals(800000L, LedgerMath.parseAmountToCents("发工资 8000"))
    }

    @Test
    fun `金额解析支持全角数字与千分位`() {
        assertEquals(123456L, LedgerMath.parseAmountToCents("1,234.56"))
        assertEquals(3580L, LedgerMath.parseAmountToCents("３５．８"))
    }

    @Test
    fun `金额解析抓不到返回空`() {
        assertNull(LedgerMath.parseAmountToCents(""))
        assertNull(LedgerMath.parseAmountToCents("今天天气不错"))
        assertNull(LedgerMath.parseAmountToCents("花了零块钱"))
    }

    @Test
    fun `金额解析忽略零与负数`() {
        assertNull(LedgerMath.parseAmountToCents("0"))
        assertNull(LedgerMath.parseAmountToCents("0.00"))
    }

    @Test
    fun `小数超过两位时截断到分`() {
        // 正则只匹配两位小数，"35.899" 应取 35.89
        assertEquals(3589L, LedgerMath.parseAmountToCents("35.899"))
    }

    @Test
    fun `收支方向默认为支出`() {
        assertFalse(LedgerMath.detectIncome("买菜花了 35"))
        assertFalse(LedgerMath.detectIncome("随便记一笔 100"))
    }

    @Test
    fun `收支方向识别收入关键词`() {
        assertTrue(LedgerMath.detectIncome("收到工资 8000"))
        assertTrue(LedgerMath.detectIncome("报销到账 200"))
        assertTrue(LedgerMath.detectIncome("收到一个红包 50"))
    }

    @Test
    fun `金额展示格式化两位小数`() {
        assertEquals("35.80", LedgerMath.formatCents(3580))
        assertEquals("0.05", LedgerMath.formatCents(5))
        assertEquals("-12.30", LedgerMath.formatCents(-1230))
        assertEquals("1000.00", LedgerMath.formatCents(100000))
    }

    // ===== 记账：语音文本 → 草稿 =====

    @Test
    fun `语音文本解析出支出草稿`() {
        val draft = LedgerParser.fromText("今天打车花了 28 块")
        assertTrue(draft.isValid)
        assertFalse(draft.isIncome)
        assertEquals(2800L, draft.amountCents)
        assertEquals("今天打车花了 28 块", draft.note)
    }

    @Test
    fun `语音文本解析出收入草稿`() {
        val draft = LedgerParser.fromText("收到退款 99.9")
        assertTrue(draft.isValid)
        assertTrue(draft.isIncome)
        assertEquals(9990L, draft.amountCents)
    }

    @Test
    fun `语音文本没有金额时草稿无效`() {
        val draft = LedgerParser.fromText("买了个东西但是忘了多少钱")
        assertFalse(draft.isValid)
        assertNull(draft.amountCents)
    }

    // ===== 记账：识图回复解析 =====

    @Test
    fun `识图 JSON 回复解析金额与方向`() {
        val reply = """{"amount":35.8,"type":"支出","note":"午餐"}"""
        val draft = LedgerParser.fromImageReply(reply)
        assertTrue(draft.isValid)
        assertFalse(draft.isIncome)
        assertEquals(3580L, draft.amountCents)
        assertEquals("午餐", draft.note)
    }

    @Test
    fun `识图 JSON 回复识别收入`() {
        val draft = LedgerParser.fromImageReply("""{"amount":200,"type":"收入","note":"收款"}""")
        assertTrue(draft.isIncome)
        assertEquals(20000L, draft.amountCents)
    }

    @Test
    fun `识图金额为字符串时也能解析`() {
        val draft = LedgerParser.fromImageReply("""{"amount":"12.50","type":"支出","note":""}""")
        assertEquals(1250L, draft.amountCents)
    }

    @Test
    fun `识图金额为 null 时整体退化纯文本解析`() {
        // JSON 里 amount 为 null → 退化到纯文本，文本里也没有数字 → 无效
        val draft = LedgerParser.fromImageReply("""{"amount":null,"type":"支出","note":""}""")
        assertFalse(draft.isValid)
    }

    @Test
    fun `识图裹代码栅栏时仍能解析`() {
        val reply = "```json\n{\"amount\":88,\"type\":\"支出\",\"note\":\"加油\"}\n```"
        val draft = LedgerParser.fromImageReply(reply)
        assertEquals(8800L, draft.amountCents)
        assertEquals("加油", draft.note)
    }

    @Test
    fun `识图返回纯文本时按文本解析`() {
        val draft = LedgerParser.fromImageReply("这张收据显示支出 66.6 元")
        assertEquals(6660L, draft.amountCents)
        assertFalse(draft.isIncome)
    }

    // ===== 本周回顾：统一口径 =====

    @Test
    fun `每日任务只算创建于当天或更早的`() {
        val today = LocalDate.of(2026, 10, 3)
        val futureTask = TaskItem(
            id = 1, title = "明天才建的每日任务", repeatDaily = true,
            createdAt = day(2026, 10, 5) + 3600_000,
        )
        val review = buildWeekReview(listOf(futureTask), emptyList(), today, zone)
        val todayCell = review.days.last()
        assertEquals(0, todayCell.total)
    }

    @Test
    fun `每日任务完成看当天打卡记录`() {
        val today = LocalDate.of(2026, 10, 3)
        val task = TaskItem(id = 7, title = "晨跑", repeatDaily = true, createdAt = day(2026, 9, 1))
        val completions = listOf(TaskCompletion(taskId = 7, day = today.toEpochDay()))
        val review = buildWeekReview(listOf(task), completions, today, zone)
        assertEquals(1, review.days.last().done)
        assertEquals(1, review.days.last().total)
        assertTrue(review.days.last().isFull)
    }

    @Test
    fun `基础任务只算当天到期的`() {
        val today = LocalDate.of(2026, 10, 3)
        val todayTask = TaskItem(id = 1, title = "今天到期", dueAt = day(2026, 10, 3))
        val otherDay = TaskItem(id = 2, title = "别天到期", dueAt = day(2026, 10, 1))
        val review = buildWeekReview(listOf(todayTask, otherDay), emptyList(), today, zone)
        val todayCell = review.days.last()
        assertEquals(1, todayCell.total)
        assertEquals("今天到期", todayCell.items.first().title)
    }

    @Test
    fun `dueAt 为零视为今天`() {
        val today = LocalDate.of(2026, 10, 3)
        val task = TaskItem(id = 1, title = "无日期任务", dueAt = 0)
        val review = buildWeekReview(listOf(task), emptyList(), today, zone)
        assertEquals(1, review.days.last().total)
        // 无日期任务不该出现在过去的日期里
        assertEquals(0, review.days.first().total)
    }

    @Test
    fun `本周合计只统计周一起的天`() {
        // 2026-10-03 是周六，本周一 = 9/28；近 7 天里 9/27（周日）属于上一周
        val today = LocalDate.of(2026, 10, 3)
        val task = TaskItem(id = 1, title = "周日任务", dueAt = day(2026, 9, 27))
        val review = buildWeekReview(listOf(task), emptyList(), today, zone)
        assertEquals(0, review.total) // 上一周的不计入本周合计
        assertEquals(1, review.days.sumOf { it.total }) // 但仍在 7 天格子里展示
    }

    @Test
    fun `连续全清从昨天往回数且今天未全清不断签`() {
        val today = LocalDate.of(2026, 10, 3)
        val task = TaskItem(id = 5, title = "打卡项", repeatDaily = true, createdAt = day(2026, 9, 1))
        // 只有前天、昨天完成，今天没完成
        val completions = listOf(
            TaskCompletion(taskId = 5, day = today.minusDays(1).toEpochDay()),
            TaskCompletion(taskId = 5, day = today.minusDays(2).toEpochDay()),
        )
        val review = buildWeekReview(listOf(task), completions, today, zone)
        assertEquals(2, review.streak)
    }

    @Test
    fun `无任务的天不打断连续`() {
        val today = LocalDate.of(2026, 10, 3)
        val task = TaskItem(id = 5, title = "打卡项", repeatDaily = true, createdAt = day(2026, 9, 20))
        val completions = listOf(
            TaskCompletion(taskId = 5, day = today.minusDays(1).toEpochDay()),
            TaskCompletion(taskId = 5, day = today.minusDays(2).toEpochDay()),
            TaskCompletion(taskId = 5, day = today.minusDays(3).toEpochDay()),
            TaskCompletion(taskId = 5, day = today.minusDays(4).toEpochDay()),
            TaskCompletion(taskId = 5, day = today.minusDays(5).toEpochDay()),
            TaskCompletion(taskId = 5, day = today.minusDays(6).toEpochDay()),
        )
        val review = buildWeekReview(listOf(task), completions, today, zone)
        assertEquals(6, review.streak)
    }

    @Test
    fun `回顾 days 固定七天且旧到新`() {
        val today = LocalDate.of(2026, 10, 3)
        val review = buildWeekReview(emptyList(), emptyList(), today, zone)
        assertEquals(7, review.days.size)
        assertEquals(today, review.days.last().date)
        assertEquals(today.minusDays(6), review.days.first().date)
    }

    @Test
    fun `回顾 completionDate 不是今天时基础任务仍按 isDone 判定`() {
        val today = LocalDate.of(2026, 10, 3)
        val done = TaskItem(id = 1, title = "完成的基础任务", dueAt = day(2026, 10, 3), isDone = true)
        val review = buildWeekReview(listOf(done), emptyList(), today, zone)
        assertEquals(1, review.days.last().done)
    }

    // ===== 每日建议：解析 =====

    @Test
    fun `建议解析标准 JSON`() {
        val parsed = DailySuggestionMath.parse("""{"title":"先做十分钟","detail":"把最想拖的那件事开个头"}""")
        assertEquals("先做十分钟", parsed?.first)
        assertEquals("把最想拖的那件事开个头", parsed?.second)
    }

    @Test
    fun `建议解析带前后说明文字`() {
        val parsed = DailySuggestionMath.parse("好的，这是今天的建议：{\"title\":\"出门走走\",\"detail\":\"二十分钟\"} 希望有用")
        assertEquals("出门走走", parsed?.first)
        assertEquals("二十分钟", parsed?.second)
    }

    @Test
    fun `建议解析裹代码栅栏`() {
        val parsed = DailySuggestionMath.parse("```json\n{\"title\":\"早睡\",\"detail\":\"十一点前\"}\n```")
        assertEquals("早睡", parsed?.first)
    }

    @Test
    fun `建议 JSON 缺 title 视为失败`() {
        assertNull(DailySuggestionMath.parse("""{"detail":"只有说明"}"""))
        assertNull(DailySuggestionMath.parse("""{"title":"","detail":"空标题"}"""))
    }

    @Test
    fun `建议纯文本回复兜底`() {
        val parsed = DailySuggestionMath.parse("先喝一杯水\n然后开始今天的第一个任务")
        assertEquals("先喝一杯水", parsed?.first)
        assertEquals("然后开始今天的第一个任务", parsed?.second)
    }

    @Test
    fun `建议空回复返回空`() {
        assertNull(DailySuggestionMath.parse(""))
        assertNull(DailySuggestionMath.parse("   "))
    }

    @Test
    fun `建议纯文本剥掉列表符号`() {
        val parsed = DailySuggestionMath.parse("- 先列个清单")
        assertEquals("先列个清单", parsed?.first)
    }

    @Test
    fun `建议超长内容被截断`() {
        val longTitle = "标".repeat(80)
        val parsed = DailySuggestionMath.parse("""{"title":"$longTitle","detail":"x"}""")
        assertEquals(40, parsed?.first?.length)
    }

    @Test
    fun `建议 prompt 带上今日任务与支出信息`() {
        val ctx = DailyContext(
            day = LocalDate.of(2026, 10, 3),
            doneToday = 1,
            totalToday = 3,
            pendingTitles = listOf("写周报", "跑步"),
            facts = listOf("职业" to "产品经理"),
            insights = listOf("晚间效率更高"),
            yesterdayExpenseCents = 12_00,
            monthExpenseCents = 300_00,
        )
        val prompt = DailySuggestionMath.buildPrompt(ctx)
        assertTrue(prompt.contains("1/3"))
        assertTrue(prompt.contains("写周报"))
        assertTrue(prompt.contains("产品经理"))
        assertTrue(prompt.contains("晚间效率更高"))
        assertTrue(prompt.contains("12.00"))
        assertTrue(prompt.contains("300.00"))
    }

    @Test
    fun `建议 prompt 无支出时不提钱`() {
        val ctx = DailyContext(
            day = LocalDate.of(2026, 10, 3),
            doneToday = 0, totalToday = 0, pendingTitles = emptyList(),
            facts = emptyList(), insights = emptyList(),
            yesterdayExpenseCents = 0, monthExpenseCents = 0,
        )
        val prompt = DailySuggestionMath.buildPrompt(ctx)
        assertFalse(prompt.contains("支出情况"))
    }

    @Test
    fun `建议 systemPrompt 拼接框架与策略`() {
        val withStrategy = DailySuggestionMath.systemPrompt("自定义策略")
        assertTrue(withStrategy.contains(DailySuggestionMath.FRAMEWORK))
        assertTrue(withStrategy.contains("自定义策略"))
        // 空策略只发框架
        assertEquals(DailySuggestionMath.FRAMEWORK, DailySuggestionMath.systemPrompt(""))
    }

    @Test
    fun `来源标签映射正确`() {
        assertEquals("手动", LedgerSource.label(LedgerSource.MANUAL))
        assertEquals("语音", LedgerSource.label(LedgerSource.VOICE))
        assertEquals("识图", LedgerSource.label(LedgerSource.IMAGE))
        assertEquals("手动", LedgerSource.label("未知来源"))
    }

    @Test
    fun `月度切换保持年份正确`() {
        assertEquals(YearMonth.of(2026, 9), YearMonth.of(2026, 10).plusMonths(-1))
        assertEquals(YearMonth.of(2027, 1), YearMonth.of(2026, 12).plusMonths(1))
    }

    // ===== v0.42.1 周度统计 =====

    @Test
    fun `周一为一周起点`() {
        // 2026-10-03 是周六 → 本周一是 2026-09-28
        assertEquals(LocalDate.of(2026, 9, 28), LedgerMath.weekStartOf(LocalDate.of(2026, 10, 3)))
        // 周一本身回自己
        assertEquals(LocalDate.of(2026, 9, 28), LedgerMath.weekStartOf(LocalDate.of(2026, 9, 28)))
        // 周日属上一周（不是下一周开始）
        assertEquals(LocalDate.of(2026, 9, 28), LedgerMath.weekStartOf(LocalDate.of(2026, 10, 4)))
        // 下一个周一进入新的一周
        assertEquals(LocalDate.of(2026, 10, 5), LedgerMath.weekStartOf(LocalDate.of(2026, 10, 5)))
    }

    @Test
    fun `周区间聚合含周一不含下周一`() {
        val weekStart = LocalDate.of(2026, 9, 28) // 周一
        val entries = listOf(
            // 周一 00:00 属于本周
            entryAt(2026, 9, 28, 0, isIncome = true, cents = 100_00),
            // 周日 23:59 仍属本周
            entryAt(2026, 10, 4, 23, isIncome = false, cents = 30_00),
            // 下周一 00:00 不属于本周（左闭右开）
            entryAt(2026, 10, 5, 0, isIncome = true, cents = 999_00),
            // 上周日 23:59 不属于本周
            entryAt(2026, 9, 27, 23, isIncome = false, cents = 888_00),
        )
        val totals = LedgerMath.weekTotals(entries, weekStart, zone)
        assertEquals(100_00, totals.incomeCents)
        assertEquals(30_00, totals.expenseCents)
        assertEquals(70_00, totals.netCents)
    }

    @Test
    fun `周内每日净额按周一到周日编号并累加同日多笔`() {
        val weekStart = LocalDate.of(2026, 9, 28)
        val entries = listOf(
            entryAt(2026, 9, 28, 9, isIncome = true, cents = 100_00), // 周一
            entryAt(2026, 9, 28, 20, isIncome = false, cents = 40_00), // 周一（同日再一笔）
            entryAt(2026, 10, 4, 9, isIncome = false, cents = 25_00), // 周日 = 第 7 天
        )
        val net = LedgerMath.weeklyNetCents(entries, weekStart, zone)
        assertEquals(60_00L, net[1]) // 周一 = 100 - 40
        assertEquals(-25_00L, net[7]) // 周日
        assertNull(net[3]) // 周三没有流水
    }

    @Test
    fun `周切换跨月跨年正确`() {
        // 2026-09-28（周一）往前一周 = 2026-09-21
        assertEquals(LocalDate.of(2026, 9, 21), LedgerMath.shiftWeek(LocalDate.of(2026, 9, 28), -1))
        // 2026-12-28（周一）往后一周跨年到 2027-01-04
        assertEquals(LocalDate.of(2027, 1, 4), LedgerMath.shiftWeek(LocalDate.of(2026, 12, 28), 1))
    }

    @Test
    fun `同一批流水月档与周档合计不同`() {
        // 验证两档口径确实分开了：一条上个月的收入只进月档
        val entries = listOf(
            entryAt(2026, 9, 15, 10, isIncome = true, cents = 500_00), // 上周月
            entryAt(2026, 10, 3, 10, isIncome = true, cents = 200_00), // 本月本周
        )
        val monthTotals = LedgerMath.monthTotals(entries, 2026, 10, zone)
        val weekTotals = LedgerMath.weekTotals(entries, LocalDate.of(2026, 9, 28), zone)
        assertEquals(200_00, monthTotals.incomeCents) // 9 月那笔不在 10 月
        assertEquals(200_00, weekTotals.incomeCents)
        // 把 9 月那笔挪进 10 月后，月档应翻倍而本周档不变
        val shifted = listOf(
            entryAt(2026, 10, 1, 10, isIncome = true, cents = 500_00),
            entryAt(2026, 10, 3, 10, isIncome = true, cents = 200_00),
        )
        assertEquals(700_00, LedgerMath.monthTotals(shifted, 2026, 10, zone).incomeCents)
        assertEquals(700_00, LedgerMath.weekTotals(shifted, LocalDate.of(2026, 9, 28), zone).incomeCents)
        // 再把它挪出本周但留在本月（10 月 20 日，已过本周日）→ 月档仍含、周档不含
        val outOfWeek = listOf(
            entryAt(2026, 10, 20, 10, isIncome = true, cents = 500_00),
            entryAt(2026, 10, 3, 10, isIncome = true, cents = 200_00),
        )
        assertEquals(700_00, LedgerMath.monthTotals(outOfWeek, 2026, 10, zone).incomeCents)
        assertEquals(200_00, LedgerMath.weekTotals(outOfWeek, LocalDate.of(2026, 9, 28), zone).incomeCents)
    }

    // ===== 辅助 =====

    private fun entry(isIncome: Boolean, cents: Long) = LedgerEntry(
        isIncome = isIncome,
        amountCents = cents,
        occurredAt = day(2026, 10, 3),
    )

    private fun entryAt(
        year: Int,
        month: Int,
        dayOfMonth: Int,
        hour: Int,
        isIncome: Boolean,
        cents: Long,
    ) = LedgerEntry(
        isIncome = isIncome,
        amountCents = cents,
        occurredAt = LocalDate.of(year, month, dayOfMonth).atTime(hour, 0)
            .atZone(zone).toInstant().toEpochMilli(),
    )

    /** 某个本地日期的 0 点毫秒值 */
    private fun day(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth).atStartOfDay(zone).toInstant().toEpochMilli()
}
