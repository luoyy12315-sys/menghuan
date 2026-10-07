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

    /**
     * AI 播放电平回调（v0.58.0，视频通话的口型驱动源）。在 [playPcm] 所在线程（IO）被调用。
     *
     * 报出去的时机刻意**跟着 AudioTrack 的真实播放头**走，而不是"写进去就算"：
     * 非阻塞写会先把轨道缓冲灌满，那一段几千个采样几乎瞬间写完，
     * 按写入速度报电平的话角色会比声音先张嘴半秒——正是方案文档 §七
     * "网络事件与真正播放状态存在明显错位"点名的那个错。
     * 所以每块电平先排队，等播放头真的推进到那块之后才报。
     */
    var levelListener: ((Float) -> Unit)? = null

    /** 待报电平（写入顺序）与它们各自的"写满后的绝对采样位置" */
    private val pendingLevels = ArrayDeque<Float>()
    private val pendingEnds = ArrayDeque<Int>()
    private var writtenTotal = 0
    private var playheadBase = 0

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
        // 播放头基准：每一轮 AI 开口都从这一刻重新对齐（[stopPlayback] 之后缓冲会被丢弃，
        // 不同 ROM 对 flush 是否归零播放头的行为不一致，所以每次都重取基准而不是假设它连续）
        playheadBase = runCatching { t.playbackHeadPosition }.getOrDefault(0)
        writtenTotal = 0
        synchronized(pendingLevels) {
            pendingLevels.clear()
            pendingEnds.clear()
        }
        true
    }.getOrDefault(false)

    /**
     * 分块写（打断时协程可退出；阻塞写无法被 cancel 中断）。
     *
     * 每块 [LEVEL_BLOCK] 采样（@24kHz ≈ 21ms），写完一块算一块电平排队，
     * 再按播放头把已经真正播出去的那些报给 [levelListener]。
     */
    fun playPcm(pcm: ShortArray, stillActive: () -> Boolean): Boolean {
        val t = track ?: return false
        var off = 0
        while (off < pcm.size && stillActive()) {
            val end = minOf(off + LEVEL_BLOCK, pcm.size)
            var written = off
            while (written < end && stillActive()) {
                val w = t.write(pcm, written, end - written, AudioTrack.WRITE_NON_BLOCKING)
                if (w > 0) {
                    written += w
                } else {
                    System.err // no-op
                    Thread.sleep(8)
                }
            }
            if (written < end) {
                // 被打断：这一块的电平不用报，队列也直接作废
                off = written
                break
            }
            writtenTotal += end - off
            synchronized(pendingLevels) {
                pendingLevels.addLast(CallAudioLevel.rms(pcm, off, end))
                pendingEnds.addLast(writtenTotal)
            }
            off = end
            drainLevels(t)
        }
        return off >= pcm.size
    }

    /** 播放头推进到哪儿，就报哪儿的电平；队列积压超上限说明播放头没动，放行避免口型冻住 */
    private fun drainLevels(t: AudioTrack) {
        val listener = levelListener ?: return
        val played = runCatching { t.playbackHeadPosition - playheadBase }.getOrDefault(Int.MAX_VALUE)
        while (true) {
            val level: Float
            synchronized(pendingLevels) {
                if (pendingLevels.isEmpty()) return
                val stuck = pendingLevels.size > LEVEL_BACKLOG_LIMIT
                if (!stuck && (pendingEnds.first() > played)) return
                pendingEnds.removeFirst()
                level = pendingLevels.removeFirst()
            }
            listener(CallAudioLevel.toMouth(level))
        }
    }

    /** 停止播放并丢弃未播数据（打断 / 每轮收尾）。⚠️ 同时把口型电平归零 */
    fun stopPlayback() {
        synchronized(pendingLevels) {
            pendingLevels.clear()
            pendingEnds.clear()
        }
        // 用户打断后口型必须立刻闭上（方案文档 §七）
        runCatching { levelListener?.invoke(0f) }
        runCatching {
            track?.pause()
            track?.flush()
        }
    }

    /** 释放全部音频资源（v0.48.0 起不再改 AudioManager 模式，无需恢复） */
    fun close(context: Context) {
        levelListener = null
        synchronized(pendingLevels) {
            pendingLevels.clear()
            pendingEnds.clear()
        }
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

        /** 电平块大小：512 采样 @24kHz ≈ 21ms，和 VAD 窗口同一量级，口型不会看着发钝 */
        private const val LEVEL_BLOCK = 512

        /** 待报队列上限（块数）：约 1 秒。超过说明播放头没在推进 */
        private const val LEVEL_BACKLOG_LIMIT = 48
    }
}
