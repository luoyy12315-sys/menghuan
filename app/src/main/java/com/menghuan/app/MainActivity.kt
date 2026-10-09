package com.menghuan.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.menghuan.app.ui.chat.ChatHistoryScreen
import com.menghuan.app.ui.chat.ChatListScreen
import com.menghuan.app.ui.chat.ChatScreen
import com.menghuan.app.ui.chat.ChatSettingsScreen
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.AdaptiveSplitScreen
import com.menghuan.app.ui.common.ContentMaxWidth
import com.menghuan.app.ui.common.EaseOutExpo
import com.menghuan.app.ui.common.EaseOutQuart
import com.menghuan.app.ui.common.LocalWindowWidthClass
import com.menghuan.app.ui.common.WideMaxContentWidth
import com.menghuan.app.ui.common.isExpandedWindow
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.data.DataScreen
import com.menghuan.app.ui.persona.PersonaEditorScreen
import com.menghuan.app.ui.moment.MomentThreadScreen
import com.menghuan.app.ui.moment.MomentsScreen
import com.menghuan.app.ui.persona.PersonaManageScreen
import com.menghuan.app.ui.profile.GalleryScreen
import com.menghuan.app.ui.profile.MeProfileScreen
import com.menghuan.app.ui.profile.MeScreen
import com.menghuan.app.ui.settings.AboutScreen
import com.menghuan.app.ui.settings.AppearanceScreen
import com.menghuan.app.ui.settings.AsrModelScreen
import com.menghuan.app.ui.settings.CallAnswerDelayScreen
import com.menghuan.app.ui.settings.CallBehaviorScreen
import com.menghuan.app.ui.settings.CallRingtoneScreen
import com.menghuan.app.ui.settings.ChatModelScreen
import com.menghuan.app.ui.settings.CrashLogScreen
import com.menghuan.app.ui.settings.DataPrivacyScreen
import com.menghuan.app.ui.settings.ImageModelScreen
import com.menghuan.app.ui.settings.ModelConfigScreen
import com.menghuan.app.ui.settings.PermissionScreen
import com.menghuan.app.ui.settings.PromptSettingsScreen
import com.menghuan.app.ui.settings.RemindersScreen
import com.menghuan.app.ui.settings.ScheduledMessageScreen
import com.menghuan.app.ui.settings.SettingsRoutes
import com.menghuan.app.ui.settings.SettingsScreen
import com.menghuan.app.ui.settings.TtsConfigScreen
import android.content.Intent
import com.menghuan.app.ui.settings.VoiceLibraryScreen
import com.menghuan.app.ui.settings.WebSearchConfigScreen
import com.menghuan.app.ui.live2d.CallActionScreen
import com.menghuan.app.ui.live2d.Live2dSpaceScreen
import com.menghuan.app.ui.realtime.RealtimeCallScreen
import com.menghuan.app.ui.tasks.TasksScreen
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.BrandSalmon
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MentorTheme
import com.menghuan.app.ui.memory.MemoryScreen
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.ThemeMode
import com.menghuan.app.ui.theme.resolveDarkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /**
     * v0.53.5：来电页按「接听」后带回的目标人设 id（0 = 无）。
     * 来电是一个独立 Activity，接听后把本页 CLEAR_TOP 起来并带上这个 extra，
     * 由 Compose 侧一次性消费后清零。
     */
    private val pendingCallPersona = mutableStateOf(0L)

    /** v0.53.7：接听 AI 来电时一并带过来的"是它打的"与开场白（用户主动打给 AI 时会被清掉） */
    private val pendingCallGreeting = mutableStateOf("")
    private val pendingCallByAi = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readCallExtras(intent)
    }

    /** 来电页接听后带回来的三个 extra 一起读（缺一不可，否则通话页不知道谁先开口） */
    private fun readCallExtras(intent: Intent?) {
        val id = intent?.getLongExtra(com.menghuan.app.data.AiCall.EXTRA_OPEN_CALL_PERSONA, 0L) ?: 0L
        if (id <= 0L) return
        pendingCallPersona.value = id
        pendingCallGreeting.value = intent?.getStringExtra(com.menghuan.app.data.AiCall.EXTRA_OPEN_CALL_GREETING).orEmpty()
        pendingCallByAi.value = intent?.getBooleanExtra(com.menghuan.app.data.AiCall.EXTRA_OPEN_CALL_BY_AI, false) == true
    }

    /** 用户自己点电话图标打给 AI：清掉"AI 打来的"标记，避免上一次来电的痕迹串进来 */
    private fun clearCallExtrasForUserCall() {
        pendingCallGreeting.value = ""
        pendingCallByAi.value = false
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 冷启动也读一次：来电页可能把整个 App 冷启起来
        readCallExtras(intent)
        setContent {
            // 主题层：跟随系统 / 强制浅色 / 强制深色（设置 · 外观）。
            // 首帧同步取值（见 settingsRepository.themeModeBlocking），避免启动时先亮后暗闪一下。
            MenghuanThemedRoot {
                // 窗口宽度等级：>=600dp（Medium/Expanded）切平板宽版式（双栏消息 / 两列网格）
                val windowSizeClass = calculateWindowSizeClass(this)
                CompositionLocalProvider(LocalWindowWidthClass provides windowSizeClass.widthSizeClass) {
                    MenghuanAppRoot(
                        pendingCallPersona = pendingCallPersona.value,
                        callByAi = pendingCallByAi.value,
                        callGreeting = pendingCallGreeting.value,
                        onCallOpened = { pendingCallPersona.value = 0L },
                        onCallConsumed = { clearCallExtrasForUserCall() },
                    )
                }
            }
        }
        // AI 周报补生成：App 打开时检查上周是否缺报（如周末没开机则静默补生成；无 API 自动跳过）
        val app = application as MenghuanApp
        app.applicationScope.launch {
            runCatching {
                val zone = java.time.ZoneId.systemDefault()
                val lastMonday = java.time.LocalDate.now(zone).with(java.time.DayOfWeek.MONDAY).minusDays(7)
                app.weeklyReportGenerator.ensureReport(lastMonday, zone)
            }
        }
    }
}

