package com.menghuan.app

import com.menghuan.app.data.TtsAudioFormat
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.data.TtsProvider
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

    @Test
    fun `硅基八个预置音色`() {
        assertEquals(8, TtsVoices.SILICONFLOW_PRESETS.size)
        assertEquals("anna", TtsVoices.defaultVoiceId(TtsProvider.SILICONFLOW))
        assertEquals(
            listOf("anna", "bella", "claire", "diana", "alex", "benjamin", "charles", "david"),
            TtsVoices.SILICONFLOW_PRESETS.map { it.id },
        )
    }

    @Test
    fun `MiMo 九个预置音色，中文 ID 就是中文本身`() {
        assertEquals(9, TtsVoices.MIMO_PRESETS.size)
        assertEquals("mimo_default", TtsVoices.defaultVoiceId(TtsProvider.XIAOMI_MIMO))
        assertTrue(TtsVoices.MIMO_PRESETS.any { it.id == "冰糖" })
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
}
