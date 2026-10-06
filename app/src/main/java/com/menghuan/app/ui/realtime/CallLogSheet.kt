package com.menghuan.app.ui.realtime

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.model.CallTurn
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.staggerEnter
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.TextPrimary

/**
 * 通话文字转写弹层（v0.49.0）：聊天流里的通话记录卡点开显示本次逐轮对话。
 * sheet 内自取应用容器（PersonaEditorSheet 同款模式），调用点零依赖。
 */
@Composable
fun CallLogSheet(
    sessionId: Long,
    title: String,
    personaName: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var turns by remember { mutableStateOf<List<CallTurn>>(emptyList()) }
    LaunchedEffect(sessionId) {
        val db = runCatching {
            (context.applicationContext as MenghuanApp).db
        }.getOrNull() ?: return@LaunchedEffect
        runCatching { db.callDao().turns(sessionId) }.getOrNull()?.let { turns = it }
    }

    NeuSheetScaffold(title = title, onDismiss = onDismiss) {
        Spacer(Modifier.height(4.dp))
        if (turns.isEmpty()) {
            Text(
                "本次通话没有留下对话",
                fontSize = 13.sp,
                color = MutedText,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        } else {
            // v0.55.0：**不再在弹层里套 LazyColumn**。
            // `NeuSheetScaffold` 的内容本身是 `Column(verticalScroll)`，里面再嵌一个固定
            // 420dp 高的 LazyColumn，两层都吃竖直手势 —— 手指划上去分不清该谁滚，
            // 实际表现就是"划不动 / 整块跟着走"。一通电话的对话量本来就有限（几十句），
            // 直接平铺进弹层的滚动列，手势只有一层，怎么划都是对的。
            //
            // 顺带：每句之间加一条发丝线，谁说的、说到哪一句一眼能数清（原来全靠 11sp 的
            // 名字前缀区分，长对话里很容易串行）。
            turns.forEachIndexed { index, t ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .height(1.dp)
                            .background(NeuHairline),
                    )
                }
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = MutedText)) {
                            append(if (t.speaker == "user") "你 " else "$personaName ")
                        }
                        withStyle(SpanStyle(fontSize = 13.5.sp, color = TextPrimary)) {
                            append(t.text)
                        }
                    },
                    lineHeight = 20.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .staggerEnter(index, stepMs = 14, slideDp = 6.dp)
                        .padding(vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
