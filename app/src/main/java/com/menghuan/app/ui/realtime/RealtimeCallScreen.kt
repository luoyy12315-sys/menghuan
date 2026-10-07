package com.menghuan.app.ui.realtime

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.realtime.RealtimeCallState
import com.menghuan.app.realtime.visual.VisualRuntime
import com.menghuan.app.data.CallRingtone
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.EaseOutQuart
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.rememberNeuPressState
import com.menghuan.app.ui.common.staggerEnter
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuBackground
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import com.menghuan.app.ui.settings.SettingsRoutes
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.personaBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 实时语音通话页（v0.47.0 重构）：无顶栏（用户反馈与中部头像重复），主视觉 =
* 中部大头像（说话时呼吸脉动）+ 名字 + 状态；字幕做成歌词式——贴底、最新一两句
* 清晰突出、越旧的越淡，顶部用背景色渐变收束；底部静音/挂断大圆钮。
 *
 * v0.60：**视频通话改为全屏铺满**（用户要求 9:16 竖屏全屏铺满、不黑边不拉伸）——
 * 画面是垫在最底层的背景（cover 排布，见 [CallVisualSlot]），名字/状态/字幕/操作钮叠在它上面，
 * 上下各补一层暗色渐变托底保证白字可读。以前画面是按素材比例排的一张卡片、夹在名字与字幕之间，
 * 竖屏素材一进来整列就比屏幕还高，把字幕与静音/挂断整个挤出屏幕——真机上的"视频通话没法用"。
 */
