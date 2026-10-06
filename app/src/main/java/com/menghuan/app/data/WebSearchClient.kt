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
        else -> "博查"
    }

    /** 该家控制台官网（必须是**查证过的官方地址**） */
    fun consoleUrlOf(provider: String): String = when (provider) {
        SEARCH_TAVILY -> "https://tavily.com"
        else -> "https://open.bochaai.com"
    }

    /** 该家接口地址（设置页"测试连接"也会显示） */
    fun endpointOf(provider: String): String = when (provider) {
        SEARCH_TAVILY -> "https://api.tavily.com/search"
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
    ): List<Hit> = runCatching { searchOrThrow(provider, apiKey, query, count) }
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
    ): List<Hit> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "没填 API Key" }
        require(query.isNotBlank()) { "搜索关键词为空" }
        val body = if (provider == SEARCH_TAVILY) {
            JSONObject()
                .put("query", query)
                .put("topic", "news")
                .put("time_range", "day")
                .put("max_results", count)
                .put("include_answer", false)
                .put("include_raw_content", false)
                .toString()
        } else {
            JSONObject()
                .put("query", query)
                .put("summary", true)
                .put("freshness", "oneDay")
                .put("count", count)
                .toString()
        }
        val request = Request.Builder()
            .url(endpointOf(provider))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.callWithTlsFallback(request) { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}：${text.take(200)}")
            val json = JSONObject(text)
            if (provider == SEARCH_TAVILY) parseTavily(json) else parseBocha(json)
        }
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
