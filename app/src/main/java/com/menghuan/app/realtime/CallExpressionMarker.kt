package com.menghuan.app.realtime

/**
 * 通话里的表情标记 `[表情:名]`（v0.69.0）。
 *
 * ## 为什么走标记而不是 function calling
 * 与 `[生图:…]`、`【语音条】` 是同一条判断（AGENTS.md §五.4.2）：
 * OpenAI 兼容端点对 `tools` 的支持并不一致，标记协议最稳，而且**长在这个形状上就顺带能测**。
 *
 * ## 为什么剥标记这件事必须存在
 * 通话链路里文本有三条出路：TTS 念出来、字幕显示、进 `turns` 当 AI 说过的话。
 * 标记没剥干净的话，用户会听见她念"表情 脸红"，字幕里也挂着一串方括号 ——
 * 这是**当场就能看见的错**，所以一律在进这三条路之前就剥掉。
 */
object CallExpressionMarker {

    /**
     * 名字长度上限：方括号里塞进整句话是模型跑偏的典型形状（跑偏时别把脏东西带进渲染层）。
     * 超长的方括号段**原样留在正文**、不当表情处理。
     */
    private const val MAX_NAME_LEN = 24

    /**
     * 名字**允许为空**：模型跑偏吐出 `[表情:]` 时，标记本身必须被吃掉 ——
     * 否则会原样进 TTS，用户当场听见"方括号 表情 冒号 方括号"。
     * （第一版这里写的是 `{1,24}`，注释承诺"空名字吃掉"而正则不认，是单测抓出来的。）
     */
    private val MARKER = Regex("\\[表情:([^\\]]{0,$MAX_NAME_LEN})\\]")

    /** 标记的起头，用于识别"模型正在往外吐、还没收口"的半个标记 */
    private const val MARKER_HEAD = "[表情:"

    /**
     * **只用于字幕显示**（纯函数）：剥掉已成形的标记，并把尾巴上还没收口的半个标记藏起来。
     *
     * 为什么显示要走这条路而不是复用 [parse]：字幕是跟着流式增量每 200ms 刷一次的，
     * 模型吐 `[表情:脸` 的那一刻，按 parse 处理会在那半秒里把"[表情:脸"亮在屏幕上 ——
     * 用户看到的就是一串莫名其妙的方括号。
     */
    fun stripForDisplay(raw: String): String {
        val closed = MARKER.replace(raw, "")
        val open = closed.lastIndexOf(MARKER_HEAD)
        val trimmed = if (open >= 0 && closed.indexOf(']', open) < 0) closed.substring(0, open) else closed
        return trimmed.replace(Regex("[ \\t]{2,}"), " ").trimEnd()
    }

    /**
     * 一次解析的结果：[text] 是剥掉所有标记后的正文（可能为空串 = 这一句只带了表情），
     * [names] 是按出现顺序去重后的表情名。
     */
    data class Parsed(val text: String, val names: List<String>) {
        val hasMarker: Boolean get() = names.isNotEmpty()
    }

    /** 解析并剥离（纯函数，JVM 单测口径）。 */
    fun parse(raw: String): Parsed {
        val names = ArrayList<String>(2)
        val text = MARKER.replace(raw) { m ->
            val name = m.groupValues[1].trim()
            // 空名字（`[表情:]`）是模型跑偏，吃掉标记但不当事儿
            if (name.isNotEmpty() && name !in names) names += name
            ""
        }
        // 标记被抠掉后常留下多余空格；只收正文首尾与连续空格，不动句子内部的换行
        return Parsed(text.replace(Regex("[ \\t]{2,}"), " ").trim(), names)
    }

    /**
     * 给模型看的那一段（纯函数）。
     *
     * **候选清单为空时返回空串 —— 整段都不注入**。
     * 这一条是刻意的：清单空的时候告诉模型"你可以用表情"只会诱使它瞎编名字，
     * 而瞎编的名字在渲染层会被静默忽略，表现成"它标了表情但什么也没发生"。
     * （爱语那边同样有"模型加载完成 但无表情数据"这一分支。）
     */
    fun promptSection(names: List<String>): String {
        val list = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (list.isEmpty()) return ""
        return buildString {
            append("## 表情\n")
            append("你面前有 Live2D 形象，在回复里插入表情标记，用户会当场看到你换表情。\n")
            append("这张模型**只有**下面这些表情名，不许自己编别的：\n")
            list.forEach { append("- [表情:").append(it).append("]\n") }
            append("用法：标记跟在句子后面，一条回复最多一两个。"
                + "标记里的字不会被念出来、也不会显示给用户，所以正文要自己说完整，"
                + "不要靠标记表意，也不要把标记的名字重复念一遍。")
        }
    }
}