/**
 * 主题根：解析「模式 + 系统深色」→ 深/浅两套色板，并同步系统栏图标颜色。
 * 系统栏必须显式指定（不用 auto）：auto 会跟随系统深色把图标翻白，
 * 与 App 自己的深浅选择脱钩（浅色界面上直接看不见图标）。
 */
@Composable
private fun MenghuanThemedRoot(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings = remember(context) { (context.applicationContext as MenghuanApp).settingsRepository }
    var mode by remember { mutableStateOf(ThemeMode.fromName(settings.themeModeBlocking())) }
    LaunchedEffect(settings) {
        settings.themeMode.collect { mode = ThemeMode.fromName(it) }
    }
    val dark = resolveDarkTheme(mode, isSystemInDarkTheme())

    val view = LocalView.current
    SideEffect {
        val activity = view.context.findActivity() ?: return@SideEffect
        val style = if (dark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    MentorTheme(darkTheme = dark) { content() }
}

/** Context 可能是 ContextWrapper 套娃（弹层/预览），向上找真正的 Activity */
private tailrec fun android.content.Context.findActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

private data class TopLevelRoute(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

/**
 * push 层级页（单聊/人设管理/记账本/洞察详情）的进出场时长（ms）。
 *
 * v0.43.1 起**所有 push 路由 + 底栏显隐共用同一个值**，这是修"返回时底栏先冒出来"
 * 的前提：只要有一处时长不一致，底栏和页面就会各走各的，用户看到的就是"错位"。
 */
private const val PUSH_TRANSITION_MS = 420

/** 顶级 tab 顺序 = Pager 页序 = 底栏序 */
private val topLevelRoutes = listOf(
    TopLevelRoute("chatList", R.string.tab_chat, MIcons.ChatBubble),
    TopLevelRoute("data", R.string.tab_data, MIcons.BarChart),
    TopLevelRoute("moments", R.string.tab_moments, MIcons.Globe),
    TopLevelRoute("me", R.string.tab_me, MIcons.Person),
)

@Composable
private fun MenghuanAppRoot(
    /** v0.53.5：来电页按接听带回来的目标 id（0 = 无） */
    pendingCallPersona: Long = 0L,
    /** v0.53.7：这通电话是 AI 打来的吗 + 它准备的开场白 */
    callByAi: Boolean = false,
    callGreeting: String = "",
    onCallOpened: () -> Unit = {},
    /** v0.53.7：通话页已经把"谁打的/开场白"接过去了 → Activity 清掉这两个标记（一次性） */
    onCallConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { topLevelRoutes.size })
    // home 的 deep link（menghuan://open/{page}）只在该 back stack entry 上生效一次：
    // entry.arguments 里的 page 会永远留着，从单聊等 push 层返回重新组合时
    // LaunchedEffect 会再跑一遍，把用户拽回深链 tab（「退出对话落到任务页」的根因）。
    // 记下已消费的 entry id，返回时不再滚动，让 Pager 自然恢复离开时的页面。
    var appliedDeepLinkEntry by rememberSaveable { mutableStateOf<String?>(null) }
    // 双栏右栏当前会话（0 = 未选中显示占位；rememberSaveable 保旋转/进程重建）
    var twoPaneChatId by rememberSaveable { mutableStateOf(0L) }

    // v0.53.5：来电接听后直达通话页 —— 一次性消费，导航完立刻清零（防止返回时又跳一次）
    LaunchedEffect(pendingCallPersona) {
        if (pendingCallPersona > 0L) {
            navController.navigate("call/$pendingCallPersona")
            onCallOpened()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 系统栏 insets 全交给 AdaptiveScreen 处理，避免与各页面重复叠加
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // 底栏只在 home（四 tab Pager）显示；单聊/人设管理是 push 层级页，
            // 隐藏底栏让输入栏直达屏幕底部（否则输入栏与底栏叠在一起）
            //
            // ⚠️⚠️ **返回时底栏不能"先冒出来"**（v0.43.1 修用户反馈的第 4 条）：
            //   `currentBackStackEntryAsState()` 在 `popBackStack()` 的**那一刻**就切回
            //   "home"，而单聊页的退场动画要跑 420ms。于是这 420ms 里底栏已经渲染出来、
            //   Scaffold 还把 `innerPadding` 加大 → 正在滑出的聊天页被往上挤了一下
            //   （用户看到"输入框往上跳"），最后才和顶栏一起退干净。
            //
            // 正解 = **底栏的显隐也走动画，且退场要盖住整段退场动画**：
            //   进场：等 420ms 后再出现（此时聊天页已经滑走，不会互相挤）
            //   退场：立刻消失（同 420ms 的 shrink 参与，视觉上是"跟着页面一起收走"）
            // 用 `AnimatedVisibility` 而不是直接 `if`，底栏的高度变化也是渐进的，
            // Scaffold 的 innerPadding 随之平滑，聊天页不会再被顶。
            val backStackEntry by navController.currentBackStackEntryAsState()
            val atHome = backStackEntry?.destination?.route == "home"
            androidx.compose.animation.AnimatedVisibility(
                visible = atHome,
                enter = androidx.compose.animation.expandVertically(
                    androidx.compose.animation.core.tween(PUSH_TRANSITION_MS, easing = EaseOutQuart),
                ) + fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 200)),
                exit = androidx.compose.animation.shrinkVertically(
                    androidx.compose.animation.core.tween(180, easing = EaseOutQuart),
                ) + fadeOut(androidx.compose.animation.core.tween(140)),
            ) {
                val app = LocalContext.current.applicationContext as MenghuanApp
                val unread by app.unreadCenter.unreadCount.collectAsState()
                HomeBottomBar(
                    pagerState = pagerState,
                    chatUnread = unread > 0,
                    onSelectTab = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            // consumeWindowInsets：让页面内 imePadding 扣除底栏已占的高度
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            // 默认过渡 = 轻淡入淡出（tab 切换在 Pager 内，不走导航动画）；push 页各自覆盖
            enterTransition = { fadeIn(tween(240)) },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { fadeIn(tween(240)) },
            popExitTransition = { fadeOut(tween(200)) },
        ) {
            // home = 四 tab 的 HorizontalPager（消息 / 数据 / 任务 / 我的），可横滑切页
            composable(
                route = "home",
                deepLinks = listOf(
                    navDeepLink { uriPattern = "menghuan://open" },
                    navDeepLink { uriPattern = "menghuan://open/{page}" },
                ),
            ) { entry ->
                // 仅 deep link（通知/小组件 menghuan://open/{page}）才强制对齐 tab，且只生效一次；
                // 普通返回（从单聊/人设管理 pop 回 home）不滚动，Pager 自己恢复离开时的页面
                val deepLinkPage = entry.arguments?.getString("page")
                // v0.73.0：底栏第 3 格由「任务」换成「动态」。
                // `tasks` 从这张表里**删掉了** —— 小组件与任务提醒通知发的那四个
                // `menghuan://open/tasks` 现在由全屏任务页自己的 deep link 接住
                // （0 个参数的模式比这里的 1 个参数更具体，Navigation 会选它），
                // 点提醒通知落到任务页、而不是动态页。
                val initialTab = when (deepLinkPage) {
                    "chatList" -> 0
                    "data" -> 1
                    "moments" -> 2
                    "me" -> 3
                    else -> -1
                }
                val linkKey = entry.id
                LaunchedEffect(linkKey) {
                    if (initialTab >= 0 && appliedDeepLinkEntry != linkKey) {
                        appliedDeepLinkEntry = linkKey
                        if (pagerState.currentPage != initialTab) {
                            pagerState.scrollToPage(initialTab)
                        }
                    }
                }
                HomePages(
                    navController = navController,
                    pagerState = pagerState,
                    twoPaneChatId = twoPaneChatId,
                    onTwoPaneChatChange = { twoPaneChatId = it },
                    onOpenLedger = { navController.navigate("ledger") },
                    onOpenAiDetail = { navController.navigate("ai_detail") },
                    onOpenTasks = { navController.navigate("tasks") },
                )
            }
            // 单聊页：标准 push 进出——从右侧滑入，返回滑出（平板双栏模式下不走此路由）。
            // v0.41.4 退场手感：滑出从 280ms 拉长到 420ms，并让页面同时轻微缩小
            // （1→0.96）——纯平移的"唰一下没了"读起来太仓促；缩放收束给出"被收走"的
            // 空间感，与底栏胶囊指示器的弹簧语言一致。同时去掉退出时的 fadeOut：
            // 边缩边淡会让输入栏在最后几帧透明化，正是用户看到的"闪一下"。
            composable(
                route = "chat/{personaId}",
                arguments = listOf(navArgument("personaId") { type = NavType.LongType }),
                // v0.38.0：Agent 进度通知 / 主动关心通知点击直达对应会话
                deepLinks = listOf(navDeepLink { uriPattern = "menghuan://chat/{personaId}" }),
                // v0.53.7：**微信式转场** —— 整页统一"从右往左平移"，不做淡入淡出/缩放。
                // 以前 enter 挂 fadeIn、exit 只 fadeOut、popExit 还叠 scaleOut，
                // 于是进对话页时"消息垂直弹一遍 + 整页横向滑入"两套动作叠在一起，
                // 观感与微信完全不符（用户反馈"聊天记录从上往下弹出、输入框从右上斜滑下来"）。
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it }
                },
                // 旧页（会话列表）向左移出 1/3 —— 微信就是这个位移量，不淡出
                exitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { -it / 3 }
                },
                // 返回：上一层从左侧 1/3 处滑回原位
                popEnterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { -it / 3 }
                },
                // 返回：当前页整块向右滑出
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it }
                },
            ) { entry ->
                // 单聊页底部避让交给输入栏自己做（max(ime, navBars) 合并动画），
                // 这里不能再叠一层 navigationBarsPadding，否则退场时输入栏会往上弹（v0.42.4）
                val pId = entry.arguments?.getLong("personaId") ?: 1L
                AdaptiveScreen(applyNavigationBarsPadding = false) {
                    ChatScreen(
                        personaId = pId,
                        onBack = { navController.popBackStack() },
                        onOpenCall = { navController.navigate("call/$pId") },
                        onOpenVideoCall = { navController.navigate("video_call/$pId") },
                        onOpenSettings = { route -> navController.navigate(route) },
                    )
                }
            }
            // 实时语音通话页（v0.46.0）：从单聊页顶栏电话图标进入，push 转场与单聊一致
            composable(
                route = "call/{personaId}",
                arguments = listOf(navArgument("personaId") { type = NavType.LongType }),
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it } + fadeIn(tween(260))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(260)) },
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it } +
                        scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
                },
            ) { entry ->
                val callId = entry.arguments?.getLong("personaId") ?: 1L
                // v0.53.7：这两个值只在"这一次进入"有效 —— 先冻结一份给本页用，
                // 随即让 Activity 清掉；否则下次用户自己点电话图标打给 AI 时，
                // 还会带着上一次来电的"是 AI 打的"痕迹（开场白和记录卡方向都会错）。
                val byAiThisCall = remember { callByAi }
                val greetingThisCall = remember { callGreeting }
                LaunchedEffect(Unit) { onCallConsumed() }
                AdaptiveScreen {
                    RealtimeCallScreen(
                        personaId = callId,
                        onBack = { navController.popBackStack() },
                        onOpenSettings = { route -> navController.navigate(route) },
                        initiatedByAi = byAiThisCall,
                        greeting = greetingThisCall,
                    )
                }
            }
            // 视频通话页（v0.58.0）：与语音通话**两个独立入口、两条路由**，
            // 底下跑的是同一套实时通话引擎，只是多挂一层视觉（方案文档 §一 / §五）。
            // 来电（AI 主动打）仍只走语音那条 —— 视觉资源没准备好时不该强行上画面。
            composable(
                route = "video_call/{personaId}",
                arguments = listOf(navArgument("personaId") { type = NavType.LongType }),
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it } + fadeIn(tween(260))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(260)) },
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it } +
                        scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
                },
            ) { entry ->
                val callId = entry.arguments?.getLong("personaId") ?: 1L
                AdaptiveScreen {
                    RealtimeCallScreen(
                        personaId = callId,
                        onBack = { navController.popBackStack() },
                        onOpenSettings = { route -> navController.navigate(route) },
                        videoMode = true,
                    )
                }
            }
            // 角色 Live2D 空间（v0.64.0）：可以换背景、拖动、点击互动、玩动作库的独立场景。
            // ⚠️ 它**不是**视频通话页的一部分（用户第 1 条要求两者解耦）：
            // 底下不跑通话引擎，也不联网，就是跟角色玩的场景。
            composable(
                route = "live2d_space/{personaId}",
                arguments = listOf(navArgument("personaId") { type = NavType.LongType }),
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it } + fadeIn(tween(260))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(260)) },
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it } +
                        scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
                },
            ) { entry ->
                val spaceId = entry.arguments?.getLong("personaId") ?: 1L
                // 这里刻意**不套 AdaptiveScreen**：空间是一块要铺满的舞台，
                // 上层那个容器会加版心宽度与导航条 padding，角色会被夹窄。
                // 顶部/底部 inset 由空间页自己处理（statusBarsPadding / navigationBarsPadding）。
                Live2dSpaceScreen(
                    personaId = spaceId,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { route -> navController.navigate(route) },
                )
            }
            // 通话动作（v0.64.0）：四个状态各绑哪条动作。只做绑定，不做编辑 ——
            // 想改动作去角色空间的动作库（用户第 2 条：通话只负责调用动作）。
            composable(
                route = "call_actions/{personaId}",
                arguments = listOf(navArgument("personaId") { type = NavType.LongType }),
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it } + fadeIn(tween(260))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(260)) },
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it } +
                        scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
                },
            ) { entry ->
                val actionPersonaId = entry.arguments?.getLong("personaId") ?: 1L
                AdaptiveScreen {
                    CallActionScreen(
                        personaId = actionPersonaId,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(
                route = "personas",
                deepLinks = listOf(navDeepLink { uriPattern = "menghuan://personas" }),
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it } + fadeIn(tween(260))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(260)) },
                // 与单聊页同一套退场手感（v0.41.4）：拉长 + 缩放收束，不叠 fadeOut
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it } +
                        scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
                },
            ) {
                AdaptiveScreen {
                    PersonaManageScreen(
                        onBack = { navController.popBackStack() },
                        onEdit = { id -> navController.navigate("persona_editor/$id") },
                        // v0.60.1：人设管理页直接进会话（被「删除会话」隐藏过的人设靠这条路找回来）
                        onOpenChat = { id -> navController.navigate("chat/$id") },
                    )
                }
            }
            // 编辑人设（v0.53.5）：**真正的整页**，新增（personaId=0）与编辑共用一条路由
            composable(
                route = "persona_editor/{personaId}",
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it } + fadeIn(tween(260))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(260)) },
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it } +
                        scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
                },
            ) { entry ->
                val pid = entry.arguments?.getString("personaId")?.toLongOrNull() ?: 0L
                AdaptiveScreen {
                    PersonaEditorScreen(personaId = pid, onBack = { navController.popBackStack() })
                }
            }
            // 聊天记录搜索页（v0.53.6）：对话页三点 →「查看聊天记录」
            composable(
                route = "chat_history/{personaId}",
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it } + fadeIn(tween(260))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(260)) },
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it } +
                        scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
                },
            ) { entry ->
                val pid = entry.arguments?.getString("personaId")?.toLongOrNull() ?: 0L
                AdaptiveScreen {
                    ChatHistoryScreen(personaId = pid, onBack = { navController.popBackStack() })
                }
            }
            // 会话设置（v0.54.4）：对话页右上角「⋮」→ **整页**（原来是弹小菜单）
            composable(
                route = "${SettingsRoutes.CHAT_SETTINGS}/{personaId}",
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it } + fadeIn(tween(260))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(260)) },
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it } +
                        scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
                },
            ) { entry ->
                val pid = entry.arguments?.getString("personaId")?.toLongOrNull() ?: 0L
                AdaptiveScreen {
                    ChatSettingsScreen(
                        personaId = pid,
                        onBack = { navController.popBackStack() },
                        onOpen = { route -> navController.navigate(route) },
                    )
                }
            }
            // 「TA 的记忆」全屏页（v0.71.0）：入口在会话设置。按 personaId 分 ——
            // 与"记忆按人设彻底分开"（v0.70.0）是同一条边界，这一页只看当前这个角色。
            composable(
                route = "memory/{personaId}",
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it } + fadeIn(tween(260))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(260)) },
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it } +
                        scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
                },
            ) { entry ->
                // 与上面 CHAT_SETTINGS 同一取法：全项目这些带参路都是 getString 再 toLong
                val pid = entry.arguments?.getString("personaId")?.toLongOrNull() ?: 0L
                AdaptiveScreen { MemoryScreen(personaId = pid, onBack = { navController.popBackStack() }) }
            }
            // 记账本（v0.42.0）：数据页底部入口进入的全屏页
            composable(
                route = "ledger",
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it } + fadeIn(tween(260))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(260)) },
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it } +
                        scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
                },
            ) {
                LedgerRoute(onBack = { navController.popBackStack() })
            }
            // 任务页（v0.73.0）：从「数据」页那张入口卡进来的全屏页。
            // 原来它是底栏第 3 个 tab，那一格让给了动态；小组件与任务提醒通知的
            // `menghuan://open/tasks` 由这里的 deep link 接住（模式里没有参数，比 home 的
            // `menghuan://open/{page}` 更具体，Navigation 选它 —— 点提醒落到任务页）。
            composable(
                route = "tasks",
                deepLinks = listOf(navDeepLink { uriPattern = "menghuan://open/tasks" }),
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it } + fadeIn(tween(260))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(260)) },
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it } +
                        scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
                },
            ) {
                AdaptiveScreen(contentMaxWidth = WideMaxContentWidth) {
                    TasksScreen(onBack = { navController.popBackStack() })
                }
            }
            // 动态的一条帖子详情（v0.73.0）：回复全部平铺在这一页，只有一层
            composable(
                route = "moment/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it } + fadeIn(tween(260))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(260)) },
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it } +
                        scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
                },
            ) { entry ->
                AdaptiveScreen(contentMaxWidth = WideMaxContentWidth) {
                    MomentThreadScreen(
                        momentId = entry.arguments?.getLong("id") ?: 0L,
                        onBack = { navController.popBackStack() },
                        onOpenChat = { id -> navController.navigate("chat/$id") },
                    )
                }
            }
            // AI 洞察 + 周报 详情（v0.42.0）：数据页合并入口进入的全屏页
            composable(
                route = "ai_detail",
                enterTransition = {
                    slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it } + fadeIn(tween(260))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(260)) },
                popExitTransition = {
                    slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it } +
                        scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
                },
            ) {
                AiDetailRoute(onBack = { navController.popBackStack() })
            }

            // ===== 设置链全屏页（v0.51.0）=====
            // 「我的」这条线原来是 13 个 ModalBottomSheet 弹层，最深要叠三层
            // （设置 → 语音 → 语音合成），弹层叠弹层既盖屏又说不清返回退到哪层；
            // 而识别模型下载要跑几分钟，用户需要能退出去干别的再回来——那只有整页做得到。
            // 转场与单聊/人设管理同一套手感。
            settingsComposables(navController)
        }
    }
}

