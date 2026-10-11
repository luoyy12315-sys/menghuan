package com.menghuan.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.AiPrompts
import com.menghuan.app.data.DailySuggestionMath
import com.menghuan.app.data.ASSISTANT_PERSONA_NAME
import com.menghuan.app.data.ASSISTANT_PERSONA_TITLE
import com.menghuan.app.data.InsightMath
import com.menghuan.app.data.ProfileExtractor
import com.menghuan.app.data.model.Persona
import com.menghuan.app.ui.chat.AGENT_CHAT_DEFAULT_STRATEGY
import com.menghuan.app.ui.chat.AGENT_CHAT_HINT_FRAMEWORK
import com.menghuan.app.ui.chat.IMAGE_HINT_DEFAULT_STRATEGY
import com.menghuan.app.ui.chat.IMAGE_HINT_FRAMEWORK
import com.menghuan.app.ui.chat.VOICE_NOTE_DEFAULT_STRATEGY
import com.menghuan.app.ui.chat.VOICE_NOTE_FRAMEWORK
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 该页所有可编提示词的**当前草稿**（v0.54.4 · v0.68.0 补每日建议） */
private data class PromptDraft(
    val assistant: String = AiPrompts.DEFAULT_ASSISTANT_PROMPT,
    val base: String = AiPrompts.DEFAULT_BASE_PROMPT,
    val preamble: String = AiPrompts.DEFAULT_PROFILE_PREAMBLE,
    val extraction: String = ProfileExtractor.DEFAULT_STRATEGY,
    // v0.88.0：洞察策略从提炼策略里拆出来（洞察改从记忆+任务+记账归纳，选材口径无关了）
    val insight: String = InsightMath.DEFAULT_STRATEGY,
    val daily: String = DailySuggestionMath.DEFAULT_STRATEGY,
    val image: String = IMAGE_HINT_DEFAULT_STRATEGY,
    val agentEnabled: Boolean = true,
    val agent: String = AGENT_CHAT_DEFAULT_STRATEGY,
    val voiceNote: String = VOICE_NOTE_DEFAULT_STRATEGY,
    // v0.82.0 P1-1：滚动摘要（总开关默认开 + 摘要策略）
    val summaryEnabled: Boolean = true,
    val summary: String = com.menghuan.app.data.SummaryExtractor.DEFAULT_STRATEGY,
    // v0.98.0：主动关心策略。这一段以前在 `ProactiveChat` 里是**零引用的死代码**，
    // 用户既改不了、约束也没生效；现在接上并开放编辑。
    val proactive: String = com.menghuan.app.data.ProactiveChat.DEFAULT_STRATEGY,
    // v0.98.0：记忆记录策略（管"聊天时 AI 随手记"，与上面的 extraction「批量提炼」是两条入口）
    val memoryMarker: String = com.menghuan.app.data.MemoryMarker.DEFAULT_STRATEGY,
)

/**
 * 设置 · AI 提示词：**十二段**可编辑（手风琴布局）。
 *
 * v0.98.0 加第十一段「主动关心策略」—— 那段提示词原本是 `ProactiveChat` 里一句
 * **零引用的死代码**（连"不超过 60 字"都没生效过），这次既接上又开放编辑。
 * 同版加第十二段「记忆记录策略」—— 它管"聊天时 AI 随手记"，与旁边那段「记忆提炼策略」
 * （批量提炼）是记忆的两条入口，摆在一起是为了让用户看得出区别。
 *
 * v0.68.0 补上第九段「每日建议策略」—— DataStore 键与生成链路的读取从 v0.42.0 就在了，
 * `DailySuggestionGenerator` 的注释也一直写着"用户可在设置里覆盖"，**唯独页面里没有这一格**：
 * 用户看得见别的段、改不了今日建议的口径，而代码那句承诺是假的。
 *
 * v0.88.0 加第十段「AI 洞察策略」，并把原来那段「AI 洞察提炼策略」**改名为「记忆提炼策略」**
 * —— 洞察从提炼副产品变成独立链路（每天 23:00 从记忆+任务+记账归纳）之后，
 * 原来那一段其实只控记忆，继续叫"洞察"会让用户改错地方。
 *
 * 分层语义不变：框架（硬编码只读，改了 parse 会崩）+ 策略（可编可恢复）。
 *
 * **v0.54.4 改版（用户点名）**：
 * - 段标题**字体加大**（13sp → 17sp）；
 * - 去掉标题与标签里的**括号文字**（「（仅生图模型配齐时注入）」这类）；
 * - 去掉解释性小字（手机操控段那句说明、列表底部的「生成逻辑说明」整段删除）；
 * - 分层标签只留「框架」「策略」四个字；
 * - 底部「全部恢复默认 / 保存」两个大按钮**删掉**，改成**改动过的段**在提示词下方
 *   自己显示「恢复 / 保存」两颗；没改动的段不显示。
 *
 * **v0.54.5（用户点名）**：输入框不再重复显示段标题 —— 原来「应用助手提示词」这类段名
 * 在屏幕上出现**两遍**（段标题一行、输入框 label 一行），「策略」也是两行；现在只留段标题，
 * 输入框自己不带 label。
 */
