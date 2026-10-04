package com.menghuan.app

import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.AsrModelManager
import com.menghuan.app.data.SpeechToText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 语音转写的契约（v0.50.0 起只有一级：端侧 sherpa-onnx）。
 *
 * 云端 Whisper 与系统识别器两级兜底已删——它们在真机上从来没通过（服务商不支持
 * `/audio/transcriptions`；国产 ROM 的识别服务只监听实时麦克风、喂文件路径无效）。
 *
 * 这组测试守的是**失败必须安静**：模型没下载、native 库加载不了、文件为空，
 * 全都返回 null 让语音条照发，绝不能把异常抛进发送链。
 * Robolectric 下 `filesDir/asr_sensevoice/` 天然不存在，正好覆盖"模型未就绪"这条路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechToTextTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun ctx(): android.content.Context = ApplicationProvider.getApplicationContext()

    private fun audioFile(content: ByteArray = ByteArray(1024) { 1 }): File =
        tmp.newFile("voice.wav").apply { writeBytes(content) }

    @Test
    fun `模型未就绪时安静返回 null`() = runBlocking {
        assertFalse("测试环境不该有模型", AsrModelManager.isReady(ctx()))
        assertNull(SpeechToText.transcribe(ctx(), audioFile().absolutePath))
    }

    @Test
    fun `文件不存在时短路`() = runBlocking {
        assertNull(
            SpeechToText.transcribe(ctx(), File(tmp.root, "nope.wav").absolutePath),
        )
    }

    @Test
    fun `空文件时短路`() = runBlocking {
        assertNull(SpeechToText.transcribe(ctx(), audioFile(content = ByteArray(0)).absolutePath))
    }
}
