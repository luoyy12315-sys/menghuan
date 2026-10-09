package com.menghuan.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.CallVisualAsset
import com.menghuan.app.data.CallVisualSlots
import com.menghuan.app.data.CallVisualStatus
import com.menghuan.app.data.CallVisualStore
import com.menghuan.app.data.CallVisualType
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 模型包选择器只认压缩包（v0.82.0 P2-1）。
 *
 * 个别 ROM 的选择器不认 zip mime、也不支持多 mime 过滤，那种机器上会漏出别的文件 ——
 * 这一档由 [CallVisualStore.looksLikeZip] 在做导入**之前**先按扩展名拦一道并当场说明，
 * 不让用户白等一轮复制解压。
 */
private val ZIP_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed")

/**
 * 通话形象的上传弹层（v0.58.0）。
 *
 * 入口在**会话设置页**那两行（用户拍板：不新增一级入口，也不放去人设编辑页重复一份）：
 * 「Live 2D」= 上传完整模型包 zip，「伪视频」= 上传若干 mp4 并逐个状态指认。
 *
 * 三条硬规矩（方案文档 §二十一 / §二十四）：
 * 1. **导入过程中不改绑定**：素材先复制/解压到私有目录，校验过了才一次性写
 *    metadata + `personas.videoVisualType`；失败只回一句原因，原绑定原封不动。
 * 2. 上传的 zip 与 mp4 都是**不可信输入**，体积、条目数、路径越界、能不能解码
 *    全在 [CallVisualStore] 里判掉，这里只管显示结果。
 * 3. 删除类操作一律"长按 → 菜单里点删除"（项目约定），长按那一下就是确认，不再叠弹窗。
 */

/** Live2D 模型包导入弹层 */
@Composable
fun Live2dVisualSheet(personaId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as MenghuanApp }
    val scope = rememberCoroutineScope()

    var asset by remember { mutableStateOf<CallVisualAsset?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<CallVisualStore.ImportProgress?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(personaId, refreshTick, busy) {
        if (busy) return@LaunchedEffect
        asset = withContext(Dispatchers.IO) { CallVisualStore.read(context, personaId) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            progress = null
            val outcome = withContext(Dispatchers.IO) {
                CallVisualStore.importLive2dPackage(context, personaId, uri) { progress = it }
            }
            busy = false
            progress = null
            if (outcome.ok) {
                val now = System.currentTimeMillis()
                val next = (asset ?: CallVisualAsset.EMPTY).copy(
                    type = CallVisualType.LIVE2D,
                    status = CallVisualStatus.READY,
                    errorMessage = "",
                    live2dModelJson = outcome.relativePath,
                    createdAt = asset?.createdAt ?: now,
                )
                note = if (commitVisual(app, personaId, next)) "模型包已就绪"
                else "模型包已解压，但配置没保存成功，请再试一次"
            } else {
                // 失败原因来自校验层（没找到 model3.json / 缺贴图 / 路径越界 / 包太大…），
                // 原绑定一个字都没改，用户可以照着改完再传
                note = outcome.message
            }
            refreshTick++
        }
    }

    val bound = asset
    val showModel = bound?.type == CallVisualType.LIVE2D && bound.status == CallVisualStatus.READY

    NeuSheetScaffold(title = "Live 2D 通话形象", onDismiss = onDismiss) {
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                busy -> progress?.label() ?: "正在准备导入…"
                showModel -> "已导入：" + bound.live2dModelJson.substringAfterLast('/')
                    .removeSuffix(".model3.json")
                bound?.status == CallVisualStatus.ERROR -> bound.errorMessage
                else -> "还没有模型包。上传一个 zip，里面要有 .model3.json、.moc3 和贴图。"
            },
            fontSize = 13.sp,
            color = if (showModel || busy) MutedText else NeuDanger,
        )
        // 进度条只在"这一步真有分母"时才画：校验/落盘都是一瞬间，画出来只会闪一下
        if (busy) {
            val fraction = progress?.fraction()
            if (fraction != null) {
                Spacer(Modifier.height(8.dp))
                ImportTrack(fraction)
            }
        }
        Spacer(Modifier.height(12.dp))
        NeuTextButton(
            text = if (showModel) "重新导入模型包（zip）" else "导入模型包（zip）",
            onClick = { if (!busy) picker.launch(ZIP_MIME_TYPES) },
        )
        if (showModel) {
            Spacer(Modifier.height(12.dp))
            NeuTextButton(
                text = "删除通话形象",
                onClick = {
                    scope.launch {
                        clearVisual(app, personaId)
                        onDismiss()
                    }
                },
                color = NeuDanger,
            )
        }
        note?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, fontSize = 13.sp, color = NeuDanger)
        }
    }
}

