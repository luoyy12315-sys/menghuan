package com.menghuan.app.data

import android.content.Context
import android.net.Uri
import com.menghuan.app.llm.NetworkStack
import com.menghuan.app.realtime.MediaAudioDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 一个已克隆好的音色（硅基 `/audio/voice/list` 的一行） */
data class ClonedVoice(val customName: String, val uri: String, val model: String)

/**
 * 音色克隆（v0.50.0）。用户拍板走**「App 内全自动」**路线：
 *
 *   选一段样本 → 裁到前 15 秒 → **端侧 SenseVoice 自动转写出逐字稿** → multipart 上传 → 拿 `uri`
 *
 * ## 为什么必须自己转写
 * 硅基上传接口把 `text`（参考音频说了什么）标成**必填**。只让用户选一个 MP3 是凑不齐请求的。
 * 本项目 v0.50.0 步骤 1 正好装了端侧离线识别，克隆前跑一次即可，零额外依赖、不额外联网。
 * 转写不出来时把输入框露给用户手填，**不传空串去赌服务端容忍**（官方标 required）。
 *
 * ## 为什么收 mp3 / wav / m4a / mp4
 * v0.52.0 起样本一律先经 [MediaAudioDecoder]（`MediaExtractor` 自己读真实采样率与声道）抽音轨，
 * 所以凡是系统能解的容器都收——包括用户点名的 **mp4**（直接拿视频文件当样本，抽音轨、裁 15 秒、
 * 重写成 WAV 上传，不需要另写 MP3 编码器）。
 * 旧实现只认 mp3/wav 且用 `MediaCodec` 手搓解码（输入格式硬写 24kHz/单声道），
 * 真实流不符就吐 0 帧，用户拿一个正常 MP3 也会被告知"文件格式不对"——那是本次重写的起因。
 *
 * ## 关于「实名认证」
 * 硅基官方明写「使用用户预置音色，需要进行实名认证」。未认证时的错误码与文案**没有公开**，
 * 所以这里不自己猜码，把服务端 message 原文透传，并在 UI 上提示去认证。
 */
object VoiceCloner {

    /**
     * 样本长度上限。官方要求 <30s、建议 3~10s：
     * v0.53.3 从 15 秒收到 10 秒——参考音频越长并不越好，CosyVoice2 是零样本克隆，
     * 参考一长就容易跨到下一句/带进背景音乐，真机表现是克隆出来的声音发抖、变调。
     */
    const val MAX_SAMPLE_SECONDS = 10

    private const val OUT_RATE = 24_000

