package com.menghuan.app

import com.menghuan.app.data.MemoryScope
import com.menghuan.app.data.model.ProfileFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆读取范围与词法检索单测（v0.70.0，纯 JVM）。
 *
 * 这一组测试钉的是**边界**，不是效果：
 * "A 角色的私事会不会从 B 角色嘴里说出来"、"被用户拒过的记忆会不会又回来"、
 * "检索会不会先搜全表再筛范围"。这三条错任何一条都不会报错，只会让用户发现 TA 记错了人。
 */
class MemoryScopeTest {

    private fun fact(
        id: Long,
        key: String,
        value: String,
        personaId: Long = 0,
        lifecycle: String = "active",
    ) = ProfileFact(
        id = id, key = key, value = value, source = "ai",
        updatedAt = 1_000L + id, personaId = personaId, lifecycle = lifecycle,
    )

    private val pool = listOf(
        fact(1, "口味", "喜欢吃火锅", personaId = 7),
        fact(2, "职业", "前端开发", personaId = 8),
        fact(3, "名字", "小红", personaId = 0),
        fact(4, "旧事", "去年去过西藏", personaId = 7, lifecycle = "rejected"),
        fact(5, "停用", "已分手", personaId = 7, lifecycle = "archived"),
        fact(6, "长住", "住在杭州", personaId = 0, lifecycle = "core"),
    )

    @Test
    fun `角色只读到自己的加允许共享的基础档案`() {
        val got = MemoryScope.visible(pool, 7, sharedReadable = true).map { it.id }
        // 7 自己的 1、被拒/停用的 4 与 5、基础档案 3 与 6；绝不包含 8 的 2
        // v0.98.0：core 置顶之后其余改**倒序**（新的在前），所以是 6, 3, 1
        assertEquals("自己的按 id 倒序、core 置顶", listOf(6L, 3L, 1L), got)
        assertFalse("别角色的记忆绝不能进来", got.contains(2L))
    }

    @Test
    fun `关掉共享开关后只剩自己聊出来的`() {
        val got = MemoryScope.visible(pool, 7, sharedReadable = false).map { it.id }
        assertEquals(listOf(1L), got)
    }

    @Test
    fun `core 置顶且顺序稳定`() {
        val got = MemoryScope.visible(pool, 8, sharedReadable = true).map { it.id }
        assertEquals("core 在前，其余按 id 倒序（新的在前）", listOf(6L, 3L, 2L), got)
    }

    @Test
    fun `被拒过的记忆永不回归`() {
        // 用户点过一次"别记这个"，之后无论开关怎么调、检索怎么搜都不该再出现
        val ids = MemoryScope.visible(pool, 7, true).map { it.id } +
            MemoryScope.visible(pool, 0, true).map { it.id } +
            MemoryScope.search(pool, 7, true, "去年 西藏").map { it.fact.id }
        assertFalse(ids.contains(4L))
        assertFalse("archived 同样不进", ids.contains(5L))
    }

    @Test
    fun `检索先筛范围再算分_A角色的内容不出现在B的结果里`() {
        val hits = MemoryScope.search(pool, 8, sharedReadable = true, "火锅")
        assertTrue("7 的私密记忆不该被 8 搜到", hits.isEmpty())
        val own = MemoryScope.search(pool, 7, sharedReadable = true, "火锅")
        assertEquals(1L, own.single().fact.id)
    }

    @Test
    fun `洗掉疑问词才过得了默认阈值——这条就是协议里那句关键词清洗`() {
        // 带疑问词时只命中 5/9 个检索单元 → 44 分，落在默认阈值 60 之下
        val raw = MemoryScope.score("喜欢吃什么", "这家伙喜欢吃火锅")
        assertTrue("疑问词该被拉低，而不是拉高", raw in 1..59)
        // 洗成「喜欢吃」之后 5/5 全中并加整串强证据 → 满分
        assertEquals(100, MemoryScope.score("喜欢吃", "这家伙喜欢吃火锅"))
        assertEquals(0, MemoryScope.score("完全无关的话", "住在杭州"))
    }

