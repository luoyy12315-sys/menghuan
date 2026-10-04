package com.menghuan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.menghuan.app.BuildConfig
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.DEFAULT_PROFILE_ID
import com.menghuan.app.data.DEFAULT_THEME_MODE
import com.menghuan.app.data.ImageApiConfig
import com.menghuan.app.ui.common.NeuChip
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * 共享设置面板：入口在「我的」页。
 * v0.45.0 收敛项：「人设管理」与「AI 提示词」**上提到「我的」页一级行卡**——
 * 人设管理此前在「我的」页与设置面板里各有一份（用户反馈"重复了"），
 * AI 提示词则从设置面板里提出来单独成行。设置面板现在只留
 * API 配置 / 外观 / 通知与提醒 / 数据与隐私 / 关于。
 *
 * v0.37.1：副标题从写死的静态提示改为**实时状态**——不用点进二级页，
 * 每行就能看清"现在配的是什么 / 开了几个"（生效接口、外观模式、通知开关数）。
 */
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    onApiConfig: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onDataPrivacy: () -> Unit = {},
    onAbout: () -> Unit = {},
    onAppearance: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as MenghuanApp

    // ===== 实时状态源（副标题用） =====
    val profiles by app.settingsRepository.apiProfiles.collectAsState(initial = emptyList())
    val activeProfileId by app.settingsRepository.activeProfileId
        .collectAsState(initial = DEFAULT_PROFILE_ID)
    val imgApi by app.settingsRepository.imageApiConfig.collectAsState(initial = ImageApiConfig())
    val themeModeName by app.settingsRepository.themeMode.collectAsState(initial = DEFAULT_THEME_MODE)
    val notifyOn by app.settingsRepository.notificationsEnabled.collectAsState(initial = true)
    val dailySummaryOn by app.settingsRepository.dailySummaryEnabled.collectAsState(initial = true)
    val weeklyNotifyOn by app.settingsRepository.weeklyReportNotifyEnabled.collectAsState(initial = false)

    val activeProfileName = profiles.firstOrNull { it.id == activeProfileId }?.name
    val notifyCount = listOf(notifyOn, dailySummaryOn, weeklyNotifyOn).count { it }

    val items = listOf(
        MIcons.Code to ("API 配置" to apiSubtitle(activeProfileName, profiles.size, imgApi.isComplete)),
        MIcons.Info to ("外观" to ThemeMode.fromName(themeModeName).label),
        MIcons.Bell to ("通知与提醒" to "已开启 $notifyCount/3 项"),
        MIcons.Shield to ("数据与隐私" to "导出 · 导入 · 清除数据"),
        MIcons.Info to ("关于" to "版本 ${BuildConfig.VERSION_NAME}"),
    )
    val actions: Map<Int, () -> Unit> = mapOf(
        0 to onApiConfig,
        1 to onAppearance,
        2 to onNotifications,
        3 to onDataPrivacy,
        4 to onAbout,
    )
    NeuSheetScaffold(title = "设置", onDismiss = onDismiss) {
        Spacer(Modifier.height(14.dp))
        items.forEachIndexed { index, (icon, label) ->
            NeuSheetRow(
                icon = icon,
                title = label.first,
                subtitle = label.second,
                onClick = { actions[index]?.invoke() },
            )
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** API 配置副标题：生效接口名 + 生图接口状态，一眼看清聊天用的是谁 */
private fun apiSubtitle(activeName: String?, profileCount: Int, imageReady: Boolean): String = when {
    profileCount == 0 -> "未配置 · AI 暂不可用"
    else -> buildString {
        append("当前：").append(activeName ?: "默认接口")
        append(" · 生图").append(if (imageReady) "已配置" else "未配置")
    }
}

/**
 * 外观弹层：主题切换的唯一入口（v0.35.0）。
 * 之前把三选一直接摊在设置面板底部，与列表里的「外观」项重复，现已收敛到这里。
 */
@Composable
fun AppearanceSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MenghuanApp
    val scope = rememberCoroutineScope()
    val themeMode by app.settingsRepository.themeMode
        .collectAsState(initial = com.menghuan.app.data.DEFAULT_THEME_MODE)
    val current = ThemeMode.fromName(themeMode)

    NeuSheetScaffold(title = "外观", onDismiss = onDismiss) {
        Spacer(Modifier.height(8.dp))
        // 只留三个胶囊就够了：选中态 chip 本身就是状态，不用再摆一行"当前值"重复表达
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                NeuChip(text = mode.label, selected = current == mode) {
                    scope.launch { app.settingsRepository.setThemeMode(mode.name) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
}