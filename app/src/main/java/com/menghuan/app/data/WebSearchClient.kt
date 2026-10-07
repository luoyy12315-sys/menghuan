package com.menghuan.app.data

import com.menghuan.app.llm.NetworkStack
import com.menghuan.app.llm.callWithTlsFallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 联网搜索（v0.53.5）：给「定时主动消息」在动笔前先查一遍最新的，
 * 这样"今天的新闻"才是真的今天的，而不是模型的旧记忆。
 *
 * 两家 Key 都要用户自己申请（地址是查证过的官方地址，不要改成别的域名）：
 * - **博查**（国内，中文/新闻更对味）：https://open.bochaai.com —— 微信扫码 → API Key 管理 → 创建
 * - **Tavily**（国际）：https://tavily.com
 *
 * 只干**搜索**这一件事：标题 + 摘要原样交给模型去组织语言，不在这里做摘要或改写。
 */
object WebSearchClient {

    data class Hit(val title: String, val url: String, val snippet: String)

    /** 展示名（设置页与手册共用） */
    fun labelOf(provider: String): String = when (provider) {
        SEARCH_TAVILY -> "Tavily"
        SEARCH_CUSTOM -> "自定义接口"
        else -> "博查"
    }

    /**
     * 该家控制台官网（必须是**查证过的官方地址**，见 AGENTS §零.6）。
     * 自定义这一家没有官网可言，返回空串：设置页据此**不显示**"去 X 注册"那行，
     * 免得编一个看起来合理的域名给用户。
     */
    fun consoleUrlOf(provider: String): String = when (provider) {
        SEARCH_TAVILY -> "https://tavily.com"
        SEARCH_CUSTOM -> ""
        else -> "https://open.bochaai.com"
    }

    /**
     * 该家接口地址（设置页"测试连接"也会显示）。
     * [customEndpoint] 只在 provider = [SEARCH_CUSTOM] 时用到：那是用户自己填的完整 URL，
     * App 不猜任何域名。
     */
    fun endpointOf(provider: String, customEndpoint: String = ""): String = when (provider) {
        SEARCH_TAVILY -> "https://api.tavily.com/search"
        SEARCH_CUSTOM -> customEndpoint.trim()
        else -> "https://api.bochaai.com/v1/web-search"
    }

    /**
     * 搜一次，按"最近一天"过滤。**任何失败都返回空列表** ——
     * 调用方会把"没搜到"如实说出来，绝不编内容。
     */
    suspend fun search(
        provider: String,
        apiKey: String,
        query: String,
        count: Int = 8,
        customEndpoint: String = "",
    ): List<Hit> = runCatching { searchOrThrow(provider, apiKey, query, count, customEndpoint) }
        .getOrDefault(emptyList())