/**
 * 设置链 12 个全屏页的路由注册（v0.51.0）。
 *
 * 单独抽出来是因为这批路由的转场参数完全一样，摊在 NavHost 里会重复 12 遍样板代码。
 */
private fun androidx.navigation.NavGraphBuilder.settingsComposables(navController: NavHostController) {
    val push = androidx.compose.animation.slideInHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutExpo)) { it }
    val pop = androidx.compose.animation.slideOutHorizontally(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart)) { it }
    val scalePop = androidx.compose.animation.scaleOut(tween(PUSH_TRANSITION_MS, easing = EaseOutQuart), targetScale = 0.96f)
    val open: (String) -> Unit = { route -> navController.navigate(route) }
    val back: () -> Unit = { navController.popBackStack() }

    composable(SettingsRoutes.SETTINGS, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { SettingsScreen(onBack = back, onOpen = open) }
    }
    // 二级页（v0.54.2 方案二）：主页那些多条目板块也是一张卡，点进来才是这几页
    composable(SettingsRoutes.MODEL_CONFIG, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { ModelConfigScreen(onBack = back, onOpen = open) }
    }
    composable(SettingsRoutes.CALL_BEHAVIOR, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { CallBehaviorScreen(onBack = back, onOpen = open) }
    }
    composable(SettingsRoutes.REMINDERS, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { RemindersScreen(onBack = back, onOpen = open) }
    }
    // 联网搜索（v0.54.2）：从「定时主动消息」里拎出的独立配置页，入口在「模型配置」下
    composable(SettingsRoutes.SEARCH_CONFIG, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { WebSearchConfigScreen(onBack = back) }
    }
    // 模型配置（v0.54.1）：对话模型与生图模型由同一个「API 配置」页拆成两页，各自只管一类
    composable(SettingsRoutes.CHAT_MODEL, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { ChatModelScreen(onBack = back) }
    }
    composable(SettingsRoutes.IMAGE_MODEL, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { ImageModelScreen(onBack = back) }
    }
    composable(SettingsRoutes.DATA_PRIVACY, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { DataPrivacyScreen(onBack = back) }
    }
    composable(SettingsRoutes.ABOUT, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { AboutScreen(onBack = back, onOpen = open) }
    }
    // 崩溃记录（v0.54.4）：从「关于」页右上角红字入口进入的整页
    composable(SettingsRoutes.CRASH_LOG, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { CrashLogScreen(onBack = back) }
    }
    // 外观（v0.54.1）：从设置主页的「外观」卡进入，三个主题胶囊
    composable(SettingsRoutes.APPEARANCE, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { AppearanceScreen(onBack = back) }
    }
    composable(SettingsRoutes.TTS_CONFIG, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { TtsConfigScreen(onBack = back) }
    }
    // 音色页（v0.53.5）：设置主页第 ③ 板块进入，顶栏右侧是克隆音色与 ID
    composable(SettingsRoutes.VOICE_LIBRARY, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        VoiceLibraryScreen(onBack = back)
    }
    // 定时主动消息（v0.53.5）：自己配「谁、几点、发什么」，可配多条、可联网搜最新
    composable(SettingsRoutes.SCHEDULED_MESSAGE, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { ScheduledMessageScreen(onBack = back) }
    }
    // 语音通话铃声（v0.53.7）：来电与去电共用，可传音频或视频
    composable(SettingsRoutes.CALL_RINGTONE, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { CallRingtoneScreen(onBack = back) }
    }
    // 接听延迟（v0.56.5）：从「语音通话铃声」页里拎出，与它同级挂在「通话行为」下
    composable(SettingsRoutes.CALL_ANSWER_DELAY, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { CallAnswerDelayScreen(onBack = back) }
    }
    // 权限（v0.53.8）：把应用会用到授权集中一处，逐项说明用途 + 直达系统设置
    composable(SettingsRoutes.PERMISSIONS, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { PermissionScreen(onBack = back) }
    }
    composable(SettingsRoutes.ASR_MODEL, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { AsrModelScreen(onBack = back) }
    }
    composable(SettingsRoutes.PROMPTS, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { PromptSettingsScreen(onBack = back) }
    }
    composable(SettingsRoutes.ME_PROFILE, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { MeProfileScreen(onBack = back) }
    }
    composable(SettingsRoutes.GALLERY, enterTransition = { push + fadeIn(tween(260)) },
        exitTransition = { fadeOut(tween(200)) }, popEnterTransition = { fadeIn(tween(260)) },
        popExitTransition = { pop + scalePop }) {
        AdaptiveScreen { GalleryScreen(onBack = back) }
    }
}

