package com.menghuan.app.ui.persona

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.VoiceRef
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.TextPrimary

/**
 * 「这个 AI 用什么声音」的选择弹层（v0.53.6）。
 *
 * 从编辑人设页**抽出来共用**：编辑人设里那一项已按要求移除，
 * 现在统一从**对话页右上角菜单 → 音色**打开（谁的声音属于谁，跟人设绑定）。
 *
 * 名单与「音色」页同源：该家两个预置（一男一女）+ 我克隆的。
 * 这里**只挑不试听不添加**（试听与添加只留在音色页那一处）。
 *
 * **v0.56.7（用户点名）**：页内那句说明小字也一并去掉（"音色模块里的小标题一并移除"）。
 *
 * **v0.56.6（用户点名：弹层卡顿 / 像弹了两次）**：这个弹层改成**纯展示** ——
 * 名单由调用方（会话设置页）**在打开之前**就加载好传进来。
 * 原来它自己在 `LaunchedEffect` 里拉数据（先拿到配置、克隆音色还要一个网络往返），
 * 于是"弹层先出现、名单后补进来"，内容跳一截；现在弹层一出现就是完整名单，不再有那一下。
 */
@Composable
fun PersonaVoiceSheet(
    /** 候选音色（引用 + 显示名），顺序即展示顺序；由调用方预先备好 */
    options: List<Pair<VoiceRef, String>>,
    /** 当前这个角色实际会用的音色 id（人设专属 → 板块默认 → 该家第一个预置） */
    effectiveId: String,
    onPick: (VoiceRef) -> Unit,
    onDismiss: () -> Unit,
) {
    NeuSheetScaffold(title = "音色", onDismiss = onDismiss) {
        Spacer(Modifier.height(8.dp))
        if (options.isEmpty()) {
            Text("还没配语音合成，先去 设置 → 模型配置 → 语音模型 配一下。", fontSize = 13.sp, color = MutedText)
        }
        options.forEach { (ref, label) ->
            val selected = effectiveId == ref.voiceId
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(NeuSurface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onPick(ref) }
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    fontSize = 15.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (selected) Text("当前", fontSize = 12.sp, color = MutedText)
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(10.dp))
    }
}
