package com.menghuan.app.data

import java.io.File
import java.io.RandomAccessFile

/**
 * 极简 WAV(PCM16) 解析 → 目标采样率单声道 FloatArray（sherpa-onnx 的输入格式）。
 *
 * 为什么自己写而不用 MediaCodec/MediaExtractor：
 *   ① 我们的录音是自己拼的 44 字节 WAV 头（见 [VoiceRecorder]），格式完全可控；
 *   ② MediaExtractor 解本地小文件纯属杀鸡用牛刀，还拿不到"纯 JVM 可测"的解析逻辑。
 *
 * 按 **chunk 遍历**而不是假定"头固定 44 字节"：万一将来换采集器（或某些 ROM 在
 * WAV 头里塞 LIST/INFO 扩展块），chunk 遍历照样能找到 data；反过来硬编码 44 会读歪。
 *
 * 立体声取首声道（我们只录 mono，这里纯防御）；非目标采样率走线性插值重采样
 * （16k↔44.1k 的语音识别场景线性插值完全够用，sherpa 内部还会再做特征提取）。
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
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataStart = -1
        var dataLen = 0

        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val size = leInt(bytes, i + 4)
            val body = i + 8
            when (id) {
                "fmt " -> if (body + 16 <= bytes.size) {
                    channels = leShort(bytes, body + 2)
                    sampleRate = leInt(bytes, body + 4)
                    bits = leShort(bytes, body + 14)
                }
                "data" -> {
                    dataStart = body
                    dataLen = minOf(size, bytes.size - body)
                }
            }
            if (size <= 0) break
            // chunk 按 2 字节对齐（奇数长度补一个 pad 字节）
            i = body + size + (size and 1)
        }

        if (dataStart < 0 || channels !in 1..2 || sampleRate <= 0 || bits != 16) return null
        val frameBytes = 2 * channels
        val frames = dataLen / frameBytes
        if (frames <= 0) return null

        // PCM16 little-endian → float，立体声取首声道
        val mono = FloatArray(frames)
        var di = dataStart
        for (f in 0 until frames) {
            val lo = bytes[di].toInt() and 0xFF
            val hi = bytes[di + 1].toInt() // 符号位在这里，不 mask
            mono[f] = ((hi shl 8) or lo) / 32768f
            di += frameBytes
        }

        return when (sampleRate) {
            targetRate -> mono
            else -> resampleLinear(mono, sampleRate, targetRate)
        }
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
}
