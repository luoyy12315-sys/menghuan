package com.menghuan.app.data

/**
 * AI 语音条标记协议（v0.50.0）：模型在回复里写 `【语音条】` 表示"这条我想用说的"，
 * App 剥掉标记、把剩下的文字合成语音，落成一条可播放的语音气泡。
 *
 * 与 `[生图:…]`、`[操作手机:…]` 同族——**用标记而不是 function calling**，
 * 因为 DeepSeek / 智谱 / 通义这些 OpenAI 兼容端点对 `tools` 的支持并不一致，标记最稳。
 *
 * 标记**绝不能进用户可见文字**，所以 [parse] 一定会把它剥干净（见 [Decision.cleanText]）。
 */
object VoiceNoteMarker {

    /**
     * 一并认下半角方括号写法：模型经常把中文全角括号自动"纠正"成半角，
     * 只认全角会让语音条时不时失灵，而这种失灵用户看不出原因。
     */
    private val VARIANTS = listOf("【语音条】", "[语音条]")

    /**
     * @param wantsVoice 是否要合成语音
     * @param cleanText 剥掉标记、并收拾好空行的正文（就是要合成/要显示的那段话）
     */
    data class Decision(val wantsVoice: Boolean, val cleanText: String)

    fun parse(raw: String): Decision {
        val wantsVoice = VARIANTS.any { raw.contains(it) }
        if (!wantsVoice) return Decision(false, raw)
        var text = raw
        VARIANTS.forEach { text = text.replace(it, "") }
        return Decision(true, tidy(text))
    }

    /** 标记常单独占一行，剥完会留下空行；连续空行压成一个、首尾留白去掉 */
    private fun tidy(text: String): String = text
        .replace(Regex("[ \\t]+\\n"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
