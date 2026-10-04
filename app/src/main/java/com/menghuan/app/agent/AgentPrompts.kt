package com.menghuan.app.agent

import org.json.JSONObject

/**
 * Agent 决策 prompt（照抄 AppAgent 四段式思路：观察 → 思考 → 行动，压缩成"只回 JSON 动作"）。
 * 解析与 ProfileExtractor 同款宽容策略：截取首个 { 到末个 }，失败返回 null（调用方重试一次）。
 */
object AgentPrompts {

    const val SYSTEM: String =
        "你是手机操作 Agent。根据屏幕元素编号清单决定下一步动作，替用户完成目标。\n" +
            "每一步只输出一个 JSON（五选一）：\n" +
            "{\"action\":\"tap\",\"element\":3}\n" +
            "{\"action\":\"text\",\"element\":4,\"value\":\"要输入的文字\"}\n" +
            "{\"action\":\"swipe\",\"dir\":\"up\"}\n" +
            "{\"action\":\"back\"}\n" +
            "{\"action\":\"done\",\"summary\":\"一句话总结结果\"}\n" +
            "规则：\n" +
            "1. 先观察当前屏幕：目标可能已经完成，重复内容不要再次输入。\n" +
            "2. 优先点击 clickable 元素；输入框用 text 填入后再点提交/发送按钮。\n" +
            "3. 找不到目标可 swipe down 翻列表，或 back 退回上一页换路径。\n" +
            "4. 连续两步执行了相同动作说明卡住了：直接 done，在 summary 里说明卡在哪里。\n" +
            "5. 只输出 JSON，不要输出任何其他文字。"

    /** LLM 决定的单步动作 */
    data class Action(
        val type: String,       // tap / text / swipe / back / done
        val element: Int? = null,
        val value: String = "",
        val dir: String = "",   // up / down
        val summary: String = "",
    )

    /** 模型输出 → 动作；无 action 字段或 JSON 破损返回 null */
    fun parseAction(raw: String): Action? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val o = JSONObject(raw.substring(start, end + 1))
            val type = o.optString("action").trim().lowercase()
            if (type.isEmpty()) return@runCatching null
            Action(
                type = type,
                element = if (o.has("element")) o.optInt("element", -1).takeIf { it >= 0 } else null,
                value = o.optString("value"),
                dir = o.optString("dir").trim().lowercase(),
                summary = o.optString("summary").trim(),
            )
        }.getOrNull()
    }

    /** 单步请求 prompt：目标 + 当前屏幕清单 + 最近动作历史（防重复输入） */
    fun userPrompt(goal: String, screenText: String, history: List<String>): String = buildString {
        append("目标：").append(goal).append("\n\n当前屏幕元素：\n").append(screenText)
        if (history.isNotEmpty()) {
            append("\n\n已执行动作（旧→新）：\n")
            history.takeLast(8).forEach { append("- ").append(it).append('\n') }
        }
        append("\n下一步动作 JSON：")
    }

    /** 敏感动作围栏：元素命中支付/删除/发送类关键词 → 执行前需要用户确认 */
    fun isSensitive(element: UiElement): Boolean {
        val text = element.text + element.contentDesc
        return SENSITIVE_KEYWORDS.any(text::contains)
    }

    // ===== 对话内触发协议（v0.37.0）：与生图 [生图:…] 同构的单行标记 =====

    /**
     * 解析 AI 回复里的 `[操作手机:目标]` 标记（纯函数，可单测）。
     * 解析策略与 ImageGenerationClient.findPromptIn 同款：首个标记起点 + 其后第一个 `]`。
     */
    fun findGoalIn(raw: String): String? {
        val start = raw.indexOf(MARK_START)
        if (start < 0) return null
        val end = raw.indexOf(MARK_END, start + MARK_START.length)
        if (end < 0) return null
        return raw.substring(start + MARK_START.length, end).trim().ifBlank { null }
    }

    /** 从回复里剥掉操控标记，只留要展示的文字（纯函数，可单测）；标记独占一行时换行一并剥掉 */
    fun stripMarker(raw: String): String {
        val start = raw.indexOf(MARK_START)
        if (start < 0) return raw.trim()
        val end = raw.indexOf(MARK_END, start + MARK_START.length)
        if (end < 0) return raw.substring(0, start).trim()
        val before = raw.substring(0, start)
        val after = raw.substring(end + MARK_END.length)
        // 标记前后都是换行（独占一行）→ 吃掉一个换行，避免剥完留出空行
        val merged = if (before.endsWith("\n") && after.startsWith("\n")) {
            before + after.drop(1)
        } else {
            before + after
        }
        return merged.trim()
    }

    const val MARK_START = "[操作手机:"
    const val MARK_END = "]"

    private val SENSITIVE_KEYWORDS = listOf(
        "支付", "付款", "确认订单", "提交订单", "购买", "转账",
        "删除", "清空", "卸载", "发送",
    )
}