@Composable
fun RealtimeCallScreen(
    personaId: Long,
    onBack: () -> Unit,
    /** v0.51.0：缺模型 / 缺 TTS 配置时跳全屏设置页（设置链已从弹层改成整页） */
    onOpenSettings: (String) -> Unit = {},
    /** v0.53.7：这通电话是不是 AI 主动打来的（决定谁先开口 + 记录卡落哪一侧） */
    initiatedByAi: Boolean = false,
    /** v0.53.7：AI 打来时已经生成好的开场白 */
    greeting: String = "",
    /** v0.58.0：视频通话模式（主视觉位换成 Live2D / 伪视频，没有形象时仍回落到头像） */
    videoMode: Boolean = false,
    vm: RealtimeCallViewModel = viewModel(
        key = "call_${personaId}_${if (initiatedByAi) "ai" else "me"}_${if (videoMode) "video" else "voice"}",
        factory = RealtimeCallViewModel.Factory(personaId, initiatedByAi, greeting, videoMode),
    ),
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
    }
    // v0.53.7：**延迟接听** —— 我打给 AI 时先"嘟"几秒 TA 才接，秒接反而显得假（用户要的真实感）。
    // 延迟期间只响铃、不启动引擎；到点才 start（那时 AI 才真正"拿起电话"）。
    var answerDelayDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val delaySec = runCatching {
            (context.applicationContext as com.menghuan.app.MenghuanApp)
                .settingsRepository.aiAnswerDelaySeconds.first()
        }.getOrDefault(0)
        if (delaySec > 0) delay(delaySec * 1000L)
        answerDelayDone = true
    }
    LaunchedEffect(hasPermission, answerDelayDone) {
        when {
            !hasPermission -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            answerDelayDone -> vm.start()
        }
    }
    // 返回键 = 挂断；ENDED 后延迟退出，给挂断提示音留出时间
    BackHandler { vm.hangUp() }
    // v0.58.0：视觉层跟着页面生命周期走 —— 切后台/锁屏停掉渲染与解码（省电、
    // 也避免 EGL 上下文被系统回收后乱画），回前台只重新订阅状态，**不重建通话**。
    // 语音通话没有视觉层，这两个回调都是空操作。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> vm.pauseVisual()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> vm.resumeVisual()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(ui.state) {
        if (ui.state == RealtimeCallState.ENDED) {
            delay(650)
            onBack()
        }
    }

    // v0.53.7：**去电也响铃声**（用户要求"我打给 AI 也能选铃声"）——
    // 从进页的"连接中"一直响到真正接通；一旦 AI 开始说话就停，别盖住它。
    // 用的是该角色的专属铃声（角色专属 → 设置里的默认铃声 → 系统默认铃声），来电去电同一套规则。
    val appContainer = context.applicationContext as com.menghuan.app.MenghuanApp
    var dialPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    var dialFallback by remember { mutableStateOf<android.media.Ringtone?>(null) }
    LaunchedEffect(ui.state) {
        val connecting = ui.state == RealtimeCallState.IDLE || ui.state == RealtimeCallState.CONNECTING
        if (connecting && dialPlayer == null && dialFallback == null) {
            val custom = runCatching { appContainer.settingsRepository.ringtoneForPersona(personaId) }
                .getOrDefault("")
            val player = custom.takeIf { it.isNotBlank() }?.let { CallRingtone.loopPlayer(it) }
            if (player != null) {
                dialPlayer = player
            } else {
                dialFallback = runCatching {
                    android.media.RingtoneManager.getRingtone(
                        context,
                        android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                    )
                }.getOrNull()?.also { runCatching { it.play() } }
            }
        } else if (!connecting) {
            runCatching { dialPlayer?.stop() }
            runCatching { dialPlayer?.release() }
            dialPlayer = null
            runCatching { dialFallback?.stop() }
            dialFallback = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { dialPlayer?.stop() }
            runCatching { dialPlayer?.release() }
            runCatching { dialFallback?.stop() }
        }
    }

    val persona = ui.persona
    val name = persona?.name ?: "…"
    val active = ui.state in setOf(
        RealtimeCallState.CONNECTING, RealtimeCallState.LISTENING, RealtimeCallState.USER_SPEAKING,
        RealtimeCallState.AI_THINKING, RealtimeCallState.AI_SPEAKING, RealtimeCallState.INTERRUPTED,
    )
    val pulsing = ui.state == RealtimeCallState.AI_SPEAKING || ui.state == RealtimeCallState.USER_SPEAKING
    // 头像呼吸（v0.55.0 加；**v0.56.5 改成恒定跑**）：原来只有 pulsing 时才呼吸、其余状态钉在 1.0，
    // 于是"角色思考"和"角色听我说话"两态下头像**大小就不一样**（用户点名的第二处差异）。
    // 现在全程同一套 0.985 ↔ 1.035 呼吸 → 两态大小完全一致；"谁在说话"仍由脉冲环表达。
    // 值只在 graphicsLayer 的 lambda 里读（延迟读），每帧只刷渲染层，**不触发重组、不重新布局**。
    val breath = rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0.985f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(950, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathScale",
    )
    val ringInk = TextPrimary

    // v0.55.0：通话计时。UiState 里没有 startedAt，所以页面自己从"进入活动态"起算 ——
    // 不碰引擎、不改仓库，纯 UI 层；状态切回准备/结束就停表，再回来接着走。
    val timingStates = remember {
        setOf(
            RealtimeCallState.CONNECTED, RealtimeCallState.LISTENING,
            RealtimeCallState.USER_SPEAKING, RealtimeCallState.AI_THINKING,
            RealtimeCallState.AI_SPEAKING, RealtimeCallState.INTERRUPTED,
        )
    }
    var elapsedSec by remember { mutableStateOf(0) }
    LaunchedEffect(ui.state) {
        if (ui.state !in timingStates) return@LaunchedEffect
        val base = elapsedSec
        val from = System.currentTimeMillis()
        while (isActive) {
            delay(500)
            elapsedSec = base + ((System.currentTimeMillis() - from) / 1000).toInt()
        }
    }

    // v0.60：视频通话的主视觉层。本页是视频模式 + 视觉引擎已创建 + runtime 是 Ready → 挂画面层。
    // v0.61.1（用户点名）：**"挂上画面层"和"切到视频布局"必须分开**。
    // Ready 只代表视图挂上了、模型开始加载；`Ready.drawn` 才代表真的画出了一帧。
    // 以前两者是同一个条件，于是用户先看到几秒"空画面 + 顶栏"、角色才蹦出来 ——
    // 那几秒页面长得和修复前一模一样，等于白等。现在加载期间保持居中大头像那一套。
    val videoVisual = if (!videoMode) null else
        vm.visualEngine?.let { e -> (ui.visualRuntime as? VisualRuntime.Ready)?.let { r -> e to r } }
    val videoVisualOn = videoVisual != null
    val videoPictureOn = videoVisual?.second?.drawn == true

    // v0.60.1（用户点名照参考图改）：**语音通话的头像按屏幕比例放大、并挪到屏高上三分之一处**。
    // 参考图量出来是：圆头像直径 ≈ 屏宽 59%，中心 ≈ 屏高 38.7%（含状态栏那条）。
    // 这里用屏幕比例而不是写死 dp（AGENTS 布局规矩：等比例元素一律走比例，换机型才不会走形）。
    // ⚠️ 只有"画面还没画出来"的这一支（语音通话 + 视频通话加载中/回落头像）走这个尺寸；
    //    视频通话真的出画面时走另一支的居中顶栏，两者互不影响。
    BoxWithConstraints(
        // v0.61.1：恢复恒定铺 NeuBackground。以前"有画面就透明"是给 SurfaceView 挖洞用的，
        // TextureView 走正常绘制，角色透明处本来就该透出主题底色，不需要再留窗口白边
        Modifier.fillMaxSize().background(NeuBackground),
    ) {
        val avatarSize = maxWidth * 0.59f
        val avatarCanvasSize = avatarSize * CallAvatarCanvasRatio
        // 0.37 而不是 0.387：AdaptiveScreen 已经吃掉状态栏那一条，
        // 从内容区顶部算起要扣掉它，落回全屏才是参考图那个位置
        val avatarTopGap = (maxHeight * 0.37f - avatarCanvasSize / 2f).coerceAtLeast(0.dp)
        // ① 画面层：垫在最底下、铺满整屏（v0.60 用户要求 9:16 竖屏全屏铺满）。
        //    画面层自己按 cover 铺满整块（伪视频在 VideoVisualEngine 里给 TextureView 设变换矩阵，
        //    Live2D 由 fitProjection 自己居中适配）：不拉伸、不留边、居中裁掉溢出的一边。
        //    v0.61.1：**首帧之前整块是透明的，画出来才淡入** —— 省得角色"啪"地出现。
        if (videoVisualOn) {
            val slotAlpha by animateFloatAsState(
                targetValue = if (videoPictureOn) 1f else 0f,
                animationSpec = tween(260, easing = EaseOutQuart),
                label = "visualSlotAlpha",
            )
            videoVisual?.let { (engine, _) ->
                CallVisualSlot(engine = engine, modifier = Modifier.fillMaxSize().alpha(slotAlpha))
            }
        }

        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 主视觉：大头像 + 名字 + 状态。**高度由内容决定**（不放 weight）——
            // 矮屏上「缺模型」提示按钮跟着长时不能被裁掉，剩余高度全给字幕区。
            Column(
                Modifier
                    .fillMaxWidth()
                    // v0.55.0 进场编排：头像块 → 字幕区 → 操作钮，三块依次抬起，
                    // 与路由的横向滑入叠成"页面铺开"，而不是整屏内容同一帧出现。
                    // v0.61.1：**恢复无条件生效**。以前"有画面时不加"是因为画面是 SurfaceView，
                    // post-layout 变换可能与曲面对不上合成；现在 Live2D 也走 TextureView
                    // （正常 View 绘制），这个顾虑不存在了，两条通话共用同一套进场节奏。
                    .staggerEnter(0, stepMs = 60, slideDp = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // v0.60.1（用户点名）：**视频通话出画面时改顶栏形状** ——
                // 名字换成头像、贴最上方，状态挂在头像右边、时间在下面。
                // 判据是 videoPictureOn（真的画出一帧了），不是"视图挂上了"：
                // 加载那几秒仍走 else 那一套居中大头像，两颗头像也不会同时出现。
                // ⚠️ else 那条还兜着"没有形象 / 视觉坏了"的回落（方案文档 §二十四：视觉失败 ≠ 通话失败）。
                val statusLabelNow =
                    if (!answerDelayDone && hasPermission) "正在呼叫…" else stateLabel(ui)
                if (videoPictureOn) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NeuAvatar(
                            emoji = persona?.avatarEmoji ?: "🙂",
                            imagePath = persona?.avatarImagePath.orEmpty(),
                            size = 42.dp,
                            ringBrush = personaBrush(personaId),
                        )
                        Spacer(Modifier.width(10.dp))
                        Crossfade(targetState = statusLabelNow, label = "stateLabel") { label ->
                            CallStatusChip(label = label, live = active)
                        }
                    }
                } else {
                    Spacer(Modifier.height(avatarTopGap))
                    // 说话时头像外扩两圈同心环（v0.55.0）：原来只有头像自己 0.985↔1.035 缩放，
                    // 在整屏留白的通话页里几乎看不出"谁在说话"。加两圈缓慢外扩、边扩边淡的环，
                    // 声源的方向感才立得住。环走 Canvas 单节点，开着整通电话也不产生重组。
                    //
                    // ⚠️ v0.56.5 修「角色思考 / 角色听我说话头像位置不一样」：
                    // 根因是这个容器原来**尺寸跟着环走**——`CallPulseRings` 在 !active 时直接 return
                    // （连节点都不占），active 时才带尺寸进来；于是整块被撑高，
                    // 居中的头像被整体往下推（上面一行名字、下面状态胶囊也跟着挪）。
                    // 现在把尺寸钉在**画布本身**（恒等于头像边长 × [CallAvatarCanvasRatio]），
                    // 环只在这个画布内画或不画，头像的位置就与状态无关了。
                    Box(Modifier.size(avatarCanvasSize), contentAlignment = Alignment.Center) {
                        CallPulseRings(
                            active = pulsing,
                            color = ringInk,
                            modifier = Modifier.fillMaxSize(),
                        )
                        NeuAvatar(
                            emoji = persona?.avatarEmoji ?: "🙂",
                            imagePath = persona?.avatarImagePath.orEmpty(),
                            size = avatarSize,
                            ringBrush = personaBrush(personaId),
                            modifier = Modifier.graphicsLayer {
                                scaleX = breath.value
                                scaleY = breath.value
                            },
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    // v0.60.1（用户点名）：**这一套不再显示名字** —— 头像就是身份，
                    // 大圆头像 + 渐变环已经足够认出是谁，那行 20sp 的名字反而抢位置。
                    Crossfade(targetState = statusLabelNow, label = "stateLabel") { label ->
                        CallStatusChip(label = label, live = active)
                    }
                }
                // 打通之后才走字：0 秒不显示，免得刚接通就挂个 00:00
                if (elapsedSec > 0) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "%02d:%02d".format(elapsedSec / 60, elapsedSec % 60),
                        fontSize = 12.sp,
                        // v0.61.1：不再为"压在画面上"改白色 —— 用户点名去掉蒙层，
                        // 底色就是主题色，用回正常的灰阶才读得清
                        color = MutedText,
                        letterSpacing = 0.8.sp,
                    )
                }
                // v0.58.0：形象坏了要说一句（静默降级是这个项目排查最久的一类问题）。
                // 只说现象与后果，不教操作——电话正在讲，这时候不该让用户读段落。
                (ui.visualRuntime as? VisualRuntime.Degraded)?.let { d ->
                    Spacer(Modifier.height(6.dp))
                    Text(d.reason, fontSize = 12.sp, color = NeuDanger, textAlign = TextAlign.Center)
                }
                if (ui.error != null) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier
                            .neuSurface(999.dp, color = NeuTrack)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(ui.error ?: "", fontSize = 12.sp, color = NeuDanger)
                    }
                }
                if (ui.llmMissing || ui.asrMissing || ui.ttsMissing || !hasPermission) {
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier
                            .neuSurface(999.dp, color = NeuAccent)
                            .clickable {
                                when {
                                    !hasPermission -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    ui.asrMissing -> onOpenSettings(SettingsRoutes.ASR_MODEL)
                                    ui.ttsMissing -> onOpenSettings(SettingsRoutes.TTS_CONFIG)
                                    // llmMissing 没有可跳的页面（接口配置在「我的 · 设置 · API 配置」），只提示
                                }
                            }
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                    ) {
                        Text(
                            when {
                                !hasPermission -> "授予麦克风权限"
                                ui.llmMissing -> "实时通话尚未配置，先在聊天里配好 AI 接口"
                                ui.asrMissing -> "语音识别模型未下载，点这里下载"
                                else -> "语音合成未配置：只有字幕没有声音，点这里配置"
                            },
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AccentInk,
                        )
                    }
                }
            }

            // 字幕区（v0.54.0；v0.54.1 限高 + 每段最多两行）：
            // 最多露出最近约 4 段，更早的历史默认被挡在可视区上方，向上翻才看得到；
            // 贴着底部时新句自动跟随，用户一向上翻就**不再被拽回**，
            // 右下角给一颗「回到底部」按钮，随时一键回到最新。
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .staggerEnter(1, stepMs = 60, slideDp = 16.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                // v0.61.1（用户点名）：**三层暗色蒙层全部删掉**，字幕不再压暗色托底，
                // 字色回到主题灰阶（蒙层没了之后白字压在浅色角色上根本看不见）。
                CallSubtitlePanel(
                    lines = remember(ui.turns, ui.userPartial, ui.aiPartial) { buildLines(ui) },
                    name = name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = SubtitleViewportHeight)
                        .padding(horizontal = 30.dp),
                )
            }

            // 底部操作：静音 + 挂断。
            // v0.61.1（用户点名）：整行不再铺那条从透明到黑的渐变托底 ——
            // 两颗钮本来就有自己的圆底（白面 / 危险色），要的是"各自悬浮"的效果。
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 40.dp)
                    .staggerEnter(2, stepMs = 60, slideDp = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallActionButton(
                    size = 60.dp,
                    container = if (ui.muted) NeuAccent else NeuSurface,
                    tint = if (ui.muted) AccentInk else TextPrimary,
                    icon = if (ui.muted) MIcons.MicOff else MIcons.Mic,
                    description = if (ui.muted) "取消静音" else "静音",
                    enabled = active,
                    // 静音是"这一刻开着"的状态，加一圈发丝环表示它正生效
                    ringed = ui.muted,
                    onClick = { vm.toggleMute() },
                )
                Spacer(Modifier.width(30.dp))
                CallActionButton(
                    size = 68.dp,
                    container = NeuDanger,
                    tint = AccentInk,
                    icon = MIcons.PhoneOff,
                    description = "挂断",
                    enabled = active,
                    onClick = { vm.hangUp() },
                )
            }
        }
    }

}

