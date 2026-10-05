package com.menghuan.app.realtime

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min

/**
 * 任意媒体文件 → 单声道 PCM16（v0.52.0 新增，取代已删除的旧 `Mp3Decoder`）。
 *
 * ## 为什么换
 * 旧实现是"裸 MP3 喂 MediaCodec"，并且把**输入** `MediaFormat` 硬写成 `24000Hz / 单声道`。
 * 硅基实际回的是 44.1kHz（文档：`sample_rate` 默认 32000，本 App 显式请求 44100）、
 * 用户自己的 MP3 更是五花八门，hint 与真实流不符时解码器直接吐 0 帧：
 * 通话报"声音合成失败：音频解码失败"、克隆报"文件格式不对"（两条真机反馈同一个根因）。
 *
 * `MediaExtractor` 会自己读容器的真实采样率与声道数，并把 MP3 的 ID3 头、Xing 头、
 * m4a/mp4 的 moov 都处理掉——**一次实现同时拿下 mp3 / wav / m4a / aac / mp4 / flac / ogg**，
 * 这也正是"用户想直接上传 mp4 当克隆样本"的答案：不需要写 MP3 编码器，抽音轨就够。
 *
 * 同步模式（不用异步回调）：调用点都在 IO 线程，代码量与状态机都小得多。
 */
object MediaAudioDecoder {

    /** 单声道 PCM16 + 真实采样率 */
    class Pcm(val samples: ShortArray, val sampleRate: Int)

    /** 空轨道保护：解 60 秒 48kHz 立体声约 57 万帧，320 万帧足够宽裕又挡得住死循环 */
    private const val MAX_SAMPLES = 3_200_000

    /** 输入输出同时拿不到缓冲区这么多毫秒就判定为卡死（真机上宁可失败，也不能吊住调用线程） */
    private const val STALL_BUDGET_MS = 5_000L

    /** @return 解码失败 / 文件里没有音轨 → null（调用方负责把原因说清楚） */
    fun decodeFile(file: File): Pcm? {
        if (!file.isFile || file.length() == 0L) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            // 有些容器不写采样率/声道（或写成 -1），读出来当缺省值用
            var rate = format.opt(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
            var channels = format.opt(MediaFormat.KEY_CHANNEL_COUNT) ?: 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.selectTrack(track)

            val info = MediaCodec.BufferInfo()
            val chunks = ArrayList<ShortArray>()
            var total = 0
            var stalled = 0L
            val startedAt = System.currentTimeMillis()
            var inputDone = false
            var outputDone = false

            decodeLoop@ while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buffer = codec.getInputBuffer(inIdx) ?: return null
                        val read = min(extractor.readSampleData(buffer, 0), buffer.capacity())
                        if (read < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        stalled = 0L
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                    in 0..Int.MAX_VALUE -> {
                        val buffer = codec.getOutputBuffer(outIdx)
                        if (info.size > 0 && buffer != null) {
                            val shorts = ShortArray(info.size / 2)
                            buffer.position(info.offset)
                            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                            // 立体声就地折叠：下游（AudioTrack / SenseVoice / 上传样本）一律单声道
                            val mono = if (channels > 1) downmix(shorts, channels) else shorts
                            chunks += mono
                            total += mono.size
                            if (total > MAX_SAMPLES) break@decodeLoop
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        stalled = 0L
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.opt(MediaFormat.KEY_SAMPLE_RATE) ?: rate
                        channels = f.opt(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        stalled += 10L
                        if (stalled > STALL_BUDGET_MS) return null
                    }
                    else -> Unit
                }
                if (System.currentTimeMillis() - startedAt > 30_000L) return null
            }
            if (total == 0) return null
            val flat = ShortArray(total)
            var offset = 0
            chunks.forEach { it.copyInto(flat, offset); offset += it.size }
            Pcm(flat, rate)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * 内存里的音频字节 → PCM。`MediaExtractor` 只认文件/fd，所以落到 [cacheDir] 的临时文件再解，
     * 解完立即删（通话链路每句都要调，不能攒垃圾）。
     */
    fun decodeBytes(bytes: ByteArray, cacheDir: File, prefix: String = "media"): Pcm? {
        if (bytes.isEmpty()) return null
        val tmp = File(cacheDir, "$prefix-${System.currentTimeMillis()}.bin")
        return runCatching {
            tmp.writeBytes(bytes)
            decodeFile(tmp)
        }.getOrNull().also { tmp.delete() }
    }

    /** 交织多声道 → 单声道（取均值，不丢能量） */
    private fun downmix(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        val out = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += interleaved[i * channels + c]
            out[i] = (sum / channels).toShort()
        }
        return out
    }

    /** 取不到 / 值为负都算没有（部分容器会写 -1） */
    private fun MediaFormat.opt(key: String): Int? =
        runCatching { getInteger(key) }.getOrNull()?.takeIf { it > 0 }
}
