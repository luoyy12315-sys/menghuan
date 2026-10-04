package com.menghuan.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.menghuan.app.BuildConfig
import com.menghuan.app.data.AsrModelManager
import com.menghuan.app.data.DEFAULT_PROFILE_ID
import com.menghuan.app.data.DEFAULT_THEME_MODE
import com.menghuan.app.data.ImageApiConfig
import com.menghuan.app.ui.common.NeuSheetRow
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.ThemeMode

/**
 * 设置主页（v0.51.0 起是全屏页，不再是弹层）。
 *
 * v0.45.0 收敛项：「人设管理」与「AI 提示词」上提到「我的」页一级行卡，
 * 设置面板里不再放人设管理，避免与「我的」页重复。
 * v0.50.0：语音相关（两个开关 + 语音合成 + 识别模型）收进单独一个「语音」页，
 * 一级列表不因此变长。
 *
 * 副标题一律是**实时状态**（生效接口、外观模式、通知开关数、语音缺什么），
 * 不用点进二级页就能看清现在配的是什么。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val app = settingsContainer()

    val profiles by app.settingsRepository.apiProfiles.collectAsState(initial = emptyList())
    val activeProfileId by app.settingsRepository.activeProfileId
        .collectAsState(initial = DEFAULT_PROFILE_ID)
    val imgApi by app.settingsRepository.imageApiConfig.collectAsState(initial = ImageApiConfig())
    val themeModeName by app.settingsRepository.themeMode.collectAsState(initial = DEFAULT_THEME_MODE)
    val notifyOn by app.settingsRepository.notificationsEnabled.collectAsState(initial = true)
    val dailySummaryOn by app.settingsRepository.dailySummaryEnabled.collectAsState(initial = true)
    val weeklyNotifyOn by app.settingsRepository.weeklyReportNotifyEnabled.collectAsState(initial = false)
    val ttsConfig by app.settingsRepository.ttsConfig.collectAsState(
        initial = com.menghuan.app.data.TtsConfig(),
    )
    // 识别模型不随包（228MB 装后下载），这行要如实报缺什么；磁盘 stat 只在进页时读一次
    val asrReady = remember { AsrModelManager.isReady(app) }

    val activeProfileName = profiles.firstOrNull { it.id == activeProfileId }?.name
    val notifyCount = listOf(notifyOn, dailySummaryOn, weeklyNotifyOn).count { it }
    val voiceSubtitle = listOfNotNull(
        if (asrReady) null else "识别模型未下载",
        if (ttsConfig.isComplete) null else "语音合成未配置",
    ).joinToString(" · ").ifBlank { "已就绪" }

    val items = listOf(
        MIcons.Code to ("API 配置" to apiSubtitle(activeProfileName, profiles.size, imgApi.isComplete)),
        MIcons.VoiceWave to ("语音" to voiceSubtitle),
        MIcons.Info to ("外观" to ThemeMode.fromName(themeModeName).label),
        MIcons.Bell to ("通知与提醒" to "已开启 $notifyCount/3 项"),
        MIcons.Shield to ("数据与隐私" to "导出 · 导入 · 清除数据"),
        MIcons.Info to ("关于" to "版本 ${BuildConfig.VERSION_NAME}"),
    )
    val routes = listOf(
        SettingsRoutes.API_CONFIG,
        SettingsRoutes.VOICE,
        SettingsRoutes.APPEARANCE,
        SettingsRoutes.NOTIFICATIONS,
        SettingsRoutes.DATA_PRIVACY,
        SettingsRoutes.ABOUT,
    )

    SettingsPage(title = "设置", onBack = onBack) {
        Spacer(Modifier.height(14.dp))
        items.forEachIndexed { index, (icon, label) ->
            NeuSheetRow(
                icon = icon,
                title = label.first,
                subtitle = label.second,
                onClick = { onOpen(routes[index]) },
            )
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * 设置链的路由名（MainActivity 注册、各页跳转都引这里，避免字符串字面量散落各处写错）。
 */
object SettingsRoutes {
    const val SETTINGS = "settings"
    const val API_CONFIG = "api_config"
    const val APPEARANCE = "appearance"
    const val NOTIFICATIONS = "notifications"
    const val DATA_PRIVACY = "data_privacy"
    const val ABOUT = "about"
    const val VOICE = "voice"
    const val TTS_CONFIG = "tts_config"
    const val ASR_MODEL = "asr_model"
    const val PROMPTS = "prompts"
    const val PROFILE = "profile"
    const val GALLERY = "gallery"
}

/** API 配置副标题：生效接口名 + 生图接口状态，一眼看清聊天用的是谁 */
private fun apiSubtitle(activeName: String?, profileCount: Int, imageReady: Boolean): String = when {
    profileCount == 0 -> "未配置 · AI 暂不可用"
    else -> buildString {
        append("当前：").append(activeName ?: "默认接口")
        append(" · 生图").append(if (imageReady) "已配置" else "未配置")
    }
}
