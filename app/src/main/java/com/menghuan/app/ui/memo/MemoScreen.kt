package com.menghuan.app.ui.memo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.data.model.MemoEntry
import com.menghuan.app.data.model.Persona
import com.menghuan.app.ui.common.AdaptiveScreen
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.NeuSectionTitle
import com.menghuan.app.ui.common.NeuTopBar
import com.menghuan.app.ui.data.DataViewModel
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.TextPrimary

/**
 * 备忘本全屏页（v0.62.0；v0.63.0 起**整页没有任何表单弹层**；v0.65.0 条目改成卡片）。
 *
 * 版式：顶栏返回 → 一行内联输入框（点它就在这儿打字，回车即存）→ **一条条备忘卡片**。
 * 进场动效照人设管理：`animateItem` 逐条错峰淡入（见 [MemoListContent]）。
 * 右上角那颗 ＋ 删了：页顶就是输入框，再放一个"跳到输入框"的按钮是多余的。
 * 空态也不写"还没有备忘"——输入行本身就是空态。
 *
 * 列表用 `LazyColumn`（不再整页 `verticalScroll`）：条目只有列表这一处会滚动，
 * 输入框钉在页顶不参与滚动，键盘弹起时不会被卷走。
 */
@Composable
fun MemoScreen(
    memos: List<MemoEntry>,
    personas: List<Persona>,
    message: String?,
    onBack: () -> Unit,
    onQuickAdd: (String) -> Unit,
    onSaveEdit: (id: Long, content: String, pinned: Boolean) -> Unit,
    onTogglePin: (Long, Boolean) -> Unit,
    onSendToAi: (personaId: Long, memo: MemoEntry) -> Unit,
    onDelete: (Long) -> Unit,
) {
    AdaptiveScreen {
        NeuTopBar(
            title = "备忘本",
            navigationIcon = {
                NeuCircleButton(onClick = onBack, size = 38.dp) {
                    Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
                }
            },
        )

        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            if (message != null) {
                Text(
                    message,
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    fontSize = 11.sp,
                    color = NeuAccent,
                )
            }

            if (memos.isNotEmpty()) {
                NeuSectionTitle("备忘 · ${memos.size} 条", Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.height(6.dp))
            }

            MemoComposer(onSave = onQuickAdd)
            Spacer(Modifier.height(10.dp))

            MemoListContent(
                memos = memos,
                personas = personas,
                modifier = Modifier.weight(1f),
                onSaveEdit = onSaveEdit,
                onTogglePin = onTogglePin,
                onSendToAi = onSendToAi,
                onDelete = onDelete,
            )
        }
    }
}

/**
 * 备忘本路由（v0.62.0）：全屏页的接线。
 *
 * `onSendToAi` 选完人设后要**当场跳进那个会话**——备忘已经写进 TA 的输入框草稿，
 * 用户点进去核对一句就能发；停在备忘页里等于事没办完。
 */
@Composable
fun MemoRoute(
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit,
) {
    val vm: DataViewModel = viewModel(factory = DataViewModel.Factory)
    val memos by vm.memos.collectAsState()
    val personas by vm.personas.collectAsState()
    val message by vm.memoMessage.collectAsState()

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(2500)
            vm.clearMemoMessage()
        }
    }

    MemoScreen(
        memos = memos,
        personas = personas,
        message = message,
        onBack = onBack,
        onQuickAdd = { vm.quickAddMemo(it) },
        onSaveEdit = { id, content, pinned -> vm.saveMemo(id, content, pinned) },
        onTogglePin = { id, pinned -> vm.toggleMemoPin(id, pinned) },
        onSendToAi = { personaId, memo ->
            vm.sendMemoToPersona(personaId, memo)
            onOpenChat(personaId)
        },
        onDelete = { vm.deleteMemo(it) },
    )
}
