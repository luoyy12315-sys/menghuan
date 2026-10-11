package com.menghuan.app.ui.common

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 提醒类权限的**主动引导**（v0.97.0，审查 B34 / B35 / B36）。
 *
 * ## 这三个问题原本长什么样
 * - **B34**：13/14+ 上 `SCHEDULE_EXACT_ALARM` 默认**不授予**，`AlarmScheduler` 会静默回落到
 *   `setAndAllowWhileIdle`（可能晚几分钟）。`权限` 页里虽然列了这一条，但**从不主动引导** ——
 *   用户不翻到那一页就永远不知道自己的提醒一直在被系统糊弄。
 * - **B35**：没有电池优化白名单引导，国产 ROM 上后台闹钟与 `goAsync` 网络生成会被冻结。
 * - **B36**：通知权限没开时所有提醒**静默丢弃**，只有任务页申请过一次。
 *
 * ## 为什么是"用户主动设提醒时提示"而不是"进 App 就提示"
 * `AlarmScheduler` 的调用点里有一大半是**开机 / 每天定时**重排（`BootReceiver`、
 * `TaskReminderReceiver`），在那里提示等于开机弹一条 —— 用户什么都没做就被念。
 * 所以引导只在**用户自己刚设了一个提醒**之后调 [warnAfterScheduling] 一次，
 * 并且用带动作按钮的轻反馈承载，不打断任何操作、不落盘"提示过了"。
 */
object ReminderPermissionHints {

    /** 通知权限是否可用（33 以下恒 true：那时候没有这个运行时权限） */
    fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 能不能排**精确**闹钟（31 以下恒 true） */
    fun exactAlarmAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                ?.canScheduleExactAlarms() == true

    /**
     * 用户刚设完一个提醒 → 缺什么提示什么。
     *
     * 通知权限优先：没有它，后面精确不精确都无所谓（提醒根本不会出现）。
     */
    fun warnAfterScheduling(context: Context) {
        val app = context.applicationContext
        when {
            !notificationsAllowed(app) -> AppToast.show(
                "通知权限没开，到点的提醒不会响",
                danger = true,
                actionLabel = "去开启",
            ) { openNotificationSettings(app) }

            !exactAlarmAllowed(app) -> AppToast.show(
                "精确闹钟未开启，提醒可能晚几分钟",
                danger = false,
                actionLabel = "去开启",
            ) { openExactAlarmSettings(app) }
        }
    }

    fun openNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppDetails(context) }
    }

    fun openExactAlarmSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppDetails(context) }
    }

    /**
     * 电池优化（B35）。
     *
     * ⚠️ 这里**故意**不申请 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、也不用
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（那是"一键把自己加白"的直通车）：
     * 那条直通车要求清单里声明该权限，而它在应用市场是敏感项（容易被判定为滥用）。
     * 打开系统那**一整个白名单列表**达成同样的目的，代价只是用户要多点一下找到「梦欢」——
     * 而这个引导本身就会告诉他去找谁，够用了。
     */
    fun openBatteryOptimizationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openAppDetails(context) }
    }

    private fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
