package com.menghuan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.data.AsrModelManager
import com.menghuan.app.data.AsrModelRules
import com.menghuan.app.data.DEFAULT_IMAGE_PROFILE_ID
import com.menghuan.app.data.DEFAULT_PROFILE_ID
import com.menghuan.app.data.SEARCH_BOCHA
import com.menghuan.app.data.SEARCH_CUSTOM
import com.menghuan.app.data.SEARCH_TAVILY
import com.menghuan.app.data.TtsConfig
import com.menghuan.app.data.WebSearchClient
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.profile.MeViewModel
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import kotlinx.coroutines.launch

/**
 * 设置主页的**二级页**（v0.54.2「方案二」）：主页那些多条目板块（模型配置 / 通话行为 /
 * 提醒与通知）现在都是**一张入口卡**，点进来才是这一页里的几张子卡。
 *
 * 不是手风琴、不是分组——用户原话："折叠，是点进去。"
 */

/** 模型配置（v0.54.2）：对话 / 生图 / 识别 / 语音模型 + 联网搜索，五张卡 */
@Composable
fun ModelConfigScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val app = settingsContainer()

    val profiles by app.settingsRepository.apiProfiles.collectAsState(initial = emptyList())
    val activeProfileId by app.settingsRepository.activeProfileId
        .collectAsState(initial = DEFAULT_PROFILE_ID)
    val imageProfiles by app.settingsRepository.imageProfiles.collectAsState(initial = emptyList())
    val imageActiveId by app.settingsRepository.imageActiveProfileId
        .collectAsState(initial = DEFAULT_IMAGE_PROFILE_ID)
    val ttsConfig by app.settingsRepository.ttsConfig.collectAsState(initial = TtsConfig())
    val searchProvider by app.settingsRepository.searchProvider.collectAsState(initial = SEARCH_BOCHA)
    val searchKey by app.settingsRepository.searchApiKey.collectAsState(initial = "")
    // v0.59.0：自定义这一家的 Key 是独立的一份，副标题要跟着当前生效那家取
    val customSearchKey by app.settingsRepository.searchCustomApiKey.collectAsState(initial = "")
    // 模型状态只在进页时读一次：磁盘 stat 不贵，但也不该每帧重组都去碰
    val asrReady = remember { AsrModelManager.isReady(app) }

    val activeProfileName = profiles.firstOrNull { it.id == activeProfileId }?.name
    val imageActiveName = imageProfiles.firstOrNull { it.id == imageActiveId }?.name
    val asrSubtitle = if (asrReady) {
        "已就绪 · SenseVoice 离线"
    } else {
        "未下载 · 需 ${AsrModelRules.humanSize(AsrModelManager.pendingBytes(app))}"
    }

    SettingsPage(title = "模型配置", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        SettingsRow(
            show = true,
            icon = MIcons.Code,
            title = "对话模型",
            subtitle = chatSubtitle(activeProfileName, profiles.size),
            onClick = { onOpen(SettingsRoutes.CHAT_MODEL) },
        )
        SettingsRow(
            show = true,
            icon = MIcons.ImageIcon,
            title = "生图模型",
            subtitle = imageSubtitle(imageActiveName, imageProfiles.size),
            onClick = { onOpen(SettingsRoutes.IMAGE_MODEL) },
        )
        SettingsRow(
            show = true,
            icon = MIcons.Mic,
            title = "识别模型",
            subtitle = asrSubtitle,
            onClick = { onOpen(SettingsRoutes.ASR_MODEL) },
        )
        SettingsRow(
            show = true,
            icon = MIcons.VoiceWave,
            title = "语音模型",
            subtitle = ttsSubtitle(ttsConfig),
            onClick = { onOpen(SettingsRoutes.TTS_CONFIG) },
        )
        SettingsRow(
            show = true,
            icon = MIcons.Search,
            title = "联网搜索",
            subtitle = searchSubtitle(
                searchProvider,
                if (searchProvider == SEARCH_CUSTOM) customSearchKey else searchKey,
            ),
            onClick = { onOpen(SettingsRoutes.SEARCH_CONFIG) },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/** 通话行为（v0.54.2）：语音通话铃声（v0.54.5：AI 主动打电话已从设置里删掉 —— 它按角色配，
 *  在**每个角色会话右上角 ⋮ → 整页设置**里的「允许主动打电话」卡片上，含时间与阈值）。
 *
 *  v0.56.5（用户点名）：**「接听延迟」从「语音通话铃声」那一页里拎出来，成为与它同级的独立设置项**，
 *  在这张「通话行为」页上与「语音通话铃声」并排两张卡。 */
@Composable
fun CallBehaviorScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val app = settingsContainer()
    val answerDelay by app.settingsRepository.aiAnswerDelaySeconds.collectAsState(initial = 3)

    SettingsPage(title = "通话行为", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        SettingsRow(
            show = true,
            icon = MIcons.Bell,
            title = "语音通话铃声",
            subtitle = "来电与去电共用 · 可传音频或视频",
            onClick = { onOpen(SettingsRoutes.CALL_RINGTONE) },
        )
        SettingsRow(
            show = true,
            icon = MIcons.Phone,
            title = "接听延迟",
            subtitle = "我打给 AI 时，TA ${answerDelayLabel(answerDelay)}接",
            onClick = { onOpen(SettingsRoutes.CALL_ANSWER_DELAY) },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * 接听延迟（v0.54.0 从「语音」页搬到「语音通话铃声」页里；**v0.56.5 再独立成与它同级的一项**）：
 * 「我打给 AI 时 TA 过几秒接」。
 *
 * v0.56.6（用户点名）：**秒数改成自己填**，不再是一排固定档位 ——
 * 填 `0` = 立即接；上限 600 秒（10 分钟）。填完点「保存」回上一页。
 */
@Composable
fun CallAnswerDelayScreen(onBack: () -> Unit) {
    val app = settingsContainer()
    val scope = rememberCoroutineScope()
    val current by app.settingsRepository.aiAnswerDelaySeconds.collectAsState(initial = 3)

    // 草稿跟着已存值走（DataStore 首次读到真实值时会刷新一次，之后不再覆盖用户的输入）
    var draft by remember(current) { mutableStateOf(current.toString()) }
    var hint by remember { mutableStateOf<String?>(null) }

    SettingsPage(title = "接听延迟", onBack = onBack) {
        Spacer(Modifier.height(14.dp))
        Text(
            "我打给 AI 时，TA 过几秒接听。只影响**你打给 AI** 的那一通（延迟期间一直响铃、" +
                "界面显示「正在呼叫…」）；AI 打给你的来电不受影响。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MutedText,
        )
        Spacer(Modifier.height(14.dp))
        NeuTextField(
            value = draft,
            // 只留数字：这一栏就是秒数，别让用户填进小数点或单位
            onValueChange = { input ->
                draft = input.filter { it.isDigit() }.take(4)
                hint = null
            },
            label = "接听前等几秒",
            placeholder = "例：8（0 = 立即接）",
            modifier = Modifier.fillMaxWidth(),
            keyboardType = KeyboardType.Number,
        )
        hint?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 12.sp, lineHeight = 18.sp, color = NeuDanger)
        }
        Spacer(Modifier.height(16.dp))
        NeuButton(
            text = "保存",
            kind = NeuButtonKind.Primary,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val seconds = draft.trim().toIntOrNull()
                if (seconds == null) {
                    hint = "请填一个 0~600 之间的整数（秒）"
                } else if (seconds > 600) {
                    hint = "最多 600 秒（10 分钟）"
                } else {
                    scope.launch { app.settingsRepository.setAiAnswerDelaySeconds(seconds) }
                    onBack()
                }
            },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/** 「接听延迟」卡副标题：0 = 立即，其余显示"n 秒" */
private fun answerDelayLabel(seconds: Int): String = if (seconds <= 0) "立即" else "$seconds 秒"

/** 提醒与通知（v0.54.2）：三个开关（联网搜索已挪去「模型配置」；v0.54.4 定时主动消息与
 *  人设主动关心按角色移到「会话设置」页，这里不再放） */
@Composable
fun RemindersScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val app = settingsContainer()
    val scope = rememberCoroutineScope()
    val vm: MeViewModel = viewModel(factory = MeViewModel.Factory)

    val notifyOn by vm.notificationsEnabled.collectAsState()
    val agentNotify by app.settingsRepository.agentNotifyEnabled.collectAsState(initial = true)

    SettingsPage(title = "提醒与通知", onBack = onBack) {
        Spacer(Modifier.height(8.dp))
        SettingsSwitchRow(
            show = true,
            title = "任务到点提醒",
            caption = "任务设定的提醒时间到点发系统通知",
            checked = notifyOn,
            onToggle = { vm.setNotificationsEnabled(it) },
        )
        SettingsSwitchRow(
            show = true,
            title = "手机操作进行中通知",
            caption = "AI 操控手机时的常驻进度条，点开回到会话",
            checked = agentNotify,
            onToggle = { on -> scope.launch { app.settingsRepository.setAgentNotifyEnabled(on) } },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * 联网搜索（v0.54.2）：从「定时主动消息」里**拎出来独立成页**（用户明确要求：
 * "博查那个联网搜索你不能提出来吗？放到定时主动消息那里干嘛？"），入口挂在「模型配置」下。
 *
 * v0.56.5：加**「测试搜索」** —— 配好 Key 之后先在这儿点一下，立刻知道通不通、
 * 通了几条；失败时把服务端原文带出来（HTTP 状态 / Key 不对 / 额度用完），
 * 不再只能等到聊天里"没搜到"才发现配错了。
 */
@Composable
fun WebSearchConfigScreen(onBack: () -> Unit) {
    val app = settingsContainer()
    val scope = rememberCoroutineScope()

    val provider by app.settingsRepository.searchProvider.collectAsState(initial = SEARCH_BOCHA)
    val searchKey by app.settingsRepository.searchApiKey.collectAsState(initial = "")
    // v0.59.0：自定义这一家有**独立的 Key 和一个用户自填的地址**
    val customKey by app.settingsRepository.searchCustomApiKey.collectAsState(initial = "")
    val customUrl by app.settingsRepository.searchCustomUrl.collectAsState(initial = "")

    // 草稿跟着已存值走：DataStore 首次读到真实值时会刷新一次，之后用户编辑不再被覆盖
    var providerDraft by remember(provider) { mutableStateOf(provider) }
    var keyDraft by remember(searchKey) { mutableStateOf(searchKey) }
    var customKeyDraft by remember(customKey) { mutableStateOf(customKey) }
    var urlDraft by remember(customUrl) { mutableStateOf(customUrl) }

    val isCustom = providerDraft == SEARCH_CUSTOM
    // 当前这一家正在用的 Key（测试按钮和"配没配 Key"的判断都走它）
    val activeKey = if (isCustom) customKeyDraft.trim() else keyDraft.trim()

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }

    SettingsPage(title = "联网搜索", onBack = onBack) {
        Spacer(Modifier.height(14.dp))
        Text("服务商", fontSize = 12.sp, color = MutedText)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NeuChip(text = "博查（国内）", selected = providerDraft == SEARCH_BOCHA) {
                providerDraft = SEARCH_BOCHA
                testResult = null
            }
            NeuChip(text = "Tavily（国际）", selected = providerDraft == SEARCH_TAVILY) {
                providerDraft = SEARCH_TAVILY
                testResult = null
            }
            // v0.59.0（用户拍板）：第三家 = 自己填接口地址 + Key
            NeuChip(text = "自定义", selected = isCustom) {
                providerDraft = SEARCH_CUSTOM
                testResult = null
            }
        }
        Spacer(Modifier.height(10.dp))
        if (isCustom) {
            // 自定义这一家**不写任何官网地址**：用户的接口是哪来的只有他自己知道，
            // 编一个"看起来合理"的域名出来是最坏的做法（AGENTS §零.6）
            NeuTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it; testResult = null },
                label = "接口地址",
                placeholder = "例：https://你的中转域名/v1/web-search",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "请求体发 {\"query\", \"max_results\", \"count\"}；返回里的结果数组按 " +
                    "webPages / results / data / items / documents 依次找。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MutedText,
            )
        } else {
            Text(
                "去 ${WebSearchClient.consoleUrlOf(providerDraft)} 注册并在控制台创建 API Key，粘贴到下面。" +
                    "接口：${WebSearchClient.endpointOf(providerDraft)}",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MutedText,
            )
        }
        Spacer(Modifier.height(12.dp))
        NeuTextField(
            value = if (isCustom) customKeyDraft else keyDraft,
            onValueChange = {
                // **只写当前这一家的草稿**：三家共用一个 state 的话，
                // 切到家就把自己那份填过的 Key 覆盖掉了（v0.52.0 语音合成踩过的坑）
                if (isCustom) customKeyDraft = it else keyDraft = it
                testResult = null
            },
            label = "API Key",
            placeholder = "粘贴控制台里的 Key",
            modifier = Modifier.fillMaxWidth(),
        )

        // 测试搜索（v0.56.5）：用草稿里的 Key 真搜一次「今天的新闻」，就地给出结果
        Spacer(Modifier.height(14.dp))
        NeuButton(
            text = if (testing) "测试中…" else "测试搜索",
            modifier = Modifier.fillMaxWidth(),
            enabled = !testing && activeKey.isNotBlank() && (!isCustom || urlDraft.isNotBlank()),
            onClick = {
                testing = true
                testResult = null
                val testingProvider = providerDraft
                val testingKey = activeKey
                val testingUrl = if (isCustom) urlDraft.trim() else ""
                scope.launch {
                    val started = System.currentTimeMillis()
                    val outcome = runCatching {
                        WebSearchClient.searchOrThrow(
                            testingProvider, testingKey, TEST_SEARCH_QUERY, customEndpoint = testingUrl,
                        )
                    }
                    val cost = System.currentTimeMillis() - started
                    outcome
                        .onSuccess { hits ->
                            testOk = hits.isNotEmpty()
                            testResult = if (hits.isEmpty()) {
                                "连接成功，但这一次没搜到结果（${cost}ms）——" +
                                    "换大会关键词再试，或确认账号额度还在。"
                            } else {
                                "测试成功 · ${cost}ms · 取到 ${hits.size} 条：" + hits.first().title
                            }
                        }
                        .onFailure {
                            testOk = false
                            testResult = "测试失败 · ${cost}ms · " +
                                com.menghuan.app.llm.NetworkStack.describeNetworkFailure(it)
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
            text = "保存",
            kind = NeuButtonKind.Primary,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    app.settingsRepository.setSearchProvider(providerDraft)
                    // 只写**当前这一家**的 Key：一把全写会把另一家填好的 Key 抹掉
                    //（v0.52.0 语音合成三家就是这么互相覆盖的，测试抓出来过）
                    if (isCustom) {
                        app.settingsRepository.setSearchCustomApiKey(customKeyDraft.trim())
                        app.settingsRepository.setSearchCustomUrl(urlDraft.trim())
                    } else {
                        app.settingsRepository.setSearchApiKey(keyDraft.trim())
                    }
                }
                onBack()
            },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/** 「测试搜索」用的固定关键词：与手册里举的例子一致（问"今天有什么新闻"是最典型的联网场景） */
private const val TEST_SEARCH_QUERY = "今天的新闻"

/** 对话模型副标题：当前生效那条的名字；一条都没有就明说聊天不可用 */
private fun chatSubtitle(activeName: String?, profileCount: Int): String = when {
    profileCount == 0 -> "未配置 · 聊天暂不可用"
    else -> "当前：${activeName ?: "默认模型"}"
}

/** 生图模型副标题：当前生效那条的名字；一条都没有就明说不会画图 */
private fun imageSubtitle(activeName: String?, profileCount: Int): String = when {
    profileCount == 0 -> "未配置 · 不会画图"
    else -> "当前：${activeName ?: "默认生图模型"}"
}

/** 语音模型副标题：哪家 + 哪个模型；没配就明说"不出声" */
private fun ttsSubtitle(cfg: TtsConfig): String =
    if (cfg.isComplete) "${cfg.provider.label} · ${cfg.effectiveModel()}" else "未配置 · AI 与通话都不会出声"

/** 联网搜索副标题：哪家 + 配没配 Key（不识别的别名交给上层搜索） */
private fun searchSubtitle(provider: String, key: String): String =
    if (key.isBlank()) "${WebSearchClient.labelOf(provider)} · 未配置 Key" else WebSearchClient.labelOf(provider)