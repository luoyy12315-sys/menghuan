package com.menghuan.app.realtime

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteOrder

/** 解码产物：16bit PCM + 实际采样率/声道（由解码器输出格式回报） */
class DecodedPcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

/**
 * 裸 MP3 → PCM（系统 MediaCodec，同步模式）。
 * Edge TTS 输出 audio-24khz-48kbitrate-mono-mp3，走系统解码而不是赌 raw-PCM
 * 输出格式是否被端点接受。解码失败返回 null（该句降级为纯字幕，不炸通话）。
 */
object Mp3Decoder {

    fun decode(mp3: ByteArray): DecodedPcm? {
        if (mp3.isEmpty()) return null
        return runCatching {
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_MPEG, 24_000, 1)
                codec.configure(format, null, null, 0)
                codec.start()

                var sampleRate = 24_000
                var channels = 1
                val chunks = ArrayList<ShortArray>()
                var total = 0
                val info = MediaCodec.BufferInfo()
                var inOffset = 0
                var eos = false

                while (true) {
                    if (!eos) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val ib = codec.getInputBuffer(inIdx)!!
                            val n = minOf(ib.remaining(), mp3.size - inOffset)
                            ib.put(mp3, inOffset, n)
                            inOffset += n
                            eos = inOffset >= mp3.size
                            codec.queueInputBuffer(
                                inIdx, 0, n, 0L,
                                if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                            )
                        }
                    }
                    when (val outIdx = codec.dequeueOutputBuffer(info, 10_000)) {
                        in 0..Int.MAX_VALUE -> {
                            val ob = codec.getOutputBuffer(outIdx)!!
                            val shorts = ShortArray(ob.remaining() / 2)
                            ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                            if (shorts.isNotEmpty()) {
                                chunks += shorts
                                total += shorts.size
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                    }
                }

                if (total == 0) return@runCatching null
                val flat = ShortArray(total)
                var off = 0
                chunks.forEach { it.copyInto(flat, off); off += it.size }
                // 立体声意外出现时折叠成单声道（播放链固定 mono）
                val samples = if (channels >= 2) {
                    ShortArray(total / channels).also { out ->
                        for (i in out.indices) {
                            var sum = 0
                            for (c in 0 until channels) sum += flat[i * channels + c]
                            out[i] = (sum / channels).toShort()
                        }
                    }
                } else flat
                DecodedPcm(samples, sampleRate, 1)
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        }.getOrNull()
    }
}