/**
 * Pager 页内容：HorizontalPager 承载四个顶级 tab（手指左右滑切页，越过阻尼走系统默认），
 * beyondBoundsPageCount 保全部 4 页存活（切页不丢列表位置）。
 * 平板（>=600dp）消息页为双栏：左会话列表 + 右单聊区，不再 push 单聊。
 */
@Composable
private fun HomePages(
    navController: NavHostController,
    pagerState: PagerState,
    twoPaneChatId: Long,
    onTwoPaneChatChange: (Long) -> Unit,
    onOpenLedger: () -> Unit,
    onOpenAiDetail: () -> Unit,
    onOpenTasks: () -> Unit,
) {
    val wide = isExpandedWindow()
    HorizontalPager(
        state = pagerState,
        // 4 页全部保留在组合内：每页滚动位置不丢；语义树含屏外页属已知取舍
        beyondViewportPageCount = topLevelRoutes.lastIndex,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        when (page) {
            0 ->
                if (wide) {
                    AdaptiveSplitScreen(
                        // 右栏是单聊：底部避让同样交给它的输入栏（见上方 chat 路由注释）
                        applyNavigationBarsPadding = false,
                        left = {
                            ChatListScreen(
                                onOpen = onTwoPaneChatChange,
                                onOpenPersonaEditor = { id -> navController.navigate("persona_editor/$id") },
                                onSelect = onTwoPaneChatChange,
                                selectedPersonaId = twoPaneChatId,
                            )
                        },
                        right = {
                            if (twoPaneChatId <= 0L) {
                                TwoPanePlaceholder()
                            } else {
                                ChatScreen(
                                    personaId = twoPaneChatId,
                                    onBack = { onTwoPaneChatChange(0L) },
                                    showBack = false,
                                    onOpenCall = { navController.navigate("call/$twoPaneChatId") },
                                    onOpenVideoCall = { navController.navigate("video_call/$twoPaneChatId") },
                                    onOpenSettings = { route -> navController.navigate(route) },
                                )
                            }
                        },
                    )
                } else {
                    AdaptiveScreen {
                        ChatListScreen(
                            onOpen = { id -> navController.navigate("chat/$id") },
                            onOpenPersonaEditor = { id -> navController.navigate("persona_editor/$id") },
                        )
                    }
                }
            1 -> AdaptiveScreen(contentMaxWidth = WideMaxContentWidth) {
                // settledPage：翻页动画**停下来**之后才变，用它驱动"数据页真正可见"的入场动画重播
                DataScreen(
                    onOpenLedger = onOpenLedger,
                    onOpenAiDetail = onOpenAiDetail,
                    onOpenTasks = onOpenTasks,
                    visible = pagerState.settledPage == 1,
                )
            }
            2 -> AdaptiveScreen(contentMaxWidth = WideMaxContentWidth) {
                MomentsScreen(
                    // settledPage：翻页**停下来**之后才变，用它驱动"这一页真的可见"的入场动画
                    visible = pagerState.settledPage == 2,
                    onOpenThread = { id -> navController.navigate("moment/$id") },
                    onOpenChat = { id -> navController.navigate("chat/$id") },
                )
            }
            else -> AdaptiveScreen(contentMaxWidth = WideMaxContentWidth) {
                MeScreen(
                    onManagePersonas = { navController.navigate("personas") },
                    onOpen = { route -> navController.navigate(route) },
                )
            }
        }
    }
}

