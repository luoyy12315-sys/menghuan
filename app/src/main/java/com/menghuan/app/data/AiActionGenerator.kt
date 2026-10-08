package com.menghuan.app.data

import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.llm.OpenAiClient
import com.menghuan.app.live2d.ActionSpec
import com.menghuan.app.live2d.GenericChannel
import com.menghuan.app.live2d.ModelCapability
import com.menghuan.app.live2d.ModelCompatibility
import com.menghuan.app.live2d.ParameterTrack
import com.menghuan.app.live2d.TrackEasing
import com.menghuan.app.live2d.TrackKey
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * AI 动作生成（v0.64.0，用户要求 §七）。
 *
 * 只做一件事：**把一句自然语言变成一份动作配置**。
 *
 * 三条纪律（都写在用户需求里，也都被这个项目踩过）：
 *
 * 1. **只在"创建动作"时调用**。播放路径一个字都不联网 ——
 *    眨眼、呼吸、拖动、点击、播动作全部本地（用户 §十）。
 * 2. **AI 不许直接产出对模型的操作**。它只输出 [ActionSpec]（通用通道 + 速率 + 时长），
 *    怎么落到某张模型的参数上由引擎决定。这样"AI 生成的动作"和内置动作走的是同一条执行链，
 *    也就天然满足"换模型也能跑"。
 * 3. **解析必须宽容、校验必须严格**。模型吐出来的东西经常带 markdown 围栏、
 *    多一段解释、少一个逗号；这些要能救回来（截首尾大括号）。
 *    但救不回来的内容**绝不硬塞**：认不出的通道丢掉、越界的数值夹回来、
 *    一条轨道都没有就判失败并回一句人话，而不是存一条"点了没反应"的空动作。
 */
object AiActionGenerator {

    /** 生成结果：动作本体 + 从 AI 输出里读到的那些"列表要用的字段" */
    data class Result(
        val name: String,
        val spec: ActionSpec,
        val durationMs: Int,
        val loop: Boolean,
        val fadeInMs: Int,
        val fadeOutMs: Int,
        val compatibility: ModelCompatibility,
    )

    /** 生成失败的原因（直接可以显示给用户） */
    data class Failure(val reason: String)

    /**
     * 调 LLM 生成一条动作。
     *
     * @param description 用户那句自然语言（"做一个开心地挥手动作"）
     * @param config 当前生效的对话接口三件套
     * @param capability 当前模型的能力表 —— 会写进提示词，让 AI **结合模型现有参数**设计，
     *   而不是凭空写一堆这张模型没有的通道（那样生成出来会"少一半动作"）
     */
    suspend fun generate(
        description: String,
        config: ApiConfig,
        capability: ModelCapability,
    ): kotlin.Result<Result> = withContext(Dispatchers.IO) {
        if (description.isBlank()) return@withContext kotlin.Result.failure(IllegalArgumentException("先说一句你想要什么动作"))
        if (!config.isComplete) return@withContext kotlin.Result.failure(IllegalArgumentException("还没配好对话接口，先在聊天里配一个"))

        val sb = StringBuilder()
        val ok = runCatching {
            OpenAiClient(config.baseUrl, config.apiKey, config.model)
                .chat(
                    systemPrompt(capability),
                    listOf(ChatMessage(conversationId = 0, isFromUser = true, content = description.trim())),
                )
                .collect { token -> sb.append(token) }
        }.isSuccess
        if (!ok) return@withContext kotlin.Result.failure(IllegalStateException("接口没返回内容，检查一下网络或接口配置"))

        parse(sb.toString())?.let { return@withContext kotlin.Result.success(it) }
        kotlin.Result.failure(IllegalStateException("AI 这次没给出能用的动作，换个说法再试一次"))
    }

    /**
     * 提示词（纯函数，便于单测钉住"通道清单必须与代码一致"）。
     *
     * 刻意把通道清单**从 [GenericChannel] 生成**而不是手抄一份：
     * 手抄的那份一旦与代码脱节，AI 就会开始编造不存在的通道，
     * 而下游的宽容解析会把它悄悄丢掉 —— 症状是"AI 生成的动作缺了半截"，很难查。
     */
    fun systemPrompt(capability: ModelCapability = ModelCapability.EMPTY): String {
        val channelLines = GenericChannel.entries.joinToString("\n") { ch ->
            "- ${ch.id}（${ch.group.label}·${ch.label}）"
        }
        val stageIds = GenericChannel.stageChannels.joinToString(", ") { it.id }
        val supported = GenericChannel.paramChannels.filter { capability.supports(it) }
        // 能力表为空（还没加载模型 / 探不到）时不加这一段，免得写成"一个都不能用"
        val capabilityBlock = if (capability.paramChannelCount == 0) {
            ""
        } else {
            "\n当前模型**已存在**的通道（优先用这些）：" +
                supported.joinToString(", ") { it.id } +
                "\n当前模型**不存在**的通道（写了也会被跳过，尽量别用）：" +
                (GenericChannel.paramChannels - supported.toSet()).joinToString(", ") { it.id } +
                "\n"
        }

        return """
你是 Live2D 角色动作设计师。把用户的一句话变成一个可以本地播放的动画配置。

# 你要输出的东西
只输出**一个 JSON 对象**，不要 markdown 代码围栏，不要任何解释文字。结构如下：
{
  "name": "动作名（2-6 个汉字，如 开心挥手）",
  "durationMs": 2400,
  "loop": false,
  "tracks": [
    { "channel": "GENERIC_ARM_R", "keys": [ {"at": 0, "value": 0}, {"at": 0.3, "value": 0.8}, {"at": 1, "value": 0} ] }
  ]
}

# 字段规则
- durationMs：400 到 20000 之间的整数，**一次动作的总时长**。挥手 2000-2600，点头 1200-1800，翻跟斗 1800-2400。
- loop：只有"持续型"的小动作（呼吸般的微摆）才写 true，一次性动作一律 false。
- tracks：1 到 10 条。"at" 是**进度**（0 = 动作开始，1 = 动作结束，必须落在 0 到 1 之间且从小到大）。
- "value" 是**归一化幅度**：0 = 角色自然状态，1 = 这个动作最多能到多大，负值表示反方向。
  大多数关键帧用 -1 到 1 之间；**只有整体旋转允许到 2**（2 = 转一整圈）。

# 可用通道（只能用下面这些 id，写别的会被丢弃）
$channelLines
$capabilityBlock
# 怎么把动作设计得像样
- **结尾要收回 0**：一次性动作的最后几个关键帧请让主要通道回到接近 0，
  否则角色会定格在动作末尾（系统虽然会淡出，但自己收回更自然）。
- **一层层叠，别只动一个地方**：真实的动作是"身体带动头、头带动表情"。
  挥手 = 手臂往复 + 头微微歪 + 眉毛上扬；点头 = 上下转头往复 + 身体小幅前后；害羞 = 低头 + 缩肩 + 脸红。
- **$stageIds 是"整体"通道**：它们搬的是**整张画布**，任何模型上都能用。
  跳起、落地、原地转圈、整体摇摆用它们；但不要拿它们当"转头"用（那会连背景一起晃）。
- 通道名必须逐字照抄上面的 id（含 GENERIC_ 前缀），大小写敏感。
""".trim()
    }

