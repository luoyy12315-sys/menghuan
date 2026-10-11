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
import com.menghuan.app.live2d.ActionScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** 主题模式默认值：跟随系统（不落盘即为 SYSTEM） */
const val DEFAULT_THEME_MODE = "SYSTEM"

/** AI 主动打电话的默认定时时刻（v0.53.5，用户可在设置里改） */
const val DEFAULT_AI_CALL_TIME = "21:00"

/**
 * 联网搜索服务商（v0.53.5）：博查（国内）/ Tavily（国际）
 * / 自定义（v0.59.0：用户自己填接口地址 + Key，走通用请求形状与兜底解析）
 */
const val SEARCH_BOCHA = "bocha"
const val SEARCH_TAVILY = "tavily"
const val SEARCH_CUSTOM = "custom"

/**
 * 语音识别引擎（v0.59.0）：**手动切换，不做自动回退**。
 *
 * 当年云端 Whisper 就是作为"自动回退的第二级"挂着的，用户配的端点实测都不支持那个接口，
 * 于是每次白等一轮超时、"识别为空"到底哪一级出的问题谁也说不清（AGENTS §零.4）。
 * 这次把它做成一个明示的选择：选在线就只走在线，失败把原因原样报出来。
 */
const val ASR_OFFLINE = "offline"
const val ASR_ONLINE = "online"

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
 * 用户自配的 embeddings 接口（v0.82.0 P1-2，OpenAI 兼容 `/embeddings`）。
 * Key 允许为空：部分自建端点不校验 Authorization（与对话接口不同，不强制三件套齐全）。
 */
