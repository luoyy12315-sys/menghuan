package com.menghuan.app.ui.realtime

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
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
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.realtime.RealtimeCallState
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.rememberNeuPressState
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.personaBrush
import com.menghuan.app.realtime.CallTurnData

/**
 * 实时语音通话页（v0.46.0）：状态 + 字幕 + 静音/挂断。
 * 视觉遵循 DESIGN.md（黑白骨架 + MIcons 线条图标），字幕是辅助（任务书 §十四），
 * 主视觉是头像 + 状态文案。
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
    // 返回键 = 挂断；引擎走到 ENDED 后自动退出页面（保证资源释放完再离开）
    BackHandler { vm.hangUp() }
    LaunchedEffect(ui.state) {
        if (ui.state == RealtimeCallState.ENDED) onBack()
    }

    val persona = ui.persona
    val name = persona?.name ?: "…"
    val active = ui.state in setOf(
        RealtimeCallState.CONNECTING, RealtimeCallState.LISTENING, RealtimeCallState.USER_SPEAKING,
        RealtimeCallState.AI_THINKING, RealtimeCallState.AI_SPEAKING, RealtimeCallState.INTERRUPTED,
    )

    AdaptiveScreen {
        Column(Modifier.fillMaxSize()) {
            NeuTopBar(
                navigationIcon = {
                    NeuCircleButton(
                        onClick = { vm.hangUp() },
                        size = 38.dp,
                    ) {
                        Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
                    }
                },
                center = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NeuAvatar(
                            emoji = persona?.avatarEmoji ?: "🙂",
                            imagePath = persona?.avatarImagePath.orEmpty(),
                            size = 38.dp,
                            ringBrush = personaBrush(personaId),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(stateLabel(ui), fontSize = 11.sp, color = MutedText)
                        }
                    }
                },
                actions = {},
            )

            // 中部主视觉：大头像 + 状态
            Column(
                Modifier.fillMaxWidth().weight(0.62f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                NeuAvatar(
                    emoji = persona?.avatarEmoji ?: "🙂",
                    imagePath = persona?.avatarImagePath.orEmpty(),
                    size = 108.dp,
                    ringBrush = personaBrush(personaId),
                )
                Spacer(Modifier.height(22.dp))
                Text(
                    when {
                        ui.llmMissing -> "实时通话尚未配置"
                        !hasPermission -> "需要麦克风权限才能开始通话"
                        else -> stateLabel(ui)
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                )
                if (ui.error != null) {
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier
                            .neuSurface(999.dp, color = NeuTrack)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(ui.error ?: "", fontSize = 12.sp, color = NeuDanger)
                    }
                }
                if (!hasPermission) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier
                            .neuSurface(999.dp, color = NeuAccent)
                            .clickable { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                    ) {
                        Text("授予麦克风权限", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AccentInk)
                    }
                }
            }

            // 字幕区（辅助 UI，任务书 §十四：不做主视觉）
            SubtitleList(ui, name, Modifier.weight(0.9f))

            // 底部操作：静音 + 挂断
            Row(
                Modifier.fillMaxWidth().padding(bottom = 34.dp),
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
}

@Composable
private fun SubtitleList(
    ui: RealtimeCallUiState,
    personaName: String,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        val visible = ui.turns.takeLast(40)
        items(visible.size) { i ->
            val t = visible[i]
            SubtitleLine(speaker = if (t.speaker == "user") "你" else personaName, text = t.text)
        }
        if (ui.userPartial.isNotBlank()) {
            item { SubtitleLine(speaker = "你", text = ui.userPartial) }
        }
        if (ui.aiPartial.isNotBlank()) {
            item { SubtitleLine(speaker = personaName, text = ui.aiPartial) }
        }
    }
    LaunchedEffect(ui.turns.size, ui.userPartial, ui.aiPartial) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) runCatching { listState.scrollToItem(count - 1) }
    }
}

@Composable
private fun SubtitleLine(speaker: String, text: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = MutedText)) {
                append("$speaker：")
            }
            withStyle(SpanStyle(fontSize = 13.sp, color = TextPrimary)) {
                append(text)
            }
        },
        lineHeight = 19.sp,
    )
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
