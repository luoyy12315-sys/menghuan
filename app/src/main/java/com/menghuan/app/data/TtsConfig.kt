package com.menghuan.app.data

import android.util.Base64

/**
 * 语音合成服务商（v0.50.0）。三家的协议**并不统一**，所以每家一个实现类，
 * 不硬凑成"一套请求体打天下"——字段名、鉴权头、响应形状三处都不一样：
 *
 * | | 硅基流动 | 小米 MiMo | 通用 OpenAI 兼容 |
 * |---|---|---|---|
 * | 端点 | `POST /audio/speech` | `POST /chat/completions` | `POST /audio/speech` |
 * | 文本放哪 | `input` | `messages[role=assistant].content` | `input` |
 * | 鉴权 | `Authorization: Bearer` | `api-key` + `Authorization` 都发 | `Authorization: Bearer` |
 * | 响应 | 裸二进制 | JSON → `choices[0].message.audio.data`（base64） | 裸二进制 |
 *
 * 逐条出处见 `docs/RESEARCH-v0.50.0-TTS协议查证.md`（每条都标了证据等级）。
 */
enum class TtsProvider(val label: String, val defaultBaseUrl: String, val defaultModel: String) {
    SILICONFLOW("硅基流动", "https://api.siliconflow.cn/v1", "FunAudioLLM/CosyVoice2-0.5B"),
    XIAOMI_MIMO("小米 MiMo", "https://api.xiaomimimo.com/v1", "mimo-v2.5-tts"),
    CUSTOM("自定义端点", "", ""),
    ;

    companion object {
        /** 老数据 / 空值一律回落到硅基流动（方案默认那家），不抛异常 */
        fun fromName(raw: String?): TtsProvider =
            entries.firstOrNull { it.name == raw?.trim() } ?: SILICONFLOW
    }
}

/** 预置音色：[id] 是发给接口的原值，[label] 是给用户看的 */
data class PresetVoice(val id: String, val label: String)

/**
 * 通话播放轨道的采样率（唯一来源：[TtsTarget.CALL] 用它，`CallAudioManager.OUT_RATE` 也引用它）。
 * 两边必须是同一个数，否则每句话都要多做一次重采样——变速变调的老坑。
 * 顶层常量而不是枚举里的：枚举项初始化时自己的 companion object 还没就绪，引用不到。
 */
const val TTS_CALL_SAMPLE_RATE = 24_000

/**
 * 合成目标（v0.52.0）：同样是"把这句念出来"，但**要什么响应格式**差别很大。
 *
 * | 目标 | 格式 | 为什么 |
 * |---|---|---|
 * | [NOTE] | MP3 | 语音条落盘给 `MediaPlayer` 播，系统自己协商格式，MP3 体积小 8 倍 |
 * | [CALL] | WAV @ [CALL_SAMPLE_RATE] | 通话要**逐句立刻**喂 AudioTrack。以前要 MP3，得自己解一遍；
 *   而旧 MP3 解码器把输入格式硬写成 24kHz/单声道，硅基真实回的是 44.1kHz → 0 帧 →
 *   真机"声音合成失败：音频解码失败"。直接要 WAV 后**通话链路根本不解 MP3** |
 *
 * ⚠️ [CALL] 的采样率取自 [TTS_CALL_SAMPLE_RATE]，必须等于 `CallAudioManager.OUT_RATE`
 * （那个常量正是从这里取的），两边不一致就会在每句话上多做一次重采样——变速变调的老坑。
 */
enum class TtsTarget(val format: TtsAudioFormat, val sampleRate: Int?) {
    NOTE(TtsAudioFormat.MP3, null),
    CALL(TtsAudioFormat.WAV, TTS_CALL_SAMPLE_RATE),
    ;

