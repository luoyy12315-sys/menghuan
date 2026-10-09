package com.menghuan.app.ui.live2d

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.live2d.ActionComposer
import com.menghuan.app.live2d.ActionPart
import com.menghuan.app.live2d.GenericChannel
import com.menghuan.app.live2d.Live2dAction
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary

/**
 * 「新建 / 编辑动作」弹层（v0.82.0 P2-3 从 `Live2dActionSheets.kt` 拆出来）。
 *
 * 共用的小组件（`PickChip` / `PrimaryPill` / `SliderRow` / [NONE_KEY] / [channelLabel]）
 * 见本包里的 `Live2dSheetWidgets.kt`。
 */

// =====================================================================
// 新建 / 编辑动作（普通模式）
// =====================================================================

/**
 * 「新建动作」编辑器 —— 普通模式 + **高级模式**（v0.74.0 ROADMAP 候选②）。
 *
 * 普通模式用户看到的是：名字、基础动作、强度、速度、时长、四个部位开关、表情、预览、保存。
 * **全程不出现任何 Live2D 参数名。**
 *
 * 高级模式（用户点标题右侧的「高级」展开）给的是**逐通道的幅度倍率**：
 * 每个用到的通道一行滑块（0.2×–2.0×），解决"整体强度调大之后，某一条动得过了/不够"这种事。
 *
 * ⚠️ 高级模式**刻意不做逐关键帧编辑**：那是完整关键帧编辑器的量级，对绝大多数用户是负担。
 * 倍率是"整体强度"与"逐帧坐标"之间唯一真正能用的一层。
 * 普通模式里那四个部位开关在高级模式下依然有效（筛轨道的动作不变），两层叠加。
 */