/**
 * 字幕可视高度 = **一屏最多 6 行文字**（v0.54.3 用户拍板，非"6 段"）。
 * 按 20sp 行高：6 行 ≈ 120dp，加每段上下各 3dp 内边距与列表两端 24dp contentPadding，
 * 取 150dp。行数由可视高度天然封顶 —— 长句再长也只会占满这一屏。
 */
private val SubtitleViewportHeight = 150.dp

/**
 * 通话页头像**画布**与头像本体的边长比（v0.56.5 钉死的是 196 : 124，同一个比值）。
 *
 * 画布必须比头像大一圈，脉冲环才有地方画；而**画布尺寸必须恒定**（不跟着环的有无涨缩），
 * 否则"角色思考"与"角色听我说话"两态下头像位置会不一样（见头像块处的注释）。
 * v0.60.1 起头像边长改成屏宽比例，画布就按这个比值跟着等比放大。
 */
private const val CallAvatarCanvasRatio = 196f / 124f

/**
 * 字幕面板（v0.54.0）：歌词式排版 + 可滚动完整记录。
 *
 * 交互规则（与 ChatScreen 的贴底跟随同一套做法，用户在那儿已经习惯）：
 * ① 贴底时新句自动跟随到底；
 * ② **只要用户自己向上翻过，就停止自动滚动**，流式增量不再打断翻阅；
 * ③ 离开底部时右下角浮出「↓ 回到底部」，点一下立刻回到最新并恢复跟随。
 *
 * 显示规则（v0.54.3）：面板可视高度只放得下**一屏 6 行文字**（每段最多占满这 6 行），
 * 更早的历史默认藏在可视区上方，向上翻才看得到。
 *
 * 渐隐是"歌词式"的（v0.54.3 加强）：**最新一句最亮、最大**，越往上越**淡且越小**，
 * 像"远离了"一样缩小后退；淡/小都设了可读下限（alpha≥0.6、scale≥0.82），
 * 翻到通话开头时不会整屏都是"看不清的灰字"。
 */
