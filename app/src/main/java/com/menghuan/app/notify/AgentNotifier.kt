package com.menghuan.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.menghuan.app.MainActivity
import com.menghuan.app.agent.AgentRunner

/**
 * Agent 执行进度通知（v0.38.0）：
 * 执行期间一条**低重要度常驻通知**实时显示第几步——Agent 会切走前台去操作别的应用，
 * 用户不在聊天页也能看到进展，随时点通知回到发起会话。
 * 由 MenghuanApp 的应用级收集器驱动（订阅 AgentRunner.state + 开关 flow），本类只管渲染。
 */
object AgentNotifier {

    private const val CHANNEL_ID = "agent_progress"
    private const val NOTIFICATION_ID = 2_000_000_004

    /**
     * @param state AgentRunner 状态；Running 且 [enabled] 才展示，其余一律撤掉
     * @param personaId 发起会话（null = 任务页手动发起 → 点通知回任务页）
     */
    fun update(context: Context, state: AgentRunner.State, personaId: Long?, enabled: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (state !is AgentRunner.State.Running || !enabled) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "手机操作进行中", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Agent 自动执行期间显示实时步骤（静默，可随时停止）"
                setShowBadge(false)
            }
        )
        val text = state.logs.lastOrNull() ?: state.goal
        // 对话发起 → 回到该会话；任务页发起 → 回任务页。
        // getActivities 先 home 后 chat：返回键回到首页而不是直接退出
        val home = Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://open/chatList"), context, MainActivity::class.java)
        val target = if (personaId != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://chat/$personaId"), context, MainActivity::class.java)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://open/tasks"), context, MainActivity::class.java)
        }
        val contentIntent = PendingIntent.getActivities(
            context,
            NOTIFICATION_ID,
            arrayOf(home, target),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("正在操作手机")
            .setContentText(text.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(200)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}
