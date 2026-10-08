package com.menghuan.app.ui.live2d

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.menghuan.app.MenghuanApp
import com.menghuan.app.live2d.ActionSlot
import com.menghuan.app.live2d.ActionSource
import com.menghuan.app.live2d.ActionScale
import com.menghuan.app.live2d.BuiltinActions
import com.menghuan.app.live2d.Live2dAction
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.staggerEnter
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuBackground
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 通话动作页的 UI 状态 */
data class CallActionUiState(
    val actions: List<Live2dAction> = emptyList(),
    val bindings: Map<ActionSlot, Long> = emptyMap(),
    /** 每个槽实际会播的那条（绑定的，或该槽的内置默认） */
    val resolved: Map<ActionSlot, Live2dAction> = emptyMap(),
    /** 这一角色的"动作幅度"档位键（见 [ActionScale]） */
    val scaleKey: String = ActionScale.STANDARD.key,
)

/**
 * 「通话动作」页（v0.64.0）—— 用户要求 §一：四个状态各绑一个动作，可以替换。
 *
 * 这一页**只做绑定**，没有任何动作编辑能力（用户第 2 条：通话页只负责调用动作）。
 * 想改动作、想新建，去「角色空间」的动作库 —— 那边有预览、有编辑器、有 AI 生成。
 *
 * 这里**不做试播**：试播要挂一整块 Live2D 引擎，而这是一张设置页；
 * 为了"点一下听个响"在设置页里养一份 GL 上下文不划算（角色空间已经给了随时可试的地方）。
 */