    @Test
    fun `整串原样出现算强证据`() {
        assertTrue(MemoryScope.score("火锅", "我喜欢吃火锅") > MemoryScope.score("我 喜欢 火锅", "我喜欢吃火锅"))
    }

    @Test
    fun `空查询与空库都不炸`() {
        assertTrue(MemoryScope.search(pool, 7, true, "  ").isEmpty())
        assertTrue(MemoryScope.search(emptyList(), 7, true, "火锅").isEmpty())
        assertTrue(MemoryScope.tokens("").isEmpty())
    }

    @Test
    fun `英文词按整词切且大小写不影响`() {
        val t = MemoryScope.tokens("用 Figma 设计")
        assertTrue(t.contains("figma"))
        assertTrue("汉字要能单独成元", t.contains("用"))
        assertTrue("相邻双字也要在", t.contains("设计"))
    }

    @Test
    fun `每日建议这类全局链路只看基础档案`() {
        val got = MemoryScope.visibleGlobal(pool).map { it.id }
        assertEquals(listOf(6L, 3L), got)
    }

    // ===== v0.98.0：档案的两条线（基础档案 / 角色记忆分开计数） =====

    @Test
    fun `拆开后基础档案与角色记忆各走各的线`() {
        val s = MemoryScope.split(pool, 7, sharedReadable = true)
        assertEquals("基础档案段：core 置顶", listOf(6L, 3L), s.base.map { it.id })
        assertEquals("角色自己的那段", listOf(1L), s.owned.map { it.id })
        assertEquals("合并注入是 base 在前", listOf(6L, 3L, 1L), s.forInject(10).map { it.id })
    }

    @Test
    fun `基础档案再多也挤不掉角色自己的记忆`() {
        // 这条正是 v0.98.0 要修的 bug：合并成一条列表时，100 条基础档案会把角色记忆整个挤出前 N 个名额。
        val many = (1..100).map { fact(100L + it, "基础$it", "值$it", personaId = 0) } +
            fact(1, "口味", "喜欢吃火锅", personaId = 7)
        val s = MemoryScope.split(many, 7, sharedReadable = true)
        assertEquals("基础档案全带", 100, s.base.size)
        assertEquals("她的记忆必须还在", listOf(1L), s.owned.map { it.id })
        // 上限只作用于 owned，所以最终注入 = 100 + 10（不够 10 条就全给）
        assertEquals(101, s.forInject(10).size)
    }

    @Test
    fun `同名条目只留最后写进库的那条`() {
        val rows = listOf(
            fact(1, "作息", "通常 00:30 睡", personaId = 0),
            fact(9, "作息", "最近改成 23:00 睡了", personaId = 7),
        )
        val s = MemoryScope.split(rows, 7, sharedReadable = true)
        assertEquals("重复的 key 不该两条线各出一行", 0, s.base.size)
        assertEquals(listOf(9L), s.owned.map { it.id })
    }

    @Test
    fun `人设 id 为 0 时没有基础档案这一档`() {
        val s = MemoryScope.split(pool, 0, sharedReadable = true)
        assertTrue("全局链路自己看自己", s.base.isEmpty())
        assertEquals(listOf(6L, 3L), s.owned.map { it.id })
    }

    // ===== v0.82.0 P1-3：自动归档判定 =====

