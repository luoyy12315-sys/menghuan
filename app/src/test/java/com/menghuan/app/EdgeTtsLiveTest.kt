package com.menghuan.app

import com.menghuan.app.realtime.EdgeTtsClient
import com.menghuan.app.realtime.Mp3Decoder
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Ignore
import org.junit.Test

/**
 * Edge TTS 真实网络诊断（v0.47.0 排查"通话没声音"）：直打微软端点验证协议与网络，
 * JVM 环境可跑（OkHttp 纯 JVM）。**此测试依赖外网连通性，常态 @Ignore；
 * 要诊断时临时去掉注解单跑：`gradle testDebugUnitTest --tests EdgeTtsLiveTest`。**
 */
@Ignore("依赖外网与微软端点，仅诊断用")
class EdgeTtsLiveTest {

    @Test
    fun `合成一句真实音频`() = kotlinx.coroutines.runBlocking {
        val client = EdgeTtsClient("zh-CN-XiaoxiaoNeural")
        val mp3 = client.synthesize("你好，能听到我说话吗")
        if (mp3 == null) println("lastFailure = ${client.lastFailure}")
        println("mp3 bytes = ${mp3?.size}")
        assertNotNull("synthesize 返回 null——${client.lastFailure ?: "未知原因"}", mp3)
        println("mp3 bytes = ${mp3?.size}")
        assertTrue("音频太小（${mp3?.size}B），合成不完整", (mp3?.size ?: 0) > 2000)
        // MP3 帧头：0xFFEx / 0xFFFx 同步字
        val head = mp3?.get(0)?.toInt()?.and(0xFF) ?: 0
        val head2 = mp3?.get(1)?.toInt()?.and(0xFF) ?: 0
        println("magic = ${"%02x".format(head)}${"%02x".format(head2)}")
        assertTrue("不是 MP3 同步字", head == 0xFF && (head2 and 0xE0) == 0xE0)
    }
}