    private val http by lazy {
        NetworkStack.baseBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun voiceDir(context: Context): File =
        File(context.filesDir, "voices").apply { if (!exists()) mkdirs() }

    /**
     * 把用户选的样本裁到前 [MAX_SAMPLE_SECONDS] 秒，统一落成 24kHz 单声道 16bit WAV。
     *
     * **收哪些格式**：凡是系统 `MediaExtractor` 能抽到音轨的都收——mp3 / wav / m4a / aac /
     * **mp4** / flac / ogg（v0.52.0 起，用户点名要能传 mp4）。不写 MP3 编码器：上传接口收 wav，
     * 而抽音轨本来就要解码，解完直接重写成 WAV 反而更小更稳。
     *
     * 落盘文件名 = `音色名_时间戳.wav`（**唯一**，见下），MiMo 那句"样本留在本机"就是靠这个目录里的文件。
     *
     * @return 落盘文件；解不出音轨时抛 [IllegalStateException]，消息可直接给用户看
     */
    suspend fun prepareSample(context: Context, uri: Uri, name: String): File =
        withContext(Dispatchers.IO) {
            val source = File(voiceDir(context), "source_${System.currentTimeMillis()}.tmp")
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(source).use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("读取所选音频失败")

            val decoded = runCatching { decodeToPcm(source) }.getOrElse {
                source.delete()
                throw IllegalStateException("解码失败：${it.javaClass.simpleName}")
            }
            source.delete()
            if (decoded == null) {
                throw IllegalStateException("这个文件里解不出音轨（支持 mp3 / wav / m4a / mp4）")
            }

            val (samples, rate) = decoded
            if (samples.isEmpty()) throw IllegalStateException("音频里没有采样数据")
            // 先挑"一句完整的话"再重采样：硬切 10 秒很可能切在半句话上，
            // 那样参考音频与逐字稿对不上，零样本克隆出来就会发抖/变调（真机反馈）
            val trimmed = trimToSpeech(samples, rate, MAX_SAMPLE_SECONDS)
            // 统一到 24kHz 再落盘：上传体积小一半，两家都收 wav。
            // 必须走带低通的 resample——裸线性插值从 48kHz 抽到 24kHz 会把高频折成毛刺，
            // 毛刺进了参考音频就被克隆继承
            val floats = FloatArray(trimmed.size) { trimmed[it] / 32768f }
            val atOutRate = WavPcm.resample(floats, rate, OUT_RATE)
            val shorts = ShortArray(atOutRate.size) {
                (atOutRate[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            // v1.0.1：峰值归一（官方要求"稳定的音量"）。放在重采样之后 —— 归一的是最终要上传的那份样本。
            val wav = WavPcm.writeMono(normalizePeak(shorts), OUT_RATE)

            // ⚠️ v1.0.1：**文件名必须唯一**。旧实现只用音色名当文件名，而选样本那一刻名字还没填
            // （取默认值"我的音色"）→ 第二次克隆把同一个文件覆盖掉；MiMo 的音色名单就是"这个目录
            // 里的 .wav"，于是**按设计只能存在一个**（用户报的"只能克隆一个"就是这条）。
            val out = File(voiceDir(context), "${sanitize(name)}_${System.currentTimeMillis()}.wav")
            out.writeBytes(wav)
            out
        }

    /**
     * 从样本里取"一段完整的人声"：跳过开头的静音，在 [maxSeconds] 之内**找最近的能量谷下刀**，
     * 而不是从中间切断一句话。纯函数，JVM 可测。
     *
     * 阈值按整段最大能量的比例取（不是绝对值），这样安静的样本和响的样本行为一致。
     */
    fun trimToSpeech(samples: ShortArray, rate: Int, maxSeconds: Int): ShortArray {
        val limit = (rate * maxSeconds).coerceAtMost(samples.size)
        if (limit <= rate) return samples.copyOf(limit)          // 不足 1 秒，没什么可裁的
        val frame = (rate * FRAME_MS / 1000).coerceAtLeast(1)
        val frames = limit / frame
        if (frames <= 0) return samples.copyOf(limit)

        val energy = IntArray(frames)
        for (f in 0 until frames) {
            var sum = 0L
            val start = f * frame
            val end = minOf(start + frame, limit)
            var n = 0
            var i = start
            while (i < end) {
                val v = samples[i].toInt()
                sum += v * v
                n++
                i++
            }
            energy[f] = if (n == 0) 0 else kotlin.math.sqrt(sum.toDouble() / n).toInt()
        }
        val peak = energy.maxOrNull() ?: 0
        if (peak <= 0) return samples.copyOf(limit)              // 全静音：原样交给服务端
        val quiet = (peak * QUIET_RATIO).toInt().coerceAtLeast(200)

        var first = 0
        while (first < frames - 1 && energy[first] < quiet) first++
        var last = frames - 1
        while (last > first && energy[last] >= quiet) last--
        // 结尾留 120ms 呼吸，别把最后一个字切掉
        val tail = (rate * 120 / 1000)
        val from = first * frame
        val to = minOf(limit, last * frame + tail)
        // 找不到谷（整段没停过）就退回硬切上限；切出来太短也退回上限，别裁成半句
        if (to - from < rate) return samples.copyOf(limit)
        return samples.copyOfRange(from, to)
    }

    private const val FRAME_MS = 20
    private const val QUIET_RATIO = 0.06f

    /**
     * 直解 WAV 的输入上限（v0.97.0）：超过它就交给 [MediaAudioDecoder]
     * （那条路有 MAX_SAMPLES 与 30 秒墙钟双重保护），不赌一次 `readBytes()`。
     */
    private const val MAX_WAV_INPUT_BYTES = 64L * 1024 * 1024

    /**
     * @return (单声道 PCM16, 采样率)；容器不认 / 没有音轨返回 null。
     * WAV 先走 [WavPcm] 直解（不依赖系统解码器，JVM 单测也能跑），其余交给
     * [MediaAudioDecoder]（v0.52.0：旧的 Mp3Decoder 把输入格式硬写成 24kHz/单声道，
     * 真实流不符就吐 0 帧，克隆因此谎报"只支持 mp3/wav"）。
     */
    private fun decodeToPcm(source: File): Pair<ShortArray, Int>? {
        // v0.97.0：**先探魔数，再决定要不要整个读进内存**。
        // 以前无条件 `source.readBytes()` —— 用户挑一个几百 MB 的 mp4 当克隆样本时，
        // 还没判断出"这不是 RIFF"就先把整个文件读进堆，OOM 风险是实打实的
        // （后面 MediaAudioDecoder 有自己的上限保护，但前面这一步没有）。
        if (looksLikeRiff(source) && source.length() <= MAX_WAV_INPUT_BYTES) {
            WavPcm.parse(source.readBytes(), OUT_RATE)?.let { floats ->
                val shorts = ShortArray(floats.size) { (floats[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
                return shorts to OUT_RATE
            }
        }
        MediaAudioDecoder.decodeFile(source)?.let { return it.samples to it.sampleRate }
        return null
    }

    /**
     * 只读前 12 字节判 RIFF/WAVE 魔数（v0.97.0）。
     * 为一次格式判断把整个文件读进内存，是"选错文件就崩"的直接原因。
     */
    private fun looksLikeRiff(file: File): Boolean = runCatching {
        val head = ByteArray(12)
        val n = java.io.FileInputStream(file).use { it.read(head) }
        n >= 12 &&
            String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
    }.getOrDefault(false)

    /** 端侧转写样本，拿到上传接口必填的 `text`。识别模型没下载 / 转不出来返回 null，由 UI 让用户手填 */
    suspend fun transcribeSample(context: Context, sample: File): String? =
        SherpaAsr.transcribeWav(context, sample.absolutePath)?.takeIf { it.isNotBlank() }

    /**
     * 服务端认的 `customName`：**只收字母、数字、`_`、`-`，不超过 64 字符**
     * （真机 HTTP 400 原文："Invalid custom name. only letters and digits and _ and - are supported.
     * Should not exceed 64 chars"）。用户起的中文名（"永雏塔菲"）一个字符都发不出去，
     * 所以这里只用来生成一个 ASCII 标识，中文名另存本机显示（`SettingsRepository.cloneVoiceNames`）。
     *
     * ## v1.0.1：**必须带唯一后缀**（用户报"只能克隆一个"，且**上传成功、无任何报错**）
     * 旧实现只是把名字里的 ASCII 抠出来（`小美a` / `小明a` → 都变成 `a`；两次都用英文名也是同一个），
     * 服务端拿到**同名**就覆盖/视作同一个音色 → 名单里永远只有一条，且不会有任何报错。
     * 现在一律拼一个 4 位随机后缀，**每次克隆都是新音色**（同一段样本反复克隆也会各留一条）。
     */
    fun asciiVoiceName(display: String, stamp: String = newStamp()): String {
        val kept = display.trim().toCharArray()
            .filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
            .joinToString("").take(MAX_NAME_BASE)
        val base = kept.ifBlank { "voice" }
        return "${base}_$stamp"
    }

    /** 4 位随机后缀（`_k3f9`）：只求"同名不撞"，不承担标识作用 */
    private fun newStamp(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(4)

    private const val MAX_NAME_BASE = 40

    /**
     * 峰值归一化（v1.0.1）：官方对参考音频的要求里明写「**稳定的音量**」，而用户给的样本
     * 音量千差万别（手机录音常偏小、直播切片偏大且压限过）。太小的样本会让模型听不清音色细节。
     *
     * 保守做法：只**放大**不缩小，最多个位数 +12dB；已经够响（≥ -0.5dBFS 的 0.9 倍）就原样返回，
     * 全静音也原样返回 —— 绝不为了"标准化"把底噪一起抬起来。纯函数，JVM 可测。
     */
    fun normalizePeak(samples: ShortArray): ShortArray {
        var peak = 0
        for (s in samples) {
            val v = kotlin.math.abs(s.toInt())
            if (v > peak) peak = v
        }
        if (peak <= 0) return samples
        val target = (32767 * PEAK_TARGET_RATIO).toInt()
        if (peak >= target) return samples                       // 已经够响
        val gain = (target.toDouble() / peak).coerceAtMost(MAX_GAIN)
        if (gain <= 1.05) return samples
        return ShortArray(samples.size) {
            (samples[it] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** 目标峰值（留 1dB 余量，别贴着满刻度） */
    private const val PEAK_TARGET_RATIO = 0.89
    private const val MAX_GAIN = 4.0                              // ≈ +12dB

    /** 端侧转写样本，拿到上传接口必填的 `text`。识别模型没下载 / 转不出来返回 null，由 UI 让用户手填 */

    /**
     * 上传样本换 `uri`（硅基 `POST /uploads/audio/voice`，multipart 形态）。
     * @param customName 必须是 [asciiVoiceName] 出来的 ASCII 名，中文会被服务端 400 拒收
     * @return `speech:<customName>:<accountId>:<voiceId>`，原样存进 [VoiceRef.voiceId]
     */
    suspend fun upload(config: TtsConfig, sample: File, customName: String, transcript: String): String =
        withContext(Dispatchers.IO) {
            if (transcript.isBlank()) throw IllegalStateException("样本逐字稿不能为空（转写失败时请手填）")
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", sample.name, sample.asRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("model", config.effectiveModel())
                .addFormDataPart("customName", customName)
                .addFormDataPart("text", transcript)
                .build()
            val request = Request.Builder()
                .url("${config.effectiveBaseUrl()}/uploads/audio/voice")
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .post(body)
                .build()
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // 未实名认证时的错误码官方没公开 → 原文透传，别自己编判断
                    throw IllegalStateException("HTTP ${resp.code}：${TtsErrors.extract(text)}")
                }
                val uri = runCatching { JSONObject(text).optString("uri") }.getOrDefault("")
                if (uri.isBlank()) throw IllegalStateException("上传成功但响应里没有 uri：${text.take(200)}")
                uri
            }
        }

    /**
     * 列出我的克隆音色（硅基 `GET /audio/voice/list`）。
     *
     * 数组键名有**三份互相矛盾的证词**：官方文档 = `results`，两个第三方实现分别是 `result` / `data`。
     * 只能四路全兜（含"整个 body 就是数组"）；取不到时报"解析失败"而不是"没有音色"——
     * 后者会让用户以为自己克隆的东西丢了。
     */
    suspend fun listVoices(config: TtsConfig): List<ClonedVoice> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${config.effectiveBaseUrl()}/audio/voice/list")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}：${TtsErrors.extract(text)}")
            parseVoiceList(text)
        }
    }

    /** 纯解析，JVM 可测 */
    internal fun parseVoiceList(body: String): List<ClonedVoice> {
        if (body.isBlank()) throw IllegalStateException("音色列表响应为空")
        val array: JSONArray = runCatching {
            val json = JSONObject(body)
            listOf("results", "result", "data", "voice")
                .firstNotNullOfOrNull { key -> json.optJSONArray(key) }
                ?: throw IllegalStateException("音色列表解析失败：找不到数组字段")
        }.getOrElse {
            runCatching { JSONArray(body) }.getOrElse {
                throw IllegalStateException("音色列表解析失败：${TtsErrors.extract(body)}")
            }
        }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val uri = o.optString("uri")
            if (uri.isBlank()) return@mapNotNull null
            ClonedVoice(
                customName = o.optString("customName").ifBlank { uri },
                uri = uri,
                model = o.optString("model"),
            )
        }
    }

    /** 音色名会进文件名与 customName，去掉路径分隔符等会惹麻烦的字符 */
    private fun sanitize(name: String): String =
        name.trim().ifBlank { "voice" }.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
}
