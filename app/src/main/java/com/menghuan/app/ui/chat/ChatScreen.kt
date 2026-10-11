package com.menghuan.app.ui.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import com.menghuan.app.ui.theme.AccentInk
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.agent.AgentRunner
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.MediaKind
import com.menghuan.app.ui.common.LocalContentWidth
import com.menghuan.app.ui.realtime.CallLogSheet
import com.menghuan.app.ui.common.EaseOutExpo
import com.menghuan.app.ui.common.EaseOutQuart
import com.menghuan.app.ui.common.MarkdownText
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuSnappySpring
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.neuRowPress
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.rememberNeuPressState
import com.menghuan.app.ui.settings.SettingsRoutes
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import com.menghuan.app.ui.theme.personaBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable

/**
 * 输入栏的底部避让已迁到 [com.menghuan.app.ui.chat.rememberBottomBarInset]
 * （`ui/chat/KeyboardInsets.kt`，v0.44.0）。
 *
 * 旧的 `INSET_SETTLE_MS` / `OUTPUT_SWITCH_QUIET_MS` / `InsetCode` 三件套是补丁叠补丁的产物，
 * 已被"导航条取基线 + 形态互斥由调用方显式表达"取代，见新文件的类注释。
 */

/**
 * 单聊页（新拟态）：从会话列表 push 进入，左上返回即退出。
 * - AI 气泡 = 凹陷纸面；用户气泡 = 强调渐变 + 柔和投影
 * - 长按头像（500ms）= 切换**本会话**的 AI 接口（v0.36.4 起按会话独立，不影响其他会话/全局）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    personaId: Long,
    onBack: () -> Unit,
    showBack: Boolean = true,   // 平板双栏：右栏嵌入时隐藏返回键（会话切换走左栏点选）
    onOpenCall: () -> Unit = {}, // v0.46.0：顶栏电话图标 → 实时语音通话页
    /** v0.58.0：加号面板「视频通话」格 → 视频通话页（真实声音仍是同一套实时通话引擎） */
    onOpenVideoCall: () -> Unit = {},
    /** v0.51.0：设置链改成全屏页，聊天页里"去配置 / 去下载"的出口一律把路由名交出去 */
    onOpenSettings: (String) -> Unit = {},
    vm: ChatViewModel = viewModel(key = "chat_$personaId", factory = ChatViewModel.factory(personaId)),
) {
    val uiState by vm.uiState.collectAsState()
    val persona by vm.activePersona.collectAsState()
    // v0.58.0：视频通话入口的先决条件 = 这个人设绑过形象。列里有就直接放行（人设流里就有，不摸磁盘）。
    // v0.60：列里是 NONE 时**再读一次盘上的 metadata** —— 口径是"两边不一致以文件为准"
    // （AGENTS §零.7）。存量向量的真实来源：v0.58.0 的编辑人设曾把 `videoVisualType` 重置成 NONE，
    // 而素材文件还在，于是形象明明配好了、点「视频」却被拦在"还没有通话形象"上。
    val columnVisual = com.menghuan.app.data.CallVisualType.fromName(persona?.videoVisualType)
    var hasCallVisual by remember(personaId) {
        mutableStateOf(columnVisual != com.menghuan.app.data.CallVisualType.NONE)
    }
    val visualCheckContext = LocalContext.current.applicationContext
    LaunchedEffect(personaId, persona?.videoVisualType) {
        if (columnVisual != com.menghuan.app.data.CallVisualType.NONE) {
            hasCallVisual = true
            return@LaunchedEffect
        }
        hasCallVisual = withContext(Dispatchers.IO) {
            runCatching {
                val app = visualCheckContext as com.menghuan.app.MenghuanApp
                com.menghuan.app.data.CallVisualStore.read(app, personaId).isUsable
            }.getOrDefault(false)
        }
    }
    var showNoVisualSheet by remember { mutableStateOf(false) }
    if (showNoVisualSheet) {
        NeuSheetScaffold(title = "还没有通话形象", onDismiss = { showNoVisualSheet = false }) {
            Text(
                "视频通话要有角色形象：Live2D 模型包，或几段按通话状态切换的短视频。",
                fontSize = 14.sp,
                color = MutedText,
            )
            Spacer(Modifier.height(14.dp))
            NeuTextButton(
                text = "去添加形象",
                onClick = {
                    showNoVisualSheet = false
                    onOpenSettings("chat_settings/$personaId")
                },
            )
            Spacer(Modifier.height(8.dp))
            NeuTextButton(
                text = "先用语音通话",
                onClick = {
                    showNoVisualSheet = false
                    onOpenCall()
                },
            )
        }
    }
    // P3-3：非只读 MCP 工具的确认弹层（计划定稿：列出服务器/工具/参数，用户点头才执行）。
    // 关掉弹层 = 拒绝（onDismiss 与「拒绝」同一条路径，结果以"用户拒绝"回灌给模型）。
    uiState.toolConfirm?.let { req ->
        NeuSheetScaffold(title = "AI 想调用工具", onDismiss = { vm.resolveToolConfirm(false) }) {
            Text(
                "${req.serverName} · ${req.toolName}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
            )
            Spacer(Modifier.height(10.dp))
            Text("参数", fontSize = 12.sp, color = MutedText)
            Spacer(Modifier.height(4.dp))
            Text(
                // 参数原样给用户看（它本来就是模型生成的 JSON）；过长截断，弹层不是阅读器
                req.argumentsJson.take(500),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MutedText,
            )
            Spacer(Modifier.height(16.dp))
            NeuTextButton(text = "执行一次", onClick = { vm.resolveToolConfirm(true) })
            Spacer(Modifier.height(8.dp))
            NeuTextButton(text = "拒绝", onClick = { vm.resolveToolConfirm(false) })
        }
    }
    val taskSuggestions by vm.taskSuggestions.collectAsState()
    var viewerPath by remember { mutableStateOf<String?>(null) } // 全屏图片查看（v0.39.0）
    var messageMenu by remember { mutableStateOf<ChatMessage?>(null) }
    // v0.54.3：长按菜单改成"微信那种"贴着气泡的横向图标菜单，需要气泡在屏幕中的位置来定位
    var messageMenuAnchor by remember { mutableStateOf<Rect?>(null) }
    // v0.54.3：多选模式（批量删除 / 复制 / 转发）+ 已选消息 id + 转发目标选择弹层
    var multiSelect by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var showForwardSheet by remember { mutableStateOf(false) }
    // 语音条「已展开转文字」的消息 id 集合（v0.43.1）。
    // 放在列表层而不是气泡内部：气泡滚出屏会被回收，状态放里面会丢。
    val voiceTranscriptIds = remember { mutableStateListOf<Long>() }
    // v0.54.6：思考过程改成**就地展开在消息下方**（可点开/收起），不再是底部弹层；
    // 与转文字同理放列表层，气泡被回收也不丢展开状态。
    val reasoningOpenIds = remember { mutableStateListOf<Long>() }
    var showApiSwitch by remember { mutableStateOf(false) }
    // v0.47.1：三点 = 编辑人设（v0.53.5 起走独立路由页，不再需要本地开关）
    var callLogSession by remember { mutableStateOf<Long?>(null) } // v0.49.0：点开的通话记录卡
    var callLogTitle by remember { mutableStateOf("") }
    // v0.43.0 输入区面板：0=收起 1=表情 2=加号（同一时刻只开一个，与键盘互斥）
    var panel by remember { mutableStateOf(0) }
    var showCamera by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val appContainer = context.applicationContext as com.menghuan.app.MenghuanApp
    // v0.54.7：这个角色的聊天背景（会话设置 → 聊天背景 → 自定义），有就铺在消息列表底下
    val chatBackgrounds by appContainer.settingsRepository.chatBackground
        .collectAsState(initial = emptyMap())
    val myBackground = chatBackgrounds[personaId] ?: ""

    // v0.77.0：聊天外观（全局一套）+ 用户头像 —— 气泡配色/字号/是否显示头像都由它定。
    val chatAppearance by appContainer.settingsRepository.chatAppearance
        .collectAsState(initial = com.menghuan.app.data.ChatAppearance())
    val userAvatar by appContainer.settingsRepository.userAvatar
        .collectAsState(initial = com.menghuan.app.data.UserAvatar())

    /**
     * v0.55.0：气泡在屏幕中的位置改用**普通容器**记录，不再用 state。
     *
     * 原实现是「每个条目各自 `remember { mutableStateOf<Rect?>() }` + `onGloballyPositioned` 里写它」——
     * 而 `onGloballyPositioned` 在滚动时**每一帧**都会回调（条目在窗口中的位置一直在变），
     * 于是每个可见条目每帧写一次 state → 每帧重组一次。消息一多，滚动就是这么被拖垮的。
     *
     * 这份坐标只在**长按那一刻**读一次（菜单要贴着气泡弹），压根不需要可观测，
     * 用普通 HashMap 就够：滚动期间零 state 写入、零重组。
     *
     * ⚠️ v0.89.0：记的**必须是气泡自己的矩形**（下面各 kind 分支挂在气泡那一层），
     * 不能挂在外层那个 `fillMaxWidth()` 的 Column 上 —— 那量到的是整行宽度，
     * left/right 是行边缘，菜单箭头（恒在菜单底部正中）就会飘到远离气泡的地方。
     */
    val bubbleBounds = remember { HashMap<Any, Rect>() }

    /**
     * 把"气泡自己"的屏幕矩形记进 [bubbleBounds]，供长按菜单定位。
     *
     * 拆成独立 lambda 而不是每个分支内联 `onGloballyPositioned { ... }`：这样各气泡只需
     * `Modifier.recordBubbleBounds(rowKey)` 一行，且 `rowKey` 在闭包里被捕获，不改动任何调用点签名。
     */
    val recordBubbleBounds: (Any) -> Modifier = { rowKey ->
        Modifier.onGloballyPositioned { bubbleBounds[rowKey] = it.boundsInRoot() }
    }

    /** v0.54.7：长按弹消息菜单统一补一次震动（与全局"长按补一次震动反馈"的约定一致） */
    val openMessageMenu: (ChatMessage, Any) -> Unit = { msg, rowKey ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        messageMenuAnchor = bubbleBounds[rowKey]
        messageMenu = msg
    }
    // 自定义表情（v0.43.0）：库表 → 面板真实数据源
    val stickers by appContainer.db.stickerDao().observeAll()
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    // v0.54.3：多选「转发」的目标会话列表（排除当前会话）
    val allPersonas by appContainer.personaRepository.observePersonas()
        .collectAsState(initial = emptyList())

    // v0.54.4：右上角三点的那些入口（编辑人设 / 聊天记录 / 主动打电话 / 立绘 / Live 2D /
    // 聊天背景 / 联网 / 音色 / 模型）连同新增的专属铃声、定时主动消息、人设主动关心
    // 一并搬进整页 ChatSettingsScreen，这里不再保留任何菜单状态与选文件入口。
    // 正在播放的语音消息 id（同一时刻只播一条；null = 没在播）
    var playingVoiceId by remember { mutableStateOf<Long?>(null) }
    val voicePlayer = remember { VoicePlayer() }
    DisposableEffect(Unit) { onDispose { voicePlayer.release() } }
    // 播放看门狗（v0.52.0）：语音条播完了还在动，是因为复位原本**只**依赖
    // MediaPlayer.onCompletion——而 MP3 尾部 padding / 缺 Xing 头时这个回调可能整场不来。
    // 这里按 position/duration 自己判结束，再加一个绝对超时兜底：UI 永远不可能卡在"播放中"。
    LaunchedEffect(playingVoiceId) {
        val id = playingVoiceId ?: return@LaunchedEffect
        var waited = 0
        while (isActive && playingVoiceId == id) {
            delay(120)
            waited += 120
            val duration = voicePlayer.durationMs
            if (!voicePlayer.alive) break
            if (duration > 0 && voicePlayer.positionMs >= duration - 80) break
            if (waited > duration + 3_000 || waited > 120_000) break
        }
        if (playingVoiceId == id) {
            voicePlayer.stop()
            playingVoiceId = null
        }
    }
    // 发图 / 发文件（v0.56.6 改成**先挂到输入框上方**）：
    // 选中后不再立刻发出去，而是把它们停在输入栏上方（可继续写文字一起发，也可以直接点发送），
    // 右侧 ✕ 丢掉。问过的口径：用户明确要"先停留、等输入文字后再发送，或支持直接发送"。
    var pendingAttachment by remember { mutableStateOf<PendingAttachment?>(null) }

    // 相册（v0.38.0）：选图 → 拷进应用私有目录 → 挂成待发送（不再立刻发）
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val app = context.applicationContext as com.menghuan.app.MenghuanApp
            val path = com.menghuan.app.data.VisionImage.copyToChatImages(context, uri, app.filesDir)
            if (path != null) {
                val file = java.io.File(path)
                pendingAttachment = PendingAttachment(
                    isImage = true,
                    path = path,
                    name = "图片",
                    size = file.length(),
                    extractedText = "",
                )
            } else {
                com.menghuan.app.ui.common.AppToast.fail("图片读取失败")
            }
        }
    }
    // 发送后收起键盘用（不主动 clearFocus：光标保留，点输入框可再次唤起键盘）
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    // 导入自定义表情（v0.43.0）：相册选图 → 拷进 filesDir/stickers/ → 入库
    val stickerPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val path = com.menghuan.app.data.StickerStore.import(context, uri)
                if (path == null) {
                    com.menghuan.app.ui.common.AppToast.fail("表情导入失败")
                } else {
                    // 同一张图重复导入不再加一条（库表按 path 查重）
                    val existing = appContainer.db.stickerDao().findByPath(path)
                    if (existing == null) {
                        appContainer.db.stickerDao()
                            .insert(com.menghuan.app.data.model.Sticker(path = path))
                    } else {
                        com.menghuan.app.ui.common.AppToast.show("这个表情已经添加过了")
                    }
                }
            }
        }
    }

    // 选文件（v0.43.0）：任意类型 → 拷进私有目录 → 本地抽正文；
    // v0.56.6：同样**不再立刻发**，挂成待发送等用户确认/补文字
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val name = queryDisplayName(context, uri) ?: "文件"
                val saved = copyUriToFiles(context, uri, name)
                if (saved == null) {
                    com.menghuan.app.ui.common.AppToast.fail("文件读取失败")
                } else {
                    val text = com.menghuan.app.data.FileTextExtractor.extract(saved, name)
                    pendingAttachment = PendingAttachment(
                        isImage = false,
                        path = saved.absolutePath,
                        name = name,
                        size = saved.length(),
                        extractedText = text.text,
                    )
                }
            }
        }
    }

    /** 把待发送附件真的发出去（带当前输入框里的文字当说明；没有文字也能直接发） */
    fun sendPendingAttachment() {
        val pending = pendingAttachment ?: return
        val caption = uiState.input.trim()
        if (pending.isImage) {
            vm.sendImage(pending.path, caption)
        } else {
            vm.sendFile(pending.path, pending.name, pending.size, pending.extractedText, caption)
        }
        pendingAttachment = null
    }

    // 录音（v0.43.0）：按住说话。v0.43.1 起改成"点语音键切到语音态 → 按住说话区录音"。
    val recorder = remember { com.menghuan.app.data.VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    // 录音蒙层（v0.53.4）：形态照微信"按住说话"——中间气泡（波形 + 秒数）、
    // 下面「松手发送」、再下面两颗圆按钮「取消」「转文字」，手指滑向哪颗哪颗点亮。
    var recordArm by remember { mutableStateOf(RecordArm.NONE) }
    var recordMs by remember { mutableStateOf(0L) }
    var waveBars by remember { mutableStateOf<List<Float>>(emptyList()) }
    // 语音态：输入框位置显示"按住 说话"，再点语音键切回打字
    var voiceMode by remember { mutableStateOf(false) }

    /**
     * 输入区「输出形态」统一切换（v0.43.1）。
     *
     * 三种形态**天然互斥**：系统键盘 / 表情·加号面板 / 语音（按住说话）。
     * 任何一次切换都必须走这里，理由有二：
     *
     * 1. **键盘与面板不会同屏**：任何一次切换都在这里同时决定"要不要键盘"，
     *    不留"键盘还开着 + 面板也开着"的窗口期（以前表情键、加号键、语音键各写一份
     *    hide/show，漏一处用户就看到界面错乱）。
     * 2. **底部避让只需要一个信号**：`panel` / `voiceMode` 决定
     *    [rememberBottomBarInset] 的 `followKeyboard`。切到面板/语音态时
     *    `followKeyboard = false`，底部直接落回导航条基线 —— 不再需要旧版那个
     *    "切换后 280ms 内不准按键盘高度算"的静默期补丁。
     *
     * @param nextPanel 目标面板（0=收起 1=表情 2=加号）
     * @param nextVoice 目标语音态
     * @param showKeyboard 目标形态是打字态、且希望**立刻唤起键盘**（用户主动点回输入框/键盘键）
     */
    fun switchOutputMode(nextPanel: Int, nextVoice: Boolean, showKeyboard: Boolean) {
        // 键盘只在"打字态 + 明确要求"时唤起；其余一律收起
        val keyboardWanted = showKeyboard && nextPanel == 0 && !nextVoice
        panel = nextPanel
        voiceMode = nextVoice
        if (keyboardWanted) keyboardController?.show() else keyboardController?.hide()
    }

    // ⚠️ **切会话时必须重置输入区的临时态**（v0.43.1 修"界面乱套"）：
    // 上面这些 `remember` 在同一个 ChatScreen 实例里**跨会话保留**。平板双栏复用
    // 同一个右栏 Composable 切会话时，会出现"表情面板还开着 / 语音态还亮着 /
    // 搜索态没退" —— 用户感受就是整个界面乱了。
    // 用 `LaunchedEffect(personaId)` 而不是 `remember(key)`：后者会重建状态槽，
    // 连正在播的语音、已展开的转文字都会被清掉（那是用户主动开的，不该丢）。
    LaunchedEffect(personaId) {
        panel = 0
        voiceMode = false
        messageMenu = null
        viewerPath = null
    }

    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) com.menghuan.app.ui.common.AppToast.fail("需要麦克风权限才能发语音")
    }

    // 录音中：每 60ms 采一格波形 + 刷一次时长（蒙层里显示成 30" 那种）。
    // 用 recorder.lastAmplitude（抽水线程写的 @Volatile 值），不额外开线程。
    LaunchedEffect(recording) {
        if (!recording) {
            // v0.55.0：**这里不再清空波形** —— 蒙层收起还有 140ms 动画，
            // 此刻清空会让它画出一个"空气泡"。清空的活改到下一次 onStart 里做。
            return@LaunchedEffect
        }
        val startedAt = System.currentTimeMillis()
        while (isActive) {
            delay(60)
            recordMs = System.currentTimeMillis() - startedAt
            waveBars = (waveBars + recorder.lastAmplitude).takeLast(WAVE_BAR_COUNT)
        }
    }

    // 气泡最大宽度 = 版心的 76%（比例随版心自适应）
    val bubbleMaxWidth = LocalContentWidth.current * 0.76f

    // 当前会话生效的 AI 接口（长按头像可切换，只影响本会话）
    val apiProfiles by vm.apiProfiles.collectAsState()
    val apiOverrideId by vm.apiOverrideId.collectAsState()
    val globalProfileId by vm.globalProfileId.collectAsState()

    // 查看即已读：会话可见期间每条新消息到达都刷新阅读时刻，列表未读点立即消除
    LaunchedEffect(uiState.messages.lastOrNull()?.id) { vm.markRead() }

    // 返回键分流（v0.43.1 定稿，v0.43.1 二次返工）：
    // 只在**真的有"可收起的层"**时启用 BackHandler：录音中 / 面板打开 / 语音态。
    //
    // ⚠️⚠️ **绝不能把"键盘可见"也算进 `enabled`** —— 这条是上一版两次踩坑的结论：
    //   ① 把 `imeVisible` 算进 `enabled` 时，BackHandler 会在**键盘刚弹起**那一刻就消费返回事件，
    //      而键盘收起是**动画**（约 200ms），这期间 `imeVisible` 一直是 true →
    //      用户连按两次返回，两次都撞在"hide 键盘"分支上，页面纹丝不动。
    //   ② `WindowInsets.ime.getBottom()` 是**逐帧变化**的量，在 ChatScreen 顶层读它
    //      会让整个聊天页在键盘动画期间每帧重组 —— 这就是"进入/返回都卡顿"的来源。
    //
    // 正解 = **键盘交给系统**：系统对"返回键 + 输入法可见"有内置分流 ——
    //   第一次返回收键盘且**不把事件交给 Activity**，键盘收起后再按才会走到 NavHost。
    // 我们只需管好自己造的那些层（面板/语音态），键盘一个字节都不用碰。
    //
    // 顺序即优先级：录音 → 面板 → 语音态。
    // ⚠️ 面板分支里**不能再调 `keyboardController.hide()`**：点表情时键盘早已收走，
    //    这一句只会在"面板还开着 + 用户刚点回输入框"的窗口里把新唤起的键盘又按下去。
    val hasDismissableLayer = recording || multiSelect || panel != 0 || voiceMode
    androidx.activity.compose.BackHandler(enabled = hasDismissableLayer) {
        when {
            // 录音中：吃掉返回，避免录制被中断（松手才算结束）
            recording -> Unit
            // v0.54.3：多选态返回 = 退出多选（清空已选）
            multiSelect -> {
                multiSelect = false
                selectedIds.clear()
            }
            panel != 0 -> panel = 0
            voiceMode -> voiceMode = false
        }
    }

    // ⚠️ 键盘真弹起 → 自动收面板/语音态（v0.44.1，修"点表情→点输入框→键盘盖住输入栏"）：
    // 点表情收键盘**不清焦点**，再点输入框时 onFocus 不触发、面板态挂着，
    // 而系统会对已聚焦的输入框自动弹键盘 → 避让按"面板态"算 → 被盖住。
    // 互斥改由键盘状态驱动（PanelSwitchHelper 思路），点击入口只作为正常路径的兜底。
    // 只收面板、不动键盘 —— 键盘是用户此刻明确要的。
    ImeDismissesPanels(
        panelOpen = panel != 0 || voiceMode,
        onKeyboardShown = {
            panel = 0
            voiceMode = false
        },
    )

    val listState = rememberLazyListState()
    // v0.55.0：列表往上一翻，顶栏下沿浮出一条发丝线 —— 顶栏与消息区之间有了明确分界，
    // 也顺带告诉用户"上面还有内容"。
    // ⚠️ 用**阈值布尔**而不是逐帧偏移量：`firstVisibleItemScrollOffset` 滚动时每帧都变，
    // 要是把它当普通 State 读进 composable，整页会跟着每帧重组；套一层 derivedStateOf
    // 之后只在跨过 8dp 那一帧翻转一次，动画期间零重组。
    val listScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8
        }
    }
    val topBarLineAlpha by animateFloatAsState(
        targetValue = if (listScrolled) 1f else 0f,
        animationSpec = tween(180),
        label = "topBarLine",
    )
    // v0.82.0：滚到顶部自动加载更早的消息（分页窗口由 VM 的 historyLimit 控制）。
    // snapshotFlow 只在索引变化时回调：加载完新页用户还停在顶部不会重复触发；
    // 条目用 DB id 稳定 key，窗口扩大时滚动位置锚在原处不动。
    val hasMoreHistory by vm.hasMoreHistory.collectAsState()
    LaunchedEffect(listState, hasMoreHistory) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index -> if (index == 0 && hasMoreHistory) vm.loadOlderMessages() }
    }
    // ⚠️ 令牌是 `@Composable get()`，不能在 drawBehind 的 lambda 里读（见 Theme.kt 说明），先取成局部值
    val topBarLineInk = NeuHairline

    // 外层容器：让搜索态覆盖层（第二个 fillMaxSize 子级）真正叠在聊天内容之上，
    // 而不是作为 AdaptiveScreen Column 的兄弟被挤成 0 高度
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize()) {
                // 顶部导航：返回键（退出；双栏嵌入时隐藏）+ 居中头像区（长按换风格）
                NeuTopBar(
                modifier = Modifier.drawBehind {
                    if (topBarLineAlpha > 0f) {
                        drawLine(
                            color = topBarLineInk,
                            start = Offset(0f, size.height - 0.5f),
                            end = Offset(size.width, size.height - 0.5f),
                            strokeWidth = 1.dp.toPx(),
                            alpha = topBarLineAlpha,
                        )
                    }
                },
                navigationIcon = if (showBack) {
                    {
                        NeuCircleButton(
                            // v0.42.3：返回前先收键盘。键盘与页面滑出同时动会让输入栏被
                            // ime inset 变化"顶一下"（用户反馈的往上弹）。先收键盘再退出，
                            // 输入栏在退场动画里就是稳定的。
                            //
                            // v0.43.1 补：`hide()` 是**异步**的（键盘收起约 200ms），
                            // 紧接着 popBackStack，退场动画（420ms）前半段 `ime` 还在变，
                            // 输入栏就会在滑出过程中抖一下。这里先把底部避让钉死
                            // （走统一切换逻辑，切到"无键盘"形态），再退出。
                            onClick = {
                                switchOutputMode(nextPanel = 0, nextVoice = false, showKeyboard = false)
                                onBack()
                            },
                            size = 38.dp,
                        ) {
                            Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
                        }
                    }
                } else null,
                center = {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        // 长按头像 = 切换本会话的 AI 接口（多接口配置；v0.36.4 起按会话独立）
                        modifier = Modifier.combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                showApiSwitch = true
                            },
                        ),
                    ) {
                        NeuAvatar(
                            emoji = persona?.avatarEmoji ?: "🙂",
                            imagePath = persona?.avatarImagePath.orEmpty(),
                            size = 38.dp,
                            ringBrush = personaBrush(personaId),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                persona?.name ?: "…",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                persona?.title ?: "",
                                fontSize = 11.sp,
                                color = MutedText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    // v0.54.4：三个点 = 进入**整页**会话设置（不再弹小菜单）。
                    // 编辑人设 / 聊天记录 / 主动打电话 / 立绘 / Live 2D / 聊天背景 / 联网 /
                    // 音色 / 模型，以及新增的**专属铃声 / 定时主动消息 / 人设主动关心**都在那一页。
                    Icon(
                        MIcons.MoreVert,
                        contentDescription = "会话设置",
                        tint = TextPrimary,
                        modifier = Modifier
                            .size(21.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onOpenSettings("chat_settings/$personaId") },
                    )
                },
            )

            // 消息列表（按天分组）
            val chatItems = remember(uiState.messages) { buildChatItems(uiState.messages) }
            // 是否贴底：控制回底按钮显隐 + 新消息是否跟随滚动
            // key = chatItems：derivedStateOf 闭包在首帧捕获 chatItems 引用，
            // 不加 key 会永远对着旧列表判断（消息更新后显隐错乱）
            val nearBottom by remember(chatItems) {
                derivedStateOf {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    last >= chatItems.lastIndex - 1
                }
            }
            // 首次消息到达（或进页就有缓存消息）：无动画直接定位到底部——
            // 带动画的 animateScrollToItem 会从列表头横扫到底（用户反馈的「进对话页闪烁」）；
            // 之后的滚动只在用户本来就贴着底部时跟随（翻历史时不被新消息强行拽回）
            var initialScrollSettled by remember { mutableStateOf(false) }
            LaunchedEffect(uiState.messages.size, uiState.isGeneratingImage) {
                if (uiState.messages.isEmpty()) return@LaunchedEffect // 首帧还没加载出消息，不滚
                val target = chatItems.lastIndex.coerceAtLeast(0)
                if (!initialScrollSettled) {
                    listState.scrollToItem(target)
                    initialScrollSettled = true
                } else if (nearBottom) {
                    listState.animateScrollToItem(target)
                }
            }
            // 生成/生图期间跟随尾部增高：仅当用户已在底部附近时按增高量滚动。
            // v0.41.4：生成中挂的是三点气泡（高度基本恒定），这里主要给生图动画卡与
            // 整段回复落地那一刻兜底——回复一次性上屏时若本来贴底，要跟着滚到新底部。
            val followTail = uiState.generating || uiState.isGeneratingImage
            LaunchedEffect(chatItems.size, followTail) {
                if (!followTail) return@LaunchedEffect
                var lastTailHeight = -1
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: 0 }
                    .collect { height ->
                        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                        val near = lastVisible >= chatItems.lastIndex - 1
                        if (near && lastTailHeight in 1 until height) {
                            listState.scrollBy((height - lastTailHeight).toFloat())
                        }
                        lastTailHeight = height
                    }
            }

            // 键盘 / 面板弹起会让列表视口变矮（输入栏靠底部 padding 被顶高 → weight(1f) 的
            // 列表可用高度变小）。LazyColumn 视口收缩时**不会自动保持贴底**——它按"首个可见项 +
            // 偏移"锚定，视口一矮，底部最新一条就被挤出屏幕外（真机反馈：点输入框唤键盘后
            // 看不到最新消息）。这里补上跟随：只在**视口收缩**且**收缩前本来就贴底**时，
            // 按收缩量往下滚；正在往上翻历史则不动。
            // ⚠️ 用 prevAtBottom（收缩前一帧的值）而不是当前帧：键盘无动画瞬时弹起时，
            //    当前帧最后一条已被整个裁掉、判断不准，只有前一帧才代表"收缩前的真实位置"。
            LaunchedEffect(chatItems.size) {
                var prevViewport = -1
                var prevAtBottom = false
                snapshotFlow {
                    val info = listState.layoutInfo
                    val vp = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(0)
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                    vp to (chatItems.isNotEmpty() && last == chatItems.lastIndex)
                }.collect { (vp, atBottom) ->
                    if (prevViewport > 0 && vp in 1 until prevViewport && prevAtBottom) {
                        listState.scrollBy((prevViewport - vp).toFloat())
                    }
                    prevViewport = vp
                    prevAtBottom = atBottom
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                // v0.54.7：自定义聊天背景（选「原生背景」时 myBackground 为空、不画）。
                // 铺在 LazyColumn 底下、跟随版心，气泡浮在它上面。
                if (myBackground.isNotBlank()) {
                    rememberLocalBitmap(myBackground)?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                }
                // 空态（v0.97.0，审查 U9）：新会话进来这里是一整片空白 —— 用户看不出
                // "这片空白是正常的"还是"消息没加载出来"。给一句事实短语（不写说明性小字）。
                if (chatItems.isEmpty() && !uiState.generating) {
                    EmptyChatHint(persona?.name)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    // v0.56.0：微信式间距——同一人连发小间距（6dp），换人时靠条目自身的
                    // gapBefore 顶部再拉开（见下），日期头自带 8dp 垂直内边距也吃这份间距。
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 稳定 key：已落库消息用 DB id（滚动往返、新消息插入时旧条目状态/位图缓存不重建，
                    // 这是长会话滚动流畅的关键）；流式占位/乐观插入尚未拿到 id，用索引兑底。
                    // contentType 分类型复用组合槽：日期头与气泡不互相回收，气泡内部结构同型复用。
                    items(
                        count = chatItems.size,
                        key = { idx -> chatKeyOf(chatItems, idx) },
                        contentType = { idx ->
                            when (chatItems[idx]) {
                                is ChatListItem.DayHeader -> "header"
                                is ChatListItem.Msg -> "msg"
                            }
                        },
                    ) { idx ->
                        // 条目稳定 key：既给 LazyColumn 复用，也当长按菜单取气泡位置的索引
                        val rowKey = chatKeyOf(chatItems, idx)
                        when (val item = chatItems[idx]) {
                            is ChatListItem.DayHeader -> Box(
                                // 只保留重排弹簧：进页时整屏条目作为"新条目"插入，
                                // 默认的成排淡入弹簧会和页面滑入动画叠在一起，观感就是"进页卡一下"
                                Modifier.fillMaxWidth().animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null),
                                contentAlignment = Alignment.Center,
                            ) {
                                // 纯文字日期（微信式）：原灰底胶囊在文字下方露出灰条，被当成"线"（用户反馈）
                                Text(
                                    item.label,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    fontSize = 10.sp,
                                    letterSpacing = 1.sp,
                                    color = MutedText,
                                )
                            }

                            is ChatListItem.Msg -> {
                                val msg = item.message
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null)
                                        // v0.56.0：换人时顶部再拉开 12dp（叠加 6dp 基础间距 ≈ 18dp），
                                        // 微信式"换说话人就空一口气"；同一人连发只吃基础间距。
                                        .padding(top = if (item.gapBefore) 12.dp else 0.dp)
                                        // ⚠️ v0.83.0：锚点**不记在这一层** —— 这一层的 top 含上面那 12dp
                                        // 换人空档，菜单会被顶到离气泡老远（用户反馈「箭头挨不到气泡」）。
                                ) {
                                // 多选态：左侧留出勾选圈的空档（v0.54.3）
                                // ⚠️ v0.54.7：这里**必须是 Column**（原来是 Box）——里面现在有两个兄弟节点
                                // （消息本体 + 就地展开的思考过程），Box 会把它们叠在一起、思考过程直接盖住气泡
                                // （用户反馈"把消息条都覆盖了"）；Column 才会一个排在另一个下方。
                                // v0.83.0：锚点下沉到这一层 —— 它的 top 就是**气泡自己的顶边**。
                                // v0.55.0：写进普通容器而不是 state（见 bubbleBounds 注释）
                                //
                                // ⚠️ v0.89.0：这一层**不能**再挂 onGloballyPositioned 当锚点用 ——
                                // 它是 fillMaxWidth()，量到的是**整行宽度**，left/right 是行边缘而不是
                                // 气泡边缘；菜单箭头恒在菜单底部正中，横向却按行边缘对齐，于是短气泡
                                // （「好」「嗯」）的箭头能偏出气泡中心 100dp+，看着就是"箭头不贴合气泡"。
                                // 现在锚点分别记在**气泡自己那一层**（见下面各 kind 分支的 recordBubbleBounds）。
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = if (multiSelect) 34.dp else 0.dp),
                                ) {
                                // v0.43.0：媒体类型优先（存量消息 mediaKind 为空，按老规则推断）
                                val kind = MediaKind.fromLegacy(msg.mediaKind, msg.imagePath)
                                when (kind) {
                                    // 图片消息（AI 生成图落盘后的最终形态 / 用户发的图 / 自定义表情）
                                    // v0.42.4：外层 Box 必须显式指定 contentAlignment。
                                    // Box 默认 TopStart——无论 ImageBubble 内部 horizontalAlignment
                                    // 是什么，整块都会被摆在左上角，用户发的图因而"跑到 AI 那侧"。
                                    MediaKind.IMAGE -> {
                                        // v0.97.0：`failed` 以前恒传 false —— 图没了的时候
                                        // 只能永远显示"正在生成图片…"（用户看到的是"卡住"）。
                                        // 现在按真实读数传：这条路只渲染**已经落过盘**的图片消息，
                                        // 读不出位图 = 文件已失效，该说"图片打不开了"，而不是继续转圈。
                                        val imgBitmap = rememberLocalBitmap(msg.imagePath)
                                        Box(
                                            Modifier.fillMaxWidth(),
                                            contentAlignment = if (msg.isFromUser) Alignment.CenterEnd
                                            else Alignment.CenterStart,
                                        ) {
                                            ImageBubble(
                                                prompt = msg.content,
                                                bitmap = imgBitmap,
                                                maxWidth = bubbleMaxWidth,
                                                failed = imgBitmap == null,
                                                failedText = "图片打不开了",
                                                onClick = { viewerPath = msg.imagePath },
                                                fromUser = msg.isFromUser,
                                                modifier = recordBubbleBounds(rowKey),
                                            )
                                        }
                                    }

                                    // 自定义表情（v0.45.0）：小尺寸、不可点开，与图片气泡区分
                                    MediaKind.STICKER ->
                                        Box(
                                            Modifier.fillMaxWidth(),
                                            contentAlignment = if (msg.isFromUser) Alignment.CenterEnd
                                            else Alignment.CenterStart,
                                        ) {
                                            StickerBubble(
                                                bitmap = rememberLocalBitmap(msg.imagePath),
                                                fromUser = msg.isFromUser,
                                                modifier = recordBubbleBounds(rowKey),
                                            )
                                        }

                                    // 语音条（v0.43.0）：点击播放/暂停；列表默认不显示识别文字，
                                    // 长按菜单里「转文字」才会把文字展开到语音条**下方**（v0.43.1）
                                    MediaKind.AUDIO -> {
                                        val transcriptShown = voiceTranscriptIds.contains(msg.id)
                                        Column(
                                            Modifier.fillMaxWidth(),
                                            horizontalAlignment = if (msg.isFromUser) Alignment.End
                                            else Alignment.Start,
                                        ) {
                                            VoiceBubble(
                                                durationMs = msg.audioDurationMs,
                                                maxWidth = bubbleMaxWidth,
                                                isPlaying = playingVoiceId == msg.id,
                                                isUser = msg.isFromUser,
                                                modifier = recordBubbleBounds(rowKey),
                                                onToggle = {
                                                    if (playingVoiceId == msg.id) {
                                                        voicePlayer.stop()
                                                        playingVoiceId = null
                                                    } else if (voicePlayer.play(msg.audioPath) {
                                                            playingVoiceId = null
                                                        }
                                                    ) {
                                                        playingVoiceId = msg.id
                                                    }
                                                },
                                                onLongPress = { openMessageMenu(msg, rowKey) },
                                            )
                                            // 转文字结果：显示在语音条下方，左右跟随气泡对齐。
                                            // content 就是识别出的文字（同时也是喂给模型的那份），
                                            // 识别失败时为空 → 菜单里压根不给这个入口。
                                            if (transcriptShown && msg.content.isNotBlank()) {
                                                Spacer(Modifier.height(5.dp))
                                                Text(
                                                    msg.content,
                                                    modifier = Modifier.widthIn(max = bubbleMaxWidth),
                                                    fontSize = 13.sp,
                                                    lineHeight = 20.sp,
                                                    color = TextPrimary,
                                                )
                                            }
                                        }
                                    }

                                    // 文件气泡（v0.43.0）
                                    MediaKind.FILE ->
                                        Box(
                                            Modifier.fillMaxWidth(),
                                            contentAlignment = if (msg.isFromUser) Alignment.CenterEnd
                                            else Alignment.CenterStart,
                                        ) {
                                            FileBubble(
                                                name = msg.attachmentName.ifBlank { "文件" },
                                                size = msg.attachmentSize,
                                                hasExtractedText = msg.content.isNotBlank(),
                                                maxWidth = bubbleMaxWidth,
                                                modifier = recordBubbleBounds(rowKey),
                                                onLongPress = { openMessageMenu(msg, rowKey) },
                                            )
                                        }

                                    // 通话记录卡（v0.49.0；v0.60 起分语音/视频）：
                                    // 挂断后落进聊天流，点开看文字转写；
                                    // v0.54.6：**长按也弹消息菜单**（以前只有 clickable，
                                    // 长按完全没反应——用户反馈"长按语音通话条唤起不了菜单"）
                                    MediaKind.CALL -> {
                                        // v0.55.0：整卡重绘（图标 + 方向 + 时长 + 未接通），见 CallRecordCard
                                        // v0.60：内容前缀就是通话类型（v0.58.0 起 saveCall 写的是
                                        // 「视频通话 · …」/「语音通话 · …」），据此把标题与图标一起换掉
                                        val duration = msg.content
                                            .substringAfter("· ", msg.content)
                                            .trim()
                                            .ifBlank { msg.content }
                                        val isVideoCall = msg.content.trimStart().startsWith("视频通话")
                                        Box(
                                            Modifier.fillMaxWidth(),
                                            contentAlignment = if (msg.isFromUser) Alignment.CenterEnd
                                            else Alignment.CenterStart,
                                        ) {
                                            CallRecordCard(
                                                duration = duration,
                                                outgoing = msg.isFromUser,
                                                video = isVideoCall,
                                                maxWidth = bubbleMaxWidth,
                                                modifier = recordBubbleBounds(rowKey),
                                                onClick = {
                                                    callLogSession = msg.attachmentPath.toLongOrNull() ?: 0L
                                                    callLogTitle = msg.content
                                                },
                                                onLongPress = { openMessageMenu(msg, rowKey) },
                                            )
                                        }
                                    }

                                    // 记忆待审系统气泡（v0.82.0）：AI 暂存了新记忆时落的居中提示行，
                                    // 点一下跳「TA 的记忆」页去审批；不进 LLM 历史/提炼/导出。
                                    MediaKind.MEMORY_NOTICE ->
                                        Box(
                                            Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                msg.content,
                                                fontSize = 12.sp,
                                                color = FaintText,
                                                modifier = Modifier
                                                    .padding(vertical = 4.dp)
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null,
                                                    ) { onOpenSettings("memory/$personaId") },
                                            )
                                        }

                                    MediaKind.TEXT -> MessageBubble(
                                        msg,
                                        bubbleMaxWidth,
                                        item.gapBefore,
                                        appearance = chatAppearance,
                                        persona = persona,
                                        userAvatar = userAvatar,
                                        onLongPress = { openMessageMenu(msg, rowKey) },
                                        // v0.89.0：锚点挂在气泡本体（UserBubble/AiBubble）上，
                                        // 不含头像与间距 —— 否则菜单箭头会按整行边缘对齐、离气泡老远
                                        anchorModifier = recordBubbleBounds(rowKey),
                                    )
                                }   // 关闭 when(kind)

                                // v0.54.6：思考过程 —— 长按菜单点「思考过程」后**就地展开在消息下方**
                                // （不再跳底部弹层），点标题行可收起。字号小、颜色淡，不抢正文。
                                if (msg.reasoning.isNotBlank() && reasoningOpenIds.contains(msg.id)) {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                            .padding(
                                                start = if (msg.isFromUser) 0.dp else 2.dp,
                                                end = if (msg.isFromUser) 2.dp else 0.dp,
                                            ),
                                        horizontalAlignment = if (msg.isFromUser) Alignment.End
                                        else Alignment.Start,
                                    ) {
                                        Row(
                                            Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                ) { reasoningOpenIds.remove(msg.id) },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                MIcons.Brain,
                                                contentDescription = null,
                                                tint = FaintText,
                                                modifier = Modifier.size(11.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("思考过程 · 点此收起", fontSize = 10.sp, color = FaintText)
                                        }
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            msg.reasoning,
                                            modifier = Modifier.widthIn(max = bubbleMaxWidth),
                                            fontSize = 11.sp,
                                            lineHeight = 17.sp,
                                            color = MutedText,
                                        )
                                    }
                                }

                                }   // 关闭"多选留白"内层 Column（v0.54.3 建 Box，v0.54.7 改 Column）
                                // v0.54.3：多选态 —— 整行可点切换选中，左侧画勾选圈
                                if (multiSelect) {
                                    Box(
                                        Modifier
                                            .matchParentSize()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) { if (!selectedIds.remove(msg.id)) selectedIds.add(msg.id) },
                                    )
                                    SelectionMark(
                                        selected = selectedIds.contains(msg.id),
                                        modifier = Modifier.align(Alignment.CenterStart),
                                    )
                                }
                                }   // 关闭 item Box
                                }   // 关闭 is ChatListItem.Msg 分支块
                            }       // 关闭 when (val item = chatItems[idx])
                        }           // 关闭 items 内容 lambda

                    // 生成中的三点气泡（v0.41.4）：AI 在后台写完再整段上屏，
                    // 期间只挂一个只含三点动效的空 AI 气泡（不再逐字吐字）
                    if (uiState.generating) {
                        item(key = "typingTail") {
                            // v0.55.0：这颗气泡进出会挤动整列，补上重排弹簧（同样不带淡入淡出，
                            // 免得跟页面滑入动画叠在一起）
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null),
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                    // 流式占位气泡：走和真实 AI 气泡同一套配色，否则流式期间
                                    // 突然"换个色再定下来"会闪一下（v0.80.0 外观可自定义后更明显）
                                    val phTheme = if (com.menghuan.app.ui.theme.IsDarkTheme) {
                                        chatAppearance.dark
                                    } else {
                                        chatAppearance.light
                                    }
                                    AiBubble(
                                        content = "",
                                        maxWidth = bubbleMaxWidth,
                                        resolved = com.menghuan.app.ui.theme.resolveBubble(
                                            style = phTheme.other,
                                            isMine = false,
                                            appearance = chatAppearance,
                                        ),
                                        onLongPress = {},
                                    )
                                }
                            }
                        }
                    }

                    // AI 任务建议卡片：提炼引擎产出，确认「添加任务」后消失（API 未配置时引擎不产出，自然不出现）
                    if (taskSuggestions.isNotEmpty()) {
                        item(key = "taskSuggestions") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                taskSuggestions.forEach { suggestion ->
                                    TaskSuggestionCard(
                                        suggestion = suggestion,
                                        onAdd = { vm.addSuggestedTask(suggestion) },
                                    )
                                }
                            }
                        }
                    }

                    // 生图进行中的动画卡（v0.38.1）：像 ChatGPT 一样让"她在画了"肉眼可见
                    if (uiState.isGeneratingImage) {
                        item(key = "imageGenerating") {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = null),
                            ) {
                                ImageGeneratingCard(
                                    prompt = uiState.imagePrompt,
                                    maxWidth = bubbleMaxWidth,
                                    modifier = Modifier.align(Alignment.CenterStart),
                                )
                            }
                        }
                    }
                }

                // 回底按钮：上翻历史时浮出
                // 注意：此处必须写全限定名——Box 作用域里裸名 AnimatedVisibility 会被解析成
                // ColumnScope 扩展而编译失败（Kotlin 隐式接收者优先于顶层函数）
                androidx.compose.animation.AnimatedVisibility(
                    visible = !nearBottom,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    enter = scaleIn(tween(220)) + fadeIn(tween(220)),
                    exit = scaleOut(tween(160)) + fadeOut(tween(160)),
                ) {
                    val scope = rememberCoroutineScope()
                    NeuCircleButton(
                        onClick = {
                            scope.launch { listState.animateScrollToItem(chatItems.lastIndex.coerceAtLeast(0)) }
                        },
                        accent = true,
                        size = 40.dp,
                    ) {
                        Text("↓", color = AccentInk, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 请求失败/提示条：按 errorKind 出动作——网络类给重试，无障碍给"去开启"，
            // API 配置给"去配置"，纯提示不放按钮（重试会重新生成回复，可能重复执行手机操作）。
            // 进出场走展开/收起+淡入淡出（错误条是插入式内容，硬弹出会跳一下）。
            // AnimatedVisibility 退出期间 error 已置 null，所以记住最后一条内容供退出动画渲染。
            var lastErrorShown by remember { mutableStateOf<Pair<String, String>?>(null) }
            uiState.error?.let { lastErrorShown = it to uiState.errorKind }
            AnimatedVisibility(
                visible = uiState.error != null,
                enter = fadeIn(tween(200)) + expandVertically(tween(260, easing = EaseOutExpo)),
                exit = fadeOut(tween(160)) + shrinkVertically(tween(200)),
            ) {
                lastErrorShown?.let { (message, kind) ->
                    ChatErrorBar(
                        message = message,
                        kind = kind,
                        onRetry = { vm.retry() },
                        onResend = { vm.resendPending() },
                        onOpenApi = { onOpenSettings(SettingsRoutes.CHAT_MODEL) },
                    )
                }
            }

            // 手机执行卡（v0.37.0）：[操作手机:…] 触发后日志实时滚动；
            // Agent 会切走前台去操作别的应用，悬浮「■ 停止」始终可掐，回来看卡片即知进展。
            // 同样记住最后状态供收起动画渲染（Idle 时 agentRun 已被置 null）。
            var lastAgentRun by remember { mutableStateOf<AgentRunUiState?>(null) }
            uiState.agentRun?.let { lastAgentRun = it }
            AnimatedVisibility(
                visible = uiState.agentRun != null,
                enter = fadeIn(tween(220)) + expandVertically(tween(320, easing = EaseOutExpo)),
                exit = fadeOut(tween(180)) + shrinkVertically(tween(240)),
            ) {
                lastAgentRun?.let { run ->
                    AgentRunCard(
                        run = run,
                        onStop = { AgentRunner.stop() },
                        onDismiss = { vm.dismissAgentCard() },
                    )
                }
            }

            // 输入栏：[语音/键盘键] [白底圆角输入框 或 按住说话] [表情键] [加号键]
            //
            // 底部避让（**v0.44.0 重做**）：全部交给 [rememberBottomBarInset]。
            // 旧版在这里堆了「单调收敛 + 切换静默期 + 方向性动画」三块补丁，
            // 根因只有一个 —— `navigationBars` 被当成实时量，键盘收起时会出现
            // `ime=0 && navBar=0` 的空档帧。新实现让导航条走**基线**、形态互斥由
            // `followKeyboard` 显式表达，补丁全部删掉。详见 KeyboardInsets.kt 的注释。
            //
            // 与键盘的关系（微信行为）：
            // - 键盘弹起时面板收起；点表情/加号时先收键盘再展开面板，两者不会同屏
            // - 面板占位与键盘同一块区域，所以输入栏位置在二者切换时是连贯的
            //
            // ⚠️ 面板高度**绝不能**算进底部避让：面板是输入栏的下一个兄弟节点、
            // 自己占 PanelHeight。算进来输入栏会被顶到屏幕中间（v0.43.0 首版截图实测）。
            //
            // ⚠️ 这个调用点必须紧贴输入栏、不能提到 ChatScreen 顶层：
            // 它内部读的是逐帧变化的 inset，提到顶层会让整个聊天页每帧重组。
            val bottomBarInset = rememberBottomBarInset(
                followKeyboard = panel == 0 && !voiceMode,
            )

            // 待发送附件条（v0.56.6）：挂在**输入栏上方** ——
            // 相册 / 文件选中后先停在这里，用户写完文字一起发，或直接点发送键。
            androidx.compose.animation.AnimatedVisibility(
                visible = pendingAttachment != null,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(120)),
            ) {
                val pending = pendingAttachment
                if (pending != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                            .neuSurface(14.dp, color = NeuTrack, inset = true)
                            .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (pending.isImage) {
                            val thumb = rememberLocalBitmap(pending.path)
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Icon(
                                    MIcons.ImageIcon,
                                    contentDescription = null,
                                    tint = MutedText,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            Icon(
                                MIcons.FileDoc,
                                contentDescription = null,
                                tint = TextPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (pending.isImage) "图片" else pending.name,
                                fontSize = 13.sp,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        NeuFlatIconButton(onClick = { pendingAttachment = null }) {
                            Icon(
                                MIcons.Close,
                                contentDescription = "移除待发送内容",
                                tint = MutedText,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = bottomBarInset)
                    .background(Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左：语音键（v0.43.1 改成**开关**）
                // 点亮 = 切到语音态（中间变"按住 说话"），再点 = 回到打字态。
                // 无外圈：直接画声波符号，颜色调淡（用户要求"不要外面那个圈"）。
                NeuFlatIconButton(
                    onClick = {
                        if (voiceMode) {
                            // 回打字态：要求键盘立刻回来（用户点的是"键盘"图标）
                            switchOutputMode(nextPanel = 0, nextVoice = false, showKeyboard = true)
                        } else {
                            // v0.59.0：识别引擎现在有离线/在线两条路，**只有选离线时才要求模型已下载**。
                            // 引擎存在 DataStore 里，读它要挂起，所以这一下的判定挪进协程里做。
                            scope.launch {
                                val online = com.menghuan.app.data.SpeechToText.isOnlineEngine(context)
                                if (!online && !com.menghuan.app.data.AsrModelManager.isReady(context)) {
                                    // 模型没下载就进语音态，用户按住说完只会得到一条空转写——
                                    // 与其让他录完才发现，不如这一下就把下载入口摆出来。
                                    com.menghuan.app.ui.common.AppToast.fail("离线识别模型未下载")
                                    onOpenSettings(SettingsRoutes.ASR_MODEL)
                                } else {
                                    // 切到语音态：收键盘、收面板（三者互斥），不要键盘
                                    switchOutputMode(nextPanel = 0, nextVoice = true, showKeyboard = false)
                                }
                            }
                        }
                    },
                    enabled = !uiState.isStreaming,
                ) {
                    // 语音态时图标翻成键盘（再点一下回打字），与微信一致。
                    // v0.45.0：键盘符号颜色从纯黑（NeuAccent）压到中灰（TextSecondary），
                    // 用户要求"颜色更淡点"——语音态本身已由"按住说话"条表达，无需再靠纯黑点亮。
                    Icon(
                        if (voiceMode) MIcons.Keyboard else MIcons.VoiceWave,
                        contentDescription = if (voiceMode) "切换到键盘" else "切换到语音",
                        tint = if (voiceMode) TextSecondary else MutedText,
                        modifier = Modifier.size(23.dp),
                    )
                }

                Spacer(Modifier.width(6.dp))

                // 中：语音态 = 按住说话条；打字态 = 白底圆角输入框
                Box(Modifier.weight(1f)) {
                    if (voiceMode) {
                        VoiceHoldBar(
                            recording = recording,
                            enabled = !uiState.isStreaming,
                            bars = waveBars,
                            onStart = {
                                val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.RECORD_AUDIO,
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (!hasMic) {
                                    micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                                } else if (recorder.start()) {
                                    // 这一段是全新的一次录音：先把上一段的波形/计时清干净
                                    // （清在**开始时**而不是结束时 —— 结束时清会让蒙层的收起
                                    // 动画那 140ms 里画出一个空气泡，观感是"闪一下"）
                                    waveBars = emptyList()
                                    recordMs = 0L
                                    recording = true
                                } else {
                                    // 带上真实原因（v0.43.1）：静默的"启动失败"无从定位，
                                    // 真机上分不清是权限、被占用还是 ROM 不认录音参数。
                                    val why = recorder.lastError
                                    com.menghuan.app.ui.common.AppToast.fail(
                                        if (why.isNullOrBlank()) "录音启动失败，可能被其他应用占用"
                                        else "录音启动失败：$why"
                                    )
                                }
                            },
                            onArmChange = { recordArm = it },
                            onRelease = { arm ->
                                recording = false
                                recordArm = RecordArm.NONE
                                when (arm) {
                                    // 滑到「取消」松手：丢掉这段录音
                                    RecordArm.CANCEL -> recorder.cancel()
                                    // 滑到「转文字」松手：录完先识别；识别到了只发文字（语音文件删掉）
                                    RecordArm.TRANSCRIBE -> {
                                        val result = recorder.stop()
                                        if (result != null) {
                                            val (file, duration) = result
                                            scope.launch {
                                                val text = com.menghuan.app.data.SpeechToText.transcribe(
                                                    context = context,
                                                    audioPath = file.absolutePath,
                                                ).orEmpty()
                                                if (text.isBlank()) {
                                                    // 没听清时**不丢内容**：照发语音，并说清为什么不是文字。
                                                    // v0.59.0：原因现在是有内容的一句话（在线那一路会带回
                                                    // 服务端原话：Key 错 / 这家没这个接口 / 超时），
                                                    // 别再用一句"没听清"把配置问题糊掉。
                                                    val why = com.menghuan.app.data.SpeechToText.lastFailure
                                                    com.menghuan.app.ui.common.AppToast.fail(
                                                        if (why.isNullOrBlank()) "没听清，已发语音"
                                                        else "识别失败：$why（已改发语音）"
                                                    )
                                                    vm.sendVoice(file.absolutePath, duration, "")
                                                } else {
                                                    vm.sendVoiceAsText(text)
                                                    runCatching { file.delete() }
                                                }
                                            }
                                        }
                                    }
                                    // 正常松手：发语音条（原逻辑不动）
                                    RecordArm.NONE -> {
                                        val result = recorder.stop()
                                        if (result != null) {
                                            val (file, duration) = result
                                            scope.launch {
                                                // 录完再转写（录音期间绝不碰识别器：会抢麦克风）。
                                                // 识别不出来就发空文字、语音条照发（原因在 SpeechToText.lastFailure）。
                                                val text = com.menghuan.app.data.SpeechToText.transcribe(
                                                    context = context,
                                                    audioPath = file.absolutePath,
                                                ) ?: ""
                                                if (text.isBlank()) {
                                                    // 与"滑到转文字"那条同样的口径：配置问题要说得出原因
                                                    val why = com.menghuan.app.data.SpeechToText.lastFailure
                                                    if (!why.isNullOrBlank()) {
                                                        com.menghuan.app.ui.common.AppToast.fail("识别失败：$why")
                                                    }
                                                }
                                                vm.sendVoice(file.absolutePath, duration, text)
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    } else {
                        NeuChatInput(
                            value = uiState.input,
                            onValueChange = vm::onInputChange,
                            // 用户点输入框 = 要打字：收面板、退语音态（键盘由系统唤起）。
                            // 必须记静默期，否则"键盘弹起 + 面板还在"的那几帧会空一大片。
                            onFocus = {
                                if (panel != 0 || voiceMode) {
                                    switchOutputMode(nextPanel = 0, nextVoice = false, showKeyboard = true)
                                }
                            },
                        )
                    }
                }

                // 右：有内容（文字或待发送附件）时给发送键；否则表情 + 加号（无外圈，颜色淡）
                if ((uiState.input.isNotBlank() || pendingAttachment != null) && !voiceMode) {
                    Spacer(Modifier.width(6.dp))
                    NeuFlatIconButton(
                        onClick = {
                            // 有待发送附件就先把它发出去（当前文字当说明），否则发文字
                            if (pendingAttachment != null) sendPendingAttachment() else vm.send()
                            keyboardController?.hide()
                        },
                        enabled = !uiState.isStreaming,
                    ) {
                        Icon(
                            MIcons.Plane,
                            contentDescription = "发送",
                            tint = NeuAccent,
                            modifier = Modifier.size(23.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.width(6.dp))
                    NeuFlatIconButton(
                        onClick = {
                            if (panel == 1) {
                                // 再点一次表情 = 回打字态，键盘要回来
                                switchOutputMode(nextPanel = 0, nextVoice = false, showKeyboard = true)
                            } else {
                                switchOutputMode(nextPanel = 1, nextVoice = false, showKeyboard = false)
                            }
                        },
                        enabled = !uiState.isStreaming,
                    ) {
                        Icon(
                            MIcons.Smile,
                            contentDescription = "表情",
                            tint = if (panel == 1) NeuAccent else MutedText,
                            modifier = Modifier.size(23.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    NeuFlatIconButton(
                        onClick = {
                            if (panel == 2) {
                                switchOutputMode(nextPanel = 0, nextVoice = false, showKeyboard = true)
                            } else {
                                switchOutputMode(nextPanel = 2, nextVoice = false, showKeyboard = false)
                            }
                        },
                        enabled = !uiState.isStreaming,
                    ) {
                        Icon(
                            MIcons.Plus,
                            contentDescription = "更多",
                            tint = if (panel == 2) NeuAccent else MutedText,
                            modifier = Modifier.size(23.dp),
                        )
                    }
                }
            }

            // 表情 / 加号面板（v0.43.0）：输入栏的**下一个兄弟**，展开时把聊天区挤上去。
            //
            // 面板自身负责底部系统栏避让（`navigationBarsPadding`）：输入栏的 inset
            // 只管它自己那一行，不管面板。v0.43.0 首版把 panelPx 算进输入栏 inset，
            // 结果输入栏被抬到屏幕中间、面板贴在底部——截图一目了然。
            // 面板展开时键盘已被收起，所以这里只需导航条高度。
            AnimatedVisibility(
                visible = panel != 0,
                enter = expandVertically(tween(240, easing = EaseOutExpo)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(120)),
            ) {
                Box(Modifier.navigationBarsPadding()) {
                    if (panel == 1) {
                        EmojiPanel(
                            stickers = stickers,
                            onEmoji = { vm.onInputChange(uiState.input + it) },
                            onSticker = { path -> vm.sendSticker(path) },
                            onAddSticker = { stickerPicker.launch("image/*") },
                            onDeleteSticker = { s ->
                                scope.launch {
                                    appContainer.db.stickerDao().delete(s.id)
                                    com.menghuan.app.data.StickerStore.deleteFile(s.path)
                                }
                            },
                        )
                    } else {
                        PlusPanel(
                            onAlbum = { panel = 0; imagePicker.launch("image/*") },
                            onCamera = { panel = 0; showCamera = true },
                            onFile = { panel = 0; filePicker.launch(arrayOf("*/*")) },
                            // v0.47.0：通话入口收进加号面板第四格（收面板 → 进通话页）
                            onCall = { panel = 0; onOpenCall() },
                            // v0.58.0：视频通话。这个人设没配通话形象时不直接进
                            //（进去了也只能看头像，用户会以为坏了），先问一句要不要去添加。
                            onVideoCall = {
                                panel = 0
                                if (hasCallVisual) onOpenVideoCall() else showNoVisualSheet = true
                            },
                        )
                    }
                }
            }
        }
    }

    // 录音蒙层（v0.53.4）：按住说话期间盖一层，形态照微信「按住说话」。
    // v0.55.0：进出场走淡入淡出（原来"啪"地出现/消失）—— 蒙层是盖在聊天内容上的一层，
    // 有渐变才知道它是"浮上来"的，而不是把下面的内容换掉了。
    AnimatedVisibility(
        visible = recording,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(140)),
    ) {
        VoiceRecordingOverlay(
            durationMs = recordMs,
            bars = waveBars,
            armed = recordArm,
        )
    }

    // App 内自绘拍摄界面（v0.43.0）：全屏覆盖，拍完把 JPEG 路径当普通图片发出。
    // v0.45.0：从聊天页进入相机的过渡 = 淡入（叠一层极轻的 0.985→1 缩放，
    // 对齐全局进场语言）；退出淡出。用户明确"拍摄页动画指的是进入相机之前的过渡动画"。
    AnimatedVisibility(
        visible = showCamera,
        enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.985f, animationSpec = tween(340)),
        exit = fadeOut(tween(200)),
    ) {
        CameraCaptureScreen(
            onCaptured = { path ->
                showCamera = false
                vm.sendImage(path, uiState.input)
            },
            onCancel = { showCamera = false },
        )
    }

    // 长按头像 = 切换"本会话"的 AI 接口（v0.36.4 按会话独立；不影响其他会话和全局）
    // v0.56.6：弹层里不再有「跟随全局」—— 点哪条就用哪条，当前生效的那条直接打勾
    if (showApiSwitch) {
        ApiSwitchSheet(
            profiles = apiProfiles,
            activeId = apiOverrideId ?: globalProfileId,
            onSelect = { id ->
                vm.switchApiProfile(id)
                showApiSwitch = false
            },
            onDismiss = { showApiSwitch = false },
        )
    }


    // v0.53.5：人设编辑走独立路由页，这里不再挂弹层

    // v0.49.0：通话记录卡点开 → 本次通话文字转写
    callLogSession?.let { sessionId ->
        CallLogSheet(
            sessionId = sessionId,
            title = callLogTitle.ifBlank { "语音通话" },
            personaName = persona?.name ?: "对方",
            onDismiss = { callLogSession = null },
        )
    }

    // 全屏图片查看（捏合缩放 / 双击放大 / 单击关闭）
    viewerPath?.let { path ->
        ImageViewerOverlay(path = path, onDismiss = { viewerPath = null })
    }

    // v0.54.3：多选操作栏（贴着底部覆盖输入栏 —— 微信式）。整行铺底、吃掉触摸，
    // 避免点击从按钮缝隙漏到底下仍可用的输入栏。
    if (multiSelect) {
        MultiSelectBar(
            count = selectedIds.size,
            onCancel = {
                multiSelect = false
                selectedIds.clear()
            },
            onCopy = {
                val text = uiState.messages.filter { it.id in selectedIds }
                    .map { it.content }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                if (text.isBlank()) {
                    com.menghuan.app.ui.common.AppToast.show("没有可复制的文字")
                } else {
                    copyToClipboard(context, text)
                }
                multiSelect = false
                selectedIds.clear()
            },
            onForward = { showForwardSheet = true },
            onDelete = {
                vm.deleteMessages(uiState.messages.filter { it.id in selectedIds })
                multiSelect = false
                selectedIds.clear()
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    }

    messageMenu?.let { message ->
        MessageActionMenu(
            message = message,
            anchor = messageMenuAnchor,
            canDelete = message.id > 0 && !uiState.isStreaming,
            onCopy = {
                copyToClipboard(context, message.content)
                messageMenu = null
                messageMenuAnchor = null
            },
            onDelete = {
                vm.deleteMessage(message)
                messageMenu = null
                messageMenuAnchor = null
            },
            // v0.54.3：进入多选（当前这条默认已选）
            onMultiSelect = {
                selectedIds.clear()
                if (message.id > 0) selectedIds.add(message.id)
                multiSelect = true
                messageMenu = null
                messageMenuAnchor = null
            },
            // 思考链（v0.41.4；v0.54.6 改成**就地展开**）：只有模型真吐过推理内容的消息才给这个入口
            onToggleReasoning = if (message.reasoning.isNotBlank()) {
                {
                    if (!reasoningOpenIds.remove(message.id)) reasoningOpenIds.add(message.id)
                    messageMenu = null
                    messageMenuAnchor = null
                }
            } else null,
            reasoningShown = reasoningOpenIds.contains(message.id),
            // 语音条转文字开关（v0.43.1）：只有真识别出文字的语音条才给
            onToggleTranscript = if (message.mediaKind == MediaKind.AUDIO.name &&
                message.content.isNotBlank()
            ) {
                {
                    if (!voiceTranscriptIds.remove(message.id)) voiceTranscriptIds.add(message.id)
                    messageMenu = null
                    messageMenuAnchor = null
                }
            } else null,
            transcriptShown = voiceTranscriptIds.contains(message.id),
            // v0.59.0：朗读这一条（有文字才给这一格）。正在念的同一条再点 = 停。
            onReadAloud = if (message.content.isNotBlank()) {
                {
                    vm.readAloud(message.id, message.content)
                    messageMenu = null
                    messageMenuAnchor = null
                }
            } else null,
            reading = uiState.readingId == message.id,
            onDismiss = {
                messageMenu = null
                messageMenuAnchor = null
            },
        )
    }

    // v0.54.3：多选「转发」—— 选一个目标会话，把选中消息复制过去
    if (showForwardSheet) {
        NeuSheetScaffold(title = "转发到", onDismiss = { showForwardSheet = false }) {
            Spacer(Modifier.height(8.dp))
            val targets = allPersonas.filter { it.id != personaId }
            if (targets.isEmpty()) {
                Text("还没有其它会话", fontSize = 13.sp, color = MutedText, modifier = Modifier.padding(14.dp))
            } else {
                targets.forEach { p ->
                    NeuSheetRow(
                        icon = MIcons.Person,
                        title = p.name,
                        subtitle = p.title,
                        onClick = {
                            vm.forwardMessages(uiState.messages.filter { it.id in selectedIds }, p.id)
                            com.menghuan.app.ui.common.AppToast.ok("已转发给${p.name}")
                            showForwardSheet = false
                            multiSelect = false
                            selectedIds.clear()
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }

    // 思考链已在 v0.54.6 改成**就地展开在消息下方**（见上方 reasoningOpenIds），不再有弹层

}

/**
 * 空会话提示（v0.97.0，审查 U9）：新会话 / 消息清空后消息区的居中占位。
 *
 * 只用一句**事实短语** + 一颗淡图标，不写"开始你们的对话吧"这类客套（§四 纪律），
 * 也不加任何按钮 —— 输入栏就在下面，用户要的动作本来就在手边。
 */
@Composable
private fun EmptyChatHint(name: String?) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(MIcons.ChatBubble, contentDescription = null, tint = FaintText, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            if (name.isNullOrBlank()) "还没有聊过" else "和 $name 还没聊过",
            fontSize = 14.sp,
            color = MutedText,
        )
    }
}

/** 错误条行（从 ChatScreen 抽出供动画包装复用）：文案 + 按错误种类给的动作按钮 */
@Composable
private fun ChatErrorBar(
    message: String,
    kind: String,
    onRetry: () -> Unit,
    onResend: () -> Unit,
    onOpenApi: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .neuSurface(16.dp, color = MaterialTheme.colorScheme.background, elevation = 4.dp, inset = true)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            message,
            modifier = Modifier.weight(1f),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = NeuDanger,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        when (kind) {
            "retry" -> {
                Spacer(Modifier.width(10.dp))
                NeuTextButton("重试", onClick = onRetry)
            }
            // v0.97.0（U13）：用户消息没落上库 —— 重跑生成是错的（这轮不在历史里），
            // 要给的是"把这条消息重新发一遍"。
            "resend" -> {
                Spacer(Modifier.width(10.dp))
                NeuTextButton("重发", onClick = onResend)
            }
            "accessibility" -> {
                Spacer(Modifier.width(10.dp))
                NeuTextButton("去开启", onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                })
            }
            "api" -> {
                Spacer(Modifier.width(10.dp))
                NeuTextButton("去配置", onClick = onOpenApi)
            }
        }
    }
}

/**
 * 长按气泡弹出的操作菜单（v0.54.3 建；v0.54.6 瘦身）：贴着气泡的横向菜单。
 * 用户消息靠右、AI 消息靠左；气泡上方放不下就翻到下方；点空白 / 返回即收起。
 *
 * **v0.54.6（用户点名）**：
 * - **只显示图标，不再显示文字**（原来图标 + 文字两行，整体又高又宽）；
 * - 整体**缩小**（图标 17dp、内边距收紧），不再"比聊天记录还大"；
 * - 「思考过程」由"打开底部弹层"改成**就地展开/收起**（toggle），展开位置在消息下方。
 *
 * 条目按需出现：转文字（仅识别成功的语音条）/ 思考过程（仅推理模型的消息）/
 * 复制 / 多选 / 删除（已落库消息才可删）。
 *
 * **v0.55.0（手势反馈 / 弹出动画 / 视觉层级）**：
 * - **弹出**：整块从 0.88 弹簧放大 + 120ms 淡入（原来没有任何动画，凭空出现）；
 * - **分组**：「查看类开关」｜「复制 / 多选」｜「删除」，组间一条 1dp 竖发丝线；
 * - **层级**：删除走危险色，开关处于展开态时整格铺凹陷灰底 + 图标上强调色（再点即收起）；
 * - **反馈**：菜单项 36dp 方形热区 + 按压缩放，点中补一记轻震动。
 */
@Composable
private fun MessageActionMenu(
    message: ChatMessage,
    anchor: Rect?,
    canDelete: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onMultiSelect: () -> Unit,
    onToggleReasoning: (() -> Unit)?,
    reasoningShown: Boolean,
    onToggleTranscript: (() -> Unit)?,
    transcriptShown: Boolean,
    /** v0.59.0：朗读这一条（null = 这条没文字可念，不出现这一格） */
    onReadAloud: (() -> Unit)? = null,
    reading: Boolean = false,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    // v0.55.0：菜单项分三组 —— 「查看类开关」｜「复制 / 多选」｜「删除」，
    // 组间用一条 1dp 竖发丝线分隔（全应用唯一的分区手段），删除单独走危险色。
    // 开关类（转文字 / 思考过程）当前处于展开态时整格上强调色：一眼看出"再点是收起"。
    val entries = buildList<MessageMenuEntry> {
        if (onToggleTranscript != null) {
            add(
                MessageMenuEntry.Action(
                    MIcons.FileText,
                    if (transcriptShown) "收起文字" else "转文字",
                    onToggleTranscript,
                    active = transcriptShown,
                ),
            )
        }
        if (onToggleReasoning != null) {
            add(
                MessageMenuEntry.Action(
                    MIcons.Brain,
                    if (reasoningShown) "收起思考" else "思考过程",
                    onToggleReasoning,
                    active = reasoningShown,
                ),
            )
        }
        if (onReadAloud != null) {
            // 正在念的这条，这一格就是「停止朗读」——不用另找停止按钮
            add(
                MessageMenuEntry.Action(
                    MIcons.VoiceWave,
                    if (reading) "停止朗读" else "朗读",
                    onReadAloud,
                    active = reading,
                ),
            )
        }
        if (size > 0) add(MessageMenuEntry.Split)
        add(MessageMenuEntry.Action(MIcons.Copy, "复制", onCopy))
        add(MessageMenuEntry.Action(MIcons.SelectAll, "多选", onMultiSelect))
        if (canDelete) {
            add(MessageMenuEntry.Split)
            add(MessageMenuEntry.Action(MIcons.Delete, "删除", onDelete, danger = true))
        }
    }
    // 弹出动画：整块从 0.88 弹到 1（弹簧带一点回弹）+ 120ms 淡入。
    //
    // ⚠️ 刻意**不用 AnimatedVisibility**：它是"可见才参与布局"，首帧内容尺寸为 0，
    // PopupPositionProvider 拿到的 popupContentSize 就是 0，菜单会先闪在错的位置再跳回来。
    // 用 graphicsLayer 做缩放/淡入则**布局尺寸恒定**，定位从第一帧就是准的，动画纯视觉。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val popScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.88f,
        animationSpec = NeuSnappySpring,
        label = "menuPop",
    )
    val popAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(120),
        label = "menuAlpha",
    )
    // 位置策略（v0.56.17，用户点名）：**始终贴在气泡正上方**，不再按气泡位置翻到下方。
    // 气泡贴着屏幕顶部时靠 coerceIn 兜住（宁可压住气泡，也不翻面——翻面会让菜单位置
    // 随消息位置忽上忽下，用户要的是"固定在上方"）。
    //
    // v0.83.0（用户点名"箭头挨到气泡上面"）：尾巴尖与气泡顶边**零间距**。
    // 原来这里统一隔着 6dp，加上锚点记的是整行（换人那 12dp 空档也算进去了），
    // 实测箭头离气泡能有 6~18dp，看着像悬在半空。现在锚点下沉到气泡自己那一层，
    // 纵向再减 0 —— 尖端正好压在气泡上沿；只有贴屏幕边缘时还按 [edge] 让开。
    //
    // ⚠️ v0.89.0（用户反馈"箭头不贴合气泡"）：横向**必须让箭头对准气泡中心**。
    // [MenuTail] 恒在菜单底部正中，所以让"菜单横向中心"落在"气泡横向中心"上，
    // 箭头就自然正对气泡。原来把菜单边缘对到行边缘（`rect.right - width` / `rect.left`），
    // 而锚点当时记的是**整行**（含 34dp 头像 + 8dp 间距），短气泡（「好」「嗯」）的
    // 箭头能偏出气泡中心 100dp+。现在锚点就是气泡自己的矩形，这里按中心对齐。
    // 靠边时用 [edge] 夹住，允许箭头相对气泡略微偏移（宁可偏移也不能把菜单捅出屏幕）。
    Popup(
        // v0.89.0：定位只依赖 anchor（气泡矩形），与"是不是自己发的"无关，key 收成 anchor
        popupPositionProvider = remember(anchor) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    // 离屏幕边缘的最小让开量（横纵都用它夹一下，别贴死在边上）
                    val edge = with(density) { 6.dp.roundToPx() }
                    // 尾巴尖到气泡顶边的距离：0 = 正好挨上
                    val tailGap = 0
                    val maxX = (windowSize.width - popupContentSize.width - edge).coerceAtLeast(edge)
                    val maxY = (windowSize.height - popupContentSize.height - edge).coerceAtLeast(edge)
                    val rect = anchor
                    // 让菜单横向中心 = 气泡横向中心（箭头在菜单底部正中 → 正对气泡中心）
                    val bubbleCenterX = rect?.let { (it.left + it.right) / 2f }
                    val x = (if (bubbleCenterX == null) {
                        windowSize.width / 2f - popupContentSize.width / 2f
                    } else {
                        bubbleCenterX - popupContentSize.width / 2f
                    }).toInt().coerceIn(edge, maxX)
                    val y = ((rect?.top?.toInt() ?: windowSize.height / 2) - popupContentSize.height - tailGap)
                        .coerceIn(edge, maxY)
                    return IntOffset(x, y)
                }
            }
        },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        // v0.56.17：**浅灰底气泡菜单**——固定浅灰（不随主题，深浅两模式都成立），
        // 底下恒伸出一枚小尾巴指向这条气泡（尾巴靠气泡那一侧）。
        Column(
            Modifier.graphicsLayer {
                scaleX = popScale
                scaleY = popScale
                alpha = popAlpha
            },
        ) {
            Row(
                Modifier
                    .background(MessageMenuBg, RoundedCornerShape(14.dp))
                    .border(1.dp, MessageMenuHairline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEach { entry ->
                    when (entry) {
                        MessageMenuEntry.Split -> Box(
                            Modifier
                                .padding(horizontal = 3.dp)
                                .width(1.dp)
                                .height(18.dp)
                                .background(Color.Black.copy(alpha = 0.12f)),
                        )

                        is MessageMenuEntry.Action -> MessageMenuButton(
                            icon = entry.icon,
                            label = entry.label,
                            active = entry.active,
                            danger = entry.danger,
                            onClick = {
                                // 点任一菜单项补一记轻震动：菜单是"浮"在内容上的层，
                                // 给一点手感才知道这一下点中了（v0.54.7 的长按震动是呼出，这是确认）
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                entry.onClick()
                            },
                        )
                    }
                }
            }
            // v0.57（用户点名）：箭头改到菜单**底部正中**、尖端朝下指向气泡（原来靠气泡那一侧），
            // 并放大一圈，让"这块浮层是从下面那条消息上冒出来的"一眼可见。
            // offset(-1dp) 让箭头顶部压住 Row 的 1dp 描边，接缝处不会留一道横线。
            MenuTail(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .offset(y = (-1).dp),
            )
        }
    }
}

/** 长按菜单的固定浅灰底：不随主题翻色，深浅两模式下这个底都成立 */
private val MessageMenuBg = Color(0xFFF2F3F5)

/** 浅灰底菜单的发丝描边（固定黑 8%：浅灰底在深色模式下同样成立，不能用主题令牌） */
private val MessageMenuHairline = Color(0x1A000000)

/**
 * 灰底气泡菜单的下指箭头：恒在菜单**底部正中**、尖端朝下指向气泡（[modifier] 由调用处给对齐）。
 *
 * v0.57（用户点名）：从"靠气泡那一侧"改到**底部正中**，尺寸放大一圈（16×7 → 24×12）让箭头读得出来。
 *
 * ⚠️ 不要在 `.width().height()` **之后**再叠 `.padding()`：旧写法那样写会把内容宽度挤成 0，
 * 箭头退化成一条看不见的线。要留白就在外层用 Spacer，别在 Canvas 的尺寸后面加 padding。
 */
@Composable
private fun MenuTail(modifier: Modifier) {
    Canvas(
        modifier
            .width(24.dp)
            .height(12.dp),
    ) {
        val path = Path().apply {
            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height)
            close()
        }
        // 先描边再填充：顶边（与菜单相接那一条）被填充盖掉，只留两条斜边的外半像素，
        // 与菜单的描边视觉连续。
        drawPath(path, MessageMenuHairline, style = Stroke(width = 1.dp.toPx()))
        drawPath(path, MessageMenuBg)
    }
}

/** 长按菜单里的一项：要么是一条分割线，要么是一个动作 */
private sealed interface MessageMenuEntry {
    data class Action(
        val icon: ImageVector,
        val label: String,
        val onClick: () -> Unit,
        /** 当前处于"已展开"态（再点是收起） */
        val active: Boolean = false,
        /** 破坏性动作：走危险色，与其它项拉开层级 */
        val danger: Boolean = false,
    ) : MessageMenuEntry

    /** 组间的竖发丝线（不用 object：项目 Kotlin 版本未开 data object） */
    object Split : MessageMenuEntry
}

/**
 * 长按菜单里的图标按钮（v0.54.6 起的"只留图标"形态保留）。
 *
 * v0.55.0：36dp 方形热区 + 按压缩放（[rememberNeuPressState]，与全应用一致），
 * 激活态铺一层底 —— 不再是"全都一个颜色的一排图标"，层级看得出主次。
 *
 * v0.56.15：底换成固定的深灰（[MessageMenuBg]），图标色**不再用主题令牌**——
 * 深灰底不随主题翻，字色/图标色也必须固定（强调色黑在深灰上不可见）。
 *
 * v0.56.17：底改**浅灰**，前景色随之从"浅色系"换成"深色系"（普通 = 近黑、激活 = 加深的品牌绿、
 * 删除 = 危险红）——同一条规矩：固定底色上的前景色必须跟着底一起定死。
 */
@Composable
private fun MessageMenuButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    danger: Boolean,
    onClick: () -> Unit,
) {
    val press = rememberNeuPressState()
    val tint = when {
        danger -> Color(0xFFF24E1E)
        active -> Color(0xFF00A15F)
        else -> Color(0xFF1A1A1A)
    }
    Box(
        Modifier
            .size(36.dp)
            .scale(press.scale)
            .clip(RoundedCornerShape(10.dp))
            .then(if (active) Modifier.background(Color.Black.copy(alpha = 0.08f)) else Modifier)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 多选勾选圈（v0.54.3）：未选 = 细线空心圆；已选 = 强调色实心 + ✓ */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (selected) NeuAccent else Color.Transparent)
            .border(1.6.dp, if (selected) NeuAccent else MutedText, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(MIcons.Check, contentDescription = null, tint = AccentInk, modifier = Modifier.size(12.dp))
        }
    }
}

/**
 * 多选操作栏（v0.54.3）：铺满底部、盖住输入栏；左「取消」，右侧三个动作
 * 「复制 / 转发 / 删除」，没选任何消息时动作置灰不可点。
 */
@Composable
private fun MultiSelectBar(
    count: Int,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = count > 0
    Row(
        modifier
            .fillMaxWidth()
            .background(NeuSurface)
            // 空点也吃掉，避免从按钮缝隙漏到底下仍可用的输入栏
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeuTextButton("取消", onClick = onCancel)
        Spacer(Modifier.weight(1f))
        Text("已选 $count", fontSize = 12.sp, color = TextSecondary)
        Spacer(Modifier.width(6.dp))
        MultiSelectAction(MIcons.Copy, "复制", enabled, onCopy)
        MultiSelectAction(MIcons.Forward, "转发", enabled, onForward)
        MultiSelectAction(MIcons.Delete, "删除", enabled, onDelete)
    }
}

@Composable
private fun MultiSelectAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) TextPrimary else MutedText.copy(alpha = 0.4f)
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = tint)
    }
}

/** AI 任务建议卡：白面细线 + 标题/备注 +「添加任务」胶囊，点击写入任务后整卡消失 */
@Composable
private fun TaskSuggestionCard(suggestion: com.menghuan.app.data.TaskSuggestion, onAdd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            // 凹陷灰面板（无边框无阴影）：白卡发丝线描边的左缘在白底会话里
            // 会呈现为一条突兀的竖直灰线（用户反馈），与生成中卡同语言
            .neuSurface(18.dp, inset = true)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                suggestion.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            if (suggestion.note.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(suggestion.note, fontSize = 12.sp, color = MutedText, maxLines = 2)
            }
            // 日期型记忆（v0.38.0）：AI 从对话里识别的到期日，卡片上亮出来
            if (suggestion.dueDate.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(suggestion.dueDate + " 到期", fontSize = 11.sp, color = TextSecondary)
            }
        }
        Spacer(Modifier.width(10.dp))
        NeuChip(text = "添加任务", selected = false, onClick = onAdd)
    }
}

/**
 * 白底圆角输入框（v0.43.0 微信风：白色面 + 极细描边；v0.43.1 起**高度恒定**）。
 *
 * 为什么会有"输入时变矮"：空态时 `decorationBox` 里只有占位 `Text`（一行 20sp），
 * 一旦有内容就换成 `BasicTextField` 的 `inner()`，而它自带一层内边距与最小高度，
 * 两者高度对不上，视觉上就是"点进去输入框缩了一下"。
 * 现在统一：外层 Box 固定 `height(40.dp)`（单行状态），占位符与输入区都居中，
 * 高度由外层决定，不再随内容跳。
 */
@Composable
private fun NeuChatInput(value: String, onValueChange: (String) -> Unit, onFocus: () -> Unit = {}) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(NeuSurface)
            .border(
                1.dp,
                NeuHairline,
                androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 15.dp, vertical = 9.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 点输入框 = 用户想打字：先收面板（与键盘互斥）
        LaunchedEffect(interaction) {
            interaction.interactions.collect { i ->
                if (i is androidx.compose.foundation.interaction.FocusInteraction.Focus) onFocus()
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, color = TextPrimary),
            maxLines = 5,
            cursorBrush = SolidColor(NeuAccent),
            interactionSource = interaction,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text("发消息…", fontSize = 15.sp, lineHeight = 20.sp, color = MutedText)
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * 语音态下的「按住 说话」条（v0.43.1 起占输入框那一格；v0.53.4 改判定方式）。
 *
 * 按住开始录音；**左右滑**决定松手后干什么，和录音蒙层里那两块弧一一对应：
 * 左半区 → 取消，右半区 → 转文字，正中间那条灰带 → 录音中，松手即按当前档确认。
 * （旧版是"上滑取消"，与蒙层里的圆按钮对不上，所以改掉。）
 * 高度与 [NeuChatInput] 的 40dp 对齐，切来切去不会跳。
 *
 * **v0.55.0（按压反馈 + 状态提示）**：
 * - 按住时整条**微缩 1.5% + 底色由白转灰**（150ms），手指一落下就有"按住了"的回感，
 *   不再是静悄悄换个背景色；
 * - 录音中条内左侧挂一段**实时迷你波形**（[RecordingWave]）—— 只靠"松开 发送"四个字
 *   看不出到底录进去没有，波形动起来才是"在收音"的证据；
 * - 滑到取消 / 转文字档位时文字转危险色，与蒙层里的扇形区同语言。
 *
 * **v0.56.7（用户点名「灵敏度」）**：判定改成**按手指在屏幕上的绝对位置划区** ——
 * 左半区 = 取消、右半区 = 转文字、正中间与蒙层那块 5% 缺口同宽的灰带 = 录音中。
 * 原来用"相对按下点的 ±60dp"：宽屏要滑很远才切档、手指抖一下就跳回来（就是"不灵敏/不跟手"）。
 * 现在半个屏幕就是一个档位区，配合下面三条：
 * ① 手指**动过 touchSlop 之后**才开始判档（否则按住偏左会一按就"取消"）；
 * ② 换档时补一记轻震动（"到位了"的手感）；
 * ③ 整条的缩放/底色/文字色都走 120~160ms 过渡，三态之间是滑过去的，不闪不跳。
 */
@Composable
private fun VoiceHoldBar(
    recording: Boolean,
    enabled: Boolean,
    /** 录音中的实时振幅序列（0..1）：条内迷你波形的数据源 */
    bars: List<Float>,
    onStart: () -> Unit,
    onArmChange: (RecordArm) -> Unit,
    onRelease: (RecordArm) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    // ⚠️ 颜色令牌只能在 composable 作用域读（`@ReadOnlyComposable get()`），
    // 不能直接在 `background(...)` 的 lambda 里引用——先在这里取成局部值。
    val trackColor = NeuTrack
    val surfaceColor = NeuSurface
    var armed by remember { mutableStateOf(RecordArm.NONE) }
    val shape = RoundedCornerShape(20.dp)

    // 档位分区：屏幕中线 ± 这条灰带半径（与蒙层里两块环带弧的中央缺口同宽）
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val gapPx = screenWidthPx * 0.05f
    // 手指坐标是"条内坐标"，要加上条自己在窗口里的左边距才能换算成屏幕 x
    var barLeftPx by remember { mutableStateOf(0f) }

    val holdScale by animateFloatAsState(
        targetValue = when {
            !recording -> 1f
            armed != RecordArm.NONE -> 0.972f
            else -> 0.985f
        },
        animationSpec = tween(120),
    )
    val bg by animateColorAsState(if (recording) trackColor else surfaceColor, tween(150))
    val labelColor by animateColorAsState(
        targetValue = when {
            recording && armed != RecordArm.NONE -> NeuDanger
            recording -> TextPrimary
            else -> MutedText
        },
        animationSpec = tween(150),
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .onGloballyPositioned { barLeftPx = it.boundsInRoot().left }
            .scale(holdScale)
            .clip(shape)
            .background(bg)
            .border(1.dp, NeuHairline, shape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                val mid = screenWidthPx / 2f
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        armed = RecordArm.NONE
                        onArmChange(RecordArm.NONE)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStart()
                        var released = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            // 手指动过一丁点才开始判档：起点在哪儿都不算（避免"一按就取消"）
                            val moved = kotlin.math.abs(change.position.x - down.position.x) > slop
                            val next = if (!moved) {
                                armed
                            } else {
                                when {
                                    barLeftPx + change.position.x < mid - gapPx -> RecordArm.CANCEL
                                    barLeftPx + change.position.x > mid + gapPx -> RecordArm.TRANSCRIBE
                                    else -> RecordArm.NONE
                                }
                            }
                            // 换档时补一次轻震动，让"滑到位了"这件事有手感
                            if (next != armed) {
                                armed = next
                                onArmChange(next)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            if (!change.pressed) {
                                released = true
                                change.consume()
                                break
                            }
                        }
                        if (released) onRelease(armed) else onRelease(RecordArm.CANCEL)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (recording) {
                RecordingWave(
                    bars = bars,
                    color = labelColor,
                    modifier = Modifier.width(56.dp).height(18.dp),
                    maxBars = 9,
                    barWidth = 2.5.dp,
                    gap = 2.5.dp,
                    minHeight = 4.dp,
                    maxHeight = 16.dp,
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                when {
                    recording && armed == RecordArm.CANCEL -> "松开 取消"
                    recording && armed == RecordArm.TRANSCRIBE -> "松开 转文字"
                    recording -> "松开 发送"
                    else -> "按住 说话"
                },
                fontSize = 14.sp,
                fontWeight = if (recording) FontWeight.Medium else FontWeight.Normal,
                color = labelColor,
            )
        }
    }
}

/** 录音蒙层里手指落在哪一档（v0.53.4） */
private enum class RecordArm { NONE, CANCEL, TRANSCRIBE }

/**
 * 录音波形（v0.53.4 建；**v0.55.0 改成 Canvas 绘制**）。
 *
 * 原来每格波形是一个 `Box`：26 根就是 26 个布局节点，每 60ms 采一格就要把整排
 * 重新量一遍（布局 + 绘制两趟）。改成单个 Canvas 后只有一个节点、一次绘制，
 * 波形再密也不掉帧；端头用 `drawRoundRect` 自己画圆角，观感与原来完全一致。
 *
 * 波形**居中排布**、条数不足时也不会靠一边挤着，配合 [maxBars] 取最近若干个采样，
 * 视觉上就是"从右往左推"的实时波形。
 */
@Composable
private fun RecordingWave(
    bars: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    maxBars: Int = WAVE_BAR_COUNT,
    barWidth: Dp = 2.dp,
    gap: Dp = 2.dp,
    minHeight: Dp = 3.dp,
    maxHeight: Dp = 18.dp,
) {
    val ink = color
    Canvas(modifier) {
        val visible = bars.takeLast(maxBars)
        if (visible.isEmpty()) return@Canvas
        // v0.56.3：三口均值平滑——相邻三根取加权均值，消除采样抖动，
        // 波形从"乱跳"变成"流动"（微信那种顺滑起伏感）
        val smoothed = visible.mapIndexed { i, v ->
            val p = visible.getOrNull(i - 1) ?: v
            val n = visible.getOrNull(i + 1) ?: v
            (p + v * 2f + n) / 4f
        }
        val w = barWidth.toPx()
        val unit = w + gap.toPx()
        val startX = ((size.width - (smoothed.size * unit - gap.toPx())) / 2f).coerceAtLeast(0f)
        val lo = minHeight.toPx()
        val span = (maxHeight - minHeight).toPx()
        smoothed.forEachIndexed { i, amp ->
            val h = (lo + amp.coerceIn(0f, 1f) * span).coerceAtMost(size.height)
            drawRoundRect(
                color = ink,
                topLeft = Offset(startX + i * unit, (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

/** 蒙层波形条数量：32 根 × 60ms ≈ 1.9 秒的可见窗口（气泡拉长后条数同步加） */
private const val WAVE_BAR_COUNT = 32

/**
 * 录音中的全屏蒙层（v0.56.1，黑白配色 + 扇形选择区，布局照用户给的微信截图）：
 * - 上方：**强调色气泡**（黑/深色主题反相白）—— 实时波形 + 秒数，下沿带小尖角；
 * - 中间：两块**环带弧块**「取消」「滑到这里 转文字」，顶边大弧、中央留缺口，
 *   手指拖进哪块哪块点亮（判定仍在 [VoiceHoldBar] 的左右滑，这里只负责显示）；
 * - 底部：贴屏幕下沿的**浅灰大弧**（半椭圆），「松开发送」字样随档位变化。
 *
 * v0.55.0 的进出场不变：蒙层在调用点淡入淡出，气泡从 0.92 弹簧长出来；
 * 滑到「取消」档气泡先缩一点（临界操作预告）。
 */
@Composable
private fun VoiceRecordingOverlay(
    durationMs: Long,
    bars: List<Float>,
    armed: RecordArm,
) {
    // 蒙层 = 跟着主题的"面色"（浅色=白、深色=暗），**从上到下渐浓**（v0.53.4 照截图）：
    // 顶部还透出一点聊天内容，往下越来越白，到底部（原来输入栏那一带）完全盖住。
    val scrim = Brush.verticalGradient(
        0f to NeuSurface.copy(alpha = 0.70f),
        0.55f to NeuSurface.copy(alpha = 0.92f),
        1f to NeuSurface,
    )

    // v0.56.1（用户拍板）：**回黑白配色**——气泡回到强调色黑（深色主题反相白），
    // 墨迹用 AccentInk；不用品牌绿。
    val bubble = NeuAccent
    val onBubble = AccentInk
    val idleBg = NeuTrack
    val idleFg = TextPrimary
    val activeBg = NeuAccent
    val activeFg = AccentInk
    // v0.55.0 进场：气泡从 0.92 弹簧长到 1。蒙层本身由调用点的 AnimatedVisibility 淡入，
    // 这里只管"气泡长出来"这一下 —— 两件事分开，才不会出现"整块一起糊上来"。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val bubbleScale by animateFloatAsState(if (entered) 1f else 0.92f, NeuSnappySpring)
    // 滑到「取消」档时气泡先缩一点：这一段要被丢掉了，在**松手之前**就先"收"起来，
    // 临界操作有了预告，不用等松手才知道刚才划到了哪一档。
    val armScale by animateFloatAsState(
        targetValue = if (armed == RecordArm.CANCEL) 0.94f else 1f,
        animationSpec = tween(200, easing = EaseOutQuart),
    )

    Box(Modifier.fillMaxSize().background(scrim)) {
        Column(
            // v0.56.0：整块贴屏幕底部 —— 气泡悬在中下部，下面依次是两块灰块、贴底大弧
            //（照截图：大弧直接压在屏幕下沿，手势条浮在弧上，微信同款）。
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 气泡 + 底部小尖角
            Box(Modifier.scale(bubbleScale * armScale)) {
                // v0.56.3：气泡**拉长压扁**（用户：转文字时框太肥，要长扁）——
                // 宽 200dp / 纵向内边距 10dp / 波形 176×20dp，秒数缩小。
                Column(
                    Modifier
                        .widthIn(min = 200.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(bubble)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    RecordingWave(
                        bars = bars,
                        color = onBubble,
                        modifier = Modifier.width(176.dp).height(20.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        com.menghuan.app.data.VoiceRecorder.formatDuration(durationMs),
                        fontSize = 11.sp,
                        color = onBubble.copy(alpha = 0.75f),
                    )
                }
                // 小尖角：旋转 45° 的小方块，一半露在气泡外
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 5.dp)
                        .size(11.dp)
                        .rotate(45f)
                        .background(bubble),
                )
            }
            Spacer(Modifier.height(56.dp))
            // v0.56.2：按用户点名改回**环带弧块**（照截图：顶边是大弧的环带段，中央留缺口，
            // 不是两颗半圆），黑白配色：点亮 = 强调色底 + AccentInk 字。
            RingSegments(
                armed = armed,
                idleBg = idleBg,
                idleFg = idleFg,
                activeBg = activeBg,
                activeFg = activeFg,
            )
            Spacer(Modifier.height(12.dp))
            ReleaseDome(
                label = when (armed) {
                    RecordArm.CANCEL -> "松开 取消"
                    RecordArm.TRANSCRIBE -> "松开 转文字"
                    RecordArm.NONE -> "松开 发送"
                },
            )
        }
    }
}

/**
 * 蒙层中部的**环带弧块**（v0.56.2 照微信截图：两块顶边为大弧的环带段，中央留缺口
 * ——**不是两颗半圆**；弧线与底部大弧同心，文字沿切线微微倾斜）。
 * 判定仍在 [VoiceHoldBar] 的左右滑（±60dp），这里只负责显示：拖进哪块哪块点亮。
 */
@Composable
private fun RingSegments(
    armed: RecordArm,
    idleBg: Color,
    idleFg: Color,
    activeBg: Color,
    activeFg: Color,
) {
    val hairline = NeuHairline
    val fillL by animateColorAsState(
        targetValue = if (armed == RecordArm.CANCEL) activeBg else idleBg,
        animationSpec = tween(160),
        label = "ringFillL",
    )
    val fgL by animateColorAsState(
        targetValue = if (armed == RecordArm.CANCEL) activeFg else idleFg,
        animationSpec = tween(160),
        label = "ringFgL",
    )
    val fillR by animateColorAsState(
        targetValue = if (armed == RecordArm.TRANSCRIBE) activeBg else idleBg,
        animationSpec = tween(160),
        label = "ringFillR",
    )
    val fgR by animateColorAsState(
        targetValue = if (armed == RecordArm.TRANSCRIBE) activeFg else idleFg,
        animationSpec = tween(160),
        label = "ringFgR",
    )
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)

    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val w = size.width
        val cx = w / 2f
        val padTop = 2.dp.toPx()
        // 顶边大弧：弦长 ≈ 全屏宽、矢高 = 7.5% 宽 → 反推圆半径
        val sag = w * 0.075f
        val r = (w * w) / (8f * sag) + sag / 2f
        val cy = padTop + r
        val t = 34.dp.toPx()
        val rm = r - t / 2f
        // v0.56.4：**左右严格镜像对称** —— 两块外端都收在 47% 半宽、内端都留 5% 半宽缺口。
        val outerDx = w * 0.47f
        val gapDx = w * 0.05f
        fun deg(dx: Float): Double = Math.toDegrees(Math.asin((dx / rm).toDouble()))
        val top = 270.0
        // 圆帽端头会沿切线方向多伸出 t/2，把两端角度各往回收这么多，端点才落在预设位置
        val capDeg = Math.toDegrees(((t / 2f) / rm).toDouble())
        val lStart = (top - deg(outerDx) + capDeg).toFloat()
        val lEnd = (top - deg(gapDx) - capDeg).toFloat()
        val rStart = (top + deg(gapDx) + capDeg).toFloat()
        val rEnd = (top + deg(outerDx) - capDeg).toFloat()

        // 一段弧线 + **圆帽描边**：端头天然是半圆角，不需要手拼路径。
        // 静息态 = 先画发丝线宽条、再叠窄 2dp 的面色条，边缘留出 1dp 描边圈。
        fun block(start: Float, end: Float, fill: Color, outlined: Boolean) {
            val tl = Offset(cx - rm, cy - rm)
            val sz = Size(rm * 2f, rm * 2f)
            drawArc(
                color = fill,
                startAngle = start,
                sweepAngle = end - start,
                useCenter = false,
                topLeft = tl,
                size = sz,
                style = Stroke(width = t, cap = StrokeCap.Round),
            )
            if (outlined) {
                drawArc(
                    color = hairline,
                    startAngle = start,
                    sweepAngle = end - start,
                    useCenter = false,
                    topLeft = tl,
                    size = sz,
                    style = Stroke(width = t, cap = StrokeCap.Round),
                )
                drawArc(
                    color = fill,
                    startAngle = start,
                    sweepAngle = end - start,
                    useCenter = false,
                    topLeft = tl,
                    size = sz,
                    style = Stroke(width = t - 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
        block(lStart, lEnd, fillL, armed != RecordArm.CANCEL)
        block(rStart, rEnd, fillR, armed != RecordArm.TRANSCRIBE)

        // 文字：放在块中角的中径上，旋转 = 中角相对顶点的偏角（沿切线微倾）
        fun drawLabel(label: String, start: Float, end: Float, color: Color) {
            val midDeg = ((start + end) / 2f).toDouble()
            val mid = Math.toRadians(midDeg)
            val rm = r - t / 2f
            val px = cx + (rm * Math.cos(mid)).toFloat()
            val py = cy + (rm * Math.sin(mid)).toFloat()
            val rot = (midDeg - top).toFloat()
            val lr = measurer.measure(label, labelStyle)
            withTransform({
                rotate(degrees = rot, pivot = Offset(px, py))
            }) {
                drawText(
                    textLayoutResult = lr,
                    color = color,
                    topLeft = Offset(px - lr.size.width / 2f, py - lr.size.height / 2f),
                )
            }
        }
        drawLabel("取消", lStart, lEnd, fgL)
        drawLabel("转文字", rStart, rEnd, fgR)
    }
}

/**
 * 蒙层底部贴屏幕下沿的**浅灰大弧**（v0.56.0 照微信截图：半椭圆穹顶，字样随档位变化）。
 * 弧体 = 上半椭圆（宽 = 全屏、可见高 = 92dp），颜色跟随主题凹陷面 [NeuTrack]。
 */
@Composable
private fun ReleaseDome(label: String) {
    val fill = NeuTrack
    Box(Modifier.fillMaxWidth().height(92.dp), contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.matchParentSize()) {
            // 上半椭圆：外接矩形高 = 可见高 × 2，只露出上面一半 → 贴底穹顶
            drawArc(
                color = fill,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height * 2f),
            )
        }
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier.padding(top = 30.dp),
        )
    }
}

/**
 * 无外圈的图标按钮（v0.43.1）：输入栏两侧的语音 / 表情 / 加号 / 发送都用它。
 *
 * 为什么去掉外面的圈：用户明确要求"不要外面那个圈，直接用里面符号代替，颜色淡一些"。
 * 所以这里只有图标本身 + 一块方形点击热区（44dp 保证好点），
 * 颜色由调用方给（默认淡灰 [MutedText]，激活时才上强调色）。
 *
 * v0.45.0：按压反馈从 Material 默认 ripple 换成 [rememberNeuPressState]（scale 0.96 / 110ms）。
 * 用户反馈"点这些按钮时灰色阴影太久太大"——ripple 是无界水波、淡出要 300ms 以上，
 * 与全应用"无涟漪 + 轻微缩放"的语言也不一致，现在统一走缩放。
 */
@Composable
private fun NeuFlatIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val press = rememberNeuPressState()
    Box(
        modifier
            .size(40.dp)
            .scale(press.scale)
            .clip(CircleShape)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 禁用时靠外层 alpha 变淡（图标 tint 由调用方决定，这里不覆盖，避免激活色被冲掉）
        Box(Modifier.graphicsLayer(alpha = if (enabled) 1f else 0.4f)) { content() }
    }
}

/**
 * 语音播放器（v0.43.0）：包一层 MediaPlayer，同一时刻只播一条。
 * 播放结束自动回调，用于把气泡上的"暂停"图标复位成"播放"。
 */
private class VoicePlayer {
    private var player: android.media.MediaPlayer? = null
    private var onDone: (() -> Unit)? = null

    val isPlaying: Boolean get() = player?.isPlaying == true

    fun play(path: String, onComplete: () -> Unit): Boolean {
        stop()
        onDone = onComplete
        return runCatching {
            val p = android.media.MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ -> stop(); true }
                prepare()
                start()
            }
            player = p
            true
        }.getOrElse {
            stop()
            false
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        val cb = onDone
        onDone = null
        cb?.invoke()
    }

    /** 播放器还活着（stop/release 之后为 false） */
    val alive: Boolean get() = player != null

    /** 毫秒；播放器不在或取不到都算 0 */
    val durationMs: Int get() = runCatching { player?.duration ?: 0 }.getOrDefault(0)
    val positionMs: Int get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    fun release() = stop()
}

/**
 * 待发送附件（v0.56.6）：相册 / 文件选完之后**先挂到输入框上方**，等用户补完文字一起发，
 * 或者直接点发送。以前是"选完立刻发出去"，用户没有写说明的机会。
 *
 * [isImage] 决定发送时走 [com.menghuan.app.ui.chat.ChatViewModel.sendImage] 还是 sendFile；
 * 两者的 [path] 都已经是应用私有目录里的落盘文件（选的时候就拷好了）。
 */
private data class PendingAttachment(
    val isImage: Boolean,
    val path: String,
    /** 文件名（图片固定显示"图片"） */
    val name: String,
    val size: Long,
    /** 文件本地抽出的正文（图片为空） */
    val extractedText: String,
)

/** 取 content:// 的显示名（用户看到的原始文件名） */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

/** 把 content:// 拷进 filesDir/uploads/，返回落盘文件（重名加时间戳前缀） */
private fun copyUriToFiles(
    context: android.content.Context,
    uri: android.net.Uri,
    name: String,
): java.io.File? = runCatching {
    val dir = java.io.File(context.filesDir, "uploads").apply { if (!exists()) mkdirs() }
    val safe = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(80)
    val file = java.io.File(dir, "${System.currentTimeMillis()}_$safe")
    context.contentResolver.openInputStream(uri)?.use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
    } ?: return null
    if (file.length() == 0L) {
        file.delete()
        return null
    }
    file
}.getOrNull()


/** 消息列表条目：按天分组的日期头 or 消息气泡（gapBefore = 换人时的呼吸间距） */
private sealed interface ChatListItem {
    data class DayHeader(val label: String) : ChatListItem
    data class Msg(val message: ChatMessage, val gapBefore: Boolean) : ChatListItem
}

private fun dayLabel(date: LocalDate): String = when (date) {
    LocalDate.now() -> "今天"
    LocalDate.now().minusDays(1) -> "昨天"
    else -> "${date.monthValue}月${date.dayOfMonth}日"
}

/**
 * 消息列表条目的稳定 key：已落库消息用 DB id，未落库（乐观插入/种子演示）退回索引。
 * id 稳定 = 滚动时 LazyColumn 能识别"同一条消息"，条目状态与位图缓存跨滚动保留。
 */
private fun chatKeyOf(items: List<ChatListItem>, idx: Int): Any = when (val item = items[idx]) {
    // v0.82.0：日期头 key 用标签而不是索引——分页在顶部插入更早的消息时索引整体平移，
    // 索引 key 会让全部头重建、滚动锚点丢失；标签在列表内唯一，前置消息不影响既有 key。
    is ChatListItem.DayHeader -> "d${item.label}"
    is ChatListItem.Msg ->
        if (item.message.id != 0L) "m${item.message.id}" else "n$idx"
}

private fun buildChatItems(messages: List<ChatMessage>): List<ChatListItem> {
    val zone = ZoneId.systemDefault()
    val items = mutableListOf<ChatListItem>()
    var lastDay: LocalDate? = null
    var lastFromUser: Boolean? = null
    messages.forEach { message ->
        val day = Instant.ofEpochMilli(message.timestamp).atZone(zone).toLocalDate()
        if (day != lastDay) {
            items += ChatListItem.DayHeader(dayLabel(day))
            lastDay = day
        }
        val gap = lastFromUser != null && lastFromUser != message.isFromUser
        items += ChatListItem.Msg(message, gap)
        lastFromUser = message.isFromUser
    }
    return items
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    maxWidth: androidx.compose.ui.unit.Dp,
    gapBefore: Boolean,
    appearance: com.menghuan.app.data.ChatAppearance =
        com.menghuan.app.data.ChatAppearance(),
    persona: com.menghuan.app.data.model.Persona? = null,
    userAvatar: com.menghuan.app.data.UserAvatar = com.menghuan.app.data.UserAvatar(),
    onLongPress: () -> Unit,
    /** v0.89.0：长按菜单锚点，必须落在**气泡本体**上（不含旁边 34dp 头像与 8dp 间距） */
    anchorModifier: Modifier = Modifier,
) {
    val isUser = message.isFromUser
    val isHeart = message.content == "❤️"
    // v0.80.0：配色（明暗各一套，按当前主题取）+ 圆角/边框 + 字体三件套
    // 渲染时取哪套看**当前是不是深色主题**：外观页里编辑的是"将来用的那套"，这里只管"现在这套"。
    val theme = if (com.menghuan.app.ui.theme.IsDarkTheme) appearance.dark else appearance.light
    val resolved = com.menghuan.app.ui.theme.resolveBubble(
        style = if (isUser) theme.mine else theme.other,
        isMine = isUser,
        appearance = appearance,
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (gapBefore) 8.dp else 0.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        when {
            isHeart -> Text("❤️", fontSize = 44.sp)
            isUser -> {
                // 用户气泡：文字先、头像贴在最右（微信 / iMessage 的常见排法）
                UserBubble(message.content, maxWidth, resolved, onLongPress, anchorModifier)
                if (appearance.showAvatar) {
                    Spacer(Modifier.width(8.dp))
                    ChatRowAvatar(personaId = 0L, persona = null, userAvatar = userAvatar, size = 34.dp)
                }
            }
            else -> {
                if (appearance.showAvatar) {
                    ChatRowAvatar(personaId = persona?.id ?: 0L, persona = persona, userAvatar = userAvatar, size = 34.dp)
                    Spacer(Modifier.width(8.dp))
                }
                AiBubble(message.content, maxWidth, resolved, onLongPress, anchorModifier)
            }
        }
    }
}

/**
 * 气泡旁的头像（v0.77.0）。
 *
 * - 用户（personaId = 0）：读用户自己的头像设置；**没设置过就退回黑底白「我」字圆**
 *   （与动态页 [MomentAuthorAvatar] 同一口径，两个页面看到的"我"是同一张脸）；
 * - AI：人设 emoji / 自定义图 + 人设渐变环（与会话列表同一颗 [personaBrush]）。
 *
 * 尺寸固定 34dp：比会话列表的 46 小一圈 —— 气泡旁的头像比"列表主头像"次要，
 * 太大了会跟气泡抢视觉重心。
 */
@Composable
private fun ChatRowAvatar(
    personaId: Long,
    persona: com.menghuan.app.data.model.Persona?,
    userAvatar: com.menghuan.app.data.UserAvatar,
    size: androidx.compose.ui.unit.Dp,
) {
    if (personaId == 0L) {
        // 用户：设了图/emoji 就用，否则黑底白「我」。
        // v0.93.0：走 `MeAvatar` —— 与档案页 / 动态页是同一张脸（含那圈渐变环），
        // 气泡里这一排头像也就和 AI 那边（personaBrush）同一套框样式。
        com.menghuan.app.ui.profile.MeAvatar(avatar = userAvatar, size = size)
        return
    }
    com.menghuan.app.ui.common.NeuAvatar(
        emoji = persona?.avatarEmoji.takeUnless { it.isNullOrEmpty() } ?: "…",
        size = size,
        ringBrush = com.menghuan.app.ui.theme.personaBrush(personaId),
        imagePath = persona?.avatarImagePath ?: "",
    )
}

/** AI 气泡：长按弹操作菜单（v0.56.15：震动只在 openMessageMenu 里补，不再自己震一次） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiBubble(
    content: String,
    maxWidth: androidx.compose.ui.unit.Dp,
    /** v0.80.0：底/字/边框/圆角/字体三件套一次给齐（旧版是 bg+ink+bodySp 三个散参） */
    resolved: com.menghuan.app.ui.theme.ResolvedBubble,
    onLongPress: () -> Unit,
    /** v0.89.0：长按菜单锚点（挂在气泡本体上，见 [MessageBubble] 说明） */
    anchorModifier: Modifier = Modifier,
) {
    val radius = resolved.radiusDp.coerceAtLeast(0).dp
    Box(
        anchorModifier
            .widthIn(max = maxWidth)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
            )
            .neuSurface(radius, color = resolved.bg, elevation = 2.dp)
            // 边框画在底色之上、内容之内：描在气泡外沿，不侵占内边距
            .then(
                resolved.border?.let {
                    Modifier.border(1.dp, it, androidx.compose.foundation.shape.RoundedCornerShape(radius))
                } ?: Modifier
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        if (content.isBlank()) {
            TypingDots()
        } else {
            // 正文色跟随气泡配色（resolved.ink）：MarkdownText 默认色是 TextPrimary，
            // 深底上会看不见，所以显式传；字体族与字重也一起传。
            MarkdownText(
                content,
                color = resolved.ink,
                fontSize = resolved.fontSizeSp.sp,
                fontFamily = resolved.fontFamily,
                fontWeight = resolved.fontWeight,
                // v0.97.0（审查 U19）：超长回复（AI 偶尔写一大篇）先折到 420dp，
                // 末尾给「展开全文（共 N 字）」。只在**正文**上折，代码块与列表照旧。
                collapseOverChars = 1200,
            )
        }
    }
}

/** 用户气泡：默认墨黑药丸 + 纸叠影（Figma 界面唯一强调色）；v0.77.0 起底色/字号可自定义 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    content: String,
    maxWidth: androidx.compose.ui.unit.Dp,
    /** v0.80.0：底/字/边框/圆角/字体三件套一次给齐（旧版是 bg+ink+bodySp 三个散参） */
    resolved: com.menghuan.app.ui.theme.ResolvedBubble,
    onLongPress: () -> Unit,
    /** v0.89.0：长按菜单锚点（挂在气泡本体上，见 [MessageBubble] 说明） */
    anchorModifier: Modifier = Modifier,
) {
    val radius = resolved.radiusDp.coerceAtLeast(0).dp
    Box(
        anchorModifier
            .widthIn(max = maxWidth)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = onLongPress,
            )
            .neuSurface(radius, color = resolved.bg, elevation = 3.dp)
            .then(
                resolved.border?.let {
                    Modifier.border(1.dp, it, androidx.compose.foundation.shape.RoundedCornerShape(radius))
                } ?: Modifier
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(
            content,
            color = resolved.ink,
            fontSize = resolved.fontSizeSp.sp,
            fontFamily = resolved.fontFamily,
            fontWeight = resolved.fontWeight,
            lineHeight = (resolved.fontSizeSp + 6).sp,
            letterSpacing = (-0.1).sp,
        )
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("menghuan", text))
    com.menghuan.app.ui.common.AppToast.ok("已复制")
}

/**
 * 对话内手机执行卡（v0.37.0）：目标 + 步骤日志（自动滚到底）+ 停止/收起。
 * 执行结束时摘要会以「🤖 自动执行…」消息落进会话流，卡片随后自动收起（state 回 Idle）。
 */
@Composable
private fun AgentRunCard(
    run: AgentRunUiState,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .neuSurface(16.dp, elevation = 3.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    run.running -> "正在操作手机…"
                    run.success -> "已完成（${run.steps} 步）"
                    else -> "未完成（${run.steps} 步）"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    run.running -> TextPrimary
                    run.success -> NeuAccent
                    else -> NeuDanger
                },
            )
            Spacer(Modifier.weight(1f))
            if (run.running) {
                NeuTextButton("停止", onClick = onStop)
            } else {
                NeuTextButton("收起", onClick = onDismiss)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            run.goal,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MutedText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (run.logs.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val logScroll = rememberScrollState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
                    .neuSurface(10.dp, inset = true),
            ) {
                // 新日志进来自动滚到底（执行是单向流水，只看最新几步）
                LaunchedEffect(run.logs.size) {
                    logScroll.animateScrollTo(logScroll.maxValue)
                }
                Column(
                    Modifier
                        .verticalScroll(logScroll)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    run.logs.forEach { line ->
                        Text(
                            line,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = if (line.startsWith("⛔") || line.startsWith("⚠️")) NeuDanger else MutedText,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

/** 流式等待三点：错落弹跳（alpha/scale/位移全走 graphicsLayer，渲染期更新不重组） */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val wave by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(760, delayMillis = i * 190, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        alpha = 0.3f + 0.7f * wave
                        scaleY = 0.65f + 0.35f * wave
                        translationY = -3.5f * wave
                    }
                    .background(TextPrimary, CircleShape),
            )
        }
    }
}
