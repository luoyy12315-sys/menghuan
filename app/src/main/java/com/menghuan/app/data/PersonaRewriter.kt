package com.menghuan.app.data

import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.llm.OpenAiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 人设改写器（v0.36.0）：用户丢一段自由描述，AI 输出结构化的【名字】【身份】【设定词】三件套，
 * 一键填入人设编辑表单。
 *
 * **为什么单独建一个改写器而不复用应用助手**：
 * 应用助手（`AiPrompts.DEFAULT_ASSISTANT_PROMPT` 里的「人设适配协议」）输出的是三行文本，
 * 用户得手动复制粘贴；这里要求 LLM 输出**严格 JSON**，代码直接 parse 后填表单，体验更顺。
 * 两个入口指向同一个能力，用户可选：
 * - 在应用助手会话里聊 → 拿三行文本自己复制
 * - 在人设编辑页点「AI 优化设定词」→ 直接调这里，一键填入
 *
 * **改写 prompt 硬编码不给用户编**：JSON schema 一旦改坏，parse 直接崩、按钮变废。
 * 想调风格？改人设描述本身就行（"温柔的姐姐" vs "毒舌教练"），不需要改协议。
 */
object PersonaRewriter {

    /** 改写请求的 system prompt（硬编码；对话以用户描述作为单条 user 消息注入） */
    const val SYSTEM_PROMPT: String =
        "你是「梦欢」App 的人设改写器。用户会给你一段自由文本描述他想要的 AI 人设，" +
            "你的任务是把它改写成结构化的三件套。\n" +
            "严格只输出 JSON，格式：\n" +
            "{\"name\":\"...\",\"title\":\"...\",\"systemPrompt\":\"...\"}\n" +
            "字段要求：\n" +
            "- name: 2-6 字，符合角色气质（例：温柔的姐姐 → \"小柔\"；健身教练 → \"铁哥\"）\n" +
            "- title: 一句话点明角色定位，10-25 字（例：陪伴你日常起居的姐姐型 AI）\n" +
            "- systemPrompt: 完整可用的 system prompt，包含【语气与风格】【职责】【约束】三节，" +
            "控制在 200-400 字。用第二人称\"你\"直接称呼用户，避免\"作为 AI 助手\"这类出戏措辞。\n" +
            "规则：\n" +
            "1. 不要凭空编造用户没提的偏好；信息不足时按最常见的合理默认填充，" +
            "并在 systemPrompt 里保持一定通用性，方便用户后续微调。\n" +
            "2. 不要输出 JSON 以外的任何文字，不要加代码栅栏，不要加解释。\n" +
            "3. 全部用简体中文（systemPrompt 内允许必要的英文术语）。\n" +
            "4. 如果用户描述里包含明显的角色类型（陪伴/教练/朋友/恋人/长辈/咨询师等），" +
            "systemPrompt 要体现该类型的典型互动方式。"

    /** 改写结果：三件套 + 可选的头像 emoji 建议（LLM 有时会给，不给就用默认） */
    data class Result(
        val name: String,
        val title: String,
        val systemPrompt: String,
        val avatarEmoji: String? = null,
    )

    /**
     * 调 LLM 改写。任何失败返回 null（调用方给用户一句提示，不崩不抛）。
     * @param userInput 用户的自由文本描述（例如"我想要一个温柔的姐姐，会关心我的作息"）
     * @param config 当前生效的对话接口三件套
     */
    suspend fun rewrite(userInput: String, config: ApiConfig): Result? = withContext(Dispatchers.IO) {
        if (userInput.isBlank() || !config.isComplete) return@withContext null
        val sb = StringBuilder()
        val ok = runCatching {
            OpenAiClient(config.baseUrl, config.apiKey, config.model)
                .chat(
                    SYSTEM_PROMPT,
                    listOf(ChatMessage(conversationId = 0, isFromUser = true, content = userInput.trim())),
                )
                .collect { token -> sb.append(token) }
        }.isSuccess
        if (!ok) return@withContext null
        parse(sb.toString())
    }

    /**
     * 宽容解析：截取首个 { 到末个 }，尝试当 JSON 解；失败返回 null。
     * 单独暴露成 public 便于 JVM 单测直接喂各种畸形输出验证。
     */
    fun parse(raw: String): Result? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val obj = JSONObject(raw.substring(start, end + 1))
            val name = obj.optString("name").trim().take(20)
            val title = obj.optString("title").trim().take(60)
            val prompt = obj.optString("systemPrompt").trim()
            val emoji = obj.optString("avatarEmoji").trim().ifBlank { null }
            // name 和 systemPrompt 是必填；title 缺了就回落用 name
            if (name.isEmpty() || prompt.isEmpty()) return null
            Result(
                name = name,
                title = title.ifBlank { name },
                systemPrompt = prompt,
                avatarEmoji = emoji,
            )
        }.getOrNull()
    }
}
