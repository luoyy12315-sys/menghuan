package com.menghuan.app

import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.SpeechToText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 语音转写的**级联契约**（Robolectric：第 ② 级要碰 `SpeechRecognizer`，纯 JVM 拿不到框架实现）。
 *
 * v0.43.1 用户实测「语音还是识别不了」后重做成三级级联，这一组测试守的就是
 * "哪一级在什么时候生效、失败时怎么降级、什么情况短路"：
 *   ① 云端 Whisper（主力）
 *   ② 系统 SpeechRecognizer（兜底）
 *   ③ 都失败 → null（语音照发，内容为空，模型不会瞎猜）
 *
 * ⚠️ 第 ② 级在 Robolectric 下 `isRecognitionAvailable()` 恒为 false（没有识别服务），
 * 于是它会**安静地给出 null** —— 这正好就是我们要断言的"降级终点"。
 * 第 ② 级在真机上的行为**没法用 JVM 测试守**，这正是本项目把它降为"兜底"、
 * 把云端转写提为主力的原因。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechToTextTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun audioFile(content: ByteArray = ByteArray(1024) { 1 }): File =
        tmp.newFile("voice.wav").apply { writeBytes(content) }

    // 传一个真实（Robolectric 提供的）Context 即可；第 ② 级会在
    // `isRecognitionAvailable()` 处返回 false 从而安静跳过。
    private fun ctx(): android.content.Context =
        ApplicationProvider.getApplicationContext()

    /**
     * 云端成功：直接返回云端结果，**整条链到此结束**。
     * 这是最关键的一条 —— 云端可用时系统识别器根本不该被碰。
     */
    @Test
    fun `云端转写成功时直接返回`() = runBlocking {
        val file = audioFile()
        var cloudCalls = 0
        val text = SpeechToText.transcribe(
            context = ctx(),
            audioPath = file.absolutePath,
            cloudTranscribe = { cloudCalls++; "云端结果" },
        )
        assertEquals("云端结果", text)
        assertEquals(1, cloudCalls)
    }

    /** 云端返回 null（服务商不支持 / 网络失败）→ 继续降级，最终安静给出 null */
    @Test
    fun `云端失败时继续降级到系统识别`() = runBlocking {
        val file = audioFile()
        var cloudCalls = 0
        val text = SpeechToText.transcribe(
            context = ctx(),
            audioPath = file.absolutePath,
            cloudTranscribe = { cloudCalls++; null },
        )
        assertEquals("云端应被调用过一次", 1, cloudCalls)
        assertNull("系统级在 JVM 下不可用，应安静返回 null", text)
    }

    /** 云端抛异常也必须被吞掉并降级 —— 抛出去会把整条语音发送链炸断 */
    @Test
    fun `云端抛异常时安静降级`() = runBlocking {
        val file = audioFile()
        val text = SpeechToText.transcribe(
            context = ctx(),
            audioPath = file.absolutePath,
            cloudTranscribe = { error("模拟网络异常") },
        )
        assertNull(text)
    }

    /** 没注入云端转写（用户压根没配 API）→ 直接走系统级，不崩溃 */
    @Test
    fun `未提供云端转写时不崩溃`() = runBlocking {
        val file = audioFile()
        assertNull(
            SpeechToText.transcribe(
                context = ctx(),
                audioPath = file.absolutePath,
                cloudTranscribe = null,
            )
        )
    }

    /** 文件不存在：任何一级都不该被调用（省一次网络往返） */
    @Test
    fun `文件不存在时不触发任何转写`() = runBlocking {
        var cloudCalls = 0
        val text = SpeechToText.transcribe(
            context = ctx(),
            audioPath = File(tmp.root, "nope.wav").absolutePath,
            cloudTranscribe = { cloudCalls++; "不该出现" },
        )
        assertNull(text)
        assertEquals("文件不存在应短路，不发请求", 0, cloudCalls)
    }

    /** 空文件同理：短路，不发请求 */
    @Test
    fun `空文件不触发任何转写`() = runBlocking {
        val file = audioFile(content = ByteArray(0))
        var cloudCalls = 0
        val text = SpeechToText.transcribe(
            context = ctx(),
            audioPath = file.absolutePath,
            cloudTranscribe = { cloudCalls++; "不该出现" },
        )
        assertNull(text)
        assertEquals("空文件应短路，不发请求", 0, cloudCalls)
    }

    /** 云端返回空白串视为失败（避免把 "" 当有效转写落库、让模型以为用户没说话） */
    @Test
    fun `云端返回空白串视为失败`() = runBlocking {
        val file = audioFile()
        var cloudCalls = 0
        val text = SpeechToText.transcribe(
            context = ctx(),
            audioPath = file.absolutePath,
            cloudTranscribe = { cloudCalls++; "   " },
        )
        assertNull(text)
        assertEquals(1, cloudCalls)
    }
}
