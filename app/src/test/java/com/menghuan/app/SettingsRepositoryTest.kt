package com.menghuan.app

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.ApiConfig
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.data.settingsStore
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
@Config(application = MenghuanApp::class, sdk = [34])
class SettingsRepositoryTest {

    @Test
    fun legacyPlaintextFallbackAndSaveRoundtrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MenghuanApp>()
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

    @Test
    fun weeklyReportNotifyDefaultOffAndToggle() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val repo = SettingsRepository(context)
        context.settingsStore.edit { it.clear() }

        // 默认关：周报不时效敏感，静默生成
        assertEquals(false, repo.weeklyReportNotifyEnabled.first())
        repo.setWeeklyReportNotifyEnabled(true)
        assertEquals(true, repo.weeklyReportNotifyEnabled.first())
        repo.setWeeklyReportNotifyEnabled(false)
        assertEquals(false, repo.weeklyReportNotifyEnabled.first())
    }

    /**
     * 「清除全部数据」（v0.36.5）的边界：只清会话级派生键（未读标记 / 提炼水位线 / 草稿），
     * 配置类键——接口、主题、会话级接口覆盖——必须原样活着，否则"保留人设与接口"就是空话。
     */
    @Test
    fun clearConversationScopedKeysKeepsConfig() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val repo = SettingsRepository(context)
        context.settingsStore.edit { it.clear() }

        repo.saveApiConfig(ApiConfig("https://api.test.com/v1", "sk-keep", "test-model"))
        repo.setThemeMode("DARK")
        repo.setPersonaApiProfile(7, "profile-keep")
        repo.markChatRead(7)
        repo.setExtractionWatermark(7, 42)
        repo.setChatDraft(7, "写到一半")

        repo.clearConversationScopedKeys()

        assertTrue(repo.readMarks.first().isEmpty())
        assertEquals(0L, repo.extractionWatermark(7))
        assertEquals("", repo.chatDraft(7))
        assertEquals("profile-keep", repo.personaApiProfileId(7).first())
        assertEquals("DARK", repo.themeMode.first())
        assertEquals("sk-keep", repo.apiConfig.first().apiKey)

        // settingsStore 是进程级单例：不清干净会串到同类里其它测试（apiConfig 优先读 api_profiles）
        context.settingsStore.edit { it.clear() }
        assertTrue(context.settingsStore.data.first().asMap().isEmpty())
    }

    /**
     * Keystore 解密缓存 + 启动预热（进页卡顿修复）：warmKeyCache 必须吞掉一切异常
     * （JVM 无 Keystore 也照样走完），且预热/重复读取后接口配置的回退语义不变
     * ——密文解不开就回落明文字段，绝不让密钥凭空消失。
     */
    @Test
    fun warmKeyCacheIsSafeAndPreservesFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val repo = SettingsRepository(context)
        context.settingsStore.edit { it.clear() }

        repo.saveApiConfig(ApiConfig("https://api.test.com/v1", "sk-warm", "test-model"))

        // 预热：JVM 无 Keystore 也不抛异常，重复调用幂等
        repo.warmKeyCache()
        repo.warmKeyCache()

        // 预热后读取：多接口路径走缓存 map，回退语义不变
        val cfg = repo.apiConfig.first()
        assertEquals("sk-warm", cfg.apiKey)
        assertTrue(cfg.isComplete)

        context.settingsStore.edit { it.clear() }
        assertTrue(context.settingsStore.data.first().asMap().isEmpty())
    }
}