    @Test
    fun `90天没被想起的active该归档_core与被想起过的豁免`() {
        val now = 100_000_000_000L
        val stale = MemoryScope.AUTO_ARCHIVE_AFTER_MS + 1
        // 超期且从没被想起（lastRecalledAt = 0 的老数据按 updatedAt 算）→ 归档
        assertTrue(
            MemoryScope.shouldAutoArchive(
                fact(1, "a", "b").copy(updatedAt = now - stale, lastRecalledAt = 0), now,
            ),
        )
        // 超期的是 updatedAt，但最近被想起过 → 不归档
        assertFalse(
            MemoryScope.shouldAutoArchive(
                fact(1, "a", "b").copy(updatedAt = now - stale, lastRecalledAt = now - 1000), now,
            ),
        )
        // core 永不动（用户设为永久是人的决定）
        assertFalse(
            MemoryScope.shouldAutoArchive(
                fact(1, "a", "b", lifecycle = "core").copy(updatedAt = now - stale), now,
            ),
        )
        // staging / archived 不在 sweep 范围（active 才会被判）
        assertFalse(
            MemoryScope.shouldAutoArchive(
                fact(1, "a", "b", lifecycle = "staging").copy(updatedAt = now - stale), now,
            ),
        )
        // 没超期的 active 不动
        assertFalse(
            MemoryScope.shouldAutoArchive(
                fact(1, "a", "b").copy(updatedAt = now - 1000), now,
            ),
        )
    }

    // ===== v0.82.0 P1-2：语义召回 =====

    @Test
    fun `余弦的基本纪律`() {
        assertEquals(1f, MemoryScope.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f)), 0.001f)
        assertEquals(0f, MemoryScope.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 0.001f)
        // 维度不等 / 空向量 / 零范数：算不出的一律当不像，不猜
        assertEquals(0f, MemoryScope.cosine(floatArrayOf(1f), floatArrayOf(1f, 2f)), 0.001f)
        assertEquals(0f, MemoryScope.cosine(FloatArray(0), floatArrayOf(1f)), 0.001f)
        assertEquals(0f, MemoryScope.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)), 0.001f)
    }

    @Test
    fun `语义-only命中要过更严的门槛_词法口径一字不动`() {
        val f = fact(1, "口味", "喜欢吃火锅", personaId = 7)
        val pool = listOf(f)
        // 词法 0 分（查询与记忆完全无字面重叠），但余弦够高 → 语义捞起
        val hits = MemoryScope.searchHybrid(
            pool, 7, true, "晚上吃点啥好呢",
            queryVector = floatArrayOf(1f, 0f),
            vectors = mapOf(1L to floatArrayOf(0.9f, 0.1f)), // cosine ≈ 0.994 ≥ 0.75
        )
        assertEquals("语义-only 命中该被捞起", listOf(1L), hits.map { it.fact.id })
        // 余弦差一点没过 0.75 → 不捞（语义模糊地带更宽，门槛必须比词法严）
        val weak = MemoryScope.searchHybrid(
            pool, 7, true, "晚上吃点啥好呢",
            queryVector = floatArrayOf(1f, 0f),
            vectors = mapOf(1L to floatArrayOf(0.7f, 0.7f)), // cosine ≈ 0.707 < 0.75
        )
        assertTrue("没到语义门槛就不该捞", weak.isEmpty())
        // 没给向量（端点本轮没调成）→ 退回纯词法，与 search 行为一致
        val lexical = MemoryScope.searchHybrid(pool, 7, true, "晚上吃点啥好呢")
        assertTrue(lexical.isEmpty())
    }

    @Test
    fun `向量编解码往返一致_坏数据不出半个float`() {
        val v = floatArrayOf(0.5f, -1.25f, 3.75f)
        val round = com.menghuan.app.data.EmbeddingVector.decode(
            com.menghuan.app.data.EmbeddingVector.encode(v),
        )
        assertEquals(v.size, round.size)
        v.indices.forEach { assertEquals(v[it], round[it], 0.0001f) }
        // 字节数不是 4 的倍数 = 数据坏了 → 空向量（余弦自然算 0）
        assertEquals(0, com.menghuan.app.data.EmbeddingVector.decode(byteArrayOf(1, 2, 3)).size)
        assertEquals(0, com.menghuan.app.data.EmbeddingVector.decode(ByteArray(0)).size)
    }
}
