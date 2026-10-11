package com.menghuan.app.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.MenghuanApp
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.settings.SettingsPage
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.TextPrimary
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch

/**
 * 「对话摘要」全屏页（v0.86.0，用户点名「对话摘要我也要全屏展示」）。
 *
 * ## 为什么从弹层改成整页
 * 摘要正文是**一段可能几百字的连续文本**，放进从底部升上来的弹层里只有半屏高、
 * 还得在弹层里再滚一层，读长文很别扭；而且弹层里同时塞着「记住最近多少字」这个输入框，
 * 键盘一顶上来正文基本看不见了。会话设置链里其它同类入口（记忆 / 聊天记录 / 三件事的回看）
 * 都是整页，这一条跟着统一。
 *
 * ## 页序：设置在上、正文在下（v0.86.0 沿用弹层里的定稿顺序）
 * 「记住最近多少字」和摘要正文**是一件事的两面**（窗口定得小 → 掉出窗口的内容就得靠摘要接住），
 * 所以两个都留在这页；但**设置在上**：用户从会话设置点进来十次有九次是来改那个数的，
 * 正文是"顺带看一眼"；反过来把一段长文压在最上面，每次改数都要先滚过它。
 *
 * ## 空态不写客套话（§四）
 * 还没有摘要时只留一句说清"什么条件下会有"，不写"暂时没有内容哦"这类话。
 */
@Composable
fun ConversationSummaryScreen(personaId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as MenghuanApp
    val repo = app.settingsRepository
    val scope = rememberCoroutineScope()

    val summary by app.db.conversationSummaryDao().observe(personaId).collectAsState(initial = null)
    val budget by remember(personaId) { repo.historyCharBudget(personaId) }
        .collectAsState(initial = repo.DEFAULT_HISTORY_CHAR_BUDGET)

    SettingsPage(title = "对话摘要", onBack = onBack) {
        Spacer(Modifier.height(14.dp))

        NumberSettingField(
            label = "记住最近多少字",
            value = budget,
            range = repo.HISTORY_CHAR_BUDGET_RANGE,
            onCommit = { scope.launch { repo.setHistoryCharBudget(personaId, it) } },
        )

        // 正文与设置的断口：正文是一大段，上面那条发丝线的节奏（14dp 行距）不够分开两块，
        // 给到 24dp。分隔只用留白，不加小标题 —— 版式纪律：界面不写说明性小字。
        Spacer(Modifier.height(24.dp))

        val text = summary?.summary?.takeIf { it.isNotBlank() }
        if (text == null) {
            Text(
                "聊得再久一些就会有了：太早的对话装不下时，会先压缩成一段摘要放在这里，TA 不会真的忘掉。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = MutedText,
            )
        } else {
            // 摘要正文是这一页的主角：字号比正文设置项大一号（15sp / 行高 24sp），
            // 不加粗、不换色，靠字号和行距和上面的设置区拉开层次。
            Text(text, fontSize = 15.sp, lineHeight = 24.sp, color = TextPrimary)

            // 摘要的时间跨度：不写"生成于几月几日"（那是元信息），只写它管到哪条消息之后 ——
            // 这恰好也是"为什么最近聊的还是 TA 原话"的解释（这部分之后的对话没进摘要）。
            summary?.coveredMessageId?.takeIf { it > 0 }?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    "这段摘要收的是更早的对话，这之后的都还在原文里。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = FaintText,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "删除后按「从来没有摘要」重新积累：之后的对话旧了会重新攒一份，不会因此丢掉记忆。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = FaintText,
            )
            Spacer(Modifier.height(6.dp))
            NeuTextButton("删除这段摘要", color = NeuDanger, onClick = {
                scope.launch { app.db.conversationSummaryDao().delete(personaId) }
            })
        }

        Spacer(Modifier.height(24.dp))
        // 生成开关不在这页：它在「设置 · 提示词库 · 对话摘要策略」段（总开关），
        // 这里是**单个角色**的窗口与正文 —— 不跨层放入口。
        Text(
            "生成开关在「设置 · 提示词库 · 对话摘要策略」。",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = FaintText,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
