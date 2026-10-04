package com.menghuan.app.ui.realtime

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MutedText
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
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(420.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                items(turns.size) { i ->
                    val t = turns[i]
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
                        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
