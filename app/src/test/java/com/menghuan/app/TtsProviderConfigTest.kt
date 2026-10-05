package com.menghuan.app

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.data.TtsProvider
import com.menghuan.app.data.VoiceRef
import com.menghuan.app.data.settingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 语音合成的"每家一套独立配置"（v0.52.0）。
 *
 * 起因是真机反馈：切到小米 MiMo，下面显示的 Key / 地址 / 模型 还是硅基那一份——
 * 因为 v0.50.0 只有**一套** `tts_*` 键，provider 只是个字段，三家共用同一屏数据。
 * 现在按家分桶，老数据虚拟归属到**当时生效的那一家**，升级后零操作。
 *
 * JVM 没有 Keystore → Key 走明文回退键（与 [SettingsRepositoryTest] 同一套前提）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MenghuanApp::class, sdk = [34])
class TtsProviderConfigTest {

    private lateinit var repo: com.menghuan.app.data.SettingsRepository
    private lateinit var store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>

    @Before
    fun setUp() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<MenghuanApp>()
            repo = com.menghuan.app.data.SettingsRepository(context)
            store = context.settingsStore
            store.edit { it.clear() }
        }
    }

    /** 模拟 v0.50.0～v0.51.x 留下来的老单桶键 */
    private suspend fun seedLegacy(provider: TtsProvider, key: String, base: String, model: String) {
        store.edit { prefs ->
            prefs[stringPreferencesKey("tts_provider")] = provider.name
            prefs[stringPreferencesKey("tts_api_key_plain")] = key
            prefs[stringPreferencesKey("tts_base_url")] = base
            prefs[stringPreferencesKey("tts_model")] = model
        }
    }

    @Test
    fun legacyBucketReadsAsTheProviderThatWasActive() = runBlocking {
        seedLegacy(TtsProvider.SILICONFLOW, "sk-sf", "https://api.siliconflow.cn/v1", "FunAudioLLM/CosyVoice2-0.5B")

        val sf = repo.ttsConfigOf(TtsProvider.SILICONFLOW).first()
        val mimo = repo.ttsConfigOf(TtsProvider.XIAOMI_MIMO).first()
        assertEquals("sk-sf", sf.apiKey)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", sf.model)
        // 关键：切到小米不能看到硅基的东西
        assertEquals("", mimo.apiKey)
        assertEquals("", mimo.baseUrl)
        assertEquals(TtsProvider.XIAOMI_MIMO, mimo.provider)
    }

    @Test
    fun savingOneProviderLeavesTheOthersAlone() = runBlocking {
        repo.saveTtsConfig(
            TtsConfig(
                provider = TtsProvider.SILICONFLOW,
                baseUrl = "https://api.siliconflow.cn/v1",
                apiKey = "sk-sf",
                model = "FunAudioLLM/CosyVoice2-0.5B",
            )
        )
        repo.saveTtsConfig(
            TtsConfig(provider = TtsProvider.XIAOMI_MIMO, baseUrl = "", apiKey = "sk-mimo", model = "mimo-v2.5-tts")
        )

        val sf = repo.ttsConfigOf(TtsProvider.SILICONFLOW).first()
        val mimo = repo.ttsConfigOf(TtsProvider.XIAOMI_MIMO).first()
        assertEquals("sk-sf", sf.apiKey)
        assertEquals("sk-mimo", mimo.apiKey)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", sf.model)
        assertEquals("mimo-v2.5-tts", mimo.model)
    }

    @Test
    fun legacyKeysRetireAfterFirstBucketWrite() = runBlocking {
        seedLegacy(TtsProvider.SILICONFLOW, "sk-sf", "https://old", "old-model")
        repo.saveTtsConfig(TtsConfig(provider = TtsProvider.CUSTOM, baseUrl = "https://x/v1", apiKey = "k", model = "m"))

        val prefs = store.data.first()
        assertNull(prefs[stringPreferencesKey("tts_api_key_plain")])
        assertNull(prefs[stringPreferencesKey("tts_base_url")])
        // 硅基那份老数据仍要读得到（老键被清了，但它是"当时生效那家"——写桶时就该把值搬过去）
        assertEquals("sk-sf", repo.ttsConfigOf(TtsProvider.SILICONFLOW).first().apiKey)
    }

    @Test
    fun onlyACompleteConfigBecomesActive() = runBlocking {
        repo.saveTtsConfig(TtsConfig(provider = TtsProvider.SILICONFLOW, baseUrl = "https://a", apiKey = "k", model = "m"))
        assertEquals(TtsProvider.SILICONFLOW, repo.ttsConfig.first().provider)

        // 切到小米但 Key 还没填：只存不切，否则通话会莫名变成"未配置"
        repo.saveTtsConfig(TtsConfig(provider = TtsProvider.XIAOMI_MIMO, baseUrl = "https://b", apiKey = "", model = "m"))
        assertEquals(TtsProvider.SILICONFLOW, repo.ttsConfig.first().provider)

        repo.saveTtsConfig(
            TtsConfig(provider = TtsProvider.XIAOMI_MIMO, baseUrl = "https://b", apiKey = "k2", model = "m")
        )
        assertEquals(TtsProvider.XIAOMI_MIMO, repo.ttsConfig.first().provider)
    }

    @Test
    fun defaultVoiceAndManualIdArePerProvider() = runBlocking {
        repo.setTtsDefaultVoice(VoiceRef(TtsProvider.SILICONFLOW, "speech:a:b:c"))
        repo.setTtsManualVoiceId(TtsProvider.CUSTOM, "alloy")

        assertEquals("speech:a:b:c", repo.ttsDefaultVoice(TtsProvider.SILICONFLOW).first()?.voiceId)
        assertNull(repo.ttsDefaultVoice(TtsProvider.XIAOMI_MIMO).first())
        assertEquals("alloy", repo.ttsManualVoiceId(TtsProvider.CUSTOM).first())
        assertEquals("", repo.ttsManualVoiceId(TtsProvider.SILICONFLOW).first())

        repo.setTtsManualVoiceId(TtsProvider.CUSTOM, "  ")
        assertEquals("", repo.ttsManualVoiceId(TtsProvider.CUSTOM).first())
    }

    /** 会话音色的三级回落：人设专属 → 板块默认 → 该家第一个预置 */
    @Test
    fun voiceForConversationFallsBackInOrder() = runBlocking {
        val config = TtsConfig(provider = TtsProvider.SILICONFLOW, baseUrl = "https://a", apiKey = "k", model = "m")
        assertEquals("alex", repo.voiceForConversation(7L, config).voiceId)

        repo.setTtsDefaultVoice(VoiceRef(TtsProvider.SILICONFLOW, "anna"))
        assertEquals("anna", repo.voiceForConversation(7L, config).voiceId)

        repo.setVoiceRefFor(7L, VoiceRef(TtsProvider.SILICONFLOW, "speech:mine:1:2"))
        assertEquals("speech:mine:1:2", repo.voiceForConversation(7L, config).voiceId)

        // 人设存的是硅基的 ID，现在生效的是小米 → 不认，回落到小米那家的板块默认（没设过 → 第一个预置）
        val mimo = TtsConfig(provider = TtsProvider.XIAOMI_MIMO, baseUrl = "https://b", apiKey = "k", model = "m")
        assertEquals("白桦", repo.voiceForConversation(7L, mimo).voiceId)
        repo.setTtsDefaultVoice(VoiceRef(TtsProvider.XIAOMI_MIMO, "冰糖"))
        assertEquals("冰糖", repo.voiceForConversation(7L, mimo).voiceId)
    }
}
