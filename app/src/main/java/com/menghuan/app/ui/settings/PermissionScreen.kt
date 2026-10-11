package com.menghuan.app.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.menghuan.app.agent.AgentAccessibilityService
import com.menghuan.app.data.AiCall
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.common.ReminderPermissionHints
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary

/** 一条权限：叫什么、现在开没开、是不是必开、去哪开 */
private data class PermItem(
    val name: String,
    val granted: Boolean,
    val required: Boolean,
    val jump: (Context) -> Unit,
)

/**
 * 「设置 → 权限」（v0.53.8，v0.54.4 改版）。
 *
 * 把应用**真正会用到**的授权集中到一处，每项只显示**权限名 + 状态 + 去开启**：
 * 用户明确要求「不需要解释性文字，只用显示通知、麦克风什么的，不需要卡片展示，变成一条条的」。
 * 所以这里是**一行行的纯列表**（无卡片、无用途说明、无汇总文案）。
 *
 * 从系统设置回来会自动刷新状态（监听 ON_RESUME），不用退出重进。
 *
 * 说明：`INTERNET` / `RECEIVE_BOOT_COMPLETED` 是安装即授予的普通权限，不列。
 */
@Composable
fun PermissionScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // 从系统设置页面返回时自动重查（否则用户开完权限回来还显示"未开启"）
    var tick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val items = remember(tick, context) { buildPermissionItems(context) }

    SettingsPage(title = "权限", onBack = onBack) {
        Spacer(Modifier.height(10.dp))
        items.forEach { item ->
            PermissionRow(item)
        }
        Spacer(Modifier.height(20.dp))
    }
}

/** 一行权限：名称 + 状态（未开时右边一颗「去开启」），无卡片、无说明小字 */
@Composable
private fun PermissionRow(item: PermItem) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.name,
            fontSize = 15.sp,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (item.granted) "已开启" else if (item.required) "未开启 · 必开" else "未开启 · 可选",
            fontSize = 12.sp,
            color = if (item.granted) MutedText else NeuDanger,
        )
        if (!item.granted) {
            Spacer(Modifier.width(10.dp))
            NeuTextButton("去开启", onClick = { jumpSafely(context, item.jump) }, color = TextPrimary)
        }
    }
}

/** 给「设置」首页用的汇总：缺几项必开、缺几项可选（同一套检测，避免两处逻辑漂移） */
fun permissionSummary(context: Context): Pair<Int, Int> {
    val items = buildPermissionItems(context)
    return items.count { !it.granted && it.required } to items.count { !it.granted && !it.required }
}

/** 跳系统设置：任何一家 ROM 缺这个 Activity 都不能崩，兜底去应用详情页 */
private fun jumpSafely(context: Context, jump: (Context) -> Unit) {
    runCatching { jump(context) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }
}

private fun buildPermissionItems(context: Context): List<PermItem> {
    val pkg = context.packageName
    val pkgUri = Uri.parse("package:$pkg")

    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    return listOf(
        PermItem(
            name = "通知",
            granted = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            required = true,
            jump = { ctx ->
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        ),
        PermItem(
            name = "麦克风",
            granted = granted(Manifest.permission.RECORD_AUDIO),
            required = true,
            jump = { ctx ->
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        ),
        PermItem(
            name = "相机",
            granted = granted(Manifest.permission.CAMERA),
            required = false,
            jump = { ctx ->
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        ),
        PermItem(
            name = "精确闹钟",
            granted = Build.VERSION.SDK_INT < 31 ||
                (context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)
                    ?.canScheduleExactAlarms() == true,
            required = false,
            jump = { ctx ->
                ctx.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        ),
        // v0.97.0（审查 B35）：国产 ROM 上"允许后台活动"没给的话，后台闹钟与 goAsync
        // 网络生成会被冻结 —— 现象是"提醒偶发不响 / 主动关心偶发不发"，用户查不到原因。
        // 跳的是系统**整个**电池优化列表（不是"一键把自己加白"那条直通车）：
        // 直通车要在清单里声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，属于应用市场敏感项，
        // 为一个引导付出那个代价不值（理由同 ReminderPermissionHints.openBatteryOptimizationSettings）。
        PermItem(
            name = "电池优化白名单",
            granted = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.isIgnoringBatteryOptimizations(pkg) == true,
            required = false,
            jump = { ctx -> ReminderPermissionHints.openBatteryOptimizationSettings(ctx) },
        ),
        PermItem(
            name = "全屏通知（锁屏来电）",
            granted = AiCall.canUseFullScreenIntent(context),
            required = false,
            jump = { ctx ->
                ctx.startActivity(
                    Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", pkgUri)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        ),
        PermItem(
            name = "手机操控（无障碍）",
            granted = AgentAccessibilityService.isEnabledInSettings(context),
            required = false,
            jump = { ctx ->
                ctx.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        ),
    )
}
