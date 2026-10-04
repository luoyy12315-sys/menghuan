package com.menghuan.app

import com.menghuan.app.data.WavPcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * [WavPcm] 纯解析逻辑（无 Android 框架依赖，纯 JVM）。
 *
 * 它是端侧识别的入口：sherpa 吃到的每一份采样都经过这里，
 * 解析错一格 = 整条语音白录。守住的契约：
 *   ① 标准 44 字节头能读（我们自己的录音格式）
 *   ② 带额外 chunk（LIST 等）也能读 —— chunk 遍历是为此才写的
 *   ③ 立体声取首声道、44.1k 自动重采样到 16k（AudioRecord 兜底采样率）
 *   ④ 坏数据返回 null 而不是抛异常（识别层靠 null 降级，不能被炸断）
 */
class WavPcmTest {

    /** 构造一段 PCM16 WAV：正弦波，[channels] 声道，[rate] 采样率，[seconds] 秒 */
    private fun wav(
        rate: Int,
        channels: Int,
        seconds: Double = 0.1,
        extraChunk: Boolean = false,
        sampleValue: ((Int) -> Short)? = null,
    ): ByteArray {
        val frames = (rate * seconds).toInt()
        val data = ByteArrayOutputStream()
        for (f in 0 until frames) {
            for (c in 0 until channels) {
                val v = sampleValue?.invoke(f)
                    ?: (sin(2 * PI * 440 * f / rate) * 16000).toInt().toShort()
                data.write(v.toInt() and 0xFF)
                data.write((v.toInt() shr 8) and 0xFF)
            }
        }
        val pcm = data.toByteArray()

        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

        ascii("RIFF"); le32(36 + pcm.size); ascii("WAVE")
        if (extraChunk) {           // LIST/INFO 扩展块（某些工具会塞）
            ascii("LIST"); le32(10); out.write(ByteArray(10))
        }
        ascii("fmt "); le32(16)
        le16(1); le16(channels); le32(rate); le32(rate * channels * 2); le16(channels * 2); le16(16)
        ascii("data"); le32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }

    @Test
    fun `标准16k单声道可解析且长度正确`() {
        val out = WavPcm.parse(wav(16_000, 1), 16_000)
        assertNotNull(out)
        assertEquals(1600, out!!.size)   // 0.1s @16k = 1600 采样
        // 幅度应落在 [-1, 1]
        assertTrue(out.all { it in -1.001f..1.001f })
    }

    @Test
    fun `带LIST扩展块也能找到data`() {
        val out = WavPcm.parse(wav(16_000, 1, extraChunk = true), 16_000)
        assertNotNull("chunk 遍历应跳过 LIST 找到 data", out)
        assertEquals(1600, out!!.size)
    }

    @Test
    fun `立体声取首声道`() {
        // 左声道恒 0.5，右声道恒 -0.5：取首声道应全为 ~0.5
        val out = WavPcm.parse(wav(16_000, 2, sampleValue = { 16384 }), 16_000)
        assertNotNull(out)
        assertEquals(1600, out!!.size)
        assertTrue("应取到左声道的 0.5", out.all { it > 0.49f })
    }

    @Test
    fun `44k1自动重采样到16k`() {
        val out = WavPcm.parse(wav(44_100, 1), 16_000)
        assertNotNull(out)
        // 0.1s → 1600 采样（±1 容差来自整数取整）
        assertEquals(1600f, out!!.size.toFloat(), 1.5f)
    }

    @Test
    fun `截断的data按实际可用字节解析`() {
        val full = wav(16_000, 1)
        val out = WavPcm.parse(full.copyOfRange(0, full.size - 37), 16_000)
        // data 头声称的长度比实际大 → 按 min 解析，不能抛异常
        assertNotNull(out)
        assertTrue(out!!.isNotEmpty())
    }

    @Test
    fun `垃圾输入返回null而不是抛异常`() {
        assertNull(WavPcm.parse(ByteArray(0), 16_000))
        assertNull(WavPcm.parse(ByteArray(64), 16_000))
        assertNull(WavPcm.parse("not a wav at all........".toByteArray(), 16_000))
    }

    @Test
    fun `重采样线性插值首尾不越界`() {
        val src = FloatArray(1000) { it / 1000f }
        val out = WavPcm.resampleLinear(src, 16_000, 8_000)
        assertEquals(500, out.size)
        assertEquals(src.first(), out.first(), 1e-5f)
        assertEquals(src.last(), out.last(), 1e-2f)
    }
}
