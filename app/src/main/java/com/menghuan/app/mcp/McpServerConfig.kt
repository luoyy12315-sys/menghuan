package com.menghuan.app.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * 一条 MCP 服务器配置（P3-3）。
 *
 * [id] 是稳定的 ASCII 短码，**工具命名空间靠它**（`mcp__<id>__<工具名>`）——
 * 用户改的显示名（可以是中文）一旦进工具名会被 OpenAI 的命名规范拒掉，所以路由只认 id。
 * token 不在这个结构里：它走 Keystore 加密按 id 单独存（与 ApiConfig 的 Key 同一纪律）。
 */
data class McpServerConfig(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
) {
    val namespacedPrefix: String get() = "mcp__${id}__"
}

/**
 * `mcp_servers` 键的编解码（JSON 数组，纯 JVM 可测）。
 * 与 ApiProfileCodec 同一思路：整张表塞一个 DataStore 键，服务器数量小，不值得开 Room 表。
 */
object McpServerCodec {

    private val random = SecureRandom()
    private val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray()

    /** 生成不与现有冲突的 6 位 id（足够短，工具名总共只有 64 字符预算） */
    fun newId(existing: Collection<String>): String {
        while (true) {
            val id = (1..6).map { ID_ALPHABET[random.nextInt(ID_ALPHABET.size)] }.joinToString("")
            if (id !in existing) return id
        }
    }

    fun encode(list: List<McpServerConfig>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("url", s.url)
                    .put("enabled", s.enabled),
            )
        }
        return arr.toString()
    }

    /** 坏行跳过不炸（DataStore 文本可能被手工改过）；id 重复的只留第一条 */
    fun decode(raw: String): List<McpServerConfig> {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<McpServerConfig>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            val url = o.optString("url").trim()
            if (id.isEmpty() || url.isEmpty() || !seen.add(id)) continue
            out += McpServerConfig(
                id = id,
                name = o.optString("name").trim().ifBlank { url },
                url = url,
                enabled = o.optBoolean("enabled", true),
            )
        }
        return out
    }

    /**
     * 工具名消毒（OpenAI 规范 `^[a-zA-Z0-9_-]{1,64}$`）：MCP 服务器的工具名
     * 可能带点号/冒号/中文，逐个替换成 `_` 再截断。消毒后撞名由调用方（McpManager）
     * 追加序号区分——这里只管"单个名字合法"。
     */
    fun sanitizeToolName(raw: String): String {
        val cleaned = raw.map { if (it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-') it else '_' }
            .joinToString("")
            .trim('_')
            .ifBlank { "tool" }
        return cleaned.take(48) // 留 16 字符给 mcp__<id>__ 前缀（6 位 id + 7 字符骨架）
    }
}
