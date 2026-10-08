package com.menghuan.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.data.model.LedgerEntry
import com.menghuan.app.data.model.LedgerMath
import com.menghuan.app.data.model.LedgerSource
import com.menghuan.app.ui.data.AiDetailScreen
import com.menghuan.app.ui.data.DataViewModel
import com.menghuan.app.ui.ledger.LedgerEntrySheet
import com.menghuan.app.ui.ledger.LedgerParser
import com.menghuan.app.ui.ledger.LedgerScreen
import com.menghuan.app.ui.ledger.VoiceLedgerController
import com.menghuan.app.util.LedgerImageRecognizer
import kotlinx.coroutines.launch

/**
 * 记账本路由（v0.42.0）：全屏页 + 记一笔弹层 + 三种录入的接线。
 *
 * 三种录入的落地方式：
 * - 手动：表单直接敲
 * - 语音：**VoiceRecorder 录音 + SherpaAsr 端侧转写**（v0.56.14 起，与语音条/通话同一条链；
 *   原来的系统 SpeechRecognizer 是 v0.50.0 已判死刑的链路，权限误报就是这么来的）
 *   → LedgerParser.fromText
 * - 识图：系统相册/相机选图 → 拷进私有目录 → vision 请求 → LedgerParser.fromImageReply
 */
@Composable
fun LedgerRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MenghuanApp
    val vm: DataViewModel = viewModel(factory = DataViewModel.Factory)
    val scope = rememberCoroutineScope()

    val entries by vm.ledgerEntries.collectAsState()
    val cash by vm.cashCents.collectAsState()
    val statsScope by vm.ledgerScope.collectAsState()
    val month by vm.ledgerMonth.collectAsState()
    val weekStart by vm.ledgerWeekStart.collectAsState()
    val periodStats by vm.periodStats.collectAsState()
    val message by vm.ledgerMessage.collectAsState()

    var showSheet by remember { mutableStateOf(false) }
    // 正在编辑的那一笔（null = 新记一笔）。v0.63.0：流水长按 →「编辑这笔」
    var editingEntry by remember { mutableStateOf<LedgerEntry?>(null) }
    var imageDraft by remember { mutableStateOf<LedgerParser.LedgerDraft?>(null) }
    var imageParsing by remember { mutableStateOf(false) }
    var imageMessage by remember { mutableStateOf<String?>(null) }

    // 录音控制器：随页面退出销毁，避免长持实例
    val voice = remember { VoiceLedgerController(context) }
    DisposableEffect(Unit) {
        onDispose { voice.stop() }
    }

    // 记一笔的反馈（保存/删除成功）自动清除
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3000)
            vm.clearLedgerMessage()
        }
    }

    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        imageMessage = if (granted) "麦克风已授权，可以语音记账了" else "没有麦克风权限，语音记账用不了"
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        imageParsing = true
        imageMessage = null
        scope.launch {
            val result = LedgerImageRecognizer.recognize(
                context = context,
                uri = uri,
                filesDir = context.filesDir,
                settingsRepository = app.settingsRepository,
            )
            imageParsing = false
            when {
                result.error != null -> imageMessage = result.error
                result.reply == null -> imageMessage = "识别失败，请手动填写"
                else -> {
                    val draft = LedgerParser.fromImageReply(result.reply)
                    imageDraft = draft
                    imageMessage = if (draft.isValid) {
                        "已从图片识别，核对后保存"
                    } else {
                        // v0.56.14：不再只说"没识别出金额"——模型兜底时会在 note 里说明原因
                        // （"图片太模糊 / 图里没有金额"），把它亮出来，用户立刻知道卡在哪
                        val said = draft.note.trim().take(60)
                        if (said.isBlank()) "没识别出金额，请手动填写"
                        else "没识别出金额，模型说：$said"
                    }
                }
            }
        }
    }

    LedgerScreen(
        entries = entries,
        cashCents = cash,
        scope = statsScope,
        month = month,
        weekStart = weekStart,
        totals = periodStats.first,
        chainNet = periodStats.second,
        onToggleScope = {
            // 点标题在两档间切换（用户指定"点击左上角那个月度统计切换"）
            vm.setLedgerScope(
                if (statsScope == DataViewModel.LedgerStatsScope.MONTH) DataViewModel.LedgerStatsScope.WEEK
                else DataViewModel.LedgerStatsScope.MONTH
            )
        },
        onShiftPeriod = { vm.shiftLedgerPeriod(it) },
        onBack = onBack,
        onDelete = { vm.deleteLedgerEntry(it) },
        onEdit = { entry ->
            editingEntry = entry
            imageDraft = null
            imageMessage = null
            showSheet = true
        },
        onAdd = {
            editingEntry = null
            imageDraft = null
            imageMessage = null
            showSheet = true
        },
    )

    if (showSheet) {
        LedgerEntrySheet(
            cashCents = cash,
            onDismiss = {
                showSheet = false
                editingEntry = null
                voice.stop()
            },
            onSave = { isIncome, cents, note, source ->
                val target = editingEntry
                if (target != null) vm.editLedgerEntry(target, isIncome, cents, note)
                else vm.addLedgerEntry(isIncome, cents, note, source)
            },
            voice = voice,
            // 录音权限未授权时先申请（点语音才弹权限框，不在进页面时突袭用户）
            onNeedPermission = {
                audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            hasPermission = {
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            },
            onPickImage = {
                // 语音与识图互斥：先停录音再选图
                voice.stop()
                pickImage.launch("image/*")
            },
            imageParsing = imageParsing,
            imageMessage = imageMessage,
            onClearImageMessage = { imageMessage = null },
            initialDraft = imageDraft,
            initialSource = if (imageDraft != null) LedgerSource.IMAGE else LedgerSource.MANUAL,
            initial = editingEntry,
        )
    }
}

/** AI 洞察 + 周报 全屏详情路由 */
@Composable
fun AiDetailRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: DataViewModel = viewModel(factory = DataViewModel.Factory)
    val insights by vm.insights.collectAsState()
    val reports by vm.weeklyReports.collectAsState()

    AiDetailScreen(
        insights = insights,
        reports = reports,
        onBack = onBack,
        onDeleteInsight = { vm.deleteInsight(it) },
        onDeleteReport = { vm.deleteWeeklyReport(it) },
        onShareReport = { vm.shareWeeklyReport(context, it) },
    )
}

/** 金额格式化转发（LedgerRoute 之外也需要） */
internal fun formatYuan(cents: Long): String = LedgerMath.formatCents(cents)
