package com.menghuan.app.ui.settings

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.menghuan.app.agent.AgentAccessibilityService
import com.menghuan.app.data.AiCall
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuCard
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary

/** 一条权限：叫什么、干什么用、现在开没开、去哪开 */
private data class PermItem(
    val name: String,
    val reason: String,
    val granted: Boolean,
    val required: Boolean,
    val jump: (Context) -> Unit,
)

/**
 * 「设置 → 权限」（v0.53.8）。
 *
 * 把应用**真正会用到**的授权集中到一处：每项写清**名称 / 用途 / 当前状态**，并给一个
 * **直达系统对应页面**的按钮。顶部汇总"还有几项没开"——
 * 因为这些权限缺了之后，功能往往是**静默失效**（打电话不响、提醒不准点），
 * 用户在别处根本看不出原因。
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
    val missingRequired = items.filter { !it.granted && it.required }
    val missingOptional = items.filter { !it.granted && !it.required }

    SettingsPage(title = "权限", onBack = onBack) {
        Spacer(Modifier.height(14.dp))

        // 顶部汇总：缺必开项时红字提醒，否则给一句"都齐了"
        if (missingRequired.isNotEmpty()) {
            Text(
                "⚠️ 还有 ${missingRequired.size} 项必开的权限没给：" +
                    missingRequired.joinToString("、") { it.name } +
                    "。缺了它们，对应功能会**静默失效**（比如叫 AI 打电话它一声不响、任务提醒不准点），" +
                    "界面上不会有任何报错。",
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = NeuDanger,
            )
        } else if (missingOptional.isEmpty()) {
            Text("全部权限都已开启 ✓", fontSize = 12.5.sp, color = MutedText)
        } else {
            Text(
                "必开的权限都齐了；还有 ${missingOptional.size} 项可选的没开（不影响主要功能）。",
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = MutedText,
            )
        }

        Spacer(Modifier.height(16.dp))
        items.forEach { item ->
            PermissionCard(item)
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "点「去开启」会跳到系统设置里对应的那一页；开完回来这里会自动刷新状态。",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = MutedText,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** 一项权限的卡片：名称 + 状态 + 用途 + 「去开启」 */
@Composable
private fun PermissionCard(item: PermItem) {
    val context = LocalContext.current
    NeuCard(cornerRadius = 18.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (item.granted) "已开启" else if (item.required) "未开启 · 必开" else "未开启 · 可选",
                    fontSize = 11.5.sp,
                    fontWeight = if (item.granted) FontWeight.Normal else FontWeight.Medium,
                    color = if (item.granted) MutedText else NeuDanger,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(item.reason, fontSize = 12.sp, lineHeight = 18.sp, color = MutedText)
            if (!item.granted) {
                Spacer(Modifier.height(10.dp))
                NeuButton(
                    text = "去开启",
                    kind = NeuButtonKind.Secondary,
                    onClick = { jumpSafely(context, item.jump) },
                )
            }
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
            reason = "任务到点提醒、AI 打进来的来电、定时主动消息、人设主动关心 —— 全靠通知送达。" +
                "没它这些功能会一声不响。",
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
            reason = "语音通话、聊天里「按住说话」、记账的语音录入都用它。",
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
            reason = "App 内自带的拍摄界面（加号面板 → 拍摄）用它拍照发图。",
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
            reason = "任务到点提醒、定时主动消息、AI 定时打电话要靠它**准点**触发。" +
                "没开也能用，但系统可能把时间往后推（晚几分钟到十几分钟）。",
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
        PermItem(
            name = "全屏通知（锁屏来电）",
            reason = "AI 打进来时，在锁屏上直接铺满整个来电界面（像真电话）。" +
                "没开的话只剩一条通知 + 铃声，点一下才进通话。",
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
            reason = "让 AI 在对话里**替你点屏幕**（读屏 + 手势）。不开只是用不了这个能力，" +
                "聊天、通话、提醒都不受影响。",
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
