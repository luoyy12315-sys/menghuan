package com.menghuan.app.data

import android.content.Context

/**
 * 语音转文字（v0.50.0 收敛为**单级**：只有端侧 sherpa-onnx）。
 *
 * ## 为什么把云端 Whisper 与系统识别器两级兜底删了
 * 这两级在真机上**从来没通过**，却挂了三个版本：
 *   ① 云端 Whisper 要服务商支持 `/audio/transcriptions`，用户配的那些端点实测都不支持 → 永远空；
 *   ② 系统 `SpeechRecognizer` 的"文件输入"（`EXTRA_AUDIO_SOURCE`，API 31+）在国产 ROM 上
 *      普遍不生效——识别服务只监听实时麦克风，喂它文件路径等于没喂，`onResults` 不回调、
 *      `onError` 也不给，最后走满 20s 超时返回 null。
 * 端侧离线识别同时绕开这两者：不碰网络、不碰系统识别服务，任何设备行为一致。
 * 留着永不生效的分支只会让"识别为空"这件事更难查，所以整条砍掉（见 [SherpaAsr]）。
 *
 * 转录失败**不阻断发送**：语音条该发还是发，`content` 为空，
 * 模型看到的是"用户发了段语音但没转成文字"，不会瞎猜内容。
 */
object SpeechToText {

    /** 录好的音频 → 文字。模型未下载 / 识别失败 / 文件为空一律 null */
    suspend fun transcribe(context: Context, audioPath: String): String? {
        val file = java.io.File(audioPath)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { SherpaAsr.transcribeWav(context, audioPath) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}