@Composable
fun CallActionScreen(
    personaId: Long,
    onBack: () -> Unit,
    vm: CallActionViewModel = viewModel(
        key = "call_actions_$personaId",
        factory = CallActionViewModel.Factory(personaId),
    ),
) {
    val ui by vm.ui.collectAsState()
    var picking by remember { mutableStateOf<ActionSlot?>(null) }

    Column(Modifier.fillMaxSize().background(NeuBackground)) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .staggerEnter(0, stepMs = 40, slideDp = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeuCircleButton(onClick = onBack, size = 40.dp) {
                Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("通话动作", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Text("四个状态各自播什么", fontSize = 11.5.sp, color = MutedText)
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
        ) {
            // 动作幅度（v0.67.0）：用户要的"看得出来在动"，按角色定一档就够，
            // 不要做成滑块 —— 那一层已经有每条动作自己的"强度"了，两层都连续就没人知道最终幅度是谁定的。
            ScaleRow(current = ui.scaleKey) { key -> vm.setScale(key) }
            Spacer(Modifier.height(18.dp))

            ActionSlot.entries.forEachIndexed { i, slot ->
                val bound = ui.bindings[slot] ?: 0L
                val current = ui.resolved[slot]
                SlotRow(
                    title = slot.label,
                    subtitle = current?.name.orEmpty(),
                    custom = bound > 0L,
                    index = i + 1,
                    onClick = { picking = slot },
                )
                if (i != ActionSlot.entries.lastIndex) Spacer(Modifier.height(10.dp))
            }
        }
    }

    picking?.let { slot ->
        SlotPickerSheet(
            slot = slot,
            // 四态默认动作**不进这张单子**（v0.67.0）：它们就是第一项「系统默认」本身，
            // 列出来等于让用户可以把"默认待机"绑到"说话"上 —— 那一档播起来和没绑一样，
            // 还会让他以为自己挑坏了。
            actions = ui.actions.filter { it.builtinKey !in stateDefaultKeys() },
            boundId = ui.bindings[slot] ?: 0L,
            onPick = { actionId ->
                vm.bind(slot, actionId)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** 四个状态各自的内置默认动作键（选择器要排除的就是这四条） */
private fun stateDefaultKeys(): Set<String> =
    ActionSlot.entries.map { BuiltinActions.defaultKeyOf(it) }.toSet()

/**
 * 「动作幅度」四档（v0.67.0）。
 *
 * 一行四颗胶囊，选中 = 纯黑药丸白字（全局 `NeuChip` 的语言）。
 * 刻意做在四个状态**上面**：这一档影响的是整场通话的观感，
 * 而四条绑定只是"哪个状态演哪条"——先定"演多用力"再定"演什么"，读起来才顺。
 *
 * 点一下就生效（不弹层、不确认）：它是个偏好，不是一个操作。
 */
@Composable
private fun ScaleRow(current: String, onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .staggerEnter(0, stepMs = 40, slideDp = 10.dp)
            .neuSurface(14.dp, color = NeuSurface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text("动作幅度", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionScale.entries.forEach { level ->
                val selected = level.key == current
                Box(
                    Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onPick(level.key) },
                        )
                        .neuSurface(999.dp, color = if (selected) NeuAccent else NeuSurface)
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        level.label,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) AccentInk else TextSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotRow(
    title: String,
    subtitle: String,
    custom: Boolean,
    index: Int,
    onClick: () -> Unit,
) {    Row(
        Modifier
            .fillMaxWidth()
            .staggerEnter(index, stepMs = 40, slideDp = 10.dp)
            .neuSurface(14.dp, color = NeuSurface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                if (subtitle.isBlank()) "默认动作" else subtitle,
                fontSize = 12.5.sp,
                color = if (custom) TextPrimary else MutedText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (custom) {
            Text("已自定义", fontSize = 11.5.sp, color = MutedText)
            Spacer(Modifier.width(8.dp))
        }
        Icon(MIcons.ChevronRight, contentDescription = null, tint = MutedText, modifier = Modifier.size(16.dp))
    }
}

/** 选动作的弹层：第一项恒为"恢复默认"（它必须一直能点回去，否则用户改坏了没退路） */
@Composable
private fun SlotPickerSheet(
    slot: ActionSlot,
    actions: List<Live2dAction>,
    boundId: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    NeuSheetScaffold(title = "「${slot.label}」播哪个动作", onDismiss = onDismiss) {
        PickerRow(
            title = "系统默认",
            subtitle = "内置的「默认${slot.label}」动作",
            selected = boundId <= 0L,
            onClick = { onPick(0L) },
        )
        Spacer(Modifier.height(8.dp))

        val groups = listOf(
            ActionSource.BUILTIN to "内置动作",
            ActionSource.MODEL to "模型自带",
            ActionSource.USER to "我的动作",
            ActionSource.AI to "AI 生成",
        )
        groups.forEach { (source, label) ->
            val rows = actions.filter { it.source == source }
            if (rows.isEmpty()) return@forEach
            Text(
                "$label · ${rows.size}",
                fontSize = 11.sp,
                color = MutedText,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 6.dp),
            )
            rows.forEach { action ->
                PickerRow(
                    title = action.name,
                    subtitle = if (action.loop) "循环" else "播一次",
                    selected = action.id == boundId,
                    onClick = { onPick(action.id) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .neuSurface(12.dp, color = if (selected) NeuTrack else NeuSurface)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, fontSize = 12.sp, color = MutedText, maxLines = 1)
            }
        }
        if (selected) Text("✓", fontSize = 15.sp, color = NeuAccent, fontWeight = FontWeight.Bold)
    }
}

/** 绑定页的 ViewModel：只做"读库 + 写绑定 + 解析当前该播哪条" */
class CallActionViewModel(
    private val app: MenghuanApp,
    private val personaId: Long,
) : ViewModel() {

    private val _ui = MutableStateFlow(CallActionUiState())
    val ui: StateFlow<CallActionUiState> = _ui

    init {
        viewModelScope.launch {
            app.live2dActions.observeBindings(personaId).collect { bindings ->
                _ui.update { it.copy(bindings = bindings) }
                refreshResolved()
            }
        }
        viewModelScope.launch {
            app.live2dActions.observe(personaId).collect { actions ->
                _ui.update { it.copy(actions = actions) }
            }
        }
        // 幅度档位（v0.67.0）：跟着角色走的一张表，读整张再挑自己那一行
        viewModelScope.launch {
            app.settingsRepository.callActionScale.collect { table ->
                _ui.update { it.copy(scaleKey = table[personaId] ?: ActionScale.STANDARD.key) }
            }
        }
    }

    private suspend fun refreshResolved() {
        val resolved = runCatching { app.live2dActions.resolveAllSlots(personaId) }
            .getOrDefault(emptyMap())
        _ui.update { it.copy(resolved = resolved) }
    }

    fun bind(slot: ActionSlot, actionId: Long) {
        viewModelScope.launch {
            runCatching { app.live2dActions.setBinding(personaId, slot, actionId) }
            refreshResolved()
        }
    }

    /** 换"动作幅度"档位。**存完就生效**：通话那头下一次拿到动作时乘的就是新档 */
    fun setScale(key: String) {
        viewModelScope.launch {
            runCatching { app.settingsRepository.setCallActionScale(personaId, key) }
        }
    }

    companion object {
        fun Factory(personaId: Long) = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MenghuanApp
                CallActionViewModel(app, personaId)
            }
        }
    }
}