    /**
     * 解析 AI 的输出（纯函数，JVM 单测直接喂各种畸形文本）。
     *
     * 宽容点：先截首尾大括号（吃掉 markdown 围栏与前后废话），再逐字段 `opt` 取值。
     * 严格点：一条轨道都解不出来就返回 null —— 用户看到"AI 没生成出来"远比
     * 看到一个不会动的动作好（后者会让人以为功能坏了）。
     */
    fun parse(raw: String?): Result? {
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null

        val duration = root.optInt("durationMs", 2400)
            .coerceIn(400, 20_000)
        val loop = root.optBoolean("loop", false)
        val tracks = parseTracks(root.optJSONArray("tracks"))
        if (tracks.isEmpty()) return null

        val required = readChannels(root.optJSONArray("required"))
        val expression = root.optString("expression", "").trim()

        val name = root.optString("name").trim().take(12).ifBlank { "AI 动作" }

        // 淡入淡出不给 AI 决定：它是"观感参数"而不是"动作设计"，
        // 由时长推一个合理值就够（短动作淡得快、长动作淡得慢），也免得 AI 写个 0 让动作"啪"地开始。
        val fadeIn = (duration / 12).coerceIn(120, 500)
        val fadeOut = (duration / 8).coerceIn(160, 700)

        return Result(
            name = name,
            spec = ActionSpec(
                expression = expression,
                amplitude = 1f,
                speed = 1f,
                fadeInMs = fadeIn,
                fadeOutMs = fadeOut,
                compatibility = ModelCompatibility(required = required, optional = emptySet()),
                tracks = tracks,
            ),
            durationMs = duration,
            loop = loop,
            fadeInMs = fadeIn,
            fadeOutMs = fadeOut,
            compatibility = ModelCompatibility(required = required),
        )
    }

    private fun parseTracks(arr: JSONArray?): List<ParameterTrack> {
        if (arr == null) return emptyList()
        val out = ArrayList<ParameterTrack>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            // 通道名兼容两种写法：完整 id（GENERIC_ARM_R）与 enum 名（ARM_R）
            val channel = GenericChannel.byId(t.optString("channel"))
                ?: GenericChannel.entries.firstOrNull { it.name == t.optString("channel") }
                ?: continue
            val keysArr = t.optJSONArray("keys") ?: continue
            val keys = ArrayList<TrackKey>()
            for (j in 0 until keysArr.length()) {
                val k = keysArr.optJSONObject(j) ?: continue
                val at = k.optDouble("at", Double.NaN)
                val value = k.optDouble("value", Double.NaN)
                if (at.isNaN() || value.isNaN()) continue
                keys.add(
                    TrackKey(
                        at = at.toFloat().coerceIn(0f, 1f),
                        // 舞台旋转允许 ±2（转一整圈），参数通道的越界值夹到 ±1.2 ——
                        // 归一化 1.0 已经是"可用范围的 60%"，再大就开始把网格拉坏
                        value = value.toFloat().coerceIn(
                            -MAX_VALUE,
                            if (channel.isStage) MAX_STAGE_VALUE else MAX_VALUE,
                        ),
                    ),
                )
            }
            if (keys.isEmpty()) continue
            out.add(
                ParameterTrack(
                    channel = channel,
                    keys = keys.sortedBy { it.at },
                    // 统一走 IN_OUT：AI 写不好"这一段要线性那一段要缓动"，
                    // 而 IN_OUT 在任何动作上都不会出难看的机械感。
                    // 要"往复摆动那种不断加速减速"的观感，靠关键帧自己排（内置摆手就是这么写的）。
                    easing = TrackEasing.IN_OUT,
                ),
            )
        }
        return out
    }

    private fun readChannels(arr: JSONArray?): Set<GenericChannel> {
        if (arr == null) return emptySet()
        val out = LinkedHashSet<GenericChannel>()
        for (i in 0 until arr.length()) {
            GenericChannel.byId(arr.optString(i))?.let { out.add(it) }
        }
        return out
    }

    private const val MAX_VALUE = 1.2f
    private const val MAX_STAGE_VALUE = 2f
}
