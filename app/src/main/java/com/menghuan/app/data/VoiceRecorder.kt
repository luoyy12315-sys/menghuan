package com.menghuan.app.data

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 语音录制：按住说话 → 录到应用私有目录 filesDir/voice/ → 交给识别。
 *
 * ## v0.44.0：采集层换成 `AudioRecord` 直采 PCM（套用端侧 ASR 开源项目的标准做法）
 *
 * 旧实现用 `MediaRecorder`，为了喂系统识别器要输出 WAV，而
 * `MediaRecorder.OutputFormat.WAV` 在公开 SDK 里根本没有这个常量（只能写魔法数 6），
 * 且 `format + encoder` 的组合在各家 ROM 上合法性不一致 —— 小米上 `prepare()` 直接抛
 * `IllegalStateException`（用户实测："按住说话"弹"录音启动失败"）。
 * 于是当时被迫加了一条 "WAV → AAC(.m4a)" 的回退链，代价是**录出来可能是 m4a**，
 * 而系统识别器只吃 PCM → 转写恒为空。
 *
 * 端侧 ASR 生态里这个问题早有定论：
 * - `alphacep/vosk-android-demo`（母项目 `alphacep/vosk-api` 15.2k star，2026 年仍在更新）
 * - `k2-fsa/sherpa-onnx`（14.3k star，2026 年仍在更新，Android 官方 sample 在
 *   `sherpa-onnx/android/…/SpeakerId.kt` 等处）
 * - `ggml-org/whisper.cpp`（51k star，Android 示例 `examples/whisper.android`）
 *
 * **三家的 Android 采集层实现完全一致**：`AudioRecord` 直采
 * `16 kHz / MONO / ENCODING_PCM_16BIT`，自己拼 44 字节 WAV 头落盘。
 * 好处很直接：
 *   ① 不碰 MediaRecorder 的 format/encoder 组合表，**不存在 ROM 兼容性问题**；
 *   ② 输出一定是标准 16bit PCM WAV —— 云端 Whisper（`/audio/transcriptions`）、
 *      系统识别器、以及将来要接的本地离线引擎都能直接吃；
 *   ③ 体积与原来一致（16kHz 单声道 ≈ 32KB/s，1 分钟 ≈ 1.9MB）。
 *
 * 对外 API 与旧版**完全一致**（`start` / `stop` / `cancel` / `lastError` / `isRecording` /
 * `voiceDir` / `formatDuration`），ChatScreen 不需要改一行。
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: AudioRecord? = null
    private var outputFile: File? = null
    private var startedAt = 0L
    private var pumpThread: Thread? = null
    @Volatile private var pumping = false

    /**
     * 最近一次 [start] 失败的原因（成功时置 null）。
     * 供 UI 在提示里带上真实原因，便于用户/开发者定位（v0.43.1：不再静默吞异常）。
     */
    var lastError: String? = null
        private set

    /** 语音落盘目录 */
    fun voiceDir(): File = File(context.filesDir, "voice").apply { if (!exists()) mkdirs() }

    /**
     * 最近一块 PCM 的峰值幅度（0..1），给录音蒙层的波形条用（v0.53.4）。
     * 抽水线程写、UI 线程读，所以是 `@Volatile`；只要相对高低，不做响度校准。
     */
    @Volatile
    var lastAmplitude: Float = 0f
        private set

    val isRecording: Boolean get() = recorder != null

    /**
     * 开始录音。返回 false = 全部候选采样率都失败（权限未授予 / 麦克风被占用），
     * 失败原因见 [lastError]；返回 true 时已进入录音态。
     */
    fun start(): Boolean {
        stopInternal(discard = true)
        lastAmplitude = 0f
        val dir = voiceDir()
        val stamp = System.currentTimeMillis()

        for (sampleRate in CANDIDATE_RATES) {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBuf <= 0) {
                // 该采样率硬件不支持，换下一个（不做无谓的构造）
                lastError = "不支持的采样率 ${sampleRate}Hz"
                continue
            }
            val file = File(dir, "voice_${stamp}.wav")
            val r = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    minBuf * BUFFER_SCALE,
                )
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord ctor failed @${sampleRate}Hz", e)
                lastError = "无法创建录音器：${e.javaClass.simpleName}"
                continue
            }

            // ⚠️ 构造不抛异常 **不代表拿到了麦克风**：必须检查 state。
            // 权限没给 / 麦克风被别的 App 独占时，这里才是真正的失败点。
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                lastError = "录音器初始化失败（麦克风被占用或无权限）"
                runCatching { r.release() }
                continue
            }

            val out = try {
                FileOutputStream(file).apply {
                    // 先占住 44 字节的 WAV 头，录完再回填真实长度
                    write(ByteArray(WAV_HEADER_BYTES))
                }
            } catch (e: Exception) {
                Log.e(TAG, "open output failed", e)
                lastError = "无法写入语音文件：${e.javaClass.simpleName}"
                runCatching { r.release() }
                continue
            }

            try {
                r.startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed @${sampleRate}Hz", e)
                lastError = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}"
                runCatching { out.close() }
                runCatching { r.release() }
                file.delete()
                continue
            }

            // 抽水线程：把 PCM 从 AudioRecord 读出来直接写文件。
            // 必须在独立线程 —— 读是阻塞的，放主线程会卡死 UI。
            pumping = true
            val buffer = ByteArray(minBuf)
            val thread = Thread({
                var written = 0
                while (pumping) {
                    val n = try {
                        r.read(buffer, 0, buffer.size)
                    } catch (e: Exception) {
                        Log.e(TAG, "read failed", e)
                        -1
                    }
                    if (n > 0) {
                        try {
                            out.write(buffer, 0, n)
                            written += n
                            lastAmplitude = peakAmplitude(buffer, n)
                        } catch (e: Exception) {
                            Log.e(TAG, "write failed", e)
                            break
                        }
                    } else if (n < 0) {
                        break   // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT 等
                    }
                }
                try {
                    out.flush()
                    out.close()
                } catch (e: Exception) {
                    Log.e(TAG, "close output failed", e)
                }
                // 头在收尾处回填：只有这里才知道真实数据长度
                runCatching { writeWavHeader(file, written, sampleRate) }
            }, "voice-pump")
            thread.start()

            recorder = r
            outputFile = file
            pumpThread = thread
            startedAt = System.currentTimeMillis()
            lastError = null
            return true
        }
        return false
    }

    /** 结束录音：[minDurationMs] 以下的丢弃（误触）；返回 (文件, 时长毫秒) */
    fun stop(minDurationMs: Long = 800): Pair<File, Long>? {
        val duration = System.currentTimeMillis() - startedAt
        val file = finishRecorder()
        if (file == null) return null
        if (duration < minDurationMs) {
            file.delete()
            return null
        }
        return file to duration
    }

    /** 用户手指滑开取消：停止并删除文件 */
    fun cancel() {
        finishRecorder()?.delete()
    }

    /** 停线程 → 停 AudioRecord → 返回落盘文件（无有效内容则 null） */
    private fun finishRecorder(): File? {
        val r = recorder ?: return null
        recorder = null
        val f = outputFile
        outputFile = null
        pumping = false
        try {
            if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
        } catch (e: Exception) {
            Log.e(TAG, "stop failed", e)
        }
        // 等抽水线程写完并回填 WAV 头，否则拿到的是没头的裸 PCM
        runCatching { pumpThread?.join(PUMP_JOIN_MS) }
        pumpThread = null
        runCatching { r.release() }
        return f?.takeIf { it.exists() && it.length() > WAV_HEADER_BYTES }
    }

    private fun stopInternal(discard: Boolean) {
        val r = recorder ?: return
        recorder = null
        pumping = false
        runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
        runCatching { pumpThread?.join(PUMP_JOIN_MS) }
        pumpThread = null
        runCatching { r.release() }
        if (discard) outputFile?.delete()
        outputFile = null
    }

    companion object {
        private const val TAG = "VoiceRecorder"

        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val WAV_HEADER_BYTES = 44
        /** 缓冲区放大倍数：minBufferSize 是下限，放大一点抗住主线程偶发抖动 */
        private const val BUFFER_SCALE = 4
        /** 等抽水线程收尾的上限：正常是毫秒级，卡住也不能把 UI 拖死 */
        private const val PUMP_JOIN_MS = 800L

        /**
         * 候选采样率，按优先级尝试。
         *
         * 16kHz 是 ASR 的通用输入（Whisper 内部也是 16k 重采样），绝大多数设备都支持；
         * 极少数设备只认 44.1k，作为兜底 —— 即便走了兜底也仍是标准 PCM WAV，
         * 下游无需改动（不像旧版"退化成 m4a 就转写不了"）。
         */
        private val CANDIDATE_RATES = intArrayOf(16_000, 44_100)

        /** 回填 44 字节 WAV 头（PCM / 单声道 / 16bit） */
        private fun writeWavHeader(file: File, dataLen: Int, sampleRate: Int) {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val header = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataLen)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)                       // fmt chunk 长度
            header.putShort(1.toShort())            // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataLen)
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(header.array())
            }
        }

        /** 语音时长展示：`12"` / `1'05"` */
        fun formatDuration(ms: Long): String {
            val totalSec = (ms / 1000).coerceAtLeast(1)
            return if (totalSec < 60) "$totalSec\""
            else "${totalSec / 60}'${(totalSec % 60).toString().padStart(2, '0')}\""
        }

        /**
         * 一块 16bit 小端 PCM 的峰值幅度（0..1）。
         * 只取绝对值最大那个样点：波形条看的是"这一小格多高"，不是响度。
         */
        private fun peakAmplitude(buf: ByteArray, len: Int): Float {
            var peak = 0
            var i = 0
            while (i + 1 < len) {
                val v = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
                val a = if (v < 0) -v else v
                if (a > peak) peak = a
                i += 2
            }
            return (peak / 32767f).coerceIn(0f, 1f)
        }
    }
}
