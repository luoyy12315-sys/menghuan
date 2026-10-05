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
    fun `立体声下混成单声道`() {
        // v0.53.0 起多声道取均值（通话要的是单声道，取首声道会丢一半能量）
        val out = WavPcm.parse(wav(16_000, 2, sampleValue = { 16384 }), 16_000)
        assertNotNull(out)
        assertEquals(1600, out!!.size)
        assertTrue("两声道同相，均值仍应是 ~0.5", out.all { it > 0.49f })
    }

    /**
     * v0.53.1 真机闪退的回归：流式导出的 WAV 会把 `data` 长度写成 0xFFFFFFFF（按 Int 读是 -1），
     * 而 chunk 遍历当时用**原始 size** 推进游标 → 游标倒退、几轮后变负 →
     * 拿负数去读 4 字节 chunk 名，抛 StringIndexOutOfBoundsException
     * （length=69294; regionStart=-82; regionLength=4）→ 冒到通话协程 = 整个 App 没了。
     */
    @Test
    fun `流式WAV的data长度为负也不许抛异常`() {
        val bytes = wav(24_000, 1)
        // 把 data 块的 size 改成 -1（0xFFFFFFFF）
        val idx = String(bytes, 0, bytes.size, Charsets.ISO_8859_1).indexOf("data")
        assertTrue(idx > 0)
        val broken = bytes.copyOf()
        writeLe32(broken, idx + 4, -1)
        val out = WavPcm.parse(broken, 24_000)
        assertNotNull("data 长度写错时应按「到文件尾」处理", out)
        assertEquals(2400, out!!.size)
    }

    /** 中间块 size 是垃圾（负数/超大）时：安静地返回 null，绝不抛 */
    @Test
    fun `垃圾块长度返回null而不是抛异常`() {
        val bytes = wav(16_000, 1)
        val evil = bytes.copyOf()
        writeLe32(evil, 16, -1_000_000)   // 第一个块（fmt）的 size 写成大负数
        assertNull(WavPcm.parse(evil, 16_000))
    }

    /** 硅基回的 WAV 是 IEEE float32：v0.52.0 只认 PCM16，于是通话报"服务商回的 WAV 解不开" */
    @Test
    fun `float32的WAV能解`() {
        val frames = 480
        val out = java.io.ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        val pcmSize = frames * 4
        ascii("RIFF"); le32(36 + pcmSize); ascii("WAVE")
        ascii("fmt "); le32(16)
        le16(3)          // WAVE_FORMAT_IEEE_FLOAT
        le16(1); le32(24_000); le32(24_000 * 4); le16(4); le16(32)
        ascii("data"); le32(pcmSize)
        for (f in 0 until frames) {
            val v = (sin(2 * PI * 440 * f / 24_000.0) * 0.5).toFloat()
            le32(java.lang.Float.floatToIntBits(v))
        }
        val parsed = WavPcm.parse(out.toByteArray(), 24_000)
        assertNotNull("float32 必须能解（否则通话没声）", parsed)
        assertEquals(frames, parsed!!.size)
        assertTrue(parsed.all { it in -1.01f..1.01f })
        assertTrue("幅度不该是静音", parsed.any { kotlin.math.abs(it) > 0.2f })
    }

    private fun writeLe32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
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
