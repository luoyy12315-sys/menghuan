package com.menghuan.app.data

import android.content.Context
import com.menghuan.app.MenghuanApp
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * 语音转文字的入口（v0.59.0 起是**两条路的开关**，不再只有一级）。
 *
 * - **离线（默认）**：端侧 sherpa-onnx + SenseVoice，模型装完后下载到本机（见 [SherpaAsr]）；
 * - **在线**：用户自己配 OpenAI 兼容的 `/audio/transcriptions`（见 [OnlineAsr]）。
 *
 * ## 关键口径：切到手上的，不做自动回退
 * v0.50.0 之前那条云端链是作为"离线失败就自动试试云端"的第二级挂着的，真机结果是
 * 每次白等一轮超时、"识别为空"到底哪一级出的事谁也说不清，于是整个删掉。
 * 现在它是一条**明示的并列路**：选了在线就只走在线，失败把原因原样交给界面（[lastFailure]），
 * 绝不偷偷改用离线再假装没事——用户可以不信这句话，但不该被蒙在鼓里。
 *
 * ## 实时通话不在这里
 * 通话链路逐段喂 PCM 给 [SherpaAsr.transcribeSamples]（端侧，毫秒级、不联网）；
 * 在线接口要的是"一个文件一次请求"，照搬过去每句话都要多一次网络往返，
 * 对话会立刻变得答不上话。所以通话的识别**始终走端侧**，与这里的开关无关。
 *
 * 转录失败**不阻断发送**：语音条该发还是发，`content` 为空，
 * 模型看到的是"用户发了段语音但没转成文字"，不会瞎猜内容。
 */
object SpeechToText {

    /**
     * 最近一次识别失败的原因，给聊天页那句提示用（在线那一路最有价值：
     * 401 / 404 / 超时分别意味着 Key 错、这家没这个接口、网络不通，处理动作完全不同）。
     * 成功时清空。
     */
    @Volatile
    var lastFailure: String? = null
        private set

    /** 当前选的是不是在线那一路 */
    suspend fun isOnlineEngine(context: Context): Boolean =
        settingsOf(context)?.asrEngine?.first() == ASR_ONLINE

    /** 录好的音频 → 文字。模型未下载 / 在线未配置 / 识别失败 / 文件为空一律 null */
    suspend fun transcribe(context: Context, audioPath: String): String? {
        val file = File(audioPath)
        if (!file.exists() || file.length() == 0L) {
            lastFailure = "录音文件是空的"
            return null
        }
        val settings = settingsOf(context)
        val engine = runCatching { settings?.asrEngine?.first() }.getOrNull() ?: ASR_OFFLINE
        if (engine == ASR_ONLINE) {
            val url = runCatching { settings?.asrOnlineUrl?.first() }.getOrNull().orEmpty()
            val key = runCatching { settings?.asrOnlineApiKey?.first() }.getOrNull().orEmpty()
            val model = runCatching { settings?.asrOnlineModel?.first() }.getOrNull().orEmpty()
            val text = runCatching { OnlineAsr.transcribe(url, key, model, file) }.getOrNull()
            lastFailure = OnlineAsr.lastFailure
            return text?.takeIf { it.isNotBlank() }
        }
        // 离线路：失败原因只有"模型没下载"这一种是用户能自己解决的，其余是识别本身没出字
        val text = runCatching { SherpaAsr.transcribeWav(context, audioPath) }
            .getOrNull()?.takeIf { it.isNotBlank() }
        if (text == null) {
            lastFailure = if (!AsrModelManager.isReady(context)) "离线识别模型还没下载" else null
        } else {
            lastFailure = null
        }
        return text
    }

    private fun settingsOf(context: Context): SettingsRepository? =
        (context.applicationContext as? MenghuanApp)?.settingsRepository
}
