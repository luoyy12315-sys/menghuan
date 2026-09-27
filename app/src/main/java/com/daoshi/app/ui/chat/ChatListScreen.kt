package com.daoshi.app.ui.chat

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.daoshi.app.MentorApp
import com.daoshi.app.data.PersonaRepository
import com.daoshi.app.data.db.AppDatabase
import com.daoshi.app.data.model.ChatMessage
import com.daoshi.app.data.model.Persona
import com.daoshi.app.ui.common.EaseOutQuart
import com.daoshi.app.ui.common.NeuAvatar
import com.daoshi.app.ui.common.NeuCircleButton
import com.daoshi.app.ui.common.NeuTopBar
import com.daoshi.app.ui.common.neuRowPress
import com.daoshi.app.ui.common.neuSurface
import com.daoshi.app.ui.persona.PersonaEditorSheet
import com.daoshi.app.ui.settings.ApiConfigSheet
import com.daoshi.app.ui.settings.SettingsSheet
import com.daoshi.app.ui.theme.FaintText
import com.daoshi.app.ui.theme.MIcons
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.TextPrimary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 会话行 UI：预览/时间取自真实消息记录 */
data class ConversationRowUi(
    val persona: Persona,
    val preview: String,
    val time: String,
    val showDot: Boolean,
)

/**
 * 会话列表页（新拟态）：主入口。
 * - 每个 persona 是一个独立会话，点行进入单聊（onOpen）
 * - 预览 = MessageDao 最后一条消息；未读点 = 导师有新回复
 * - 设置入口在本页右上角（仅此一处）；＋ 直接新增人设
 */
class ChatListViewModel(
    private val personaRepository: PersonaRepository,
    db: AppDatabase,
) : ViewModel() {

    val rows: StateFlow<List<ConversationRowUi>> =
        combine(
            personaRepository.observePersonas(),
            db.messageDao().observeLastMessages(),
        ) { personas, lastMessages ->
            val byConversation = lastMessages.associateBy { it.conversationId }
            personas.map { persona ->
                val last = byConversation[persona.id]
                ConversationRowUi(
                    persona = persona,
                    preview = previewOf(persona, last),
                    time = timeOf(last?.timestamp),
                    showDot = last != null && !last.isFromUser,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun savePersona(persona: Persona) = viewModelScope.launch {
        personaRepository.save(persona)
    }

    private fun previewOf(persona: Persona, last: ChatMessage?): String {
        val content = last?.content?.replace('\n', ' ') ?: return persona.title
        return if (last.isFromUser) "你：$content" else content
    }

    private fun timeOf(timestamp: Long?): String {
        if (timestamp == null) return ""
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochMilli(timestamp).atZone(zone)
        val today = LocalDate.now(zone)
        return when (time.toLocalDate()) {
            today -> time.format(DateTimeFormatter.ofPattern("HH:mm"))
            today.minusDays(1) -> "昨天"
            else ->
                if (time.toLocalDate().isAfter(today.minusDays(7))) weekdayLabel(time.dayOfWeek)
                else "${time.monthValue}月${time.dayOfMonth}日"
        }
    }

    private fun weekdayLabel(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "周一"
        DayOfWeek.TUESDAY -> "周二"
        DayOfWeek.WEDNESDAY -> "周三"
        DayOfWeek.THURSDAY -> "周四"
        DayOfWeek.FRIDAY -> "周五"
        DayOfWeek.SATURDAY -> "周六"
        DayOfWeek.SUNDAY -> "周日"
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MentorApp
                ChatListViewModel(app.personaRepository, app.db)
            }
        }
    }
}

@Composable
fun ChatListScreen(
    onOpen: (Long) -> Unit,
    onManagePersonas: () -> Unit,
    vm: ChatListViewModel = viewModel(factory = ChatListViewModel.Factory),
) {
    val rows by vm.rows.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showApiConfig by remember { mutableStateOf(false) }
    var showNewPersona by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶栏：标题 + 新增人设 + 设置（仅此一处设置入口）
        NeuTopBar(
            title = "消息",
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NeuCircleButton(
                        onClick = { showNewPersona = true },
                        size = 38.dp,
                    ) {
                        Icon(MIcons.Plus, contentDescription = "新增人设", tint = TextPrimary, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    NeuCircleButton(
                        onClick = { showSettings = true },
                        size = 38.dp,
                    ) {
                        Icon(MIcons.Sliders, contentDescription = "设置", tint = TextPrimary, modifier = Modifier.size(19.dp))
                    }
                }
            },
        )

        // 搜索行：凹陷胶囊（二期接真实搜索）
        Box(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .neuSurface(16.dp, color = MaterialTheme.colorScheme.background, elevation = 5.dp, inset = true)
                    .neuRowPress {
                        Toast.makeText(context, "开发中", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    MIcons.Search,
                    contentDescription = null,
                    tint = MutedText,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("搜索", fontSize = 14.sp, color = MutedText)
            }
        }

        // 会话列表：级联淡入（每条 40ms，封顶 320ms）+ 位置重排动画
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(rows, key = { _, row -> row.persona.id }) { index, row ->
                Box(Modifier.animateItem(fadeInSpec = tween(340, delayMillis = (index * 40).coerceAtMost(320)))) {
                    ConversationRow(
                        persona = row.persona,
                        preview = row.preview,
                        time = row.time,
                        unread = row.showDot,
                        onClick = { onOpen(row.persona.id) },
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            onDismiss = { showSettings = false },
            onApiConfig = {
                showSettings = false
                showApiConfig = true
            },
            onPersonaManage = {
                showSettings = false
                onManagePersonas()
            },
        )
    }
    if (showApiConfig) {
        ApiConfigSheet(onDismiss = { showApiConfig = false })
    }
    if (showNewPersona) {
        PersonaEditorSheet(
            persona = null,
            onSave = { vm.savePersona(it); showNewPersona = false },
            onDismiss = { showNewPersona = false },
        )
    }
}

@Composable
private fun ConversationRow(
    persona: Persona,
    preview: String,
    time: String,
    unread: Boolean,
    onClick: () -> Unit,
) {
    val dotScale by animateFloatAsState(if (unread) 1f else 0f, tween(220, easing = EaseOutQuart))
    Row(
        Modifier
            .fillMaxWidth()
            .neuRowPress(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeuAvatar(persona.avatarEmoji, size = 46.dp)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                persona.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                preview,
                fontSize = 13.sp,
                color = MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(time, fontSize = 11.sp, color = FaintText)
            Box(
                Modifier.size(9.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (dotScale > 0.01f) {
                    Box(
                        Modifier
                            .scale(dotScale)
                            .size(9.dp)
                            .background(NeuAccent, CircleShape),
                    )
                }
            }
        }
    }
}