@Composable
private fun CallSubtitlePanel(
    lines: List<CallLine>,
    name: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 是否贴着底部（离末尾 ≤1 行就算贴着：半行也允许跟随，不然观感很"卡"）
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= info.totalItemsCount - 2
        }
    }

    // 新增句子 → 贴底才滚到底（首次无动画直跳，避免从列表头横扫）
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(lines.size) {
        if (lines.isEmpty()) return@LaunchedEffect
        val target = lines.lastIndex
        if (!settled) {
            listState.scrollToItem(target)
            settled = true
        } else if (atBottom) {
            listState.animateScrollToItem(target)
        }
    }

    // 流式增量（partial 逐字变长）：LazyColumn 按"首个可见项 + 偏移"锚定，
    // 视口不会自动跟到底，长出来的那截会被顶出屏幕。按末项高度差补滚。
    LaunchedEffect(listState, lines.size) {
        var lastTailHeight = -1
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: 0 }
            .collect { height ->
                val info = listState.layoutInfo
                val near = (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 2
                if (near && lastTailHeight in 1 until height) {
                    listState.scrollBy((height - lastTailHeight).toFloat())
                }
                lastTailHeight = height
            }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 10.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            itemsIndexed(
                items = lines,
                // key 里刻意不带 text：partial 每帧都在变，带上会让整条列表每帧重建
                key = { i, line -> "$i|${line.isUser}|${line.isPartial}" },
            ) { i, line ->
                val fromEnd = lines.lastIndex - i
                val isNewest = fromEnd == 0
                // 渐隐阶梯：**最新一句最亮，越旧越淡**（歌词式）。
                // v0.54.1 修正方向 —— 原来这排数反着取了（最新的 fromEnd=0 拿到 0.3f），
                // 导致正在说的那句被压成灰、整屏像蒙了一层；现在改成线性递减 + 可读下限，
                // 翻出去的历史不会淡到看不清。
                val fade = (1f - fromEnd * 0.13f).coerceAtLeast(0.6f)
                // v0.54.3：越旧的字还要**越小**（"远离了"的观感），同样是线性递减 + 下限，
                // 免得翻出去的历史缩成看不清的小字。
                val recede = (1f - fromEnd * 0.045f).coerceAtLeast(0.82f)
                Text(
                    subtitleText(line, name, MutedText),
                    fontSize = if (isNewest) 15.sp else 14.sp,
                    lineHeight = if (isNewest) 22.sp else 20.sp,
                    fontWeight = if (isNewest) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        isNewest -> TextPrimary
                        fromEnd < 5 -> TextSecondary
                        else -> MutedText
                    },
                    // v0.54.3：每段最多占满一屏 6 行（超了截断省略号）——
                    // 配合可视高度封顶，一屏最多只露 6 行文字。
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .scale(recede)
                        .alpha(fade),
                )
            }
        }

        // 离开底部 → 浮出「回到底部」。不用 fadeOut 收尾（会留一帧半透明字）。
        AnimatedVisibility(
            visible = !atBottom && lines.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = scaleIn(tween(220)) + fadeIn(tween(220)),
            exit = scaleOut(tween(160)) + fadeOut(tween(120)),
        ) {
            NeuCircleButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(lines.lastIndex)
                        settled = true
                    }
                },
                accent = true,
                size = 38.dp,
            ) {
                Icon(
                    MIcons.ChevronDown,
                    contentDescription = "回到底部",
                    tint = AccentInk,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

/** 字幕行：text + 是否用户 + 是否已收口（partial = 正在进行） */
private data class CallLine(val text: String, val isUser: Boolean, val isPartial: Boolean)

/**
 * v0.54.0：**保留整通电话的完整记录**（原来只 takeLast(5)，翻不回去）。
 * 上限 200 条纯粹是防御——极端情况下不让内存无限涨；正常一通电话远不到。
 */
private fun buildLines(ui: RealtimeCallUiState): List<CallLine> {
    val closed = ui.turns.takeLast(200).map { CallLine(it.text, it.speaker == "user", false) }
    val live = buildList {
        if (ui.userPartial.isNotBlank()) add(CallLine(ui.userPartial, true, true))
        if (ui.aiPartial.isNotBlank()) add(CallLine(ui.aiPartial, false, true))
    }
    return closed + live
}

private fun subtitleText(line: CallLine, name: String, labelColor: Color) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = labelColor)) {
        append(if (line.isUser) "你 " else "$name ")
    }
    append(line.text)
    if (line.isPartial) append(" …")
}

