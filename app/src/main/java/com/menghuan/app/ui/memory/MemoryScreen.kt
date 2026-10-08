package com.menghuan.app.ui.memory

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.model.ProfileFact
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.settings.SettingsPage
import com.menghuan.app.ui.theme.FaintText
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuHairline
import com.menghuan.app.ui.theme.TextPrimary
import kotlinx.coroutines.launch

/**
 * 「TA 的记忆」全屏页（v0.71.0）：只看**当前这个角色**的记忆，按生命周期分四段。
 *
 * ## 为什么这一页必须和"待入库"同一批落地
 * `MemoryScope.visible` 不注入 `staging` 条目。要是 AI 写进来的东西停在 staging、
 * 而这里没有点头/摇头的入口，那就是一个**用户看不见的死功能**：屏幕上 TA 说"我记住了"，
 * 那行数据永远进不了下一次的 prompt。所以顺序是**先有审批入口，再让写入落到 staging**
 * （`ChatViewModel.maybeStageMemories` 在那之后才改成写 staging）。
 *
 * ## 四段各自的含义
 * - **待入库**：AI 说要记、用户还没表态 → 「记住」/「不要记」。
 * - **记住的**：已生效，会被注入、也会被检索命中。
 * - **永久**：置顶注入，且不被后来的同名合并与降级动到。
 * - **已忽略**：摇过头（`rejected`）与手动停用（`archived`）。
 *   ⚠️ 这里的行**不跟着角色删、也不能一键清空** —— "拒过某句话"这件事本身就是数据：
 *   把它删了，AI 下次聊天又会把同一句记进来，用户会觉得"我明明说过不要记"。
 *   要真清掉只有逐条「彻底删除」。
 *
 * 界面沿用设计系统：无涟漪、发丝线分隔、不写说明性小字（段落标题是功能标签）、
 * 破坏性动作不叠确认框（「彻底删除」本身就是列表里点出来的第二步）。
 */
@Composable
fun MemoryScreen(personaId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as MenghuanApp
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rows by app.db.profileFactDao().observeOwnedBy(personaId).collectAsState(initial = emptyList())

    val staging = rows.filter { it.lifecycle == "staging" }
    val active = rows.filter { it.lifecycle == "active" }
    val core = rows.filter { it.lifecycle == "core" }
    val ignored = rows.filter { it.lifecycle == "rejected" || it.lifecycle == "archived" }

    fun setLifecycle(row: ProfileFact, next: String, toast: String? = null) {
        scope.launch {
            // 失败一定要出声：静默失败在这个项目里是被用户当成"坏了"排查最久的一类问题
            val ok = runCatching {
                app.db.profileFactDao().upsert(row.copy(lifecycle = next, updatedAt = System.currentTimeMillis()))
            }.isSuccess
            Toast.makeText(
                context,
                if (!ok) "操作失败，请重试" else (toast ?: ""),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun delete(row: ProfileFact) {
        scope.launch {
            runCatching { app.db.profileFactDao().delete(row.id) }
            Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
        }
    }

    SettingsPage(title = "TA 的记忆", onBack = onBack) {
        Spacer(Modifier.height(10.dp))

        Section("待入库", staging) { row ->
            MemoryRow(row.value, trailing = {
                NeuTextButton("记住", onClick = { setLifecycle(row, "active", "已放进记忆") })
                Spacer(Modifier.width(4.dp))
                NeuTextButton("不要记", onClick = { setLifecycle(row, "rejected", "这条以后不会再记") })
            })
        }
        Section("记住的", active) { row ->
            MemoryRow(row.value, trailing = {
                NeuTextButton("永久", onClick = { setLifecycle(row, "core", "已设为永久") })
                Spacer(Modifier.width(4.dp))
                NeuTextButton("停用", onClick = { setLifecycle(row, "archived", "已停用") })
            })
        }
        Section("永久", core) { row ->
            MemoryRow(row.value, trailing = {
                NeuTextButton("取消永久", onClick = { setLifecycle(row, "active", "已取消永久") })
            })
        }
        Section("已忽略", ignored) { row ->
            MemoryRow(row.value, dim = true, trailing = {
                if (row.lifecycle == "archived") {
                    NeuTextButton("恢复", onClick = { setLifecycle(row, "active", "已恢复") })
                    Spacer(Modifier.width(4.dp))
                }
                NeuTextButton("彻底删除", onClick = { delete(row) })
            })
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 空段整段不占位（空状态的客套话按 §四 一律不写） */
@Composable
private fun Section(
    title: String,
    items: List<ProfileFact>,
    row: @Composable (ProfileFact) -> Unit,
) {
    if (items.isEmpty()) return
    Column {
        Text(
            "$title · ${items.size}",
            fontSize = 11.sp,
            color = FaintText,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp),
        )
        items.forEach { row(it) }
    }
}

@Composable
private fun MemoryRow(text: String, dim: Boolean = false, trailing: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                fontSize = 14.sp,
                color = if (dim) MutedText else TextPrimary,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            trailing()
        }
        // 发丝线：只用 0.5dp 实色，不用阴影（v0.42.1 起全局零阴影）
        Spacer(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(0.5.dp)
                .background(NeuHairline)
        )
    }
}
