package com.menghuan.app.ui.persona

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.PersonaRewriter
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「AI 优化设定词」子面板（v0.36.0）：从人设编辑页弹出。
 *
 * 流程：
 * 1. 用户在输入框里粘贴/敲一段自由描述（"我想要一个温柔的姐姐，会关心我的作息"）
 * 2. 点「生成」→ 调 [PersonaRewriter.rewrite]（走当前生效的对话接口）
 * 3. 结果三件套（名字/身份/设定词）预览在下方
 * 4. 点「使用这个」→ [onApply] 回调，父表单一键填入 → 关闭本面板
 *
 * **失败兜底**：API 未配置、网络异常、LLM 输出无法解析都走 [error] 分支显示一句可读原因，
 * 不抛异常，用户可修改描述后重试。
 */
@Composable
fun AiPersonaOptimizeSheet(
    initialInput: String = "",
    onApply: (PersonaRewriter.Result) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as MenghuanApp
    val scope = rememberCoroutineScope()

    var userInput by remember { mutableStateOf(initialInput) }
    var isLoading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PersonaRewriter.Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun generate() {
        if (userInput.isBlank() || isLoading) return
        scope.launch {
            isLoading = true
            error = null
            val config = runCatching { app.settingsRepository.apiConfig.first() }.getOrNull()
            if (config == null || !config.isComplete) {
                error = "先在「设置 · API 配置」里配好对话接口再来"
                isLoading = false
                return@launch
            }
            val r = PersonaRewriter.rewrite(userInput, config)
            if (r == null) {
                error = "AI 没能返回可用结果，试试把描述写得更具体一点，或换个接口重试"
            } else {
                result = r
            }
            isLoading = false
        }
    }

    NeuSheetScaffold(title = "AI 优化设定词", onDismiss = onDismiss) {
        Spacer(Modifier.height(14.dp))
        Text(
            "描述你想要的人设，AI 帮你改写成结构化的三件套（名字 / 身份 / 设定词）。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MutedText,
        )
        Spacer(Modifier.height(12.dp))
        NeuTextField(
            value = userInput,
            onValueChange = { userInput = it },
            label = "人设描述",
            placeholder = "例：温柔的姐姐，会关心我的作息，偶尔吐槽我熬夜",
            singleLine = false,
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            NeuTextButton("清空", onClick = {
                userInput = ""
                result = null
                error = null
            })
            Spacer(Modifier.weight(1f))
            NeuButton(
                text = if (isLoading) "生成中…" else if (result == null) "生成" else "重新生成",
                onClick = { generate() },
                kind = NeuButtonKind.Primary,
                enabled = !isLoading && userInput.isNotBlank(),
                modifier = Modifier.width(140.dp),
            )
        }

        error?.let { msg ->
            Spacer(Modifier.height(14.dp))
            Text(msg, fontSize = 12.sp, color = NeuDanger, lineHeight = 18.sp)
        }

        result?.let { r ->
            Spacer(Modifier.height(18.dp))
            Text(
                "生成结果预览",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            ResultField(label = "名字", value = r.name)
            Spacer(Modifier.height(8.dp))
            ResultField(label = "身份", value = r.title)
            Spacer(Modifier.height(8.dp))
            ResultField(label = "设定词", value = r.systemPrompt, multiline = true)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                NeuButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                NeuButton(
                    text = "使用这个",
                    onClick = { onApply(r) },
                    kind = NeuButtonKind.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
    }
}

/** 结果字段展示：凹陷面 + 标签 + 值（多行时保留换行） */
@Composable
private fun ResultField(label: String, value: String, multiline: Boolean = false) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            fontSize = 10.sp,
            color = TextSecondary,
            letterSpacing = 0.4.sp,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .neuSurface(10.dp, inset = true)
                .padding(12.dp),
        ) {
            Text(
                value,
                fontSize = 12.sp,
                lineHeight = if (multiline) 19.sp else 17.sp,
                color = TextPrimary,
            )
        }
    }
}
