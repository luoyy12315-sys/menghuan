package com.menghuan.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** 主题模式默认值：跟随系统（不落盘即为 SYSTEM） */
const val DEFAULT_THEME_MODE = "SYSTEM"

// internal：单测（同模块）需要直接预置旧版明文数据来验证迁移
internal val Context.settingsStore by preferencesDataStore(name = "settings")

/** 用户自配的 LLM 接口（OpenAI 兼容三件套） */
data class ApiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/**
 * 用户配置仓库。API Key 加密存储（AGENTS §五.4，v0.2.3 起）：
 * - 首选 AndroidKeyStore AES-256-GCM 加密，DataStore 只落密文 `api_key_enc`
 * - 升级迁移：读到旧明文 `api_key` 时自动加密搬移并删除明文；Keystore 不可用时回退读明文（不删，密钥不丢）
 * - 解密失败（锁屏凭据变更使 Keystore 密钥失效）视为空，用户在「设置 · API 配置」重填即可
 */
class SettingsRepository(
    context: Context,
    // store 可注入：单测用独立 DataStore 实例（委托单例会跨 Robolectric 测试残留）
    private val store: DataStore<Preferences> = context.settingsStore,
) {

    private val keyBase = stringPreferencesKey("api_base_url")
    private val keyKey = stringPreferencesKey("api_key")        // 旧明文键（迁移后删除）
    private val keyModel = stringPreferencesKey("api_model")
    private val keyKeyEnc = stringPreferencesKey("api_key_enc") // 新密文键 Base64(IV+GCM)
    private val keyProfiles = stringPreferencesKey("api_profiles")      // 多接口 JSON 数组
    private val keyActiveProfile = stringPreferencesKey("api_active_profile")
    private val keyImgBase = stringPreferencesKey("img_base_url")       // 生图接口
    private val keyImgModel = stringPreferencesKey("img_model")
    private val keyImgKeyEnc = stringPreferencesKey("img_key_enc")
    private val keyImgKeyPlain = stringPreferencesKey("img_key_plain")
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyNotifyEnabled = booleanPreferencesKey("notify_enabled")
    private val keyDailySummaryEnabled = booleanPreferencesKey("daily_summary_enabled")
    private val keyWeeklyReportNotifyEnabled = booleanPreferencesKey("weekly_report_notify_enabled")
    private val keyAgentMaxSteps = intPreferencesKey("agent_max_steps")
    private val keyAgentWhitelist = stringPreferencesKey("agent_whitelist")
    private val keyAgentSensitiveConfirm = booleanPreferencesKey("agent_sensitive_confirm")
    // v0.42.0：每日建议（独立第三条 AI 产出链路）的提示词策略 + 是否通知
    private val keyDailySuggestionStrategy = stringPreferencesKey("daily_suggestion_strategy")
    private val keyDailySuggestionNotifyEnabled = booleanPreferencesKey("daily_suggestion_notify_enabled")

    /** 会话已读标记前缀：last_read_<personaId> = 最后阅读时刻毫秒值 */
    private val readPrefix = "last_read_"

    /** 提炼水位线前缀：extract_upto_<personaId> = 已提炼到的最后一条消息 id */
    private val watermarkPrefix = "extract_upto_"

    /** 聊天草稿前缀：draft_<personaId> = 未发送的输入（进程被杀后恢复） */
    private val draftPrefix = "draft_"

    /**
     * Keystore 解密有成本（真机一次可达十几毫秒）：密文 → 明文的进程级缓存。
     * 同一条密文整个进程生命周期只解一次；后续 DataStore 重发（进聊天页 markChatRead
     * 就会触发一次）全部走内存，不再在收集方线程上同步 binder 到 keystore2。
     * JVM 单测下 Keystore 不可用，解密失败也照缓存（空串），调用方回退明文字段的语义不变。
     */
    private val decryptCache = ConcurrentHashMap<String, String>()

    /** 主题模式内存缓存（首帧同步读用，省掉二次磁盘 IO） */
    @Volatile
    private var cachedThemeMode: String? = null

    /**
     * 全部对话接口（密钥已解密）。老版本只存了一条单接口配置，
     * 没有 api_profiles 时把它当作「默认接口」虚拟读出，升级零操作。
     */
    val apiProfiles: Flow<List<ApiProfile>> = store.data.map { prefs ->
        val stored = ApiProfileCodec.decode(prefs[keyProfiles] ?: "")
        if (stored.isNotEmpty()) {
            stored.map { it.toProfile() }
        } else {
            val legacyBase = prefs[keyBase] ?: ""
            val legacyKey = resolveApiKey(prefs)
            if (legacyBase.isBlank() && legacyKey.isBlank()) {
                emptyList()
            } else {
                listOf(
                    ApiProfile(
                        id = DEFAULT_PROFILE_ID,
                        name = "默认接口",
                        baseUrl = legacyBase,
                        apiKey = legacyKey,
                        model = prefs[keyModel] ?: "",
                    )
                )
            }
        }
    }

    /** 当前生效的接口 id（指向已删除的接口时回落到第一条） */
    val activeProfileId: Flow<String> = store.data.map { prefs ->
        val id = prefs[keyActiveProfile] ?: DEFAULT_PROFILE_ID
        val storedIds = ApiProfileCodec.decode(prefs[keyProfiles] ?: "").map { it.id }
        if (storedIds.isEmpty() || id in storedIds) id else storedIds.first()
    }

    /** 当前生效的对话接口：聊天 / 提炼 / 周报 / Agent 全读这一个 */
    val apiConfig: Flow<ApiConfig> = combine(apiProfiles, activeProfileId) { profiles, activeId ->
        val hit = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
        ApiConfig(
            baseUrl = hit?.baseUrl ?: "",
            model = hit?.model ?: "",
            apiKey = hit?.apiKey ?: "",
        )
    }.distinctUntilChanged()

    // ===== 会话级接口覆盖（v0.36.4：每个会话可独立选接口，未设的跟随全局） =====

    /** 会话级覆盖前缀：persona_api_<personaId> = 该会话独立选的接口 id */
    private val personaApiPrefix = "persona_api_"

    /** 某会话的独立接口 id；null/空 = 跟随全局默认 */
    fun personaApiProfileId(personaId: Long): Flow<String?> = store.data.map { prefs ->
        prefs[stringPreferencesKey(personaApiPrefix + personaId)]?.takeIf { it.isNotBlank() }
    }

    /** 设置某会话独立接口；传 null/空 = 清除覆盖（回跟随全局） */
    suspend fun setPersonaApiProfile(personaId: Long, profileId: String?) {
        val key = stringPreferencesKey(personaApiPrefix + personaId)
        store.edit { prefs ->
            if (profileId.isNullOrBlank()) prefs.remove(key) else prefs[key] = profileId
        }
    }

    /** 人设删除的级联清理：移除会话级接口覆盖 */
    suspend fun removePersonaApiProfile(personaId: Long) {
        store.edit { it.remove(stringPreferencesKey(personaApiPrefix + personaId)) }
    }

    /**
     * 某会话实际生效的接口 id：会话覆盖优先（且该接口必须仍存在），否则回落全局。
     * 覆盖指向的接口被删除时自动回落全局，不会卡死在一个不存在的 id 上。
     */
    fun effectiveProfileId(personaId: Long): Flow<String> =
        combine(apiProfiles, personaApiProfileId(personaId), activeProfileId) { profiles, override, global ->
            val ids = profiles.map { it.id }
            when {
                ids.isEmpty() -> global
                override != null && override in ids -> override
                else -> global
            }
        }.distinctUntilChanged()

    /** 某会话实际生效的对话接口配置（该会话的聊天回复 / 提炼用） */
    fun apiConfigFor(personaId: Long): Flow<ApiConfig> =
        combine(apiProfiles, effectiveProfileId(personaId)) { profiles, id ->
            val hit = profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull()
            ApiConfig(
                baseUrl = hit?.baseUrl ?: "",
                model = hit?.model ?: "",
                apiKey = hit?.apiKey ?: "",
            )
        }.distinctUntilChanged()

    /** 落盘形态 → 内存形态：密文优先，Keystore 不可用时读明文兜底 */
    private fun StoredApiProfile.toProfile() = ApiProfile(
        id = id,
        name = name,
        baseUrl = baseUrl,
        model = model,
        apiKey = if (keyEnc.isNotBlank()) resolveSealed(keyEnc) ?: keyPlain else keyPlain,
    )

    /** 密文 → 明文（缓存优先）；解密失败/未加密返回 null，由调用方决定回退策略 */
    private fun resolveSealed(sealed: String): String? = when {
        sealed.isBlank() -> null
        decryptCache.containsKey(sealed) -> decryptCache[sealed]!!.ifEmpty { null }
        else -> {
            val plain = ApiKeyCipher.decrypt(sealed) ?: ""
            decryptCache[sealed] = plain
            plain.ifEmpty { null }
        }
    }

    private suspend fun currentStoredProfiles(): MutableList<StoredApiProfile> =
        ApiProfileCodec.decode(store.data.first()[keyProfiles] ?: "").toMutableList()

    /** 密文优先，旧明文自动迁移，Keystore 不可用时回退读明文 */
    private suspend fun resolveApiKey(prefs: Preferences): String {
        val sealed = prefs[keyKeyEnc]
        val legacyPlain = prefs[keyKey] ?: ""
        return when {
            sealed != null -> {
                val plain = resolveSealed(sealed) ?: ""
                if (legacyPlain.isNotEmpty()) {
                    // 加密版已就绪：清掉历史明文
                    store.edit { it.remove(keyKey) }
                }
                plain
            }
            legacyPlain.isNotEmpty() -> {
                val sealedText = ApiKeyCipher.encrypt(legacyPlain)
                if (sealedText != null) {
                    // 旧数据迁移：明文 → 密文，删除明文
                    store.edit { it[keyKeyEnc] = sealedText; it.remove(keyKey) }
                    decryptCache[sealedText] = legacyPlain
                }
                // encrypt 失败（JVM 单测/Keystore 异常）：回退读明文且不删，绝不让密钥凭空消失
                legacyPlain
            }
            else -> ""
        }
    }

    /** 新增或更新一条接口；[profile].id 为空则自动生成 */
    suspend fun saveApiProfile(profile: ApiProfile): String = withContext(Dispatchers.IO) {
        val id = profile.id.ifBlank { "p" + System.currentTimeMillis() }
        val list = currentStoredProfiles()
        val stored = StoredApiProfile.of(
            id = id,
            name = profile.name.trim().ifBlank { "未命名接口" },
            baseUrl = profile.baseUrl.trim(),
            model = profile.model.trim(),
            key = profile.apiKey.trim(),
        )
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) list[index] = stored else list.add(stored)
        store.edit { prefs ->
            prefs[keyProfiles] = ApiProfileCodec.encode(list)
            if (prefs[keyActiveProfile] == null && list.isNotEmpty()) prefs[keyActiveProfile] = list.first().id
        }
        id
    }

    /** 切换当前生效接口（聊天气泡页长按头像） */
    suspend fun setActiveProfile(id: String) {
        store.edit { it[keyActiveProfile] = id }
    }

    /** 删除接口；删掉正在生效的那条时自动回落到剩下的第一条 */
    suspend fun deleteApiProfile(id: String) = withContext(Dispatchers.IO) {
        val list = currentStoredProfiles().filterNot { it.id == id }
        store.edit { prefs ->
            prefs[keyProfiles] = ApiProfileCodec.encode(list)
            val active = prefs[keyActiveProfile]
            if (active == null || active == id) {
                if (list.isEmpty()) prefs.remove(keyActiveProfile) else prefs[keyActiveProfile] = list.first().id
            }
        }
    }

    /**
     * 覆盖当前生效接口的三件套（老调用方入口）。
     * 同时镜像写回旧的单接口键，保证任何按老键名读取的老路径都不失效。
     */
    suspend fun saveApiConfig(config: ApiConfig) = withContext(Dispatchers.IO) {
        val sealedText = if (config.apiKey.isEmpty()) null else ApiKeyCipher.encrypt(config.apiKey)
        store.edit { prefs ->
            when {
                sealedText != null -> { prefs[keyKeyEnc] = sealedText; prefs.remove(keyKey) }
                config.apiKey.isNotEmpty() -> {
                    // Keystore 不可用：退回明文（自用兜底），旧密文一并清掉避免错位
                    prefs[keyKey] = config.apiKey; prefs.remove(keyKeyEnc)
                }
                else -> { prefs.remove(keyKeyEnc); prefs.remove(keyKey) }
            }
            prefs[keyBase] = config.baseUrl
            prefs[keyModel] = config.model
        }
        // 旧键名镜像写入：加密失败（sealedText 为 null）时不进缓存，读侧走明文兜底
        sealedText?.let { decryptCache[it] = config.apiKey }
        saveApiProfile(
            ApiProfile(
                id = store.data.first()[keyActiveProfile] ?: DEFAULT_PROFILE_ID,
                name = "默认接口",
                baseUrl = config.baseUrl,
                apiKey = config.apiKey,
                model = config.model,
            )
        )
    }

    /**
     * 启动预热：把全部密文在 IO 线程解一遍进内存缓存。
     * 用户进聊天页必然触发 markChatRead 写库 → store.data 全量重发 →
     * 各接口流重新 map；没预热的话那一轮解密全部落在主线程（真机 trace 实测
     * 主线程反复同步 binder 到 keystore2，doFrame 被推迟 60ms+，进页动画掉到个位数帧）。
     */
    suspend fun warmKeyCache() = withContext(Dispatchers.IO) {
        runCatching {
            val prefs = store.data.first()
            ApiProfileCodec.decode(prefs[keyProfiles] ?: "").forEach { resolveSealed(it.keyEnc) }
            prefs[keyKeyEnc]?.let { resolveSealed(it) }
            prefs[keyImgKeyEnc]?.let { resolveSealed(it) }
        }
    }

    /** 各会话的最后阅读时刻（未读点判断用）：personaId → 时间戳毫秒 */
    val readMarks: Flow<Map<Long, Long>> = store.data.map { prefs ->
        buildMap {
            prefs.asMap().forEach { (key, value) ->
                if (key.name.startsWith(readPrefix) && value is Long) {
                    key.name.removePrefix(readPrefix).toLongOrNull()?.let { put(it, value) }
                }
            }
        }
    }

    /** 进入会话即标记已读（未读点消除） */
    suspend fun markChatRead(personaId: Long) {
        store.edit { it[longPreferencesKey(readPrefix + personaId)] = System.currentTimeMillis() }
    }

    /** 人设删除的级联清理：移除未读标记 */
    suspend fun removeReadMark(personaId: Long) {
        store.edit { it.remove(longPreferencesKey(readPrefix + personaId)) }
    }

    /** 任务到点通知总开关（设置 · 通知与提醒） */
    val notificationsEnabled: Flow<Boolean> = store.data.map { it[keyNotifyEnabled] ?: true }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        store.edit { it[keyNotifyEnabled] = enabled }
    }

    /** 每日总结通知（每晚 21:30「今日完成 x/y」，设置 · 通知与提醒） */
    val dailySummaryEnabled: Flow<Boolean> = store.data.map { it[keyDailySummaryEnabled] ?: true }

    suspend fun setDailySummaryEnabled(enabled: Boolean) {
        store.edit { it[keyDailySummaryEnabled] = enabled }
    }

    /** AI 周报生成完成通知（默认关：周报不时效敏感，静默生成、数据页可看） */
    val weeklyReportNotifyEnabled: Flow<Boolean> = store.data.map { it[keyWeeklyReportNotifyEnabled] ?: false }

    suspend fun setWeeklyReportNotifyEnabled(enabled: Boolean) {
        store.edit { it[keyWeeklyReportNotifyEnabled] = enabled }
    }

    // ===== AI 提示词（设置 · AI 提示词，可编辑；默认值见 AiPrompts） =====

    private val keyBasePrompt = stringPreferencesKey("base_prompt")
    private val keyProfilePreamble = stringPreferencesKey("profile_preamble")
    // v0.36.0：三段"策略"提示词（框架部分硬编码在 ProfileExtractor / WeeklyReportMath / ChatViewModel）
    private val keyExtractionStrategy = stringPreferencesKey("extraction_strategy")
    private val keyWeeklyReportStrategy = stringPreferencesKey("weekly_report_strategy")
    private val keyImageHintStrategy = stringPreferencesKey("image_hint_strategy")
    // v0.37.0：对话内操控手机（总开关默认开 = 用户授了完全权限；关掉即不注入能力提示）
    private val keyAgentChatEnabled = booleanPreferencesKey("agent_chat_enabled")
    private val keyAgentChatStrategy = stringPreferencesKey("agent_chat_strategy")
    // v0.38.0：Agent 进度通知 / 人设主动关心 / token 用量记账
    private val keyAgentNotifyEnabled = booleanPreferencesKey("agent_notify_enabled")
    private val keyProactiveEnabled = booleanPreferencesKey("proactive_enabled")
    private val keyProactiveLastDay = longPreferencesKey("proactive_last_day")
    private val keyUsageMonth = stringPreferencesKey("usage_month")
    private val keyUsageTokens = intPreferencesKey("usage_tokens")

    /** 基础提示词：人设未填设定词时的中性 system prompt */
    val basePrompt: Flow<String> = store.data.map { it[keyBasePrompt] ?: AiPrompts.DEFAULT_BASE_PROMPT }

    suspend fun setBasePrompt(text: String) {
        store.edit { prefs ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == AiPrompts.DEFAULT_BASE_PROMPT) {
                prefs.remove(keyBasePrompt) // 与默认一致时不落盘，改默认值即全局生效
            } else {
                prefs[keyBasePrompt] = trimmed
            }
        }
    }

    /** 档案注入说明：用户档案进入 system prompt 前的包装语 */
    val profilePreamble: Flow<String> =
        store.data.map { it[keyProfilePreamble] ?: AiPrompts.DEFAULT_PROFILE_PREAMBLE }

    suspend fun setProfilePreamble(text: String) {
        store.edit { prefs ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == AiPrompts.DEFAULT_PROFILE_PREAMBLE) {
                prefs.remove(keyProfilePreamble)
            } else {
                prefs[keyProfilePreamble] = trimmed
            }
        }
    }

    /**
     * AI 洞察提炼策略（框架 JSON schema 硬编码在 [ProfileExtractor.FRAMEWORK]）。
     * 用户改这段只影响"什么该记 / 什么不该记"的策略，不会破坏 parse 逻辑。
     */
    val extractionStrategy: Flow<String> =
        store.data.map { it[keyExtractionStrategy] ?: ProfileExtractor.DEFAULT_STRATEGY }

    suspend fun setExtractionStrategy(text: String) {
        store.edit { prefs ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == ProfileExtractor.DEFAULT_STRATEGY) {
                prefs.remove(keyExtractionStrategy)
            } else {
                prefs[keyExtractionStrategy] = trimmed
            }
        }
    }

    /** AI 周报策略（框架"只输出正文纯文本"硬编码在 [WeeklyReportMath.FRAMEWORK]） */
    val weeklyReportStrategy: Flow<String> =
        store.data.map { it[keyWeeklyReportStrategy] ?: WeeklyReportMath.DEFAULT_STRATEGY }

    suspend fun setWeeklyReportStrategy(text: String) {
        store.edit { prefs ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == WeeklyReportMath.DEFAULT_STRATEGY) {
                prefs.remove(keyWeeklyReportStrategy)
            } else {
                prefs[keyWeeklyReportStrategy] = trimmed
            }
        }
    }

    /** 生图触发策略（标记格式 [生图:...] 硬编码在 IMAGE_HINT_FRAMEWORK） */
    val imageHintStrategy: Flow<String> =
        store.data.map { it[keyImageHintStrategy] ?: com.menghuan.app.ui.chat.IMAGE_HINT_DEFAULT_STRATEGY }

    suspend fun setImageHintStrategy(text: String) {
        store.edit { prefs ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == com.menghuan.app.ui.chat.IMAGE_HINT_DEFAULT_STRATEGY) {
                prefs.remove(keyImageHintStrategy)
            } else {
                prefs[keyImageHintStrategy] = trimmed
            }
        }
    }

    /** 对话内操控手机总开关（默认开；关掉后聊天回复不注入能力提示，模型无法发起标记） */
    val agentChatEnabled: Flow<Boolean> = store.data.map { it[keyAgentChatEnabled] ?: true }

    suspend fun setAgentChatEnabled(enabled: Boolean) {
        store.edit { prefs ->
            if (enabled) prefs.remove(keyAgentChatEnabled) else prefs[keyAgentChatEnabled] = false
        }
    }

    /** 对话内操控手机策略（标记格式 [操作手机:...] 硬编码在 AGENT_CHAT_HINT_FRAMEWORK） */
    val agentChatStrategy: Flow<String> =
        store.data.map { it[keyAgentChatStrategy] ?: com.menghuan.app.ui.chat.AGENT_CHAT_DEFAULT_STRATEGY }

    suspend fun setAgentChatStrategy(text: String) {
        store.edit { prefs ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == com.menghuan.app.ui.chat.AGENT_CHAT_DEFAULT_STRATEGY) {
                prefs.remove(keyAgentChatStrategy)
            } else {
                prefs[keyAgentChatStrategy] = trimmed
            }
        }
    }

    /** Agent 执行期间的进度通知（默认开：低重要度常驻通知，实时显示第几步） */
    val agentNotifyEnabled: Flow<Boolean> = store.data.map { it[keyAgentNotifyEnabled] ?: true }

    suspend fun setAgentNotifyEnabled(enabled: Boolean) {
        store.edit { prefs ->
            if (enabled) prefs.remove(keyAgentNotifyEnabled) else prefs[keyAgentNotifyEnabled] = false
        }
    }

    /** AI 每日建议策略（框架"只输出 JSON"硬编码在 [DailySuggestionMath.FRAMEWORK]） */
    val dailySuggestionStrategy: Flow<String> =
        store.data.map { it[keyDailySuggestionStrategy] ?: DailySuggestionMath.DEFAULT_STRATEGY }

    suspend fun setDailySuggestionStrategy(text: String) {
        store.edit { prefs ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == DailySuggestionMath.DEFAULT_STRATEGY) {
                prefs.remove(keyDailySuggestionStrategy)
            } else {
                prefs[keyDailySuggestionStrategy] = trimmed
            }
        }
    }

    /** 每日建议生成后的通知（默认关：和 AI 周报一样静默生成，数据页可看） */
    val dailySuggestionNotifyEnabled: Flow<Boolean> =
        store.data.map { it[keyDailySuggestionNotifyEnabled] ?: false }

    suspend fun setDailySuggestionNotifyEnabled(enabled: Boolean) {
        store.edit { it[keyDailySuggestionNotifyEnabled] = enabled }
    }

    /** 人设主动关心（默认关：每天晚间最多一条，基于档案与任务情况） */
    val proactiveEnabled: Flow<Boolean> = store.data.map { it[keyProactiveEnabled] ?: false }

    suspend fun setProactiveEnabled(enabled: Boolean) {
        store.edit { prefs ->
            if (enabled) prefs[keyProactiveEnabled] = true else prefs.remove(keyProactiveEnabled)
        }
    }

    /** 上次主动关心发送日（epochDay；当天已发过就不再发） */
    suspend fun proactiveLastDay(): Long = store.data.first()[keyProactiveLastDay] ?: 0L

    suspend fun setProactiveLastDay(day: Long) {
        store.edit { it[keyProactiveLastDay] = day }
    }

    /** 本月 API 用量（total_tokens 累计；跨月由 UsageTracker.add 重置）。返回 月键 to tokens */
    val monthlyUsage: Flow<Pair<String, Int>> = store.data.map { prefs ->
        (prefs[keyUsageMonth] ?: "") to (prefs[keyUsageTokens] ?: 0)
    }

    suspend fun addUsageTokens(month: String, tokens: Int) {
        store.edit { prefs ->
            val storedMonth = prefs[keyUsageMonth]
            val base = if (storedMonth == month) prefs[keyUsageTokens] ?: 0 else 0
            prefs[keyUsageMonth] = month
            prefs[keyUsageTokens] = base + tokens
        }
    }

    /** 自动执行 Agent 的安全配置（白名单为逗号分隔包名；空 = 不限制） */
    val agentConfig: Flow<com.menghuan.app.agent.AgentConfig> = store.data.map { prefs ->
        com.menghuan.app.agent.AgentConfig(
            maxSteps = prefs[keyAgentMaxSteps] ?: 15,
            whitelist = prefs[keyAgentWhitelist]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet(),
            sensitiveConfirm = prefs[keyAgentSensitiveConfirm] ?: true,
        )
    }

    suspend fun setAgentWhitelist(packages: Set<String>) {
        store.edit { it[keyAgentWhitelist] = packages.joinToString(",") }
    }

    suspend fun setAgentSensitiveConfirm(enabled: Boolean) {
        store.edit { it[keyAgentSensitiveConfirm] = enabled }
    }

    /** 提炼水位线：每个会话已提炼到的最后一条消息 id（extract_upto_<personaId>） */
    suspend fun extractionWatermark(personaId: Long): Long =
        store.data.first()[longPreferencesKey(watermarkPrefix + personaId)] ?: 0L

    suspend fun setExtractionWatermark(personaId: Long, messageId: Long) {
        store.edit { it[longPreferencesKey(watermarkPrefix + personaId)] = messageId }
    }

    /** 人设删除的级联清理：移除提炼水位线 */
    suspend fun removeExtractionWatermark(personaId: Long) {
        store.edit { it.remove(longPreferencesKey(watermarkPrefix + personaId)) }
    }

    /**
     * 清除全部会话级派生键（未读标记 / 提炼水位线 / 聊天草稿），**保留配置类键**
     * （API 配置 / 开关 / 提示词覆盖 / 会话级接口覆盖 / 主题）。
     * v0.36.5「清除全部数据」用：聊天删了之后这些派生键指向已不存在的消息 id，必须一起清。
     */
    suspend fun clearConversationScopedKeys() {
        store.edit { prefs ->
            prefs.asMap().keys
                .filter { key ->
                    key.name.startsWith(readPrefix) ||
                        key.name.startsWith(watermarkPrefix) ||
                        key.name.startsWith(draftPrefix)
                }
                .forEach { prefs.remove(it) }
        }
    }

    /** 聊天草稿（进程内存 ChatDrafts 的落盘副本；空串 = 清除） */
    suspend fun chatDraft(personaId: Long): String =
        store.data.first()[stringPreferencesKey(draftPrefix + personaId)] ?: ""

    suspend fun setChatDraft(personaId: Long, text: String) {
        store.edit { prefs ->
            val key = stringPreferencesKey(draftPrefix + personaId)
            if (text.isEmpty()) prefs.remove(key) else prefs[key] = text
        }
    }

    // ===== 生图接口（独立于对话接口；AI 回复里出现 [生图:…] 标记时调用） =====

    val imageApiConfig: Flow<ImageApiConfig> = store.data.map { prefs ->
        val sealed = prefs[keyImgKeyEnc]
        ImageApiConfig(
            baseUrl = prefs[keyImgBase] ?: "",
            model = prefs[keyImgModel] ?: "",
            apiKey = if (sealed.isNullOrBlank()) {
                prefs[keyImgKeyPlain] ?: ""
            } else {
                resolveSealed(sealed) ?: (prefs[keyImgKeyPlain] ?: "")
            },
        )
    }.distinctUntilChanged()

    suspend fun saveImageApiConfig(config: ImageApiConfig) = withContext(Dispatchers.IO) {
        val sealed = if (config.apiKey.isEmpty()) null else ApiKeyCipher.encrypt(config.apiKey)
        store.edit { prefs ->
            when {
                sealed != null -> { prefs[keyImgKeyEnc] = sealed; prefs.remove(keyImgKeyPlain) }
                config.apiKey.isNotEmpty() -> {
                    prefs[keyImgKeyPlain] = config.apiKey; prefs.remove(keyImgKeyEnc)
                }
                else -> { prefs.remove(keyImgKeyEnc); prefs.remove(keyImgKeyPlain) }
            }
            prefs[keyImgBase] = config.baseUrl.trim()
            prefs[keyImgModel] = config.model.trim()
        }
    }

    // ===== 外观（跟随系统 / 浅色 / 深色） =====

    /** 主题模式名：SYSTEM / LIGHT / DARK（缺失 = 跟随系统） */
    val themeMode: Flow<String> = store.data.map { prefs ->
        (prefs[keyThemeMode] ?: DEFAULT_THEME_MODE).also { cachedThemeMode = it }
    }

    /**
     * 同步首读（MainActivity 首帧前取）。首次读会落一次磁盘 IO，但这里必须同步——
     * 否则会先亮后暗闪一下（用户反馈「进页面闪烁」的一个来源）。
     */
    fun themeModeBlocking(): String = cachedThemeMode ?: runBlocking {
        store.data.first()[keyThemeMode] ?: DEFAULT_THEME_MODE
    }.also { cachedThemeMode = it }

    suspend fun setThemeMode(mode: String) {
        cachedThemeMode = mode
        store.edit { prefs ->
            if (mode == DEFAULT_THEME_MODE) prefs.remove(keyThemeMode) else prefs[keyThemeMode] = mode
        }
    }

    /**
     * 一键重置：清空全部设置（API 配置 / 开关 / 未读键 / 水位线 / 草稿 / 提示词覆盖）。
     * 数据库表由调用层配合 clearAllTables 处理。
     */
    suspend fun resetAll() {
        store.edit { it.clear() }
        decryptCache.clear()
        cachedThemeMode = DEFAULT_THEME_MODE
    }
}
