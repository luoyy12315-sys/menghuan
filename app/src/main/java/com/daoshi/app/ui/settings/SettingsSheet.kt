package com.daoshi.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoshi.app.BuildConfig
import com.daoshi.app.ui.common.NeuSheetRow
import com.daoshi.app.ui.common.NeuSheetScaffold
import com.daoshi.app.ui.theme.MIcons
import com.daoshi.app.ui.theme.MutedText

/**
 * 共享设置面板（新拟态）：入口在会话列表页右上角（仅此一处）。
 * 五项全部可用：人设管理 / API 配置 / 通知与提醒 / 数据与隐私 / 关于。
 */
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    onApiConfig: () -> Unit = {},
    onPersonaManage: () -> Unit = {},
    onNotifications: () -> Unit = {},
    onDataPrivacy: () -> Unit = {},
    onAbout: () -> Unit = {},
) {
    val items = listOf(
        MIcons.Person to ("人设管理" to "名字、身份、设定词 · 可增删改"),
        MIcons.Code to ("API 配置" to "接口地址 · 密钥 · 模型"),
        MIcons.Bell to ("通知与提醒" to "任务到点提醒开关"),
        MIcons.Shield to ("数据与隐私" to "本机数据统计 · 清除记录"),
        MIcons.Info to ("关于" to "版本 ${BuildConfig.VERSION_NAME}"),
    )
    val actions: Map<Int, () -> Unit> = mapOf(
        0 to onPersonaManage,
        1 to onApiConfig,
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
        Spacer(Modifier.height(2.dp))
        Text(
            "数据仅存本机，不上传云端。",
            fontSize = 11.sp,
            color = MutedText,
        )
    }
}
