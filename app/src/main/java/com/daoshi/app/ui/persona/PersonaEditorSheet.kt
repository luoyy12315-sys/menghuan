package com.daoshi.app.ui.persona

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daoshi.app.data.model.Persona
import com.daoshi.app.data.model.ToneStyle
import com.daoshi.app.ui.common.NeuAvatar
import com.daoshi.app.ui.common.NeuButton
import com.daoshi.app.ui.common.NeuButtonKind
import com.daoshi.app.ui.common.NeuSheetScaffold
import com.daoshi.app.ui.common.NeuTextField

/**
 * 人设编辑弹层（新拟态）：新增（persona = null）与编辑共用。
 * 名字必填；预置人设可编辑、不可删除（删除入口仅自建人设可见）。
 */
@Composable
fun PersonaEditorSheet(
    persona: Persona?,
    onSave: (Persona) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(persona?.name ?: "") }
    var title by remember { mutableStateOf(persona?.title ?: "") }
    var emoji by remember { mutableStateOf(persona?.avatarEmoji ?: "🙂") }
    var prompt by remember { mutableStateOf(persona?.systemPrompt ?: "") }

    fun buildPersona(): Persona = Persona(
        id = persona?.id ?: 0,
        name = name.trim(),
        title = title.trim().ifBlank { name.trim() },
        avatarEmoji = emoji.trim().ifBlank { "🙂" },
        systemPrompt = prompt.trim(),
        isBuiltIn = persona?.isBuiltIn ?: false,
        tone = persona?.tone ?: ToneStyle.GENTLE.name,
        pinned = persona?.pinned ?: false, // 编辑不改置顶状态
        createdAt = persona?.createdAt ?: System.currentTimeMillis(),
    )

    NeuSheetScaffold(
        title = if (persona == null) "新增人设" else "编辑人设",
        onDismiss = onDismiss,
    ) {
        Spacer(Modifier.height(16.dp))

        // 头像预览 + emoji 输入
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeuAvatar(emoji.trim().ifBlank { "🙂" }, size = 52.dp, accentRing = true)
            Spacer(Modifier.width(14.dp))
            NeuTextField(
                value = emoji,
                onValueChange = { emoji = it.take(4) },
                label = "头像 emoji",
                placeholder = "🙂",
                modifier = Modifier.width(130.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = name,
            onValueChange = { name = it },
            label = "名字",
            placeholder = "例：阿布",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = title,
            onValueChange = { title = it },
            label = "身份",
            placeholder = "例：人生导师 / 健身教练",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = "设定词（system prompt）",
            placeholder = "描述 TA 的性格、职责和说话方式",
            singleLine = false,
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            onDelete?.let { onDeleteAction ->
                NeuButton(
                    text = "删除",
                    onClick = onDeleteAction,
                    kind = NeuButtonKind.Danger,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
            }
            NeuButton(
                text = "取消",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            NeuButton(
                text = "保存",
                onClick = { onSave(buildPersona()) },
                kind = NeuButtonKind.Primary,
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
        if (persona?.isBuiltIn == true) {
            Spacer(Modifier.height(10.dp))
            com.daoshi.app.ui.common.NeuMuted("预置人设可自由编辑，但不可删除。")
        }
        Spacer(Modifier.height(6.dp))
    }
}
