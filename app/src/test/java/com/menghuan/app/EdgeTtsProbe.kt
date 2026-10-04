package com.menghuan.app

import com.menghuan.app.realtime.EdgeTtsDrm
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Ignore
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 完整协议 + 全帧打印的诊断探针（诊断工具，不进常态单测） */
@Ignore("依赖外网与微软端点，仅诊断用")
class EdgeTtsProbe {

    private fun timestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date())
    }

    private fun hex32(): String = (1..32).map { "0123456789abcdef"[(Math.random() * 16).toInt()] }.joinToString("")

    @Test
    fun `探查握手与帧`() {
        val latch = CountDownLatch(1)
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=${EdgeTtsDrm.TRUSTED_CLIENT_TOKEN}" +
            "&ConnectionId=${hex32()}" +
            "&Sec-MS-GEC=${EdgeTtsDrm.secMsGec(System.currentTimeMillis())}" +
            "&Sec-MS-GEC-Version=1-143.0.3650.75"
        val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
        val ws = http.newWebSocket(
            Request.Builder().url(url)
                .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .addHeader("Pragma", "no-cache")
                .addHeader("Cache-Control", "no-cache")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
                .addHeader("Cookie", "muid=${hex32()};")
                .build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    println("OPEN http=${response.code}")
                    webSocket.send(
                        "X-Timestamp:${timestamp()}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""" +
                            "\r\n",
                    )
                    webSocket.send(
                        "X-RequestId:${hex32()}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${timestamp()}Z\r\nPath:ssml\r\n\r\n" +
                            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'><voice name='zh-CN-XiaoxiaoNeural'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>你好，能听到我说话吗</prosody></voice></speak>",
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    println("TEXT(${text.length}): ${text.take(400).replace('\n', '|')}")
                    if (text.contains("Path:turn.end")) latch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    println("BIN(${bytes.size})")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    println("FAILURE: ${t.javaClass.name}: ${t.message} http=${response?.code}")
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    println("CLOSED code=$code reason=$reason")
                    latch.countDown()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    println("CLOSING code=$code reason=$reason")
                    webSocket.close(1000, null)
                }
            },
        )
        latch.await(30, TimeUnit.SECONDS)
        ws.cancel()
    }
}
