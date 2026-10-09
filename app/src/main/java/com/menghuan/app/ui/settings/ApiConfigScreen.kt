package com.menghuan.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.ApiProfile
import com.menghuan.app.data.DEFAULT_IMAGE_PROFILE_ID
import com.menghuan.app.data.DEFAULT_PROFILE_ID
import com.menghuan.app.llm.ImageProbe
import com.menghuan.app.llm.NetworkStack
import com.menghuan.app.llm.OpenAiClient
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/** 粗校验：必须是 http(s) URL（Android 9+ 默认禁明文，本 App 通过 network_security_config 全局放开） */
private fun isValidBaseUrl(url: String): Boolean =
    url.trim().startsWith("http://") || url.trim().startsWith("https://")

/** 展示用：去掉协议头，"api.deepseek.com/v1" 比整串 URL 短一截、扫一眼就懂 */
private fun displayUrl(url: String): String =
    url.trim().removePrefix("https://").removePrefix("http://")

/** 常用服务商预设（OpenAI 兼容端点 + 推荐模型；模型名随各家更新，可手改） */
private data class ApiPreset(val name: String, val url: String, val model: String)

/**
 * ⚠️ 预设里的 Model ID 必须与**官方当前**的写法逐字一致（全小写 + 连字符 + 日期后缀）。
 * v0.56.17 补「豆包」并刷新生图 ID：用户真机两次手打都打错——`Doubao-Seedream-5.0-lite`
 * （方舟实际是 `doubao-seedream-5-0-lite-260128`，且官方标注 lite 别名即将下线）
 * 和 `Doubao-Seed-2.1-lite`（实际 `doubao-seed-2-1-lite-260915`）都 404。
 * 出处：方舟模型列表 https://docs.volcengine.com/docs/82379/1536429
 */
