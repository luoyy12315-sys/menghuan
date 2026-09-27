package com.daoshi.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

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
class SettingsRepository(private val context: Context) {

    private val keyBase = stringPreferencesKey("api_base_url")
    private val keyKey = stringPreferencesKey("api_key")        // 旧明文键（迁移后删除）
    private val keyModel = stringPreferencesKey("api_model")
    private val keyKeyEnc = stringPreferencesKey("api_key_enc") // 新密文键 Base64(IV+GCM)

    // Keystore 解密有成本：密文未变时复用内存中的明文
    private var cachedSealed: String? = null
    private var cachedPlain: String = ""

    val apiConfig: Flow<ApiConfig> = context.settingsStore.data.map { prefs ->
        ApiConfig(
            baseUrl = prefs[keyBase] ?: "",
            model = prefs[keyModel] ?: "",
            apiKey = withContext(Dispatchers.IO) { resolveApiKey(prefs) },
        )
    }

    /** 密文优先，旧明文自动迁移，Keystore 不可用时回退读明文 */
    private suspend fun resolveApiKey(prefs: Preferences): String {
        val sealed = prefs[keyKeyEnc]
        val legacyPlain = prefs[keyKey] ?: ""
        return when {
            sealed != null -> {
                val plain = if (sealed == cachedSealed) cachedPlain else decrypt(sealed)
                if (legacyPlain.isNotEmpty()) {
                    // 加密版已就绪：清掉历史明文
                    context.settingsStore.edit { it.remove(keyKey) }
                }
                plain
            }
            legacyPlain.isNotEmpty() -> {
                val sealedText = ApiKeyCipher.encrypt(legacyPlain)
                if (sealedText != null) {
                    // 旧数据迁移：明文 → 密文，删除明文
                    context.settingsStore.edit { it[keyKeyEnc] = sealedText; it.remove(keyKey) }
                    cache(sealedText, legacyPlain)
                }
                // encrypt 失败（JVM 单测/Keystore 异常）：回退读明文且不删，绝不让密钥凭空消失
                legacyPlain
            }
            else -> ""
        }
    }

    suspend fun saveApiConfig(config: ApiConfig) = withContext(Dispatchers.IO) {
        val sealedText = if (config.apiKey.isEmpty()) null else ApiKeyCipher.encrypt(config.apiKey)
        context.settingsStore.edit { prefs ->
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
        cache(sealedText, config.apiKey)
    }

    private fun cache(sealed: String?, plain: String) {
        cachedSealed = sealed
        cachedPlain = plain
    }

    private fun decrypt(sealed: String): String {
        val plain = ApiKeyCipher.decrypt(sealed) ?: "" // 密钥失效 → 空值，提示用户重填
        cache(if (plain.isEmpty()) null else sealed, plain)
        return plain
    }
}
