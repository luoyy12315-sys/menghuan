package com.menghuan.app.data

import com.menghuan.app.data.model.ProfileFact

/** Kotlin 的 Char 没有"是不是汉字"这一说，自己给一个（基本区 + 扩展 A 区，够聊天用） */
private fun Char.isCJK(): Boolean = this in '\u4e00'..'\u9fff' || this in '\u3400'..'\u4dbf'

/**
 * 记忆的读取范围与词法检索（v0.70.0，全纯函数、JVM 单测口径）。
 *
 * ## 为什么这一层单独存在
 * "谁能读到哪几条记忆"是**安全边界**，不是排版细节：以前整张 `profile_facts` 是全局的，
 * 每个角色的 system prompt 都拼同一份，A 角色聊出来的私事会原样出现在 B 角色嘴里。
 * 判据必须能脱离 Android、脱离 Room 一次算清并钉进测试，所以放在这里，而不是散在 ViewModel 里。
 *
 * ## 为什么检索只做词法、不引向量
 * 向量召回要下模型（对齐 `AsrModelManager` 那条"装完再提示下载"的路），
 * 但**没有向量的检索远好过没有检索**：`<search:关键词>` 那条协议要能先跑起来。
 * 中文按**相邻双字 bigram**切（Aelios 的实测做法），不需要分词库也不需要拼音词典。
 */
object MemoryScope {

    /** 用户自己的基础档案固定占这一档，与"某个角色的记忆"区分开 */
    const val GLOBAL_PERSONA_ID = 0L

    /**
     * 这个角色这一轮**能读到**的记忆。
     *
     * - `core`（永久记忆）与 `active` 都算；`staging` / `rejected` / `archived` 一律不进 prompt
     *   —— 待入库的还没被用户点头，被拒过的**永远不再回来**（拒一次就反复提起是最惹恼人的那种）。
     * - 基础档案（`personaId = 0`）只在 [sharedReadable] 为真时给。关掉之后，
     *   每个角色只剩自己聊出来的那部分。
     *
     * 顺序稳定：`core` 置顶，其余按 id。按 updatedAt 排会让注入内容每次编辑后整体跳动
     * —— 前缀一抖，缓存就白丢（口径见 AGENTS.md §零.9）。
     */
    fun visible(all: List<ProfileFact>, personaId: Long, sharedReadable: Boolean): List<ProfileFact> =
        all.filter { f ->
            f.injectable && (
                f.personaId == personaId ||
                    (f.personaId == GLOBAL_PERSONA_ID && sharedReadable && personaId != GLOBAL_PERSONA_ID)
                )
        }.sortedWith(
            compareBy<ProfileFact> { if (it.lifecycle == "core") 0 else 1 }.thenBy { it.id }
        )

    /** 只取用户本人的基础档案（不属于任何角色的全局链路用，如每日建议） */
    fun visibleGlobal(all: List<ProfileFact>): List<ProfileFact> =
        all.filter { it.injectable && it.personaId == GLOBAL_PERSONA_ID }
            .sortedWith(compareBy<ProfileFact> { if (it.lifecycle == "core") 0 else 1 }.thenBy { it.id })

    /** 只取属于某个角色自己的那部分（记忆页列本体用；不含基础档案、不过滤生命周期） */
    fun ownedBy(all: List<ProfileFact>, personaId: Long): List<ProfileFact> =
        all.filter { it.personaId == personaId }.sortedBy { it.id }

    // ===== 词法检索 =====

    /**
     * 切检索单元：ASCII 连续词（≥2 字母数字，转小写）+ **中文相邻双字 bigram**。
     *
     * 为什么用 bigram 而不是整词：中文没有空格，引分词库要么加词典要么加模型。
     * bigram 的代价是召回偏宽，由 [score] 的"命中占比"把精度找回来。
     */
    fun tokens(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>(text.length / 2 + 2)
        val run = StringBuilder()
        for (ch in text) {
            if (ch.isCJK()) {
                // 汉字串里滑窗取双字；单字成串时它也该能被搜到
                if (run.isNotEmpty()) { out += run.toString().lowercase(); run.setLength(0) }
                out += ch.toString()
            } else if (ch.isLetterOrDigit()) {
                run.append(ch)
            } else if (run.isNotEmpty()) {
                out += run.toString().lowercase(); run.setLength(0)
            }
        }
        if (run.isNotEmpty()) out += run.toString().lowercase()
        val grams = ArrayList<String>(out.size)
        // 汉字单字再拼成相邻双字（上面已存了单字，这里补 bigram）
        val chars = text.filter { it.isCJK() }.toList()
        for (i in 0 until chars.size - 1) grams += "${chars[i]}${chars[i + 1]}"
        return (out + grams).filter { it.isNotEmpty() }.distinct()
    }

    /**
     * 一条记忆对某个查询的相似分 0–100（纯函数）。
     *
     * 口径 = **查询的检索单元有多少比例出现在这条记忆里**（不是反过来）：
     * 查"喜欢吃什么"命中"这家伙喜欢吃火锅"要给高分；反过来用记忆的 token 去除会以长条目占便宜。
     * 命中比例给 0–80 分，剩下的 20 分留给"整串原样出现"这种强证据。
     */
    fun score(query: String, memory: String): Int {
        val q = query.trim()
        val m = memory.trim()
        if (q.isEmpty() || m.isEmpty()) return 0
        val qt = tokens(q)
        if (qt.isEmpty()) return 0
        val hits = qt.count { m.contains(it, ignoreCase = true) }
        if (hits == 0) return 0
        val ratio = hits.toFloat() / qt.size
        var s = (ratio * 80f).toInt().coerceIn(1, 80)
        if (m.contains(q, ignoreCase = true) || q.contains(m, ignoreCase = true)) s += 20
        return s.coerceAtMost(100)
    }

    /** 一次检索的命中（带分数，页面要把"按相似度排序"这件事显示出来） */
    data class Hit(val fact: ProfileFact, val score: Int)

    /**
     * 在**这个角色的可见记忆**里搜（先筛范围再搜分数——范围是安全边界，绝不能先搜后筛，
     * 否则 A 角色的内容会带着高分出现在给 B 角色的检索结果里）。
     *
     * [minScore] 默认 60，与爱语那个"记忆库模糊搜索得分阈值 0-100、默认 60"同口径，
     * 这样用户从别处带来的直觉与调好的数字能直接对上。
     */
    fun search(
        all: List<ProfileFact>,
        personaId: Long,
        sharedReadable: Boolean,
        query: String,
        minScore: Int = 60,
        limit: Int = 5,
    ): List<Hit> {
        val pool = visible(all, personaId, sharedReadable)
        if (pool.isEmpty() || query.isBlank()) return emptyList()
        return pool.mapNotNull { f ->
            val s = maxOf(score(query, f.key), score(query, f.value), score(query, "${f.key}：${f.value}"))
            if (s >= minScore) Hit(f, s) else null
        }
            .sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.fact.updatedAt })
            .take(limit)
    }

    private fun Char.isCJK(): Boolean = this in '\u4e00'..'\u9fff' || this in '\u3400'..'\u4dbf'
}
