package com.menghuan.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.AiCall
import com.menghuan.app.data.AiCallLogic
import com.menghuan.app.data.AiCallReason
import com.menghuan.app.data.KIND_SCHEDULED_CALL
import com.menghuan.app.data.ScheduledMessageLogic
import com.menghuan.app.data.ScheduledMessageSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「定时主动消息 / 定时电话」的闹钟落点（v0.53.5；v0.56.6 起同一条路也管定时电话）。
 *
 * 每条一个闹钟（requestCode = id 的 hashCode），到点：
 * ① 复查这条还在不在、该不该发（今天没发过 + 已过设定时刻）；
 * ② 按 [ScheduledMessage.kind] 分流：
 *    - `message` → 交给 [ScheduledMessageSender] 生成并落库（联网搜索也在这里做）；
 *    - `call` → 交给 [AiCall.ring] **直接打过来**（接通后说的内容就是那条里写的 instruction）；
 * ③ **自排明天**（进程被杀、手机重启都不会漏，BootReceiver 另有补排）。
 *
 * 与 AiCallReceiver 一样：`goAsync()` + IO 线程，避免在广播里做网络请求把主线程卡死。
 */
class ScheduledMessageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_SCHEDULED_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as? MenghuanApp ?: return@launch
                val settings = app.settingsRepository
                val message = settings.scheduledMessages.first().firstOrNull { it.id == id } ?: return@launch
                val now = System.currentTimeMillis()
                val today = java.time.LocalDate.now().toEpochDay()

                if (ScheduledMessageLogic.isDue(message, AiCallLogic.minuteOfDayAt(now), today)) {
                    if (message.kind == KIND_SCHEDULED_CALL) {
                        // 定时电话：直接由 TA 打过来；两个开关的判定在 AiCall.ring 里
                        AiCall.ring(
                            context = context,
                            db = app.db,
                            settings = settings,
                            personaId = message.personaId,
                            reason = AiCallReason.SCHEDULED,
                            instructionOverride = message.instruction,
                            byExplicitSchedule = true,
                        )
                    } else {
                        ScheduledMessageSender.send(context, app.db, settings, message)
                    }
                }

                // 明天接着来（哪怕这次没发成/没打通，调度不能断）
                if (message.enabled) {
                    AlarmScheduler.scheduleScheduledMessage(context, id, message.time)
                }
            } catch (t: Throwable) {
                // 静默：一条定时项出问题不该影响任何东西
            } finally {
                pending.finish()
            }
        }
    }
}
