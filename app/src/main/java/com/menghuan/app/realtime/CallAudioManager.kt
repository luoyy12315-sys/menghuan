package com.menghuan.app.realtime

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder

/**
 * 通话音频通道（引擎专用，与语音消息链路的 VoiceRecorder / SpeechToText 完全独立，
 * 任务书 §八：两套系统互不破坏）。
 *
 * - 进：AudioRecord，**VOICE_COMMUNICATION 源** = 系统级回声消除/降噪/自动增益
 *   （任务书 §二十四，真机 AEC 是"AI 不打断自己"的第一道防线）；
 * - 出：AudioTrack（VOICE_COMMUNICATION 用途 + SPEECH 内容类型，24kHz mono），
 *   单一播放链路（任务书 §二十五）——没有第二个播放器；
 * - AudioManager 进 MODE_IN_COMMUNICATION + 外放，[close] 恢复进入前状态。
 */
class CallAudioManager {

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null

    @SuppressLint("MissingPermission") // 麦克风权限由 UI 层在启动前申请并检查
    fun start(context: Context): Boolean = runCatching {
        // **不再设 MODE_IN_COMMUNICATION / speakerphone**（v0.48.0）：通话模式下部分 ROM
        // 把媒体音频路由进无声/听筒通道，真机表现即"AI 说话没声音"——那是硬伤；
        // 回声消除仍由麦克风侧 VOICE_COMMUNICATION 源承担，播放走普通媒体通道保证出声。
        val minBuf = AudioRecord.getMinBufferSize(
            IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            IN_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf * 2, IN_RATE), // ≥ 0.5s，网络卡顿时不丢帧
        )
        if (record?.state != AudioRecord.STATE_INITIALIZED) return false
        record?.startRecording()
        record?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }.getOrDefault(false)

    /** 读一个 512 采样窗口（32ms @16kHz）。未在录/未初始化返回 null。 */
    fun readChunk(): ShortArray? {
        val r = record ?: return null
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null
        val out = ShortArray(CallVad.WINDOW)
        val n = r.read(out, 0, out.size)
        return if (n == out.size) out else null
    }

    /** 建播放轨道并启动（每轮 AI 回复开始出声前调用）。
     *  用途 = USAGE_MEDIA（走媒体音量，用户日常听得见）而非 VOICE_COMMUNICATION——
     *  通话音量（STREAM_VOICE_CALL）经常被用户调到 0，真机上表现为"没声音"；
     *  回声消除仍由麦克风侧 VOICE_COMMUNICATION 源 + MODE_IN_COMMUNICATION 提供。 */
    fun playStart(): Boolean = runCatching {
        val t = track ?: AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(OUT_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        ).also { track = it }
        if (t.state != AudioTrack.STATE_INITIALIZED) return false
        t.play()
        true
    }.getOrDefault(false)

    /** 非阻塞分块写（打断时协程可退出；阻塞写无法被 cancel 中断） */
    fun playPcm(pcm: ShortArray, stillActive: () -> Boolean): Boolean {
        val t = track ?: return false
        var off = 0
        while (off < pcm.size && stillActive()) {
            val w = t.write(pcm, off, pcm.size - off, AudioTrack.WRITE_NON_BLOCKING)
            if (w > 0) {
                off += w
            } else {
                System.err // no-op
                Thread.sleep(8)
            }
        }
        return off >= pcm.size
    }

    /** 停止播放并丢弃未播数据（打断 / 每轮收尾） */
    fun stopPlayback() {
        runCatching {
            track?.pause()
            track?.flush()
        }
    }

    /** 释放全部音频资源（v0.48.0 起不再改 AudioManager 模式，无需恢复） */
    fun close(context: Context) {
        runCatching {
            record?.stop()
            record?.release()
            track?.stop()
            track?.release()
        }
        record = null
        track = null
    }

    companion object {
        const val IN_RATE = 16_000

        /** 播放轨道采样率 = TTS 通话目标采样率（同一个常量，两边不一致就会每句多做一次重采样） */
        const val OUT_RATE = com.menghuan.app.data.TTS_CALL_SAMPLE_RATE
    }
}