private fun stateLabel(ui: RealtimeCallUiState): String = when (ui.state) {
    RealtimeCallState.IDLE -> "正在准备…"
    RealtimeCallState.CONNECTING -> "正在连接…"
    RealtimeCallState.CONNECTED -> "已连接"
    RealtimeCallState.LISTENING -> "正在听你说话"
    RealtimeCallState.USER_SPEAKING -> "正在听你说话"
    RealtimeCallState.AI_THINKING -> "思考中…"
    RealtimeCallState.AI_SPEAKING -> "${ui.persona?.name ?: "对方"} 正在说"
    RealtimeCallState.INTERRUPTED -> "正在听你说话"
    RealtimeCallState.DISCONNECTING -> "正在挂断…"
    RealtimeCallState.ENDED -> "通话已结束"
    RealtimeCallState.ERROR -> "通话出错"
}

/**
 * 头像外的同心脉冲环（v0.55.0）：两圈环错开半周期、缓慢外扩并边扩边淡，
 * 表示"这一侧正在出声"。
 *
 * 走 Canvas 单节点 + `rememberInfiniteTransition` 的线性 0→1 循环：
 * 半径与透明度都只由这一个进度算出来，**不产生任何重组**，
 * 所以整通电话都开着也不会拖累帧率（用两个 `Box` + `animateFloatAsState` 会每帧重组两次）。
 */
