package com.menghuan.app.ui.realtime

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.menghuan.app.data.CallBackgroundStore
import com.menghuan.app.data.CallRingtone
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.EaseOutQuart
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.StageBackgroundLayer
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.rememberNeuPressState
import com.menghuan.app.ui.common.staggerEnter
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuBackground
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import com.menghuan.app.ui.settings.SettingsRoutes
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.personaBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 实时通话页（v0.47.0 重构 · v0.60 全屏铺满 · v0.64.0 改分区 + 右滑纯净态 · **v0.67.0 角色能点**）。
 *
 * ## 这一版的布局（用户拍板）
 *
 * ```
 * ┌──────────────────────────────┐
 * │  ● 说话中           01:23     │ ← 顶栏：状态胶囊 + 计时，**常驻**
 * │                              │
 * │         [角色区]              │ ← 角色完整落在这里；
 * │      Live2D / 伪视频          │    任何 UI 都不进入这一块
 * │                              │
 * │ (摆手)(摇头)(翻跟斗)          │ ← 动作胶囊：**只有 Live2D 才有**，点一下当场演
 * ├──────────────────────────────┤ ← 对话台（页面底色）
 * │  你 今天想聊点什么呀           │
 * │  阿布 我在听呢，你慢慢说       │ ← 字幕最多 3 行，上滑看历史
 * │      ( 🎤 )    ( 📞 )        │ ← 静音 / 挂断
 * └──────────────────────────────┘
 *      ← 右滑：对话台与动作胶囊一起滑到右边去，角色铺满全屏，顶栏仍在
 * ```
 *
 * ## 四条设计决定
 *
 * 1. **字幕与按钮不压在角色身上，是结构性的，不是靠蒙版救的。**
 *    v0.61.1 删掉三层暗色蒙层之后，字幕压在浅色角色上就时常读不清（灰字压灰面）。
 *    现在角色被投影完整收进上方那块（`setViewportBottomInset`），
 *    下面那块本来就是"画面里没有角色"的地方 —— 于是字幕用正常灰阶、按钮不用任何托底，
 *    既清晰又不脏。**不要再加渐变蒙版**：那是把上一个版本的问题原样搬回来。
 * 2. **画面层始终铺满整屏、尺寸恒定**。分区只改引擎的投影/矩阵，**不改视图尺寸** ——
 *    否则右滑那 320ms 里 GL 表面要重新量几十次，`setRenderTargetSize` 每次重建离屏缓冲，
 *    观感是"滑动时画面抖"（v0.60 那轮把画面改成全屏时踩过尺寸反复变的坑）。
 * 3. **右滑进纯净态、左滑唤回**。用户点名要的表现是"下面的 UI 都不见、好像是移到了右边"，
 *    所以这里是**横移出场**（不是淡出），并且跟手 —— 手指走多远就移多远，
 *    松手按位置与方向弹簧归位。**顶栏在纯净态里保留**（用户点名"顶部栏的 UI 还要"）。
 * 4. **点角色有反应，但只有角色那一块算**（v0.67.0，用户点名要通话里的互动入口）：
 *    点按判定挂在整页（用户的手会落在画面中间），但**落在对话台那一条上不算** ——
 *    点字幕是想读字，不是想让 TA 摆手。判定与"点哪儿演什么"都复用角色空间那一套
 *    （`Live2dStage.hit` + `resolveInteraction`），不另立第二份规矩。
 *
 * 语音通话（没有形象）走的还是原来的居中大头像那一套，只是底下同样挂了对话台；
 * 两条路共用同一套字幕与操作钮。**语音通话完全不碰视觉层与动作**（AGENTS §零.7）。
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
    // v0.66.0：这一角色的**视频通话背景**档位（`preset` / `file:<名字>`；没配过=按预设画）。
    // 表很小（每个角色一行），整表收集一次不贵；文件 stat 放在 keyed remember 里，
    // 档位没变就不碰磁盘（同一个角色反复重组不该反复 stat）。
    // ⚠️ **语音通话一个字都不碰这一层**（AGENTS §零.7）：背景只有视频通话会画，
    // 所以连订阅都要门掉 —— 否则语音通话白挂一条 DataStore 流、还要在组合期 stat 一次。
    // flow 用 remember(videoMode) 钉住：每次重组换新实例会让 collectAsState 重新订阅一遍。
    val callBgFlow = remember(videoMode) {
        if (videoMode) appContainer.settingsRepository.callBackground
        else flowOf(emptyMap<Long, String>())
    }
    val callBgMap by callBgFlow.collectAsState(initial = emptyMap())
    val callBg = if (videoMode) callBgMap[personaId].orEmpty() else ""
    val callBgFile = remember(personaId, callBg) {
        if (callBg.isBlank()) null
        else runCatching { CallBackgroundStore.backgroundFile(context, personaId, callBg) }.getOrNull()
    }
    // 字幕按固定节拍刷，不跟着每一个 token 走：
    // AiDelta 一秒能来几十次，而 buildLines 每次都要把最近 200 条整表重铺一遍
    //（每秒上万个临时对象 + 整列重组），换来的只是"字变得慢一点"。
    // 字幕是给人跟读的，80ms 一刷看着完全连续。与同页推 inset 用 snapshotFlow 是同一套思路。
    // 先比"签名"（条数 + 两段进行中的原文）再决定要不要重铺，所以**没人说话时一次都不铺**。
    var subtitleLines by remember { mutableStateOf(buildLines(ui)) }
    var subtitleSig by remember { mutableStateOf(ui.turns.size to (ui.userPartial to ui.aiPartial)) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(SUBTITLE_TICK_MS)
            val sig = ui.turns.size to (ui.userPartial to ui.aiPartial)
            if (sig != subtitleSig) {
                subtitleSig = sig
                subtitleLines = buildLines(ui)
            }
        }
    }
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
    // 头像呼吸（v0.56.5 起恒定跑）：全程同一套 0.985 ↔ 1.035 呼吸，两态大小完全一致；
    // "谁在说话"由脉冲环表达。值只在 graphicsLayer 的 lambda 里读（延迟读），不触发重组。
    val breath = rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0.985f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(950, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathScale",
    )
    val ringInk = TextPrimary

    // 通话计时（v0.55.0）：页面自己从"进入活动态"起算，不碰引擎、不改仓库。
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

    val videoVisual = if (!videoMode) null else
        vm.visualEngine?.let { e -> (ui.visualRuntime as? VisualRuntime.Ready)?.let { r -> e to r } }
    val videoVisualOn = videoVisual != null
    val videoPictureOn = videoVisual?.second?.drawn == true

    // 纯净态进度：0 = 正常分区，1 = 对话台完全滑到右边、角色铺满全屏。
    // ⚠️ 这两个必须声明在 BoxWithConstraints **之外**：手势挂在 BoxWithConstraints 自己的
    // modifier 上，那个位置的 lambda 还没进 BoxWithConstraintsScope，读不到里面声明的状态。
    val spread = remember { Animatable(0f) }
    /**
     * 手指拖动期间的**实时位移**（像素）。
     *
     * 为什么不直接用 `spread.snapTo`：`awaitEachGesture` 那个 lambda 带 `@RestrictSuspension`
     * （Compose 1.6 起的规矩），在里面调任何外部挂起函数**编译就过不去**；
     * 而绕开它的老办法 —— 每个事件 `scope.launch { snapTo }` —— 一次拖动能报 60~120 个事件，
     * 就是原来"滑动的时候画面跟着抖"的来源（主线程被这些一次性协程排满）。
     * 所以拖动期间写这一个普通浮点（非挂起、随便写），松手时才把合起来的值交还给
     * [spread] 去跑弹簧。两处读数（graphicsLayer / snapshotFlow）都是把两者相加，
     * 观感上是一条连续的跟手位移，切换所有权那一帧也不会跳。
     */
    var dragPx by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(NeuBackground)
            // 右滑进纯净态 / 左滑唤回 + 点角色有反应。**都挂在整页**而不是只挂在对话台上：
            // 用户想收起 UI 时，手会落在画面中间那块（角色身上），
            // 只让底下那条窄面板响应等于让他们猜"要从哪儿滑"。
            //
            // ⚠️ 点按与横滑**写在同一个 awaitEachGesture 里**，不是两个 pointerInput：
            // ①`detectTapGestures` 与 `detectHorizontalDragGestures` 分挂两个通道时，
            //    点按那条会先把 Down 消费掉，横滑要等下一个 Move 才发现自己被判死 ——
            //    表现是"得先滑一下、第二下才跟着动"；② 挂在整页时两者抢的本来就是同一根手指。
            //    合成一个循环最省事：水平越过阈值就是滑、没越过就是点，一次判定。
            //    竖直不在这儿管 —— 越过阈值的是水平位移，翻字幕仍然归 LazyColumn。
            // ⚠️ 滑动期间只写 [dragPx]（普通浮点），松手才交给 [spread] 跑弹簧：
            //    `awaitEachGesture` 的 lambda 带 `@RestrictSuspension`，在里面调 `snapTo` 编译不过，
            //    而"每个事件 launch 一条协程去 snapTo"正是上一版滑动发抖的来源（见 dragPx 的注释）。
            .pointerInput(videoPictureOn, ui.stageInteractive) {
                if (!videoPictureOn) return@pointerInput
                // PointerInputScope 自己就是 Density 且带 size，所以这里不需要外层的屏宽
                val widthPx = size.width.toFloat().coerceAtLeast(1f)
                val heightPx = size.height.toFloat().coerceAtLeast(1f)
                // 对话台那一条不算"角色区"：点它是想读字幕，不该让角色突然摆手
                //（InteractionRules 留 OTHER 那一档就是为了挡这个）
                val characterBottomPx = (heightPx - CallConsoleHeight.toPx()).coerceAtLeast(1f)
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastX = down.position.x
                    var accX = 0f
                    var dragging = false
                    // 松手归位：过了一半就往那个方向去，否则弹回原处。
                    // 用弹簧而不是 tween —— 与全项目"状态走弹簧"的语言一致，
                    // 也与底栏横滑切页那套手感同源。
                    // 先把拖出来的那段位移**并进** spread（snapTo 到合起来的值），
                    // 再把 dragPx 清零 —— 顺序反了会看见面板在松手那一帧跳一下。
                    fun settleDrag() {
                        if (!dragging) return
                        val total = (spread.value + dragPx / widthPx).coerceIn(0f, 1f)
                        scope.launch {
                            spread.snapTo(total)
                            dragPx = 0f
                            spread.animateTo(
                                if (total >= 0.5f) 1f else 0f,
                                spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                        }
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            // 这根手指的事件流断了（被别的层抢走、指针消失…）。
                            // 必须按"已经松手"收一次：否则 dragPx 永远留在一半的位移上，
                            // 对话台就卡在那个位置不回弹了。
                            settleDrag()
                            break
                        }
                        if (!change.pressed) {
                            if (dragging) {
                                settleDrag()
                            } else if (ui.stageInteractive) {
                                val p = change.position
                                // 落在对话台那一条上就当没点：角色区只有上面那块
                                if (p.y <= characterBottomPx) {
                                    vm.onStageTap(
                                        ndcX = p.x / widthPx * 2f - 1f,
                                        // 屏幕 y 向下、NDC y 向上，所以要 1 - …
                                        ndcY = 1f - p.y / heightPx * 2f,
                                    )
                                }
                            }
                            break
                        }
                        val x = change.position.x
                        val dx = x - lastX
                        lastX = x
                        accX += dx
                        if (!dragging) {
                            // 只认**水平**越过阈值
                            if (abs(accX) < slop) continue
                            dragging = true
                        }
                        change.consume()
                        // 手指走多远就移多远 —— "跟手"感全靠这一句（非挂起，见 dragPx 的注释）
                        dragPx += dx
                    }
                }
            },
    ) {
        val avatarSize = maxWidth * 0.59f
        val avatarCanvasSize = avatarSize * CallAvatarCanvasRatio
        // 0.37 而不是 0.387：AdaptiveScreen 已经吃掉状态栏那一条，
        // 从内容区顶部算起要扣掉它，落回全屏才是参考图那个位置
        val avatarTopGap = (maxHeight * 0.37f - avatarCanvasSize / 2f).coerceAtLeast(0.dp)

        // 对话台占屏幕的比例。它是"角色区让出来的那一块"，也就是引擎要留的底部可视余量。
        val consoleFraction = (CallConsoleHeight / maxHeight).coerceIn(0f, 0.72f)
        val screenWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        // 进度 = 已归位的那段 + 手指正拖着的那段。两处都要读这个和，
        // 否则松手交接所有权的那一帧面板会跳。
        // （写成局部函数而不是 `val … get()`：局部属性没有 getter，编译不认。）
        fun spreadTotal() = (spread.value + dragPx / screenWidthPx).coerceIn(0f, 1f)

        // 每帧把"角色可用区"推给引擎。走 snapshotFlow 而不是在组合里读 spread.value：
        // 后者会让整页每帧重组一次（320ms ≈ 20 帧），而这里只是把矩阵算对，
        // 不需要重新组合任何东西。
        LaunchedEffect(videoVisualOn, consoleFraction) {
            val engine = videoVisual?.first ?: return@LaunchedEffect
            snapshotFlow { spreadTotal() }.collect { p ->
                engine.setViewportBottomInset(consoleFraction * (1f - p))
            }
        }

        // ⓪ 背景层（v0.66.0 用户点名"视频通话加个通话背景选择"）：**只有视频通话铺**。
        //    语音通话维持现在的页面底色（用户口径），所以这一层挂在 videoMode 那一支里。
        //    它在最底下：Live2D 那层是透出的 GL，画在它背后才看得见；伪视频自己满屏盖住，不受影响。
        //    画法与角色空间共用一份（没选过背景 / 图读不出来 → 都画系统预设，不会"选了却没变化"）。
        if (videoMode) {
            StageBackgroundLayer(
                custom = CallBackgroundStore.isCustom(callBg),
                filePath = callBgFile?.absolutePath.orEmpty(),
            )
        }

        // ① 画面层：垫在最底下、铺满整屏（尺寸恒定，右滑时也不变，见类注释）。
        //    v0.61.1：首帧之前整块是透明的，画出来才淡入 —— 省得角色"啪"地出现。
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

        // ② 顶栏：状态 + 计时。**纯净态里也保留**（用户点名"顶部栏的 UI 还要"）。
        //    只有"画面真的出来了"才浮在画面上；没有画面时状态与计时跟着头像走（下面那一支），
        //    免得同一条信息出现两遍。
        val statusLabelNow = if (!answerDelayDone && hasPermission) "正在呼叫…" else stateLabel(ui)
        if (videoPictureOn) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp)
                    .staggerEnter(0, stepMs = 60, slideDp = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Crossfade(targetState = statusLabelNow, label = "stateLabelTop") { label ->
                    CallStatusChip(label = label, live = active)
                }
                Spacer(Modifier.weight(1f))
                if (elapsedSec > 0) {
                    CallTimeChip("%02d:%02d".format(elapsedSec / 60, elapsedSec % 60))
                }
            }
        }

        // ③ 居中的大头像那一套 —— 只在**没有画面**时出现：
        //    语音通话，以及视频通话加载中 / 形象坏了回落的那些情况（方案文档 §二十四）。
        if (!videoPictureOn) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .staggerEnter(0, stepMs = 60, slideDp = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(avatarTopGap))
                // 尺寸钉在**画布本身**（恒等于头像边长 × CallAvatarCanvasRatio），环只在这个画布内画或不画，
                // 头像位置就与状态无关了（v0.56.5 修"两态头像位置不一样"）
                Box(Modifier.size(avatarCanvasSize), contentAlignment = Alignment.Center) {
                    CallPulseRings(active = pulsing, color = ringInk, modifier = Modifier.fillMaxSize())
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
                Crossfade(targetState = statusLabelNow, label = "stateLabel") { label ->
                    CallStatusChip(label = label, live = active)
                }
                if (elapsedSec > 0) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "%02d:%02d".format(elapsedSec / 60, elapsedSec % 60),
                        fontSize = 12.sp,
                        color = MutedText,
                        letterSpacing = 0.8.sp,
                    )
                }
                // 形象坏了要说一句（静默降级是这个项目排查最久的一类问题）。
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
        }

        // ③′ 动作胶囊（v0.67.0，用户点名"通话页要有让 TA 当场做一个动作的入口"）。
        //
        // 浮在**角色区下沿**、对话台上面那一横条：它属于"看角色"那一半，
        // 所以跟着对话台一起右滑出场（纯净态里不留任何控件，那才叫纯净）。
        //
        // ⚠️ **刻意用居中的普通 Row，不是 LazyRow / horizontalScroll**：可横滚的容器会把自己
        // 那条横向手势吃掉，而"右滑进纯净态"是挂在整页上的 —— 两条抢同一根手指的结果
        // 就是"在这一排胶囊上滑不动、通话收不干净"（v0.67 自检时踩到，别改回去）。
        // 换成不滚的 Row 之后，条数上限收到 4（`QUICK_ACTION_LIMIT`），长名字用省略号截，
        // 一整排最宽也就 344dp，360dp 宽的机器也放得下。
        // ⚠️ 只有 Live2D 形象才出现这一排（`stageInteractive`）：伪视频是一组循环短片，
        // 点它一个参数都写不进去 —— 给一条点了没反应的胶囊比不给更糟。
        if (videoPictureOn && ui.stageInteractive && ui.quickActions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 18.dp)
                    .padding(bottom = CallConsoleHeight + 8.dp)
                    .graphicsLayer {
                        // 与对话台同步横移：纯净态里这块也该收走
                        translationX = spread.value * screenWidthPx + dragPx
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ui.quickActions.forEach { action ->
                    CallQuickChip(name = action.name) { vm.playQuickAction(action) }
                }
            }
        }

        // ④ 对话台：字幕 + 操作钮。右滑时整块横移出场。
        //
        //    为什么会遮不住角色：角色已经被 ③/① 的 bottomInset 收进上方那块了，
        //    这块面板覆盖的**本来就是画面里没有角色的地方**（类注释第 1 条）。
        //    所以这里不需要蒙版、不需要半透明。
        //
        //    ⚠️ v0.65.0（用户点名"下面 UI 的背景白膜换成透明的"）：**有画面时这块不再铺底色**，
        //    伪视频那一路尤其明显 —— 铺了底色就等于在视频上盖了一条白带。
        //    没有画面（语音通话 / 形象坏了回落头像）时才铺 [NeuBackground]：
        //    那一支底下本来就是页面底色，铺与不铺是同一个颜色，但大头像那套要靠它托住字幕。
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer {
                    // 延迟读：滑动期间只刷渲染层，不重组这张面板（一重组就要重排字幕列表）
                    translationX = spread.value * screenWidthPx + dragPx
                    alpha = 1f - spreadTotal() * 0.35f
                }
                .then(if (videoPictureOn) Modifier else Modifier.background(NeuBackground))
                .staggerEnter(1, stepMs = 60, slideDp = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CallSubtitlePanel(
                lines = subtitleLines,
                name = name,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = CallSubtitleViewportHeight)
                    .padding(horizontal = 26.dp)
                    .padding(top = 10.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 30.dp)
                    .staggerEnter(2, stepMs = 60, slideDp = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallActionButton(
                    size = 58.dp,
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
                    size = 66.dp,
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
 * 对话台的总高度（v0.64.0）。**它必须是一个常数**：
 *
 * 同一个数要用在两个地方 —— 面板自己的排版高度，以及"角色区占多少"的那个比例
 * （`consoleFraction = 这个数 / 屏高`）。两处各自算一遍的话，
 * 一旦有一边改了字号或内边距，角色就会被对话台压住一条边（真机上表现为"下巴被切掉"）。
 *
 * 构成：字幕可视区 96 + 间距 8 + 按钮 66 + 底边距 30 ≈ 200，留 8dp 余量。
 */
private val CallConsoleHeight = 208.dp

/**
 * 字幕可视高度 = **最多 3 行**（v0.64.0 用户拍板）。
 *
 * 上一版是"一屏 6 行 / 150dp"，那是在字幕**压在角色身上**的前提下做的（越少越不遮）。
 * 现在字幕有自己的台面，3 行（≈ 66dp）配上下内边距正好是 96dp ——
 * 再多会把角色区压小，而底部这块同时还要放两颗钮。
 * 更早的历史仍然**翻得回去**：上滑就是完整记录，右下角有「回到底部」。
 */
private val CallSubtitleViewportHeight = 96.dp

/**
 * 通话页头像**画布**与头像本体的边长比（v0.56.5 钉死的是 196 : 124，同一个比值）。
 *
 * 画布必须比头像大一圈，脉冲环才有地方画；而**画布尺寸必须恒定**（不跟着环的有无涨缩），
 * 否则"角色思考"与"角色听我说话"两态下头像位置会不一样（见头像块处的注释）。
 * v0.60.1 起头像边长改成屏宽比例，画布就按这个比值跟着等比放大。
 */
private const val CallAvatarCanvasRatio = 196f / 124f

/**
 * 字幕重铺的节拍（v0.66.x）。
 *
 * 不是为了"看起来连续"——80ms 完全够；是为了把 `buildLines` 从"每一个流式增量跑一次"
 * 降到每秒 12.5 次。整表重铺跑在主线程，而通话主线程还要接手势与滑动。
 */
private const val SUBTITLE_TICK_MS = 80L

/**
 * 字幕面板（v0.54.0）：歌词式排版 + 可滚动完整记录。
 *
 * 交互规则（与 ChatScreen 的贴底跟随同一套做法，用户在那儿已经习惯）：
 * ① 贴底时新句自动跟随到底；
 * ② **只要用户自己向上翻过，就停止自动滚动**，流式增量不再打断翻阅；
 * ③ 离开底部时右下角浮出「↓ 回到底部」，点一下立刻回到最新并恢复跟随。
 *
 * 渐隐是"歌词式"的：**最新一句最亮、最大**，越往上越**淡且越小**，
 * 都设了可读下限（alpha≥0.6、scale≥0.82），翻到通话开头时不会整屏都是"看不清的灰字"。
 *
 * ⚠️ v0.64.0：这里**不再有任何蒙版或渐变托底**。字幕现在压在页面底色上（见对话台的说明），
 * 正常灰阶就够清晰 —— 把蒙版加回来只会把 v0.61.1 删掉的那三层重新请回来。
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
            contentPadding = PaddingValues(top = 6.dp, bottom = 6.dp),
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
                // 线性递减 + 可读下限，翻出去的历史不会淡到看不清。
                val fade = (1f - fromEnd * 0.13f).coerceAtLeast(0.6f)
                // 越旧的字还要**越小**（"远离了"的观感），同样有下限
                val recede = (1f - fromEnd * 0.045f).coerceAtLeast(0.82f)
                Text(
                    subtitleText(line, name, MutedText),
                    fontSize = if (isNewest) 15.sp else 14.sp,
                    lineHeight = if (isNewest) 21.sp else 20.sp,
                    fontWeight = if (isNewest) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        isNewest -> TextPrimary
                        fromEnd < 5 -> TextSecondary
                        else -> MutedText
                    },
                    // 每段最多 3 行（超了截断省略号）—— 配合可视高度封顶，一屏最多只露 3 行
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
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
                size = 34.dp,
            ) {
                Icon(
                    MIcons.ChevronDown,
                    contentDescription = "回到底部",
                    tint = AccentInk,
                    modifier = Modifier.size(18.dp),
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
 * 通话里"当场演一个"的胶囊（v0.67.0）。
 *
 * 三件事决定了它长这样：
 * · **白面细线、不加底色** —— 它浮在画面上，任何实底色块都会变成压在角色身上的一条白条
 *   （v0.65.0 用户点名把对话台的白膜换成透明，同一个理由）；
 * · **无涟漪、按压缩放** —— 全 App 的输入语言（§四"输入区按钮一律走缩放反馈"）；
 * · **只有名字，没有图标** —— 这一排要读的是"演哪个"，图标只会把六个字挤成三个。
 */
@Composable
private fun CallQuickChip(name: String, onClick: () -> Unit) {
    val press = rememberNeuPressState()
    Box(
        Modifier
            .scale(press.scale)
            // 名字是用户自己起的（最长 12 字）。封顶 80dp 让一排四颗在 360dp 宽的机器上也放得下，
            // 超了就省略号 —— 宁可少看两个字，也不要这一排把角色区挤没。
            .widthIn(max = 80.dp)
            .neuSurface(999.dp, color = NeuSurface)
            .clickable(
                interactionSource = press.interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 15.dp, vertical = 9.dp),
    ) {
        Text(
            name,
            fontSize = 13.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
 * 计时胶囊（v0.64.0）。与状态胶囊同一套几何、同一套色，只是右边对齐 ——
 * 顶栏上两颗并列的胶囊读起来才像一组信息，而不是"一个控件 + 一行散字"。
 */
@Composable
private fun CallTimeChip(text: String) {
    Box(
        Modifier
            .neuSurface(999.dp, color = NeuTrack, inset = true)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = 12.5.sp, color = TextSecondary, letterSpacing = 0.6.sp)
    }
}

/**
 * 大号圆形操作钮（静音/挂断）：无涟漪缩放反馈，容器色调用方指定。
 *
 * v0.55.0：补两处以前没有的反馈 ——
 * - **禁用态淡出**（原来 enable=false 时按钮还是全亮，看不出"现在点不动"）；
 * - **生效态内圈**（静音开着时加一圈发丝环，表示"这个状态正生效"）。
 *
 * v0.64.0：v0.61.1 删掉的"外圈描边"在 [ringed] 上**保持删除状态**——
 * 现在它画在按钮**内部**（往外画会被父节点裁掉，也和"发丝线是界面唯一分隔手段"的语言不一致）。
 */
@Composable
private fun CallActionButton(
    size: Dp,
    container: Color,
    tint: Color,
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    /** 是否画一圈内环（表示"这个开关正生效"，如静音中） */
    ringed: Boolean = false,
    onClick: () -> Unit,
) {
    val press = rememberNeuPressState()
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.35f,
        animationSpec = tween(200),
        label = "callBtnAlpha",
    )
    val hairline = NeuHairline
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
        if (ringed) {
            // 生效环画在按钮**内部**（往外画会被父节点裁掉，也和"发丝线是界面唯一分隔手段"
            // 的语言一致 —— 不用第二套装饰）。就是一个 Canvas 圆，不加第二层描边。
            Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                drawCircle(color = hairline, style = Stroke(width = 1.4.dp.toPx()))
            }
        }
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.44f))
    }
}
