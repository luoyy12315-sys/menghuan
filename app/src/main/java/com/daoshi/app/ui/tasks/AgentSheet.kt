package com.daoshi.app.ui.tasks

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoshi.app.MentorApp
import com.daoshi.app.agent.AgentAccessibilityService
import com.daoshi.app.agent.AgentRunner
import com.daoshi.app.ui.common.NeuButton
import com.daoshi.app.ui.common.NeuButtonKind
import com.daoshi.app.ui.common.NeuMuted
import com.daoshi.app.ui.common.NeuSheetScaffold
import com.daoshi.app.ui.common.NeuSwitch
import com.daoshi.app.ui.common.NeuTextField
import com.daoshi.app.ui.theme.FaintText
import com.daoshi.app.ui.theme.MutedText
import com.daoshi.app.ui.theme.NeuAccent
import com.daoshi.app.ui.theme.NeuDanger
import com.daoshi.app.ui.theme.TextPrimary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「自动执行」弹层（任务页顶栏 Play 进入）：输入目标 → AgentLoop 逐步执行并实时显示日志。
 * 安全围栏：无障碍服务引导、应用白名单（空 = 不限制）、敏感动作确认、步数上限、悬浮停止。
 * 完成后摘要写入主导师会话（进入 LLM 历史）。
 */
@Composable
fun AgentSheet(vm: TasksViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MentorApp
    val scope = rememberCoroutineScope()
    val state by AgentRunner.state.collectAsState()
    var goal by remember { mutableStateOf("") }
    var whitelist by remember { mutableStateOf("") }
    var sensitiveConfirm by remember { mutableStateOf(true) }
    var serviceEnabled by remember { mutableStateOf(AgentAccessibilityService.isRunning()) }

    LaunchedEffect(Unit) {
        runCatching {
            val config = app.settingsRepository.agentConfig.first()
            whitelist = config.whitelist.joinToString(",")
            sensitiveConfirm = config.sensitiveConfirm
        }
    }
    // 运行中窗口可能变化：轮询服务连接态（无障碍设置返回后能刷新）
    LaunchedEffect(state) {
        serviceEnabled = AgentAccessibilityService.isRunning() ||
            AgentAccessibilityService.isEnabledInSettings(context)
    }

    NeuSheetScaffold(title = "自动执行", onDismiss = {
        if (state !is AgentRunner.State.Running) onDismiss()
    }) {
        Spacer(Modifier.height(14.dp))
        when (val s = state) {
            is AgentRunner.State.Idle -> {
                NeuTextField(
                    value = goal,
                    onValueChange = { goal = it },
                    label = "目标",
                    placeholder = "例：打开备忘录新建一条「明天交稿」",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                NeuTextField(
                    value = whitelist,
                    onValueChange = { whitelist = it },
                    label = "应用白名单（包名，逗号分隔；留空不限制）",
                    placeholder = "com.android.calendar, com.example.app",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("敏感操作需确认", fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                    NeuSwitch(checked = sensitiveConfirm, onToggle = { sensitiveConfirm = it })
                }
                if (!serviceEnabled) {
                    Spacer(Modifier.height(14.dp))
                    NeuButton(
                        text = "去开启无障碍服务",
                        kind = NeuButtonKind.Secondary,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                }
                Spacer(Modifier.height(18.dp))
                NeuButton(
                    text = "开始执行",
                    kind = NeuButtonKind.Primary,
                    enabled = goal.isNotBlank() && serviceEnabled,
                    onClick = {
                        scope.launch {
                            val packages = whitelist.split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                                .toSet()
                            app.settingsRepository.setAgentWhitelist(packages)
                            app.settingsRepository.setAgentSensitiveConfirm(sensitiveConfirm)
                            AgentRunner.start(vm.viewModelScope, app.settingsRepository, goal.trim())
                        }
                    },
                )
            }

            is AgentRunner.State.Running -> {
                Text("执行中…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    s.logs.forEach { line ->
                        Text(
                            line,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = if (line.startsWith("⛔") || line.startsWith("⚠️")) NeuDanger else MutedText,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                }
                s.waitingConfirm?.let { question ->
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().background(FaintText.copy(alpha = 0.12f), rounded()).padding(12.dp)) {
                        Column {
                            Text(question, fontSize = 13.sp, lineHeight = 20.sp, color = TextPrimary)
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth()) {
                                NeuButton("取消", onClick = { AgentRunner.answerConfirm(false) }, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(10.dp))
                                NeuButton("继续", onClick = { AgentRunner.answerConfirm(true) }, kind = NeuButtonKind.Primary, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                NeuButton(
                    text = "停止",
                    kind = NeuButtonKind.Danger,
                    onClick = { AgentRunner.stop() },
                )
            }

            is AgentRunner.State.Finished -> {
                Text(
                    if (s.success) "执行完成（${s.steps} 步）" else "未完成（${s.steps} 步）",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (s.success) NeuAccent else NeuDanger,
                )
                Spacer(Modifier.height(8.dp))
                Text(s.summary, fontSize = 14.sp, lineHeight = 21.sp, color = TextPrimary)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    NeuButton(
                        text = "关闭",
                        onClick = {
                            AgentRunner.reset()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    NeuButton(
                        text = "写入会话",
                        kind = NeuButtonKind.Primary,
                        onClick = {
                            val summary = "🤖 自动执行${if (s.success) "完成" else "未完成"}（${s.steps} 步）：${s.summary}"
                            vm.writeAgentSummary(summary)
                            AgentRunner.reset()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

private fun rounded() = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

