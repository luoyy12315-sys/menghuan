package com.menghuan.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.menghuan.app.data.AiCall
import com.menghuan.app.data.AiCallLogic
import com.menghuan.app.data.AiCallRule
import com.menghuan.app.data.DEFAULT_AI_CALL_TIME
import com.menghuan.app.notify.AlarmScheduler
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSwitch
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * 「AI 主动打电话」设置页（v0.53.6：**按人设独立配置**）。
 *
 * 交互流程：
 * ① 最上面一个**总开关**（默认关）—— 关着时下面什么都不显示，也真的一通都不打；
 * ② 打开后**展开 AI 人设列表**，每个 AI 一行，行上直接显示它的规则摘要
 *    （`每天 21:00 · 3 天没聊` 或 `未开启`）；
 * ③ 点某一行**手风琴展开**（单开、再点收起）它的三项配置：
 *    **是否允许 TA 主动打电话 / 每天几点 / 多久没聊才打**；
 * ④ 改哪一项立刻落盘并**只重排这一个人设的闹钟** —— 别人设的调度一根毛都不动。
 *
 * 每个 AI 独立的关键在数据层：规则按 `personaId` 存（`ai_call_rules`），
 * 闹钟也按 `personaId` 派生 requestCode，判定时看的是"跟**这个**人设的最后一次聊天"。
 */
@Composable
fun AiCallSettingsScreen(onBack: () -> Unit) {
    val app = settingsContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val masterEnabled by app.settingsRepository.aiCallEnabled.collectAsState(initial = false)
    val rules by app.settingsRepository.aiCallRules.collectAsState(initial = emptyList())
    val personas by app.personaRepository.observePersonas().collectAsState(initial = emptyList())

    /** 手风琴：同时只展开一个（项目里长设置面板的既定做法） */
    var expandedId by remember { mutableStateOf(0L) }
    var fullScreenOk by remember { mutableStateOf(AiCall.canUseFullScreenIntent(context)) }

    // v0.53.8：**AI 打电话全靠通知弹出来** —— 通知权限没给，它一声不响、界面上也没任何反馈。
    // 用户踩过"我叫它打，它没打过来"，根因之一就是这个（以前只有任务提醒页会申请通知权限）。
    var notifGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> notifGranted = granted }

    fun ruleOf(personaId: Long): AiCallRule? = rules.firstOrNull { it.personaId == personaId }

    /** 落盘 + 只重排这一个人设的闹钟 */
    fun applyRule(rule: AiCallRule) {
        scope.launch {
            app.settingsRepository.upsertAiCallRule(rule)
            if (masterEnabled && rule.enabled) {
                AlarmScheduler.scheduleAiCallForPersona(context, rule.personaId, rule.time)
            } else {
                AlarmScheduler.cancelAiCallForPersona(context, rule.personaId)
            }
        }
    }

    SettingsPage(title = "AI 主动打电话", onBack = onBack) {
        Spacer(Modifier.height(16.dp))

        // 通知权限（缺了就真的什么都看不到，所以放在最上面、最显眼）
        if (!notifGranted) {
            Text(
                "⚠️ 通知权限没开 —— AI 打过来不会有任何反应（来电是靠通知弹出来的）",
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = NeuDanger,
            )
            Spacer(Modifier.height(4.dp))
            NeuTextButton(
                "授予通知权限",
                onClick = { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) },
            )
            Spacer(Modifier.height(14.dp))
        }

        // ① 总开关
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("允许 AI 主动打电话", fontSize = 15.sp, color = TextPrimary)
            NeuSwitch(checked = masterEnabled, onToggle = { on ->
                scope.launch {
                    app.settingsRepository.setAiCallEnabled(on)
                    if (on) {
                        AiCall.rearm(app.settingsRepository, context)
                    } else {
                        // 总开关关掉：所有人的闹钟一并撤掉
                        rules.forEach { AlarmScheduler.cancelAiCallForPersona(context, it.personaId) }
                    }
                }
            })
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "打开后可以给**每个 AI 单独**设规则，互相独立、互不影响。\n" +
                "到点会看「多久没跟 TA 说话了」：够久了才打，刚聊过就不打扰。\n" +
                "22:30–08:00 一律不打；每个 AI 每天最多一通；没接会留一句话给你。",
            fontSize = 12.sp,
            lineHeight = 19.sp,
            color = MutedText,
        )

        if (masterEnabled) {
            Spacer(Modifier.height(20.dp))
            Text("每个 AI 的规则", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextPrimary, letterSpacing = 0.6.sp)
            Spacer(Modifier.height(10.dp))

            if (personas.isEmpty()) {
                Text("还没有 AI 人设", fontSize = 13.sp, color = MutedText)
            }

            personas.forEach { persona ->
                val rule = ruleOf(persona.id)
                val on = rule?.enabled == true
                NeuSheetRow(
                    icon = MIcons.Phone,
                    title = persona.name,
                    subtitle = if (on) {
                        "每天 ${rule?.time} · ${rule?.idleDays} 天没聊"
                    } else {
                        "未开启"
                    },
                    onClick = { expandedId = if (expandedId == persona.id) 0L else persona.id },
                )
                Spacer(Modifier.height(8.dp))

                if (expandedId == persona.id) {
                    // ③ 只属于这个人设的三项配置
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("允许 TA 主动给我打电话", fontSize = 14.sp, color = TextPrimary)
                            NeuSwitch(checked = on, onToggle = { want ->
                                applyRule(
                                    (rule ?: AiCallRule(personaId = persona.id)).copy(enabled = want),
                                )
                            })
                        }

                        if (on) {
                            val current = rule ?: AiCallRule(personaId = persona.id)
                            // 时间：本地草稿 + 合法才落盘（避免打到一半就重排闹钟）
                            var timeDraft by remember(persona.id, current.time) { mutableStateOf(current.time) }
                            LaunchedEffect(current.time) { timeDraft = current.time }

                            Spacer(Modifier.height(14.dp))
                            NeuTextField(
                                value = timeDraft,
                                onValueChange = { v ->
                                    timeDraft = v
                                    if (AiCallLogic.minuteOfDayOf(v) != null) {
                                        applyRule(current.copy(time = v))
                                    }
                                },
                                label = "每天几点检查（HH:mm）",
                                placeholder = DEFAULT_AI_CALL_TIME,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            Spacer(Modifier.height(14.dp))
                            Text("距离上次聊天多久没交流才打", fontSize = 13.sp, color = TextPrimary)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(1, 3, 7, 30).forEach { d ->
                                    NeuChip(text = "$d 天", selected = current.idleDays == d) {
                                        applyRule(current.copy(idleDays = d))
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "到点时会比较「距上次跟 ${persona.name} 聊天」的天数：不到这个数就不打。\n" +
                                    "想让它每天到点必打，选 1 天即可。",
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = MutedText,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            // 全屏来电权限（Android 14+ 要手动允许）
            if (Build.VERSION.SDK_INT >= 34) {
                Spacer(Modifier.height(20.dp))
                Text(
                    if (fullScreenOk) "全屏来电：已允许（锁屏上会直接弹来电界面）"
                    else "全屏来电：未允许 —— 现在只能发通知 + 响铃，点一下才进通话",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = if (fullScreenOk) MutedText else NeuDanger,
                )
                if (!fullScreenOk) {
                    Spacer(Modifier.height(4.dp))
                    NeuTextButton("去系统设置里允许「全屏通知」", onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT",
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                        fullScreenOk = AiCall.canUseFullScreenIntent(context)
                    })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
