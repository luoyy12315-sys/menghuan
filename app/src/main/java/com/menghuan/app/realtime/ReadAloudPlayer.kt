package com.menghuan.app.realtime

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 聊天回复朗读的播放器（v0.49.0）：解码后的 PCM 非阻塞分块喂 AudioTrack，
 * 协程取消（关喇叭/发新消息/新回复）立即停播并释放。USAGE_MEDIA 走媒体音量。
 */
object ReadAloudPlayer {

    /** stillActive 由协程取消驱动：job.cancel() 时 currentCoroutineContext().isActive 立即变 false */
    suspend fun play(pcm: ShortArray, sampleRate: Int = CallAudioManager.OUT_RATE) =
        withContext(Dispatchers.IO) {
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
            try {
                if (track.state != AudioTrack.STATE_INITIALIZED) return@withContext
                track.play()
                var off = 0
                while (off < pcm.size && currentCoroutineContext().isActive) {
                    val w = track.write(pcm, off, min(8192, pcm.size - off), AudioTrack.WRITE_NON_BLOCKING)
                    if (w > 0) off += w else delay(8)
                }
                if (currentCoroutineContext().isActive) {
                    // 自然播完：等缓冲出尽再释放（非阻塞写完只代表进了缓冲）
                    val pending = (pcm.size - off) * 1000L / sampleRate
                    delay(pending + 80)
                }
            } finally {
                runCatching {
                    track.pause()
                    track.flush()
                    track.stop()
                    track.release()
                }
            }
        }
}
