package com.menghuan.app.ui.live2d

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.menghuan.app.live2d.ActionType
import com.menghuan.app.live2d.Live2dAction
import com.menghuan.app.realtime.visual.VisualRuntime
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuCircleButton
import com.menghuan.app.ui.common.StageBackgroundLayer
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.staggerEnter
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuBackground
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary

/**
 * 角色 Live2D 空间（v0.64.0，用户要求 §二）。
 *
 * **这不是视频通话页**（用户第 1 条）：它不接通话引擎、不联网、不产生 token，
 * 就是一个可以跟角色玩的场景 —— 换背景、拖来拖去、点它、让它做动作。
 *
 * 页面拿到的只有 [com.menghuan.app.realtime.visual.Live2dStage] 这一层，
 * 里面不出现任何 Cubism / GL 的名字（老规矩：页面不碰底层 SDK）。
 *
 * 三层结构（从下往上）：
 * ① 背景层：系统预设渐变 / 用户相册图（v0.65.0 起只有这两档）；
 * ② 舞台层：Live2D 角色，可拖动、可点；
 * ③ UI 层：顶栏（返回 + 背景）与底部动作条。
 */
@Composable
fun Live2dSpaceScreen(
    personaId: Long,
    onBack: () -> Unit,
    onOpenSettings: (String) -> Unit = {},
    /** v0.64.0：进「通话动作」页改四个状态绑什么（与空间共用同一套动作库） */
    onOpenCallActions: () -> Unit = {},
    vm: Live2dSpaceViewModel = viewModel(
        key = "live2d_space_$personaId",
        factory = Live2dSpaceViewModel.Factory(personaId),
    ),
) {
    val ui by vm.ui.collectAsState()

    var showLibrary by remember { mutableStateOf(false) }
    var showBackground by remember { mutableStateOf(false) }
    var showEditor by remember { mutableStateOf<Live2dAction?>(null) }
    var showAi by remember { mutableStateOf(false) }
    var showCreateMenu by remember { mutableStateOf(false) }

    // 页面生命周期 → 引擎停/续。切后台停画（省电），回来只接回渲染节拍，不重建模型。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> vm.pause()
                Lifecycle.Event.ON_RESUME -> vm.resume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().background(NeuBackground)) {

        // ① 背景层（画法与视频通话共用一份，见 ui/common/StageBackground.kt）
        StageBackgroundLayer(
            custom = ui.background.isCustom,
            filePath = ui.backgroundFile?.absolutePath.orEmpty(),
        )

        // ② 舞台层
        val stage = vm.stage
        when {
            stage != null -> {
                // 视图尺寸就地记住：点击要换算成 NDC 坐标（-1..1，y 向上），
                // 拖动要换算成画面比例。两个都用"实际测量到的尺寸"，不用屏幕尺寸 ——
                // 分屏 / 平板下它们并不相等。
                var viewWidth by remember { mutableStateOf(1) }
                var viewHeight by remember { mutableStateOf(1) }
                AndroidView(
                    factory = { stage.onCreateView(it) },
                    modifier = Modifier
                        .fillMaxSize()
                        // 先量尺寸：点击换算 NDC、拖动换算画面比例都要用**实际测量到的尺寸**
                        // （分屏 / 平板下它与屏幕尺寸并不相等）。尺寸变了才触发一次重组。
                        .onMeasured { w, h ->
                            viewWidth = w.coerceAtLeast(1)
                            viewHeight = h.coerceAtLeast(1)
                        }
                        .then(
                            Modifier.pointerInputUnit { detectDragGestures { change, dragAmount ->
                                change.consume()
                                vm.drag(
                                    dxFraction = dragAmount.x / viewWidth,
                                    // 屏幕 y 向下 = 模型要往下走 = NDC 的负方向，所以取反
                                    dyFraction = -dragAmount.y / viewHeight,
                                )
                            } },
                        )
                        .then(
                            Modifier.pointerInputUnit { detectTapGestures { pos ->
                                vm.onTap(
                                    ndcX = pos.x / viewWidth * 2f - 1f,
                                    ndcY = 1f - pos.y / viewHeight * 2f,
                                )
                            } },
                        ),
                    update = {},
                )
            }

            !ui.hasModel -> SpaceEmptyState(
                onImport = { onOpenSettings("chat_settings/$personaId") },
            )

            else -> LoadingHint()
        }

        // 形象坏了：把原因写在这页上。
        // ⚠️ 这不是"说明性小字"（§四那条禁的是客套话与操作提示）：视觉降级在屏幕上
        // 长得和"还没加载出来"一模一样，不写原因就只能靠猜——
        // 当年 Live2D 着色器那件事猜掉了三个版本（AGENTS §零.7）。
        (ui.runtime as? VisualRuntime.Degraded)?.let { d ->
            Text(
                d.reason,
                fontSize = 13.sp,
                color = MutedText,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
            )
        }

        // ③ 顶栏
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .staggerEnter(0, stepMs = 50, slideDp = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeuCircleButton(onClick = onBack, size = 40.dp) {
                Icon(MIcons.Back, contentDescription = "返回", tint = TextPrimary, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ui.persona?.name ?: "角色空间",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (ui.capability.paramChannelCount > 0) {
                        "可以做动作 · 支持 ${ui.capability.paramChannelCount} 项"
                    } else {
                        "可以做动作"
                    },
                    fontSize = 11.5.sp,
                    color = MutedText,
                )
            }
            NeuCircleButton(onClick = { showBackground = true }, size = 40.dp) {
                Icon(MIcons.ImageIcon, contentDescription = "背景", tint = TextPrimary, modifier = Modifier.size(19.dp))
            }
        }

        // ④ 底部动作条
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 14.dp)
                .staggerEnter(1, stepMs = 50, slideDp = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val chips = remember(ui.actions) { stageActionsOf(ui.actions) }
            if (chips.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(chips, key = { "${it.id}|${it.name}" }) { action ->
                        SpaceActionChip(
                            name = action.name,
                            playing = action.id == ui.playingId,
                            onClick = { vm.play(action) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpaceToolButton(
                    icon = MIcons.Play,
                    label = "动作库",
                    onClick = { showLibrary = true },
                    modifier = Modifier.weight(1f),
                )
                SpaceToolButton(
                    icon = MIcons.Plus,
                    label = "自己做",
                    onClick = { showEditor = null; showCreateMenu = true },
                    modifier = Modifier.weight(1f),
                )
                SpaceToolButton(
                    icon = MIcons.Bulb,
                    label = "AI 生成",
                    onClick = { showAi = true },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        // 正在播的那条动作的名字（轻量反馈：点了到底有没有生效）
        if (ui.playingName.isNotBlank()) {
            Text(
                ui.playingName,
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 62.dp)
                    .neuSurface(999.dp, color = NeuTrack)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        ui.note?.let { note ->
            SpaceNote(text = note, onDismiss = { vm.clearNote() }, modifier = Modifier.align(Alignment.Center))
        }
    }

    // ---- 弹层 ----
    if (showBackground) {
        SpaceBackgroundSheet(
            current = ui.background,
            onPick = { config -> vm.setBackground(config); showBackground = false },
            onImport = { uri ->
                // 选完立刻关弹层：背景是垫在角色背后的，不关掉就看不见它到底换没换。
                // 导入失败由页面中间那颗 SpaceNote 说清原因（不藏在弹层里）。
                vm.importBackground(uri)
                showBackground = false
            },
            onDismiss = { showBackground = false },
        )
    }

    if (showLibrary) {
        ActionLibrarySheet(
            actions = ui.actions,
            playingId = ui.playingId,
            onPlay = { vm.play(it) },
            onFavorite = { vm.toggleFavorite(it) },
            onDelete = { vm.delete(it) },
            onEdit = { action -> showLibrary = false; showEditor = action },
            onDismiss = { showLibrary = false },
        )
    }

    if (showEditor != null || showCreateMenu) {
        // 能当"基础动作"的：内置趣味动作 + 我的动作 + AI 生成的动作。
        // 刻意排除**四态默认动作**（它们是通话那四个状态用的，动一下就会牵动通话观感）
        // 与**模型自带动作**（它们的时长由模型自己的 motion 决定，嵌进编辑器里调时长是假的可调）。
        val templates = remember(ui.actions) {
            val stateKeys = com.menghuan.app.live2d.ActionSlot.entries
                .map { com.menghuan.app.live2d.BuiltinActions.defaultKeyOf(it) }.toSet()
            ui.actions.filter {
                it.hasTracks && it.type == ActionType.PROCEDURAL && it.builtinKey !in stateKeys
            }
        }
        val expressions = remember(ui.actions) {
            ui.actions.filter { it.type == ActionType.EXPRESSION && it.expression.isNotBlank() }
                .map { it.expression }
                .distinct()
        }
        ActionEditorSheet(
            base = showEditor,
            templates = templates,
            expressions = expressions,
            onPreview = { vm.play(it) },
            onSave = { action ->
                vm.saveAction(action)
                showEditor = null
                showCreateMenu = false
            },
            onDismiss = { showEditor = null; showCreateMenu = false },
        )
    }

    if (showAi) {
        AiActionSheet(
            generate = { description -> vm.generateWithAi(description) },
            onPreview = { vm.play(it) },
            onSave = { action, onDone ->
                vm.saveAction(action) { onDone() }
                showAi = false
            },
            onDismiss = { showAi = false },
        )
    }
}

// ===== 小组件 =====

/** 动作条上的一颗胶囊：点了就播，正在播的那条是黑底（本项目"选中 = 纯黑药丸"的语言） */
@Composable
private fun SpaceActionChip(name: String, playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clickable { onClick() }
            .neuSurface(999.dp, color = if (playing) NeuAccent else NeuSurface)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            name,
            fontSize = 13.sp,
            fontWeight = if (playing) FontWeight.Medium else FontWeight.Normal,
            color = if (playing) AccentInk else TextPrimary,
            maxLines = 1,
        )
    }
}

/** 底部三个入口：图标 + 名字的小胶囊 */
@Composable
private fun SpaceToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clickable(onClick = onClick)
            .neuSurface(999.dp, color = NeuSurface)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(label, fontSize = 13.sp, color = TextPrimary, maxLines = 1)
        }
    }
}