    /**
     * 该家实际请求的格式。MiMo **永远要 WAV**：官方示例全是 wav/pcm16，
     * 它 enum 里那个 mp3 没有任何成熟实现用过（`docs/RESEARCH-v0.50.0-TTS协议查证.md` §2），
     * 不拿真机去赌。
     */
    fun formatFor(provider: TtsProvider): TtsAudioFormat =
        if (provider == TtsProvider.XIAOMI_MIMO) TtsAudioFormat.WAV else format
}

/**
 * 一次合成的产物。
 *
 * [format] 必须带着走：MiMo 默认回 **WAV**，硅基/通用回 **MP3**，
 * 而通话链路要把音频解成 PCM 喂 AudioTrack——不知道格式就没法选解码器。
 */
class TtsAudio(val bytes: ByteArray, val format: TtsAudioFormat) {
    val isEmpty: Boolean get() = bytes.isEmpty()
}

enum class TtsAudioFormat(val extension: String, val mime: String) {
    MP3("mp3", "audio/mpeg"),
    WAV("wav", "audio/wav"),
    ;

    companion object {
        /**
         * 按容器魔术字节嗅探，**不信 Content-Type 也不信自己请求的 response_format**：
         * 官方文档没给硅基成功响应的 Content-Type 逐字值，而网关改格式是常事。
         * 认不出来返回 null，调用方按失败处理（不猜）。
         */
        fun sniff(bytes: ByteArray): TtsAudioFormat? = when {
            bytes.size < 12 -> null
            // WAV：RIFF....WAVE
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() -> WAV
            // MP3：ID3 标签头，或帧同步 0xFFEx/0xFFFx
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte() -> MP3
            bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 -> MP3
            else -> null
        }
    }
}

/**
 * 音色引用：哪家服务商 + 该家认的音色串。
 *
 * [voiceId] 有三种取值：
 * 1. **预置音色别名**（`anna` / `冰糖`）——硅基发请求时要拼成 `<model>:<alias>`，见 [TtsVoices.siliconVoice]；
 * 2. **硅基克隆返回的 uri 整串**（`speech:<name>:<account>:<id>`）——原样发，不能再拼前缀；
 * 3. **`local:<绝对路径>`**——克隆样本落在本机，MiMo 没有上传接口，只能每次请求把样本
 *    base64 内联进 `audio.voice`（见 [TtsVoices.MIMO_LOCAL_PREFIX]）。
 *    为什么不直接把 base64 存进 DataStore：一条就接近 10MB，会把偏好文件撑爆。
 */
data class VoiceRef(val provider: TtsProvider, val voiceId: String) {

    val isBlank: Boolean get() = voiceId.isBlank()

    /** 存进 DataStore 的形态：`<PROVIDER>|<voiceId>`（voiceId 里不会出现 `|`） */
    fun encode(): String = "${provider.name}|$voiceId"

    companion object {
        /** 解不出来（老版本存的是 Edge 音色名如 `zh-CN-XiaoxiaoNeural`）返回 null，调用方回落默认 */
        fun decode(raw: String?): VoiceRef? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val sep = text.indexOf('|')
            if (sep <= 0) return null
            val provider = TtsProvider.entries.firstOrNull { it.name == text.substring(0, sep) } ?: return null
            val id = text.substring(sep + 1)
            if (id.isBlank()) return null
            return VoiceRef(provider, id)
        }
    }
}

