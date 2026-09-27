package com.daoshi.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoshi.app.MentorApp
import com.daoshi.app.data.ApiConfig
import com.daoshi.app.llm.OpenAiClient
import com.daoshi.app.ui.common.NeuButton
import com.daoshi.app.ui.common.NeuButtonKind
import com.daoshi.app.ui.common.NeuSheetScaffold
import com.daoshi.app.ui.common.NeuTextField
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.NeuDanger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * API 配置表单（新拟态）：接口地址 / 密钥 / 模型 三件套 + 测试连接 + 保存。
 * 保存至 DataStore（SettingsRepository）。
 */
@Composable
fun ApiConfigSheet(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as MentorApp
    val scope = rememberCoroutineScope()

    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val cfg = app.settingsRepository.apiConfig.first()
        baseUrl = cfg.baseUrl
        apiKey = cfg.apiKey
        model = cfg.model
    }

    NeuSheetScaffold(title = "API 配置", onDismiss = onDismiss) {
        Spacer(Modifier.height(16.dp))
        NeuTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = "接口地址",
            placeholder = "https://api.deepseek.com/v1",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = "API Key",
            placeholder = "sk-…",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = model,
            onValueChange = { model = it },
            label = "模型",
            placeholder = "deepseek-chat",
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        testResult?.let {
            Text(
                it,
                fontSize = 13.sp,
                color = if (it.startsWith("连接成功")) NeuAccent else NeuDanger,
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            NeuButton(
                text = if (testing) "测试中…" else "测试连接",
                onClick = {
                    if (baseUrl.isBlank() || apiKey.isBlank()) {
                        testResult = "先填接口地址和 Key"
                        return@NeuButton
                    }
                    scope.launch {
                        testing = true
                        val err = OpenAiClient(baseUrl, apiKey, model).testConnection()
                        testResult = err?.let { "连接失败 · $it" } ?: "连接成功"
                        testing = false
                    }
                },
                enabled = !testing,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            NeuButton(
                text = "保存",
                onClick = {
                    scope.launch {
                        app.settingsRepository.saveApiConfig(
                            ApiConfig(baseUrl.trim(), apiKey.trim(), model.trim())
                        )
                        onDismiss()
                    }
                },
                kind = NeuButtonKind.Primary,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "配置后聊天将真实调用该接口（流式）。Key 仅存本机。",
            fontSize = 12.sp,
            color = MutedText,
        )
        Spacer(Modifier.height(6.dp))
    }
}