/** 双栏右栏无选中会话时的占位（事实短语，无引导文字） */
@Composable
private fun TwoPanePlaceholder() {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Text("未选择会话", fontSize = 13.sp, color = MutedText)
    }
}

/**
 * 浮起胶囊底栏：白纸 + 发丝线 + 纸叠影；选中项 = 黑色胶囊指示器滑入（弹簧过冲）+
 * 图标/文字反白。刻意不用 Material NavigationBar（默认 80dp 高 + 胶囊指示器，与设计语言不符）。
 * 选中态跟随 Pager（拖动跨半页即滑移），点击 = animateScrollToPage 弹簧切页。
 *
 * **v0.56.9（用户点名"底栏改成可滑动的，那个黑色块可以滑动切换屏幕"）**：
 * - 手指在底栏上**横向拖动**：黑块贴着手指走，**每跨过一格就翻一页**；
 * - v0.56.11 修"黑块跟手指反方向"：位移不再取负——右拖 = 黑块右移 = 滑向右边的 tab；
 * - **v0.56.14 修"拖动一弹一弹"**：旧实现弹簧位置 + 弹簧化的跟手残余两层叠加；
 *   现在拖动中黑块 **snapTo 直接跟手（零动画）**，松手 / 点选才弹簧滑到目标格——
 *   点选的 Q 弹过冲原样保留（用户点名不许删）。
 * - 翻页仍然走 `animateScrollToPage`（弹簧过冲）—— **Q 弹感是这一版的硬要求**；
 * - 点击切页照旧，两条路径共用同一个 Pager。
 */
