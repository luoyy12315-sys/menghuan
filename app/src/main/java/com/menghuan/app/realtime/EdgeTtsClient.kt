package com.menghuan.app.realtime

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import com.menghuan.app.llm.NetworkStack
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Edge TTS 协议实现（微软 Edge "大声朗读" 免费端点，零 Key）。
 *
 * 协议参考成熟开源实现 `rany2/edge-tts`（Python 事实标准）与
 * `jing332/tts-server-android`（Kotlin，Android 端同类实现）移植：
 * - WSS 握手需 TrustedClientToken + Sec-MS-GEC（2024 年起微软校验的时钟令牌）；
 * - 连接后先发 speech.config，再发 SSML 请求（二进制帧 = 2 字节大端头长 + 头 + body）；
 * - 服务端回二进制帧（Path:audio = MP3 分片）与文本帧（Path:turn.end = 结束）。
 * 每句一次连接，简单可靠；非官方接口，失败由引擎层重试一次并降级为纯字幕。
 */
object EdgeTtsDrm {

    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private const val WIN_EPOCH_SECONDS = 11_644_473_600L
    private const val FIVE_MINUTES = 300L
    private const val TICKS_PER_SECOND = 10_000_000L

    /** SHA-256 → 大写十六进制（独立暴露便于 JVM 单测对标准测试向量） */
    fun sha256HexUpper(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }
            .uppercase(Locale.US)

    /**
     * Sec-MS-GEC：当前时刻（毫秒）对齐到 5 分钟边界 → Windows file time（100ns）
     * → 拼 TrustedClientToken → SHA256 大写。
     */
    fun secMsGec(nowMillis: Long): String {
        var ticks = nowMillis / 1000 + WIN_EPOCH_SECONDS
        ticks -= ticks % FIVE_MINUTES
        ticks *= TICKS_PER_SECOND
        return sha256HexUpper("$ticks$TRUSTED_CLIENT_TOKEN")
    }
}

class EdgeTtsClient(private val voice: String) {

    /** 最近一次合成失败的诊断信息（连接被拒/HTTP 码/异常类名），供上层与测试排查 */
    @Volatile
    var lastFailure: String? = null
        private set

    private val http: OkHttpClient = NetworkStack.baseBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 合成一句 → MP3 字节。失败（网络/协议/超时）返回 null，由调用方重试或降级。 */
    suspend fun synthesize(text: String): ByteArray? {
        val result = Channel<ByteArray?>(capacity = 1)
        val audio = ByteArrayOutputStream()
        var ws: WebSocket? = null

        val request = Request.Builder()
            .url(endpointUrl())
            .addHeader("Origin", ORIGIN)
            .addHeader("Pragma", "no-cache")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("User-Agent", USER_AGENT)
            // rany2/edge-tts 的 headers_with_muid：WSS 握手带随机 MUID cookie
            .addHeader("Cookie", "muid=${randomHex32()};")
            .build()

        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(speechConfigMessage())
                webSocket.send(ssmlRequestMessage(text))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains(HEADER_TURN_END)) {
                    result.trySend(audio.toByteArray())
                    webSocket.close(1000, null)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val audioChunk = extractAudio(bytes) ?: return
                synchronized(audio) { audio.write(audioChunk) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                lastFailure = "${t.javaClass.simpleName}: ${t.message ?: ""}" +
                    (response?.let { " http=${it.code}" } ?: "")
                result.trySend(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                lastFailure = "closed code=$code reason=$reason"
                result.trySend(null)
            }
        })

        return try {
            val r = withTimeoutOrNull(30_000L) { result.receive() }
            if (r == null && lastFailure == null) lastFailure = "timeout 30s"
            r
        } finally {
            runCatching { ws?.cancel() }
        }
    }

    // ---- 协议细节 ----

    private fun endpointUrl(): String =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=${EdgeTtsDrm.TRUSTED_CLIENT_TOKEN}" +
            "&ConnectionId=${randomHex32()}" +
            "&Sec-MS-GEC=${EdgeTtsDrm.secMsGec(System.currentTimeMillis())}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

    private fun timestamp(): String {
        // rany2/edge-tts 的 date_to_string 格式
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date())
    }

    private fun speechConfigMessage(): String =
        "X-Timestamp:${timestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""" +
            "\r\n"

    private fun ssml(text: String): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
            "<voice name='$voice'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>${xmlEscape(text)}</prosody></voice></speak>"

    private fun ssmlRequestMessage(text: String): String =
        "X-RequestId:${randomHex32()}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp()}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml(text)

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&apos;")

    /** uuid4().hex 风格：32 位小写 hex（X-RequestId 必须 32 位，短了服务端静默丢弃——v0.47.0 实测踩过） */
    private fun randomHex32(): String =
        (1..32).map { "0123456789abcdef"[(Math.random() * 16).toInt()] }.joinToString("")

    /** 二进制帧：前 2 字节大端头长，头里 Path:audio 的才是语音数据 */
    private fun extractAudio(bytes: ByteString): ByteArray? {
        if (bytes.size < 2) return null
        val headLen = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (bytes.size < 2 + headLen) return null
        val header = bytes.substring(2, 2 + headLen).utf8()
        if (!header.contains("Path:audio")) return null
        return bytes.substring(2 + headLen).toByteArray()
    }

    private companion object {
        const val HEADER_TURN_END = "Path:turn.end"
        // Edge 大声朗读的现行版本族（rany2/edge-tts constants.py，143.x）：
        // 版本号过老（130 时代）会被端点 403 拒绝——v0.47.0 实测踩过
        const val SEC_MS_GEC_VERSION = "1-143.0.3650.75"
        const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
    }
}
