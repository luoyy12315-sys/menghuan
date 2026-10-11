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
 * 所以回帖和点赞必须带 `#id`，而这个 id 由**主动关心链**每轮现拼给它看
 * （[com.menghuan.app.data.ProactiveChat.momentsFeedBlock] 用 [feedBlock] / [feedLine] 拼那份名单）
 * —— 模型手里有 id 才谈得上回帖/点赞。
 *
 * ## 现在只有主动关心链在用它
 * v0.73.0 起聊天链也会顺手回帖/点赞，但用户后来拍板「**动态只存于主动关心那条链**」：
 * 聊天链原本的协议段（`promptSection`）与每轮名单块都已整块移除 —— 那条链现在只剩一步
 * **防御性剥除**（[parse] 的结果里只取 `cleanText`，`posts` / `replies` / `likes` 一律不落库），
 * 为的是万一模型凭旧习惯写了标记，也不至于把内部协议漏到用户眼前。
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
     * 易变上下文里那份「你手上有哪些帖子可以回 / 可以赞」（v0.73.0，点赞 v0.77.0）。
     *
     * @param lines 调用方用 [feedLine] 拼好的行
     *
     * ⚠️ **空列表返回空串**：不塞一个空标题进上下文 —— 那既是白花的 token，
     * 又会让模型以为"有个地方可以回"，然后编一个编号出来。
     *
     * v0.85.0：标题下面补一句「行首标了『你自己』的就是你发的」——
     * 用户点名「AI 发动态分得清是谁发的吗」。以前名单只有名字，自己的帖和别人的长得一样，
     * 模型可能给自己的帖子点赞/回帖（屏幕上就是"自己夸自己"）。
     * **本版只把信息标清、不拦落库**（用户拍板：标注即可，不改行为）。
     */
    fun feedBlock(lines: List<String>): String {
        if (lines.isEmpty()) return ""
        return "## 动态时间线现状\n" +
            "有这些帖子可以回复或点赞（用行首的编号当 id，最新在前）。" +
            "**每条都标了是谁发的**：`你自己` = 你发的，`用户` = 用户本人发的，其余是别的角色的名字。\n" +
            "**你自己发的那些不要回、也不要点赞**（那等于自问自答）；要回要赞的是用户或别人的。\n" +
            lines.joinToString("\n") { "· $it" }
    }

    /**
     * 列表项的固定形状：`7 小满：今天下了很大的雨`（id + 作者 + 截断正文）。
     *
     * v0.85.0 加了 [self]/[otherAi] 两个标记位，让"谁发的"一眼可辨：
     * - [self] = true → 作者名后面缀「（你自己）」；
     * - [otherAi] = true → 后缀「（另一位角色）」—— 名字可能重名或看不出是人是 AI，
     *   点明一下模型才不会把别的角色当成用户。
     *
     * 两者都 false = 就是用户本人或未标注，维持老形状（[ProactiveChat] 那份只给用户本人的帖，不需要标）。
     */
    fun feedLine(id: Long, author: String, text: String, self: Boolean = false, otherAi: Boolean = false): String {
        val tag = when {
            self -> "（你自己）"
            otherAi -> "（另一位角色）"
            else -> ""
        }
        return "$id $author$tag：${if (text.length <= 40) text else text.take(40) + "…"}"
    }
}