    /**
     * 同一条搜索链路，但**失败直接抛出、带服务端原文**（v0.56.5）。
     *
     * 「联网搜索」设置页的**测试按钮**用它：测试必须能说出"为什么没结果"
     * （Key 不对 / 额度用完 / 域名不通），而 [search] 把所有失败都吞成空列表，
     * 拿它做测试只能显示一句没法排查的"没搜到"。
     */
    suspend fun searchOrThrow(
        provider: String,
        apiKey: String,
        query: String,
        count: Int = 8,
        /** 只有 [SEARCH_CUSTOM] 用得到：用户自己填的完整接口地址 */
        customEndpoint: String = "",
    ): List<Hit> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "没填 API Key" }
        require(query.isNotBlank()) { "搜索关键词为空" }
        val endpoint = endpointOf(provider, customEndpoint)
        if (provider == SEARCH_CUSTOM) {
            // 自定义这一家**地址必须由用户填**，缺了就是配置问题，不许静默返回空结果
            require(endpoint.isNotBlank()) { "没填自定义接口地址" }
            require(endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
                "自定义接口地址要以 http:// 或 https:// 开头"
            }
        }
        val body = when (provider) {
            SEARCH_TAVILY -> JSONObject()
                .put("query", query)
                .put("topic", "news")
                .put("time_range", "day")
                .put("max_results", count)
                .put("include_answer", false)
                .put("include_raw_content", false)
                .toString()
            // 通用形状：绝大多数搜索中转都认这两个字段。
            // 顺带把 count 也发一份——有些家抄的是博查的字段名，多带一个不亏，
            // 不认的字段一般被忽略（比只发一种字段命中率高一档）。
            SEARCH_CUSTOM -> JSONObject()
                .put("query", query)
                .put("max_results", count)
                .put("count", count)
                .toString()
            else -> JSONObject()
                .put("query", query)
                .put("summary", true)
                .put("freshness", "oneDay")
                .put("count", count)
                .toString()
        }
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.callWithTlsFallback(request) { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}：${text.take(200)}")
            val json = JSONObject(text)
            when (provider) {
                SEARCH_TAVILY -> parseTavily(json)
                SEARCH_CUSTOM -> parseGeneric(json).also {
                    if (it.isEmpty()) {
                        // 一句都没解析出来**不等于**这家没结果：更可能是字段名不在兜底清单里。
                        // 把响应开头带回去，用户/开发者一眼能看出该加哪个字段名。
                        error("没从返回里解析出结果（字段名不在兜底清单里）。返回开头：${text.take(180)}")
                    }
                }
                else -> parseBocha(json)
            }
        }
    }

    /**
     * 自定义接口的**多路兜底**解析：各家把结果数组叫不同名字，条目里的字段名也一样。
     *
     * 数组键按常见写法依次试：`webPages`(博查/必应) → `results`(Tavily) → `data` → `items` → `documents`；
     * 条目字段 title 试 `name`/`title`/`siteName`，url 试 `url`/`link`/`displayUrl`，
     * 摘要试 `snippet`/`summary`/`content`/`text`/`description`。
     * 三样全空的条目丢掉（拿它喂模型只会让模型编）。
     */
    private fun parseGeneric(json: JSONObject): List<Hit> {
        val arrayNames = listOf("webPages", "results", "data", "items", "documents", "values")
        val titleNames = listOf("name", "title", "siteName")
        val urlNames = listOf("url", "link", "displayUrl")
        val snippetNames = listOf("snippet", "summary", "content", "text", "description")
        for (key in arrayNames) {
            val arr = json.optJSONArray(key) ?: findNestedArray(json, key) ?: continue
            val out = ArrayList<Hit>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = firstString(o, titleNames)
                val url = firstString(o, urlNames)
                val snippet = firstString(o, snippetNames)
                if (title.isBlank() && url.isBlank() && snippet.isBlank()) continue
                out += Hit(title.ifBlank { url }, url, snippet)
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    /** `{data:{webPages:[…]}}` 这种套一层的写法：在对象值里再找一层同名数组 */
    private fun findNestedArray(json: JSONObject, key: String): org.json.JSONArray? {
        val keys = json.keys()
        while (keys.hasNext()) {
            val child = json.optJSONObject(keys.next()) ?: continue
            val arr = child.optJSONArray(key)
            if (arr != null) return arr
        }
        return null
    }

    private fun firstString(o: JSONObject, names: List<String>): String {
        for (n in names) {
            val v = o.optString(n, "")
            if (v.isNotBlank()) return v.trim()
        }
        return ""
    }

    /** 拼成给模型看的文本块；没有结果返回空串，让调用方自己决定怎么措辞 */
    fun toPromptBlock(hits: List<Hit>, providerLabel: String): String {
        if (hits.isEmpty()) return ""
        return buildString {
            append("【").append(providerLabel).append(" 刚搜到的今日结果】\n")
            hits.forEachIndexed { i, h ->
                append(i + 1).append(". ").append(h.title)
                if (h.snippet.isNotBlank()) append("｜").append(h.snippet.take(200))
                append("\n")
            }
        }
    }

    private fun parseTavily(json: JSONObject): List<Hit> {
        val arr = json.optJSONArray("results") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").trim()
                if (title.isEmpty()) continue
                add(Hit(title, o.optString("url").trim(), o.optString("content").trim()))
            }
        }
    }

    private fun parseBocha(json: JSONObject): List<Hit> {
        val pages = json.optJSONObject("data")
            ?.optJSONObject("webPages")
            ?.optJSONArray("value")
            ?: return emptyList()
        return buildList {
            for (i in 0 until pages.length()) {
                val o = pages.optJSONObject(i) ?: continue
                val title = o.optString("name").trim()
                if (title.isEmpty()) continue
                val snippet = o.optString("summary").trim().ifEmpty { o.optString("snippet").trim() }
                add(Hit(title, o.optString("url").trim(), snippet))
            }
        }
    }

    private val http: OkHttpClient by lazy {
        NetworkStack.baseBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
