package com.menghuan.app.realtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * 通话提示音（v0.47.1）：代码合成的柔和音（正弦 + 淡入淡出包络），直接喂 AudioTrack
 * （USAGE_MEDIA，走媒体音量），播完自动释放，不依赖调用方生命周期。
 * CONNECT/HANG_UP：通话接通（上行双音）/挂断（下行双音）。
 */
object CallTones {

    enum class Kind { CONNECT, HANG_UP }

    private const val SAMPLE_RATE = 44_100
    private const val AMPLITUDE = 0.32 // 满幅的 1/3，柔和不炸耳
    private const val FADE_IN_S = 0.012
    private const val FADE_OUT_S = 0.09

    fun play(kind: Kind) {
        val (f1, f2) = when (kind) {
            // 接通：上行双音（C5 → G5，明亮"打通了"）
            Kind.CONNECT -> 523 to 784
            // 挂断：下行双音（E5 → A4，缓落"结束了"）
            Kind.HANG_UP -> 659 to 440
        }
        val pcm = dualTone(f1, f2)
        val durationMs = (pcm.size * 1000L / SAMPLE_RATE).toLong()
        Thread {
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                pcm.size * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            runCatching {
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(durationMs + 200)
            }
            runCatching { track.release() }
        }.apply {
            isDaemon = true
            name = "call-tone"
        }.start()
    }

    /** 两段正弦（中间留 45ms 间隙），每段带淡入/淡出包络——避免硬起止的"咔哒"感 */
    private fun dualTone(f1: Int, f2: Int): ShortArray {
        val seg1 = (SAMPLE_RATE * 0.17).toInt()
        val seg2 = (SAMPLE_RATE * 0.26).toInt()
        val gap = (SAMPLE_RATE * 0.045).toInt()
        val out = ShortArray(seg1 + gap + seg2)
        fillTone(out, 0, seg1, f1)
        fillTone(out, seg1 + gap, seg2, f2)
        return out
    }

    private fun fillTone(out: ShortArray, offset: Int, length: Int, freq: Int) {
        val fadeIn = (SAMPLE_RATE * FADE_IN_S).toInt()
        val fadeOut = (SAMPLE_RATE * FADE_OUT_S).toInt()
        for (i in 0 until length) {
            val t = i.toDouble() / SAMPLE_RATE
            val envelope = when {
                i < fadeIn -> i.toDouble() / fadeIn
                i >= length - fadeOut -> (length - i).toDouble() / fadeOut
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            val sample = AMPLITUDE * envelope * sin(2 * PI * freq * t)
            out[offset + i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