private val apiPresets = listOf(
    ApiPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
    ApiPreset("豆包", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-2-1-lite-260915"),
    ApiPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5"),
    ApiPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    ApiPreset("Moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
    ApiPreset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
)

/** 生图服务商预设（各家 `/images/generations` 端点；豆包见上方注释，5.0 lite 别名即将下线所以不用它） */
private val imagePresets = listOf(
    ApiPreset("OpenAI", "https://api.openai.com/v1", "gpt-image-1"),
    ApiPreset("豆包", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seedream-5-0-260128"),
    ApiPreset("通义万相", "https://dashscope.aliyuncs.com/compatible-mode/v1", "wanx-v1"),
    ApiPreset("智谱 CogView", "https://open.bigmodel.cn/api/paas/v4", "cogview-3-flash"),
    ApiPreset("SiliconFlow", "https://api.siliconflow.cn/v1", "Kwai-Kolors/Kolors"),
)

/** 编辑态：新增（profileId 为 null）或改已有。对话模型与生图模型共用同一个形状。 */
private data class EditorState(
    val profileId: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val isNew: Boolean get() = profileId == null
}

private fun ApiProfile.toEditorState() = EditorState(
    profileId = id,
    name = name,
    baseUrl = baseUrl,
    apiKey = apiKey,
    model = model,
)

/** 这条页只管一类模型：对话 or 生图（v0.54.1 由同一个「API 配置」页拆成两页） */
enum class ApiModelKind { CHAT, IMAGE }

/** 设置 · 模型配置 · 对话模型 */
@Composable
fun ChatModelScreen(onBack: () -> Unit) = ApiConfigScreen(onBack, ApiModelKind.CHAT)

/** 设置 · 模型配置 · 生图模型 */
@Composable
fun ImageModelScreen(onBack: () -> Unit) = ApiConfigScreen(onBack, ApiModelKind.IMAGE)

/**
 * API 配置（v0.53.4 按「模型」而非「接口」组织；v0.54.1 按 [kind] 拆成对话 / 生图两页）：
 *
 * - 列的是**模型列表**，每行 = 一个模型条目；
 * - **点行 = 切换当前生效（默认）**；**点右侧箭头 = 打开这条模型的详情**；
 * - 详情弹窗里改 / 测试 / 保存 / **设为默认** / **删除**；
 * - 当前默认那条的副标题前加「默认 · 」，选中态**不再画 ✓**（用户点名：已经有标识了，勾多余）；
 * - 生图与对话模型行为完全一致：可配多条、可设默认，默认那条调不通时
 *   生图会自动换列表里其他模型继续试（见 ChatViewModel.imageClients）。
 *
 * 预设 chips 尾部都追加「自定义」——选中态 = 当前 baseUrl 不匹配任何预设；
 * 点击时清空 baseUrl 与 model 让用户从头填（若已经是自定义态则不动，避免误清）。
 */
@Composable
fun ApiConfigScreen(onBack: () -> Unit, kind: ApiModelKind) {
    val app = LocalContext.current.applicationContext as MenghuanApp
    val scope = rememberCoroutineScope()

    val profiles by app.settingsRepository.apiProfiles
        .collectAsState(initial = emptyList())
    val activeId by app.settingsRepository.activeProfileId
        .collectAsState(initial = DEFAULT_PROFILE_ID)
    val imageProfiles by app.settingsRepository.imageProfiles
        .collectAsState(initial = emptyList())
    val imageActiveId by app.settingsRepository.imageActiveProfileId
        .collectAsState(initial = DEFAULT_IMAGE_PROFILE_ID)

    var editor by remember { mutableStateOf<EditorState?>(null) }        // 对话模型详情
    var imageEditor by remember { mutableStateOf<EditorState?>(null) }   // 生图模型详情
    // 列表层反馈（保存 / 删除 / 设为默认）。弹窗里的"测试连接"结果只活在弹窗里，
    // 关掉就没了，所以列表级的话必须单独一个槽（v0.37.1 的教训不变）
    var listMsg by remember { mutableStateOf<String?>(null) }

    // 本月「输入 token / 其中缓存命中」（v0.69.0）：把"前缀稳不稳"变成看得见的数。
    // 服务商没回 usage 时两个数都是 0 —— **这时一行都不显示**，不画个 0% 骗用户去改本来就对的前缀。
    val cacheUsage by app.settingsRepository.monthlyCacheUsage
        .collectAsState(initial = Triple("", 0, 0))

    SettingsPage(
        title = if (kind == ApiModelKind.CHAT) "对话模型" else "生图模型",
        onBack = onBack,
    ) {
        Spacer(Modifier.height(14.dp))

        // 缓存命中率一行（v0.69.0）：有读数才占位
        if (com.menghuan.app.data.UsageTracker.cacheHitRate(cacheUsage.second, cacheUsage.third) != null) {
            Text(
                com.menghuan.app.data.UsageTracker.cacheHitLabel(cacheUsage.second, cacheUsage.third),
                fontSize = 12.sp,
                color = MutedText,
            )
            Spacer(Modifier.height(8.dp))
        }

        // 本月用量按模型查表（v0.56.8 用户点名：**直接显示在模型卡片上、模型名的右边**，
        // 文字是「共消耗 X tokens」，字体沿用行内原来的小字）。
        // 服务商在响应里回了 usage 才计（对话/提炼/周报/Agent/识图/主动关心/通话都算，v0.82.0 补齐盲区）。
        val usageByModel by app.settingsRepository.monthlyUsageByModel
            .collectAsState(initial = "" to emptyList())
        val usageOf = usageByModel.second.associateBy { it.model }

        if (kind == ApiModelKind.CHAT) {
            profiles.forEach { profile ->
                val usageRow = usageOf[profile.model]
                ModelRow(
                    icon = MIcons.Code,
                    profile = profile,
                    isDefault = profile.id == activeId,
                    usageTokens = usageRow?.tokens ?: 0,
                    usageCacheRate = usageRow?.cacheHitRate(),
                    onSwitch = {
                        scope.launch {
                            app.settingsRepository.setActiveProfile(profile.id)
                            listMsg = "已切换到「${profile.name}」"
                        }
                    },
                    onOpenDetail = { listMsg = null; editor = profile.toEditorState() },
                )
                Spacer(Modifier.height(8.dp))
            }
            if (profiles.isEmpty()) {
                Text(
                    "还没有添加模型",
                    fontSize = 13.sp,
                    color = MutedText,
                    modifier = Modifier.height(30.dp),
                )
            }
            NeuTextButton("＋ 添加模型", onClick = {
                listMsg = null
                editor = EditorState()
            })
        } else {
            imageProfiles.forEach { profile ->
                ModelRow(
                    icon = MIcons.ImageIcon,
                    profile = profile,
                    isDefault = profile.id == imageActiveId,
                    usageTokens = usageOf[profile.model]?.tokens ?: 0,
                    onSwitch = {
                        scope.launch {
                            app.settingsRepository.setActiveImageProfile(profile.id)
                            listMsg = "已切换到「${profile.name}」"
                        }
                    },
                    onOpenDetail = { listMsg = null; imageEditor = profile.toEditorState() },
                )
                Spacer(Modifier.height(8.dp))
            }
            if (imageProfiles.isEmpty()) {
                Text(
                    "还没有添加生图模型",
                    fontSize = 13.sp,
                    color = MutedText,
                    modifier = Modifier.height(30.dp),
                )
            }
            NeuTextButton("＋ 添加生图模型", onClick = {
                listMsg = null
                imageEditor = EditorState()
            })
        }

        listMsg?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 13.sp, color = NeuAccent)
        }

        Spacer(Modifier.height(6.dp))
    }

    // ===== 对话模型详情（中间弹窗：改 / 测试 / 保存 / 设默认 / 删除都在这里） =====
    editor?.takeIf { kind == ApiModelKind.CHAT }?.let { initial ->
        ModelEditorDialog(
            initial = initial,
            presets = apiPresets,
            isDefault = initial.profileId != null && initial.profileId == activeId,
            defaultLabel = "设为默认模型",
            deleteLabel = "删除模型",
            onDismiss = { editor = null },
            onSetDefault = {
                initial.profileId?.let { pid ->
                    scope.launch {
                        app.settingsRepository.setActiveProfile(pid)
                        editor = null
                        listMsg = "已设为默认：${initial.name}"
                    }
                }
            },
            onDelete = {
                initial.profileId?.let { pid ->
                    scope.launch {
                        app.settingsRepository.deleteApiProfile(pid)
                        editor = null
                        listMsg = "已删除「${initial.name}」"
                    }
                }
            },
            onSave = { draft ->
                if (!isValidBaseUrl(draft.baseUrl)) {
                    "接口地址需以 http(s):// 开头"
                } else {
                    scope.launch {
                        val name = draft.name.trim().ifBlank { "未命名模型" }
                        val id = app.settingsRepository.saveApiProfile(
                            ApiProfile(
                                id = draft.profileId.orEmpty(),
                                name = name,
                                baseUrl = draft.baseUrl.trim(),
                                apiKey = draft.apiKey.trim(),
                                model = draft.model.trim(),
                            )
                        )
                        editor = null
                        listMsg = "已保存「$name」"
                        // 第一条模型自动生效
                        if (draft.isNew && profiles.isEmpty()) {
                            app.settingsRepository.setActiveProfile(id)
                        }
                    }
                    null
                }
            },
            onTest = { draft ->
                when {
                    draft.baseUrl.isBlank() || draft.apiKey.isBlank() -> "先填接口地址和 Key"
                    !isValidBaseUrl(draft.baseUrl) -> "接口地址需以 http(s):// 开头"
                    else -> {
                        val err = OpenAiClient(draft.baseUrl, draft.apiKey, draft.model).testConnection()
                        if (err == null) "连接成功" else "连接失败 · $err"
                    }
                }
            },
        )
    }

    // ===== 生图模型详情（与对话模型同一个弹窗形态） =====
    imageEditor?.takeIf { kind == ApiModelKind.IMAGE }?.let { initial ->
        ModelEditorDialog(
            initial = initial,
            presets = imagePresets,
            isDefault = initial.profileId != null && initial.profileId == imageActiveId,
            defaultLabel = "设为默认生图模型",
            deleteLabel = "删除生图模型",
            modelPlaceholder = "gpt-image-1",
            modelIcon = MIcons.ImageIcon,
            onDismiss = { imageEditor = null },
            onSetDefault = {
                initial.profileId?.let { pid ->
                    scope.launch {
                        app.settingsRepository.setActiveImageProfile(pid)
                        imageEditor = null
                        listMsg = "已设为默认生图模型：${initial.name}"
                    }
                }
            },
            onDelete = {
                initial.profileId?.let { pid ->
                    scope.launch {
                        app.settingsRepository.deleteImageProfile(pid)
                        imageEditor = null
                        listMsg = "已删除生图模型「${initial.name}」"
                    }
                }
            },
            onSave = { draft ->
                if (!isValidBaseUrl(draft.baseUrl)) {
                    "接口地址需以 http(s):// 开头"
                } else {
                    scope.launch {
                        val name = draft.name.trim().ifBlank { draft.model.trim().ifBlank { "未命名生图模型" } }
                        val id = app.settingsRepository.saveImageProfile(
                            ApiProfile(
                                id = draft.profileId.orEmpty(),
                                name = name,
                                baseUrl = draft.baseUrl.trim(),
                                apiKey = draft.apiKey.trim(),
                                model = draft.model.trim(),
                            )
                        )
                        imageEditor = null
                        listMsg = "已保存生图模型「$name」"
                        if (draft.isNew && imageProfiles.isEmpty()) {
                            app.settingsRepository.setActiveImageProfile(id)
                        }
                    }
                    null
                }
            },
            onTest = { draft ->
                when {
                    draft.baseUrl.isBlank() || draft.apiKey.isBlank() -> "先填接口地址和 Key"
                    !isValidBaseUrl(draft.baseUrl) -> "接口地址需以 http(s):// 开头"
                    draft.model.isBlank() -> "先填模型名（第三方生图接口都强制要求 model 字段）"
                    else -> {
                        val err = runCatching { ImageProbe(draft.baseUrl, draft.apiKey, draft.model).probe() }
                            .getOrElse { NetworkStack.describeNetworkFailure(it) }
                        if (err == null) "连接成功" else "连接失败 · $err"
                    }
                }
            },
        )
    }
}

/**
 * 模型列表行：**点整行 = 切换当前生效（默认）**，**点右侧箭头 = 进详情**。
 * 选中态由虚线框 + 副标题「默认 · 」共同表达，**不画 ✓**（那颗勾是多余的，v0.53.4 用户点名去掉）。
 */
@Composable
private fun ModelRow(
    icon: ImageVector,
    profile: ApiProfile,
    isDefault: Boolean,
    /** 本月这个模型消耗的 tokens（v0.56.8）；0 = 不显示 */
    usageTokens: Int,
    /** 该模型的缓存命中率（v0.82.0，模型行尾追加「· 缓存 xx%」）；null = 服务商没回读数，不编 */
    usageCacheRate: Double? = null,
    onSwitch: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    // 右侧挂一句「共消耗 X tokens」（字体与行内小字一致）；0 就不占位。
    // 有缓存读数（prompt>0）时顺手带上命中率——用户选型时能直接看出哪个模型缓存友好。
    val usageSlot: (@Composable () -> Unit)? = if (usageTokens > 0) {
        {
            Text(
                "共消耗 " + com.menghuan.app.data.UsageTracker.formatTokens(usageTokens) + " tokens" +
                    (usageCacheRate?.let { " · 缓存 " + kotlin.math.round(it * 100).toInt() + "%" } ?: ""),
                fontSize = 12.sp,
                color = MutedText,
            )
        }
    } else {
        null
    }
    NeuSheetRow(
        icon = icon,
        title = profile.name,
        subtitle = buildString {
            if (isDefault) append("默认 · ")
            append(profile.model.ifBlank { "模型未填" })
            append(" · ").append(displayUrl(profile.baseUrl).ifBlank { "地址未填" })
        },
        selected = isDefault,
        showCheck = false,
        trailing = usageSlot,
        onClick = onSwitch,
        onChevronClick = onOpenDetail,
    )
}

/**
 * 模型详情弹窗（对话 / 生图共用，保证两边字段与操作完全一致）。
 *
 * [onSave] / [onTest] 返回"要显示给用户的提示"：null = 无提示、正常关闭；
 * 非 null = 就地红字提示且不关弹窗（用 `draft to msg` 表达）。测试结果始终就地显示。
 */
@Composable
private fun ModelEditorDialog(
    initial: EditorState,
    presets: List<ApiPreset>,
    isDefault: Boolean,
    defaultLabel: String,
    deleteLabel: String,
    onDismiss: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
    onSave: (EditorState) -> String?,
    onTest: suspend (EditorState) -> String,
    modelPlaceholder: String = "deepseek-chat",
    /** 选择列表里每行左侧的图标：对话模型用代码图标、生图模型用图片图标（与外面列表行一致） */
    modelIcon: ImageVector = MIcons.Code,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    // 「获取可用模型」（v0.57）：**点了才联网**；拿到列表后**在同一个弹层里**切成列表态让用户点选
    // （不再叠第二个 ModalBottomSheet：两层弹层会只剩麻烦，标题与返回都在这一层里换）
    var picking by remember { mutableStateOf(false) }
    var listBusy by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }

    NeuSheetScaffold(
        title = when {
            picking -> "选择模型"
            draft.isNew -> "新模型"
            else -> "编辑模型"
        },
        onDismiss = onDismiss,
    ) {
        if (picking) {
            ModelPickerContent(
                models = models,
                icon = modelIcon,
                onPick = {
                    draft = draft.copy(model = it)
                    picking = false
                },
                onBack = { picking = false },
            )
            return@NeuSheetScaffold
        }
        NeuTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            label = "名称",
            placeholder = "例：DeepSeek",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        PresetChipsRow(
            presets = presets,
            currentBaseUrl = draft.baseUrl,
            onPickPreset = { preset ->
                // 模型名只在还没填的时候跟着预设走，别把用户手改过的模型名覆盖掉
                draft = if (draft.model.isBlank()) {
                    draft.copy(baseUrl = preset.url, model = preset.model)
                } else {
                    draft.copy(baseUrl = preset.url)
                }
            },
            onPickCustom = {
                // 已是自定义态就不动，避免误清用户输入
                if (presets.any { it.url == draft.baseUrl }) {
                    draft = draft.copy(baseUrl = "", model = "")
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        NeuTextField(
            value = draft.baseUrl,
            onValueChange = { draft = draft.copy(baseUrl = it) },
            label = "接口地址",
            placeholder = "https://api.deepseek.com/v1",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        NeuTextField(
            value = draft.apiKey,
            onValueChange = { draft = draft.copy(apiKey = it) },
            label = "API Key",
            placeholder = "sk-…",
            modifier = Modifier.fillMaxWidth(),
        )
        // 「获取可用模型」（v0.57）：模型 ID 是各家那套"全小写 + 连字符 + 日期后缀"写法，
        // 手打必错（真机 404 就是这么来的）→ 填好地址 + Key 后拉一次列表，点一个直接填进「模型」。
        Spacer(Modifier.height(4.dp))
        NeuTextButton(
            text = if (listBusy) "获取中…" else "获取可用模型",
            onClick = {
                when {
                    draft.baseUrl.isBlank() || draft.apiKey.isBlank() ->
                        testResult = "先填接口地址和 API Key"

                    listBusy -> Unit
                    else -> {
                        listBusy = true
                        testResult = null
                        scope.launch {
                            val r = OpenAiClient(draft.baseUrl, draft.apiKey, draft.model).listModels()
                            if (r.error != null) {
                                // 拉不到不阻塞保存：有的网关压根没实现 /models
                                testResult = "获取模型失败 · ${r.error}（可直接手填模型名）"
                            } else {
                                models = r.ids
                                picking = true
                            }
                            listBusy = false
                        }
                    }
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        NeuTextField(
            value = draft.model,
            onValueChange = { draft = draft.copy(model = it) },
            label = "模型",
            placeholder = modelPlaceholder,
            modifier = Modifier.fillMaxWidth(),
        )
        testResult?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                fontSize = 13.sp,
                color = if (it.startsWith("连接成功")) NeuAccent else NeuDanger,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            NeuButton(
                text = if (testing) "测试中…" else "测试连接",
                onClick = {
                    testing = true
                    scope.launch {
                        testResult = onTest(draft)
                        testing = false
                    }
                },
                enabled = !testing,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            NeuButton(
                text = "保存",
                kind = NeuButtonKind.Primary,
                onClick = {
                    onSave(draft)?.let { testResult = it }
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (!draft.isNew) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                if (isDefault) {
                    Text("当前默认", fontSize = 13.sp, color = MutedText)
                } else {
                    NeuTextButton(defaultLabel, onClick = onSetDefault)
                }
                Spacer(Modifier.weight(1f))
                NeuTextButton(deleteLabel, color = NeuDanger, onClick = onDelete)
            }
        }
    }
}

/**
 * 「获取可用模型」的列表态（v0.57）：列表，点一行 = 把模型 ID 填进「模型」栏。
 *
 * ⚠️ 列表**必须给死高度**：外层弹层是 `verticalScroll` 的 Column，里面再放没有界高度的
 * LazyColumn 会直接崩（"measured with infinity maximum height constraints"）。
 *
 * 列表只显示模型 ID 原文，不加说明小字（全应用语言）；接口没实现 `/models` 时压根到不了这里
 * （上一层的按钮会把原因写出来，并提示可手填）。
 */
@Composable
private fun ModelPickerContent(
    models: List<String>,
    icon: ImageVector,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NeuTextButton("← 返回", onClick = onBack)
        Spacer(Modifier.width(12.dp))
        Text("共 ${models.size} 个", fontSize = 12.sp, color = MutedText)
    }
    Spacer(Modifier.height(10.dp))
    LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
        items(models) { id ->
            NeuSheetRow(icon = icon, title = id, subtitle = "", onClick = { onPick(id) })
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 预设 chips 行：常用服务商 + 尾部「自定义」。
 *
 * 「自定义」的选中态 = 当前 baseUrl 非空且不匹配任何预设；点击时若当前是预设态则清空
 * baseUrl/model 让用户从头填，已是自定义态则不动（避免误清用户辛苦填的自定义 URL）。
 * 对话模型和生图模型共用此组件，保证两处 UI 一致。
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