@Composable
fun PromptSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MenghuanApp
    val scope = rememberCoroutineScope()

    // draft = 正在编辑的值；persisted = 已落库的值。两者不等 = 这一段有改动 → 显示「恢复 / 保存」
    var draft by remember { mutableStateOf(PromptDraft()) }
    var persisted by remember { mutableStateOf(PromptDraft()) }
    var loaded by remember { mutableStateOf(false) }
    var openSection by remember { mutableStateOf<String?>(null) }
    fun toggle(key: String) {
        openSection = if (openSection == key) null else key
    }

    LaunchedEffect(Unit) {
        val initial = PromptDraft(
            // 助手手册按"是不是内置助手"定位，不是按名字（v0.68.0）：
            // 老逻辑只查 name="应用助手"，用户改过名字就查不到 → 这一格显示默认手册，
            // 点保存还会**再建一个**内置助手，而原来那份从此不再跟着版本升级。
            assistant = runCatching {
                app.personaRepository.findAssistant()?.systemPrompt
            }.getOrNull() ?: AiPrompts.DEFAULT_ASSISTANT_PROMPT,
            base = app.settingsRepository.basePrompt.first(),
            preamble = app.settingsRepository.profilePreamble.first(),
            extraction = app.settingsRepository.extractionStrategy.first(),
            insight = app.settingsRepository.insightStrategy.first(),
            daily = app.settingsRepository.dailySuggestionStrategy.first(),
            image = app.settingsRepository.imageHintStrategy.first(),
            agentEnabled = app.settingsRepository.agentChatEnabled.first(),
            agent = app.settingsRepository.agentChatStrategy.first(),
            voiceNote = app.settingsRepository.voiceNoteStrategy.first(),
            summaryEnabled = app.settingsRepository.rollingSummaryEnabled.first(),
            summary = app.settingsRepository.summaryStrategy.first(),
            proactive = app.settingsRepository.proactiveStrategy.first(),
            memoryMarker = app.settingsRepository.memoryMarkerStrategy.first(),
        )
        draft = initial
        persisted = initial
        loaded = true
    }

    /** 把草稿整体落库；「保存」只落库、不退出（用户可以一段段保存） */
    fun save() {
        scope.launch {
            val current = draft
            runCatching {
                // 应用助手：改内置人设的设定词；被删除后保存会以固定名字重建。
                // 必须走 personaRepository.save——那里记下"用户亲手改过手册"，
                // App 才不会在下个版本把用户写的东西冲掉（v0.51.1 升级机制）
                // 查"是不是内置助手"也走同一个 findAssistant（v0.68.0）：
                // 按名字查会在改过名的装机上多建一条内置助手。
                val existing = app.personaRepository.findAssistant()
                val trimmed = current.assistant.trim()
                app.personaRepository.save(
                    existing?.copy(systemPrompt = trimmed)
                        ?: Persona(
                            name = ASSISTANT_PERSONA_NAME,
                            title = ASSISTANT_PERSONA_TITLE,
                            avatarEmoji = "🤖",
                            isBuiltIn = true,
                            systemPrompt = trimmed,
                        )
                )
                app.settingsRepository.setBasePrompt(current.base)
                app.settingsRepository.setProfilePreamble(current.preamble)
                app.settingsRepository.setExtractionStrategy(current.extraction)
                app.settingsRepository.setInsightStrategy(current.insight)
                app.settingsRepository.setDailySuggestionStrategy(current.daily)
                app.settingsRepository.setImageHintStrategy(current.image)
                app.settingsRepository.setAgentChatEnabled(current.agentEnabled)
                app.settingsRepository.setAgentChatStrategy(current.agent)
                app.settingsRepository.setVoiceNoteStrategy(current.voiceNote)
                app.settingsRepository.setRollingSummaryEnabled(current.summaryEnabled)
                app.settingsRepository.setSummaryStrategy(current.summary)
                app.settingsRepository.setProactiveStrategy(current.proactive)
                app.settingsRepository.setMemoryMarkerStrategy(current.memoryMarker)
            }
            persisted = current
        }
    }

    if (!loaded) {
        SettingsPage(title = "AI 提示词", onBack = onBack) {
            Spacer(Modifier.height(24.dp))
        }
        return
    }

    // 「已自定义」徽标 = 与默认值不同（语义沿用旧版）
    fun customized(value: String, default: String) = value.trim() != default

    SettingsPage(title = "AI 提示词", onBack = onBack) {
        Spacer(Modifier.height(8.dp))

        SettingsSectionRow(
            title = "应用助手提示词",
            titleSize = 17.sp,
            customized = customized(draft.assistant, AiPrompts.DEFAULT_ASSISTANT_PROMPT),
            expanded = openSection == "assistant",
        ) { toggle("assistant") }
        if (openSection == "assistant") {
            NeuTextField(
                value = draft.assistant,
                onValueChange = { draft = draft.copy(assistant = it) },
                singleLine = false,
                minLines = 4,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionFooter(
                dirty = draft.assistant != persisted.assistant,
                onReset = { draft = draft.copy(assistant = AiPrompts.DEFAULT_ASSISTANT_PROMPT) },
                onSave = { save() },
            )
        }

        SettingsSectionRow(
            title = "基础提示词",
            titleSize = 17.sp,
            customized = customized(draft.base, AiPrompts.DEFAULT_BASE_PROMPT),
            expanded = openSection == "base",
        ) { toggle("base") }
        if (openSection == "base") {
            NeuTextField(
                value = draft.base,
                onValueChange = { draft = draft.copy(base = it) },
                singleLine = false,
                minLines = 2,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionFooter(
                dirty = draft.base != persisted.base,
                onReset = { draft = draft.copy(base = AiPrompts.DEFAULT_BASE_PROMPT) },
                onSave = { save() },
            )
        }

        SettingsSectionRow(
            title = "档案注入说明",
            titleSize = 17.sp,
            customized = customized(draft.preamble, AiPrompts.DEFAULT_PROFILE_PREAMBLE),
            expanded = openSection == "preamble",
        ) { toggle("preamble") }
        if (openSection == "preamble") {
            NeuTextField(
                value = draft.preamble,
                onValueChange = { draft = draft.copy(preamble = it) },
                singleLine = false,
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            SectionFooter(
                dirty = draft.preamble != persisted.preamble,
                onReset = { draft = draft.copy(preamble = AiPrompts.DEFAULT_PROFILE_PREAMBLE) },
                onSave = { save() },
            )
        }

        SettingsSectionRow(
            // v0.88.0：段名从「AI 洞察提炼策略」改成「记忆提炼策略」——
            // 它现在只管记忆（facts），叫"洞察"会让用户以为改这里能影响数据页的洞察。
            title = "记忆提炼策略",
            titleSize = 17.sp,
            customized = customized(draft.extraction, ProfileExtractor.DEFAULT_STRATEGY),
            expanded = openSection == "extraction",
        ) { toggle("extraction") }
        if (openSection == "extraction") {
            LayeredEditor(
                framework = ProfileExtractor.FRAMEWORK,
                strategy = draft.extraction,
                dirty = draft.extraction != persisted.extraction,
                onStrategyChange = { draft = draft.copy(extraction = it) },
                onReset = { draft = draft.copy(extraction = ProfileExtractor.DEFAULT_STRATEGY) },
                onSave = { save() },
            )
        }

        // v0.98.0：记忆记录策略。**紧挨着「记忆提炼策略」是有意的** —— 两者是记忆的两条入口，
        // 用户极易搞混"我改的到底是哪个"。这段管**聊天时 AI 随手记**（每轮注入 system prompt），
        // 上面那段管**攒够轮数后批量提炼**（只在提炼请求里发给提炼模型）。
        SettingsSectionRow(
            title = "记忆记录策略",
            titleSize = 17.sp,
            customized = customized(draft.memoryMarker, com.menghuan.app.data.MemoryMarker.DEFAULT_STRATEGY),
            expanded = openSection == "memoryMarker",
        ) { toggle("memoryMarker") }
        if (openSection == "memoryMarker") {
            LayeredEditor(
                framework = com.menghuan.app.data.MemoryMarker.HINT_FRAMEWORK,
                strategy = draft.memoryMarker,
                dirty = draft.memoryMarker != persisted.memoryMarker,
                onStrategyChange = { draft = draft.copy(memoryMarker = it) },
                onReset = { draft = draft.copy(memoryMarker = com.menghuan.app.data.MemoryMarker.DEFAULT_STRATEGY) },
                onSave = { save() },
            )
        }

        SettingsSectionRow(
            // v0.88.0 新增：洞察策略（每天 23:00 从记忆+任务+记账归纳）
            title = "AI 洞察策略",
            titleSize = 17.sp,
            customized = customized(draft.insight, InsightMath.DEFAULT_STRATEGY),
            expanded = openSection == "insight",
        ) { toggle("insight") }
        if (openSection == "insight") {
            LayeredEditor(
                framework = InsightMath.FRAMEWORK,
                strategy = draft.insight,
                dirty = draft.insight != persisted.insight,
                onStrategyChange = { draft = draft.copy(insight = it) },
                onReset = { draft = draft.copy(insight = InsightMath.DEFAULT_STRATEGY) },
                onSave = { save() },
            )
        }

        SettingsSectionRow(
            title = "每日建议策略",
            titleSize = 17.sp,
            customized = customized(draft.daily, DailySuggestionMath.DEFAULT_STRATEGY),
            expanded = openSection == "daily",
        ) { toggle("daily") }
        if (openSection == "daily") {
            LayeredEditor(
                framework = DailySuggestionMath.FRAMEWORK,
                strategy = draft.daily,
                dirty = draft.daily != persisted.daily,
                onStrategyChange = { draft = draft.copy(daily = it) },
                onReset = { draft = draft.copy(daily = DailySuggestionMath.DEFAULT_STRATEGY) },
                onSave = { save() },
            )
        }

        SettingsSectionRow(
            title = "生图触发策略",
            titleSize = 17.sp,
            customized = customized(draft.image, IMAGE_HINT_DEFAULT_STRATEGY),
            expanded = openSection == "image",
        ) { toggle("image") }
        if (openSection == "image") {
            LayeredEditor(
                framework = IMAGE_HINT_FRAMEWORK,
                strategy = draft.image,
                dirty = draft.image != persisted.image,
                onStrategyChange = { draft = draft.copy(image = it) },
                onReset = { draft = draft.copy(image = IMAGE_HINT_DEFAULT_STRATEGY) },
                onSave = { save() },
            )
        }

        SettingsSectionRow(
            title = "手机操控",
            titleSize = 17.sp,
            customized = customized(draft.agent, AGENT_CHAT_DEFAULT_STRATEGY),
            expanded = openSection == "agent",
            trailing = {
                NeuSwitch(
                    checked = draft.agentEnabled,
                    onToggle = { draft = draft.copy(agentEnabled = it) },
                )
            },
        ) { toggle("agent") }
        if (openSection == "agent") {
            LayeredEditor(
                framework = AGENT_CHAT_HINT_FRAMEWORK,
                strategy = draft.agent,
                dirty = draft.agent != persisted.agent || draft.agentEnabled != persisted.agentEnabled,
                onStrategyChange = { draft = draft.copy(agent = it) },
                onReset = { draft = draft.copy(agent = AGENT_CHAT_DEFAULT_STRATEGY) },
                onSave = { save() },
            )
        }

        SettingsSectionRow(
            title = "AI 语音条",
            titleSize = 17.sp,
            customized = customized(draft.voiceNote, VOICE_NOTE_DEFAULT_STRATEGY),
            expanded = openSection == "voiceNote",
        ) { toggle("voiceNote") }
        if (openSection == "voiceNote") {
            LayeredEditor(
                framework = VOICE_NOTE_FRAMEWORK,
                strategy = draft.voiceNote,
                dirty = draft.voiceNote != persisted.voiceNote,
                onStrategyChange = { draft = draft.copy(voiceNote = it) },
                onReset = { draft = draft.copy(voiceNote = VOICE_NOTE_DEFAULT_STRATEGY) },
                onSave = { save() },
            )
        }

        // v0.82.0 P1-1：滚动摘要。开关放段标题右侧（与「手机操控」同款）：
        // 关掉 = 不压缩、不注入；摘要在「会话设置 → 对话摘要」里按会话查看/删除。
        SettingsSectionRow(
            title = "对话摘要策略",
            titleSize = 17.sp,
            customized = customized(draft.summary, com.menghuan.app.data.SummaryExtractor.DEFAULT_STRATEGY),
            expanded = openSection == "summary",
            trailing = {
                NeuSwitch(
                    checked = draft.summaryEnabled,
                    onToggle = { draft = draft.copy(summaryEnabled = it) },
                )
            },
        ) { toggle("summary") }
        if (openSection == "summary") {
            LayeredEditor(
                framework = com.menghuan.app.data.SummaryExtractor.FRAMEWORK,
                strategy = draft.summary,
                dirty = draft.summary != persisted.summary || draft.summaryEnabled != persisted.summaryEnabled,
                onStrategyChange = { draft = draft.copy(summary = it) },
                onReset = { draft = draft.copy(summary = com.menghuan.app.data.SummaryExtractor.DEFAULT_STRATEGY) },
                onSave = { save() },
            )
        }

        // v0.98.0：主动关心策略。它管的是「她主动来找你时说什么」的取材/语气/长度
        // —— 改长一点会显得更黏人，改短一点更清爽，属于用户口味，所以放可编这半。
        SettingsSectionRow(
            title = "主动关心策略",
            titleSize = 17.sp,
            customized = customized(draft.proactive, com.menghuan.app.data.ProactiveChat.DEFAULT_STRATEGY),
            expanded = openSection == "proactive",
        ) { toggle("proactive") }
        if (openSection == "proactive") {
            LayeredEditor(
                framework = com.menghuan.app.data.ProactiveChat.HINT_FRAMEWORK,
                strategy = draft.proactive,
                dirty = draft.proactive != persisted.proactive,
                onStrategyChange = { draft = draft.copy(proactive = it) },
                onReset = { draft = draft.copy(proactive = com.menghuan.app.data.ProactiveChat.DEFAULT_STRATEGY) },
                onSave = { save() },
            )
        }

        Spacer(Modifier.height(20.dp))
    }
}

/** 有改动才出现的两颗操作：「恢复」（本段回默认值）/「保存」（落库） */
@Composable
private fun SectionFooter(dirty: Boolean, onReset: () -> Unit, onSave: () -> Unit) {
    if (!dirty) return
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        NeuTextButton("恢复", onClick = onReset)
        Spacer(Modifier.weight(1f))
        NeuTextButton("保存", onClick = onSave)
    }
}

/**
 * 分层编辑区：框架只读凹陷面 + 策略可编输入框，标签只写「框架」「策略」四个字。
 * 有改动时下方出现「恢复 / 保存」。
 *
 * v0.54.5（用户点名"你为什么要写两个策略？"）：**输入框自己不再带 label** ——
 * 原来"策略"这个标题在屏幕上是**两行**（上面的小标题一行、输入框的 label 一行），
 * 用户看到的就是重复。同理，上面三段（应用助手提示词 / 基础提示词 / 档案注入说明）
 * 的输入框 label 也一并删掉：段标题已经说明了它是哪一段。
 */
@Composable
private fun LayeredEditor(
    framework: String,
    strategy: String,
    dirty: Boolean,
    onStrategyChange: (String) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("框架", fontSize = 11.sp, color = TextSecondary, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .neuSurface(10.dp, inset = true)
                .padding(12.dp),
        ) {
            Text(framework, fontSize = 11.sp, lineHeight = 17.sp, color = TextSecondary)
        }
        Spacer(Modifier.height(10.dp))
        Text("策略", fontSize = 11.sp, color = TextSecondary, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(4.dp))
        NeuTextField(
            value = strategy,
            onValueChange = onStrategyChange,
            singleLine = false,
            minLines = 4,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth(),
        )
        SectionFooter(dirty = dirty, onReset = onReset, onSave = onSave)
    }
}