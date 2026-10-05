package com.menghuan.app.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import com.menghuan.app.realtime.MediaAudioDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 语音通话铃声（v0.53.7；v0.54.4 起**可添加多个**）。
 *
 * 用户可以传**音频或视频**：音频**原样留用**（不重编码，音质不损）；
 * 视频则用项目里已有的 [MediaAudioDecoder] 把音轨**抽出来存成 WAV**（不引新依赖、不重编码）。
 * 文件名保留用户原文件名（便于在列表里认出来）。哪个铃声生效由
 * [SettingsRepository.ringtoneForPersona] 决定：角色专属 → 设置里的默认铃声 → 系统默认铃声。
 */
object CallRingtone {

    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "amr", "wma")

    fun dir(context: Context): File = File(context.filesDir, "call_ringtone").apply { mkdirs() }

    private fun isAudio(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

    /** 展示用的文件名（存的是绝对路径）；空路径 = 系统默认铃声 */
    fun displayNameOf(path: String): String =
        if (path.isBlank()) "系统默认铃声" else File(path).name

    /** 删除一个已添加的铃声文件（从铃声库移除时调用） */
    fun delete(path: String) {
        if (path.isNotBlank()) runCatching { File(path).delete() }
    }

    /** 系统默认铃声（试听 / 展示用）；这台手机没设默认铃声时返回 null */
    fun defaultRingtone(context: Context): android.media.Ringtone? = runCatching {
        android.media.RingtoneManager.getRingtone(
            context,
            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
        )
    }.getOrNull()

    /** 系统默认铃声的名字（没设自定义铃声时摆在界面上，用户能对上试听听到的是什么） */
    fun defaultRingtoneTitle(context: Context): String? =
        runCatching { defaultRingtone(context)?.getTitle(context) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /**
     * 这个文件到底能不能播（MediaPlayer 能不能 prepare）。
     * 设置页用它做「读不出来就在界面上明说」—— 不要等打电话时才静默退回系统默认铃声。
     * 有磁盘 IO，调用方放 IO 线程。
     */
    fun isPlayable(path: String): Boolean {
        if (path.isBlank()) return false
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return false
        return runCatching {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                mp.setDataSource(path)
                mp.prepare()
                true
            } finally {
                runCatching { mp.release() }
            }
        }.getOrDefault(false)
    }

    /**
     * 导入铃声，返回落盘路径（失败 null）。
     * 音频 → 直接拷贝；视频 → 抽音轨写 WAV。**可添加多个**：文件名沿用用户原文件名，
     * 重名时补一个时间戳后缀（v0.54.4 起不再"只留一个"）。
     */
    suspend fun import(context: Context, uri: Uri, displayName: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val directory = dir(context)
                val temp = File(directory, "incoming_tmp")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                if (!temp.exists() || temp.length() <= 0L) return@runCatching null

                val audio = isAudio(displayName)
                val ext = if (audio) {
                    displayName.substringAfterLast('.', "mp3").lowercase().ifBlank { "mp3" }
                } else {
                    "wav"
                }
                // 保留原文件名（去掉不安全字符），重名补时间戳——列表里能认出自己加的是哪首
                val base = displayName.substringBeforeLast('.', displayName)
                    .replace(Regex("[^\\p{L}\\p{N} _\\-（）()]"), "_")
                    .trim()
                    .take(40)
                    .ifBlank { "铃声" }
                val stamp = System.currentTimeMillis()
                var target = File(directory, "$base.$ext")
                if (target.exists()) target = File(directory, "${base}_$stamp.$ext")

                if (audio) {
                    temp.copyTo(target, overwrite = true)
                } else {
                    val pcm = MediaAudioDecoder.decodeFile(temp) ?: return@runCatching null
                    writeWav(target, pcm.samples, pcm.sampleRate)
                }
                temp.delete()

                target.takeIf { it.length() > 0L }?.absolutePath
            }.getOrNull()
        }

    /** 44 字节 WAV 头 + PCM（与 VoiceRecorder 手拼 WAV 的同一套写法） */
    private fun writeWav(out: File, samples: ShortArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        java.io.DataOutputStream(java.io.BufferedOutputStream(out.outputStream())).use { dos ->
            dos.writeBytes("RIFF")
            dos.writeInt(Integer.reverseBytes(36 + dataSize))
            dos.writeBytes("WAVE")
            dos.writeBytes("fmt ")
            dos.writeInt(Integer.reverseBytes(16))
            dos.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // PCM
            dos.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // 单声道
            dos.writeInt(Integer.reverseBytes(sampleRate))
            dos.writeInt(Integer.reverseBytes(sampleRate * 2)) // byteRate
            dos.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt()) // blockAlign
            dos.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt()) // bitsPerSample
            dos.writeBytes("data")
            dos.writeInt(Integer.reverseBytes(dataSize))
            val bytes = ByteArray(dataSize)
            for (i in samples.indices) {
                bytes[i * 2] = (samples[i].toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
            }
            dos.write(bytes)
        }
    }

    /**
     * 开一个**循环播放**的 MediaPlayer（来电响铃与设置页试听都用它）。失败返回 null，
     * 调用方负责在不用时 `stop()` + `release()`。
     */
    fun loopPlayer(path: String): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setDataSource(path)
            isLooping = true
            prepare()
            start()
        }
    }.getOrNull()
}
