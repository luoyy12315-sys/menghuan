package com.menghuan.app.ui.realtime

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.realtime.CallTurnData
import com.menghuan.app.realtime.RealtimeCallState
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.rememberNeuPressState
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuBackground
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.personaBrush
import kotlinx.coroutines.delay

/**
 * 实时语音通话页（v0.47.0 重构）：无顶栏（用户反馈与中部头像重复），主视觉 =
* 中部大头像（说话时呼吸脉动）+ 名字 + 状态；字幕做成歌词式——贴底、最新一两句
* 清晰突出、越旧的越淡，顶部用背景色渐变收束；底部静音/挂断大圆钮。
 */
@Composable
fun RealtimeCallScreen(
    personaId: Long,
    onBack: () -> Unit,
    vm: RealtimeCallViewModel = viewModel(key = "call_$personaId", factory = RealtimeCallViewModel.Factory(personaId)),
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
        if (granted) vm.start()
    }
    LaunchedEffect(Unit) {
        if (hasPermission) vm.start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    // 返回键 = 挂断；ENDED 后延迟退出，给挂断提示音留出时间
    BackHandler { vm.hangUp() }
    LaunchedEffect(ui.state) {
        if (ui.state == RealtimeCallState.ENDED) {
            delay(650)
            onBack()
        }
    }

    val persona = ui.persona
    val name = persona?.name ?: "…"
    val active = ui.state in setOf(
        RealtimeCallState.CONNECTING, RealtimeCallState.LISTENING, RealtimeCallState.USER_SPEAKING,
        RealtimeCallState.AI_THINKING, RealtimeCallState.AI_SPEAKING, RealtimeCallState.INTERRUPTED,
    )
    val pulsing = ui.state == RealtimeCallState.AI_SPEAKING || ui.state == RealtimeCallState.USER_SPEAKING
    val breath = rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0.985f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(950, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathScale",
    )
    val avatarScale by animateFloatAsState(if (pulsing) breath.value else 1f, label = "avatarScale")

    Box(Modifier.fillMaxSize().background(NeuBackground)) {
        // 主视觉：大头像 + 名字 + 状态
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1.05f))
            NeuAvatar(
                emoji = persona?.avatarEmoji ?: "🙂",
                imagePath = persona?.avatarImagePath.orEmpty(),
                size = 124.dp,
                ringBrush = personaBrush(personaId),
                modifier = Modifier.scale(avatarScale),
            )
            Spacer(Modifier.height(18.dp))
            Text(name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Crossfade(targetState = stateLabel(ui), label = "stateLabel") { label ->
                Text(label, fontSize = 13.sp, color = MutedText)
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
            if (ui.llmMissing || !hasPermission) {
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .neuSurface(999.dp, color = NeuAccent)
                        .clickable {
                            if (!hasPermission) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text(
                        when {
                            ui.llmMissing -> "实时通话尚未配置，先在聊天里配好 AI 接口"
                            else -> "授予麦克风权限"
                        },
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AccentInk,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
        }

        // 歌词式字幕：贴底、最新清晰、旧的渐隐（透明度阶梯；禁止 blur，用透明度+字号收束）
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 122.dp, start = 30.dp, end = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            val lines = remember(ui.turns, ui.userPartial, ui.aiPartial) { buildLines(ui, name) }
            val alphas = listOf(0.14f, 0.22f, 0.36f, 0.55f, 0.8f, 1f)
            lines.forEachIndexed { i, line ->
                val fromEnd = lines.lastIndex - i
                val alpha = alphas[alphas.lastIndex - fromEnd.coerceIn(0, alphas.lastIndex)]
                val isNew = fromEnd == 0
                Text(
                    subtitleText(line, name, MutedText),
                    fontSize = if (isNew) 15.sp else if (fromEnd == 1) 14.sp else 13.sp,
                    lineHeight = if (isNew) 21.sp else 19.sp,
                    fontWeight = if (isNew) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        isNew -> TextPrimary
                        fromEnd == 1 -> TextSecondary
                        else -> FaintText
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(alpha),
                    maxLines = 2,
                )
            }
        }

        // 底部操作：静音 + 挂断
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
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

/** 字幕行：text + 是否用户 + 是否已收口（partial = 正在进行） */
private data class CallLine(val text: String, val isUser: Boolean, val isPartial: Boolean)

private fun buildLines(ui: RealtimeCallUiState, name: String): List<CallLine> {
    val closed = ui.turns.takeLast(5).map { CallLine(it.text, it.speaker == "user", false) }
    val live = buildList {
        if (ui.userPartial.isNotBlank()) add(CallLine(ui.userPartial, true, true))
        if (ui.aiPartial.isNotBlank()) add(CallLine(ui.aiPartial, false, true))
    }
    return (closed + live).takeLast(6)
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

/** 大号圆形操作钮（静音/挂断）：无涟漪缩放反馈，容器色调用方指定 */
@Composable
private fun CallActionButton(
    size: Dp,
    container: Color,
    tint: Color,
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val press = rememberNeuPressState()
    Box(
        Modifier
            .scale(press.scale)
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
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.44f))
    }
}