data class EmbeddingConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
)

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
    private val keyImgBase = stringPreferencesKey("img_base_url")       // 生图接口（老单条键）
    private val keyImgModel = stringPreferencesKey("img_model")
    private val keyImgKeyEnc = stringPreferencesKey("img_key_enc")
    private val keyImgKeyPlain = stringPreferencesKey("img_key_plain")
    // v0.53.4：生图也支持多条模型（与对话模型同构），老四键作为「默认生图模型」虚拟读出
    private val keyImgProfiles = stringPreferencesKey("img_profiles")
    private val keyImgActiveProfile = stringPreferencesKey("img_active_profile")
    private val keyThemeMode = stringPreferencesKey("theme_mode")
    private val keyNotifyEnabled = booleanPreferencesKey("notify_enabled")
    private val keyDailySummaryEnabled = booleanPreferencesKey("daily_summary_enabled")
    private val keyAgentMaxSteps = intPreferencesKey("agent_max_steps")
    private val keyAgentWhitelist = stringPreferencesKey("agent_whitelist")
    private val keyAgentSensitiveConfirm = booleanPreferencesKey("agent_sensitive_confirm")
    private val keyAgentKeepAlive = booleanPreferencesKey("agent_keep_alive")
    /** Agent 专用模型（空 = 跟随所选接口的模型）；每步决策是"这一屏点哪个"的简单任务，用快模型能省一半延迟 */
    private val keyAgentModel = stringPreferencesKey("agent_model")
    /** Agent 观测层：每步随请求附一张屏幕截图（混合输入，更准但更费图片 token）；API 30+ 才真的生效 */
    private val keyAgentVision = booleanPreferencesKey("agent_vision")
    // v0.42.0：每日建议（独立第三条 AI 产出链路）的提示词策略
    // ⚠️ 老键 `daily_suggestion_notify_enabled` 已删：那个开关**从来没有过 UI 写入口**
    //（v0.42.0 引入后 setter 一直零调用，`?: false` 永远生效）→ 这条通知**一次都没弹过**。
    // 整条链（开关 + 通知通道 + 通知构建）是行为不变的清理，不是删功能。
    private val keyDailySuggestionStrategy = stringPreferencesKey("daily_suggestion_strategy")
    // v0.50.0：语音合成接口（三家可切；通话与 AI 语音条共用同一套配置）
    private val keyTtsProvider = stringPreferencesKey("tts_provider")
    private val keyTtsBaseUrl = stringPreferencesKey("tts_base_url")
    private val keyTtsModel = stringPreferencesKey("tts_model")
    private val keyTtsKeyEnc = stringPreferencesKey("tts_api_key_enc")
    private val keyTtsKeyPlain = stringPreferencesKey("tts_api_key_plain")
    // v0.50.0：按人设的音色，前缀键 tts_voice_<personaId>（空 = 该 provider 的默认预置音色）。
    // 音色属于人设（在编辑人设弹层里选），通话与 AI 语音条共用，不再有单独的"通话设置"。
    // 老键 call_voice_<id> 存的是 Edge 音色名，Edge TTS 已删，读也不读它。
    private val ttsVoicePrefix = "tts_voice_"
    // v0.50.0：语音两个总开关（朗读功能已删）——**v0.53.4 已整块删除**，见下方语音合成段
    // v0.51.1：内置「应用助手」手册是否被用户亲手改过（键缺失 = 从未判定，见 assistantPromptCustomized）
    private val keyAssistantPromptCustomized = booleanPreferencesKey("assistant_prompt_customized")
    // v0.53.0：克隆音色的中文显示名表（服务端 customName 只收 ASCII，见 cloneVoiceNames）
    private val keyCloneVoiceNames = stringPreferencesKey("tts_clone_names")

    /** 会话已读标记前缀：last_read_<personaId> = 最后阅读时刻毫秒值 */
    private val readPrefix = "last_read_"

    /** 角色五轴状态前缀：companion_state_<personaId> = 编码串（见 CompanionLogic.encode） */
    private val companionStatePrefix = "companion_state_"

    /** 提炼水位线前缀：extract_upto_<personaId> = 已提炼到的最后一条消息 id */
    private val watermarkPrefix = "extract_upto_"

    /** 上下文窗口前缀：history_budget_<personaId> = 该会话记住最近多少字（v0.86.0） */
    private val historyBudgetPrefix = "history_budget_"

    /** 提炼间隔前缀：extract_every_<personaId> = 该会话攒够几轮提炼一次（v0.86.0） */
    private val extractEveryPrefix = "extract_every_"

    /** 聊天草稿前缀：draft_<personaId> = 未发送的输入（进程被杀后恢复） */
    private val draftPrefix = "draft_"

    companion object {
        /**
         * [decryptCache] 的容量上限（v0.97.0，审查 B37）。
         *
         * 64 远超实际需要：真正会被反复读的就是当前生效的那一两条 API Key，
         * 其余（历史接口配置）命中率极低，被淘汰也只是多解一次密（十几毫秒，且不在热路径）。
         */
        private const val MAX_DECRYPT_CACHE_ENTRIES = 64
    }

    /**
     * Keystore 解密有成本（真机一次可达十几毫秒）：密文 → 明文的进程级缓存。
     * 同一条密文整个进程生命周期只解一次；后续 DataStore 重发（进聊天页 markChatRead
     * 就会触发一次）全部走内存，不再在收集方线程上同步 binder 到 keystore2。
     * JVM 单测下 Keystore 不可用，解密失败也照缓存（空串），调用方回退明文字段的语义不变。
     *
     * ⚠️ v0.97.0（审查 B37）：加了**容量上限**。原来是裸 `ConcurrentHashMap`，只在
     * `resetAll()` 里清空 —— 而每次保存/更换 API Key 都会产生一个**新的密文**
     * （AES/GCM 的 IV 每次都不同），老的键值对永远留着。频繁换 Key 的用户会让**明文**
     * 在内存里只增不减（既是内存问题，也是"明文多留一份"的问题）。
     * 改用访问序 LRU：命中会刷新顺序，容量 64 远超实际需要（真正热的就是当前那条），
     * 淘汰的代价只是下次多解一次密。
     */
    private val decryptCache: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(
            object : java.util.LinkedHashMap<String, String>(16, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, String>?,
                ): Boolean = size > MAX_DECRYPT_CACHE_ENTRIES
            },
        )

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

    /** 当前生效的对话接口：聊天 / 提炼 / Agent 全读这一个 */
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
    private fun resolveSealed(sealed: String): String? {
        if (sealed.isBlank()) return null
        // `get`（而不是 containsKey + get）才会刷新 LRU 的访问顺序
        val cached = decryptCache[sealed]
        if (cached != null) return cached.ifEmpty { null }
        val plain = ApiKeyCipher.decrypt(sealed) ?: ""
        decryptCache[sealed] = plain
        return plain.ifEmpty { null }
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
            ApiProfileCodec.decode(prefs[keyImgProfiles] ?: "").forEach { resolveSealed(it.keyEnc) }
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

    // ===== 角色情绪状态（v0.72.0）=====
    /** 五轴状态串；编码格式见 [com.menghuan.app.data.CompanionLogic.encode]。空串 = 还没算过 */
    fun companionStateRaw(personaId: Long): Flow<String> =
        store.data.map { it[stringPreferencesKey(companionStatePrefix + personaId)] ?: "" }

    suspend fun setCompanionStateRaw(personaId: Long, raw: String) {
        store.edit { it[stringPreferencesKey(companionStatePrefix + personaId)] = raw }
    }

    /**
     * 全部角色的五轴状态串（v0.74.0）：personaId → 编码串。
     *
     * ⚠️ **会话列表一次读全表**，不许对每个角色各开一条 [companionStateRaw] ——
     * 会话列表会随角色数增长，N 条 DataStore flow 在组合层做 N 次订阅，
     * 每次落盘都要遍历一遍，内存与重组次数都随角色数线性涨（`readMarks` 同理已走批量）。
     *
     * 前缀提取与 [readMarks] 一致：只有一个 [store] 的 `data` 是真源，
     * 单角色读写仍走 [companionStateRaw] / [setCompanionStateRaw]。
     */
    val companionStates: Flow<Map<Long, String>> = store.data.map { prefs ->
        buildMap {
            prefs.asMap().forEach { (key, value) ->
                if (key.name.startsWith(companionStatePrefix) && value is String) {
                    key.name.removePrefix(companionStatePrefix).toLongOrNull()?.let { put(it, value) }
                }
            }
        }
    }

    /**
     * `companion_last_msg_<personaId>` 的键名（v0.97.0，审查 B39）。
     *
     * 这个字面量原来在**读 / 写 / 清**三处各写一遍。运行期拼接键拼错是不会报错的：
     * 改一处漏一处，就是"删角色时清了状态、但这个时间戳还在"这种静默不一致 ——
     * 收敛成一个函数后，"三处必须同名"由类型系统保证，而不是靠人眼。
     */
    private fun companionLastMsgKey(personaId: Long) =
        longPreferencesKey("companion_last_msg_$personaId")

    /**
     * 上一次收到用户消息的时刻。连接需求看的是「离上次他说话多久」，
     * 不是「离上次算漂移多久」—— 一次长关机（两天没打开 App）不能只按这一次的长度算。
     */
    fun companionLastMessageAt(personaId: Long): Flow<Long> =
        store.data.map { it[companionLastMsgKey(personaId)] ?: 0L }

    suspend fun markCompanionMessageAt(personaId: Long, atMs: Long) {
        store.edit { it[companionLastMsgKey(personaId)] = atMs }
    }

    /** 人设删除时级联清掉（与 [removeReadMark] 同批调用） */
    suspend fun removeCompanionState(personaId: Long) {
        store.edit {
            it.remove(stringPreferencesKey(companionStatePrefix + personaId))
            it.remove(companionLastMsgKey(personaId))
        }
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

    // ===== AI 提示词（设置 · AI 提示词，可编辑；默认值见 AiPrompts） =====

    private val keyBasePrompt = stringPreferencesKey("base_prompt")
    private val keyProfilePreamble = stringPreferencesKey("profile_preamble")
    /**
     * 「个人档案全部 AI 可读」开关（v0.70.0）。
     *
     * 记忆按人设分开之后，**基础档案是那一档跨角色共享的**（`personaId = 0`）。
     * 这个开关决定它到底给不给所有 AI 读：默认给（保持老行为，也符合"档案本来就是讲我这个人"）。
     * 关掉之后每个角色只剩自己的记忆 —— 有人就是不想让新认识的角色知道自己以前说过什么。
     */
    private val keyProfileSharedReadable = booleanPreferencesKey("profile_shared_readable")
    // v0.36.0：三段"策略"提示词（框架部分硬编码在 ProfileExtractor / ChatViewModel）
    private val keyExtractionStrategy = stringPreferencesKey("extraction_strategy")
    private val keyImageHintStrategy = stringPreferencesKey("image_hint_strategy")
    // v0.88.0：AI 洞察策略从「记忆提炼策略」里拆出来（洞察不再由提炼副产，选材口径无关了）
    private val keyInsightStrategy = stringPreferencesKey("insight_strategy")
    // v0.37.0：对话内操控手机（总开关默认开 = 用户授了完全权限；关掉即不注入能力提示）
    private val keyAgentChatEnabled = booleanPreferencesKey("agent_chat_enabled")
    private val keyAgentChatStrategy = stringPreferencesKey("agent_chat_strategy")
    // v0.50.0：AI 语音条策略（标记格式 【语音条】 硬编码在 VOICE_NOTE_FRAMEWORK）
    private val keyVoiceNoteStrategy = stringPreferencesKey("voice_note_strategy")
    // v0.98.0：主动关心策略（硬约束 HINT_FRAMEWORK 硬编码在 ProactiveChat）
    private val keyProactiveStrategy = stringPreferencesKey("proactive_strategy")
    // v0.98.0：记忆记录策略（硬约束 HINT_FRAMEWORK 硬编码在 MemoryMarker；
    // ⚠️ 与 keyExtractionStrategy「记忆提炼策略」是两条入口，别搞混）
    private val keyMemoryMarkerStrategy = stringPreferencesKey("memory_marker_strategy")
    // v0.82.0 P1-1：滚动摘要（总开关默认开 + 摘要策略可编辑，框架硬编码在 SummaryExtractor）
    private val keyRollingSummaryEnabled = booleanPreferencesKey("rolling_summary_enabled")
    private val keySummaryStrategy = stringPreferencesKey("summary_strategy")
    // v0.82.0 P1-2：记忆语义召回（OpenAI 兼容 /embeddings，默认关，地址/Key/模型用户自己填）
    private val keyEmbeddingEnabled = booleanPreferencesKey("embedding_enabled")
    private val keyEmbeddingBaseUrl = stringPreferencesKey("embedding_base_url")
    private val keyEmbeddingModel = stringPreferencesKey("embedding_model")
    private val keyEmbeddingKeyEnc = stringPreferencesKey("embedding_key_enc")
    private val keyEmbeddingKeyPlain = stringPreferencesKey("embedding_key_plain")
    // v0.38.0：Agent 进度通知 / token 用量记账
    private val keyAgentNotifyEnabled = booleanPreferencesKey("agent_notify_enabled")
    // v0.54.4：人设主动关心改为**按角色**（旧的两个全局键 proactive_enabled / proactive_last_day 已删除）
    private val keyPersonaProactive = stringPreferencesKey("persona_proactive")
    private val keyPersonaProactiveLastDay = stringPreferencesKey("persona_proactive_last_day")
    /**
     * 主动关心的**时间段**与**每天最多几条**（v0.56.8 第二批：**每个角色各一套**）。
     *
     * 值按 `id\t起|止` / `id\t条数` 的行式表存 —— 角色 A 20:00–23:00、角色 B 09:00–11:00 互不影响。
     * （更早那两个全局键 `proactive_window_start/end` 与 `proactive_daily_limit` 只在未出包的
     *   v0.56.8 里存在过，这里直接不建，不留死键。）
     */
    private val keyPersonaProactiveWindow = stringPreferencesKey("persona_proactive_window")
    private val keyPersonaProactiveLimit = stringPreferencesKey("persona_proactive_limit")
    // v0.94：主动关心的另外两个独立开关（发帖/打电话）与电话的每日次数/当日计数
    private val keyPersonaProactiveMoments = stringPreferencesKey("persona_proactive_moments")
    private val keyPersonaProactiveCall = stringPreferencesKey("persona_proactive_call")
    private val keyPersonaProactiveCallLimit = stringPreferencesKey("persona_proactive_call_limit")
    private val keyPersonaProactiveCallDay = stringPreferencesKey("persona_proactive_call_day")
    // v0.53.5：AI 主动打电话
    private val keyAiCallEnabled = booleanPreferencesKey("ai_call_enabled")
    private val keyAiCallTime = stringPreferencesKey("ai_call_time")
    private val keyAiCallIdleDays = intPreferencesKey("ai_call_idle_days")
    private val keyAiCallPersonaId = longPreferencesKey("ai_call_persona_id")
    // ⚠️ `keyAiCallCountDay` / `keyAiCallCount` 已删：它们是老「几天没聊就主动打」的当日计数，
    // v0.94 那条链整段砍掉后（当日次数改走 `keyPersonaProactiveCallDay`）就零引用了。
    /** v0.53.6：每人设一套「主动打电话」规则（JSON） */
    private val keyAiCallRules = stringPreferencesKey("ai_call_rules")
    // v0.53.5：定时主动消息 + 联网搜索
    private val keyScheduledMessages = stringPreferencesKey("scheduled_messages")
    private val keySearchProvider = stringPreferencesKey("search_provider")
    // ⚠️ v0.66.0 起这两个是**只读的老单桶**（博查与 Tavily 当年共用一把 Key）。
    // 现行写法是按服务商分桶；老键只虚拟归属到"当时生效的那一家"，任何一次保存都会把它搬进桶并清掉。
    private val keySearchKeyEnc = stringPreferencesKey("search_key_enc")
    private val keySearchKeyPlain = stringPreferencesKey("search_key_plain")
    // v0.66.0：视频通话背景（按角色，`id\t档位` 行式表；档位见 [CallBackgroundStore]）
    private val keyCallBackground = stringPreferencesKey("call_background")
    // v0.67.0：这一角色通话时的动作幅度倍率（`id\t倍率` 行式表，缺省 1.0）
    private val keyCallActionScale = stringPreferencesKey("call_action_scale")
    // v0.59.0：在线语音识别（引擎切换 + 三家共用的通用 OpenAI 兼容形状）
    private val keyAsrEngine = stringPreferencesKey("asr_engine")
    private val keyAsrOnlineUrl = stringPreferencesKey("asr_online_url")
    private val keyAsrOnlineKeyEnc = stringPreferencesKey("asr_online_key_enc")
    private val keyAsrOnlineKeyPlain = stringPreferencesKey("asr_online_key_plain")
    private val keyAsrOnlineModel = stringPreferencesKey("asr_online_model")
    /** v0.59.0：自动朗读 AI 回复（按角色一条 `id\t1|0`，默认全关；长按菜单的单条朗读不受它管） */
    private val keyChatReadAloudPersona = stringPreferencesKey("chat_read_aloud_personas")
    // v0.53.6：会话级资产（`id\t值` 行式小表）
    private val keyPersonaArt = stringPreferencesKey("persona_art")
    private val keyPersonaLive2d = stringPreferencesKey("persona_live2d")
    private val keyChatBackground = stringPreferencesKey("chat_background")
    private val keyChatWebSearch = stringPreferencesKey("chat_web_search")
    // ===== v0.80.0：聊天外观（推倒重做：明暗各一套气泡配色 + 字体族/字号/字重） =====
    //
    // 为什么全局而不是按角色：气泡是"整个 App 的观感"，不是某个角色的性格属性；
    // 按角色存会让用户想统一改一次要挨个角色点一遍，且和「音色/联网」那种
    // 有语义归属的按角色设置不同 —— 那两样是"这个角色该不该这样"，气泡是"我想怎么看"。
    //
    // 颜色存 [ChatAppearance.BubbleStyle.encode] 的 `bg|ink|border|radius` 串，
    // 0L 段 = "跟主题"哨兵（背景跟主题令牌、字色按亮度自动、无边框）。
    /** 浅色主题 · 自己的气泡（v0.80.0，用户拍板明暗各存一套） */
    private val keyChatBubbleLightMine = stringPreferencesKey("chat_bubble_light_mine")
    /** 浅色主题 · 对方的气泡 */
    private val keyChatBubbleLightOther = stringPreferencesKey("chat_bubble_light_other")
    /** 深色主题 · 自己的气泡 */
    private val keyChatBubbleDarkMine = stringPreferencesKey("chat_bubble_dark_mine")
    /** 深色主题 · 对方的气泡 */
    private val keyChatBubbleDarkOther = stringPreferencesKey("chat_bubble_dark_other")
    /** 字体族：sans(默认) / serif / mono */
    private val keyChatFontFamily = stringPreferencesKey("chat_font_family")
    /** 气泡正文字号（sp，12~22；旧版的三档枚举已删） */
    private val keyChatFontSizeSp = intPreferencesKey("chat_font_size_sp")
    /** 字重：normal(默认) / medium / semibold / bold */
    private val keyChatFontWeight = stringPreferencesKey("chat_font_weight")
    /** 是否在气泡旁显示头像（默认显示；键沿用 v0.77 的旧键，开关语义没变） */
    private val keyChatShowAvatar = booleanPreferencesKey("chat_show_avatar")
    // ===== v0.80.0：我自己的档案卡（昵称 + 一句话简介）=====
    //
    // 「我的页顶部那张卡」与「个人档案页」读的就是这两个键。与 [UserAvatar] 配套：
    // 头像 + 昵称 + 简介 = 一张完整的档案卡。空串 = 没填过，UI 走兜底文案（不显示空白行）。
    private val keyProfileNickname = stringPreferencesKey("profile_nickname")
    private val keyProfileBio = stringPreferencesKey("profile_bio")
    /** 用户自己的头像 emoji（与头像图片二选一，图片优先） */
    private val keyUserAvatarEmoji = stringPreferencesKey("user_avatar_emoji")
    /** 用户自己的头像图片绝对路径（非空时优先于 emoji） */
    private val keyUserAvatarImage = stringPreferencesKey("user_avatar_image")
    /** v0.53.7：语音通话铃声（自定义音频文件路径；空 = 用系统默认铃声） */
    private val keyCallRingtonePath = stringPreferencesKey("call_ringtone_path")
    /** v0.54.4：本机添加的铃声库（文件路径，每行一个） */
    private val keyCallRingtoneLibrary = stringPreferencesKey("call_ringtone_library")
    /** v0.54.4：每个角色的专属铃声（`id\t路径`） */
    private val keyPersonaRingtone = stringPreferencesKey("persona_call_ringtone")
    /** v0.53.7：我打给 AI 时，TA 隔几秒才接听（默认 3 秒；0 = 立刻接） */
    private val keyAiAnswerDelay = intPreferencesKey("ai_answer_delay_seconds")
    private val keyUsageMonth = stringPreferencesKey("usage_month")
    private val keyUsageTokens = intPreferencesKey("usage_tokens")
    /**
     * 本月**输入** token 累计与其中**缓存命中**的累计（v0.69.0）。
     * 只为一件事：把"前缀到底稳不稳"变成屏幕上看得见的一个百分比。
     * 跨月口径与 [keyUsageTokens] 完全一致（同一个 [keyUsageMonth] 判定）。
     */
    private val keyUsagePromptTokens = intPreferencesKey("usage_prompt_tokens")
    private val keyUsageCachedTokens = intPreferencesKey("usage_cached_tokens")
    /** 本月用量按模型分表（v0.56.7）：`月键\t模型\ttokens` 一行一条 */
    private val keyUsageByModel = stringPreferencesKey("usage_by_model")

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

    /** 基础档案（`personaId = 0`）要不要注入给所有角色（v0.70.0，默认 true） */
    val profileSharedReadable: Flow<Boolean> =
        store.data.map { it[keyProfileSharedReadable] ?: true }

    suspend fun setProfileSharedReadable(enabled: Boolean) {
        store.edit { prefs -> prefs[keyProfileSharedReadable] = enabled }
    }

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
     * AI 记忆提炼策略（框架 JSON schema 硬编码在 [ProfileExtractor.FRAMEWORK]）。
     * 用户改这段只影响"什么该记 / 什么不该记"的策略，不会破坏 parse 逻辑。
     *
     * ⚠️ v0.88.0 起这条只管**记忆**：以前设置页把它叫「AI 洞察策略」，
     * 但它实际控的是提炼（facts）+ 洞察 + 任务建议三件事。洞察已挪去 [insightStrategy]。
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

    /**
     * 主动关心策略（v0.98.0）。
     *
     * 这一段在 v0.98.0 之前是 `ProactiveChat` 里的一句**死代码**（零引用），
     * 所以那时用户改不了、约束也没生效。现在接上并开放编辑：
     * 取值范围 = 取材、语气、长度（硬约束 `ProactiveChat.HINT_FRAMEWORK` 仍不可编）。
     */
    val proactiveStrategy: Flow<String> =
        store.data.map { it[keyProactiveStrategy] ?: ProactiveChat.DEFAULT_STRATEGY }

    suspend fun setProactiveStrategy(text: String) {
        store.edit { prefs ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == ProactiveChat.DEFAULT_STRATEGY) {
                prefs.remove(keyProactiveStrategy)
            } else {
                prefs[keyProactiveStrategy] = trimmed
            }
        }
    }

    /**
     * 记忆记录策略（v0.98.0）：聊天时 AI 随手记（`<add:…>`）该记什么、以什么视角记。
     *
     * ⚠️ 与 [extractionStrategy]「记忆提炼策略」是记忆的**两条入口**，别搞混：
     * 这段注入**聊天 system prompt**（每轮都在），管"聊天当下要不要记"；
     * 那段只在**批量提炼请求**里发给提炼模型，管"攒够轮数后从对话记录里提炼哪些"。
     * 用户抱怨"记太多/记太快"时该改的是这一段，不是那段，也不是提炼间隔。
     */
    val memoryMarkerStrategy: Flow<String> =
        store.data.map { it[keyMemoryMarkerStrategy] ?: MemoryMarker.DEFAULT_STRATEGY }

    suspend fun setMemoryMarkerStrategy(text: String) {
        store.edit { prefs ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == MemoryMarker.DEFAULT_STRATEGY) {
                prefs.remove(keyMemoryMarkerStrategy)
            } else {
                prefs[keyMemoryMarkerStrategy] = trimmed
            }
        }
    }

    /** AI 语音条策略（标记格式 【语音条】 硬编码在 VOICE_NOTE_FRAMEWORK） */
    val voiceNoteStrategy: Flow<String> =
        store.data.map { it[keyVoiceNoteStrategy] ?: com.menghuan.app.ui.chat.VOICE_NOTE_DEFAULT_STRATEGY }

    suspend fun setVoiceNoteStrategy(text: String) {
        store.edit { prefs ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == com.menghuan.app.ui.chat.VOICE_NOTE_DEFAULT_STRATEGY) {
                prefs.remove(keyVoiceNoteStrategy)
            } else {
                prefs[keyVoiceNoteStrategy] = trimmed
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

    /**
     * 滚动摘要总开关（v0.82.0 P1-1，**默认开**）。
     *
     * 关掉 = 不压缩、不注入（已有的摘要留在库里不动，重新打开即恢复）；
     * 摘要在「会话设置 → 对话摘要」里按会话查看/删除。
     */
    val rollingSummaryEnabled: Flow<Boolean> =
        store.data.map { it[keyRollingSummaryEnabled] ?: true }

    suspend fun setRollingSummaryEnabled(enabled: Boolean) {
        store.edit { prefs ->
            if (enabled) prefs.remove(keyRollingSummaryEnabled) else prefs[keyRollingSummaryEnabled] = false
        }
    }

    /** 对话摘要策略（框架"已有摘要+新增对话→自包含新摘要"硬编码在 [SummaryExtractor.FRAMEWORK]） */
    val summaryStrategy: Flow<String> =
        store.data.map { it[keySummaryStrategy] ?: SummaryExtractor.DEFAULT_STRATEGY }

    suspend fun setSummaryStrategy(text: String) {
        store.edit { prefs ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == SummaryExtractor.DEFAULT_STRATEGY) {
                prefs.remove(keySummaryStrategy)
            } else {
                prefs[keySummaryStrategy] = trimmed
            }
        }
    }

    // ===== v0.82.0 P1-2：记忆语义召回（embeddings）=====
    //
    // 与纯词法召回是**并列的两条路**，靠这个开关手动切；
    // 地址/Key/模型都由用户自己填（不猜服务商，AGENTS §零.6）。**绝不做自动回落**——
    // 开了而端点调不通时，本轮召回退回纯词法、但不偷偷把开关关掉（与 ASR 双引擎同一纪律）。

    /** 语义召回开关（默认关：开了之后记忆内容会发给用户自配的 embeddings 服务商） */
    val embeddingEnabled: Flow<Boolean> = store.data.map { it[keyEmbeddingEnabled] ?: false }

    suspend fun setEmbeddingEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            if (enabled) prefs[keyEmbeddingEnabled] = true else prefs.remove(keyEmbeddingEnabled)
        }
    }

    val embeddingBaseUrl: Flow<String> = store.data.map { it[keyEmbeddingBaseUrl] ?: "" }

    suspend fun setEmbeddingBaseUrl(url: String) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            if (url.isBlank()) prefs.remove(keyEmbeddingBaseUrl) else prefs[keyEmbeddingBaseUrl] = url.trim()
        }
    }

    val embeddingModel: Flow<String> = store.data.map { it[keyEmbeddingModel] ?: "" }

    suspend fun setEmbeddingModel(model: String) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            if (model.isBlank()) prefs.remove(keyEmbeddingModel) else prefs[keyEmbeddingModel] = model.trim()
        }
    }

    /** embeddings 的 Key（密文优先，规矩与其余 Key 一致） */
    val embeddingApiKey: Flow<String> = store.data.map { prefs ->
        val sealed = prefs[keyEmbeddingKeyEnc]
        if (sealed.isNullOrBlank()) {
            prefs[keyEmbeddingKeyPlain] ?: ""
        } else {
            resolveSealed(sealed) ?: (prefs[keyEmbeddingKeyPlain] ?: "")
        }
    }

    suspend fun setEmbeddingApiKey(key: String) = withContext(Dispatchers.IO) {
        val sealed = if (key.isEmpty()) null else ApiKeyCipher.encrypt(key)
        store.edit { prefs ->
            when {
                sealed != null -> {
                    prefs[keyEmbeddingKeyEnc] = sealed; prefs.remove(keyEmbeddingKeyPlain)
                }
                key.isNotEmpty() -> {
                    prefs[keyEmbeddingKeyPlain] = key; prefs.remove(keyEmbeddingKeyEnc)
                }
                else -> {
                    prefs.remove(keyEmbeddingKeyEnc); prefs.remove(keyEmbeddingKeyPlain)
                }
            }
        }
    }

    /** 语义召回是否可发请求（开关 + 三件套齐全）；向量按 "baseUrl|model" 分桶存，见 [embeddingBucketOf] */
    suspend fun embeddingConfigOrNull(): EmbeddingConfig? {
        if (!embeddingEnabled.first()) return null
        val url = embeddingBaseUrl.first()
        val model = embeddingModel.first()
        if (url.isBlank() || model.isBlank()) return null
        return EmbeddingConfig(url, embeddingApiKey.first(), model)
    }

    /** 当前配置的分桶键：换地址或换模型后旧向量整体作废（读取带桶过滤，桶外由后台重算补齐） */
    fun embeddingBucketOf(config: EmbeddingConfig): String = "${config.baseUrl}|${config.model}"

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

    /**
     * AI 洞察策略（v0.88.0 从「记忆提炼策略」里拆出来）。
     *
     * 拆分原因就是本次重构的核心：以前洞察是提炼对话的副产品，两份策略由同一段文字管；
     * 现在洞察改从「记忆 + 任务 + 记账」归纳，选材口径与「什么该记进档案」完全无关了，
     * 继续共用一个旋钮会让用户改一头痛另一头。框架 JSON schema 硬编码在 [InsightMath.FRAMEWORK]。
     */
    val insightStrategy: Flow<String> =
        store.data.map { it[keyInsightStrategy] ?: InsightMath.DEFAULT_STRATEGY }

    suspend fun setInsightStrategy(text: String) {
        store.edit { prefs ->
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == InsightMath.DEFAULT_STRATEGY) {
                prefs.remove(keyInsightStrategy)
            } else {
                prefs[keyInsightStrategy] = trimmed
            }
        }
    }

    // 每日建议生成后的通知那一套（`dailySuggestionNotifyEnabled` + `setDailySuggestionNotifyEnabled`）
    // 已整段删除 —— 见上面 key 处的说明：开关从引入起就零写入口，通知从未执行过。

    /**
     * 人设专属音色（通话与 AI 语音条**共用一套**）。空 = 用当前 provider 的第一个预置音色。
     *
     * v0.50.0 起存 [VoiceRef]（`<PROVIDER>|<voiceId>`）而不是 Edge 音色名。
     * 老键 `call_voice_<id>` 里的值（如 `zh-CN-XiaoxiaoNeural`）不带 provider 段，
     * [VoiceRef.decode] 解不出来自然回落默认 —— 这就是方案要求的"忽略并回落、不报错不崩"，
     * 不需要额外迁移代码。
     */
    fun voiceRefFor(personaId: Long): Flow<VoiceRef?> =
        store.data.map { VoiceRef.decode(it[stringPreferencesKey("$ttsVoicePrefix$personaId")]) }

    /** 传 null = 恢复默认音色 */
    suspend fun setVoiceRefFor(personaId: Long, ref: VoiceRef?) {
        store.edit {
            val key = stringPreferencesKey("$ttsVoicePrefix$personaId")
            if (ref == null || ref.isBlank) it.remove(key) else it[key] = ref.encode()
        }
    }

    /**
     * 这个会话该用哪个音色：**人设专属 → 「音色板块」默认 → 该家第一个预置**（v0.52.0）。
     *
     * 人设那份必须是**当前生效 provider** 的：用户换了服务商之后旧音色 ID 在新家根本不认。
     * 中间这层是新增的——用户想让所有 AI 都用同一个自己克隆的声音，在板块里点一下就行，
     * 不用挨个人设改；通话与语音条都走这里，两边音色一致。
     */
    suspend fun voiceForConversation(personaId: Long, config: TtsConfig): VoiceRef {
        val personaVoice = voiceRefFor(personaId).first()
            ?.takeIf { it.provider == config.provider && !it.isBlank }
        if (personaVoice != null) return personaVoice
        val boardDefault = ttsDefaultVoice(config.provider).first()?.takeIf { !it.isBlank }
        return boardDefault ?: VoiceRef(config.provider, TtsVoices.defaultVoiceId(config.provider))
    }

    /** 人设删除的级联清理：移除音色配置 */
    suspend fun removeVoiceRef(personaId: Long) {
        store.edit { it.remove(stringPreferencesKey("$ttsVoicePrefix$personaId")) }
    }

    // v0.53.4：原先的「AI 语音条」「语音通话」两个总开关已删除（键也一并去掉）——
    // 用户点名"本来就是默认打开的，我也不会关闭这些"，留着开关只让语音页多两行。
    // 功能固定开启：语音条能力只在 TTS 配齐时注入，通话入口恒显示。

    // ===== 语音合成接口（v0.50.0 三家；v0.52.0 起**每家一套独立配置**） =====

    /**
     * 每家自己的键（`tts_siliconflow_base_url` 这种）。
     * v0.50.0 只有一套 `tts_*`，切服务商时 Key/地址/模型 全跟着串味——
     * 用户在硅基填好的内容会原样出现在小米那页（真机反馈"切换就应该像切换 3 个不同界面"）。
     */
    private fun ttsKey(provider: TtsProvider, field: String) =
        stringPreferencesKey("tts_${provider.name.lowercase()}_$field")

    /**
     * 读某一家。老单桶数据只属于**当时生效的那一家**（`tts_provider`），所以：
     * 该家桶里一个字段都没有、且这家正是老数据的东家 → 把老键**虚拟**当成它的内容读出来，
     * 升级后零操作（与多接口配置 `ApiProfileCodec` 同一套路）；桶里有字段就只认桶。
     */
    private fun readTtsConfig(prefs: Preferences, provider: TtsProvider): TtsConfig {
        val enc = prefs[ttsKey(provider, "api_key_enc")]
        val plain = prefs[ttsKey(provider, "api_key_plain")]
        val base = prefs[ttsKey(provider, "base_url")]
        val model = prefs[ttsKey(provider, "model")]
        val legacyOwner = TtsProvider.fromName(prefs[keyTtsProvider])
        // 只有"这家从没写过"才回退老键，不能逐字段混着读——混读会让用户看到半新半旧的一屏。
        // 用 contains 而不是判空：用户点「删除这家配置」写入的是空串，键在=写过，不能被老键复活
        val untouched = !prefs.contains(ttsKey(provider, "api_key_enc")) &&
            !prefs.contains(ttsKey(provider, "api_key_plain")) &&
            !prefs.contains(ttsKey(provider, "base_url")) &&
            !prefs.contains(ttsKey(provider, "model"))
        val fromLegacy = untouched && provider == legacyOwner
        val sealed = if (fromLegacy) prefs[keyTtsKeyEnc] else enc
        val plainText = if (fromLegacy) prefs[keyTtsKeyPlain] else plain
        return TtsConfig(
            provider = provider,
            baseUrl = (if (fromLegacy) prefs[keyTtsBaseUrl] else base).orEmpty(),
            model = (if (fromLegacy) prefs[keyTtsModel] else model).orEmpty(),
            apiKey = when {
                // Key 走 Keystore；不可用时回退明文（AGENTS §五.4，与对话/生图接口同一套规矩）
                !sealed.isNullOrBlank() -> resolveSealed(sealed) ?: plainText.orEmpty()
                else -> plainText.orEmpty()
            },
        )
    }

    /** 当前生效那家的配置（通话与 AI 语音条都读这条） */
    val ttsConfig: Flow<TtsConfig> = store.data.map { prefs ->
        readTtsConfig(prefs, TtsProvider.fromName(prefs[keyTtsProvider]))
    }.distinctUntilChanged()

    /** 指定那一家的配置（语音合成页切 tab 时用，每家看到的是自己那份） */
    fun ttsConfigOf(provider: TtsProvider): Flow<TtsConfig> =
        store.data.map { readTtsConfig(it, provider) }.distinctUntilChanged()

    /**
     * 保存某一家的配置。
     *
     * **只有配齐了才把"当前生效"切到这家**（[TtsConfig.isComplete]）：
     * 切到小米但 Key 还没填就保存，会把通话与语音条一起变成"未配置"，
     * 那是用户没预期的副作用；配齐了才切，没配齐就只存不改生效家。
     * 同时把老单桶键清掉——数据已经落到分桶，留着会在字段被删空时被虚拟读出"复活"。
     */
    suspend fun saveTtsConfig(config: TtsConfig) = withContext(Dispatchers.IO) {
        val provider = config.provider
        val sealed = if (config.apiKey.isEmpty()) null else ApiKeyCipher.encrypt(config.apiKey)
        store.edit { prefs ->
            // 动老键之前先把老数据**落到它那一家的桶**里——否则保存别的家时把老键清掉，
            // 硅基那份填好的 Key 就凭空消失了（写这条的时候测试直接抓到）
            migrateLegacyTtsLocked(prefs)
            when {
                sealed != null -> {
                    prefs[ttsKey(provider, "api_key_enc")] = sealed
                    prefs.remove(ttsKey(provider, "api_key_plain"))
                }

                config.apiKey.isNotEmpty() -> {
                    prefs[ttsKey(provider, "api_key_plain")] = config.apiKey
                    prefs.remove(ttsKey(provider, "api_key_enc"))
                }

                else -> {
                    prefs.remove(ttsKey(provider, "api_key_enc"))
                    prefs.remove(ttsKey(provider, "api_key_plain"))
                }
            }
            prefs[ttsKey(provider, "base_url")] = config.baseUrl.trim()
            prefs[ttsKey(provider, "model")] = config.model.trim()
            prefs.remove(keyTtsBaseUrl)
            prefs.remove(keyTtsModel)
            prefs.remove(keyTtsKeyEnc)
            prefs.remove(keyTtsKeyPlain)
            val active = TtsProvider.fromName(prefs[keyTtsProvider])
            when {
                config.isComplete -> prefs[keyTtsProvider] = provider.name
                // 把"当前生效那家"清空了（UI 里的删除就是这条路径）：不留在哑火上，
                // 否则通话与语音条会一起变成"未配置"却看不出原因
                active == provider -> TtsProvider.entries
                    .firstOrNull { it != provider && readTtsConfig(prefs, it).isComplete }
                    ?.let { prefs[keyTtsProvider] = it.name }
                    ?: run { prefs[keyTtsProvider] = provider.name }
            }
        }
    }

    /** 老单桶 → 那一家自己的桶（只在"这家从没写过"时搬，搬完由调用方清老键） */
    private fun migrateLegacyTtsLocked(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        val owner = TtsProvider.fromName(prefs[keyTtsProvider])
        val untouched = !prefs.contains(ttsKey(owner, "api_key_enc")) &&
            !prefs.contains(ttsKey(owner, "api_key_plain")) &&
            !prefs.contains(ttsKey(owner, "base_url")) &&
            !prefs.contains(ttsKey(owner, "model"))
        if (!untouched) return
        listOf(
            keyTtsKeyEnc to ttsKey(owner, "api_key_enc"),
            keyTtsKeyPlain to ttsKey(owner, "api_key_plain"),
            keyTtsBaseUrl to ttsKey(owner, "base_url"),
            keyTtsModel to ttsKey(owner, "model"),
        ).forEach { (old, new) ->
            prefs[old]?.let { value -> prefs[new] = value }
        }
    }

    /** 切换"当前生效"的那一家（不碰任何一家的内容） */
    suspend fun setActiveTtsProvider(provider: TtsProvider) {
        store.edit { it[keyTtsProvider] = provider.name }
    }

    // ===== 音色（v0.52.0「音色板块」：默认音色 + 手填 ID，每家一份） =====

    /** 该家的默认音色（人设没单独配音色时用它；空 = 该家第一个预置） */
    fun ttsDefaultVoice(provider: TtsProvider): Flow<VoiceRef?> = store.data.map { prefs ->
        VoiceRef.decode(prefs[ttsKey(provider, "default_voice")])
    }

    /** 传 null = 恢复"用该家第一个预置" */
    suspend fun setTtsDefaultVoice(ref: VoiceRef?) {
        val key = ttsKey(ref?.provider ?: TtsProvider.SILICONFLOW, "default_voice")
        store.edit { prefs ->
            if (ref == null || ref.isBlank) prefs.remove(key) else prefs[key] = ref.encode()
        }
    }

    /** 该家手填的音色 ID（板块里"手动音色"那一行；空 = 不显示） */
    fun ttsManualVoiceId(provider: TtsProvider): Flow<String> =
        store.data.map { it[ttsKey(provider, "manual_voice_id")] ?: "" }

    suspend fun setTtsManualVoiceId(provider: TtsProvider, id: String) {
        val key = ttsKey(provider, "manual_voice_id")
        store.edit { prefs ->
            val text = id.trim()
            if (text.isEmpty()) prefs.remove(key) else prefs[key] = text
        }
    }

    // ===== 自定义端点的手填音色库（v0.56.13）=====
    //
    // 自定义 OpenAI 兼容端点没有预置音色也没有克隆，手填 ID 是它唯一的音色来源；
    // 单值键时代只能存一条、再填就覆盖（用户点名不合理）。改成行式小表
    // （`id\t名字` 一行一条，与 [keyCloneVoiceNames] 同款）：可加多条、可起中文名、
    // 可试听、可长按删除。硅基 / MiMo 的手填 ID 仍是单值（它们的主力是预置 + 克隆）。
    // 旧的单值键在首次读取时自动迁移进来（名字先用 ID 顶着），搬完清旧键。
    private val keyCustomVoices = ttsKey(TtsProvider.CUSTOM, "voices")

    /** 自定义端点已录入的音色（id → 显示名，保持录入顺序） */
    suspend fun customVoices(): List<Pair<String, String>> {
        migrateLegacyCustomVoice()
        return store.data.first()[keyCustomVoices]?.lines()
            ?.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2 || parts[0].isBlank()) null else parts[0] to parts[1]
            }
            .orEmpty()
    }

    suspend fun addCustomVoice(id: String, name: String) {
        val vid = id.trim()
        if (vid.isBlank()) return
        val clean = name.trim().replace('\t', ' ').replace('\n', ' ')
        withContext(Dispatchers.IO) {
            store.edit { prefs ->
                val rows = (prefs[keyCustomVoices] ?: "").lines()
                    .filter { it.isNotBlank() && it.substringBefore('\t') != vid }
                    .toMutableList()
                rows += "$vid\t${clean.ifBlank { vid }}"
                prefs[keyCustomVoices] = rows.joinToString("\n")
            }
        }
    }

    suspend fun removeCustomVoice(id: String) {
        if (id.isBlank()) return
        withContext(Dispatchers.IO) {
            store.edit { prefs ->
                val rows = (prefs[keyCustomVoices] ?: "").lines()
                    .filter { it.isNotBlank() && it.substringBefore('\t') != id }
                if (rows.isEmpty()) prefs.remove(keyCustomVoices) else prefs[keyCustomVoices] = rows.joinToString("\n")
            }
        }
    }

    /** 旧单值手填 ID（v0.52.0~v0.56.12 只作用于自定义端点那一条）搬进多条表；幂等，搬完清旧键 */
    private suspend fun migrateLegacyCustomVoice() {
        val prefs = store.data.first()
        val legacy = prefs[ttsKey(TtsProvider.CUSTOM, "manual_voice_id")]?.trim().orEmpty()
        if (legacy.isEmpty()) return
        val rows = prefs[keyCustomVoices]?.lines()?.filter { it.isNotBlank() }.orEmpty()
        withContext(Dispatchers.IO) {
            store.edit {
                if (rows.none { it.substringBefore('\t') == legacy }) {
                    it[keyCustomVoices] = (rows + "$legacy\t$legacy").joinToString("\n")
                }
                it.remove(ttsKey(TtsProvider.CUSTOM, "manual_voice_id"))
            }
        }
    }

    /**
     * 克隆音色的**中文显示名**（v0.53.0），按服务端返回的 uri 索引。
     *
     * 为什么需要：硅基的 `customName` 只收字母/数字/`_`/`-`（真机直接 HTTP 400
     * "Invalid custom name. only letters and digits and _ and - are supported"），
     * 所以发上去的只能是 `voice_1690000000000` 这种 ASCII 名；用户起的「永雏塔菲」
     * 是本机显示名，存在这里。整张表塞一个键（一行一条 `uri\t名字`），
     * 克隆音色数量本来就少，不值得为它开一张 Room 表。
     */
    suspend fun cloneVoiceNames(): Map<String, String> =
        store.data.first()[keyCloneVoiceNames]?.lines()
            ?.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) null else parts[0] to parts[1]
            }?.toMap()
            .orEmpty()

    suspend fun setCloneVoiceName(uri: String, name: String) {
        val clean = name.trim().replace('\t', ' ').replace('\n', ' ')
        if (uri.isBlank() || clean.isBlank()) return
        store.edit { prefs ->
            val table = (prefs[keyCloneVoiceNames] ?: "").lines()
                .filter { it.isNotBlank() && it.substringBefore('\t') != uri }.toMutableList()
            table += "$uri\t$clean"
            prefs[keyCloneVoiceNames] = table.joinToString("\n")
        }
    }

    suspend fun removeCloneVoiceName(uri: String) {
        store.edit { prefs ->
            val table = (prefs[keyCloneVoiceNames] ?: "").lines()
                .filter { it.isNotBlank() && it.substringBefore('\t') != uri }
            if (table.isEmpty()) prefs.remove(keyCloneVoiceNames) else prefs[keyCloneVoiceNames] = table.joinToString("\n")
        }
    }

    // ===== 动态的位置候选（v0.76.0）=====
    //
    // 发动态带的位置是**纯文字**（不定位、不要权限，理由写在 MomentPlaces 的头注释里）。
    // 发过一次「西湖边的星巴克」的人在第二次发帖时不想再手打一遍，所以这里存最近用过
    // 的那几个——一行一条，与 cloneVoiceNames 同一张小表做法，不另外建 Room 表。

    /** 最近用过的动态位置（一行一条，越靠前越近） */
    private val keyMomentPlaces = stringPreferencesKey("moment_places")

    val momentPlaces: Flow<List<String>> = store.data.map { prefs ->
        MomentPlaces.candidates((prefs[keyMomentPlaces] ?: "").lines().map { it.trim() })
    }

    /** 发完动态记住这个地方（幂等：同一个地方来了多少次都只留一条，且提到最前） */
    suspend fun rememberMomentPlace(place: String) {
        val clean = place.trim()
        if (clean.isBlank() || clean.any { it == '\n' || it == '\t' }) return
        store.edit { prefs ->
            val current = (prefs[keyMomentPlaces] ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
            prefs[keyMomentPlaces] = MomentPlaces.remembered(current, clean).joinToString("\n")
        }
    }

    // ===== 克隆音色列表缓存（v0.56.5）=====
    //
    // 「音色」页的克隆那几条只有一个来源：服务商的 `/audio/voice/list`（一次网络往返）。
    // 于是进页面时预置音色已经在了、克隆要等一个来回才"啪"地补进来 —— 用户原话
    // "总是最后才加载出来，像卡了一样突然弹出来"。
    // 这里把**上次拉到的结果**存在本机，进页面先用它把整份名单一次性渲染齐，再后台刷一次覆盖。

    /** `<PROVIDER>\t<customName>\t<uri>\t<model>` 一行一条；切换服务商也各自保留自己那一份 */
    private val keyClonedVoiceCache = stringPreferencesKey("tts_cloned_cache")

    /** 该服务商上次拉到的克隆音色（本地读，毫秒级；失败/没缓存返回空表） */
    suspend fun cachedClonedVoices(provider: TtsProvider): List<ClonedVoice> =
        store.data.first()[keyClonedVoiceCache]?.lines()
            ?.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 4 || parts[0] != provider.name) return@mapNotNull null
                val uri = parts[2]
                if (uri.isBlank()) null else ClonedVoice(parts[1], uri, parts[3])
            }
            .orEmpty()

    // ===== 隐藏的克隆音色（v0.56.12：音色页长按删除）=====
    //
    // 硅基的克隆音色实体在**服务商账号**里，App 不调服务商的删除接口——账号资产删了就找不回来，
    // 而且「克隆音色」本来就不该被误触清掉。这里的"删除" = 加进这张隐藏表
    // （`PROVIDER\tvoiceId` 一行一条，与 [keyCloneVoiceNames] 同款行式小表）：
    // 名单里不再显示，缓存读取与网络刷新两条路径都按它过滤。手填 ID 与 MiMo 本机样本
    // 是实打实的本地数据，删除就是真删（清键 / 删文件），不走这张表。
    private val keyHiddenVoices = stringPreferencesKey("tts_hidden_voices")

    /** 该服务商被用户删掉（隐藏）的克隆音色 uri 集合 */
    suspend fun hiddenVoiceIds(provider: TtsProvider): Set<String> =
        store.data.first()[keyHiddenVoices]?.lines()
            ?.mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size == 2 && parts[0] == provider.name) parts[1].takeIf { it.isNotBlank() } else null
            }?.toSet()
            .orEmpty()

    suspend fun hideVoice(provider: TtsProvider, voiceId: String) {
        if (voiceId.isBlank()) return
        withContext(Dispatchers.IO) {
            store.edit { prefs ->
                val table = (prefs[keyHiddenVoices] ?: "").lines().filter { it.isNotBlank() }.toMutableList()
                val entry = "${provider.name}\t$voiceId"
                if (entry !in table) table += entry
                prefs[keyHiddenVoices] = table.joinToString("\n")
            }
        }
    }

    /**
     * 取消隐藏（v1.0.1）：隐藏表原来是**只进不出**的 —— 长按删一条克隆音色后，
     * 任何界面都没有把它放回来的入口（用户因此会觉得"克隆的音色莫名其妙少了一个"）。
     * 音色页底部据此提供「查看已隐藏」+ 恢复。
     */
    suspend fun unhideVoice(provider: TtsProvider, voiceId: String) {
        if (voiceId.isBlank()) return
        withContext(Dispatchers.IO) {
            store.edit { prefs ->
                val entry = "${provider.name}\t$voiceId"
                val table = (prefs[keyHiddenVoices] ?: "").lines()
                    .filter { it.isNotBlank() && it != entry }
                if (table.isEmpty()) prefs.remove(keyHiddenVoices) else prefs[keyHiddenVoices] = table.joinToString("\n")
            }
        }
    }

    /** 覆盖写该服务商那一份缓存（别的服务商的条目原样留着） */
    suspend fun setCachedClonedVoices(provider: TtsProvider, voices: List<ClonedVoice>) =
        withContext(Dispatchers.IO) {
            store.edit { prefs ->
                val others = (prefs[keyClonedVoiceCache] ?: "").lines()
                    .filter { it.isNotBlank() && it.substringBefore('\t') != provider.name }
                val mine = voices.map { v ->
                    listOf(
                        provider.name,
                        v.customName.replace('\t', ' ').replace('\n', ' '),
                        v.uri.replace('\t', ' ').replace('\n', ' '),
                        v.model.replace('\t', ' ').replace('\n', ' '),
                    ).joinToString("\t")
                }
                val all = others + mine
                if (all.isEmpty()) prefs.remove(keyClonedVoiceCache)
                else prefs[keyClonedVoiceCache] = all.joinToString("\n")
            }
        }

    // ===== 人设主动关心（v0.38.0；v0.54.4 起**按角色**，只在每个角色的整页里开关）=====

    /** 哪些角色开了「主动关心」（`id\t1`） */
    val personaProactive: Flow<Map<Long, Boolean>> = store.data.map { prefs ->
        readIdTable(prefs[keyPersonaProactive]).mapValues { it.value == "1" }
    }

    suspend fun setPersonaProactive(personaId: Long, enabled: Boolean) =
        writeIdTable(keyPersonaProactive, personaId, if (enabled) "1" else "0")

    /**
     * 该角色**今天**已经发了几条主动关心。
     *
     * v0.56.8：值从"上次发送日"扩成 `day:sent:planned`（见 [proactivePlannedToday]）；
     * 老数据只有 day（`"20612"`）→ 按"当天发过 1 条"读，不丢、不重复发。
     */
    suspend fun proactiveSentTodayOf(personaId: Long, today: Long): Int {
        val raw = readIdTable(store.data.first()[keyPersonaProactiveLastDay])[personaId] ?: return 0
        val parts = raw.split(':')
        if ((parts[0].toLongOrNull() ?: 0L) != today) return 0
        // 老格式只有 day：当天算发过 1 条
        return parts.getOrNull(1)?.toIntOrNull() ?: 1
    }

    /**
     * 该角色**今天打算发几条**：在 `1..上限` 里**随机摇一个**（上限见 [proactiveDailyLimit]）。
     *
     * - 摇出来就落盘，当天不再重摇（否则每响一次闹钟就重摇一次，条数没个准）；
     * - 上限后来改小了 → 按新上限截断；改大了 → 今天仍按已经摇好的走，明天才用新上限；
     * - 上限 ≤ 1 时恒为 1（"最多 1 条"就是每天 1 条）。
     */
    suspend fun proactivePlannedToday(personaId: Long, today: Long, limit: Int): Int {
        val raw = readIdTable(store.data.first()[keyPersonaProactiveLastDay])[personaId]
        val parts = raw?.split(':').orEmpty()
        val day = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val sent = if (day == today) (parts.getOrNull(1)?.toIntOrNull() ?: 1) else 0
        if (day == today) {
            val planned = parts.getOrNull(2)?.toIntOrNull() ?: 0
            if (planned > 0) return if (limit <= 0) planned else planned.coerceAtMost(limit)
        }
        val safeLimit = limit.coerceAtLeast(1)
        val planned = if (safeLimit <= 1) 1 else (1..safeLimit).random()
        writeIdTable(keyPersonaProactiveLastDay, personaId, "$today:$sent:$planned")
        return planned
    }

    /** 落盘"今天已发 [sent] 条 / 计划 [planned] 条"；[planned] 由 [proactivePlannedToday] 摇出后传回来 */
    suspend fun setProactiveToday(personaId: Long, day: Long, sent: Int, planned: Int) =
        writeIdTable(keyPersonaProactiveLastDay, personaId, "$day:$sent:${planned.coerceAtLeast(1)}")

    /** 该角色上次发送日（epochDay，0 = 没发过）；用于"这一轮挑谁"时按最久没发的优先 */
    suspend fun proactiveLastDayOf(personaId: Long): Long =
        readIdTable(store.data.first()[keyPersonaProactiveLastDay])[personaId]
            ?.substringBefore(':')?.toLongOrNull() ?: 0L

    /**
     * 该角色主动关心的**随机时间段**（v0.56.8 第二批：**每个角色一套**）：`起 HH:mm` to `止 HH:mm`。
     * 每天在这个区间里随机取时刻发；跨零点也支持（止 ≤ 起 时视为到第二天）。
     * 没单独设过 → 默认 20:00–23:00。
     */
    fun proactiveWindow(personaId: Long): Flow<Pair<String, String>> = store.data.map { prefs ->
        val raw = readIdTable(prefs[keyPersonaProactiveWindow])[personaId] ?: ""
        val start = raw.substringBefore('|', "").trim().ifEmpty { DEFAULT_PROACTIVE_WINDOW_START }
        val end = raw.substringAfter('|', "").trim().ifEmpty { DEFAULT_PROACTIVE_WINDOW_END }
        start to end
    }

    suspend fun setProactiveWindow(personaId: Long, start: String, end: String) =
        writeIdTable(keyPersonaProactiveWindow, personaId, "${start.trim()}|${end.trim()}")

    /**
     * 该角色主动关心**每天最多几条**（v0.56.8 第二批：**每个角色一套**）。
     *
     * 是**上限不是定额**：实际发几条在 `1..上限` 里随机（见 [proactivePlannedToday]），
     * 不超过这个数就行。什么数字都能填（小于 1 按 1 算，上限 999 —— 再大就是几分钟一条了）；
     * 没设过 = 1。
     */
    fun proactiveDailyLimit(personaId: Long): Flow<Int> = store.data.map { prefs ->
        val raw = readIdTable(prefs[keyPersonaProactiveLimit])[personaId]
        (raw?.trim()?.toIntOrNull() ?: 1).coerceIn(1, 999)
    }

    suspend fun setProactiveDailyLimit(personaId: Long, count: Int) =
        writeIdTable(keyPersonaProactiveLimit, personaId, count.coerceIn(1, 999).toString())

    /**
     * 该角色主动关心时**顺手发动态**（发新帖 / 回帖 / 点赞）的开关（v0.94）。
     *
     * 默认**开**：v0.79.0 起主动链本来就带动态动作，老用户的预期不变；
     * 关掉 = TA 主动找你时只发消息，不动你的动态。
     */
    fun proactiveMomentsEnabled(personaId: Long): Flow<Boolean> = store.data.map { prefs ->
        readIdTable(prefs[keyPersonaProactiveMoments])[personaId] != "0"
    }

    suspend fun setProactiveMomentsEnabled(personaId: Long, enabled: Boolean) =
        writeIdTable(keyPersonaProactiveMoments, personaId, if (enabled) "1" else "0")

    /**
     * 该角色**主动打电话**的开关（v0.94：电话并入主动关心的随机时刻，不再是「几天没聊」）。
     *
     * 老数据的迁移走**读取时推断**：v0.93 及更早的「主动电话」开关存在 `aiCallRules`
     * 的 `enabled` 里，这张新表没写过值时按老开关读（只读推断、不回写盘，
     * 与项目"存量字段靠 from* 推断、不做批量回填"的规矩一致）。
     */
    fun proactiveCallEnabled(personaId: Long): Flow<Boolean> = store.data.map { prefs ->
        readIdTable(prefs[keyPersonaProactiveCall])[personaId]?.let { return@map it == "1" }
        // 老装机：新键没值 → 看老的「主动电话」开关
        AiCallRuleCodec.decode(prefs[keyAiCallRules] ?: "")
            .firstOrNull { it.personaId == personaId }?.enabled ?: false
    }

    suspend fun setProactiveCallEnabled(personaId: Long, enabled: Boolean) =
        writeIdTable(keyPersonaProactiveCall, personaId, if (enabled) "1" else "0")

    /** 该角色**每天最多打几次**主动电话（v0.94，1–9，默认 1；取代老的「每人每天最多一次」） */
    fun proactiveCallLimit(personaId: Long): Flow<Int> = store.data.map { prefs ->
        val raw = readIdTable(prefs[keyPersonaProactiveCallLimit])[personaId]
        (raw?.trim()?.toIntOrNull() ?: 1).coerceIn(1, 9)
    }

    suspend fun setProactiveCallLimit(personaId: Long, count: Int) =
        writeIdTable(keyPersonaProactiveCallLimit, personaId, count.coerceIn(1, 9).toString())

    /** 该角色今天已经主动打过几次电话 */
    suspend fun proactiveCallsTodayOf(personaId: Long, today: Long): Int {
        val raw = readIdTable(store.data.first()[keyPersonaProactiveCallDay])[personaId] ?: return 0
        val parts = raw.split(':')
        if ((parts[0].toLongOrNull() ?: 0L) != today) return 0
        return parts.getOrNull(1)?.toIntOrNull() ?: 0
    }

    /** 记下"今天给这个角色主动打了一通电话" */
    suspend fun markProactiveCall(personaId: Long, today: Long) {
        val sent = proactiveCallsTodayOf(personaId, today)
        writeIdTable(keyPersonaProactiveCallDay, personaId, "$today:${sent + 1}")
    }

    /**
     * 排闹钟的门槛：三个开关（发信息 / 发帖 / 打电话）**有一个开着**这个角色就该有闹钟
     * （v0.94：闹钟不再只看「发信息」——关信息开动态的角色也该按时去看一眼动态）。
     *
     * 「发帖」默认值是开但**未写键不算开**：从没配过主动关心的角色，
     * 不能因为发帖的默认值就白给它排上闹钟（全关 = 不触发）。
     */
    suspend fun anyProactiveEnabled(personaId: Long): Boolean {
        val prefs = store.data.first()
        if (readIdTable(prefs[keyPersonaProactive])[personaId] == "1") return true
        if (readIdTable(prefs[keyPersonaProactiveMoments])[personaId] == "1") return true
        readIdTable(prefs[keyPersonaProactiveCall])[personaId]?.let { return it == "1" }
        // 老装机的「主动电话」开关（只读推断，见 proactiveCallEnabled）
        return AiCallRuleCodec.decode(prefs[keyAiCallRules] ?: "")
            .firstOrNull { it.personaId == personaId }?.enabled ?: false
    }

    /** 人设删除的级联清理：主动关心的三开关 / 次数 / 当日计数一并摘掉（不留孤儿键） */
    suspend fun removeProactiveTuning(personaId: Long) {
        setPersonaProactive(personaId, false)
        writeIdTable(keyPersonaProactiveMoments, personaId, "0")
        writeIdTable(keyPersonaProactiveCall, personaId, "0")
        writeIdTable(keyPersonaProactiveCallLimit, personaId, "1")
        writeIdTable(keyPersonaProactiveCallDay, personaId, "0:0")
    }


    // ===== AI 主动打电话（v0.53.6：**按人设独立配置**）=====
    //
    // v0.54.5：**原来的全局总开关（aiCallEnabled）已删掉** —— 设置里不再有
    // 「设置 → 通话行为 → AI 主动打电话」这一页，开关改在**每个角色会话的整页设置**里
    // （「允许主动打电话」卡片，连同时间与阈值一起按角色走）。
    // 老键 keyAiCallEnabled 只保留给下面 aiCallRules 的**存量推断**读（不再有写入方）。

    /**
     * 所有人设的规则（每人设一条，用户没配过的人设不在列表里 = 不允许）。
     *
     * 兼容 v0.53.6 之前的**全局配置**：那时只有"一个时间 + 一个阈值 + 一个选定人设"，
     * 这里在**读取时**把它推成那个人的一条规则（只读推断，不回写盘）——
     * 与项目"存量字段靠 from* 推断、不做批量回填"的规矩一致。
     */
    val aiCallRules: Flow<List<AiCallRule>> = store.data.map { prefs ->
        val stored = AiCallRuleCodec.decode(prefs[keyAiCallRules] ?: "")
        if (stored.isNotEmpty()) return@map stored
        val legacyPersona = prefs[keyAiCallPersonaId] ?: 0L
        val legacyEnabled = prefs[keyAiCallEnabled] ?: false
        if (legacyPersona <= 0L || !legacyEnabled) return@map emptyList()
        listOf(
            AiCallRule(
                personaId = legacyPersona,
                enabled = true,
                time = prefs[keyAiCallTime] ?: DEFAULT_AI_CALL_TIME,
                idleDays = prefs[keyAiCallIdleDays] ?: 3,
            ),
        )
    }

    suspend fun upsertAiCallRule(rule: AiCallRule) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            val list = AiCallRuleCodec.decode(prefs[keyAiCallRules] ?: "").toMutableList()
            val index = list.indexOfFirst { it.personaId == rule.personaId }
            if (index >= 0) list[index] = rule else list.add(rule)
            prefs[keyAiCallRules] = AiCallRuleCodec.encode(list)
        }
    }

    /** 人设被删时清掉它的规则（不留孤儿） */
    suspend fun removeAiCallRule(personaId: Long) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            val list = AiCallRuleCodec.decode(prefs[keyAiCallRules] ?: "")
                .filterNot { it.personaId == personaId }
            if (list.isEmpty()) prefs.remove(keyAiCallRules)
            else prefs[keyAiCallRules] = AiCallRuleCodec.encode(list)
        }
    }

    // v0.94：markAiCallDone 已随「几天没聊就主动打」整条删除（当日电话计数改走
    // keyPersonaProactiveCallDay，见 markProactiveCall）。

    // ⚠️ v0.94 又删掉三处：`setAiCallTime` / `setAiCallIdleDays` / `aiCallPersonaId`。
    // 它们是 v0.53.6 之前那套「全局配置」的**写入口与读出口**，被「每人设规则」取代后一直零调用 ——
    // `setAiCallTime` 只写 `keyAiCallTime`、`setAiCallIdleDays` 只写 `keyAiCallIdleDays`（两个键
    // 仍被上面 `aiCallRules` 的兼容推断读着，**键保留**），`aiCallPersonaId` 这个 Flow 连读方都没有。
    // 兼容推断所需的三个键（time / idleDays / personaId）保持只读，不受影响。

    // ===== 定时主动消息（v0.53.5，可配多条）+ 联网搜索服务 =====

    /** 全部「定时主动消息」。条目少、又总是一起读写，所以存 JSON 不建表（省一次 Room 迁移） */
    val scheduledMessages: Flow<List<ScheduledMessage>> = store.data.map { prefs ->
        ScheduledMessageCodec.decode(prefs[keyScheduledMessages] ?: "")
    }

    suspend fun upsertScheduledMessage(message: ScheduledMessage) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            val list = ScheduledMessageCodec.decode(prefs[keyScheduledMessages] ?: "").toMutableList()
            val index = list.indexOfFirst { it.id == message.id }
            if (index >= 0) list[index] = message else list.add(message)
            prefs[keyScheduledMessages] = ScheduledMessageCodec.encode(list)
        }
    }

    suspend fun deleteScheduledMessage(id: String) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            val list = ScheduledMessageCodec.decode(prefs[keyScheduledMessages] ?: "").filterNot { it.id == id }
            if (list.isEmpty()) prefs.remove(keyScheduledMessages)
            else prefs[keyScheduledMessages] = ScheduledMessageCodec.encode(list)
        }
    }

    /**
     * 删角色时的级联清理：把这个角色的**全部定时项**（定时消息 + 定时电话，两种 kind 同一条存储）摘掉。
     *
     * ⚠️ v0.89.0：以前漏了这一步 —— 人设删了，定时项还在 DataStore 里、闹钟还在每天响，
     * 报时被 [ScheduledMessageSender] 的兜底"改嫁"给了应用助手（用户看到的现像：
     * 「我删了人设，它还是每天从应用助手发过来」）。删角色就把它写给那个角色的话一起带走。
     *
     * @return 被删掉的定时项 id（调用方拿它取消对应闹钟；闹钟 key 是 id 的 hashCode）
     */
    suspend fun removeScheduledMessagesForPersona(personaId: Long): List<String> =
        withContext(Dispatchers.IO) {
            var removed = emptyList<String>()
            store.edit { prefs ->
                val all = ScheduledMessageCodec.decode(prefs[keyScheduledMessages] ?: "")
                val keep = all.filterNot { it.personaId == personaId }
                removed = all.filter { it.personaId == personaId }.map { it.id }
                if (keep.isEmpty()) prefs.remove(keyScheduledMessages)
                else prefs[keyScheduledMessages] = ScheduledMessageCodec.encode(keep)
            }
            removed
        }

    /** 标记某条今天已发（epochDay 去重，保证当天只发一次） */
    suspend fun markScheduledMessageSent(id: String, day: Long) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            val list = ScheduledMessageCodec.decode(prefs[keyScheduledMessages] ?: "")
                .map { if (it.id == id) it.copy(lastSentDay = day) else it }
            prefs[keyScheduledMessages] = ScheduledMessageCodec.encode(list)
        }
    }

    /** 联网搜索服务商：见 [SEARCH_BOCHA] / [SEARCH_TAVILY] / [SEARCH_CUSTOM] */
    val searchProvider: Flow<String> = store.data.map { it[keySearchProvider] ?: SEARCH_BOCHA }

    /**
     * 每一家自己的键：`search_bocha_key_enc` / `search_tavily_key_plain` / `search_custom_url`……
     *
     * v0.66.0（用户点名"切换国内国外 key 都不变"）：博查与 Tavily 过去**共用一把** `search_key_*`，
     * 切服务商时看到和写进去的都是同一串，拿博查的 Key 去打 Tavily 就是"搜不到、切换像没生效"。
     * 这与 v0.52.0 语音合成踩过的是同一个坑，修法也照搬那一版（每家一桶）。
     * 自定义那一家的键名本来就长这样（`search_custom_key_*`），所以三家统一到同一个拼法。
     */
    private fun searchKeyOf(provider: String, field: String) =
        stringPreferencesKey("search_${provider}_$field")

    /**
     * 读某一家的 Key。
     *
     * 老单桶只属于**当时生效的那一家**：这家从没写过自己的桶、且它就是老数据的主人 →
     * 把老键**虚拟**当成它的内容读出来，升级后零操作。
     * ⚠️ 只在"整桶没动过"时回退，不能逐字段混读——混读会让用户看到半新半旧的一屏；
     * 用 `contains` 而不是判空：用户点「把 Key 清空」写的是空串，键在=写过，不能被老键复活。
     */
    private fun readSearchKey(prefs: Preferences, provider: String): String {
        val untouched = !prefs.contains(searchKeyOf(provider, "key_enc")) &&
            !prefs.contains(searchKeyOf(provider, "key_plain"))
        val fromLegacy = untouched && provider != SEARCH_CUSTOM &&
            provider == (prefs[keySearchProvider] ?: SEARCH_BOCHA)
        val sealed = if (fromLegacy) prefs[keySearchKeyEnc] else prefs[searchKeyOf(provider, "key_enc")]
        val plain = if (fromLegacy) prefs[keySearchKeyPlain] else prefs[searchKeyOf(provider, "key_plain")]
        return when {
            // Key 走 Keystore；不可用时回退明文（AGENTS §五.4，与对话/生图/语音接口同一套规矩）
            !sealed.isNullOrBlank() -> resolveSealed(sealed) ?: plain.orEmpty()
            else -> plain.orEmpty()
        }
    }

    /** 指定那一家的 Key（设置页切服务商时，每家看到的都是自己那份） */
    fun searchApiKeyOf(provider: String): Flow<String> =
        store.data.map { readSearchKey(it, provider) }.distinctUntilChanged()

    /** 自定义那一家的接口地址（内置两家不用它，端点写死在 [WebSearchClient.endpointOf]） */
    val searchCustomUrl: Flow<String> =
        store.data.map { it[searchKeyOf(SEARCH_CUSTOM, "url")] ?: "" }

    /**
     * 保存搜索配置：**一家一次写全**（当前生效服务商 + 这一家的 Key，自定义还带上地址）。
     *
     * ⚠️ 三件事必须在同一个 `edit` 块里，顺序也不许换：
     * ① 先把老单桶**落到它那一家的桶**里——先清老键会把用户填过的 Key 凭空抹掉
     *   （v0.52.0 语音合成保存别家时清老键，硅基那份 Key 就是这么丢的，测试当场抓到）；
     * ② 只写**当前这一家**的桶，绝不碰对面那家；
     * ③ 最后才把 `search_provider` 切过去——先切再搬，老数据就会记到新选那一家名下。
     */
    suspend fun saveSearchConfig(provider: String, key: String, customUrl: String = "") =
        withContext(Dispatchers.IO) {
            val sealed = if (key.isEmpty()) null else ApiKeyCipher.encrypt(key)
            store.edit { prefs ->
                val legacyOwner = prefs[keySearchProvider] ?: SEARCH_BOCHA
                val ownerUntouched = !prefs.contains(searchKeyOf(legacyOwner, "key_enc")) &&
                    !prefs.contains(searchKeyOf(legacyOwner, "key_plain"))
                if (ownerUntouched && legacyOwner != SEARCH_CUSTOM) {
                    prefs[keySearchKeyEnc]?.let { prefs[searchKeyOf(legacyOwner, "key_enc")] = it }
                    prefs[keySearchKeyPlain]?.let { prefs[searchKeyOf(legacyOwner, "key_plain")] = it }
                }
                when {
                    sealed != null -> {
                        prefs[searchKeyOf(provider, "key_enc")] = sealed
                        prefs.remove(searchKeyOf(provider, "key_plain"))
                    }

                    key.isNotEmpty() -> {
                        prefs[searchKeyOf(provider, "key_plain")] = key
                        prefs.remove(searchKeyOf(provider, "key_enc"))
                    }

                    else -> {
                        prefs.remove(searchKeyOf(provider, "key_enc"))
                        prefs.remove(searchKeyOf(provider, "key_plain"))
                    }
                }
                if (provider == SEARCH_CUSTOM) {
                    if (customUrl.isBlank()) prefs.remove(searchKeyOf(SEARCH_CUSTOM, "url"))
                    else prefs[searchKeyOf(SEARCH_CUSTOM, "url")] = customUrl.trim()
                }
                prefs.remove(keySearchKeyEnc)
                prefs.remove(keySearchKeyPlain)
                prefs[keySearchProvider] = provider
            }
        }

    /** 某一家该用哪份 Key（通话/定时消息/聊天里真正发搜索请求前取它） */
    suspend fun searchKeyFor(provider: String): String = searchApiKeyOf(provider).first()

    /** 自定义才返回用户填的地址；内置两家仍用写死的官方端点（返回空串表示"用内置的"） */
    suspend fun searchEndpointFor(provider: String): String =
        if (provider == SEARCH_CUSTOM) searchCustomUrl.first() else ""

    // ===== v0.59.0：在线语音识别 =====
    //
    // 与离线 SenseVoice 是**并列的两条路**，靠 [asrEngine] 手动切；
    // 在线这一路的地址/Key/模型都由用户自己填（不猜服务商，AGENTS §零.6）。

    /** 当前识别引擎：[ASR_OFFLINE]（默认，端侧 SenseVoice）/ [ASR_ONLINE] */
    val asrEngine: Flow<String> = store.data.map { it[keyAsrEngine] ?: ASR_OFFLINE }

    suspend fun setAsrEngine(engine: String) = withContext(Dispatchers.IO) {
        store.edit { it[keyAsrEngine] = if (engine == ASR_ONLINE) ASR_ONLINE else ASR_OFFLINE }
    }

    val asrOnlineUrl: Flow<String> = store.data.map { it[keyAsrOnlineUrl] ?: "" }

    suspend fun setAsrOnlineUrl(url: String) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            if (url.isBlank()) prefs.remove(keyAsrOnlineUrl) else prefs[keyAsrOnlineUrl] = url.trim()
        }
    }

    val asrOnlineModel: Flow<String> = store.data.map { it[keyAsrOnlineModel] ?: "" }

    suspend fun setAsrOnlineModel(model: String) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            if (model.isBlank()) prefs.remove(keyAsrOnlineModel) else prefs[keyAsrOnlineModel] = model.trim()
        }
    }

    /** 在线识别的 Key（密文优先，规矩与其余 Key 一致） */
    val asrOnlineApiKey: Flow<String> = store.data.map { prefs ->
        val sealed = prefs[keyAsrOnlineKeyEnc]
        if (sealed.isNullOrBlank()) {
            prefs[keyAsrOnlineKeyPlain] ?: ""
        } else {
            resolveSealed(sealed) ?: (prefs[keyAsrOnlineKeyPlain] ?: "")
        }
    }

    suspend fun setAsrOnlineApiKey(key: String) = withContext(Dispatchers.IO) {
        val sealed = if (key.isEmpty()) null else ApiKeyCipher.encrypt(key)
        store.edit { prefs ->
            when {
                sealed != null -> {
                    prefs[keyAsrOnlineKeyEnc] = sealed; prefs.remove(keyAsrOnlineKeyPlain)
                }
                key.isNotEmpty() -> {
                    prefs[keyAsrOnlineKeyPlain] = key; prefs.remove(keyAsrOnlineKeyEnc)
                }
                else -> {
                    prefs.remove(keyAsrOnlineKeyEnc); prefs.remove(keyAsrOnlineKeyPlain)
                }
            }
        }
    }

    /**
     * 自动朗读 AI 的回复（v0.59.0，**按角色各开各的**，默认全关）。
     *
     * 为什么按角色而不是全局一条：每个角色的音色、说话长短都不一样，
     * 陪伴型角色想听着念，工具型角色只想看字；开着它每条回复都会发一次合成请求，
     * 那是真花额度的，所以默认关、且只影响你打开的那一个角色。
     *
     * 存法与「会话联网搜索」同一套（`id\t1|0` 行式小表）。
     * ⚠️ 这与 v0.50.0 删掉的"右上角喇叭自动朗读"不是一回事，**不要把那颗喇叭复活**；
     * 长按菜单里的「朗读」是另一条入口，随时可点、不占这个开关。
     */
    val chatReadAloud: Flow<Map<Long, Boolean>> = store.data.map { prefs ->
        readIdTable(prefs[keyChatReadAloudPersona]).mapValues { it.value == "1" }
    }

    suspend fun setChatReadAloudEnabled(personaId: Long, enabled: Boolean) =
        writeIdTable(keyChatReadAloudPersona, personaId, if (enabled) "1" else "0")


    // ===== 会话级资产（聊天背景 / 会话联网 …）=====
    //
    // 都按 personaId 存成 `id\t值` 的行式小表（与 cloneVoiceNames 同一套做法，条目少、不建表）。
    //
    // v0.58.0：**「角色立绘」和「Live 2D」这两个键连同读取口一起删掉。**
    // 它们以前的形态是"把一个文件复制到 filesDir/persona_art|persona_live2d/，只存路径不显示"，
    // 而视频通话要的是**完整资源包**（Live2D 要有 model3.json+moc3+贴图，伪视频要按状态分素材），
    // 单个文件既渲染不了也没法指认。新实现在 CallVisualStore（filesDir/call_visuals/<id>/），
    // 所以下面这两个键不再有任何读取方，只在启动时清一次老键，避免留下没人读的数据。

    /** 启动时清掉已废弃的两个老键（幂等；文件目录由 MenghuanApp 一起清） */
    suspend fun clearDeprecatedPersonaAssetKeys() {
        store.edit { prefs ->
            if (prefs.contains(keyPersonaArt) || prefs.contains(keyPersonaLive2d)) {
                prefs.remove(keyPersonaArt)
                prefs.remove(keyPersonaLive2d)
            }
        }
    }

    /** 该会话的聊天背景图路径 */
    val chatBackground: Flow<Map<Long, String>> = store.data.map { prefs ->
        readIdTable(prefs[keyChatBackground])
    }

    suspend fun setChatBackground(personaId: Long, path: String) = writeIdTable(keyChatBackground, personaId, path)

    /**
     * 该角色的**视频通话背景档位**（v0.66.0，用户点名"视频通话加个通话背景选择"）。
     *
     * 存的是档位串而不是路径（`preset` / `file:<文件名>`），与 [Live2dSpaceStore.SpaceConfig] 同形状：
     * 图片一律落在 `filesDir/call_background/<personaId>/background.jpg` 这个**固定名**上，
     * 换一张就是覆盖——省下"每个用户图存一个时间戳名、删人设时留下一堆孤儿"那套账
     *（口径同 AGENTS §三：库里只留必要的那一点状态，文件以目录为准）。
     * 读的人拿档位判断要不要读文件、文件在不在，两边不一致时**以文件为准**（同通话形象）。
     */
    val callBackground: Flow<Map<Long, String>> = store.data.map { prefs ->
        readIdTable(prefs[keyCallBackground])
    }

    suspend fun setCallBackground(personaId: Long, value: String) =
        writeIdTable(keyCallBackground, personaId, value)

    /**
     * 这一角色通话时的**动作幅度档位**（v0.67.0，`id\t档位键` 行式表）。
     *
     * 存的是 [com.menghuan.app.live2d.ActionScale] 的键串而不是浮点：
     * 档位是要显示回给用户看的（"大"），存 1.4 就得再猜一次它当初点的是哪一档。
     * 没设过 = 标准档（倍率 1.0，等于什么都不乘）。
     */
    val callActionScale: Flow<Map<Long, String>> = store.data.map { prefs ->
        readIdTable(prefs[keyCallActionScale])
    }

    suspend fun setCallActionScale(personaId: Long, key: String) =
        writeIdTable(keyCallActionScale, personaId, key)

    /** 该角色当前的幅度倍率（供通话链路乘上去，不做流式订阅） */
    suspend fun callActionScaleFor(personaId: Long): Float {
        val table = runCatching { callActionScale.first() }.getOrDefault(emptyMap())
        return ActionScale.fromKey(table[personaId]).multiplier
    }

    /** 该会话是否允许 AI 联网查资料（配合「联网搜索」里配好的 Key） */
    val chatWebSearch: Flow<Map<Long, Boolean>> = store.data.map { prefs ->
        readIdTable(prefs[keyChatWebSearch]).mapValues { it.value == "1" }
    }

    suspend fun setChatWebSearchEnabled(personaId: Long, enabled: Boolean) =
        writeIdTable(keyChatWebSearch, personaId, if (enabled) "1" else "0")

    // ===== 聊天外观（v0.80.0，明暗各一套 + 字体三件套）=====

    /** 气泡配色 / 边框 / 圆角 + 字体族字号字重 + 是否显示头像（见 [ChatAppearance]） */
    val chatAppearance: Flow<ChatAppearance> = store.data.map { prefs ->
        ChatAppearance(
            light = ChatAppearance.BubbleTheme(
                mine = ChatAppearance.BubbleStyle.decode(prefs[keyChatBubbleLightMine]),
                other = ChatAppearance.BubbleStyle.decode(prefs[keyChatBubbleLightOther]),
            ),
            dark = ChatAppearance.BubbleTheme(
                mine = ChatAppearance.BubbleStyle.decode(prefs[keyChatBubbleDarkMine]),
                other = ChatAppearance.BubbleStyle.decode(prefs[keyChatBubbleDarkOther]),
            ),
            fontFamily = ChatAppearance.FontFamilyKey.fromKey(prefs[keyChatFontFamily]),
            fontSizeSp = (prefs[keyChatFontSizeSp] ?: ChatAppearance.DEFAULT_FONT_SP)
                .coerceIn(ChatAppearance.MIN_FONT_SP, ChatAppearance.MAX_FONT_SP),
            fontWeightKey = ChatAppearance.FontWeightKey.fromKey(prefs[keyChatFontWeight]),
            // 默认**显示**头像：以前没有这个开关，聊天页本来就不显示，但既然做开关，
            // 打开是更"有人味"的默认（用户能立刻看到效果、也立刻知道能关）
            showAvatar = prefs[keyChatShowAvatar] ?: true,
        )
    }

    suspend fun setChatAppearance(next: ChatAppearance) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            prefs[keyChatBubbleLightMine] = next.light.mine.encode()
            prefs[keyChatBubbleLightOther] = next.light.other.encode()
            prefs[keyChatBubbleDarkMine] = next.dark.mine.encode()
            prefs[keyChatBubbleDarkOther] = next.dark.other.encode()
            prefs[keyChatFontFamily] = next.fontFamily.key
            prefs[keyChatFontSizeSp] = next.fontSizeSp
            prefs[keyChatFontWeight] = next.fontWeightKey.key
            prefs[keyChatShowAvatar] = next.showAvatar
        }
    }

    /**
     * 我的档案（v0.80.0）：昵称 + 一句话简介。
     *
     * 与基础档案（ProfileFact 那张 key/value 表）是**两回事**：那张是"AI 读我的画像"，
     * 这是"我在这个 App 里叫什么、写句什么话"（微信「我的」页顶部那张卡的同款东西）。
     */
    val myProfile: Flow<com.menghuan.app.data.MyProfile> = store.data.map { prefs ->
        com.menghuan.app.data.MyProfile(
            nickname = prefs[keyProfileNickname] ?: "",
            bio = prefs[keyProfileBio] ?: "",
        )
    }

    suspend fun setMyProfile(next: com.menghuan.app.data.MyProfile) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            if (next.nickname.isBlank()) prefs.remove(keyProfileNickname)
            else prefs[keyProfileNickname] = next.nickname.trim()
            if (next.bio.isBlank()) prefs.remove(keyProfileBio)
            else prefs[keyProfileBio] = next.bio.trim()
        }
    }

    /** 用户自己的头像（emoji + 图片路径，二选一，图片优先） */
    val userAvatar: Flow<UserAvatar> = store.data.map { prefs ->
        UserAvatar(
            emoji = prefs[keyUserAvatarEmoji] ?: "",
            imagePath = prefs[keyUserAvatarImage] ?: "",
        )
    }

    suspend fun setUserAvatar(next: UserAvatar) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            if (next.emoji.isBlank()) prefs.remove(keyUserAvatarEmoji) else prefs[keyUserAvatarEmoji] = next.emoji
            if (next.imagePath.isBlank()) prefs.remove(keyUserAvatarImage)
            else prefs[keyUserAvatarImage] = next.imagePath
        }
    }

    /** `id\t值` 行式表的读 */
    private fun readIdTable(raw: String?): Map<Long, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return buildMap {
            raw.lines().forEach { line ->
                if (line.isBlank()) return@forEach
                val id = line.substringBefore('\t').trim().toLongOrNull() ?: return@forEach
                val value = line.substringAfter('\t', "")
                if (value.isNotEmpty()) put(id, value)
            }
        }
    }

    /** `id\t值` 行式表的写（空值 = 删掉这一行） */
    private suspend fun writeIdTable(
        key: Preferences.Key<String>,
        id: Long,
        value: String,
    ) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            val rows = (prefs[key] ?: "").lines()
                .filter { it.isNotBlank() && it.substringBefore('\t').trim() != id.toString() }
                .toMutableList()
            if (value.isNotEmpty()) rows.add("$id\t$value")
            if (rows.isEmpty()) prefs.remove(key) else prefs[key] = rows.joinToString("\n")
        }
    }

    // ===== 语音通话铃声（v0.53.7；v0.54.4 起支持多个 + 角色专属）=====

    /** 默认铃声文件路径（空 = 用系统默认铃声）。没给角色设专属铃声时用它。 */
    val callRingtonePath: Flow<String> = store.data.map { it[keyCallRingtonePath] ?: "" }

    suspend fun setCallRingtonePath(path: String) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            if (path.isEmpty()) prefs.remove(keyCallRingtonePath) else prefs[keyCallRingtonePath] = path
        }
    }

    /** 本机已添加的铃声库（文件路径，每行一个）——设置里那张铃声列表的数据源 */
    val callRingtoneLibrary: Flow<List<String>> = store.data.map { prefs ->
        (prefs[keyCallRingtoneLibrary] ?: "").lines().filter { it.isNotBlank() }
    }

    suspend fun addCallRingtone(path: String) = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext
        store.edit { prefs ->
            val rows = (prefs[keyCallRingtoneLibrary] ?: "").lines()
                .filter { it.isNotBlank() }.toMutableList()
            if (path !in rows) rows.add(path)
            prefs[keyCallRingtoneLibrary] = rows.joinToString("\n")
        }
    }

    /** 移出铃声库：同时把「默认铃声」与各角色的引用清掉，不留指向已删文件的死链 */
    suspend fun removeCallRingtone(path: String) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            val rows = (prefs[keyCallRingtoneLibrary] ?: "").lines()
                .filter { it.isNotBlank() && it != path }
            if (rows.isEmpty()) prefs.remove(keyCallRingtoneLibrary)
            else prefs[keyCallRingtoneLibrary] = rows.joinToString("\n")
            if ((prefs[keyCallRingtonePath] ?: "") == path) prefs.remove(keyCallRingtonePath)
            val personaRows = (prefs[keyPersonaRingtone] ?: "").lines()
                .filter { it.isNotBlank() && it.substringAfter('\t', "") != path }
            if (personaRows.isEmpty()) prefs.remove(keyPersonaRingtone)
            else prefs[keyPersonaRingtone] = personaRows.joinToString("\n")
        }
    }

    /** 各角色的专属铃声（`id\t路径`） */
    val personaRingtone: Flow<Map<Long, String>> =
        store.data.map { readIdTable(it[keyPersonaRingtone]) }

    /** 传空字符串 = 该角色恢复用默认铃声 */
    suspend fun setPersonaRingtone(personaId: Long, path: String) =
        writeIdTable(keyPersonaRingtone, personaId, path)

    /** 这个角色打电话时放哪个铃声：角色专属 → 设置里的默认铃声 → 空（= 系统默认铃声） */
    suspend fun ringtoneForPersona(personaId: Long): String {
        val own = personaRingtone.first()[personaId]
        if (!own.isNullOrBlank()) return own
        return callRingtonePath.first()
    }

    /**
     * 我打给 AI 时，TA 隔几秒才接听（默认 3 秒；0 = 立刻接）。
     * 纯粹为了像真电话 —— 秒接反而显得假。**只影响去电**，AI 打来的来电不受影响。
     * v0.56.6：秒数改成**用户自定义输入**（原来是几个固定档位），上限放到 600 秒（10 分钟）。
     */
    val aiAnswerDelaySeconds: Flow<Int> = store.data.map { it[keyAiAnswerDelay] ?: 3 }

    suspend fun setAiAnswerDelaySeconds(seconds: Int) {
        store.edit { it[keyAiAnswerDelay] = seconds.coerceIn(0, 600) }
    }

    /** 本月 API 用量（total_tokens 累计；跨月由 UsageTracker.add 重置）。返回 月键 to tokens */
    val monthlyUsage: Flow<Pair<String, Int>> = store.data.map { prefs ->
        (prefs[keyUsageMonth] ?: "") to (prefs[keyUsageTokens] ?: 0)
    }

    /**
     * 本月**输入 token 累计** 与其中**缓存命中累计**（v0.69.0，月键与 [monthlyUsage] 同一份）。
     *
     * ⚠️ 口径：**两个数都是 0 不等于"一次都没命中"，而是"服务商压根没回 usage"**。
     * 界面必须按这个区别显示（`UsageTracker.cacheHitRate` 对 0 输入返回 null，不编百分比）。
     */
    val monthlyCacheUsage: Flow<Triple<String, Int, Int>> = store.data.map { prefs ->
        Triple(
            prefs[keyUsageMonth] ?: "",
            prefs[keyUsagePromptTokens] ?: 0,
            prefs[keyUsageCachedTokens] ?: 0,
        )
    }

    /**
     * 本月用量**按模型分开看**（v0.56.7；v0.82.0 行格式扩到 5 列带 prompt/cached 读数）：
     * `月键 to 用量行`，按 tokens 降序。只有服务商回了 usage 才记（拿不到的不猜），与总数同一口径。
     * ⚠️ 兼容：v0.82.0 之前落盘的老行只有 3 列（无 prompt/cached），解析按缺省 0 处理，不丢行。
     */
    val monthlyUsageByModel: Flow<Pair<String, List<ModelUsageRow>>> = store.data.map { prefs ->
        val month = prefs[keyUsageMonth] ?: ""
        val rows = (prefs[keyUsageByModel] ?: "").lines()
            .mapNotNull { line -> ModelUsageRow.parse(line, month) }
            .sortedByDescending { it.tokens }
        month to rows
    }

    suspend fun addUsageTokens(month: String, tokens: Int, promptTokens: Int = 0, cachedTokens: Int = 0) {
        store.edit { prefs ->
            val storedMonth = prefs[keyUsageMonth]
            val sameMonth = storedMonth == month
            // 三个数**在同一次 edit 里一起判跨月**：拆成两个函数就会互相抢 keyUsageMonth，
            // 谁先写谁把月键翻过去、另一个读到"同月"于是上月累计不清零（v0.69.0 想过省事分开写）。
            prefs[keyUsageMonth] = month
            prefs[keyUsageTokens] = (if (sameMonth) prefs[keyUsageTokens] ?: 0 else 0) + tokens
            prefs[keyUsagePromptTokens] =
                (if (sameMonth) prefs[keyUsagePromptTokens] ?: 0 else 0) + promptTokens.coerceAtLeast(0)
            prefs[keyUsageCachedTokens] =
                (if (sameMonth) prefs[keyUsageCachedTokens] ?: 0 else 0) + cachedTokens.coerceAtLeast(0)
        }
    }

    /**
     * 记一笔**某个模型**的用量（v0.56.7；v0.82.0 起行格式 `月\t模型\ttokens\tprompt\tcached`）。
     * 跨月整体重置（与总数一致）；模型名里的制表符/换行先洗掉，免得把行拆坏。
     * tokens/prompt/cached 三者全 0 不记（调用点兜底）。老 3 列行读出来当 prompt/cached=0 再写回 5 列。
     */
    suspend fun addUsageTokensForModel(
        month: String,
        model: String,
        tokens: Int,
        promptTokens: Int = 0,
        cachedTokens: Int = 0,
    ) {
        if (tokens <= 0 && promptTokens <= 0 && cachedTokens <= 0) return
        val label = model.trim().replace('\t', ' ').replace('\n', ' ').ifBlank { "（未填模型）" }
        store.edit { prefs ->
            val rows = (prefs[keyUsageByModel] ?: "").lines()
                .filter { it.isNotBlank() }
                // 跨月：旧月的行整批丢掉（总数也是这个口径）
                .filter { it.substringBefore('\t') == month }
                .map { line ->
                    val parts = line.split('\t')
                    ModelUsageRow(
                        model = parts.getOrNull(1) ?: "",
                        tokens = parts.getOrNull(2)?.toIntOrNull() ?: 0,
                        promptTokens = parts.getOrNull(3)?.toIntOrNull() ?: 0,
                        cachedTokens = parts.getOrNull(4)?.toIntOrNull() ?: 0,
                    )
                }
                .toMutableList()
            val index = rows.indexOfFirst { it.model == label }
            if (index >= 0) {
                val old = rows[index]
                rows[index] = old.copy(
                    tokens = old.tokens + tokens,
                    promptTokens = old.promptTokens + promptTokens.coerceAtLeast(0),
                    cachedTokens = old.cachedTokens + cachedTokens.coerceAtLeast(0),
                )
            } else {
                rows += ModelUsageRow(
                    model = label,
                    tokens = tokens,
                    promptTokens = promptTokens.coerceAtLeast(0),
                    cachedTokens = cachedTokens.coerceAtLeast(0),
                )
            }
            prefs[keyUsageByModel] = rows.joinToString("\n") {
                "${month}\t${it.model}\t${it.tokens}\t${it.promptTokens}\t${it.cachedTokens}"
            }
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

    /** Agent 保活前台服务开关（默认开）：用过 Agent 才启动，「自动执行」弹层里可关 */
    val agentKeepAlive: Flow<Boolean> = store.data.map { it[keyAgentKeepAlive] ?: true }

    suspend fun setAgentKeepAlive(enabled: Boolean) {
        store.edit { it[keyAgentKeepAlive] = enabled }
    }

    /** Agent 执行模型：空串 = 跟随接口自带模型（默认），非空 = 只给 Agent 决策用这个模型名 */
    val agentModel: Flow<String> = store.data.map { it[keyAgentModel].orEmpty() }

    suspend fun setAgentModel(model: String) {
        store.edit { prefs ->
            val v = model.trim()
            if (v.isEmpty()) prefs.remove(keyAgentModel) else prefs[keyAgentModel] = v
        }
    }

    /** Agent 是否随每步请求附屏幕截图（默认开；API 30 以下自动不生效） */
    val agentVision: Flow<Boolean> = store.data.map { it[keyAgentVision] ?: true }

    suspend fun setAgentVision(enabled: Boolean) {
        store.edit { it[keyAgentVision] = enabled }
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

    // ===== v0.86.0：按会话（角色）配置的「上下文窗口」与「提炼间隔」=====
    //
    // 为什么按角色而不是全局：每个角色可以配不同的 API/模型，模型不同则能装多少、
    // 值不值得堆，都是这个角色自己的事。写成全局常量（原 HISTORY_CHAR_BUDGET = 20 万）
    // 等于假设所有角色共用一个模型的价目表 —— 换角色即失效。
    // 两条都**只在用户手动改过时**才落盘（见 setter 的 remove 分支），
    // 没设过的角色走默认值，所以以后调默认值一处改、不必迁移数据。

    /**
     * 上下文窗口默认值：记住最近多少字（v0.86.0）。
     * 唯一来源是 [com.menghuan.app.ui.chat.HISTORY_CHAR_BUDGET] —— 写成两个数，
     * 将来调默认值时会漏改一处，历史与摘要的分界就会错位。
     */
    val DEFAULT_HISTORY_CHAR_BUDGET = com.menghuan.app.ui.chat.HISTORY_CHAR_BUDGET

    /** 提炼间隔默认值：攒够几轮对话提炼一次（v0.86.0） */
    val DEFAULT_EXTRACT_EVERY_TURNS = 6

    /** 上下文窗口允许范围：太小学不到上下文，太大既贵又超出有效记忆（衰减区） */
    val HISTORY_CHAR_BUDGET_RANGE = 2_000..200_000

    /** 提炼间隔允许范围：1 = 每轮都提炼（最及时最贵）；上限防手滑填出天文数字 */
    val EXTRACT_EVERY_TURNS_RANGE = 1..50

    /**
     * 该会话的上下文窗口（字数）。没设过 = [DEFAULT_HISTORY_CHAR_BUDGET]。
     * 记得最近多少字，直接决定每一轮重发的历史量 → 就是成本旋钮。
     */
    fun historyCharBudget(personaId: Long): Flow<Int> =
        store.data.map { it[intPreferencesKey(historyBudgetPrefix + personaId)] ?: DEFAULT_HISTORY_CHAR_BUDGET }

    suspend fun setHistoryCharBudget(personaId: Long, chars: Int) {
        val v = chars.coerceIn(HISTORY_CHAR_BUDGET_RANGE)
        store.edit { prefs ->
            if (v == DEFAULT_HISTORY_CHAR_BUDGET) prefs.remove(intPreferencesKey(historyBudgetPrefix + personaId))
            else prefs[intPreferencesKey(historyBudgetPrefix + personaId)] = v
        }
    }

    /**
     * 该会话的提炼间隔（轮）。没设过 = [DEFAULT_EXTRACT_EVERY_TURNS]。
     * 「轮」= 一次用户发言 + 一次 AI 回复；按轮不按条，免得系统气泡/动态等额外行把节奏打乱。
     */
    fun extractEveryTurns(personaId: Long): Flow<Int> =
        store.data.map { it[intPreferencesKey(extractEveryPrefix + personaId)] ?: DEFAULT_EXTRACT_EVERY_TURNS }

    suspend fun setExtractEveryTurns(personaId: Long, turns: Int) {
        val v = turns.coerceIn(EXTRACT_EVERY_TURNS_RANGE)
        store.edit { prefs ->
            if (v == DEFAULT_EXTRACT_EVERY_TURNS) prefs.remove(intPreferencesKey(extractEveryPrefix + personaId))
            else prefs[intPreferencesKey(extractEveryPrefix + personaId)] = v
        }
    }

    /** 人设删除的级联清理：移除该角色的上下文窗口 / 提炼间隔配置 */
    suspend fun removeSessionTuning(personaId: Long) {
        store.edit {
            it.remove(intPreferencesKey(historyBudgetPrefix + personaId))
            it.remove(intPreferencesKey(extractEveryPrefix + personaId))
        }
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

    // ===== 生图模型（独立于对话模型；AI 回复里出现 [生图:…] 标记时调用） =====
    // v0.53.4：生图与对话模型行为对齐——可配多条、可设默认、默认调不通时自动换别的。

    /**
     * 全部生图模型（密钥已解密）。与对话模型同构、共用 [ApiProfileCodec]。
     * 老版本只有一条单配置（img_base_url 四键），没有 img_profiles 时把它当作
     * 「默认生图模型」虚拟读出，升级零操作。
     */
    val imageProfiles: Flow<List<ApiProfile>> = store.data.map { prefs ->
        val stored = ApiProfileCodec.decode(prefs[keyImgProfiles] ?: "")
        if (stored.isNotEmpty()) {
            stored.map { it.toProfile() }
        } else {
            val legacyBase = prefs[keyImgBase] ?: ""
            val legacyModel = prefs[keyImgModel] ?: ""
            val legacyKey = resolveImageKey(prefs)
            if (legacyBase.isBlank() && legacyModel.isBlank() && legacyKey.isBlank()) {
                emptyList()
            } else {
                listOf(
                    ApiProfile(
                        id = DEFAULT_IMAGE_PROFILE_ID,
                        name = "默认生图模型",
                        baseUrl = legacyBase,
                        apiKey = legacyKey,
                        model = legacyModel,
                    )
                )
            }
        }
    }

    /** 当前默认的生图模型 id（指向已删除的模型时回落到第一条） */
    val imageActiveProfileId: Flow<String> = store.data.map { prefs ->
        val id = prefs[keyImgActiveProfile] ?: DEFAULT_IMAGE_PROFILE_ID
        val storedIds = ApiProfileCodec.decode(prefs[keyImgProfiles] ?: "").map { it.id }
        if (storedIds.isEmpty() || id in storedIds) id else storedIds.first()
    }

    /**
     * 当前默认的生图配置（只要一个配置的调用方入口）：取默认那条，取不到就用第一条。
     * ⚠️ 「生图是否可用」用这个判断会漏掉"默认那条不完整、但别的还能用"，
     * 需要那个语义请用 [imageProfiles]（`.any { it.isComplete }`）。
     */
    val imageApiConfig: Flow<ImageApiConfig> =
        combine(imageProfiles, imageActiveProfileId) { profiles, activeId ->
            val hit = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
            ImageApiConfig(
                baseUrl = hit?.baseUrl ?: "",
                apiKey = hit?.apiKey ?: "",
                model = hit?.model ?: "",
            )
        }.distinctUntilChanged()

    /** 新增或更新一条生图模型；[profile].id 为空则自动生成 */
    suspend fun saveImageProfile(profile: ApiProfile): String = withContext(Dispatchers.IO) {
        val id = profile.id.ifBlank { "img" + System.currentTimeMillis() }
        val list = currentStoredImageProfiles()
        val stored = StoredApiProfile.of(
            id = id,
            name = profile.name.trim().ifBlank { "未命名模型" },
            baseUrl = profile.baseUrl.trim(),
            model = profile.model.trim(),
            key = profile.apiKey.trim(),
        )
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) list[index] = stored else list.add(stored)
        store.edit { prefs ->
            prefs[keyImgProfiles] = ApiProfileCodec.encode(list)
            if (prefs[keyImgActiveProfile] == null && list.isNotEmpty()) {
                prefs[keyImgActiveProfile] = list.first().id
            }
        }
        id
    }

    /** 切换默认生图模型 */
    suspend fun setActiveImageProfile(id: String) {
        store.edit { it[keyImgActiveProfile] = id }
    }

    /** 删除生图模型；删掉默认那条时自动回落到剩下的第一条 */
    suspend fun deleteImageProfile(id: String) = withContext(Dispatchers.IO) {
        val list = currentStoredImageProfiles().filterNot { it.id == id }
        store.edit { prefs ->
            prefs[keyImgProfiles] = ApiProfileCodec.encode(list)
            val active = prefs[keyImgActiveProfile]
            if (active == null || active == id) {
                if (list.isEmpty()) prefs.remove(keyImgActiveProfile) else prefs[keyImgActiveProfile] = list.first().id
            }
        }
    }

    /**
     * 读当前生图模型列表。老单条配置（img_base_url 四键）在这里**先实体化**进来：
     * 否则用户老配置没迁、新增第二条时 `img_profiles` 一变非空，虚拟读出失效，老配置就凭空消失。
     */
    private suspend fun currentStoredImageProfiles(): MutableList<StoredApiProfile> {
        val prefs = store.data.first()
        val stored = ApiProfileCodec.decode(prefs[keyImgProfiles] ?: "")
        if (stored.isNotEmpty()) return stored.toMutableList()
        val legacyBase = prefs[keyImgBase] ?: ""
        val legacyModel = prefs[keyImgModel] ?: ""
        val legacyKey = resolveImageKey(prefs)
        if (legacyBase.isBlank() && legacyModel.isBlank() && legacyKey.isBlank()) return mutableListOf()
        return mutableListOf(
            StoredApiProfile.of(
                id = DEFAULT_IMAGE_PROFILE_ID,
                name = "默认生图模型",
                baseUrl = legacyBase,
                model = legacyModel,
                key = legacyKey,
            )
        )
    }

    /** 解密老单条生图配置的 Key（密文优先，Keystore 不可用时回退明文） */
    private fun resolveImageKey(prefs: Preferences): String {
        val sealed = prefs[keyImgKeyEnc]
        return if (sealed.isNullOrBlank()) {
            prefs[keyImgKeyPlain] ?: ""
        } else {
            resolveSealed(sealed) ?: (prefs[keyImgKeyPlain] ?: "")
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

    // ===== MCP 服务器（P3-3）=====
    //
    // 白名单制：用户手动添加（名称 + Streamable HTTP URL + 可选 token），
    // 没有任何"发现机制"。清单整张塞 `mcp_servers` 一个键（JSON 数组，编解码见
    // McpServerCodec）；token 按 id 走 Keystore 加密单独存（与 ApiConfig 的 Key 同一纪律）。
    private val keyMcpServers = stringPreferencesKey("mcp_servers")

    /** 服务器清单流（设置页列表用） */
    val mcpServersFlow: Flow<List<com.menghuan.app.mcp.McpServerConfig>> =
        store.data.map { com.menghuan.app.mcp.McpServerCodec.decode(it[keyMcpServers] ?: "") }

    suspend fun mcpServers(): List<com.menghuan.app.mcp.McpServerConfig> =
        com.menghuan.app.mcp.McpServerCodec.decode(store.data.first()[keyMcpServers] ?: "")

    /** 新增/覆盖一条（按 id 替换）；[token] 为空串 = 清除该服务器的 token，null = 不动它 */
    suspend fun saveMcpServer(server: com.menghuan.app.mcp.McpServerConfig, token: String? = null) =
        withContext(Dispatchers.IO) {
            val sealed = if (token.isNullOrEmpty()) null else ApiKeyCipher.encrypt(token)
            store.edit { prefs ->
                val list = com.menghuan.app.mcp.McpServerCodec.decode(prefs[keyMcpServers] ?: "")
                    .filterNot { it.id == server.id } + server
                prefs[keyMcpServers] = com.menghuan.app.mcp.McpServerCodec.encode(list)
                if (token != null) {
                    val encKey = stringPreferencesKey("mcp_token_${server.id}_enc")
                    val plainKey = stringPreferencesKey("mcp_token_${server.id}_plain")
                    when {
                        sealed != null -> {
                            prefs[encKey] = sealed
                            prefs.remove(plainKey)
                        }

                        token.isNotEmpty() -> {
                            prefs[plainKey] = token
                            prefs.remove(encKey)
                        }

                        else -> {
                            prefs.remove(encKey)
                            prefs.remove(plainKey)
                        }
                    }
                }
            }
        }

    suspend fun removeMcpServer(id: String) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            val list = com.menghuan.app.mcp.McpServerCodec.decode(prefs[keyMcpServers] ?: "")
                .filterNot { it.id == id }
            prefs[keyMcpServers] = com.menghuan.app.mcp.McpServerCodec.encode(list)
            prefs.remove(stringPreferencesKey("mcp_token_${id}_enc"))
            prefs.remove(stringPreferencesKey("mcp_token_${id}_plain"))
        }
    }

    /** 某台服务器的 token（密文优先，Keystore 不可用时回退明文；没有 = 空串） */
    suspend fun mcpTokenFor(id: String): String {
        val prefs = store.data.first()
        val sealed = prefs[stringPreferencesKey("mcp_token_${id}_enc")]
        return if (sealed.isNullOrBlank()) {
            prefs[stringPreferencesKey("mcp_token_${id}_plain")] ?: ""
        } else {
            resolveSealed(sealed) ?: (prefs[stringPreferencesKey("mcp_token_${id}_plain")] ?: "")
        }
    }

    // ---- tools 能力探测缓存（P3-1）：按 `baseUrl|model` 记，换配置旧结论自动作废 ----
    private val keyMcpToolsProbe = stringPreferencesKey("mcp_tools_probe")

    /** 某端点的 tools 探测结论："ok" = 支持；其余字符串 = 失败原因；null = 没探过 */
    suspend fun toolsProbeFor(endpointKey: String): String? = runCatching {
        org.json.JSONObject(store.data.first()[keyMcpToolsProbe] ?: "{}")
            .optString(endpointKey).takeIf { it.isNotBlank() }
    }.getOrNull()

    suspend fun setToolsProbe(endpointKey: String, result: String?) = withContext(Dispatchers.IO) {
        store.edit { prefs ->
            val table = runCatching { org.json.JSONObject(prefs[keyMcpToolsProbe] ?: "{}") }
                .getOrDefault(org.json.JSONObject())
            if (result == null) table.remove(endpointKey) else table.put(endpointKey, result)
            prefs[keyMcpToolsProbe] = table.toString()
        }
    }

    // ===== 内置「应用助手」手册的归属（v0.51.1 升级机制） =====

    /**
     * 助手手册是否被用户亲手改过。三态：
     * - null = 从未判定（v0.51.1 之前装机的老库，由调用方按"是不是内置手册的形状"判一次）
     * - true = 用户在提示词页/人设编辑里存过自定义内容 → App 从此不再自动覆盖
     * - false 不落盘（[setAssistantPromptCustomized] 只存 true）：改回默认值就等于
     *   把手册重新交还给 App 维护，与 null 同义
     */
    suspend fun assistantPromptCustomized(): Boolean? =
        store.data.first()[keyAssistantPromptCustomized]

    suspend fun setAssistantPromptCustomized(customized: Boolean) {
        store.edit { prefs ->
            if (customized) prefs[keyAssistantPromptCustomized] = true
            else prefs.remove(keyAssistantPromptCustomized)
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
