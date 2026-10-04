package com.menghuan.app

import com.menghuan.app.data.AsrModelFile
import com.menghuan.app.data.AsrModelRules
import com.menghuan.app.data.AsrModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型下载的纯逻辑（续传偏移、Range 头、sha256、进度换算、下载源拼 URL）。
 *
 * 真机上没法用 JVM 测 228MB 的实际下载，但这几处算错会让用户下完发现文件是坏的、
 * 或者续传从错误偏移拼出脏数据——所以逐个钉住。
 */
class AsrModelManagerTest {

    @Test
    fun `没有分片时从头下载`() {
        assertEquals(AsrModelRules.Resume.FromScratch, AsrModelRules.resumeDecision(0L, 1000L))
        assertEquals(AsrModelRules.Resume.FromScratch, AsrModelRules.resumeDecision(-5L, 1000L))
    }

    @Test
    fun `有分片时从分片末尾续传`() {
        assertEquals(AsrModelRules.Resume.From(400L), AsrModelRules.resumeDecision(400L, 1000L))
    }

    @Test
    fun `分片已经不小于目标大小时视为完成`() {
        assertEquals(AsrModelRules.Resume.AlreadyComplete, AsrModelRules.resumeDecision(1000L, 1000L))
        // 比目标还大 = 上一次写崩留下的脏数据，绝不能当"已完成"直接用
        assertEquals(AsrModelRules.Resume.AlreadyComplete, AsrModelRules.resumeDecision(1500L, 1000L))
    }

    @Test
    fun `偏移为 0 时不发 Range 头`() {
        assertNull(AsrModelRules.rangeHeader(0L))
        assertEquals("bytes=400-", AsrModelRules.rangeHeader(400L))
    }

    @Test
    fun `sha256 对标准测试向量正确`() {
        val hex = "abc".byteInputStream().use { AsrModelRules.sha256Hex(it) }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }

    @Test
    fun `sha256 空输入也是标准值`() {
        val hex = ByteArray(0).inputStream().use { AsrModelRules.sha256Hex(it) }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex)
    }

    @Test
    fun `进度换算不越界也不除零`() {
        assertEquals(0f, AsrModelRules.fraction(0L, 0L), 0f)
        assertEquals(0f, AsrModelRules.fraction(0L, 1000L), 1e-6f)
        assertEquals(0.5f, AsrModelRules.fraction(500L, 1000L), 1e-6f)
        assertEquals(1f, AsrModelRules.fraction(1500L, 1000L), 1e-6f)
    }

    @Test
    fun `体积文案按量级换单位`() {
        assertEquals("512 B", AsrModelRules.humanSize(512L))
        assertEquals("1.0 KB", AsrModelRules.humanSize(1024L))
        assertEquals("228.2 MB", AsrModelRules.humanSize(AsrModelFile.MODEL.sizeBytes))
        assertEquals("1.00 GB", AsrModelRules.humanSize(1_073_741_824L))
    }

    @Test
    fun `两个下载源都指向各自仓库里的同名文件`() {
        for (source in AsrModelSource.entries) {
            for (file in AsrModelFile.entries) {
                val url = source.urlOf(file)
                assertTrue("$url 应带上文件名", url.contains(file.fileName))
                assertTrue("$url 应是 https", url.startsWith("https://"))
            }
        }
        assertEquals(
            "https://www.modelscope.cn/api/v1/models/" +
                "pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/repo" +
                "?Revision=master&FilePath=tokens.txt",
            AsrModelSource.MODELSCOPE.urlOf(AsrModelFile.TOKENS),
        )
    }

    @Test
    fun `模型清单里的校验值形如 64 位十六进制`() {
        AsrModelFile.entries.forEach { file ->
            assertEquals("${file.fileName} sha256 长度", 64, file.sha256.length)
            assertTrue("${file.fileName} sha256 应是十六进制", file.sha256.all { it in "0123456789abcdef" })
            assertTrue("${file.fileName} 大小应为正", file.sizeBytes > 0L)
        }
    }
}
