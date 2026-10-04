package com.menghuan.app.realtime

import com.menghuan.app.data.TtsAudio
import com.menghuan.app.data.TtsAudioFormat
import com.menghuan.app.data.WavPcm

/**
 * TTS 产物 → AudioTrack 能直接吃的 PCM（v0.50.0）。
 *
 * ## 为什么需要这一层（以前不需要）
 * 旧的 Edge TTS **恒定**输出 24kHz 单声道 MP3，正好等于 [CallAudioManager.OUT_RATE]，
 * 所以引擎直接把 `Mp3Decoder` 的产物丢给 `playPcm` 就对了。
 * 换成三家可选的云端 TTS 之后这个巧合没了：硅基回 44.1kHz MP3、MiMo 默认回 WAV，
 * 声道数也不保证是 1。播放轨道的采样率与声道是建轨道时定死的，
 * **不重采样/不下混就会变速变调**（44.1kHz 的音频按 24kHz 播 = 慢近一倍、音调低下去）。
 *
 * 所以统一在这里归一化：解容器 → 下混单声道 → 线性重采样到 [outRate]。
 */
object TtsAudioDecoder {

    /** @return 单声道 PCM16 采样；解不出来（格式不认 / 数据损坏）返回 null，该句降级为纯字幕 */
    fun decode(audio: TtsAudio, outRate: Int): ShortArray? {
        val raw = when (audio.format) {
            // MP3：系统 MediaCodec 解码（与旧 Edge TTS 同一条路，已验证）
            TtsAudioFormat.MP3 ->
                Mp3Decoder.decode(audio.bytes)?.let { RawPcm(it.samples, it.sampleRate, it.channels) }
            // WAV：不用解码器，直接解 RIFF 头 + 重采样（复用录音链路那套已单测过的解析）
            TtsAudioFormat.WAV ->
                WavPcm.parse(audio.bytes, outRate)?.let { RawPcm(toShort(it), outRate, 1) }
        } ?: return null
        return normalize(raw, outRate)
    }

    private class RawPcm(val samples: ShortArray, val sampleRate: Int, val channels: Int)

    private fun normalize(raw: RawPcm, outRate: Int): ShortArray {
        var mono = raw.samples
        if (raw.channels > 1) mono = downmix(mono, raw.channels)
        if (raw.sampleRate == outRate) return mono

        val floats = FloatArray(mono.size) { mono[it] / 32768f }
        val resampled = WavPcm.resampleLinear(floats, raw.sampleRate, outRate)
        return toShort(resampled)
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

    private fun toShort(floats: FloatArray): ShortArray =
        ShortArray(floats.size) { (floats[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
}
