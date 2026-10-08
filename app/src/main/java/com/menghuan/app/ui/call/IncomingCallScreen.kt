package com.menghuan.app.ui.call

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.menghuan.app.MainActivity
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.AiCall
import com.menghuan.app.data.CallRingtone
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.Persona
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MentorTheme
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.ThemeMode
import com.menghuan.app.ui.theme.personaBrush
import com.menghuan.app.ui.theme.resolveDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 响铃最多响多久：没人接就自动挂断，并把开场白转成一条留言 */
private const val RING_TIMEOUT_MS = 30_000L

/**
 * 来电页（v0.53.5）：AI 主动打电话的落地界面。
 *
 * 它是**独立 Activity**而不是 App 内的一个页面 —— 因为要能在**锁屏上直接盖上来**
 * （`setShowWhenLocked` + `setTurnScreenOn`），这是"像真电话"的关键；全屏通知
 * （`setFullScreenIntent`）指向的就是它。
 *
 * 接听 = 跳回主界面走现有的实时语音通话路由；挂断/超时 = 把开场白**当成一条留言**落进会话，
 * 不白打这一通。
 */
class IncomingCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // v0.53.7：**整页铺满** —— 以前没关掉 decorFitsSystemWindows，状态栏/导航栏把内容挤住，
        // 来电界看着"不是全屏"。背景铺到系统栏下面，内容再用 systemBarsPadding 避让。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }

        val personaId = intent.getLongExtra(AiCall.EXTRA_PERSONA_ID, 0L)
        val name = intent.getStringExtra(AiCall.EXTRA_PERSONA_NAME).orEmpty()
        val greeting = intent.getStringExtra(AiCall.EXTRA_GREETING).orEmpty()
        if (personaId <= 0L) {
            finish()
            return
        }

        setContent {
            val app = application as MenghuanApp
            val mode = remember { runCatching { app.settingsRepository.themeModeBlocking() }.getOrDefault("SYSTEM") }
            val dark = resolveDarkTheme(ThemeMode.fromName(mode), isSystemInDarkTheme())
            MentorTheme(darkTheme = dark) {
                IncomingCallScreen(
                    personaId = personaId,
                    personaName = name,
                    onAccept = { acceptCall(personaId, greeting) },
                    onDecline = { decline(personaId, greeting) },
                    onTimeout = { decline(personaId, greeting) },
                )
            }
        }
    }

    /** 接听：回主界面并直达实时语音通话页（复用现有链路；走 extra 而不是 deep link，
     *  因为 MainActivity 大概率已在栈里，deep link 只在冷启动时被 NavHost 处理）。
     *  v0.53.7：**必须把"是 AI 打的"和开场白一起带过去** —— 通话页据此决定谁先开口，
     *  通话记录卡也据此落在 AI 那一侧。 */
    private fun acceptCall(personaId: Long, greeting: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AiCall.EXTRA_OPEN_CALL_PERSONA, personaId)
                .putExtra(AiCall.EXTRA_OPEN_CALL_GREETING, greeting)
                .putExtra(AiCall.EXTRA_OPEN_CALL_BY_AI, true),
        )
        finish()
    }

    /** 没接 / 挂断：开场白改成一条留言消息落进会话 */
    private fun decline(personaId: Long, greeting: String) {
        if (greeting.isNotBlank()) {
            val app = application as MenghuanApp
            lifecycleScope.launch {
                runCatching {
                    app.db.messageDao().insert(
                        ChatMessage(conversationId = personaId, isFromUser = false, content = greeting),
                    )
                }
            }
        }
        finish()
    }
}

@Composable
private fun IncomingCallScreen(
    personaId: Long,
    personaName: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onTimeout: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MenghuanApp

    // 颜色令牌只在 composable 作用域读
    val surface = NeuSurface
    val accent = NeuAccent
    val danger = NeuDanger
    val ink = TextPrimary

    val persona by produceState<Persona?>(initialValue = null, personaId) {
        value = runCatching { app.db.personaDao().getById(personaId) }.getOrNull()
    }

    // 响铃（v0.53.7；v0.54.4 起按角色）：角色专属铃声 → 设置里的默认铃声 → 系统默认铃声
    //
    // v0.62.0（用户点名"AI 主动打电话时就算免打扰也要有铃声"）：**三档一律放到闹钟音轨**。
    // 原来系统默认铃声走 `Ringtone.play()` = RINGTONE 音轨，系统勿扰下被直接静音 ——
    // 这就是真机上"开了勿扰，AI 打过来一点声音都没有"的根因（不是没弹通知，是没声）。
    // `USAGE_ALARM` 是闹钟 App 穿勿扰的标准做法，不需要任何权限；
    // 代价：吃的是**闹钟音量**那条滑杆，闹钟音量被拉到 0 就真的听不见（界面上没法替用户判断）。
    var ringPlayer by remember { mutableStateOf<android.media.MediaPlayer?>(null) }
    LaunchedEffect(Unit) {
        val custom = runCatching { app.settingsRepository.ringtoneForPersona(personaId) }
            .getOrDefault("")
        ringPlayer = if (custom.isNotBlank()) {
            CallRingtone.loopPlayer(custom, ringThroughDnd = true)
        } else {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            CallRingtone.loopPlayer(context, uri, ringThroughDnd = true)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ringPlayer?.stop() }
            runCatching { ringPlayer?.release() }
        }
    }
    LaunchedEffect(Unit) {
        delay(RING_TIMEOUT_MS)
        onTimeout()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .systemBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NeuAvatar(
                emoji = persona?.avatarEmoji?.ifBlank { "🙂" } ?: "🙂",
                size = 96.dp,
                ringBrush = personaBrush(personaId),
                imagePath = persona?.avatarImagePath.orEmpty(),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                personaName.ifBlank { persona?.name.orEmpty() }.ifBlank { "对方" },
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = ink,
            )
            Spacer(Modifier.height(6.dp))
            Text("想跟你说话", fontSize = 14.sp, color = MutedText)
            Spacer(Modifier.height(56.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(56.dp)) {
                CallActionButton(
                    label = "挂断",
                    background = danger,
                    content = { tint ->
                        Icon(
                            MIcons.Phone,
                            contentDescription = "挂断",
                            tint = tint,
                            modifier = Modifier.size(28.dp).rotate(135f),
                        )
                    },
                    onClick = onDecline,
                )
                CallActionButton(
                    label = "接听",
                    background = accent,
                    content = { tint ->
                        Icon(MIcons.Phone, contentDescription = "接听", tint = tint, modifier = Modifier.size(28.dp))
                    },
                    onClick = onAccept,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text("30 秒没人接会自动挂断，并留一句话给你", fontSize = 12.sp, color = MutedText)
        }
    }
}

/** 来电页上的圆形操作键（接听 / 挂断）：实心圆 + 图标 + 下方文字 */
@Composable
private fun CallActionButton(
    label: String,
    background: Color,
    content: @Composable (Color) -> Unit,
    onClick: () -> Unit,
) {
    val ink = AccentInk
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            content(ink)
        }
        Spacer(Modifier.height(10.dp))
        Text(label, fontSize = 13.sp, color = TextPrimary)
    }
}
