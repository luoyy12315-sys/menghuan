package com.menghuan.app

import com.menghuan.app.data.TtsAudioFormat
import com.menghuan.app.data.TtsClients
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.data.TtsProvider
import com.menghuan.app.data.VoiceRef
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * 三家 TTS 的**请求体组装与响应解析**（MockWebServer）。
 *
 * 为什么这批测试是这一版的关键防线：用户当下没有任何一家的 Key，真机出声验收做不了，
 * 而协议又处处是坑（预置音色漏了模型前缀会被判非法音色、MiMo 的目标文本必须放
 * `assistant` 而不是 `user`、错误体有时是 JSON 有时是裸串、200 里可能塞错误 JSON、
 * MiMo 回的是 base64 而不是二进制）。这些只能靠单测把线上形状钉死，
 * 协议逐条出处见 `docs/RESEARCH-v0.50.0-TTS协议查证.md`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun base() = server.url("/v1").toString()

    /** 会被容器嗅探认成 MP3 的假音频（ID3 头即可） */
    private fun fakeMp3(): ByteArray = ByteArrayOutputStream().apply {
        write("ID3".toByteArray())
        write(ByteArray(32))
    }.toByteArray()

    /** 会被认成 WAV 的假音频（RIFF....WAVE + 44 字节头） */
    private fun fakeWav(): ByteArray = ByteArrayOutputStream().apply {
        write("RIFF".toByteArray())
        write(ByteArray(4))
        write("WAVE".toByteArray())
        write(ByteArray(20))
    }.toByteArray()

    private fun audioResponse(bytes: ByteArray) = MockResponse().setBody(Buffer().write(bytes))

    private fun sfClient(key: String = "k") = TtsClients.from(
        TtsConfig(provider = TtsProvider.SILICONFLOW, baseUrl = base(), apiKey = key),
    )

    private fun mimoClient(key: String = "k") = TtsClients.from(
        TtsConfig(provider = TtsProvider.XIAOMI_MIMO, baseUrl = base(), apiKey = key),
    )

    // ===================== 硅基流动 =====================

    @Test
    fun `硅基 - 发 audio speech 且预置音色带模型前缀`() = runBlocking {
        server.enqueue(audioResponse(fakeMp3()))
        val client = sfClient()
        val audio = client.synthesize("今天过得怎么样", VoiceRef(TtsProvider.SILICONFLOW, "anna"))

        assertNotNull(audio)
        assertEquals(TtsAudioFormat.MP3, audio!!.format)
        val req = server.takeRequest()
        assertEquals("/v1/audio/speech", req.path)
        assertEquals("Bearer k", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", body.getString("model"))
        assertEquals("今天过得怎么样", body.getString("input"))
        // 官方硬要求：系统预置音色必须写成 "<model>:<alias>"
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:anna", body.getString("voice"))
        assertEquals("mp3", body.getString("response_format"))
        // 官方文档在 sample_rate 默认值上自相矛盾（44100 vs 32000）→ 一律显式传
        assertEquals(44100, body.getInt("sample_rate"))
        assertFalse(body.getBoolean("stream"))
    }

    @Test
    fun `硅基 - 克隆音色 uri 原样发不重复拼前缀`() = runBlocking {
        server.enqueue(audioResponse(fakeMp3()))
        val uri = "speech:myname:acct:voice"
        sfClient().synthesize("哈喽", VoiceRef(TtsProvider.SILICONFLOW, uri))
        assertEquals(uri, JSONObject(server.takeRequest().body.readUtf8()).getString("voice"))
    }

    @Test
    fun `硅基 - 裸字符串错误体也能把原因带出来`() = runBlocking {
        // 401/403/404 官方回的是裸字符串（"Invalid token"），不是 JSON
        server.enqueue(MockResponse().setResponseCode(401).setBody("Invalid token"))
        val client = sfClient("bad")
        assertNull(client.synthesize("喂", VoiceRef(TtsProvider.SILICONFLOW, "anna")))
        val why = client.lastFailure.orEmpty()
        assertTrue(why, why.contains("Invalid token") && why.contains("401"))
    }

    @Test
    fun `硅基 - 200 里塞 JSON 错误体不当成音频`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":20012,"message":"额度不足"}"""))
        val client = sfClient()
        assertNull(client.synthesize("喂", VoiceRef(TtsProvider.SILICONFLOW, "anna")))
        assertTrue(client.lastFailure.orEmpty(), client.lastFailure!!.contains("额度不足"))
    }

    @Test
    fun `硅基 - 空音频返回失败而不是空成功`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val client = sfClient()
        assertNull(client.synthesize("喂", VoiceRef(TtsProvider.SILICONFLOW, "anna")))
        assertNotNull(client.lastFailure)
    }

    // ===================== 自定义 OpenAI 兼容 =====================

    @Test
    fun `自定义端点 - 只发最小公约字段并带 Accept 头`() = runBlocking {
        server.enqueue(audioResponse(fakeMp3()))
        val client = TtsClients.from(
            TtsConfig(provider = TtsProvider.CUSTOM, baseUrl = base(), apiKey = "k", model = "tts-1"),
        )
        client.synthesize("你好", VoiceRef(TtsProvider.CUSTOM, "nova"))
        val req = server.takeRequest()
        assertEquals("/v1/audio/speech", req.path)
        assertEquals("application/octet-stream", req.getHeader("Accept"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("tts-1", body.getString("model"))
        assertEquals("nova", body.getString("voice"))
        // gain / sample_rate 是硅基私有、instructions 是 OpenAI 私有且 tts-1 不支持：
        // 自定义端点多塞一个字段就可能被严格网关 400，所以一个都不发
        listOf("gain", "sample_rate", "instructions", "stream").forEach {
            assertFalse("$it 不该出现在自定义端点请求里", body.has(it))
        }
    }

    @Test
    fun `自定义端点 - OpenAI 形状错误体取 error 里的 message`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"message":"model not found","type":"invalid_request_error"}}"""),
        )
        val client = TtsClients.from(
            TtsConfig(provider = TtsProvider.CUSTOM, baseUrl = base(), apiKey = "k", model = "x"),
        )
        assertNull(client.synthesize("你好", VoiceRef(TtsProvider.CUSTOM, "nova")))
        assertTrue(client.lastFailure.orEmpty(), client.lastFailure!!.contains("model not found"))
    }

    // ===================== 小米 MiMo =====================

    @Test
    fun `MiMo - 走 chat completions 且目标文本放 assistant`() = runBlocking {
        val b64 = android.util.Base64.encodeToString(fakeWav(), android.util.Base64.NO_WRAP)
        server.enqueue(
            MockResponse().setBody(
                JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put(
                            "message",
                            JSONObject().put("audio", JSONObject().put("id", "a1").put("data", b64)),
                        ),
                    ),
                ).toString(),
            ),
        )
        val client = mimoClient()
        val audio = client.synthesize("今天过得怎么样", VoiceRef(TtsProvider.XIAOMI_MIMO, "冰糖"))

        assertNotNull(audio)
        assertEquals(TtsAudioFormat.WAV, audio!!.format)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        // 官方首选用 api-key，FAQ 说 Authorization 也行 → 两个都发，别赌
        assertEquals("k", req.getHeader("api-key"))
        assertEquals("Bearer k", req.getHeader("Authorization"))

        val body = JSONObject(req.body.readUtf8())
        assertEquals("mimo-v2.5-tts", body.getString("model"))
        // **最容易写反的一条**：要念的文字必须在 assistant，放 user 里服务商不会念
        val messages = body.getJSONArray("messages")
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("今天过得怎么样", messages.getJSONObject(1).getString("content"))
        assertEquals("冰糖", body.getJSONObject("audio").getString("voice"))
        assertEquals("wav", body.getJSONObject("audio").getString("format"))
        // 官方 OpenAI 兼容参考全文没有 modalities，三个独立开源实现也都不传
        assertFalse(body.has("modalities"))
    }

    @Test
    fun `MiMo - 响应带 data URI 前缀时也照剥`() = runBlocking {
        val b64 = "data:audio/wav;base64," +
            android.util.Base64.encodeToString(fakeWav(), android.util.Base64.NO_WRAP)
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"audio":{"data":"$b64"}}}]}"""))
        val audio = mimoClient().synthesize("喂", VoiceRef(TtsProvider.XIAOMI_MIMO, "mimo_default"))
        assertEquals(TtsAudioFormat.WAV, audio?.format)
    }

    @Test
    fun `MiMo - 421 内容审核给出可行动的原因且不重试`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(421).setBody("{}"))
        val client = mimoClient()
        assertNull(client.synthesize("敏感内容", VoiceRef(TtsProvider.XIAOMI_MIMO, "冰糖")))
        assertTrue(client.lastFailure.orEmpty(), client.lastFailure!!.contains("内容审核"))
        assertEquals("421 只该发一次请求", 1, server.requestCount)
    }

    @Test
    fun `MiMo - 响应里没有音频时逐层报清楚`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{}}]}"""))
        val client = mimoClient()
        assertNull(client.synthesize("喂", VoiceRef(TtsProvider.XIAOMI_MIMO, "冰糖")))
        assertTrue(client.lastFailure.orEmpty(), client.lastFailure!!.contains("message.audio"))
    }

    // ===================== 通用契约 =====================

    @Test
    fun `没配 Key 时不发请求，直接给出原因`() = runBlocking {
        val client = TtsClients.from(TtsConfig(provider = TtsProvider.SILICONFLOW, baseUrl = base()))
        assertNull(client.synthesize("喂", VoiceRef(TtsProvider.SILICONFLOW, "anna")))
        assertEquals(0, server.requestCount)
        assertTrue(client.lastFailure.orEmpty(), client.lastFailure!!.contains("未配置"))
    }

    @Test
    fun `空文本不发请求`() = runBlocking {
        val client = sfClient()
        assertNull(client.synthesize("   ", VoiceRef(TtsProvider.SILICONFLOW, "anna")))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `上一次失败的原因在下次调用前被清掉`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        server.enqueue(audioResponse(fakeMp3()))
        val client = sfClient()
        assertNull(client.synthesize("喂", VoiceRef(TtsProvider.SILICONFLOW, "anna")))
        assertNotNull(client.lastFailure)
        assertNotNull(client.synthesize("喂", VoiceRef(TtsProvider.SILICONFLOW, "anna")))
        assertNull(client.lastFailure)
    }
}
