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
        assertEquals("自己的按 id、core 置顶", listOf(6L, 1L, 3L), got)
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
        assertEquals("core 在前，其余按 id", listOf(6L, 2L, 3L), got)
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
}
