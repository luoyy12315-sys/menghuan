package com.menghuan.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.menghuan.app.data.ApiProfile
import com.menghuan.app.data.ImageApiConfig
import com.menghuan.app.llm.OpenAiClient
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextSecondary
import com.menghuan.app.llm.ImageProbe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 粗校验：必须是 http(s) URL（Android 9+ 默认禁明文，本 App 通过 network_security_config 全局放开） */
private fun isValidBaseUrl(url: String): Boolean =
    url.trim().startsWith("http://") || url.trim().startsWith("https://")

/** 展示用：去掉协议头，"api.deepseek.com/v1" 比整串 URL 短一截、扫一眼就懂 */
private fun displayUrl(url: String): String =
    url.trim().removePrefix("https://").removePrefix("http://")

/** 常用服务商预设（OpenAI 兼容端点 + 推荐模型；模型名随各家更新，可手改） */
private data class ApiPreset(val name: String, val url: String, val model: String)

private val apiPresets = listOf(
    ApiPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    ApiPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5"),
    ApiPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    ApiPreset("Moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
    ApiPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
)

/** 生图接口预设（各家 `/images/generations` 端点） */
private val imagePresets = listOf(
    ApiPreset("OpenAI", "https://api.openai.com/v1", "gpt-image-1"),
    ApiPreset("豆包", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seedream-3-0-t2i-250415"),
    ApiPreset("通义万相", "https://dashscope.aliyuncs.com/compatible-mode/v1", "wanx-v1"),
    ApiPreset("智谱 CogView", "https://open.bigmodel.cn/api/paas/v4", "cogview-3-flash"),
    ApiPreset("SiliconFlow", "https://api.siliconflow.cn/v1", "Kwai-Kolors/Kolors"),
)

/** 编辑态：新增（profileId 为 null）或改已有 */
private data class EditorState(
    val profileId: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val isNew: Boolean get() = profileId == null
}

/**
 * API 配置（v0.36.1 UI 统一）：
 *
 * **对话接口 & 生图接口现在共用同一套交互模式**：
 * - 未展开时只显示摘要（对话接口是列表 + 每条一行；生图接口是单行摘要或"＋ 配置"按钮）
 * - 点「＋ 添加接口」/「编辑」/「＋ 配置生图接口」才展开详细参数表单
 * - 两组预设 chips 尾部都追加「自定义」——选中态 = 当前 baseUrl 不匹配任何预设；
 *   点击时清空 baseUrl 与 model 让用户从头填（若已经是自定义态则不动，避免误清）
 *
 * v0.36.1 删除：section header 里的「（可配置多条）」「（AI 可生成图片）」、生图区底部
 * 「AI 觉得需要配图时会在回复里发起生图请求，图片只存本机」的小字说明——信息噪音，
 * 用户既然进了这个面板就已经知道要干什么。
 */
@Composable
fun ApiConfigSheet(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as MenghuanApp
    val scope = rememberCoroutineScope()

    val profiles by app.settingsRepository.apiProfiles
        .collectAsState(initial = emptyList())
    val activeId by app.settingsRepository.activeProfileId
        .collectAsState(initial = com.menghuan.app.data.DEFAULT_PROFILE_ID)

    var editor by remember { mutableStateOf<EditorState?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    // 列表层反馈（切换 / 删除 / 保存 / 生图保存清除）：v0.37.1 起不再塞进 testResult——
    // 那个只在编辑表单里渲染，表单没开时用户点切换等于没有任何可见反馈
    var listMsg by remember { mutableStateOf<String?>(null) }

    // 生图接口（单条，不进多接口列表）；v0.36.1 起也走"点开才展开表单"模式
    var imgBaseUrl by remember { mutableStateOf("") }
    var imgApiKey by remember { mutableStateOf("") }
    var imgModel by remember { mutableStateOf("") }
    var imgTest by remember { mutableStateOf<String?>(null) }
    var imgTesting by remember { mutableStateOf(false) }
    var imgEditorOpen by remember { mutableStateOf(false) }
    var imgLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val cfg = app.settingsRepository.imageApiConfig.first()
        imgBaseUrl = cfg.baseUrl
        imgApiKey = cfg.apiKey
        imgModel = cfg.model
        imgLoaded = true
    }

    // 生图接口是否已配置：任一字段非空即视为"已配置"（用户可能只填了部分）
    val imgConfigured = imgBaseUrl.isNotBlank() || imgApiKey.isNotBlank() || imgModel.isNotBlank()

    NeuSheetScaffold(title = "API 配置", onDismiss = onDismiss) {
        Spacer(Modifier.height(14.dp))

        // ===== 对话接口 =====
        Text(
            "对话接口",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.height(8.dp))
        profiles.forEach { profile ->
            NeuSheetRow(
                icon = MIcons.Code,
                title = profile.name,
                subtitle = "${profile.model} · ${displayUrl(profile.baseUrl)}",
                selected = profile.id == activeId,
                onClick = {
                    scope.launch {
                        app.settingsRepository.setActiveProfile(profile.id)
                        listMsg = "已切换到「${profile.name}」"
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                NeuTextButton("编辑", onClick = {
                    listMsg = null
                    editor = EditorState(profile.id, profile.name, profile.baseUrl, profile.apiKey, profile.model)
                })
                Spacer(Modifier.width(18.dp))
                if (profiles.size > 1) {
                    NeuTextButton("删除", onClick = {
                        scope.launch {
                            app.settingsRepository.deleteApiProfile(profile.id)
                            listMsg = "已删除「${profile.name}」"
                        }
                    })
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        if (profiles.isEmpty()) {
            Text(
                "还没有配置接口",
                fontSize = 13.sp,
                color = MutedText,
                modifier = Modifier.height(30.dp),
            )
        }
        NeuTextButton("＋ 添加接口", onClick = {
            listMsg = null
            editor = EditorState()
        })
        listMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 13.sp, color = NeuAccent)
        }
        Spacer(Modifier.height(12.dp))

        // ===== 对话接口编辑表单（点添加/编辑才展开） =====
        editor?.let { form ->
            Spacer(Modifier.height(8.dp))
            Text(
                if (form.isNew) "新接口" else "编辑接口",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
                letterSpacing = 0.6.sp,
            )
            Spacer(Modifier.height(8.dp))
            NeuTextField(
                value = form.name,
                onValueChange = { editor = form.copy(name = it) },
                label = "名称",
                placeholder = "例：DeepSeek",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            PresetChipsRow(
                presets = apiPresets,
                currentBaseUrl = form.baseUrl,
                onPickPreset = { preset ->
                    editor = form.copy(baseUrl = preset.url).let {
                        if (form.model.isBlank()) it.copy(model = preset.model) else it
                    }
                },
                onPickCustom = {
                    // 已是自定义态就不动，避免误清用户输入
                    if (apiPresets.any { it.url == form.baseUrl }) {
                        editor = form.copy(baseUrl = "", model = "")
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            NeuTextField(
                value = form.baseUrl,
                onValueChange = { editor = form.copy(baseUrl = it) },
                label = "接口地址",
                placeholder = "https://api.deepseek.com/v1",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            NeuTextField(
                value = form.apiKey,
                onValueChange = { editor = form.copy(apiKey = it) },
                label = "API Key",
                placeholder = "sk-…",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            NeuTextField(
                value = form.model,
                onValueChange = { editor = form.copy(model = it) },
                label = "模型",
                placeholder = "deepseek-chat",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            testResult?.let {
                Text(it, fontSize = 13.sp, color = if (it.startsWith("连接成功")) NeuAccent else NeuDanger)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                NeuButton(
                    text = if (testing) "测试中…" else "测试连接",
                    onClick = {
                        if (form.baseUrl.isBlank() || form.apiKey.isBlank()) {
                            testResult = "先填接口地址和 Key"
                            return@NeuButton
                        }
                        if (!isValidBaseUrl(form.baseUrl)) {
                            testResult = "接口地址需以 http(s):// 开头"
                            return@NeuButton
                        }
                        scope.launch {
                            testing = true
                            val err = OpenAiClient(form.baseUrl, form.apiKey, form.model).testConnection()
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
                        if (!isValidBaseUrl(form.baseUrl)) {
                            testResult = "接口地址需以 http(s):// 开头"
                            return@NeuButton
                        }
                        scope.launch {
                            val id = app.settingsRepository.saveApiProfile(
                                ApiProfile(
                                    id = form.profileId.orEmpty(),
                                    name = form.name.trim().ifBlank { "未命名接口" },
                                    baseUrl = form.baseUrl.trim(),
                                    apiKey = form.apiKey.trim(),
                                    model = form.model.trim(),
                                )
                            )
                            editor = null
                            listMsg = "已保存「${form.name.trim().ifBlank { "未命名接口" }}」"
                            // 第一条接口自动生效
                            if (profiles.isEmpty()) app.settingsRepository.setActiveProfile(id)
                        }
                    },
                    kind = NeuButtonKind.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ===== 生图接口（v0.36.1：与对话接口同一套点开-展开交互） =====
        Spacer(Modifier.height(22.dp))
        Text(
            "生图接口",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.height(8.dp))
        if (imgLoaded && imgConfigured && !imgEditorOpen) {
            // 摘要行：标题直接给模型名（板块头已经写了"生图接口"，行内不再重复）
            NeuSheetRow(
                icon = MIcons.ImageIcon,
                title = imgModel.ifBlank { "模型未填" },
                subtitle = displayUrl(imgBaseUrl).ifBlank { "地址未填" },
                onClick = { imgEditorOpen = true },
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                NeuTextButton("编辑", onClick = { listMsg = null; imgEditorOpen = true })
                Spacer(Modifier.width(18.dp))
                NeuTextButton("清除", onClick = {
                    scope.launch {
                        app.settingsRepository.saveImageApiConfig(ImageApiConfig("", "", ""))
                        imgBaseUrl = ""; imgApiKey = ""; imgModel = ""
                        listMsg = "已清除生图接口"
                    }
                }, color = NeuDanger)
            }
        } else if (!imgEditorOpen) {
            NeuTextButton("＋ 配置生图接口", onClick = { listMsg = null; imgEditorOpen = true })
        }

        // 生图接口编辑表单
        if (imgEditorOpen) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (imgConfigured) "编辑生图接口" else "新生图接口",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextSecondary,
                letterSpacing = 0.6.sp,
            )
            Spacer(Modifier.height(8.dp))
            PresetChipsRow(
                presets = imagePresets,
                currentBaseUrl = imgBaseUrl,
                onPickPreset = { preset ->
                    imgBaseUrl = preset.url
                    if (imgModel.isBlank()) imgModel = preset.model
                },
                onPickCustom = {
                    if (imagePresets.any { it.url == imgBaseUrl }) {
                        imgBaseUrl = ""; imgModel = ""
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            NeuTextField(
                value = imgBaseUrl,
                onValueChange = { imgBaseUrl = it },
                label = "接口地址",
                placeholder = "https://api.openai.com/v1",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            NeuTextField(
                value = imgApiKey,
                onValueChange = { imgApiKey = it },
                label = "API Key",
                placeholder = "sk-…",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            NeuTextField(
                value = imgModel,
                onValueChange = { imgModel = it },
                label = "模型",
                placeholder = "gpt-image-1",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            imgTest?.let {
                Text(it, fontSize = 13.sp, color = if (it.startsWith("已保存") || it.startsWith("连接成功")) NeuAccent else NeuDanger)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                NeuButton(
                    text = if (imgTesting) "测试中…" else "测试连接",
                    onClick = {
                        if (!isValidBaseUrl(imgBaseUrl) || imgApiKey.isBlank()) {
                            imgTest = "先填接口地址和 Key"
                            return@NeuButton
                        }
                        if (imgModel.isBlank()) {
                            imgTest = "先填模型名（第三方生图接口都强制要求 model 字段）"
                            return@NeuButton
                        }
                        scope.launch {
                            imgTesting = true
                            val err = runCatching {
                                ImageProbe(imgBaseUrl, imgApiKey, imgModel).probe()
                            }.getOrElse { com.menghuan.app.llm.NetworkStack.describeNetworkFailure(it) }
                            imgTest = if (err == null) "连接成功" else "连接失败 · $err"
                            imgTesting = false
                        }
                    },
                    enabled = !imgTesting,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                NeuButton(
                    text = "保存",
                    onClick = {
                        scope.launch {
                            app.settingsRepository.saveImageApiConfig(
                                ImageApiConfig(imgBaseUrl.trim(), imgApiKey.trim(), imgModel.trim())
                            )
                            imgEditorOpen = false
                            listMsg = "已保存生图接口"
                        }
                    },
                    kind = NeuButtonKind.Primary,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            NeuTextButton("收起", onClick = { imgEditorOpen = false })
        }

        // 本月用量（v0.38.0）：服务商在响应里回了 usage 才计（对话/提炼/周报/Agent 都算），
        // 没拿到过数据就不显示这一行，免得用户以为没花 token
        val usage by app.settingsRepository.monthlyUsage.collectAsState(initial = "" to 0)
        if (usage.second > 0) {
            Spacer(Modifier.height(18.dp))
            Text(
                "本月 API 用量：${com.menghuan.app.data.UsageTracker.formatTokens(usage.second)} tokens",
                fontSize = 12.sp,
                color = MutedText,
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * 预设 chips 行：常用服务商 + 尾部「自定义」。
 *
 * 「自定义」的选中态 = 当前 baseUrl 非空且不匹配任何预设；点击时若当前是预设态则清空
 * baseUrl/model 让用户从头填，已是自定义态则不动（避免误清用户辛苦填的自定义 URL）。
 * 对话接口和生图接口共用此组件，保证两处 UI 一致。
 */
@Composable
private fun PresetChipsRow(
    presets: List<ApiPreset>,
    currentBaseUrl: String,
    onPickPreset: (ApiPreset) -> Unit,
    onPickCustom: () -> Unit,
) {
    Text(
        "常用服务商",
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = TextSecondary,
        letterSpacing = 0.6.sp,
    )
    Spacer(Modifier.height(7.dp))
    val isCustom = currentBaseUrl.isNotBlank() && presets.none { it.url == currentBaseUrl }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { preset ->
            NeuChip(
                text = preset.name,
                selected = currentBaseUrl == preset.url,
                onClick = { onPickPreset(preset) },
            )
        }
        NeuChip(text = "自定义", selected = isCustom, onClick = onPickCustom)
    }
}
