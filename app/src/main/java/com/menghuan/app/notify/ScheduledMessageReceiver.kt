package com.menghuan.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.AiCallLogic
import com.menghuan.app.data.ScheduledMessageLogic
import com.menghuan.app.data.ScheduledMessageSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「定时主动消息」的闹钟落点（v0.53.5）。
 *
 * 每条消息一个闹钟（requestCode = id 的 hashCode），到点：
 * ① 复查这条还在不在、该不该发（今天没发过 + 已过设定时刻）；
 * ② 交给 [ScheduledMessageSender] 生成并落库（联网搜索也在这里做）；
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
                    ScheduledMessageSender.send(context, app.db, settings, message)
                }

                // 明天接着来（哪怕这次没发成，调度不能断）
                if (message.enabled) {
                    AlarmScheduler.scheduleScheduledMessage(context, id, message.time)
                }
            } catch (t: Throwable) {
                // 静默：一条定时消息出问题不该影响任何东西
            } finally {
                pending.finish()
            }
        }
    }
}