@Composable
fun ActionEditorSheet(
    base: Live2dAction?,
    /** 可以当基础动作的：内置的趣味动作 + 用户自己建的（模型自带的也能用，但它们的时长由模型决定，先不放） */
    templates: List<Live2dAction>,
    /** 当前模型自带的表情名 */
    expressions: List<String>,
    onPreview: (Live2dAction) -> Unit,
    onSave: (Live2dAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(base) { mutableStateOf(base?.name ?: "") }
    var chosenKey by remember(base) { mutableStateOf(base?.builtinKey ?: NONE_KEY) }
    var chosenId by remember(base) { mutableStateOf(base?.id ?: 0L) }
    var amplitude by remember(base) { mutableStateOf(base?.safeAmplitude ?: 1f) }
    var speed by remember(base) { mutableStateOf(base?.safeSpeed ?: 1f) }
    var durationMs by remember(base) { mutableStateOf(base?.safeDurationMs ?: 2400) }
    var parts by remember(base) { mutableStateOf(ActionComposer.partsOf(base)) }
    var expression by remember(base) { mutableStateOf(base?.expression.orEmpty()) }
    // 高级模式：展开 + 逐通道倍率表（空表 = 全 1.0）
    var advanced by remember(base) { mutableStateOf(false) }
    var channelFactors by remember(base) { mutableStateOf(emptyMap<GenericChannel, Float>()) }

    val selectedTemplate = remember(chosenKey, chosenId, templates, base) {
        when {
            base != null && chosenKey == (base.builtinKey.ifBlank { NONE_KEY }) && chosenId == base.id -> base
            chosenKey == NONE_KEY -> null
            chosenKey.startsWith("id:") -> templates.firstOrNull { it.id == chosenId }
            else -> templates.firstOrNull { it.builtinKey == chosenKey }
        }
    }

    // 高级模式要调的通道 = 这套动作真正用到的那些（按部位开关筛完之后的）
    val advChannels = remember(selectedTemplate, parts) {
        val raw = selectedTemplate?.tracks?.takeIf { it.isNotEmpty() }
            ?: ActionComposer.defaultTracks(parts)
        raw.map { it.channel }.distinct()
    }

    fun build() = ActionComposer.compose(
        base = selectedTemplate,
        name = name,
        amplitude = amplitude,
        speed = speed,
        durationMs = durationMs,
        parts = parts,
        expression = expression,
        channelFactors = if (advanced) channelFactors else emptyMap(),
    )

    NeuSheetScaffold(title = if (base != null) "调整动作" else "新建动作", onDismiss = onDismiss) {
        NeuTextField(
            value = name,
            onValueChange = { name = it },
            label = "动作名字",
            placeholder = "比如：开心挥手",
        )
        Spacer(Modifier.height(16.dp))

        Text("基础动作", fontSize = 11.sp, color = TextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                PickChip(
                    text = "从零拼一个",
                    selected = chosenKey == NONE_KEY,
                    onClick = { chosenKey = NONE_KEY; chosenId = 0L },
                )
            }
            items(templates, key = { "${it.id}|${it.builtinKey}|${it.name}" }) { t ->
                PickChip(
                    text = t.name,
                    selected = chosenKey == t.builtinKey.ifBlank { "id:${t.id}" },
                    onClick = {
                        chosenKey = t.builtinKey.ifBlank { "id:${t.id}" }
                        chosenId = t.id
                        if (name.isBlank()) name = t.name
                        durationMs = t.safeDurationMs
                        parts = ActionComposer.partsOf(t)
                    },
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        // 滑块的上限**直接取数据层那两个常数**（v0.66 之前这里写的是 1.8 / 1.8，
        // 比 `Live2dAction.MAX_AMPLITUDE / MAX_SPEED` 小一号）：两套上限并存时，
        // 用户在别处（AI 生成、导入）能拿到 2.0、在编辑器里却拖不到 —— 那是两个东西在说话。
        SliderRow(
            label = "强度",
            value = amplitude,
            range = Live2dAction.MIN_AMPLITUDE..Live2dAction.MAX_AMPLITUDE,
            display = "×%.1f".format(amplitude),
        ) {
            amplitude = it
        }
        SliderRow(
            label = "速度",
            value = speed,
            range = Live2dAction.MIN_SPEED..Live2dAction.MAX_SPEED,
            display = "×%.1f".format(speed),
        ) {
            speed = it
        }
        SliderRow(
            label = "时长",
            value = durationMs.toFloat(),
            range = 600f..6000f,
            display = "%.1f 秒".format(durationMs / 1000f),
        ) { durationMs = it.toInt() }

        Spacer(Modifier.height(6.dp))
        Text("动作包含哪些部分", fontSize = 11.sp, color = TextSecondary, letterSpacing = 0.6.sp)
        Spacer(Modifier.height(8.dp))
        ActionPart.entries.forEach { part ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(part.label, fontSize = 14.5.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                NeuSwitch(
                    checked = part in parts,
                    onToggle = { on ->
                        parts = if (on) parts + part else parts - part
                    },
                )
            }
        }

        if (expressions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("顺带做个表情", fontSize = 11.sp, color = TextSecondary, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    PickChip(text = "不用", selected = expression.isBlank(), onClick = { expression = "" })
                }
                items(expressions, key = { it }) { e ->
                    PickChip(text = e, selected = expression == e, onClick = { expression = e })
                }
            }
        }

        // ---- 高级模式（v0.74.0）：逐通道幅度倍率 ----
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = { advanced = !advanced },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("高级", fontSize = 14.5.sp, color = TextPrimary, modifier = Modifier.weight(1f))
            Text(if (advanced) "▾" else "▸", fontSize = 13.sp, color = MutedText)
        }
        if (advanced) {
            Spacer(Modifier.height(6.dp))
            if (advChannels.isEmpty()) {
                Text("这套动作还没有可调的通道", fontSize = 12.sp, color = MutedText)
            } else {
                advChannels.forEach { ch ->
                    val f = channelFactors[ch] ?: 1f
                    SliderRow(
                        label = channelLabel(ch),
                        value = f,
                        range = ActionComposer.MIN_CHANNEL_FACTOR..ActionComposer.MAX_CHANNEL_FACTOR,
                        display = "×%.2f".format(f),
                    ) {
                        // 拖回 1.0 就从表里删掉：表越干净，"这条其实没调过"越一眼可见
                        channelFactors = if (kotlin.math.abs(it - 1f) < 0.005f) {
                            channelFactors - ch
                        } else {
                            channelFactors + (ch to it)
                        }
                    }
                }
                if (channelFactors.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "恢复全部",
                        fontSize = 12.5.sp,
                        color = NeuAccent,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                                onClick = { channelFactors = emptyMap() },
                            )
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) {
                PrimaryPill(
                    text = "预览",
                    filled = false,
                    enabled = build().hasTracks,
                    onClick = { onPreview(build()) },
                )
            }
            Box(Modifier.weight(1f)) {
                PrimaryPill(
                    text = "保存到我的动作",
                    filled = true,
                    enabled = build().hasTracks,
                    onClick = { onSave(build()) },
                )
            }
        }
        if (!build().hasTracks) {
            Spacer(Modifier.height(10.dp))
            Text(
                "这条动作现在什么都不动",
                fontSize = 12.sp,
                color = NeuDanger,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
