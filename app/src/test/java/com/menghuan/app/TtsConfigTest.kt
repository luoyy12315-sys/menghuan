package com.menghuan.app

import com.menghuan.app.data.ClonedVoice
import com.menghuan.app.data.TtsAudioFormat
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.data.TtsProvider
import com.menghuan.app.data.TtsTarget
import com.menghuan.app.data.TtsVoices
import com.menghuan.app.data.VoiceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音合成配置的纯逻辑（音色编解码、`voice` 拼法、容器嗅探、MiMo 上限换算）。
 *
 * 这些算错都属于"静默坏掉"那一类：音色漏了模型前缀会被服务商判成非法音色、
 * 容器嗅错会让通话播放变速变调、上限按原文件字节数算会在编码之后才被拒。
 */
class TtsConfigTest {

    // ===== VoiceRef 编解码 =====

    @Test
    fun `音色引用编解码往返一致`() {
        val ref = VoiceRef(TtsProvider.SILICONFLOW, "speech:myname:acct123:voice456")
        assertEquals(ref, VoiceRef.decode(ref.encode()))
    }

    @Test
    fun `中文音色 ID 原样往返`() {
        val ref = VoiceRef(TtsProvider.XIAOMI_MIMO, "冰糖")
        assertEquals("XIAOMI_MIMO|冰糖", ref.encode())
        assertEquals(ref, VoiceRef.decode(ref.encode()))
    }

    @Test
    fun `老版本存的 Edge 音色名解不出来时返回空而不是崩`() {
        // v0.47.1 的 call_voice_<id> 存的是 "zh-CN-XiaoxiaoNeural" 这类裸音色名，没有 provider 段；
        // 读时解不出，调用方回落默认即可（方案要求的兼容行为：忽略并回落，不报错不崩）。
        assertNull(VoiceRef.decode("zh-CN-XiaoxiaoNeural"))
        assertNull(VoiceRef.decode(""))
        assertNull(VoiceRef.decode(null))
        assertNull(VoiceRef.decode("|anna"))
        assertNull(VoiceRef.decode("NOT_A_PROVIDER|anna"))
        assertNull(VoiceRef.decode("SILICONFLOW|"))
    }

    @Test
    fun `provider 名不认识时回落默认那家`() {
        assertEquals(TtsProvider.SILICONFLOW, TtsProvider.fromName(null))
        assertEquals(TtsProvider.SILICONFLOW, TtsProvider.fromName(""))
        assertEquals(TtsProvider.SILICONFLOW, TtsProvider.fromName("whichever"))
        assertEquals(TtsProvider.XIAOMI_MIMO, TtsProvider.fromName(" XIAOMI_MIMO "))
    }

    // ===== 配置：留空回落各家默认 =====

    @Test
    fun `地址与模型留空用服务商默认，填了以用户为准`() {
        val mimo = TtsConfig(provider = TtsProvider.XIAOMI_MIMO)
        assertEquals("https://api.xiaomimimo.com/v1", mimo.effectiveBaseUrl())
        assertEquals("mimo-v2.5-tts", mimo.effectiveModel())
        // Token Plan 的 Key 必须配专属 Base URL，所以用户填的值必须生效；结尾斜杠要裁掉
        val custom = TtsConfig(provider = TtsProvider.XIAOMI_MIMO, baseUrl = "https://tp.example.com/v1/")
        assertEquals("https://tp.example.com/v1", custom.effectiveBaseUrl())
    }

    @Test
    fun `配置齐不齐：三家都要 Key，自定义端点还必须填地址和模型`() {
        assertFalse(TtsConfig(provider = TtsProvider.SILICONFLOW).isComplete)
        assertTrue(TtsConfig(provider = TtsProvider.SILICONFLOW, apiKey = "k").isComplete)
        assertFalse(TtsConfig(provider = TtsProvider.CUSTOM, apiKey = "k").isComplete)
        assertFalse(
            TtsConfig(provider = TtsProvider.CUSTOM, apiKey = "k", baseUrl = "https://a.com/v1").isComplete,
        )
        assertTrue(
            TtsConfig(
                provider = TtsProvider.CUSTOM, apiKey = "k",
                baseUrl = "https://a.com/v1", model = "tts-1",
            ).isComplete,
        )
    }

    // ===== 硅基 voice 字段拼法 =====

