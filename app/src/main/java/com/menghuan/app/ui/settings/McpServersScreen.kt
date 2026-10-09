package com.menghuan.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.DEFAULT_PROFILE_ID
import com.menghuan.app.mcp.McpClient
import com.menghuan.app.mcp.McpManager
import com.menghuan.app.mcp.McpServerCodec
import com.menghuan.app.mcp.McpServerConfig
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * MCP 服务器管理（P3-3，白名单制：只能手动添加，没有任何"发现机制"）。
 *
 * 页面要素（计划定稿）：
 * - 明示「仅支持远程服务器（HTTP），不支持本地 stdio」；
 * - 列表 = 名称 + 地址 + 启用开关（停用 = 它的工具本轮起不进聚合，配置一个标点不动）；
 * - 添加/编辑走底部弹层：名称 + Streamable HTTP URL + 可选 token（Keystore 加密落盘）；
 * - 「工具调用支持」行 = 当前对话模型的 tools 探测结论（聊天回环用的同一份缓存），
 *   点一下重新探测（探测失败被缓存成"不支持"时这是唯一的翻案入口）。
 */
@Composable
fun McpServersScreen(onBack: () -> Unit) {
    val app = settingsContainer()
    val scope = rememberCoroutineScope()

    val servers by app.settingsRepository.mcpServersFlow.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var adding by remember { mutableStateOf(false) }

    // 当前生效对话模型（探测结论按 baseUrl|model 记，跟着它走）
    val profiles by app.settingsRepository.apiProfiles.collectAsState(initial = emptyList())
    val activeProfileId by app.settingsRepository.activeProfileId.collectAsState(initial = DEFAULT_PROFILE_ID)
    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
    var probeState by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    LaunchedEffect(activeProfile?.baseUrl, activeProfile?.model) {
        probeState = if (activeProfile == null) {
            null
        } else {
            app.settingsRepository.toolsProbeFor(
                McpManager.toolsProbeKey(activeProfile.baseUrl, activeProfile.model)
            )
        }
    }

    SettingsPage(title = "MCP 服务器", onBack = onBack) {
        Spacer(Modifier.height(14.dp))
        Text(
            "仅支持远程服务器（HTTP），不支持本地 stdio。AI 聊天时可调用这里列出的工具；" +
                "只读工具直接执行，其余工具每次执行前都会先问你。",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = MutedText,
        )
        Spacer(Modifier.height(14.dp))

        servers.forEach { server ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(server.name, fontSize = 15.sp, color = TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(server.url, fontSize = 12.sp, color = MutedText, maxLines = 1)
                }
                NeuTextButton(text = "编辑", onClick = { editing = server })
                NeuSwitch(
                    checked = server.enabled,
                    onToggle = { on ->
                        scope.launch {
                            app.settingsRepository.saveMcpServer(server.copy(enabled = on))
                            app.mcpManager.invalidate()
                        }
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        NeuButton(
            text = "添加服务器",
            modifier = Modifier.fillMaxWidth(),
            onClick = { adding = true },
        )

        // 端点 tools 探测结论（与聊天回环同一份缓存；探测失败被记住时这里是翻案入口）
        Spacer(Modifier.height(18.dp))
        Text("工具调用支持", fontSize = 13.sp, color = TextPrimary)
        Spacer(Modifier.height(3.dp))
        Text(
            when {
                activeProfile == null || !activeProfile.isComplete -> "先在「对话模型」里配好接口"
                probing -> "正在探测…"
                probeState == "ok" -> "当前对话模型支持（${activeProfile.name}）"
                probeState == null -> "未探测 · 首轮聊天时自动探测"
                else -> "当前对话模型不支持：$probeState"
            },
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = if (probeState == "ok") NeuAccent else MutedText,
        )
        if (activeProfile != null && activeProfile.isComplete) {
            Spacer(Modifier.height(6.dp))
            NeuTextButton(
                text = if (probing) "探测中…" else "重新探测",
                onClick = {
                    if (probing) return@NeuTextButton
                    probing = true
                    val profile = activeProfile
                    scope.launch {
                        val key = McpManager.toolsProbeKey(profile.baseUrl, profile.model)
                        val err = com.menghuan.app.llm.OpenAiClient(
                            profile.baseUrl, profile.apiKey, profile.model,
                        ).probeToolSupport()
                        app.settingsRepository.setToolsProbe(key, err ?: "ok")
                        probeState = err ?: "ok"
                        probing = false
                    }
                },
            )
        }
        Spacer(Modifier.height(10.dp))
    }

    if (adding || editing != null) {
        McpServerEditSheet(
            initial = editing,
            existingIds = servers.map { it.id },
            onDismiss = { adding = false; editing = null },
        )
    }
}

/** 添加/编辑服务器弹层：名称 + 地址 + token + 测试连接 + 保存/删除 */
@Composable
private fun McpServerEditSheet(
    initial: McpServerConfig?,
    existingIds: List<String>,
    onDismiss: () -> Unit,
) {
    val app = settingsContainer()
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initial?.name ?: "") }
    var url by remember { mutableStateOf(initial?.url ?: "") }
    // 编辑时空白 = 不动已存 token（读侧也绝不把已存 token 显示出来）
    var token by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    NeuSheetScaffold(
        title = if (initial == null) "添加 MCP 服务器" else "编辑服务器",
        onDismiss = onDismiss,
    ) {
        NeuTextField(
            value = name,
            onValueChange = { name = it },
            label = "名称",
            placeholder = "例：我的工具箱",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        NeuTextField(
            value = url,
            onValueChange = { url = it; testResult = null },
            label = "服务器地址（Streamable HTTP）",
            placeholder = "例：https://example.com/mcp",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        NeuTextField(
            value = token,
            onValueChange = { token = it; testResult = null },
            label = if (initial == null) "Token（服务器不校验可留空）" else "Token（留空 = 保持不变）",
            placeholder = "服务器给的访问令牌",
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))
        NeuButton(
            text = if (testing) "测试中…" else "测试连接",
            modifier = Modifier.fillMaxWidth(),
            enabled = !testing && url.isNotBlank(),
            onClick = {
                testing = true
                testResult = null
                val testUrl = url.trim()
                scope.launch {
                    val started = System.currentTimeMillis()
                    // 编辑时用户没重填 token → 用已存的那份去测
                    val effectiveToken = token.trim().ifBlank {
                        initial?.let { app.settingsRepository.mcpTokenFor(it.id) }.orEmpty()
                    }.ifBlank { null }
                    val result = runCatching {
                        val client = McpClient(testUrl, effectiveToken)
                        val info = client.initialize()
                        val toolCount = runCatching { client.listTools().size }.getOrNull()
                        client.close()
                        info to toolCount
                    }
                    val cost = System.currentTimeMillis() - started
                    result
                        .onSuccess { (info, toolCount) ->
                            testOk = true
                            testResult = buildString {
                                append("连接成功 · ${cost}ms")
                                if (info.name.isNotBlank()) append(" · ${info.name}")
                                if (toolCount != null) append(" · 发现 $toolCount 个工具")
                            }
                        }
                        .onFailure {
                            testOk = false
                            testResult = "连接失败 · ${cost}ms · ${it.message ?: it.javaClass.simpleName}"
                        }
                    testing = false
                }
            },
        )
        testResult?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = if (testOk) NeuAccent else NeuDanger,
            )
        }

        Spacer(Modifier.height(16.dp))
        NeuButton(
            text = if (saving) "保存中…" else "保存",
            kind = NeuButtonKind.Primary,
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving && url.isNotBlank(),
            onClick = {
                saving = true
                scope.launch {
                    runCatching {
                        val server = McpServerConfig(
                            id = initial?.id ?: McpServerCodec.newId(existingIds),
                            name = name.trim().ifBlank { url.trim() },
                            url = url.trim(),
                            enabled = initial?.enabled ?: true,
                        )
                        // token：新增时空白=不存；编辑时空白=保持不变（token=null 不动它）
                        val tokenToSave = when {
                            token.isNotBlank() -> token.trim()
                            initial == null -> ""
                            else -> null
                        }
                        app.settingsRepository.saveMcpServer(server, tokenToSave)
                        app.mcpManager.invalidate()
                    }
                    onDismiss()
                }
            },
        )
        if (initial != null) {
            Spacer(Modifier.height(8.dp))
            NeuTextButton(
                text = "删除这台服务器",
                onClick = {
                    scope.launch {
                        app.settingsRepository.removeMcpServer(initial.id)
                        app.mcpManager.invalidate()
                        onDismiss()
                    }
                },
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}
