package com.daoshi.app.ui.persona

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
import com.daoshi.app.data.model.Persona
import com.daoshi.app.ui.common.NeuAvatar
import com.daoshi.app.ui.common.NeuCard
import com.daoshi.app.ui.common.NeuCircleButton
import com.daoshi.app.ui.common.NeuTopBar
import com.daoshi.app.ui.common.neuRowPress
import com.daoshi.app.ui.common.neuSurface
import com.daoshi.app.ui.theme.FaintText
import com.daoshi.app.ui.theme.MIcons
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.TextPrimary
import kotlinx.coroutines.launch

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
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MentorApp
                PersonaManageViewModel(app.personaRepository)
            }
        }
    }
}

@Composable
fun PersonaManageScreen(
    onBack: () -> Unit,
    vm: PersonaManageViewModel = viewModel(factory = PersonaManageViewModel.Factory),
) {
    val personas by vm.personas.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Persona?>(null) }
    var showEditor by remember { mutableStateOf(false) }

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
                NeuCircleButton(onClick = { editing = null; showEditor = true }, size = 38.dp) {
                    Icon(MIcons.Plus, contentDescription = "新增人设", tint = TextPrimary, modifier = Modifier.size(19.dp))
                }
            },
        )

        Text(
            "每个人设是一个独立会话；名字、身份、设定词随时可改。",
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 12.dp),
            fontSize = 12.sp,
            color = MutedText,
        )

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(personas, key = { _, p -> p.id }) { index, persona ->
                Box(Modifier.animateItem(fadeInSpec = tween(340, delayMillis = (index * 50).coerceAtMost(300)))) {
                    PersonaCard(
                        persona = persona,
                        onClick = {
                            editing = persona
                            showEditor = true
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (showEditor) {
        val target = editing
        PersonaEditorSheet(
            persona = target,
            onSave = { vm.save(it); showEditor = false },
            onDelete = if (target?.isBuiltIn == true) null else {
                {
                    target?.let { vm.delete(it) }
                    showEditor = false
                }
            },
            onDismiss = { showEditor = false },
        )
    }
}

@Composable
private fun PersonaCard(persona: Persona, onClick: () -> Unit) {
    NeuCard(
        cornerRadius = 22.dp,
        contentPadding = 14.dp,
        modifier = Modifier.fillMaxWidth().neuRowPress(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeuAvatar(persona.avatarEmoji, size = 44.dp)
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        persona.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                    )
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
