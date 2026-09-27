package com.daoshi.app

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.daoshi.app.ui.chat.ChatListScreen
import com.daoshi.app.ui.chat.ChatScreen
import com.daoshi.app.ui.common.AdaptiveScreen
import com.daoshi.app.ui.common.ContentMaxWidth
import com.daoshi.app.ui.common.EaseOutExpo
import com.daoshi.app.ui.common.neuSurface
import com.daoshi.app.ui.data.DataScreen
import com.daoshi.app.ui.persona.PersonaManageScreen
import com.daoshi.app.ui.tasks.TasksScreen
import com.daoshi.app.ui.theme.MIcons
import com.daoshi.app.ui.theme.MentorTheme
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.NeuSurface
import com.daoshi.app.ui.theme.TextPrimary

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边 + 系统栏图标锁定深色（浅色锁定 App：不能跟系统深色模式翻成白色图标）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        setContent {
            MentorTheme {
                MentorAppRoot()
            }
        }
    }
}

private data class TopLevelRoute(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val topLevelRoutes = listOf(
    TopLevelRoute("chatList", R.string.tab_chat, MIcons.ChatBubble),
    TopLevelRoute("data", R.string.tab_data, MIcons.BarChart),
    TopLevelRoute("tasks", R.string.tab_tasks, MIcons.Checklist),
)

/** tab 顺序：方向感知滑动动画用 */
private fun topLevelIndexOf(route: String?): Int =
    topLevelRoutes.indexOfFirst { it.route == route }.coerceAtLeast(0)

@Composable
private fun MentorAppRoot() {
    val navController = rememberNavController()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 系统栏 insets 全交给 AdaptiveScreen 处理，避免与各页面重复叠加
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            NeuBottomBar(
                currentRoute = backStackEntry?.destination?.route,
                onSelect = { route ->
                    navController.navigate(route) {
                        popUpTo("chatList") { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "chatList",
            // consumeWindowInsets：让页面内 imePadding 扣除底栏已占的高度
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
            // tab 间切换：方向感知的小幅滑入 + 淡入
            enterTransition = {
                val forward = topLevelIndexOf(targetState.destination.route) >= topLevelIndexOf(initialState.destination.route)
                slideInHorizontally(tween(340, easing = EaseOutExpo)) { if (forward) it / 4 else -it / 4 } +
                    fadeIn(tween(280))
            },
            exitTransition = {
                val forward = topLevelIndexOf(targetState.destination.route) >= topLevelIndexOf(initialState.destination.route)
                slideOutHorizontally(tween(220, easing = EaseOutExpo)) { if (forward) -it / 5 else it / 5 } +
                    fadeOut(tween(180))
            },
        ) {
            // 会话列表：聊天 tab 主入口，设置入口在此页
            composable("chatList") {
                AdaptiveScreen {
                    ChatListScreen(
                        onOpen = { personaId -> navController.navigate("chat/$personaId") },
                        onManagePersonas = { navController.navigate("personas") },
                    )
                }
            }
            // 单聊页：标准 push 进出——从右侧滑入，返回滑出
            composable(
                route = "chat/{personaId}",
                arguments = listOf(navArgument("personaId") { type = NavType.LongType }),
                enterTransition = {
                    slideInHorizontally(tween(360, easing = EaseOutExpo)) { it } + fadeIn(tween(280))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(240)) },
                popExitTransition = {
                    slideOutHorizontally(tween(280, easing = EaseOutExpo)) { it } + fadeOut(tween(200))
                },
            ) { entry ->
                AdaptiveScreen {
                    ChatScreen(
                        personaId = entry.arguments?.getLong("personaId") ?: 1L,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(
                route = "personas",
                enterTransition = {
                    slideInHorizontally(tween(360, easing = EaseOutExpo)) { it } + fadeIn(tween(280))
                },
                exitTransition = { fadeOut(tween(200)) },
                popEnterTransition = { fadeIn(tween(240)) },
                popExitTransition = {
                    slideOutHorizontally(tween(280, easing = EaseOutExpo)) { it } + fadeOut(tween(200))
                },
            ) {
                AdaptiveScreen { PersonaManageScreen(onBack = { navController.popBackStack() }) }
            }
            composable("data") { AdaptiveScreen { DataScreen() } }
            composable("tasks") { AdaptiveScreen { TasksScreen() } }
        }
    }
}

/**
 * 浮起胶囊底栏：整块从纸面凸起（圆角 26dp + 双向软阴影），
 * 选中项 = 图标沉进凹陷圆井并染强调色，井的弹出用弹簧缩放。
 * 刻意不用 Material NavigationBar（默认 80dp 高 + 胶囊指示器，与新拟态语言不符）。
 */
@Composable
private fun NeuBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= ContentMaxWidth
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (wide) Modifier.widthIn(max = ContentMaxWidth) else Modifier)
                .padding(horizontal = 14.dp)
                .padding(top = 6.dp, bottom = 10.dp)
                .navigationBarsPadding(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .neuSurface(26.dp, color = NeuSurface, elevation = 8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    topLevelRoutes.forEach { route ->
                        val selected = currentRoute == route.route
                        val iconScale by animateFloatAsState(
                            if (selected) 1f else 0f,
                            tween(240, easing = EaseOutExpo),
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .clickable { if (!selected) onSelect(route.route) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Box(
                                    Modifier.size(38.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // 选中井：凹陷圆，弹簧感弹出
                                    if (iconScale > 0.01f) {
                                        Box(
                                            Modifier
                                                .scale(iconScale)
                                                .size(38.dp)
                                                .neuSurface(
                                                    999.dp,
                                                    color = MaterialTheme.colorScheme.background,
                                                    elevation = 4.dp,
                                                    inset = true,
                                                ),
                                        )
                                    }
                                    Icon(
                                        route.icon,
                                        contentDescription = null,
                                        tint = if (selected) NeuAccent else MutedText,
                                        modifier = Modifier.size(21.dp),
                                    )
                                }
                                Spacer(Modifier.height(1.dp))
                                Text(
                                    stringResource(route.labelRes),
                                    fontSize = 9.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) NeuAccent else MutedText,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