/** TTS 接口配置（DataStore：tts_provider / tts_base_url / tts_api_key / tts_model） */
data class TtsConfig(
    val provider: TtsProvider = TtsProvider.SILICONFLOW,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {

    /** 用户没填就用该服务商的默认地址/模型；填了以用户为准（MiMo Token Plan 的地址与按量付费不同） */
    fun effectiveBaseUrl(): String = baseUrl.trim().trimEnd('/').ifBlank { provider.defaultBaseUrl }

    fun effectiveModel(): String = model.trim().ifBlank { provider.defaultModel }

    val isComplete: Boolean
        get() = apiKey.isNotBlank() && effectiveBaseUrl().isNotBlank() && effectiveModel().isNotBlank()
}

/** 音色表与各家 `voice` 字段的拼法（纯函数，JVM 可测） */
object TtsVoices {

    /** MiMo 克隆样本的本地引用前缀，见 [VoiceRef] 第 3 种取值 */
    const val MIMO_LOCAL_PREFIX = "local:"

    /**
     * 预置音色**每家只留两个，一男一女**（2026-10-05 用户拍板："其他都不要"）。
     * 官方那 8 / 9 个的长列表在设置页里纯属噪音——想听别的声音走「克隆音色」。
     * ID 是官方逐字值（`docs/RESEARCH-v0.50.0-TTS协议查证.md` §音色）；
     * ⚠️ 别家的人设存过某个现在删掉的预置 ID 时，[voiceLabel] 会显示 ID 本身，不会崩。
     */
    val SILICONFLOW_PRESETS: List<PresetVoice> = listOf(
        PresetVoice("alex", "Alex · 男声"),
        PresetVoice("anna", "Anna · 女声"),
    )

    /** MiMo 的中文音色 ID **就是中文字符串本身**，不是拼音 */
    val MIMO_PRESETS: List<PresetVoice> = listOf(
        PresetVoice("白桦", "白桦 · 男声"),
        PresetVoice("冰糖", "冰糖 · 女声"),
    )

    fun presets(provider: TtsProvider): List<PresetVoice> = when (provider) {
        TtsProvider.SILICONFLOW -> SILICONFLOW_PRESETS
        TtsProvider.XIAOMI_MIMO -> MIMO_PRESETS
        // 自定义端点的音色名只有用户自己知道，不做候选，让他直接填
        TtsProvider.CUSTOM -> emptyList()
    }

    fun defaultVoiceId(provider: TtsProvider): String = presets(provider).firstOrNull()?.id.orEmpty()

    /**
     * 硅基的 `voice` 字段：预置音色**必须带模型名前缀**（官方原文：
     * 「在使用对应的系统预置音色时，需要在前面加上模型名称」），
     * 而克隆返回的 `uri`（`speech:...`）要**原样发**、再拼前缀就废了。
     * 已经带 `model:` 前缀的也别重复拼。
     */
    fun siliconVoice(model: String, voiceId: String): String {
        val id = voiceId.trim()
        if (id.isEmpty()) return id
        if (id.startsWith("speech:") || id.startsWith("$model:")) return id
        return "$model:$id"
    }

    /**
     * MiMo 的 `audio.voice`：克隆时把样本编成 data URI 内联。
     * 官方要求前缀逐字为 `data:{MIME_TYPE};base64,$BASE64_AUDIO`，且 `$BASE64_AUDIO` 不含任何前缀。
     */
    fun mimoCloneVoice(mime: String, base64Audio: String): String =
        "data:$mime;base64,$base64Audio"

    fun base64NoWrap(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    /**
     * 剥 data URI 前缀。MiMo 官方口径是**纯 base64 无前缀**（示例直接 b64decode 写文件），
     * 但反编译的成熟实现防御性地剥前缀——线上出现过变体，剥一下零成本。
     * 顺带去掉换行/空白：有些网关会把 base64 折行。
     */
    fun stripBase64(raw: String): String {
        var s = raw.trim()
        val idx = s.indexOf(";base64,")
        if (idx >= 0) s = s.substring(idx + ";base64,".length)
        return s.filter { !it.isWhitespace() }
    }

    /**
     * MiMo voiceclone 的样本上限是 **base64 编码后 ≤ 10MB**（不是原文件 10MB）。
     * base64 膨胀 4/3，所以原文件实际上限约 7.5MB —— 按编码后长度校验才不会超限被拒。
     */
    const val MIMO_CLONE_BASE64_LIMIT: Long = 10L * 1024 * 1024

    fun mimoCloneBase64Size(fileBytes: Long): Long = ((fileBytes + 2) / 3) * 4

    fun isWithinMimoCloneLimit(fileBytes: Long): Boolean =
        mimoCloneBase64Size(fileBytes) <= MIMO_CLONE_BASE64_LIMIT

    /** 本地克隆样本引用 ↔ 路径 */
    fun localVoiceRef(provider: TtsProvider, path: String) = VoiceRef(provider, "$MIMO_LOCAL_PREFIX$path")

    fun localSamplePath(voiceId: String): String? =
        voiceId.takeIf { it.startsWith(MIMO_LOCAL_PREFIX) }?.removePrefix(MIMO_LOCAL_PREFIX)

    /**
     * 「音色板块」的全部条目（v0.52.0）。设置页的音色段与人设编辑页的候选**共用这一个函数**——
     * 用户点名"人设页音色选择就只放音色板块里有的"，两边读同一份才不会各说各话。
     *
     * @param cloned 硅基那边从 `/audio/voice/list` 拉回来的克隆音色（账号级，谁都能用）
     * @param localSamples 本机 `filesDir/voices/` 里的样本文件名（MiMo 没有上传接口，样本随请求内联）
     * @param manualId 该家手填的音色 ID（空串 = 不给这一行）
     * @param aliases 克隆音色的**中文显示名**（uri → 名字）。硅基的 customName 只收 ASCII，
     *   用户起的中文名存在本机（`SettingsRepository.cloneVoiceNames`）；不传就退回显示 ASCII 名
     */
    fun library(
        provider: TtsProvider,
        cloned: List<ClonedVoice> = emptyList(),
        localSamples: List<String> = emptyList(),
        manualId: String = "",
        aliases: Map<String, String> = emptyMap(),
    ): List<VoiceItem> {
        val out = ArrayList<VoiceItem>()
        presets(provider).forEach { out += VoiceItem(VoiceRef(provider, it.id), it.label) }
        if (provider == TtsProvider.SILICONFLOW) {
            cloned.forEach {
                out += VoiceItem(
                    VoiceRef(provider, it.uri),
                    "克隆 · ${aliases[it.uri] ?: it.customName}",
                    detail = it.uri,
                )
            }
        }
        if (provider == TtsProvider.XIAOMI_MIMO) {
            localSamples.forEach { name ->
                out += VoiceItem(
                    localVoiceRef(provider, name),
                    "克隆 · ${aliases[name] ?: name.substringBeforeLast('.')}",
                    local = true,
                )
            }
        }
        if (manualId.isNotBlank()) {
            out += VoiceItem(VoiceRef(provider, manualId.trim()), "手动 · ${manualId.trim()}", local = true)
        }
        return out
    }

    /**
     * 音色行上给用户看的标签（[ref] 不属于当前 provider 时回落到"默认"——
     * 用户换了服务商，那个 ID 在新家不认，但人设行里还存着旧值）。
     */
    fun labelOf(
        provider: TtsProvider,
        ref: VoiceRef?,
        cloned: List<ClonedVoice> = emptyList(),
        manualId: String = "",
        aliases: Map<String, String> = emptyMap(),
    ): String {
        val id = ref?.takeIf { it.provider == provider }?.voiceId
            ?: return "默认 · ${defaultVoiceId(provider).ifBlank { "（该家无预置，用手动音色）" }}"
        presets(provider).firstOrNull { it.id == id }?.let { return it.label }
        cloned.firstOrNull { it.uri == id }?.let { return "克隆 · ${aliases[id] ?: it.customName}" }
        localSamplePath(id)?.let { path ->
            val fileKey = path.substringAfterLast('/')
            return "克隆 · ${aliases[path] ?: aliases[fileKey] ?: fileKey.substringBeforeLast('.')}"
        }
        if (id == manualId.trim()) return "手动 · $id"
        return id
    }
}

/**
 * 音色板块里的一行。[ref] 是存进 DataStore / 人设的那份引用，[label] 给用户看，
 * [local] 为真表示这行没有服务商端的实体（本地样本或手填 ID），因此不随"重新拉取"消失。
 */
data class VoiceItem(
    val ref: VoiceRef,
    val label: String,
    val detail: String = "",
    val local: Boolean = false,
)
