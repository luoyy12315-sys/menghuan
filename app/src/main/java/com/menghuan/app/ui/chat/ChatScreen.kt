package com.menghuan.app.ui.chat

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import com.menghuan.app.ui.theme.AccentInk
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.agent.AgentRunner
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.MediaKind
import com.menghuan.app.ui.common.LocalContentWidth
import com.menghuan.app.ui.common.EaseOutExpo
import com.menghuan.app.ui.common.EaseOutQuart
import com.menghuan.app.ui.common.MarkdownText
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.neuRowPress
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.rememberNeuPressState
import com.menghuan.app.ui.settings.ApiConfigSheet
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
import kotlinx.coroutines.launch
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
    vm: ChatViewModel = viewModel(key = "chat_$personaId", factory = ChatViewModel.factory(personaId)),
) {
    val uiState by vm.uiState.collectAsState()
    val persona by vm.activePersona.collectAsState()
    val taskSuggestions by vm.taskSuggestions.collectAsState()
    val searchResults by vm.searchResults.collectAsState()
    var showApiConfig by remember { mutableStateOf(false) }
    var viewerPath by remember { mutableStateOf<String?>(null) } // 全屏图片查看（v0.39.0）
    var messageMenu by remember { mutableStateOf<ChatMessage?>(null) }
    // 语音条「已展开转文字」的消息 id 集合（v0.43.1）。
    // 放在列表层而不是气泡内部：气泡滚出屏会被回收，状态放里面会丢。
    val voiceTranscriptIds = remember { mutableStateListOf<Long>() }
    var reasoningView by remember { mutableStateOf<ChatMessage?>(null) } // 思考链查看（v0.41.4）
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showApiSwitch by remember { mutableStateOf(false) }
    // v0.43.0 输入区面板：0=收起 1=表情 2=加号（同一时刻只开一个，与键盘互斥）
    var panel by remember { mutableStateOf(0) }
    var showCamera by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val appContainer = context.applicationContext as com.menghuan.app.MenghuanApp
    // 自定义表情（v0.43.0）：库表 → 面板真实数据源
    val stickers by appContainer.db.stickerDao().observeAll()
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    // 正在播放的语音消息 id（同一时刻只播一条；null = 没在播）
    var playingVoiceId by remember { mutableStateOf<Long?>(null) }
    val voicePlayer = remember { VoicePlayer() }
    DisposableEffect(Unit) { onDispose { voicePlayer.release() } }
    // 发图给 AI 看（v0.38.0）：选图 → 拷进应用私有目录 → 以当前输入为字幕发出
    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val app = context.applicationContext as com.menghuan.app.MenghuanApp
            val path = com.menghuan.app.data.VisionImage.copyToChatImages(context, uri, app.filesDir)
            if (path != null) {
                vm.sendImage(path, uiState.input)
            } else {
                Toast.makeText(context, "图片读取失败", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "表情导入失败", Toast.LENGTH_SHORT).show()
                } else {
                    // 同一张图重复导入不再加一条（库表按 path 查重）
                    val existing = appContainer.db.stickerDao().findByPath(path)
                    if (existing == null) {
                        appContainer.db.stickerDao()
                            .insert(com.menghuan.app.data.model.Sticker(path = path))
                    } else {
                        Toast.makeText(context, "这个表情已经添加过了", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // 选文件发给 AI（v0.43.0）：任意类型 → 拷进私有目录 → 本地抽正文 → 发消息
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val name = queryDisplayName(context, uri) ?: "文件"
                val saved = copyUriToFiles(context, uri, name)
                if (saved == null) {
                    Toast.makeText(context, "文件读取失败", Toast.LENGTH_SHORT).show()
                } else {
                    val text = com.menghuan.app.data.FileTextExtractor.extract(saved, name)
                    vm.sendFile(saved.absolutePath, name, saved.length(), text.text)
                }
            }
        }
    }

    // 录音（v0.43.0）：按住说话。v0.43.1 起改成"点语音键切到语音态 → 按住说话区录音"。
    val recorder = remember { com.menghuan.app.data.VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
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
        searchMode = false
        searchQuery = ""
        messageMenu = null
        reasoningView = null
        viewerPath = null
    }

    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) Toast.makeText(context, "需要麦克风权限才能发语音", Toast.LENGTH_SHORT).show()
    }

    // 气泡最大宽度 = 版心的 76%（比例随版心自适应）
    val bubbleMaxWidth = LocalContentWidth.current * 0.76f

    // 当前会话生效的 AI 接口（长按头像可切换，只影响本会话）
    val apiProfiles by vm.apiProfiles.collectAsState()
    val activeProfileId by vm.activeProfileId.collectAsState()
    val apiOverrideId by vm.apiOverrideId.collectAsState()
    val globalProfileId by vm.globalProfileId.collectAsState()
    val globalLabel = apiProfiles.firstOrNull { it.id == globalProfileId }?.name ?: "未配置"

    // 查看即已读：会话可见期间每条新消息到达都刷新阅读时刻，列表未读点立即消除
    LaunchedEffect(uiState.messages.lastOrNull()?.id) { vm.markRead() }

    // 返回键分流（v0.43.1 定稿，v0.43.1 二次返工）：
    // 只在**真的有"可收起的层"**时启用 BackHandler：录音中 / 搜索态 / 面板打开 / 语音态。
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
    // 我们只需管好自己造的那些层（面板/语音态/搜索），键盘一个字节都不用碰。
    //
    // 顺序即优先级：录音 → 搜索 → 面板 → 语音态。
    // ⚠️ 面板分支里**不能再调 `keyboardController.hide()`**：点表情时键盘早已收走，
    //    这一句只会在"面板还开着 + 用户刚点回输入框"的窗口里把新唤起的键盘又按下去。
    val hasDismissableLayer = recording || searchMode || panel != 0 || voiceMode
    androidx.activity.compose.BackHandler(enabled = hasDismissableLayer) {
        when {
            // 录音中：吃掉返回，避免录制被中断（松手才算结束）
            recording -> Unit
            searchMode -> {
                searchMode = false
                vm.onSearchQueryChange("")
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

    // 外层容器：让搜索态覆盖层（第二个 fillMaxSize 子级）真正叠在聊天内容之上，
    // 而不是作为 AdaptiveScreen Column 的兄弟被挤成 0 高度
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.fillMaxSize()) {
                // 顶部导航：返回键（退出；双栏嵌入时隐藏）+ 居中头像区（长按换风格）
                NeuTopBar(
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
                    // v0.46.0：语音通话入口——与放大镜同款"无圆圈"裸图标（NeuTopBar 默认
                    // 84dp 槽宽正好两颗 38dp 按钮，无需传 actionsWidth）
                    Icon(
                        MIcons.Phone,
                        contentDescription = "语音通话",
                        tint = TextPrimary,
                        modifier = Modifier
                            .size(21.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onOpenCall() },
                    )
                    Spacer(Modifier.width(14.dp))
                    // 无圆圈：放大镜图标直接落在顶栏（用户反馈"图标周围不需要圆圈"）
                    Icon(
                        MIcons.Search,
                        contentDescription = "搜索消息",
                        tint = TextPrimary,
                        modifier = Modifier
                            .size(21.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { searchMode = true },
                    )
                },
            )

            // 消息列表（按天分组）
            val listState = rememberLazyListState()
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
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
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
                        when (val item = chatItems[idx]) {
                            is ChatListItem.DayHeader -> Box(
                                // 只保留重排弹簧：进页时整屏条目作为"新条目"插入，
                                // 默认的成排淡入弹簧会和页面滑入动画叠在一起，观感就是"进页卡一下"
                                Modifier.fillMaxWidth().animateItem(fadeInSpec = null, fadeOutSpec = null),
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

                            is ChatListItem.Msg -> Box(
                                Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                            ) {
                                val msg = item.message
                                // v0.43.0：媒体类型优先（存量消息 mediaKind 为空，按老规则推断）
                                val kind = MediaKind.fromLegacy(msg.mediaKind, msg.imagePath)
                                when (kind) {
                                    // 图片消息（AI 生成图落盘后的最终形态 / 用户发的图 / 自定义表情）
                                    // v0.42.4：外层 Box 必须显式指定 contentAlignment。
                                    // Box 默认 TopStart——无论 ImageBubble 内部 horizontalAlignment
                                    // 是什么，整块都会被摆在左上角，用户发的图因而"跑到 AI 那侧"。
                                    MediaKind.IMAGE ->
                                        Box(
                                            Modifier.fillMaxWidth(),
                                            contentAlignment = if (msg.isFromUser) Alignment.CenterEnd
                                            else Alignment.CenterStart,
                                        ) {
                                            ImageBubble(
                                                prompt = msg.content,
                                                bitmap = rememberLocalBitmap(msg.imagePath),
                                                maxWidth = bubbleMaxWidth,
                                                failed = false,
                                                onClick = { viewerPath = msg.imagePath },
                                                fromUser = msg.isFromUser,
                                            )
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
                                                onLongPress = { messageMenu = msg },
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
                                                onLongPress = { messageMenu = msg },
                                            )
                                        }

                                    MediaKind.TEXT -> MessageBubble(
                                        msg,
                                        bubbleMaxWidth,
                                        item.gapBefore,
                                        onLongPress = { messageMenu = msg },
                                    )
                                }
                            }
                        }
                    }

                    // 生成中的三点气泡（v0.41.4）：AI 在后台写完再整段上屏，
                    // 期间只挂一个只含三点动效的空 AI 气泡（不再逐字吐字）
                    if (uiState.generating) {
                        item(key = "typingTail") {
                            Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                    AiBubble(content = "", maxWidth = bubbleMaxWidth, onLongPress = {})
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
                                    .animateItem(fadeInSpec = null, fadeOutSpec = null),
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
                                    .animateItem(fadeInSpec = null, fadeOutSpec = null),
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
                        onOpenApi = { showApiConfig = true },
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
                            // 切到语音态：收键盘、收面板（三者互斥），不要键盘
                            switchOutputMode(nextPanel = 0, nextVoice = true, showKeyboard = false)
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
                            onStart = {
                                val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.RECORD_AUDIO,
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                if (!hasMic) {
                                    micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                                } else if (recorder.start()) {
                                    recording = true
                                } else {
                                    // 带上真实原因（v0.43.1）：静默的"启动失败"无从定位，
                                    // 真机上分不清是权限、被占用还是 ROM 不认录音参数。
                                    val why = recorder.lastError
                                    Toast.makeText(
                                        context,
                                        if (why.isNullOrBlank()) "录音启动失败，可能被其他应用占用"
                                        else "录音启动失败：$why",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                            onCancel = {
                                recording = false
                                recorder.cancel()
                            },
                            onFinish = {
                                recording = false
                                val result = recorder.stop()
                                if (result != null) {
                                    val (file, duration) = result
                                    scope.launch {
                                        // 录完再转写（录音期间绝不碰识别器：会抢麦克风）。
                                        // 三级级联：云端 Whisper（主力）→ 系统识别（兜底）→ 空。
                                        val text = com.menghuan.app.data.SpeechToText.transcribe(
                                            context = context,
                                            audioPath = file.absolutePath,
                                            cloudTranscribe = { f -> vm.cloudTranscribe(f) },
                                        ) ?: ""
                                        vm.sendVoice(file.absolutePath, duration, text)
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

                // 右：有内容时给发送键；否则表情 + 加号（无外圈，颜色淡）
                if (uiState.input.isNotBlank() && !voiceMode) {
                    Spacer(Modifier.width(6.dp))
                    NeuFlatIconButton(
                        onClick = {
                            vm.send()
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
                        )
                    }
                }
            }
        }
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
    if (showApiSwitch) {
        ApiSwitchSheet(
            profiles = apiProfiles,
            activeId = activeProfileId,
            isFollowingGlobal = apiOverrideId == null,
            globalLabel = globalLabel,
            onSelect = { id ->
                vm.switchApiProfile(id)
                showApiSwitch = false
            },
            onFollowGlobal = {
                vm.followGlobalApi()
                showApiSwitch = false
            },
            onDismiss = { showApiSwitch = false },
        )
    }

    // 错误条「去配置」直达：不跳页，直接在这里把 API 配置弹层叠上来
    if (showApiConfig) {
        ApiConfigSheet(onDismiss = { showApiConfig = false })
    }

    // 全屏图片查看（捏合缩放 / 双击放大 / 单击关闭）
    viewerPath?.let { path ->
        ImageViewerOverlay(path = path, onDismiss = { viewerPath = null })
    }

    // 会话内搜索态：覆盖整页（顶栏搜索图标进入，返回退出），LIKE 命中实时过滤
    if (searchMode) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeuCircleButton(
                    onClick = {
                        searchMode = false
                        searchQuery = ""
                        vm.onSearchQueryChange("")
                    },
                    size = 38.dp,
                ) {
                    Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .neuSurface(22.dp, color = MaterialTheme.colorScheme.background, elevation = 5.dp, inset = true),
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            vm.onSearchQueryChange(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 11.dp)
                            .focusRequester(focusRequester)
                            .testTag("chat_search_field"),
                        textStyle = TextStyle(fontSize = 15.sp, color = TextPrimary),
                        singleLine = true,
                        cursorBrush = SolidColor(NeuAccent),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text("搜索消息…", fontSize = 15.sp, color = MutedText)
                                }
                                inner()
                            }
                        },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    searchQuery.isBlank() -> {}
                    searchResults.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("没有匹配结果", fontSize = 13.sp, color = com.menghuan.app.ui.theme.FaintText)
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                        items(searchResults.size) { idx ->
                            ChatSearchRow(
                                message = searchResults[idx],
                                fromLabel = if (searchResults[idx].isFromUser) "我" else (persona?.name ?: "AI"),
                                onClick = {
                                    searchMode = false
                                    searchQuery = ""
                                    vm.onSearchQueryChange("")
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    }

    messageMenu?.let { message ->
        MessageMenuSheet(
            message = message,
            canDelete = message.id > 0 && !uiState.isStreaming,
            onCopy = {
                copyToClipboard(context, message.content)
                messageMenu = null
            },
            onDelete = {
                vm.deleteMessage(message)
                messageMenu = null
            },
            // 思考链（v0.41.4）：只有模型真的吐过推理内容的消息才给这个入口
            onViewReasoning = if (message.reasoning.isNotBlank()) {
                {
                    reasoningView = message
                    messageMenu = null
                }
            } else null,
            // 语音条转文字开关（v0.43.1）：只有真识别出文字的语音条才给
            onToggleTranscript = if (message.mediaKind == MediaKind.AUDIO.name &&
                message.content.isNotBlank()
            ) {
                {
                    if (!voiceTranscriptIds.remove(message.id)) voiceTranscriptIds.add(message.id)
                    messageMenu = null
                }
            } else null,
            transcriptShown = voiceTranscriptIds.contains(message.id),
            onDismiss = { messageMenu = null },
        )
    }

    // 思考链全文查看（v0.41.4）：推理类模型的思维过程，只在这里按需展示
    reasoningView?.let { message ->
        ReasoningSheet(content = message.reasoning, onDismiss = { reasoningView = null })
    }
}

/** 错误条行（从 ChatScreen 抽出供动画包装复用）：文案 + 按错误种类给的动作按钮 */
@Composable
private fun ChatErrorBar(
    message: String,
    kind: String,
    onRetry: () -> Unit,
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
 * 长按气泡弹出：查看思考过程（仅推理模型的消息）/ 复制 / 删除
 * （已落库消息才可删；种子演示消息仅可复制）。
 * [onViewReasoning] 为 null = 该消息没有思考链，不显示这一行。
 */
@Composable
private fun MessageMenuSheet(
    message: ChatMessage,
    canDelete: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onViewReasoning: (() -> Unit)?,
    onToggleTranscript: (() -> Unit)? = null,
    transcriptShown: Boolean = false,
    onDismiss: () -> Unit,
) {
    NeuSheetScaffold(
        title = if (message.isFromUser) "我的消息" else "AI 消息",
        onDismiss = onDismiss,
    ) {
        // 语音条（v0.43.1）：菜单第一项是「转文字 / 取消转文字」开关。
        // 识别失败（content 为空）的语音条不给这个入口——点开也是空的。
        if (onToggleTranscript != null) {
            Spacer(Modifier.height(14.dp))
            NeuSheetRow(
                icon = MIcons.FileText,
                title = if (transcriptShown) "取消转文字" else "转文字",
                subtitle = if (transcriptShown) "收起语音下方的文字" else "把这段语音识别成文字显示在下方",
                onClick = onToggleTranscript,
            )
        }
        if (onViewReasoning != null) {
            Spacer(Modifier.height(14.dp))
            NeuSheetRow(
                icon = MIcons.Brain,
                title = "查看思考过程",
                subtitle = "这段回复生成前的推理过程",
                onClick = onViewReasoning,
            )
        }
        Spacer(Modifier.height(if (onViewReasoning != null || onToggleTranscript != null) 10.dp else 14.dp))
        NeuSheetRow(
            icon = MIcons.Copy,
            title = "复制全文",
            subtitle = "",
            onClick = onCopy,
        )
        Spacer(Modifier.height(10.dp))
        if (canDelete) {
            NeuSheetRow(
                icon = MIcons.Delete,
                title = "删除消息",
                subtitle = "",
                onClick = onDelete,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * 思考链全文（v0.41.4）：推理类模型生成正文前的思维过程，等宽行距、灰字、可滚动。
 * 顶部一句固定说明，避免用户把模型的自我对话当成"它对我说的话"。
 */
@Composable
private fun ReasoningSheet(content: String, onDismiss: () -> Unit) {
    NeuSheetScaffold(title = "思考过程", onDismiss = onDismiss) {
        Spacer(Modifier.height(12.dp))
        Text(
            "模型在给出回复之前的推理草稿，不构成回复内容。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MutedText,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .neuSurface(14.dp, inset = true)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                content,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.height(10.dp))
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
 * 语音态下的「按住 说话」条（v0.43.1）：占输入框那一格，按住才录音、上滑取消。
 * 高度与 [NeuChatInput] 的 40dp 对齐，切来切去不会跳。
 */
@Composable
private fun VoiceHoldBar(
    recording: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    // ⚠️ 颜色令牌只能在 composable 作用域读（`@ReadOnlyComposable get()`），
    // 不能直接在 `background(...)` 的 lambda 里引用——先在这里取成局部值。
    val trackColor = NeuTrack
    val surfaceColor = NeuSurface
    var cancelArmed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(if (recording) trackColor else surfaceColor)
            .border(1.dp, NeuHairline, shape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        cancelArmed = false
                        dragging = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onStart()
                        var released = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val dy: Float = change.position.y - down.position.y
                            if (dy < -80f && !cancelArmed) cancelArmed = true
                            else if (dy >= -80f) cancelArmed = false
                            if (!change.pressed) {
                                released = true
                                change.consume()
                                break
                            }
                        }
                        dragging = false
                        if (released && !cancelArmed) onFinish() else onCancel()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                recording && cancelArmed -> "松开 取消"
                recording -> "松开 发送"
                else -> "按住 说话"
            },
            fontSize = 14.sp,
            fontWeight = if (recording) FontWeight.Medium else FontWeight.Normal,
            color = if (recording && cancelArmed) NeuDanger
            else if (recording) TextPrimary else MutedText,
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

    fun release() = stop()
}

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
    is ChatListItem.DayHeader -> "d$idx"
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
    onLongPress: () -> Unit,
) {
    val isUser = message.isFromUser
    val isHeart = message.content == "❤️"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = if (gapBefore) 8.dp else 0.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        when {
            isHeart -> Text("❤️", fontSize = 44.sp)
            isUser -> UserBubble(message.content, maxWidth, onLongPress)
            else -> AiBubble(message.content, maxWidth, onLongPress)
        }
    }
}

/** AI 气泡：凹陷纸面；长按弹操作菜单 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiBubble(
    content: String,
    maxWidth: androidx.compose.ui.unit.Dp,
    onLongPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .widthIn(max = maxWidth)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .neuSurface(18.dp, color = MaterialTheme.colorScheme.surface, elevation = 2.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        if (content.isBlank()) {
            TypingDots()
        } else {
            MarkdownText(content)
        }
    }
}

/** 用户气泡：纯黑药丸 + 纸叠影（Figma 界面唯一强调色） */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    content: String,
    maxWidth: androidx.compose.ui.unit.Dp,
    onLongPress: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier
            .widthIn(max = maxWidth)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                },
            )
            .neuSurface(22.dp, color = NeuAccent, elevation = 3.dp)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Text(content, color = AccentInk, fontSize = 15.sp, lineHeight = 21.sp, letterSpacing = (-0.1).sp)
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as? android.content.ClipboardManager ?: return
    cm.setPrimaryClip(android.content.ClipData.newPlainText("menghuan", text))
    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
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

/** 单聊搜索结果行：来源 + 时间 + 内容预览（点击退出搜索态） */
@Composable
private fun ChatSearchRow(message: ChatMessage, fromLabel: String, onClick: () -> Unit) {
    val time = Instant.ofEpochMilli(message.timestamp).atZone(ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))
    Column(
        Modifier
            .fillMaxWidth()
            .neuRowPress(onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fromLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.width(8.dp))
            Text(time, fontSize = 11.sp, color = com.menghuan.app.ui.theme.FaintText)
        }
        Spacer(Modifier.height(3.dp))
        Text(
            message.content.replace('\n', ' '),
            fontSize = 14.sp,
            color = MutedText,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
