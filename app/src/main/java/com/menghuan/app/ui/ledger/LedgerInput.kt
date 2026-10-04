package com.menghuan.app.ui.ledger

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.menghuan.app.data.model.LedgerMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 记账的三种录入解析（纯函数区，JVM 单测覆盖）。
 *
 * 目标是把「一句人话」或「一张账单图」变成一条可落库的流水草稿：
 * 金额 + 收支方向 + 备注。解析不出来就返回 [LedgerDraft.INVALID]，
 * UI 侧提示用户手动补齐——绝不用猜的金额悄悄落库。
 */
object LedgerParser {

    /** 解析结果：金额为 null 表示没抓到，UI 应让用户手填 */
    data class LedgerDraft(
        val isIncome: Boolean,
        val amountCents: Long?,
        val note: String,
    ) {
        val isValid: Boolean get() = amountCents != null && amountCents > 0
    }

    /**
     * 从语音转写 / 手动一句话里解析一笔账。
     * 例：「今天买菜花了 35 块 8」→ 支出 3580；「发工资 8000」→ 收入 800000。
     */
    fun fromText(raw: String): LedgerDraft {
        val text = raw.trim()
        val amount = LedgerMath.parseAmountToCents(text)
        return LedgerDraft(
            isIncome = LedgerMath.detectIncome(text),
            amountCents = amount,
            note = text.take(60),
        )
    }

    /**
     * 从识图模型返回的文本里解析一笔账（模型可能回 JSON 也可能回一句话）。
     * 优先读 JSON 的 amount / type / note，退化为按纯文本解析。
     */
    fun fromImageReply(raw: String): LedgerDraft {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return LedgerDraft(false, null, "")
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val json = runCatching { org.json.JSONObject(trimmed.substring(start, end + 1)) }.getOrNull()
            if (json != null) {
                // 只接受 String / Number 类型的 amount，避免 optString 把对象强转成字面量
                val amountValue = json.opt("amount")
                val cents = when (amountValue) {
                    is Number -> Math.round(amountValue.toDouble() * 100)
                    is String -> LedgerMath.parseAmountToCents(amountValue)
                    else -> null
                }
                val typeText = (json.opt("type") as? String).orEmpty()
                val note = (json.opt("note") as? String).orEmpty().trim()
                if (cents != null && cents > 0) {
                    return LedgerDraft(
                        isIncome = typeText.contains("收入") || typeText.contains("income", ignoreCase = true),
                        amountCents = cents,
                        note = note.ifEmpty { trimmed.take(60) },
                    )
                }
                // JSON 里没拿到金额（或金额无效）：整体退化为纯文本解析
            }
        }
        return fromText(trimmed)
    }

    /** 识图 prompt（硬编码框架 + 可读文本金额示意） */
    const val IMAGE_FRAMEWORK: String =
        "你是一个账单识别器。看图片里的消费/收款凭证，提取出金额与收支方向，只输出 JSON，格式：\n" +
            "{\"amount\":数字（单位元，不带符号）,\"type\":\"支出\"或\"收入\",\"note\":\"一句话备注，不超过 20 字\"}\n" +
            "规则：\n" +
            "- 金额取该凭证的**合计/实付/总计**金额（若有优惠后金额取实付），不要取单项小计。\n" +
            "- 看不出收支方向时按「支出」处理；看不出金额时 {\"amount\":null,\"type\":\"支出\",\"note\":\"\"}。\n" +
            "- 不要输出 JSON 以外的任何文字。"
}

/**
 * 语音识别封装（v0.42.0）：用系统 [SpeechRecognizer]，零第三方依赖。
 *
 * 为什么不用第三方 ASR：本项目自用分发、依赖精简，且只有「记一笔」这一处语音需求；
 * 系统识别在国产 ROM 上一般已内置且免登录。代价是不支持离线兜底——识别失败让用户手填即可。
 *
 * 生命周期：每次录音前 [SpeechRecognizer.createSpeechRecognizer] 新建、结束后 destroy，
 * 避免长持实例在部分 ROM 上漏 reclaim（这是社区反复踩过的坑）。
 */
class VoiceLedgerController(private val context: Context) {

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** 识别中间结果（实时上屏，让用户知道"听到了"） */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /** 系统是否提供语音识别能力（部分精简 ROM 无） */
    fun isAvailable(): Boolean = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }
        .getOrDefault(false)

    /**
     * 开始一次识别。[onResult] 拿到最终转写文本（可能为空串，表示没听清）；
     * [onError] 拿到面向用户的中文提示。
     */
    fun start(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (_listening.value) return
        if (!isAvailable()) {
            onError("当前设备不支持语音识别，请手动输入")
            return
        }
        stop()
        _partial.value = ""
        val rec = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (rec == null) {
            onError("语音识别初始化失败")
            return
        }
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _listening.value = true
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                _listening.value = false
            }

            override fun onError(error: Int) {
                _listening.value = false
                onError(errorMessage(error))
                stop()
            }

            override fun onResults(results: Bundle?) {
                _listening.value = false
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isBlank()) onError("没听清，再说一次或手动输入") else onResult(text)
                stop()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) _partial.value = text
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { rec.startListening(intent) }.onFailure {
            _listening.value = false
            onError("无法启动录音，请检查麦克风权限")
            stop()
        }
        _listening.value = true
    }

    /** 手动结束（用户点停止）：让识别器出最终结果 */
    fun stopListening() {
        runCatching { recognizer?.stopListening() }
    }

    fun stop() {
        val rec = recognizer ?: return
        recognizer = null
        _listening.value = false
        runCatching { rec.cancel() }
        runCatching { rec.destroy() }
    }

    /** 错误码 → 用户能看懂的中文（不暴露技术细节） */
    fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错，请重试"
        SpeechRecognizer.ERROR_CLIENT -> "识别被中断，请重试"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限，请在设置里允许录音"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络不给力，识别失败"
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次或手动输入"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙，稍后再试"
        SpeechRecognizer.ERROR_SERVER -> "识别服务出错，请重试"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音"
        else -> "识别失败，请手动输入"
    }
}
