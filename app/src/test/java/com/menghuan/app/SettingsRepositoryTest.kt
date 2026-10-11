package com.menghuan.app

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.ApiConfig
import com.menghuan.app.data.CallBackgroundStore
import com.menghuan.app.data.SEARCH_BOCHA
import com.menghuan.app.data.SEARCH_CUSTOM
import com.menghuan.app.data.SEARCH_TAVILY
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.data.settingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * 只删本类这三条新测试**自己写过**的键，不 `clear()` 整库。
     *
     * `settingsStore` 是进程级单例（Robolectric 下同一个 JVM 里所有测试类共用同一份文件），
     * 整库 clear 会把**排在后面的那个类**刚写进去的状态一起抹掉：
     * 实测加了三条测试之后，`AssistantManualUpgradeTest.userEditedManualIsNotOverwritten`
     * 拿不到自己写的 `assistant_prompt_customized` 标记（手册被当成"没改过"刷回默认），
     * 而 `UiRenderTest.render12WeeklyPlaceholder` 直接在主线程 `runBlocking { saveApiConfig }`
     * 上等死 —— 都是这一句 `it.clear()` 殃及邻居，不是被测代码坏了。
     */
    private suspend fun wipeSearchAndCallBackgroundKeys(context: MenghuanApp) {
        context.settingsStore.edit { p ->
            p.remove(stringPreferencesKey("search_provider"))
            p.remove(stringPreferencesKey("search_key_enc"))
            p.remove(stringPreferencesKey("search_key_plain"))
            p.remove(stringPreferencesKey("call_background"))
            listOf(SEARCH_BOCHA, SEARCH_TAVILY, SEARCH_CUSTOM).forEach { provider ->
                listOf("key_enc", "key_plain", "url").forEach { field ->
                    p.remove(stringPreferencesKey("search_${provider}_$field"))
                }
            }
        }
    }

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

    /**
     * v0.66.0（用户点名"联网搜索切换不了，切换国内国外 key 都不变"）：三家**各一份 Key**。
     *
     * 博查与 Tavily 过去共用一把 `search_key_*`，切服务商时看到和写进去的都是同一串，
     * 拿博查的 Key 去打 Tavily 永远"搜不到"——与 v0.52.0 语音合成踩过的是同一个坑，
     * 那一次同样是 `TtsProviderConfigTest` 先抓出来的。这三条断言守的就是"别又串味"。
     */
    @Test
    fun searchKeysAreBucketedPerProvider(): Unit = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val repo = SettingsRepository(context)
        wipeSearchAndCallBackgroundKeys(context)

        repo.saveSearchConfig(SEARCH_BOCHA, "sk-bocha")
        repo.saveSearchConfig(SEARCH_TAVILY, "sk-tavily")
        repo.saveSearchConfig(SEARCH_CUSTOM, "sk-custom", "https://relay.example.com/v1/web-search")

        assertEquals("sk-bocha", repo.searchApiKeyOf(SEARCH_BOCHA).first())
        assertEquals("sk-tavily", repo.searchApiKeyOf(SEARCH_TAVILY).first())
        assertEquals("sk-custom", repo.searchApiKeyOf(SEARCH_CUSTOM).first())
        // 地址只属于自定义那一家
        assertEquals("https://relay.example.com/v1/web-search", repo.searchCustomUrl.first())
        assertEquals("https://relay.example.com/v1/web-search", repo.searchEndpointFor(SEARCH_CUSTOM))
        assertEquals("", repo.searchEndpointFor(SEARCH_BOCHA))
        // 最后一次保存的那家成为当前生效
        assertEquals(SEARCH_CUSTOM, repo.searchProvider.first())
        assertEquals("sk-custom", repo.searchKeyFor(SEARCH_CUSTOM))

        // 清空一家不许碰另外两家（v0.52.0 那条"保存别家时把老键直接清掉"的复刻断言）
        repo.saveSearchConfig(SEARCH_TAVILY, "")
        assertEquals("", repo.searchApiKeyOf(SEARCH_TAVILY).first())
        assertEquals("sk-bocha", repo.searchApiKeyOf(SEARCH_BOCHA).first())
        assertEquals("sk-custom", repo.searchApiKeyOf(SEARCH_CUSTOM).first())

        wipeSearchAndCallBackgroundKeys(context)
    }

    /**
     * 老单桶（v0.53.5~v0.65：博查与 Tavily 共用）→ **虚拟归属到当时生效的那一家**，
     * 升级零操作；并且在"保存另一家"时先把它搬进桶再清老键——
     * 顺序反了（先清老键）用户那把填好的 Key 就凭空消失了。
     */
    @Test
    fun legacySearchKeyAttributesToItsOwnerAndSurvivesOtherProviderSave(): Unit = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val repo = SettingsRepository(context)
        wipeSearchAndCallBackgroundKeys(context)
        context.settingsStore.edit { prefs ->
            prefs[stringPreferencesKey("search_key_plain")] = "sk-legacy"
            prefs[stringPreferencesKey("search_provider")] = SEARCH_BOCHA
        }

        // 老数据算在博查头上（当时生效那家），Tavily 看到的是空
        assertEquals("sk-legacy", repo.searchApiKeyOf(SEARCH_BOCHA).first())
        assertEquals("", repo.searchApiKeyOf(SEARCH_TAVILY).first())

        // 切到 Tavily 保存：老键先落到博查桶里，才会被清掉
        repo.saveSearchConfig(SEARCH_TAVILY, "sk-tavily")
        assertEquals("sk-legacy", repo.searchApiKeyOf(SEARCH_BOCHA).first())
        assertEquals("sk-tavily", repo.searchApiKeyOf(SEARCH_TAVILY).first())
        assertEquals(SEARCH_TAVILY, repo.searchProvider.first())
        // 老单桶已经落桶，必须被清干净（留着会在字段被删空时"复活"成半新半旧的一屏）
        assertFalse(context.settingsStore.data.first().asMap().containsKey(stringPreferencesKey("search_key_plain")))

        wipeSearchAndCallBackgroundKeys(context)
    }

    /**
     * 视频通话背景（v0.66.0）：档位按角色各存一行，`file:` 才算自定义；
     * 传空串 = 删掉这一行（不是写一个空值进去）。
     */
    @Test
    fun callBackgroundIsPerPersonaAndRemovable(): Unit = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<MenghuanApp>()
        val repo = SettingsRepository(context)
        wipeSearchAndCallBackgroundKeys(context)

        repo.setCallBackground(7L, CallBackgroundStore.PRESET)
        repo.setCallBackground(9L, "file:壁纸.jpg")
        val table = repo.callBackground.first()
        assertEquals(CallBackgroundStore.PRESET, table[7L])
        assertEquals("file:壁纸.jpg", table[9L])
        assertTrue(CallBackgroundStore.isCustom(table[9L].orEmpty()))
        assertFalse(CallBackgroundStore.isCustom(table[7L].orEmpty()))
        assertEquals("壁纸", CallBackgroundStore.labelOf("file:壁纸.jpg"))
        assertEquals("系统预设", CallBackgroundStore.labelOf(""))

        // 空值 = 删行（人设删除走的就是这条）
        repo.setCallBackground(9L, "")
        val after = repo.callBackground.first()
        assertFalse(after.containsKey(9L))
        assertEquals(CallBackgroundStore.PRESET, after[7L])

        wipeSearchAndCallBackgroundKeys(context)
    }
}
