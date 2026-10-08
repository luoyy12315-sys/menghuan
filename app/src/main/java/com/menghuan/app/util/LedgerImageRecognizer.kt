package com.menghuan.app.util

import android.content.Context
import android.net.Uri
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.data.UsageTracker
import com.menghuan.app.data.VisionImage
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.llm.OpenAiClient
import com.menghuan.app.ui.ledger.LedgerParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 识图记账（v0.42.0）：把用户选的账单/支付截图交给 vision 模型，取回金额与收支方向。
 *
 * 复用已有的多模态通道（[OpenAiClient.collectReply] 的 imageDataUrl 参数），
 * 不新增任何 LLM 能力；图片走 [VisionImage] 的两级处理——原图落盘进 ledger_images/，
 * 请求时现场降采样 base64（与聊天发图完全同一套约定，改一处两处受益）。
 */
object LedgerImageRecognizer {

    data class Result(val reply: String? = null, val error: String? = null)

    /** 账单图落盘目录（与聊天图 chat_images/ 分开：记账图可能量更大，便于单独清理） */
    fun ledgerImagesDir(filesDir: File): File = File(filesDir, "ledger_images")

    /**
     * 识别一张账单图。任何一步失败都返回 [Result.error]（面向用户的中文），
     * 成功返回模型原始文本（由 [LedgerParser.fromImageReply] 宽容解析）。
     */
    suspend fun recognize(
        context: Context,
        uri: Uri,
        filesDir: File,
        settingsRepository: SettingsRepository,
        usageTracker: suspend (Int) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val config = runCatching { settingsRepository.apiConfig.first() }.getOrNull()
        if (config == null || !config.isComplete) {
            return@withContext Result(error = "未配置 AI 接口，识图记账不可用")
        }

        // 1) 把选中的图拷进应用私有目录（原图保留：用户自己的凭证不该被悄悄压成缩略图）
        val dir = ledgerImagesDir(filesDir)
        if (!dir.exists() && !dir.mkdirs()) return@withContext Result(error = "无法保存图片")
        val file = File(dir, "receipt_${System.currentTimeMillis()}.jpg")
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file.takeIf { it.length() > 0 }
        }.getOrNull()
        if (copied == null) return@withContext Result(error = "读取图片失败，请重试")

        // 2) 降采样成 data URL（只在内存里压，落盘永远是原图）。
        //    v0.56.14：识图用 **1536** 而不是聊天发图的 1024——账单是文字密集图，
        //    长截图压到 1024 金额数字会糊到模型认不出，模型只能按兜底回"看不出金额"。
        val dataUrl = VisionImage.encodeDataUrl(copied.absolutePath, maxDim = 1536, quality = 85)
            ?: return@withContext Result(error = "图片无法解码，换一张试试")

        // 3) vision 请求：system prompt 硬编码框架 + 用户消息带图。
        // v0.56.14：不再 runCatching{getOrNull()} 把真实失败吞成一句笼统提示——
        // 模型不支持图片（400）/ Key 错（401）/ 超时，用户看到的都是同一句"请检查网络或接口配置"，
        // 没法排查。现在带上异常原文（服务端一般会说清"does not support image"这类真话）。
        val reply = runCatching {
            OpenAiClient(config.baseUrl, config.apiKey, config.model).collectReply(
                systemPrompt = LedgerParser.IMAGE_FRAMEWORK,
                history = listOf(
                    ChatMessage(
                        conversationId = 0,
                        content = "识别这张账单的金额与收支方向。",
                        isFromUser = true,
                    )
                ),
                imageDataUrl = { dataUrl },
            )
        }.getOrElse { e ->
            return@withContext Result(error = "识别失败：${e.message?.take(160) ?: e.javaClass.simpleName}")
        }
        usageTracker(reply.usageTokens ?: 0)
        runCatching { UsageTracker.add(settingsRepository, reply.usageTokens ?: 0, config.model,
            reply.promptTokens, reply.cachedPromptTokens) }
        val text = reply.text.trim()
        if (text.isEmpty()) return@withContext Result(error = "没能识别出内容，换张清晰的图")
        Result(reply = text)
    }
}
