package com.menghuan.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.menghuan.app.MenghuanApp
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.TextPrimary

/**
 * 设置链全屏页的统一骨架（v0.51.0）。
 *
 * **为什么从弹层改成整页**：设置链现在有三层（设置 → 语音 → 语音合成），
 * 弹层叠弹层在手机上会把整屏盖死、返回手势也说不清退到哪一层；
 * 而且识别模型下载要跑几分钟，用户需要能退出去干别的再回来——
 * 那正是"页面有返回栈"才成立的事。
 *
 * insets 仍由 [AdaptiveScreen] 统一处理，页面内部不要再写 statusBarsPadding。
 */
@Composable
fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    AdaptiveScreen {
        NeuTopBar(
            title = title,
            navigationIcon = {
                NeuCircleButton(onClick = onBack, size = 38.dp) {
                    Icon(
                        MIcons.Back,
                        contentDescription = "返回",
                        tint = TextPrimary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            },
            actionsWidth = 56.dp,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp),
            content = content,
        )
    }
}

/** 设置页共用的容器句柄（各页自己取仓库，路由不传参） */
@Composable
fun settingsContainer(): MenghuanApp =
    LocalContext.current.applicationContext as MenghuanApp
