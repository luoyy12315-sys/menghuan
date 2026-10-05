package com.menghuan.app.data

import java.io.File
import java.io.RandomAccessFile

/**
 * WAV 解析 → 目标采样率单声道 FloatArray（sherpa-onnx 与通话播放轨道的输入格式）。
 *
 * 为什么自己写而不用 MediaCodec/MediaExtractor：
 *   ① 我们的录音是自己拼的 44 字节 WAV 头（见 [VoiceRecorder]），格式完全可控；
 *   ② MediaExtractor 解本地小文件纯属杀鸡用牛刀，还拿不到"纯 JVM 可测"的解析逻辑。
 *
 * **位深不挑**：PCM 8/16/24/32 与 IEEE float 32/64 都解，`WAVE_FORMAT_EXTENSIBLE`
 * 从 SubFormat GUID 里取真编码，声道数 >2 取均值下混。v0.52.0 只认 PCM16，
 * 结果硅基回的 float32 WAV 被判"解不开"、通话整场没声（真机反馈），所以这里一律按表头解释。
 *
 * 按 **chunk 遍历**而不是假定"头固定 44 字节"：某些实现在 WAV 头里塞 LIST/INFO 扩展块，
 * chunk 遍历照样能找到 data；反过来硬编码 44 会读歪。流式导出的 `data` 长度常是 0 或
 * 0xFFFFFFFF，这两种都按"到文件尾"处理。
 *
 * 非目标采样率走线性插值重采样（语音识别/播放场景线性插值完全够用，sherpa 内部还会再做特征提取）。
 */
object WavPcm {

    /** 解析 WAV 文件并重采样到 [targetRate]；格式不对/读不了返回 null */
    fun readMonoAt(file: File, targetRate: Int = 16_000): FloatArray? = try {
        RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray(raf.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            raf.readFully(bytes)
            parse(bytes, targetRate)
        }
    } catch (e: Exception) {
        null
    }

    /** 纯解析入口（无 IO，可单测）：失败返回 null */
    fun parse(bytes: ByteArray, targetRate: Int): FloatArray? {
        if (bytes.size < 12) return null
        if (!isAscii(bytes, 0, "RIFF") || !isAscii(bytes, 8, "WAVE")) return null

        var i = 12
        var format = 1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataStart = -1
        var dataLen = 0

        while (i in 0..(bytes.size - 8)) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val size = leInt(bytes, i + 4)
            val body = i + 8
            if (id == "data") {
                dataStart = body
                // 流式导出的 WAV 常把 data 长度写成 0 或 0xFFFFFFFF（当 Int 是负数）：
                // 这两种都按"到文件尾"处理，否则算出负帧数 → 整条音频被判解不开
                dataLen = if (size <= 0) bytes.size - body else minOf(size, bytes.size - body)
                break // data 就是载荷，后面没有我们要找的块
            }
            if (size <= 0) break
            if (id == "fmt " && body + 16 <= bytes.size) {
                format = leShort(bytes, body)
                channels = leShort(bytes, body + 2)
                sampleRate = leInt(bytes, body + 4)
                bits = leShort(bytes, body + 14)
                // WAVE_FORMAT_EXTENSIBLE(0xFFFE)：真正的编码在 SubFormat GUID 的前两字节
                if (format == 0xFFFE && body + 26 <= bytes.size) format = leShort(bytes, body + 22)
            }
            // chunk 按 2 字节对齐（奇数长度补一个 pad 字节）。
            // ⚠️ 游标必须**严格前进**：v0.53.1 真机闪退（StringIndexOutOfBoundsException
            // "length=69294; regionStart=-82; regionLength=4"）就是这里——原来直接用原始 size 推进，
            // 碰到 size 为负/超大的块时游标倒退，几轮之后变负，再拿负数去读 4 字节 chunk 名就炸。
            val next = body + size + (size and 1)
            if (next <= i) break
            i = next
        }

        if (dataStart < 0 || channels <= 0 || sampleRate <= 0) return null
        val bytesPerSample = bits / 8
        if (bytesPerSample <= 0) return null
        val frameBytes = bytesPerSample * channels
        val frames = dataLen / frameBytes
        if (frames <= 0) return null