@Composable
private fun HomeBottomBar(
    pagerState: PagerState,
    chatUnread: Boolean = false,
    onSelectTab: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= ContentMaxWidth
        // 一格有多宽（px）：与下面指示器的 cell 同一套算法（底栏宽 - 左右 8dp，再除以 tab 数）
        val cellPx = with(LocalDensity.current) {
            ((if (wide) ContentMaxWidth else maxWidth) - 28.dp - 16.dp).toPx() / topLevelRoutes.size
        }
        // 黑块位置（px，相对轨道左缘）的**唯一来源**。
        //
        // v0.56.14 修"拖动一弹一弹"：旧实现是弹簧位置（animateDpAsState）+ 跟手残余
        // （外面再包一层 animateFloatAsState 弹簧）两层动画叠一个黑块——拖动中每跨一格
        // currentPage 一变弹簧就开始追，偏移又突变又带滞后，三股劲挤在一起 = 一弹一弹。
        // 现在单一来源：**拖动中 snapTo 直接跟手指（零动画，丝滑）**；松手 / 点选 →
        // 弹簧滑到目标格（MediumBouncy 过冲——点选的 Q 弹感保留，用户点名不许删）。
        val indicator = remember { Animatable(Float.NaN) }
        // 拖动进行中：所有弹簧协程让位，黑块只听手指的
        var dragging by remember { mutableStateOf(false) }
        LaunchedEffect(pagerState.targetPage, dragging, cellPx) {
            if (dragging) return@LaunchedEffect
            val target = cellPx * pagerState.targetPage
            if (indicator.value.isNaN()) indicator.snapTo(target)
            else indicator.animateTo(
                target,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (wide) Modifier.widthIn(max = ContentMaxWidth) else Modifier)
                .padding(horizontal = 14.dp)
                .padding(top = 4.dp, bottom = 8.dp)
                .navigationBarsPadding()
                // 底栏本身可横滑：黑块贴着手指走（snapTo，无弹簧），每跨过**一格**翻一页。
                // 不用 `dispatchRawDelta` 那一套（v0.56.8 试过，拖了没反应 ——
                // 它只在 Pager 自己的滚动流程里生效，这里拿不到）。
                // 手势放在 clickable 之外（更外层），拖动不会被点击吞掉。
                .pointerInput(topLevelRoutes.size) {
                    var startPage = 0
                    var dragBase = 0f
                    var crossed = 0
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            startPage = pagerState.currentPage
                            dragBase = indicator.value.takeUnless { it.isNaN() }
                                ?: cellPx * startPage
                            crossed = 0
                            total = 0f
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            total += dragAmount
                            // 手指往哪边拖黑块就往哪边走（右拖 = 滑向右边的 tab），完全跟手
                            scope.launch {
                                indicator.snapTo(
                                    (dragBase + total).coerceIn(0f, cellPx * topLevelRoutes.lastIndex),
                                )
                            }
                            // Float.toInt() 就是"向零取整"（= trunc），跨满一格才翻一页；
                            // 翻页走 Pager 自己的弹簧，黑块不停、继续跟手
                            val raw = total / cellPx
                            val nextCrossed = raw.toInt()
                            if (nextCrossed != crossed) {
                                crossed = nextCrossed
                                val target = (startPage + crossed).coerceIn(0, topLevelRoutes.lastIndex)
                                scope.launch { pagerState.animateScrollToPage(target) }
                            }
                        },
                    )
                },
        ) {
            val barInset = 8.dp
            val tabIndex = pagerState.currentPage.coerceIn(0, topLevelRoutes.lastIndex)
            Box(
                Modifier
                    .fillMaxWidth()
                    // 用户拍板：底栏不要阴影，靠细线描边与页面基底分区即可
                    .neuSurface(26.dp, color = NeuSurface, elevation = 0.dp),
            ) {
                BoxWithConstraints(Modifier.matchParentSize()) {
                    val track = maxWidth - barInset * 2
                    val cell = track / topLevelRoutes.size
                    val x = with(LocalDensity.current) {
                        (indicator.value.takeUnless { it.isNaN() } ?: cellPx * pagerState.targetPage)
                            .toDp() + barInset
                    }
                    Box(
                        Modifier
                            .offset(x = x)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .width(cell - 16.dp)
                            .height(40.dp)
                            .background(NeuAccent, RoundedCornerShape(999.dp)),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(horizontal = barInset),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    topLevelRoutes.forEachIndexed { index, route ->
                        val selected = index == tabIndex
                        Box(
                            Modifier
                                .weight(1f)
                                .testTag("bottom_tab_$index")
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { if (!selected) onSelectTab(index) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                val iconScale by animateFloatAsState(
                                    if (selected) 1.08f else 1f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                                Box {
                                    Icon(
                                        route.icon,
                                        contentDescription = null,
                                        tint = if (selected) AccentInk else MutedText,
                                        modifier = Modifier.size(20.dp).scale(iconScale),
                                    )
                                    // 消息 tab 未读点：非选中且有未读时叠在图标右上。
                                    // v0.62.0 与会话列表那颗一起换成品牌红（原来跟着强调色 = 浅色纯黑，
                                    // 用户点名"黑点不像未读，要红点"）
                                    if (index == 0 && chatUnread && !selected) {
                                        Box(
                                            Modifier
                                                .align(Alignment.TopEnd)
                                                .offset(x = 4.dp, y = (-1).dp)
                                                .size(7.dp)
                                                .background(BrandSalmon, RoundedCornerShape(999.dp)),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    stringResource(route.labelRes),
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                    color = if (selected) AccentInk else MutedText,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

