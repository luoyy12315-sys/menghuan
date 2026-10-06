package com.menghuan.app.ui.persona

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.PersonaRepository
import com.menghuan.app.data.model.Persona
import com.menghuan.app.ui.common.NeuAvatar
import com.menghuan.app.ui.common.NeuCard
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.personaBrush
import kotlinx.coroutines.launch
import com.menghuan.app.ui.theme.NeuSurface

/**
 * 人设管理页（新拟态）：凸起卡片列表 + 新增/编辑（PersonaEditorSheet）+ 删除（仅自建）。
 * 入口：会话列表 → 设置 · 人设管理。
 */
class PersonaManageViewModel(private val repository: PersonaRepository) : ViewModel() {

    val personas = repository.observePersonas()

    fun save(persona: Persona) = viewModelScope.launch { repository.save(persona) }

    fun delete(persona: Persona) = viewModelScope.launch { repository.delete(persona.id) }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MenghuanApp
                PersonaManageViewModel(app.personaRepository)
            }
        }
    }
}

@Composable
fun PersonaManageScreen(
    onBack: () -> Unit,
    /** v0.53.5：编辑人设是独立整页，这里只把 id 交出去（0 = 新建） */
    onEdit: (Long) -> Unit,
    vm: PersonaManageViewModel = viewModel(factory = PersonaManageViewModel.Factory),
) {
    val personas by vm.personas.collectAsState(initial = emptyList())
    var deleteTarget by remember { mutableStateOf<Persona?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NeuTopBar(
            title = "人设管理",
            navigationIcon = {
                NeuCircleButton(onClick = onBack, size = 38.dp) {
                    Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
                }
            },
            actions = {
                NeuCircleButton(onClick = { onEdit(0L) }, size = 38.dp) {
                    Icon(MIcons.Plus, contentDescription = "新增人设", tint = TextPrimary, modifier = Modifier.size(19.dp))
                }
            },
        )

        if (personas.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无人设", fontSize = 14.sp, color = MutedText)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(personas, key = { _, p -> p.id }, contentType = { _, _ -> "persona" }) { index, persona ->
                Box(Modifier.animateItem(fadeInSpec = tween(340, delayMillis = (index * 50).coerceAtMost(300)))) {
                    PersonaCard(
                        persona = persona,
                        onClick = { onEdit(persona.id) },
                        onLongClick = { deleteTarget = persona },
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    // v0.53.5：编辑人设走独立路由（整页），这里只剩列表自己的长按删除确认
    deleteTarget?.let { target ->
        AlertDialog(
            tonalElevation = 0.dp, // v0.42.1 全应用去阴影（该重载阴影来自 tonalElevation）
            onDismissRequest = { deleteTarget = null },
            containerColor = NeuSurface,
            shape = RoundedCornerShape(20.dp),
            title = { Text("删除人设", fontWeight = FontWeight.SemiBold, color = TextPrimary) },
            text = {
                Text(
                    "将删除「${target.name}」及其全部聊天消息，此操作不可恢复。",
                    color = MutedText,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target)
                    deleteTarget = null
                }) { Text("删除", color = NeuDanger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消", color = MutedText) }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PersonaCard(persona: Persona, onClick: () -> Unit, onLongClick: () -> Unit) {
    NeuCard(
        cornerRadius = 22.dp,
        contentPadding = 14.dp,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeuAvatar(persona.avatarEmoji, size = 44.dp, ringBrush = personaBrush(persona.id), imagePath = persona.avatarImagePath)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        persona.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                    )
                    if (persona.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Text("📌", fontSize = 11.sp)
                    }
                    if (persona.isBuiltIn) {
                        Spacer(Modifier.width(7.dp))
                        BuiltinTag()
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    persona.systemPrompt,
                    fontSize = 13.sp,
                    color = MutedText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                MIcons.ChevronRight,
                contentDescription = null,
                tint = FaintText,
                modifier = Modifier.width(18.dp),
            )
        }
    }
}

/** "预置"小标签：凹陷胶囊 */
@Composable
private fun BuiltinTag() {
    Box(
        Modifier
            .neuSurface(999.dp, color = MaterialTheme.colorScheme.background, elevation = 3.dp, inset = true)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text("预置", fontSize = 10.sp, color = NeuAccent, fontWeight = FontWeight.Medium)
    }
}
