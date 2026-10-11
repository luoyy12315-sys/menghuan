package com.menghuan.app.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.menghuan.app.MainActivity

/**
 * Agent 保活前台服务（默认开，「自动执行」弹层里可关）：
 * 痛点「每次操作手机都要去设置里重开无障碍」的根因是全工程零保活——无障碍绑定跟着进程走，
 * 国产 ROM 一杀后台，绑定就没了。挂一个低重要度常驻通知的前台服务把进程钉住。
 * - 只在 Agent 真正跑起来后才启动（AgentLoop.run 前置检查通过后 ensureStarted），不骚扰没用过的人
 * - targetSdk 35 强制要求：Manifest 声明 FOREGROUND_SERVICE(_SPECIAL_USE) + foregroundServiceType="specialUse"
 * - Android 12+ 后台起前台服务会被系统拒（ForegroundServiceStartNotAllowedException），
 *   所以所有调用点都包 runCatching；startForeground 失败立即 stopSelf，不残留半死不活的服务
 */
class AgentKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess
        if (!ok) {
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "自动执行保活", NotificationManager.IMPORTANCE_MIN).apply {
                description = "保持无障碍服务连接，避免每次用 Agent 都要重开（静默通知，可长按隐藏）"
                setShowBadge(false)
            },
        )
        val home = Intent(Intent.ACTION_VIEW, Uri.parse("menghuan://open/tasks"), this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this, NOTIFICATION_ID, home,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("自动执行保活中")
            .setContentText("保持无障碍连接，随时可在「自动执行」里关闭")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "agent_keep_alive"
        private const val NOTIFICATION_ID = 2_000_000_005

        @Volatile
        var running = false
            private set

        /** 幂等启动（已在跑则什么都不做）。调用方自己包 runCatching 也行，这里再兜一层。 */
        fun ensureStarted(context: Context) {
            if (running) return
            runCatching {
                context.startForegroundService(Intent(context, AgentKeepAliveService::class.java))
            }
        }

        /** 关闭入口（「自动执行」弹层的保活开关关掉时调） */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentKeepAliveService::class.java)) }
            running = false
        }
    }
}