/** 没有形象时的空态：说清要做什么，并给一条直达的路（不要让人自己找） */
@Composable
private fun SpaceEmptyState(onImport: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(MIcons.Smile, contentDescription = null, tint = MutedText, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            "这个角色还没有 Live2D 形象",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
        )
        Spacer(Modifier.height(22.dp))
        NeuButton(text = "去导入模型包", onClick = onImport)
    }
}

@Composable
private fun LoadingHint() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("正在准备角色…", fontSize = 13.sp, color = MutedText)
    }
}

/** 一句提示（导入失败 / 保存成功…），点一下就走 */
@Composable
private fun SpaceNote(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = 40.dp)
            .clickable { onDismiss() }
            .neuSurface(14.dp, color = NeuSurface)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(text, fontSize = 13.sp, color = NeuDanger, textAlign = TextAlign.Center)
    }
}

// ===== 两个小工具 =====

/**
 * 独立的 pointerInput 通道。
 *
 * 抽出来是因为**拖动与点按必须各占一个 pointerInput**：写在同一个里的话，
 * `detectDragGestures` 要跨过 touchSlop 才回调，纯点按永远进不来；
 * 分成两个之后，手势竞技场会自己判"这是拖还是点"，两边互不吞对方。
 */
private fun Modifier.pointerInputUnit(block: suspend androidx.compose.ui.input.pointer.PointerInputScope.() -> Unit): Modifier =
    this.pointerInput(Unit) { block() }

/**
 * 测量回调。
 *
 * 用 `onSizeChanged` 会把像素尺寸写进 state，每次更改都触发重组；
 * 而这里只是要拿到尺寸做坐标换算，用 `onGloballyPositioned` 更贴近"量一下"的语义。
 */
private fun Modifier.onMeasured(block: (Int, Int) -> Unit): Modifier =
    this.onGloballyPositioned { coords -> block(coords.size.width, coords.size.height) }
