package com.daoshi.app

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.daoshi.app.data.ApiConfig
import com.daoshi.app.data.SettingsRepository
import com.daoshi.app.data.settingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * API Key 加密迁移单测。注意：Robolectric JVM 没有 AndroidKeyStore provider，
 * ApiKeyCipher 加密必失败 → 本测试验证的正是**回退读取路径**（存量明文不丢、读写闭环）。
 * 真机上的加密落盘路径由 ApiKeyCipher 的 runCatching + KeyStore 标准保证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MentorApp::class, sdk = [34])
class SettingsRepositoryTest {

    @Test
    fun legacyPlaintextFallbackAndSaveRoundtrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MentorApp>()
        val repo = SettingsRepository(context)

        // 预置 v0.2.2 及以前的存量明文数据
        context.settingsStore.edit { prefs ->
            prefs[stringPreferencesKey("api_base_url")] = "https://api.test.com/v1"
            prefs[stringPreferencesKey("api_key")] = "sk-legacy"
            prefs[stringPreferencesKey("api_model")] = "test-model"
        }

        // 迁移读取：JVM 无 Keystore → 回退读明文，密钥不丢
        val migrated = repo.apiConfig.first()
        assertEquals("https://api.test.com/v1", migrated.baseUrl)
        assertEquals("sk-legacy", migrated.apiKey)
        assertEquals("test-model", migrated.model)
        assertTrue(migrated.isComplete)

        // 回写后重新读取（JVM 上走明文兜底路径，同样不能丢）
        repo.saveApiConfig(ApiConfig("https://api.test.com/v1", "sk-new", "test-model"))
        val after = repo.apiConfig.first()
        assertEquals("sk-new", after.apiKey)
        assertTrue(after.isComplete)
    }
}
