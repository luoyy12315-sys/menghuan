package com.menghuan.app.realtime

/**
 * LLM 流式文本切句器（纯函数，JVM 单测覆盖）。
 *
 * 管道通话的"首声延迟"取决于第一句话多早凑齐：句末标点出现立即切出可播放的句子；
 * 超长无句末标点的段落按逗号/顿号兜底（达到 [SOFT_LIMIT] 字后在最近的逗号处切），
 * 避免 AI 一口气输出几十字才出声。参考 huggingface/speech-to-speech 的
 * sentence-streaming TTS 做法（任务书 §零.1：先抄成熟案例）。
 */
object SentenceSplitter {

    private val HARD_ENDERS = charArrayOf('。', '！', '？', '；', '!', '?', '\n')
    private const val SOFT_LIMIT = 36

    /**
     * 从累计 buffer 中切出所有"可播放的完整句"。
     * @return (完整句列表, 剩余不足以成句的尾巴)——尾巴留在 buffer 里继续等增量。
     */
    fun drain(buffer: String): Pair<List<String>, String> {
        val sentences = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            when {
                c in HARD_ENDERS -> {
                    cut(buffer, start, i + 1, sentences)
                    start = i + 1
                }
                // 省略号整体（连写若干个）作为一句结尾
                c == '…' -> {
                    var j = i
                    while (j + 1 < buffer.length && buffer[j + 1] == '…') j++
                    cut(buffer, start, j + 1, sentences)
                    start = j + 1
                    i = j
                }
                // 英文句号只在后接空白/结尾时算句末（"3.5"、"ok.com" 不切碎）
                c == '.' && (i + 1 >= buffer.length || buffer[i + 1].isWhitespace()) -> {
                    cut(buffer, start, i + 1, sentences)
                    start = i + 1
                }
                // 超长句在逗号处兜底切分（数字千分位 "1,000" 因未达长度上限不受影响）
                (c == '，' || c == ',') && (i - start + 1) >= SOFT_LIMIT -> {
                    cut(buffer, start, i + 1, sentences)
                    start = i + 1
                }
            }
            i++
        }
        return sentences to buffer.substring(start).trimStart()
    }

    /** 只去首尾空白，**保留标点**——句尾的"，/。"给 TTS 天然的停顿提示 */
    private fun cut(buffer: String, from: Int, toExclusive: Int, out: MutableList<String>) {
        val s = buffer.substring(from, toExclusive).trim { it.isWhitespace() }
        if (s.isNotEmpty()) out += s
    }
}