        val mono = FloatArray(frames)
        var di = dataStart
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += sampleAt(bytes, di + c * bytesPerSample, format, bits)
            }
            mono[f] = sum / channels
            di += frameBytes
        }

        return when (sampleRate) {
            targetRate -> mono
            else -> resample(mono, sampleRate, targetRate)
        }
    }

    /**
     * 一个采样点 → [-1, 1]。云端 TTS 的 WAV 到底是哪种位深不由我们定：
     * 硅基回的是 IEEE float32，MiMo 是 PCM16，别家还有 PCM24 —— 只认 PCM16 的话
     * 就会把"能播的音频"报成"解不开"（v0.52.0 真机反馈的通话无声就是这个）。
     */
    private fun sampleAt(bytes: ByteArray, off: Int, format: Int, bits: Int): Float = when {
        format == 3 && bits == 32 -> leFloat(bytes, off)
        format == 3 && bits == 64 ->
            java.lang.Double.longBitsToDouble(leLong(bytes, off)).toFloat()
        bits == 8 -> ((bytes[off].toInt() and 0xFF) - 128) / 128f // 8 位是无符号偏移
        bits == 16 -> leSignedShort(bytes, off) / 32768f
        bits == 24 -> {
            val v = (bytes[off].toInt() and 0xFF) or
                ((bytes[off + 1].toInt() and 0xFF) shl 8) or
                ((bytes[off + 2].toInt() and 0xFF) shl 16)
            (if (v and 0x800000 != 0) v - 0x1000000 else v) / 8388608f
        }
        bits == 32 -> leInt(bytes, off) / 2147483648f
        else -> 0f
    }

    /**
     * 带**抗混叠**的重采样：降采样时先做低通再插值。
     *
     * [resampleLinear] 是裸线性插值——从 48 kHz 抽到 24 kHz 等于"每隔一个取一个"，
     * 高于新奈奎斯特（12 kHz）的成分会折回可听频段变成毛刺。音色克隆把样本重采样后
     * 当成参考音频上传，毛刺就被"烘"进参考里，真机表现是**克隆出来的声音发抖、变调**
     * （预置音色不卡，只有克隆卡，就是这个）。升采样不需要低通，直接插值。
     */
    fun resample(src: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate || src.isEmpty()) return src
        if (toRate > fromRate) return resampleLinear(src, fromRate, toRate)
        val window = (fromRate.toDouble() / toRate).toInt().coerceAtLeast(2)
        // 两遍滑动平均 = 三角核，滚降比一遍的方核干净得多，且不需要任何依赖
        return resampleLinear(movingAverage(movingAverage(src, window), window), fromRate, toRate)
    }

    /** 因果滑动平均（前缀和实现，O(n)），边界处用可用窗口，不补零以免炸音 */
    private fun movingAverage(src: FloatArray, window: Int): FloatArray {
        if (window <= 1 || src.isEmpty()) return src
        val out = FloatArray(src.size)
        var sum = 0f
        for (i in src.indices) {
            sum += src[i]
            if (i >= window) sum -= src[i - window]
            out[i] = sum / minOf(i + 1, window)
        }
        return out
    }

    /** 线性插值重采样（语音识别场景足够） */
    fun resampleLinear(src: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || src.isEmpty() || fromRate <= 0 || toRate <= 0) return src
        val ratio = fromRate.toDouble() / toRate
        val outLen = (src.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (j in out.indices) {
            val pos = j * ratio
            val i0 = pos.toInt().coerceAtMost(src.size - 1)
            val i1 = minOf(i0 + 1, src.size - 1)
            val frac = (pos - i0).toFloat()
            out[j] = src[i0] * (1f - frac) + src[i1] * frac
        }
        return out
    }

    /**
     * 单声道 PCM16 → 标准 44 字节头 WAV（[parse] 的逆操作，纯函数可单测）。
     * 音色克隆要把样本裁到 15 秒再上传，裁完得重新装成容器。
     */
    fun writeMono(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataLen = samples.size * 2
        val out = java.nio.ByteBuffer.allocate(44 + dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * 2 // 单声道 × 16bit
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + dataLen)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16)
        out.putShort(1) // PCM
        out.putShort(1) // 单声道
        out.putInt(sampleRate)
        out.putInt(byteRate)
        out.putShort(2) // 块对齐 = 声道 × 位深/8
        out.putShort(16)
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(dataLen)
        samples.forEach { out.putShort(it) }
        return out.array()
    }

    private fun isAscii(bytes: ByteArray, offset: Int, tag: String): Boolean {
        if (offset + tag.length > bytes.size) return false
        for (k in tag.indices) {
            if (bytes[offset + k] != tag[k].code.toByte()) return false
        }
        return true
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    /** 有符号 16 位：短整型的符号位在高字节，必须用 toShort 转一次 */
    private fun leSignedShort(b: ByteArray, off: Int): Short = leShort(b, off).toShort()

    private fun leFloat(b: ByteArray, off: Int): Float =
        java.lang.Float.intBitsToFloat(leInt(b, off))

    private fun leLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = v or ((b[off + k].toLong() and 0xFF) shl (8 * k))
        return v
    }
}
