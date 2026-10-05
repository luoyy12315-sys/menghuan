package com.menghuan.app.ui.settings

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.CallRingtone
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「语音通话铃声设置」（v0.53.7；v0.54.0 并入「接听延迟」）。
 *
 * - 可以选**音频**（mp3 / m4a / wav / ogg…）：原样留用，不重编码；
 * - 也可以选**视频**（mp4 / mkv…）：自动把**音轨抽出来**存成音频（用项目里已有的解码器）；
 * - **来电与去电共用这一个铃声**（AI 打给你、你打给 AI 都是它）；不设就用系统默认。
 * - v0.54.0：「我打给 AI 时TA 过几秒接听」从「语音」页搬到这里 —— 它和铃声
 *   都是"打电话时的手感"，语义上属同一件事，原来那个裸数字框夹在行卡中间很突兀。
 */
@Composable
fun CallRingtoneScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = settingsContainer()
    val scope = rememberCoroutineScope()

    val path by app.settingsRepository.callRingtonePath.collectAsState(initial = "")
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var systemPreview by remember { mutableStateOf<android.media.Ringtone?>(null) }
    var previewing by remember { mutableStateOf(false) }
    // 这个文件到底能不能播（null = 还没查 / 用的是系统默认铃声）
    var playable by remember { mutableStateOf<Boolean?>(null) }
    var systemTitle by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        systemTitle = withContext(Dispatchers.IO) { CallRingtone.defaultRingtoneTitle(context) }
    }

    fun stopPreview() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        runCatching { systemPreview?.stop() }
        player = null
        systemPreview = null
        previewing = false
    }

    /** 试听当前生效的铃声：设了自定义的就试听它，没设就试听系统默认铃声（v0.53.9） */
    fun togglePreview() {
        if (previewing) {
            stopPreview()
            return
        }
        error = null
        if (path.isNotBlank()) {
            val p = CallRingtone.loopPlayer(path)
            if (p == null) {
                error = "这段铃声播不出来（文件可能已被删，或这台手机不支持这个格式）"
            } else {
                player = p
                previewing = true
            }
        } else {
            val r = CallRingtone.defaultRingtone(context)
            if (r == null) {
                error = "这台手机没有可用的系统默认铃声"
            } else {
                runCatching { r.play() }
                systemPreview = r
                previewing = true
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPreview() }
    }

    // 换过铃声 / 进页面就查一次「能不能播」——播不出来直接在界面上标红，不等打电话才发现
    LaunchedEffect(path) {
        playable = if (path.isBlank()) null else withContext(Dispatchers.IO) { CallRingtone.isPlayable(path) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { picked ->
            scope.launch {
                busy = true
                error = null
                stopPreview()
                val name = displayNameOf(context, picked)
                val saved = CallRingtone.import(context, picked, name)
                if (saved == null) {
                    error = "这个文件读不出音轨（也许是纯视频没声音，或格式不支持）"
                } else {
                    app.settingsRepository.setCallRingtonePath(saved)
                }
                busy = false
            }
        }
    }

    SettingsPage(title = "语音通话铃声", onBack = onBack) {
        Spacer(Modifier.height(16.dp))
        Text("当前铃声", fontSize = 12.sp, color = MutedText)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(NeuTrack)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (path.isBlank()) {
                    systemTitle?.let { "系统默认铃声（$it）" } ?: "系统默认铃声"
                } else {
                    CallRingtone.displayNameOf(path)
                },
                fontSize = 14.sp,
                fontWeight = if (path.isBlank()) FontWeight.Normal else FontWeight.Medium,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (busy) Text("处理中…", fontSize = 12.sp, color = MutedText)
        }

        // 自定义铃声读不出来（文件被删 / 这台手机不支持这个格式）→ 直接标红说明，不静默退回系统铃声
        if (playable == false) {
            Spacer(Modifier.height(10.dp))
            Text(
                "⚠️ 这个铃声文件**播不出来**（可能已被删除，或这台手机不支持这个格式）——" +
                    "通话时会退回系统默认铃声，点「试听」也不会响。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = NeuDanger,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row {
            NeuButton(
                text = if (path.isBlank()) "选择铃声文件" else "更换铃声",
                kind = NeuButtonKind.Primary,
                enabled = !busy,
                onClick = { picker.launch("*/*") },
            )
            Spacer(Modifier.width(12.dp))
            // 试听随时可点：设了自定义铃声就试听它，没设就试听系统默认铃声（v0.53.9）
            NeuButton(
                text = if (previewing) "停止试听" else "试听",
                enabled = !busy,
                onClick = { togglePreview() },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "支持音频（mp3 / m4a / wav / ogg…）与**视频**：选视频会自动把音轨抽出来存成音频。" +
                "选音频则原样留用、不重新编码。\n" +
                "**来电与去电共用这一个铃声** —— AI 打给你、你打给 AI，响的都是它。\n" +
                "⚠️ 手机开着**免打扰 / 静音 / 振动**时铃声一律不响（试听也一样）——" +
                "觉得「设了铃声却没声音」先看这里。",
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = MutedText,
        )

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = NeuDanger)
        }

        if (path.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            NeuTextButton(
                text = "恢复系统默认铃声",
                color = NeuDanger,
                onClick = {
                    scope.launch {
                        stopPreview()
                        app.settingsRepository.setCallRingtonePath("")
                    }
                },
            )
        }

        // 接听延迟（v0.54.0 从「语音」页搬过来）：
        // 原来它是个裸数字输入框夹在一堆行卡中间，没有归属；现在归到「通话体验」这一段里，
        // 和铃声摆在同一页 —— 两者都是"打电话时的手感"，语义上是一件事。
        Spacer(Modifier.height(22.dp))
        SettingsSubLabel("接听延迟")
        val answerDelay by app.settingsRepository.aiAnswerDelaySeconds.collectAsState(initial = 3)
        var delayDraft by remember(answerDelay) { mutableStateOf(answerDelay.toString()) }
        NeuTextField(
            value = delayDraft,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(2)
                delayDraft = digits
                digits.toIntOrNull()?.let { sec ->
                    scope.launch { app.settingsRepository.setAiAnswerDelaySeconds(sec) }
                }
            },
            label = "我打给 AI 时，TA 过几秒接听",
            placeholder = "0",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "填 0 = 立刻接听，想让 TA 慢点接就填几秒（最多 30）。延迟期间一直响铃、" +
                "界面显示「正在呼叫…」。**只影响你打给 AI 的那一通**，AI 打给你的来电不受影响。",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = MutedText,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** 从 content Uri 里问出真实文件名（扩展名决定"音频直接留用 / 视频抽音轨"这条路） */
private fun displayNameOf(context: Context, uri: Uri): String {
    val fromResolver = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    return (fromResolver ?: uri.lastPathSegment ?: "ringtone.mp3").substringAfterLast('/')
}
