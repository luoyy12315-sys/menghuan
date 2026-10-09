package com.menghuan.app.ui.live2d

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.live2d.ActionPart
import com.menghuan.app.live2d.Live2dAction
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * 「AI 生成动作」弹层（v0.82.0 P2-3 从 `Live2dActionSheets.kt` 拆出来）。
 *
 * 共用的小组件（`PickChip` / `PrimaryPill` / [AI_EXAMPLES]）见本包里的 `Live2dSheetWidgets.kt`。
 */

// =====================================================================
// AI 生成
// =====================================================================

/**
 * AI 动作生成（用户 §七的流程：描述 → 分析 → 结合模型能力 → 生成配置 → 本地预览 → 确认保存）。
 *
 * ⚠️ 两步是**刻意的**：生成完不自动保存、不自动播放。
 * 用户要求里写着"本地预览 → 用户确认 → 保存到我的动作"，
 * 而且未经确认就落库的 AI 内容，用户想反悔还得自己去删 —— 那不像话。
 */
@Composable
fun AiActionSheet(
    generate: suspend (String) -> kotlin.Result<Live2dAction>,
    onPreview: (Live2dAction) -> Unit,
    onSave: (Live2dAction, () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Live2dAction?>(null) }

    NeuSheetScaffold(title = "AI 生成动作", onDismiss = onDismiss) {
        if (draft == null) {
            NeuTextField(
                value = text,
                onValueChange = { text = it },
                label = "想要什么动作",
                placeholder = "做一个开心地挥手动作",
                singleLine = false,
                maxLines = 3,
            )
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AI_EXAMPLES, key = { it }) { example ->
                    PickChip(text = example, selected = false, onClick = { text = example })
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryPill(
                text = if (busy) "正在生成…" else "生成",
                filled = true,
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        val r = generate(text)
                        busy = false
                        r.onSuccess { draft = it }.onFailure { error = it.message ?: "没生成出来，换句话再试" }
                    }
                },
            )
        } else {
            val d = draft!!
            Text(d.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                "会动：${d.tracks.map { ActionPart.of(it.channel) }.distinct().joinToString("+") { it.label }} · " +
                    "%.1f 秒".format(d.safeDurationMs / 1000f),
                fontSize = 12.5.sp,
                color = TextSecondary,
            )
            if (d.note.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("你说的：${d.note}", fontSize = 12.sp, color = MutedText, maxLines = 2)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    PrimaryPill(text = "预览", filled = false, enabled = true, onClick = { onPreview(d) })
                }
                Box(Modifier.weight(1f)) {
                    PrimaryPill(
                        text = "保存",
                        filled = true,
                        enabled = true,
                        onClick = { onSave(d, { draft = null }) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "重新描述",
                fontSize = 12.5.sp,
                color = MutedText,
                modifier = Modifier.clickable { draft = null },
            )
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, fontSize = 12.5.sp, color = NeuDanger, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(10.dp))
    }
}
