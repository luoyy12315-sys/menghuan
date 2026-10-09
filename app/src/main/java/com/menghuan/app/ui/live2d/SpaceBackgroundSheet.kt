package com.menghuan.app.ui.live2d

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.Live2dSpaceStore
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary

/**
 * 「角色空间背景」弹层（v0.82.0 P2-3 从 `Live2dActionSheets.kt` 拆出来）。
 *
 * 共用的小组件见本包里的 `Live2dSheetWidgets.kt`。
 */

// =====================================================================
// 背景
// =====================================================================

/**
 * 背景选择：v0.65.0 用户点名**只留两档** —— 系统预设 / 自定义背景；
 * v0.74.0 再加**视频背景**一档（用户点名"角色空间背景支持视频"）。
 *
 * 原来那"主题底色 + 4 张内置渐变 + 相册图"六行里，用户真正会用的只有"要不要换成自己的图"
 * 这一件事；四档渐变是给用户添麻烦的（选了第 3 档的人，事后说不出那三档有什么差别）。
 * 老数据（`theme`、`builtin:1..3`）不用迁移：不是 `file:` / `video:` 的一律按系统预设画。
 */
@Composable
fun SpaceBackgroundSheet(
    current: Live2dSpaceStore.SpaceConfig,
    onPick: (Live2dSpaceStore.SpaceConfig) -> Unit,
    onImport: (Uri) -> Unit,
    onImportVideo: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImport(uri)
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImportVideo(uri)
    }

    NeuSheetScaffold(title = "角色空间背景", onDismiss = onDismiss) {
        Spacer(Modifier.height(6.dp))
        BackgroundRow(
            label = "系统预设",
            selected = !current.isCustom,
            onClick = { onPick(Live2dSpaceStore.SpaceConfig(Live2dSpaceStore.BACKGROUND_PRESET)) },
        )
        Spacer(Modifier.height(8.dp))
        BackgroundRow(
            label = "自定义背景",
            selected = current.fileName != null,
            onClick = { picker.launch("image/*") },
        )
        Spacer(Modifier.height(8.dp))
        BackgroundRow(
            label = "视频背景（≤30 秒）",
            selected = current.isVideo,
            onClick = { videoPicker.launch("video/*") },
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun BackgroundRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .neuSurface(14.dp, color = if (selected) NeuTrack else NeuSurface)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        if (selected) {
            Text("✓", fontSize = 15.sp, color = NeuAccent, fontWeight = FontWeight.Bold)
        }
    }
}
