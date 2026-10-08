package com.menghuan.app.data

/**
 * 动态标记 `[动态:内容]` / `[动态#帖子id:内容]`（v0.73.0）。
 *
 * ## 为什么用标记而不是 function calling
 * 与 `[生图:…]`、`【语音条】`、`[操作手机:…]`、`<add:…>` 同族（§五.4.2 / §零.4）：
 * OpenAI 兼容端点对 `tools` 的支持并不一致，标记协议各家都能跑，而且**看得懂日志**。
 *
 * ## 两种标记为什么要分开写
 * 发新帖和回帖是两件不同的事，id 只能来自 App（模型自己编不出也不会编一条存在的 id）。
 * 所以回帖必须带 `#id`，而这个 id 由 [com.menghuan.app.llm.OpenAiClient] 的
 * **易变上下文**（[promptSection]）每轮现拼给模型看 —— 模型手里有 id 才谈得上回帖。
 *
 * ## 标记绝不能进用户可见的文字
 * 所以 [parse] 一定把它剥干净（同 [MemoryMarker] 的硬规定）：
 * 屏幕上出现 `[动态:今天下雨了]` 就是把内部协议漏给了用户。
 */
object MomentMarker {

    /**
     * 认 `[动态:内容]`，也认全角冒号 `[动态：内容]`（模型会把冒号"纠正"成全角，
     * 只认半角会让功能时不时失灵而且看不出原因 —— [MemoryMarker] 同族踩过）。
     * 方括号本身也容忍模型写成全角 `［动态:…］` 吗？**不容忍**：全角方括号在中文正文里
     * 是合法的强调写法，认它等于把用户想看的括号内容当指令吞掉。
     */
    private val POST = Regex("\\[\\s*动态\\s*[:：]\\s*([^\\]]{1,300})]")

    /**
     * 回帖 `[动态#12:内容]`。id 只认 1–12 位数字：
     * 再长就已经不是本机 SQLite 自增能到的量级，多半是模型把别处的编号抄来了，
     * 那种帖子必然不存在，宁可不当标记（留在正文里让用户看见）也不要静默丢掉一句话。
     */
    private val REPLY = Regex("\\[\\s*动态#(\\d{1,12})\\s*[:：]\\s*([^\\]]{1,300})]")

    /** 只有标记的那一整行（连行尾换行一起吃掉，否则正文里留空行；理由见 [MemoryMarker.LINE_MARKER]） */
    private val LINE_POST = Regex("(?m)^[ \t]*\\[\\s*动态\\s*[:：][^\\]]{1,300}][ \t]*\r?\n")
    private val LINE_REPLY = Regex("(?m)^[ \t]*\\[\\s*动态#\\d{1,12}\\s*[:：][^\\]]{1,300}][ \t]*\r?\n")

    /**
     * @param posts 要发的新帖正文（按出现顺序，已去空白）
     * @param replies 要回的路径：`帖子id → 内容`
     * @param cleanText 剥掉所有标记后的正文 = 要显示、要落库的那段话
     */
    data class Decision(
        val posts: List<String>,
        val replies: List<Pair<Long, String>>,
        val cleanText: String,
    ) {
        val wantsAnything: Boolean get() = posts.isNotEmpty() || replies.isNotEmpty()
    }

    fun parse(raw: String): Decision {
        val posts = ArrayList<String>(1)
        POST.findAll(raw).forEach { m ->
            val text = m.groupValues[1].trim()
            if (text.isNotEmpty()) posts += text
        }
        val replies = ArrayList<Pair<Long, String>>(1)
        REPLY.findAll(raw).forEach { m ->
            val id = m.groupValues[1].toLongOrNull() ?: return@forEach
            val text = m.groupValues[2].trim()
            if (text.isNotEmpty()) replies += id to text
        }
        // 先整行吃掉"只有标记的那一行"，再吃掉夹在正文里的（顺序反了会留空行）
        val cleaned = POST.replace(LINE_POST.replace(raw, ""), "")
            .let { REPLY.replace(LINE_REPLY.replace(it, ""), "") }
            .replace(Regex("[ \t]{2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return Decision(posts, replies, cleaned)
    }

    /**
     * 给模型看的**固定**说明 —— 拼在 system prompt 末尾（与 [MemoryMarker.promptSection] 同位置）。
     *
     * ⚠️ 这里**不放"可以回复哪些帖子"**：那份名单每轮都变，进 system prompt 就是
     * 让整段前缀每轮作废（AGENTS.md §零.9 的缓存纪律）。名单走 [feedBlock]，
     * 由易变上下文贴到最后一条用户消息上。
     *
     * 三条措辞都是实测会做错的地方：
     * - **「不要为了发而发」**：不加这句，模型每轮都冒出一条无意义的动态，时间线两天就成垃圾场；
     * - **「正文里不要把动态内容再念一遍」**：否则屏幕上会出现"我发了条动态（今天下雨了）"；
     * - **回帖要带 App 给过的编号**：不约束这一点它会自己编一个号，那条回复就静默丢了。
     */
    fun promptSection(): String =
        "## 动态\n" +
            "除了聊天，你还有一条「动态」时间线（像朋友圈/推特），用户会去看，也能在上面回你。\n" +
            "发新帖：[动态:一句话]　　回复某条帖子：[动态#帖子id:一句话]\n" +
            "**只在真的有东西可说时发**：你的心情、正在做的事、看到用户说了什么想接的那一句。" +
            "不要为了刷存在感而发，时间线塞满废话比空着更糟。\n" +
            "只有 App 给过你的编号才能用来回复（编号列在每轮最后的实时上下文里）；" +
            "没有编号可回时就不要写 #，凭空编一个编号那条回复会静默丢掉。\n" +
            "标记会被 App 剥掉、不显示给用户，所以**正文里不要把动态的内容再念一遍**。"

    /**
     * 易变上下文里那份「你手上有哪些帖子可以回」（v0.73.0）。
     *
     * @param lines 调用方用 [feedLine] 拼好的行
     *
     * ⚠️ **空列表返回空串**：不塞一个空标题进上下文 —— 那既是白花的 token，
     * 又会让模型以为"有个地方可以回"，然后编一个编号出来。
     */
    fun feedBlock(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        return "## 动态时间线现状\n可以回复的帖子（用行首的编号当 id，最新在前）：\n" +
            lines.joinToString("\n") { "· $it" }
    }

    /** 列表项的固定形状：`7 小满：今天下了很大的雨`（id + 作者 + 截断正文） */
    fun feedLine(id: Long, author: String, text: String): String =
        "$id $author：${if (text.length <= 40) text else text.take(40) + "…"}"
}
