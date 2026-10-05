package com.menghuan.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.AsrDownloadPhase
import com.menghuan.app.data.AsrDownloadSession
import com.menghuan.app.data.AsrModelFile
import com.menghuan.app.data.AsrModelManager
import com.menghuan.app.data.AsrModelRules
import com.menghuan.app.data.AsrModelSource
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuSectionTitle
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary

/**
 * 语音识别模型面板（v0.50.0）：SenseVoice int8 **不随包**，在这里下载。
 *
 * 挂在「设置」里，也会被"点语音键却没模型"的提示直接拉起——所以它必须能独立工作，
 * 不依赖任何聊天/通话上下文。
 *
 * 进度挂在进程级的 [AsrDownloadSession] 上：228MB 要下几分钟，用户关掉面板不该把下载掐断，
 * 重开面板还能看到同一次下载的百分比。
 */
@Composable
fun AsrModelScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MenghuanApp

    val phase by AsrDownloadSession.phase.collectAsState()
    val running = AsrDownloadSession.isRunning

    // 每次面板重开或有动作后重算一次；不在每帧重组里 stat 磁盘
    var ready by remember { mutableStateOf(AsrModelManager.isReady(context)) }
    var pending by remember { mutableStateOf(AsrModelManager.pendingBytes(context)) }
    fun refresh() {
        ready = AsrModelManager.isReady(context)
        pending = AsrModelManager.pendingBytes(context)
    }

    var source by remember { mutableStateOf(AsrModelSource.MODELSCOPE) }
    var notice by remember { mutableStateOf<String?>(null) }
        var confirmDelete by remember { mutableStateOf(false) }
    SettingsPage(title = "语音识别模型", onBack = onBack) {
        Spacer(Modifier.height(14.dp))

        NeuSectionTitle(if (ready) "已就绪" else "未就绪")
        Spacer(Modifier.height(8.dp))
        AsrModelFile.entries.forEach { file ->
            ModelFileRow(
                name = file.fileName,
                size = AsrModelRules.humanSize(file.sizeBytes),
                present = AsrModelManager.fileOf(context, file)
                    .let { it.isFile && it.length() == file.sizeBytes },
            )
            Spacer(Modifier.height(6.dp))
        }

        val runningPhase = phase as? AsrDownloadPhase.Running
        if (runningPhase != null) {
            Spacer(Modifier.height(8.dp))
            DownloadProgress(runningPhase)
        }

        Spacer(Modifier.height(14.dp))
        NeuSectionTitle("下载源")
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            AsrModelSource.entries.forEach { s ->
                NeuChip(text = s.label, selected = source == s, onClick = { source = s })
                Spacer(Modifier.width(8.dp))
            }
        }

        Spacer(Modifier.height(14.dp))
        if (ready) {
            NeuButton(text = "已就绪", kind = NeuButtonKind.Secondary, enabled = false, onClick = {})
        } else if (running) {
            NeuButton(text = "暂停下载", kind = NeuButtonKind.Secondary, onClick = { AsrDownloadSession.pause() })
        } else {
            NeuButton(
                // 按钮上直接写还差多少字节：这既是"要下多大"也是"要走多少流量"，
                // 不用再另加一行说明性小字
                text = (if (pending < AsrModelFile.entries.sumOf { it.sizeBytes }) "继续下载 · " else "下载模型 · ") +
                    AsrModelRules.humanSize(pending),
                kind = NeuButtonKind.Primary,
                onClick = {
                    notice = null
                    AsrDownloadSession.clearFailure()
                    AsrDownloadSession.start(context, app.applicationScope, source)
                },
            )
        }

        (phase as? AsrDownloadPhase.Failed)?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.reason, fontSize = 12.sp, lineHeight = 18.sp, color = NeuDanger)
            // 两个源都连不上时用户没有别的出路（手动导入已删——普通人不知道该下哪个文件），
            // 至少把官方仓库主页给出来：长按选中即可复制，去浏览器里自己试
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(source.webPage, fontSize = 11.sp, lineHeight = 16.sp, color = TextSecondary)
            }
        }
        notice?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = MutedText)
        }

        if (ready || pending < AsrModelFile.entries.sumOf { it.sizeBytes }) {
            Spacer(Modifier.height(8.dp))
            NeuSectionTitle("释放空间")
            Spacer(Modifier.height(8.dp))
            NeuButton(
                text = "删除模型文件",
                kind = NeuButtonKind.Danger,
                onClick = { confirmDelete = true },
            )
        }
        Spacer(Modifier.height(6.dp))
    }

    if (confirmDelete) {
        // v0.54.6：确认框也从底部弹（用户点名"所有弹窗改为从底下弹"）
        NeuSheetScaffold(title = "删除模型文件", onDismiss = { confirmDelete = false }) {
            Text(
                "将删除：已下载的 SenseVoice 模型与 tokens（约 228 MB），以及未下完的续传分片。\n" +
                    "保留：聊天记录、人设、任务与全部设置。删除后语音输入与通话不可用，" +
                    "文字聊天不受影响；随时可重新下载。",
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = TextPrimary,
            )
            Spacer(Modifier.height(18.dp))
            NeuButton(
                text = "删除",
                kind = NeuButtonKind.Danger,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    AsrDownloadSession.pause()
                    AsrModelManager.delete(context)
                    confirmDelete = false
                    notice = "已删除模型文件"
                    refresh()
                },
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ModelFileRow(name: String, size: String, present: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )
            Text(size, fontSize = 11.sp, color = FaintText)
        }
        Text(
            if (present) "已就绪" else "未下载",
            fontSize = 12.sp,
            color = if (present) TextSecondary else MutedText,
        )
    }
}

@Composable
private fun DownloadProgress(phase: AsrDownloadPhase.Running) {
    val fraction = AsrModelRules.fraction(phase.doneBytes, phase.totalBytes)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                phase.file.fileName,
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${AsrModelRules.humanSize(phase.doneBytes)} / ${AsrModelRules.humanSize(phase.totalBytes)}",
                fontSize = 12.sp,
                color = MutedText,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .neuSurface(999.dp, color = NeuTrack, inset = true),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(NeuAccent, RoundedCornerShape(999.dp)),
            )
        }
    }
}
