package com.menghuan.app.ui.settings

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.CallRingtone
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「语音通话铃声设置」（v0.53.7；v0.54.0 并入「接听延迟」；v0.54.4 支持多个铃声）。
 *
 * - 可添加**多个**铃声（音频原样留用；视频自动抽音轨），点一条即设为**默认铃声**；
 * - 每个角色还能在「对话框右上角 ⋮ → 整页」里设**专属铃声**（角色专属 → 默认铃声 → 系统默认）；
 * - v0.54.0：「我打给 AI 时 TA 过几秒接听」从「语音」页搬到这里 —— 它和铃声
 *   都是"打电话时的手感"，语义上属同一件事。
 *
 * **v0.54.5（用户点名）**：
 * - 页内解释性小字**全部删掉**（免打扰提示、交互说明都不留）；
 * - 「添加铃声」旁边只标明**能上传什么文件**（mp3 / m4a / wav / ogg 等音频，mp4 等视频抽音轨）；
 * - 「接听延迟」从**手填秒数的输入框**改成**卡片形式**：右侧直接显示当前取值
 *   （立即接 / n 秒），点卡片或右侧弹出菜单选择（0 / 2 / 3 / 5 / 10 / 15 / 30 秒）。
 *
 * **v0.56.5（用户点名）**：本页只管铃声了 ——「接听延迟」**搬出去独立成项**，
 * 与「语音通话铃声」并排出现在「通话行为」页上（设置 → 通话行为 → 接听延迟）。
 */
@Composable
fun CallRingtoneScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = settingsContainer()
    val scope = rememberCoroutineScope()

    val path by app.settingsRepository.callRingtonePath.collectAsState(initial = "")
    val library by app.settingsRepository.callRingtoneLibrary.collectAsState(initial = emptyList())
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var systemPreview by remember { mutableStateOf<android.media.Ringtone?>(null) }
    var previewing by remember { mutableStateOf(false) }
    // 当前默认铃声能不能播（null = 用系统默认铃声）
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

    /** 试听指定铃声；path 为空 = 试听系统默认铃声 */
    fun preview(target: String) {
        stopPreview()
        error = null
        if (target.isNotBlank()) {
            val p = CallRingtone.loopPlayer(target)
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

    // 换过默认铃声 / 进页面就查一次「能不能播」——播不出来直接在界面上标红，不等打电话才发现
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
                    app.settingsRepository.addCallRingtone(saved)
                    app.settingsRepository.setCallRingtonePath(saved)
                }
                busy = false
            }
        }
    }

    SettingsPage(title = "语音通话铃声", onBack = onBack) {
        Spacer(Modifier.height(16.dp))
        Text("默认铃声", fontSize = 12.sp, color = MutedText)
        Spacer(Modifier.height(6.dp))

        // 系统默认铃声（永远排第一；点它 = 恢复用系统铃声）
        RingtoneRow(
            title = systemTitle?.let { "系统默认铃声（$it）" } ?: "系统默认铃声",
            selected = path.isBlank(),
            onSelect = { scope.launch { stopPreview(); app.settingsRepository.setCallRingtonePath("") } },
            onPreview = { preview("") },
            onDelete = null,
        )

        // 我添加的铃声：点一条设为默认；长按弹菜单删除
        library.forEach { p ->
            RingtoneRow(
                title = CallRingtone.displayNameOf(p),
                selected = path == p,
                onSelect = { scope.launch { stopPreview(); app.settingsRepository.setCallRingtonePath(p) } },
                onPreview = { preview(p) },
                onDelete = {
                    scope.launch {
                        stopPreview()
                        runCatching { withContext(Dispatchers.IO) { CallRingtone.delete(p) } }
                        app.settingsRepository.removeCallRingtone(p)
                    }
                },
            )
        }

        // 默认铃声读不出来（文件被删 / 这台手机不支持这个格式）→ 直接标红说明，不静默退回系统铃声
        if (playable == false) {
            Spacer(Modifier.height(6.dp))
            Text(
                "⚠️ 这个铃声文件**播不出来**（可能已被删除，或这台手机不支持这个格式）——" +
                    "通话时会退回系统默认铃声，「试听」也不会响。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = NeuDanger,
            )
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = NeuDanger)
        }

        Spacer(Modifier.height(16.dp))
        Row {
            NeuButton(
                text = "添加铃声",
                kind = NeuButtonKind.Primary,
                enabled = !busy,
                onClick = { picker.launch("*/*") },
            )
            Spacer(Modifier.width(12.dp))
            NeuButton(
                text = if (previewing) "停止试听" else "试听默认",
                enabled = !busy,
                onClick = { preview(path) },
            )
        }
        // v0.54.5（用户点名）：解释性小字全部删掉 —— 「添加铃声」旁边只标明**能传什么文件**
        Spacer(Modifier.height(8.dp))
        Text(
            "可上传 mp3 / m4a / wav / ogg 等音频，或 mp4 等视频（自动抽出音轨）",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MutedText,
        )

        // v0.56.5（用户点名）：「接听延迟」已从本页**拎出去**，成为「通话行为」页上与
        // 「语音通话铃声」**同级**的一张卡（设置 → 通话行为 → 接听延迟），不再挤在铃声列表下面。
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 铃声列表一行：点本体 = 设为默认，点「试听」= 试听这一条；
 * 长按弹右下角菜单才可删除（破坏性操作不当场执行，同全局约定）。
 */
@Composable
private fun RingtoneRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            NeuSheetRow(
                icon = MIcons.Bell,
                title = title,
                subtitle = "",
                onClick = onSelect,
                selected = selected,
                onLongClick = onDelete?.let {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                },
            )
            // 试听：长按菜单锚定在条目右下角（与项目里其它长按菜单一致）
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp)) {
                NeuTextButton(text = "试听", onClick = onPreview)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("删除", fontSize = 15.sp, color = NeuDanger) },
                        onClick = {
                            menuOpen = false
                            onDelete?.invoke()
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
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