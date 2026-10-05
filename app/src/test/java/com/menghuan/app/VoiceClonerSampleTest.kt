package com.menghuan.app

import com.menghuan.app.data.VoiceCloner
import com.menghuan.app.data.WavPcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 音色克隆的样本裁剪 + 抗混叠重采样（v0.53.3）。
 *
 * 真机反馈："克隆的声音很奇怪、经常卡顿"，而**预置音色一切正常**、语音条与通话一样怪
 * ——把范围钉死在"参考音频本身"。两处都是我们自己把样本弄坏的：
 *   ① 从 48 kHz 用裸线性插值抽到 24 kHz，高于新奈奎斯特的成分折回可听频段 = 毛刺被烘进参考；
 *   ② 15 秒硬切常切在半句话上，参考音频与逐字稿对不上，零样本克隆就发抖变调。
 * 这两个都是纯函数，能在 JVM 里直接钉住。
 */
class VoiceClonerSampleTest {

    private fun tone(rate: Int, hz: Int, seconds: Double, amp: Double = 12_000.0): ShortArray {
        val n = (rate * seconds).toInt()
        val hzD = hz.toDouble()
        return ShortArray(n) { (sin(2 * PI * hzD * it / rate) * amp).toInt().toShort() }
    }

    private fun concat(parts: List<ShortArray>): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var off = 0
        parts.forEach { it.copyInto(out, off); off += it.size }
        return out
    }

    /** 能量谱：主峰应落在 [hz] 附近；用来判断混叠有没有发生 */
    private fun dominant(samples: FloatArray, rate: Int): Double {
        var bestHz = 0.0
        var best = 0.0
        var hz = 40.0
        while (hz <= rate / 2.0) {
            var re = 0.0
            var im = 0.0
            for (i in samples.indices) {
                val angle = 2 * PI * hz * i / rate
                re += samples[i] * cosOf(angle)
                im += samples[i] * sinOf(angle)
            }
            val mag = re * re + im * im
            if (mag > best) {
                best = mag
                bestHz = hz
            }
            hz += 20
        }
        return bestHz
    }

    private fun cosOf(a: Double) = kotlin.math.cos(a)

    private fun sinOf(a: Double) = sin(a)

    @Test
    fun `降采样把高于新奈奎斯特的成分压下去`() {
        // 48kHz 里塞一个 20kHz（> 24k 的一半 = 12k）的纯音：直接抽点会折回 4kHz 变成毛刺
        val hot = tone(48_000, 20_000, 0.5)
        val floats = FloatArray(hot.size) { hot[it] / 32768f }

        val naive = WavPcm.resampleLinear(floats, 48_000, 24_000)
        val filtered = WavPcm.resample(floats, 48_000, 24_000)

        val naiveEnergy = naive.sumOf { (it * it).toDouble() }
        val filteredEnergy = filtered.sumOf { (it * it).toDouble() }
        assertTrue(
            "低通之后 20kHz 成分应被大幅衰减（naive=$naiveEnergy filtered=$filteredEnergy）",
            filteredEnergy < naiveEnergy * 0.5,
        )
    }

    @Test
    fun `有用频段的幅度基本保住`() {
        val speech = tone(48_000, 1_000, 0.5)
        val floats = FloatArray(speech.size) { speech[it] / 32768f }
        val out = WavPcm.resample(floats, 48_000, 24_000)
        assertEquals(12_000f, out.size.toFloat(), 60f)
        assertTrue("1kHz 主峰位置不该被低通搬走", abs(dominant(out, 24_000) - 1_000) < 120)
        assertTrue(out.maxOf { abs(it) } > 0.2f)
    }

    @Test
    fun `裁剪跳过前导静音并在句子间隙下刀`() {
        val rate = 24_000
        val silence = ShortArray(rate)                       // 1 秒静音
        val speech = tone(rate, 220, 3.0, amp = 9_000.0)     // 3 秒"句子"
        val gap = ShortArray((rate * 0.4).toInt())           // 0.4 秒停顿
        val more = tone(rate, 220, 8.0, amp = 9_000.0)       // 后面还有一大段
        val samples = concat(listOf(silence, speech, gap, more))

        val out = VoiceCloner.trimToSpeech(samples, rate, VoiceCloner.MAX_SAMPLE_SECONDS)

        // 不该从静音开始：开头 100ms 里就该有说话能量
        val headLoud = out.take(2400).maxOf { abs(it.toInt()) }
        assertTrue("前导静音应被跳过（开头 100ms 最大能量 $headLoud）", headLoud > 500)
        // 不该长过上限
        assertTrue(out.size <= rate * VoiceCloner.MAX_SAMPLE_SECONDS)
        // 应该在第一个间隙处收尾（3 秒话 + 0.4 秒尾巴），而不是切满 10 秒把第二句切一半
        val seconds = out.size.toDouble() / rate
        assertTrue("应在句子间隙下刀，实际 $seconds 秒", seconds in 2.5..4.5)
    }

    @Test
    fun `整段没有停顿就退回上限`() {
        val rate = 24_000
        val nonStop = tone(rate, 220, 20.0, amp = 9_000.0)
        val out = VoiceCloner.trimToSpeech(nonStop, rate, VoiceCloner.MAX_SAMPLE_SECONDS)
        assertEquals(rate * VoiceCloner.MAX_SAMPLE_SECONDS, out.size)
    }

    @Test
    fun `短样本原样通过`() {
        val rate = 24_000
        val short = tone(rate, 220, 1.2, amp = 9_000.0)
        val out = VoiceCloner.trimToSpeech(short, rate, VoiceCloner.MAX_SAMPLE_SECONDS)
        assertEquals(short.size, out.size)
    }
}