@Composable
private fun CallPulseRings(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    if (!active) return
    val phase by rememberInfiniteTransition(label = "callRings").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing)),
        label = "ringPhase",
    )
    val ink = color
    Canvas(modifier) {
        val maxR = size.minDimension / 2f
        repeat(2) { k ->
            val p = (phase + k * 0.5f) % 1f
            drawCircle(
                color = ink,
                radius = maxR * (0.62f + p * 0.36f),
                alpha = (1f - p) * 0.22f,
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
    }
}

/**
 * 通话状态胶囊（v0.55.0）：凹陷灰槽 + 一颗状态点 + 状态文案。
 *
 * 那颗点是"进行中"的信号 —— **呼吸 = 通话真的在进行，静止 = 已结束 / 未接通**。
 * 原来只有一行灰字，两种状态长得一模一样，看不出电话还通着没有。
 */
@Composable
private fun CallStatusChip(label: String, live: Boolean) {
    val dotAlpha by animateFloatAsState(
        targetValue = if (live) 1f else 0.3f,
        animationSpec = tween(220, easing = EaseOutQuart),
        label = "statusDot",
    )
    val dotScale by animateFloatAsState(
        targetValue = if (live) 1f else 0.8f,
        animationSpec = tween(220, easing = EaseOutQuart),
        label = "statusDotScale",
    )
    Row(
        Modifier
            .neuSurface(999.dp, color = NeuTrack, inset = true)
            .padding(horizontal = 13.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 不用 neuSurface(inset = true)：凹陷分支固定涂 NeuTrack、不吃 color，
        // 这颗点要的是"文字色的深浅"，所以直接用带 alpha 的强调色
        Box(
            Modifier
                .size(6.dp)
                .scale(dotScale)
                .background(TextPrimary.copy(alpha = dotAlpha), CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 12.5.sp, color = TextSecondary)
    }
}

/**
 * 大号圆形操作钮（静音/挂断）：无涟漪缩放反馈，容器色调用方指定。
 *
 * v0.55.0：补两处以前没有的反馈 ——
 * - **禁用态淡出**（原来 enable=false 时按钮还是全亮，看不出"现在点不动"）；
 * - **生效态内圈**（静音开着时加一圈发丝环，表示"这个状态正生效"）。
 */
@Composable
private fun CallActionButton(
    size: Dp,
    container: Color,
    tint: Color,
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    /** 是否画一圈外环（表示"这个开关正生效"，如静音中） */
    ringed: Boolean = false,
    onClick: () -> Unit,
) {
    val press = rememberNeuPressState()
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.35f,
        animationSpec = tween(200),
        label = "callBtnAlpha",
    )
    Box(
        Modifier
            .scale(press.scale)
            .alpha(alpha)
            .size(size)
            .neuSurface(size / 2, color = container)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 生效环画在按钮**内部**（不往外扩）：往外画会被父节点裁掉，
        // 而且和"发丝线描边 = 界面唯一分隔手段"的语言一致 —— 不用第二套装饰。
        if (ringed) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(5.dp)
                    .neuSurface(999.dp, color = Color.Transparent),
            )
        }
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.44f))
    }
}
