package com.menghuan.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.AiPrompts
import com.menghuan.app.data.ASSISTANT_PERSONA_NAME
import com.menghuan.app.data.ASSISTANT_PERSONA_TITLE
import com.menghuan.app.data.ProfileExtractor
import com.menghuan.app.data.WeeklyReportMath
import com.menghuan.app.data.model.Persona
import com.menghuan.app.ui.chat.AGENT_CHAT_DEFAULT_STRATEGY
import com.menghuan.app.ui.chat.AGENT_CHAT_HINT_FRAMEWORK
import com.menghuan.app.ui.chat.IMAGE_HINT_DEFAULT_STRATEGY
import com.menghuan.app.ui.chat.IMAGE_HINT_FRAMEWORK
import com.menghuan.app.ui.chat.insightLogicText
import com.menghuan.app.ui.chat.reportLogicText
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 设置 · AI 提示词：**七段**可编辑（v0.37.2 起手风琴布局）。
 *
 * **v0.37.2 布局重构（用户反馈"太繁琐"）**：此前七段全部平铺——框架只读框 + 策略输入框 +
 * 每段恢复默认按钮 + 两段生成逻辑长文，整页是一面提示词墙。现在改为**手风琴**：
 * - 默认全部收起，整页只有 8 行索引（七段 + 生成逻辑说明），「已自定义」徽标在行内直接可见；
 * - 点一段才展开编辑，同一时间只展开一段（再点收起）；
 * - 「本段恢复默认」只在改过（≠默认值）时出现，没改过的段不放空按钮；
 * - 框架/策略分层语义不变：框架硬编码只读（改了 parse 会崩），策略可编可恢复。
 *
 * 分层设计（v0.36.0 引入）：最终发给模型的 system prompt = 框架 + "\n" + 策略。
 */