    @Test
    fun `预置音色必须补模型名前缀`() {
        val model = "FunAudioLLM/CosyVoice2-0.5B"
        assertEquals("$model:anna", TtsVoices.siliconVoice(model, "anna"))
    }

    @Test
    fun `克隆 uri 原样发，不再拼前缀`() {
        val uri = "speech:myname:acct:voice"
        assertEquals(uri, TtsVoices.siliconVoice("FunAudioLLM/CosyVoice2-0.5B", uri))
    }

    @Test
    fun `已带模型前缀的值不重复拼`() {
        val model = "FunAudioLLM/CosyVoice2-0.5B"
        assertEquals("$model:anna", TtsVoices.siliconVoice(model, "$model:anna"))
    }

    // ===== 预置音色表 =====

    /** v0.52.0：每家只留两个预置（一男一女），多的那些是噪音 */
    @Test
    fun `硅基只留一男一女两个预置`() {
        assertEquals(
            listOf("alex", "anna"),
            TtsVoices.SILICONFLOW_PRESETS.map { it.id },
        )
        assertEquals("alex", TtsVoices.defaultVoiceId(TtsProvider.SILICONFLOW))
    }

    @Test
    fun `MiMo 只留一男一女，中文 ID 就是中文本身`() {
        assertEquals(listOf("白桦", "冰糖"), TtsVoices.MIMO_PRESETS.map { it.id })
        assertEquals("白桦", TtsVoices.defaultVoiceId(TtsProvider.XIAOMI_MIMO))
    }

    /** 音色板块 = 预置 + 克隆 + 手填 ID；人设编辑页的候选就是这一份 */
    @Test
    fun `音色板块按服务商组名单`() {
        val cloned = listOf(ClonedVoice("绘梨衣", "speech:a:b:c", "FunAudioLLM/CosyVoice2-0.5B"))
        val sf = TtsVoices.library(TtsProvider.SILICONFLOW, cloned, emptyList(), "speech:x")
        assertEquals(listOf("Alex · 男声", "Anna · 女声", "克隆 · 绘梨衣", "手动 · speech:x"), sf.map { it.label })
        // 本地样本只进 MiMo 的名单，硅基的克隆只进硅基的名单
        val mimo = TtsVoices.library(TtsProvider.XIAOMI_MIMO, cloned, listOf("我的.wav"), "")
        assertEquals(listOf("白桦 · 男声", "冰糖 · 女声", "克隆 · 我的"), mimo.map { it.label })
        assertTrue(mimo.last().ref.voiceId.startsWith("local:"))
        // 自定义端点没有预置、也拿不到别家的克隆/本地样本，名单就是那条手填 ID
        assertTrue(TtsVoices.library(TtsProvider.CUSTOM, cloned, listOf("a.wav"), "").isEmpty())
        assertEquals(
            listOf("手动 · alloy"),
            TtsVoices.library(TtsProvider.CUSTOM, cloned, listOf("a.wav"), "alloy").map { it.label },
        )
    }

    /** 人设存过旧音色、或换了服务商时，标签不能崩，也不能把别家的 ID 当成本家的 */
    @Test
    fun `音色标签回落`() {
        val cloned = listOf(ClonedVoice("绘梨衣", "speech:a:b:c", "m"))
        assertEquals(
            "默认 · alex",
            TtsVoices.labelOf(TtsProvider.SILICONFLOW, null, cloned, ""),
        )
        assertEquals(
            "克隆 · 绘梨衣",
            TtsVoices.labelOf(TtsProvider.SILICONFLOW, VoiceRef(TtsProvider.SILICONFLOW, "speech:a:b:c"), cloned, ""),
        )
        assertEquals(
            "手动 · my-voice",
            TtsVoices.labelOf(TtsProvider.CUSTOM, VoiceRef(TtsProvider.CUSTOM, "my-voice"), emptyList(), "my-voice"),
        )
        // 存的是硅基的音色，现在生效的是小米 → 按"默认"显示（那个 ID 在小米不认）
        assertEquals(
            "默认 · 白桦",
            TtsVoices.labelOf(TtsProvider.XIAOMI_MIMO, VoiceRef(TtsProvider.SILICONFLOW, "anna"), emptyList(), ""),
        )
    }

    @Test
    fun `自定义端点不预设音色候选`() {
        assertTrue(TtsVoices.presets(TtsProvider.CUSTOM).isEmpty())
        assertEquals("", TtsVoices.defaultVoiceId(TtsProvider.CUSTOM))
    }