/** 伪视频素材导入 + 逐状态指认弹层 */
@Composable
fun VideoVisualSheet(personaId: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as MenghuanApp }
    val scope = rememberCoroutineScope()

    var asset by remember { mutableStateOf(CallVisualAsset.EMPTY) }
    var clips by remember { mutableStateOf<List<CallVisualStore.VideoClip>>(emptyList()) }
    var choosing by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(personaId, refreshTick, busy) {
        if (busy) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            CallVisualStore.read(context, personaId) to CallVisualStore.listVideos(context, personaId)
        }
        asset = loaded.first
        clips = loaded.second
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            // 复制 + 解码探测是重活，一条一条在 IO 线程做
            val failures = withContext(Dispatchers.IO) {
                uris.map { CallVisualStore.importVideo(context, personaId, it) }
                    .filter { !it.ok }
                    .map { it.message }
            }
            busy = false
            note = when {
                failures.isEmpty() -> null
                failures.size == uris.size -> failures.first()
                else -> "部分没导入：" + failures.joinToString("；")
            }
            refreshTick++
        }
    }

    NeuSheetScaffold(title = "伪视频通话形象", onDismiss = onDismiss) {
        Spacer(Modifier.height(6.dp))
        if (choosing == null) {
            CallVisualSlots.all.forEach { slot ->
                val assigned = asset.videoStates[slot.key]
                val clip = clips.firstOrNull { it.name == assigned }
                NeuSheetRow(
                    icon = MIcons.Play,
                    title = slot.label,
                    subtitle = when {
                        assigned.isNullOrBlank() && slot.key == CallVisualSlots.IDLE -> "未设置（必填，其他状态缺素材都回退到它）"
                        assigned.isNullOrBlank() -> "未设置（播待机素材兜底）"
                        clip == null -> "文件不在了：$assigned"
                        else -> "${clip.displayName} · ${formatSize(clip.bytes)} · ${formatDuration(clip.durationMs)}"
                    },
                    onClick = { choosing = slot.key },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NeuTextButton(
                    text = if (clips.isEmpty()) "＋ 添加视频素材" else "＋ 再加几条素材",
                    onClick = { if (!busy) picker.launch(arrayOf("video/*")) },
                )
                if (busy) {
                    Spacer(Modifier.size(12.dp))
                    Text("正在校验…", fontSize = 13.sp, color = MutedText)
                }
            }
            if (asset.videoStates.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                NeuTextButton(
                    text = "删除通话形象",
                    color = NeuDanger,
                    onClick = {
                        scope.launch {
                            clearVisual(app, personaId)
                            onDismiss()
                        }
                    },
                )
            }
        } else {
            // 指认某一条：点一条就落一次盘（每条都是完整可回退的状态表）
            val key = choosing!!
            val label = CallVisualSlots.all.first { it.key == key }.label
            Text("把「$label」指认到哪条素材", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            if (clips.isEmpty()) {
                Text("还没有素材，先返回添加视频。", fontSize = 13.sp, color = MutedText)
            }
            if (asset.videoStates[key] != null) {
                NeuSheetRow(
                    icon = MIcons.Close,
                    title = "不指认",
                    subtitle = if (key == CallVisualSlots.IDLE) "待机不能空着" else "这个状态改回播待机素材",
                    onClick = {
                        if (key == CallVisualSlots.IDLE) {
                            note = "待机是其他状态的兜底，必须指认一条"
                            choosing = null
                        } else {
                            scope.launch {
                                commitVisual(
                                    app, personaId,
                                    asset.copy(videoStates = asset.videoStates - key),
                                )
                                choosing = null
                                refreshTick++
                            }
                        }
                    },
                )
            }
            clips.forEach { c ->
                VideoClipRow(
                    clip = c,
                    picked = asset.videoStates[key] == c.name,
                    onPick = {
                        val next = asset.videoStates + (key to c.name)
                        if (next[CallVisualSlots.IDLE].isNullOrBlank()) {
                            note = "先给「待机」指认一条素材"
                            choosing = null
                        } else {
                            scope.launch {
                                val now = System.currentTimeMillis()
                                val saved = commitVisual(
                                    app, personaId,
                                    asset.copy(
                                        type = CallVisualType.VIDEO,
                                        status = CallVisualStatus.READY,
                                        errorMessage = "",
                                        videoStates = next,
                                        createdAt = if (asset.createdAt > 0) asset.createdAt else now,
                                    ),
                                )
                                note = if (saved) null else "保存失败，请再试一次"
                                choosing = null
                                refreshTick++
                            }
                        }
                    },
                    onDelete = {
                        scope.launch {
                            withContext(Dispatchers.IO) { CallVisualStore.deleteVideo(context, personaId, c.name) }
                            // 被删的素材如果还挂在某个状态上，一并从表里摘掉，
                            // 免得 metadata 里留一个指向空气的路径
                            val pruned = asset.videoStates.filterValues { it != c.name }
                            if (pruned != asset.videoStates) {
                                commitVisual(
                                    app, personaId,
                                    if (pruned.isEmpty()) asset.copy(type = CallVisualType.NONE, status = CallVisualStatus.NONE)
                                    else asset.copy(videoStates = pruned),
                                )
                            }
                            refreshTick++
                        }
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            NeuTextButton(text = "返回", onClick = { choosing = null })
        }
        note?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 13.sp, color = NeuDanger)
        }
    }
}

/**
 * 一条素材行：**点 = 指认过去，长按 = 弹菜单删除**。
 *
 * 长按不直接删（项目约定：那一下只弹菜单，点菜单里的删除才真删），
 * 并且长按补一次震动反馈。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoClipRow(clip: CallVisualStore.VideoClip, picked: Boolean, onPick: () -> Unit, onDelete: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    if (picked) com.menghuan.app.ui.theme.NeuTrack else androidx.compose.ui.graphics.Color.Transparent,
                    RoundedCornerShape(12.dp),
                )
                .combinedClickable(
                    onClick = onPick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(clip.displayName, fontSize = 15.sp, color = TextPrimary, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${formatSize(clip.bytes)} · ${formatDuration(clip.durationMs)} · ${clip.width}×${clip.height}",
                    fontSize = 12.sp,
                    color = FaintText,
                )
            }
            if (picked) Text("已指认", fontSize = 12.sp, color = TextSecondary)
        }
        // 长按菜单锚在这个 0 宽盒子上：DropdownMenu 的 Popup 只认直接父节点的位置
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("删除这条素材", color = NeuDanger) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}

/**
 * 一次性提交绑定：先落 metadata.json，再把 `personas.videoVisualType` 写成同一种类型。
 *
 * 顺序是有意的——metadata 在前、库在后：万一第二步失败，通话那边读到的还是旧类型，
 * 而目录里的素材已经准备好，不会演出"类型说 VIDEO、素材却不存在"的半吊子状态。
 */
private suspend fun commitVisual(app: MenghuanApp, personaId: Long, asset: CallVisualAsset): Boolean {
    val ok = withContext(Dispatchers.IO) { CallVisualStore.write(app, personaId, asset) }
    if (!ok) return false
    val persona = app.personaRepository.getPersona(personaId) ?: return false
    app.personaRepository.save(persona.copy(videoVisualType = asset.type.name))
    return true
}

/** 解绑并清掉所有素材文件（删除形象 = 目录一起走，不留几百 MB 孤儿） */
private suspend fun clearVisual(app: MenghuanApp, personaId: Long) {
    val cleared = CallVisualAsset(type = CallVisualType.NONE, status = CallVisualStatus.NONE)
    commitVisual(app, personaId, cleared)
    withContext(Dispatchers.IO) { CallVisualStore.pruneUnbound(app, personaId, cleared) }
}

/**
 * 导入进度条：一条细灰槽 + 纯黑填充。
 *
 * 不用 M3 的 `LinearProgressIndicator` 是同一条老规矩：它自己的轨道高度与配色是另一套质感。
 * 分数由 [CallVisualStore.ImportProgress.fraction] 给出，**null 时根本不画**
 * （一闪而过的进度条比没有更糟）。
 */
@Composable
private fun ImportTrack(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(NeuTrack, RoundedCornerShape(2.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(NeuAccent, RoundedCornerShape(2.dp)),
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.CHINA, "%.1fMB", bytes / 1048576f)
    else -> "${bytes / 1024}KB"
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