@Composable
fun PromptSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MenghuanApp
    val scope = rememberCoroutineScope()

    var assistantPrompt by remember { mutableStateOf("") }
    var basePrompt by remember { mutableStateOf(AiPrompts.DEFAULT_BASE_PROMPT) }
    var profilePreamble by remember { mutableStateOf(AiPrompts.DEFAULT_PROFILE_PREAMBLE) }
    var extractionStrategy by remember { mutableStateOf(ProfileExtractor.DEFAULT_STRATEGY) }
    var weeklyReportStrategy by remember { mutableStateOf(WeeklyReportMath.DEFAULT_STRATEGY) }
    var imageHintStrategy by remember { mutableStateOf(IMAGE_HINT_DEFAULT_STRATEGY) }
    var agentChatEnabled by remember { mutableStateOf(true) }
    var agentChatStrategy by remember { mutableStateOf(AGENT_CHAT_DEFAULT_STRATEGY) }
    var loaded by remember { mutableStateOf(false) }
    // 手风琴：同一时间只展开一段；null = 全收起
    var openSection by remember { mutableStateOf<String?>(null) }
    fun toggle(key: String) {
        openSection = if (openSection == key) null else key
    }

    LaunchedEffect(Unit) {
        assistantPrompt = runCatching {
            app.db.personaDao().getByName(ASSISTANT_PERSONA_NAME)?.systemPrompt
        }.getOrNull() ?: AiPrompts.DEFAULT_ASSISTANT_PROMPT
        basePrompt = app.settingsRepository.basePrompt.first()
        profilePreamble = app.settingsRepository.profilePreamble.first()
        extractionStrategy = app.settingsRepository.extractionStrategy.first()
        weeklyReportStrategy = app.settingsRepository.weeklyReportStrategy.first()
        imageHintStrategy = app.settingsRepository.imageHintStrategy.first()
        agentChatEnabled = app.settingsRepository.agentChatEnabled.first()
        agentChatStrategy = app.settingsRepository.agentChatStrategy.first()
        loaded = true
    }

    fun save() {
        scope.launch {
            runCatching {
                // 应用助手：改内置人设的设定词；被删除后保存会以固定名字重建
                val dao = app.db.personaDao()
                val existing = dao.getByName(ASSISTANT_PERSONA_NAME)
                val trimmed = assistantPrompt.trim()
                if (existing != null) {
                    dao.upsert(existing.copy(systemPrompt = trimmed))
                } else {
                    dao.upsert(
                        Persona(
                            name = ASSISTANT_PERSONA_NAME,
                            title = ASSISTANT_PERSONA_TITLE,
                            avatarEmoji = "🤖",
                            isBuiltIn = true,
                            systemPrompt = trimmed,
                        )
                    )
                }
                app.settingsRepository.setBasePrompt(basePrompt)
                app.settingsRepository.setProfilePreamble(profilePreamble)
                app.settingsRepository.setExtractionStrategy(extractionStrategy)
                app.settingsRepository.setWeeklyReportStrategy(weeklyReportStrategy)
                app.settingsRepository.setImageHintStrategy(imageHintStrategy)
                app.settingsRepository.setAgentChatEnabled(agentChatEnabled)
                app.settingsRepository.setAgentChatStrategy(agentChatStrategy)
            }
            onDismiss()
        }
    }

    fun resetDefaults() {
        assistantPrompt = AiPrompts.DEFAULT_ASSISTANT_PROMPT
        basePrompt = AiPrompts.DEFAULT_BASE_PROMPT
        profilePreamble = AiPrompts.DEFAULT_PROFILE_PREAMBLE
        extractionStrategy = ProfileExtractor.DEFAULT_STRATEGY
        weeklyReportStrategy = WeeklyReportMath.DEFAULT_STRATEGY
        imageHintStrategy = IMAGE_HINT_DEFAULT_STRATEGY
        agentChatEnabled = true
        agentChatStrategy = AGENT_CHAT_DEFAULT_STRATEGY
    }

    if (!loaded) {
        NeuSheetScaffold(title = "AI 提示词", onDismiss = onDismiss) {
            Spacer(Modifier.height(24.dp))
        }
        return
    }

    val assistantCustomized = assistantPrompt.trim() != AiPrompts.DEFAULT_ASSISTANT_PROMPT
    val baseCustomized = basePrompt.trim() != AiPrompts.DEFAULT_BASE_PROMPT
    val preambleCustomized = profilePreamble.trim() != AiPrompts.DEFAULT_PROFILE_PREAMBLE
    val extractionCustomized = extractionStrategy.trim() != ProfileExtractor.DEFAULT_STRATEGY
    val weeklyCustomized = weeklyReportStrategy.trim() != WeeklyReportMath.DEFAULT_STRATEGY
    val imageCustomized = imageHintStrategy.trim() != IMAGE_HINT_DEFAULT_STRATEGY
    val agentChatCustomized = agentChatStrategy.trim() != AGENT_CHAT_DEFAULT_STRATEGY

    NeuSheetScaffold(title = "AI 提示词", onDismiss = onDismiss) {
        Spacer(Modifier.height(8.dp))

        SectionRow(
            title = "① 应用助手提示词",
            customized = assistantCustomized,
            expanded = openSection == "assistant",
        ) { toggle("assistant") }
        if (openSection == "assistant") {
            NeuTextField(
                value = assistantPrompt,
                onValueChange = { assistantPrompt = it },
                label = "应用助手提示词",
                singleLine = false,
                minLines = 4,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
        }

        SectionRow(
            title = "② 基础提示词",
            customized = baseCustomized,
            expanded = openSection == "base",
        ) { toggle("base") }
        if (openSection == "base") {
            NeuTextField(
                value = basePrompt,
                onValueChange = { basePrompt = it },
                label = "基础提示词（人设未填设定词时生效）",
                singleLine = false,
                minLines = 2,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
        }

        SectionRow(
            title = "③ 档案注入说明",
            customized = preambleCustomized,
            expanded = openSection == "preamble",
        ) { toggle("preamble") }
        if (openSection == "preamble") {
            NeuTextField(
                value = profilePreamble,
                onValueChange = { profilePreamble = it },
                label = "档案注入说明（档案进入对话的包装语）",
                singleLine = false,
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
        }

        SectionRow(
            title = "④ AI 洞察提炼策略",
            customized = extractionCustomized,
            expanded = openSection == "extraction",
        ) { toggle("extraction") }
        if (openSection == "extraction") {
            LayeredEditor(
                frameworkLabel = "框架（硬编码不可改：JSON schema + 输出格式）",
                framework = ProfileExtractor.FRAMEWORK,
                strategyLabel = "策略（可编：什么该记 / 什么不该记 / 数量指引）",
                strategy = extractionStrategy,
                customized = extractionCustomized,
                onStrategyChange = { extractionStrategy = it },
                onResetStrategy = { extractionStrategy = ProfileExtractor.DEFAULT_STRATEGY },
            )
            Spacer(Modifier.height(10.dp))
        }

        SectionRow(
            title = "⑤ AI 周报撰写策略",
            customized = weeklyCustomized,
            expanded = openSection == "weekly",
        ) { toggle("weekly") }
        if (openSection == "weekly") {
            LayeredEditor(
                frameworkLabel = "框架（硬编码不可改：输出格式约束）",
                framework = WeeklyReportMath.FRAMEWORK,
                strategyLabel = "策略（可编：字数 / 语气 / 结构）",
                strategy = weeklyReportStrategy,
                customized = weeklyCustomized,
                onStrategyChange = { weeklyReportStrategy = it },
                onResetStrategy = { weeklyReportStrategy = WeeklyReportMath.DEFAULT_STRATEGY },
            )
            Spacer(Modifier.height(10.dp))
        }

        SectionRow(
            title = "⑥ 生图触发策略（仅生图接口配齐时注入）",
            customized = imageCustomized,
            expanded = openSection == "image",
        ) { toggle("image") }
        if (openSection == "image") {
            LayeredEditor(
                frameworkLabel = "框架（硬编码不可改：[生图:...] 标记格式）",
                framework = IMAGE_HINT_FRAMEWORK,
                strategyLabel = "策略（可编：何时触发 / 如何写提示词）",
                strategy = imageHintStrategy,
                customized = imageCustomized,
                onStrategyChange = { imageHintStrategy = it },
                onResetStrategy = { imageHintStrategy = IMAGE_HINT_DEFAULT_STRATEGY },
            )
            Spacer(Modifier.height(10.dp))
        }

        SectionRow(
            title = "⑦ 手机操控（无障碍服务开启时生效）",
            customized = agentChatCustomized,
            expanded = openSection == "agent",
            trailing = {
                NeuSwitch(checked = agentChatEnabled, onToggle = { agentChatEnabled = it })
            },
        ) { toggle("agent") }
        if (openSection == "agent") {
            Text(
                "允许 AI 在对话中直接操控手机 · 发起即执行，无确认；白名单与悬浮停止仍生效",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = TextSecondary,
            )
            Spacer(Modifier.height(10.dp))
            LayeredEditor(
                frameworkLabel = "框架（硬编码不可改：[操作手机:...] 标记格式）",
                framework = AGENT_CHAT_HINT_FRAMEWORK,
                strategyLabel = "策略（可编：何时触发 / 目标怎么写）",
                strategy = agentChatStrategy,
                customized = agentChatCustomized,
                onStrategyChange = { agentChatStrategy = it },
                onResetStrategy = { agentChatStrategy = AGENT_CHAT_DEFAULT_STRATEGY },
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            NeuTextButton("全部恢复默认", onClick = { resetDefaults() })
            Spacer(Modifier.weight(1f))
            NeuButton(
                text = "保存",
                onClick = { save() },
                kind = NeuButtonKind.Primary,
                modifier = Modifier.width(140.dp),
            )
        }

        // ===== 生成逻辑说明：收进折叠行，需要时再展开 =====
        SectionRow(
            title = "生成逻辑说明（洞察 / 周报何时触发）",
            expanded = openSection == "logic",
        ) { toggle("logic") }
        if (openSection == "logic") {
            Text(insightLogicText, fontSize = 12.sp, lineHeight = 19.sp, color = MutedText)
            Spacer(Modifier.height(12.dp))
            Text(reportLogicText, fontSize = 12.sp, lineHeight = 19.sp, color = MutedText)
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * 手风琴段行：标题 + 「已自定义」徽标 + 可选尾部控件（⑦ 的总开关）+ 展开箭头。
 * 点击整行切换展开态；箭头只是状态指示，不是独立按钮。
 */
@Composable
private fun SectionRow(
    title: String,
    expanded: Boolean,
    customized: Boolean = false,
    trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        if (customized) {
            Spacer(Modifier.width(8.dp))
            Text("已自定义", fontSize = 10.sp, color = TextSecondary, letterSpacing = 0.4.sp)
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = if (expanded) MIcons.ChevronUp else MIcons.ChevronDown,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MutedText,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * 分层编辑区（展开态内容）：框架只读凹陷面 + 策略可编输入框。
 * 「本段恢复默认」只在改过（≠默认值）时显示，没改过的段不放空按钮。
 */
@Composable
private fun LayeredEditor(
    frameworkLabel: String,
    framework: String,
    strategyLabel: String,
    strategy: String,
    customized: Boolean,
    onStrategyChange: (String) -> Unit,
    onResetStrategy: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(frameworkLabel, fontSize = 10.sp, color = TextSecondary, letterSpacing = 0.4.sp)
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
        Text(strategyLabel, fontSize = 10.sp, color = TextSecondary, letterSpacing = 0.4.sp)
        Spacer(Modifier.height(4.dp))
        NeuTextField(
            value = strategy,
            onValueChange = onStrategyChange,
            label = "策略",
            singleLine = false,
            minLines = 4,
            maxLines = 12,
            modifier = Modifier.fillMaxWidth(),
        )
        if (customized) {
            Spacer(Modifier.height(4.dp))
            NeuTextButton("本段恢复默认", onClick = onResetStrategy)
        }
    }
}