    // ===== MiMo 克隆的 data URI 与上限 =====

    @Test
    fun `克隆音色拼成官方要求的 data URI`() {
        assertEquals(
            "data:audio/mpeg;base64,QUJD",
            TtsVoices.mimoCloneVoice("audio/mpeg", "QUJD"),
        )
    }

    @Test
    fun `上限按编码后长度算，不是原文件字节数`() {
        // base64 膨胀 4/3：7.5MB 原文件编码后正好 10MB
        assertEquals(10_485_760L, TtsVoices.mimoCloneBase64Size(7_864_320L))
        assertTrue(TtsVoices.isWithinMimoCloneLimit(7_864_320L))
        assertFalse(TtsVoices.isWithinMimoCloneLimit(7_864_321L))
    }

    // ===== base64 变体剥离 =====

    @Test
    fun `剥掉 data URI 前缀与折行空白`() {
        assertEquals("QUJDRA", TtsVoices.stripBase64("data:audio/wav;base64,QUJDRA"))
        assertEquals("QUJDRA", TtsVoices.stripBase64("  QUJD\r\nRA  "))
        assertEquals("QUJDRA", TtsVoices.stripBase64("QUJDRA"))
    }

    // ===== 容器嗅探 =====

    @Test
    fun `按魔术字节认出 MP3 与 WAV`() {
        val wav = ByteArray(44)
        "RIFF".toByteArray().copyInto(wav, 0)
        "WAVE".toByteArray().copyInto(wav, 8)
        assertEquals(TtsAudioFormat.WAV, TtsAudioFormat.sniff(wav))

        val id3 = ByteArray(20)
        "ID3".toByteArray().copyInto(id3, 0)
        assertEquals(TtsAudioFormat.MP3, TtsAudioFormat.sniff(id3))

        val frame = ByteArray(20)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFB.toByte()
        assertEquals(TtsAudioFormat.MP3, TtsAudioFormat.sniff(frame))
    }

    @Test
    fun `认不出来的容器返回空而不是硬猜`() {
        assertNull(TtsAudioFormat.sniff(ByteArray(0)))
        assertNull(TtsAudioFormat.sniff(ByteArray(10)))
        assertNull(TtsAudioFormat.sniff("""{"message":"boom"}""".toByteArray()))
        // 裸 PCM 没有魔术字节，本来就不该被认成 mp3/wav
        assertNull(TtsAudioFormat.sniff(ByteArray(20) { (it % 7).toByte() }))
    }

    @Test
    fun `本地克隆样本引用与路径互转`() {
        val ref = TtsVoices.localVoiceRef(TtsProvider.XIAOMI_MIMO, "/data/voices/a.wav")
        assertEquals("XIAOMI_MIMO|local:/data/voices/a.wav", ref.encode())
        assertEquals("/data/voices/a.wav", TtsVoices.localSamplePath(ref.voiceId))
        assertNull(TtsVoices.localSamplePath("冰糖"))
    }

    /**
     * v0.52.0：通话要 **WAV@24k**（回来正好喂 AudioTrack，链路里根本不解 MP3），
     * 语音条/试听要 MP3（小、MediaPlayer 直接播）。MiMo 永远 wav——
     * 它那个 mp3 枚举没有任何成熟实现用过，不赌。
     */
    @Test
    fun `合成目标决定要什么格式`() {
        assertEquals(TtsAudioFormat.MP3, TtsTarget.NOTE.format)
        assertNull(TtsTarget.NOTE.sampleRate)
        assertEquals(TtsAudioFormat.WAV, TtsTarget.CALL.format)
        assertEquals(24_000, TtsTarget.CALL.sampleRate)
        assertEquals(TtsAudioFormat.MP3, TtsTarget.NOTE.formatFor(TtsProvider.SILICONFLOW))
        assertEquals(TtsAudioFormat.MP3, TtsTarget.NOTE.formatFor(TtsProvider.CUSTOM))
        assertEquals(TtsAudioFormat.WAV, TtsTarget.NOTE.formatFor(TtsProvider.XIAOMI_MIMO))
        assertEquals(TtsAudioFormat.WAV, TtsTarget.CALL.formatFor(TtsProvider.SILICONFLOW))
        // 通话采样率必须等于播放轨道的采样率，否则每句话都白做一次重采样
        assertEquals(
            com.menghuan.app.realtime.CallAudioManager.OUT_RATE,
            TtsTarget.CALL.sampleRate,
        )
    }
}
