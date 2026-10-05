package com.menghuan.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.AiCall
import com.menghuan.app.data.AiCallLogic
import com.menghuan.app.data.AiCallReason
import com.menghuan.app.data.AiCallRuleLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 「AI 主动打电话」的闹钟落点（v0.53.6 起**按人设**）。
 *
 * 每个人设一个闹钟（requestCode 由 personaId 派生），到点只为**那一个人**做判定：
 * - 那个人没配规则、或规则关着 → 只有这个人不打（**不影响别人**）；
 * - 否则按它的 `idleDays` 看"距**跟 TA 的**最后一次聊天"够不够久，够了才打；
 * - 打完/没打都**自排明天**（调度不能断）。
 *
 * v0.54.5：**总开关已删** —— 设置里不再有「AI 主动打电话」页，开关就是各角色自己的规则
 * （会话设置页的「允许主动打电话」卡片）。
 *
 * 口头约定那一路（`once`）也带 personaId：用户让谁打就谁打，不看规则。
 *
 * ⚠️ 这里只做规则判定，不调 LLM：开场白生成在 [AiCall.ring] 里（网络请求），
 * 所以整段走 `goAsync()` + IO 线程。
 */
class AiCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val personaId = intent.getLongExtra(AlarmScheduler.EXTRA_AI_CALL_PERSONA, 0L)
        val once = intent.getBooleanExtra(AlarmScheduler.EXTRA_AI_CALL_ONCE, false)
        if (personaId <= 0L) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? MenghuanApp ?: return@launch
                val settings = app.settingsRepository

                // 总开关已在 v0.54.5 删掉（设置里不再有「AI 主动打电话」页）：开关就是
                // **各角色自己的 rule.enabled**（会话设置页的卡片）。
                // ⚠️ **用户在聊天里点名要求的那一通不受规则限制**（reason = REQUESTED），
                // 所以这里只拦非 once 的两路（用户踩过："我叫他打，他没打过来"）。
                val requested = once
                val rule = settings.aiCallRules.first().firstOrNull { it.personaId == personaId }

                val now = System.currentTimeMillis()
                val today = LocalDate.now().toEpochDay()

                if (requested) {
                    // 口头约定：用户明确要求的，直接打（不看规则、不看静默、不算次数）
                    AiCall.ring(context, app.db, settings, personaId, AiCallReason.REQUESTED)
                    return@launch
                }

                // 每天那一路：先把明天的排上（今天打不打，调度都不能断）
                if (rule != null && rule.enabled) {
                    AlarmScheduler.scheduleAiCallForPersona(context, personaId, rule.time)
                }
                if (rule == null || !rule.enabled) return@launch

                // ⚠️ 关键：看的是**跟这个人设**的最后一次聊天，不是全局最近一条
                val lastChatAt = runCatching {
                    app.db.messageDao().observeConversation(personaId).first()
                        .lastOrNull()?.timestamp ?: 0L
                }.getOrDefault(0L)

                if (!AiCallRuleLogic.shouldRing(rule, AiCallLogic.minuteOfDayAt(now), today, lastChatAt, now)) {
                    return@launch
                }

                AiCall.ring(context, app.db, settings, personaId, AiCallReason.SCHEDULED)
            } catch (t: Throwable) {
                // 静默：一次没打出去不该影响任何东西
            } finally {
                pending.finish()
            }
        }
    }
}
