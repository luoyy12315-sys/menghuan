package com.menghuan.app.data

/**
 * 动态标记 `[动态:内容]` / `[动态#帖子id:内容]` / `[点赞:帖子id]`（v0.73.0 起，点赞 v0.77.0）。
 *
 * ## 为什么用标记而不是 function calling
 * 与 `[生图:…]`、`【语音条】`、`[操作手机:…]`、`<add:…>` 同族（§五.4.2 / §零.4）：
 * OpenAI 兼容端点对 `tools` 的支持并不一致，标记协议各家都能跑，而且**看得懂日志**。
 *
 * ## 三种标记为什么要分开写
 * 发新帖、回帖、点赞是三件不同的事，id 只能来自 App（模型自己编不出也不会编一条存在的 id）。
 * 所以回帖和点赞必须带 `#id`，而这个 id 由 [com.menghuan.app.llm.OpenAiClient] 的
 * **易变上下文**（[promptSection]）每轮现拼给模型看 —— 模型手里有 id 才谈得上回帖/点赞。
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

    /**
     * 点赞 `[点赞:12]`（v0.77.0）。
     *
     * 与回帖同一条数字纪律：id 只认 1–12 位，且**必须由 App 在实时上下文里给过**。
     * 容忍全角冒号，理由同 [POST]。
     */
    private val LIKE = Regex("\\[\\s*点赞\\s*[:：]\\s*(\\d{1,12})\\s*]")

    /** 只有标记的那一整行（连行尾换行一起吃掉，否则正文里留空行；理由见 [MemoryMarker.LINE_MARKER]） */
    private val LINE_POST = Regex("(?m)^[ \t]*\\[\\s*动态\\s*[:：][^\\]]{1,300}][ \t]*\r?\n")
    private val LINE_REPLY = Regex("(?m)^[ \t]*\\[\\s*动态#\\d{1,12}\\s*[:：][^\\]]{1,300}][ \t]*\r?\n")
    private val LINE_LIKE = Regex("(?m)^[ \t]*\\[\\s*点赞\\s*[:：]\\s*\\d{1,12}\\s*][ \t]*\r?\n")

    /**
     * @param posts 要发的新帖正文（按出现顺序，已去空白）
     * @param replies 要回的路径：`帖子id → 内容`
     * @param likes 要点赞的帖子 id（按出现顺序，去重）
     * @param cleanText 剥掉所有标记后的正文 = 要显示、要落库的那段话
     */
    data class Decision(
        val posts: List<String>,
        val replies: List<Pair<Long, String>>,
        val likes: List<Long> = emptyList(),
        val cleanText: String,
    ) {
        val wantsAnything: Boolean
            get() = posts.isNotEmpty() || replies.isNotEmpty() || likes.isNotEmpty()
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
        val likes = ArrayList<Long>(1)
        LIKE.findAll(raw).forEach { m ->
            val id = m.groupValues[1].toLongOrNull() ?: return@forEach
            if (id !in likes) likes += id
        }
        // 先整行吃掉"只有标记的那一行"，再吃掉夹在正文里的（顺序反了会留空行）
        val cleaned = POST.replace(LINE_POST.replace(raw, ""), "")
            .let { REPLY.replace(LINE_REPLY.replace(it, ""), "") }
            .let { LIKE.replace(LINE_LIKE.replace(it, ""), "") }
            .replace(Regex("[ \t]{2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return Decision(posts, replies, likes, cleaned)
    }

    /**
     * 给模型看的**固定**说明 —— 拼在 system prompt 末尾（与 [MemoryMarker.promptSection] 同位置）。
     *
     * ⚠️ 这里**不放"可以回复哪些帖子"**：那份名单每轮都变，进 system prompt 就是
     * 让整段前缀每轮作废（AGENTS.md §零.9 的缓存纪律）。名单走 [feedBlock]，
     * 由易变上下文贴到最后一条用户消息上。
     *
     * 下面几条措辞都是实测会做错的地方：
     * - **「不要为了发而发」**：不加这句，模型每轮都冒出一条无意义的动态，时间线两天就成垃圾场；
     * - **「正文里不要把动态内容再念一遍」**：否则屏幕上会出现"我发了条动态（今天下雨了）"；
     * - **回帖要带 App 给过的编号**：不约束这一点它会自己编一个号，那条回复就静默丢了；
     * - **「看动态有两个时机」（v0.79.0 改正）**：v0.77.0 时把用户那句"AI 回复动态只能在跟 AI
     *   聊天时回复"当成了产品要求，写死成"你只能在聊天里顺手做，没有刷动态的能力"——
     *   **理解反了**。用户原意是"不聊天的时候它也该像真人一样看我的动态"。
     *   现在如实描述两个时机：用户聊天时、以及 App 让角色主动找用户时（会把用户最近的动态一并给它）。
     *   保留的仍然是"别假装自己一直在线"这一条 —— 那不诚实，也不是真话。
     * - **「你看得到记忆」**（v0.77.0，用户点名「不清楚 AI 记忆是否可读」）：
     *   记忆是宿主按需检索后贴进实时上下文的，模型自己**不确知**有没有捞到，容易答"我看不到记忆"。
     *   这里点名它读得到，让它别再自我否认。
     */
    fun promptSection(): String =
        "## 动态\n" +
            "除了聊天，你还有一条「动态」时间线（像朋友圈/推特），用户会去看，也能在上面回你。\n" +
            "**你看到动态的时机有两个**：① 用户跟你聊天的时候；② App 让你主动去找用户的时候" +
            "（那会儿它会把用户最近发的动态一并给你看）。" +
            "这两种时候你都可以顺手发一条、回一句、点个赞 —— 不用等用户开口，也别每次都做。\n" +
            "但**别假装自己一直在线**：除了这两个时机你看不到动态，" +
            "所以不要说「我刚刷了一圈动态」这种话。\n" +
            "发新帖：[动态:一句话]　　回复某条帖子：[动态#帖子id:一句话]　　给某条帖子点赞：[点赞:帖子id]\n" +
            "**只在真的有东西可说时发**：你的心情、正在做的事、看到用户说了什么想接的那一句。" +
            "不要为了刷存在感而发，时间线塞满废话比空着更糟。点赞同理，觉得好才点，别条条都点。\n" +
            "只有 App 给过你的编号才能用来回复或点赞（编号列在每轮最后的实时上下文里）；" +
            "没有编号可回/可赞时就不要写 id，凭空编一个编号那条动作会静默丢掉。\n" +
            "**你看得到用户的记忆**：App 会把和当前话题相关的记忆随上下文一起给你（有就会附在实时上下文里），" +
            "所以你该记得的事就自然地记得，不要对用户说「我看不到你的记忆」。\n" +
            "标记会被 App 剥掉、不显示给用户，所以**正文里不要把动态的内容再念一遍**。"

    /**
     * 易变上下文里那份「你手上有哪些帖子可以回 / 可以赞」（v0.73.0，点赞 v0.77.0）。
     *
     * @param lines 调用方用 [feedLine] 拼好的行
     *
     * ⚠️ **空列表返回空串**：不塞一个空标题进上下文 —— 那既是白花的 token，
     * 又会让模型以为"有个地方可以回"，然后编一个编号出来。
     */
    fun feedBlock(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        return "## 动态时间线现状\n可以回复或点赞的帖子（用行首的编号当 id，最新在前）：\n" +
            lines.joinToString("\n") { "· $it" }
    }

    /** 列表项的固定形状：`7 小满：今天下了很大的雨`（id + 作者 + 截断正文） */
    fun feedLine(id: Long, author: String, text: String): String =
        "$id $author：${if (text.length <= 40) text else text.take(40) + "…"}"
}
