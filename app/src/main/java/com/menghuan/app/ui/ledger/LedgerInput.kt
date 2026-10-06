package com.menghuan.app.ui.ledger

import android.content.Context
import com.menghuan.app.data.AsrModelManager
import com.menghuan.app.data.SherpaAsr
import com.menghuan.app.data.VoiceRecorder
import com.menghuan.app.data.model.LedgerMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
                // JSON 里没拿到金额（或金额无效）：模型的 note 是它说明的原因
                // （v0.56.14 起 prompt 要求兜底时写原因）——把它交给备注框，
                // 不再把整段 JSON 串灌进表单
                if (note.isNotEmpty()) return LedgerDraft(false, null, note)
                // JSON 里没拿到金额（或金额无效）：整体退化为纯文本解析
            }
        }
        return fromText(trimmed)
    }

    /** 识图 prompt（硬编码框架 + 可读文本金额示意）。v0.56.14：兜底时要求说明原因，用户看得到模型卡在哪 */
    const val IMAGE_FRAMEWORK: String =
        "你是一个账单识别器。看图片里的消费/收款凭证，提取出金额与收支方向，只输出 JSON，格式：\n" +
            "{\"amount\":数字（单位元，不带符号）,\"type\":\"支出\"或\"收入\",\"note\":\"一句话备注，不超过 20 字\"}\n" +
            "规则：\n" +
            "- 金额取该凭证的**合计/实付/总计**金额（若有优惠后金额取实付），不要取单项小计。\n" +
            "- 看不出收支方向时按「支出」处理。\n" +
            "- 看不出金额时输出 {\"amount\":null,\"type\":\"支出\",\"note\":\"说明原因，例如：图片太模糊 / 图里没有金额数字 / 不是账单截图\"}。\n" +
            "- 不要输出 JSON 以外的任何文字。"
}

/**
 * 语音识别封装。**v0.56.14 推倒重做**：原来用系统 [android.speech.SpeechRecognizer]——
 * 那是 v0.50.0 语音栈重做时判定"国产 ROM 上永远走不通、不要复活"的链路，记账本是漏网之鱼；
 * 用户明明授权了麦克风，系统识别服务不可用时却报"没有麦克风权限"
 * （ERROR_INSUFFICIENT_PERMISSIONS 被国产 ROM 滥用），语音记账永远哑火。
 *
 * 现在回归**唯一识别一级**：[VoiceRecorder] 录音 + [SherpaAsr] 端侧转写，
 * 与聊天语音条 / 通话同一条链（模型在 设置 → 模型配置 → 识别模型 下载）。
 *
 * 形态：点「语音」开始录音（listening=true）→ 点「停止」结束并转写（recognizing=true）
 * → 文本一次性回来。端侧离线识别**没有逐字 partial**（语音条/通话同款），识别完才知道全文。
 */
class VoiceLedgerController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = VoiceRecorder(context)

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** 停止录音后的端侧转写（1~3 秒）：弹层显示成"正在识别…" */
    private val _recognizing = MutableStateFlow(false)
    val recognizing: StateFlow<Boolean> = _recognizing.asStateFlow()

    private var onResult: ((String) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    /** 识别模型是否就绪（没下载在开始时就亮出来，别等用户说完一句才失败） */
    fun isModelReady(): Boolean = AsrModelManager.isReady(context)

    /** 开始一次录音。回调在识别完成后触发（存字段，[stopListening] 里收尾用） */
    fun start(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (_listening.value || _recognizing.value) return
        if (!isModelReady()) {
            onError("语音识别模型还没下载：设置 → 模型配置 → 识别模型")
            return
        }
        stop()
        this.onResult = onResult
        this.onError = onError
        if (!runCatching { recorder.start() }.getOrDefault(false)) {
            onError("无法启动录音，请检查麦克风权限")
            return
        }
        _listening.value = true
    }

    /** 手动结束（点「停止」）：停录音 → 端侧转写 → 回调结果 */
    fun stopListening() {
        if (!_listening.value) return
        _listening.value = false
        val saved = recorder.stop(minDurationMs = 600)
        val file = saved?.first
        if (file == null) {
            onError?.invoke("太短了，没录上，说完再点停止")
            return
        }
        _recognizing.value = true
        scope.launch {
            val text = SherpaAsr.transcribeWav(context, file.absolutePath)
            _recognizing.value = false
            runCatching { file.delete() }
            if (text.isNullOrBlank()) onError?.invoke("没听清，再说一次或手动输入")
            else onResult?.invoke(text)
        }
    }

    /** 彻底取消（关弹层 / 切识图）：录音与识别协程一并停掉 */
    fun stop() {
        scope.coroutineContext.cancelChildren()
        _listening.value = false
        _recognizing.value = false
        runCatching { recorder.cancel() }
    }
}
