@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

/**
 * 聊天输入区下方的功能面板（v0.43.0 引入，v0.43.1 大幅收紧）：表情 / 加号。
 * 展开时**收起键盘**（微信行为：键盘和面板不会同时在），由调用方负责 hide。
 *
 * v0.43.1 按用户反馈调整：
 * - 表情面板**删掉分区标题**（常用/情绪/手势/生活）与**中间那条灰线**——
 *   "不需要情绪、手势这些分类，也不要中间那条灰线"
 * - 面板**贴紧输入框**（去掉顶部大留白），顶部 tab 行也压薄
 * - 加号面板**只占三个按钮需要的高度**（原来 268dp 太空，"又大又丑"）
 */

package com.menghuan.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.data.model.Sticker
import com.menghuan.app.ui.common.neuSurface
import com.menghuan.app.ui.common.rememberNeuPressState
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.NeuSurface
import com.menghuan.app.ui.theme.TextPrimary

/** 表情面板总高：约 6 行 emoji（紧凑行高，一屏能看全） */
val EmojiPanelHeight = 232.dp

/** 加号面板总高：**只够三个按钮**（v0.45.0 再收紧，去掉多余留白） */
val PlusPanelHeight = 104.dp

/** 面板内统一的「无涟漪点击」（v0.45.0）：emoji / 表情 / tab 格子不再出现灰色水波纹 */
private fun Modifier.tapNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

/**
 * 表情面板：一页 emoji / 一页收藏，**上下滑动选择**。
 * v0.43.1 去掉分区标题与分隔线，emoji 平铺成一整片。
 * v0.45.0 按用户反馈：顶部留白再收紧（贴紧输入框）、「我的表情」改名「收藏」、
 * 添加表情的入口收进收藏页网格末尾的加号格（此处不再挂右上角 +）。
 */
@Composable
fun EmojiPanel(
    stickers: List<Sticker>,
    onEmoji: (String) -> Unit,
    onSticker: (String) -> Unit,
    onAddSticker: () -> Unit,
    onDeleteSticker: (Sticker) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }

    Column(
        Modifier
            .fillMaxWidth()
            .height(EmojiPanelHeight)
            .background(NeuSurface)
            // v0.45.0：顶部留白 8dp → 2dp，tab 行贴着输入框
            .padding(top = 2.dp),
    ) {
        // 顶部 tab：两页（第二页 = 收藏）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelTab("表情", selected = tab == 0) { tab = 0 }
            Spacer(Modifier.width(6.dp))
            PanelTab("收藏", selected = tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(4.dp))

        if (tab == 0) {
            EmojiGrid(onEmoji, Modifier.weight(1f))
        } else {
            StickerGrid(stickers, onSticker, onDeleteSticker, onAddSticker, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PanelTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) TextPrimary else MutedText,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) NeuTrack else Color.Transparent)
            .tapNoRipple(onClick)
            .padding(horizontal = 11.dp, vertical = 3.dp),
    )
}

/**
 * emoji 页：**扁平铺开、无分区标题**（v0.43.1），可上下滑动。
 * 用 emoji **字符**而不是图片：系统字体自带彩色 emoji，零资源、任何设备都清晰。
 */
@Composable
private fun EmojiGrid(onEmoji: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        // 每行 8 个，用 chunked 手动排：嵌套同向 Lazy 会崩，且这里的量很小
        AllEmojis.chunked(8).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { e ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .tapNoRipple { onEmoji(e) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(e, fontSize = 23.sp, textAlign = TextAlign.Center)
                    }
                }
                // 补齐最后一行，避免格子被拉伸变形
                repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 收藏页：网格铺图，点击直接发送；长按弹删除。
 * v0.45.0：**网格末尾固定一个加号格**作为唯一添加入口，空的时候也只剩这一个加号格，
 * 不再有任何说明文字（原来右上角的 + 与两行空状态提示已删除）。
 */
@Composable
private fun StickerGrid(
    stickers: List<Sticker>,
    onSticker: (String) -> Unit,
    onDelete: (Sticker) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(stickers, key = { it.id }, contentType = { "sticker" }) { s ->
            var menuOpen by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .neuSurface(10.dp, inset = true)
                    .tapNoRipple { onSticker(s.path) },
                contentAlignment = Alignment.Center,
            ) {
                val bmp = rememberLocalBitmap(s.path)
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp,
                        contentDescription = "自定义表情",
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(MIcons.ImageIcon, contentDescription = null, tint = MutedText, modifier = Modifier.size(20.dp))
                }
                // 长按删除：菜单锚在条目右下角（项目约定：破坏性操作只弹菜单）
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(26.dp)
                        .combinedClickableSticker { menuOpen = true },
                ) {
                    androidx.compose.material3.DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        // v0.53.5：全应用去阴影后，白底菜单在白底页面上糊成一片 —— 统一加一圈发丝灰线框
                        modifier = Modifier.border(
                            1.dp,
                            com.menghuan.app.ui.theme.NeuHairline,
                            RoundedCornerShape(14.dp),
                        ),
                        containerColor = NeuSurface,
                        shape = RoundedCornerShape(14.dp),
                        shadowElevation = 0.dp,
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("删除", fontSize = 14.sp, color = TextPrimary) },
                            onClick = {
                                menuOpen = false
                                onDelete(s)
                            },
                        )
                    }
                }
            }
        }
        // 添加表情：固定在网格末尾的加号格（唯一入口，收藏为空时它就是唯一一格）
        item(key = "sticker_add", contentType = "addTile") {
            val press = rememberNeuPressState()
            Box(
                Modifier
                    .aspectRatio(1f)
                    .scale(press.scale)
                    .clip(RoundedCornerShape(10.dp))
                    .neuSurface(10.dp, inset = true)
                    .clickable(
                        interactionSource = press.interactionSource,
                        indication = null,
                        onClick = onAdd,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    MIcons.Plus,
                    contentDescription = "从相册添加表情",
                    tint = MutedText,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun Modifier.combinedClickableSticker(
    onLongClick: () -> Unit,
): Modifier = this.composed {
    pointerInput(onLongClick) {
        detectTapGestures(onLongPress = { onLongClick() })
    }
}

/**
 * 加号面板：相册 / 拍摄 / 文件 / 通话 / 视频 五格。
 * v0.45.0：高度再压到 104dp、四周留白收紧，并把按钮按压从 Material ripple
 * 换成与输入栏一致的缩放反馈（用户要求"跟输入框周围按钮一样改小改快"）。
 * v0.47.0：第四格（原留空）放入「语音通话」——顶栏只剩三点设置，通话入口收进这里。
 * v0.53.4：原「语音通话」总开关已删，第四格**恒显示**。
 */
@Composable
fun PlusPanel(
    onAlbum: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onCall: () -> Unit,
    /** v0.58.0：视频通话（第五格） */
    onVideoCall: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(PlusPanelHeight)
            .background(NeuSurface)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        // v0.58.0：四格变五格，间距 18→10dp、格子 52→48dp。
        // 320dp 宽的窄屏算下来每格 48.8dp，正好还放得下（再挤就要压文字了）。
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PlusAction("相册", MIcons.ImageIcon, onAlbum, Modifier.weight(1f))
            PlusAction("拍摄", MIcons.Camera, onCamera, Modifier.weight(1f))
            PlusAction("文件", MIcons.FileDoc, onFile, Modifier.weight(1f))
            PlusAction("通话", MIcons.Phone, onCall, Modifier.weight(1f))
            PlusAction("视频", MIcons.Play, onVideoCall, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlusAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val press = rememberNeuPressState()
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .scale(press.scale)
                .clip(RoundedCornerShape(14.dp))
                .neuSurface(14.dp, inset = true)
                .clickable(
                    interactionSource = press.interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = TextPrimary, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(label, fontSize = 12.sp, color = MutedText)
    }
}

/**
 * emoji 全集（v0.43.1 扁平化）：按"常用 → 情绪 → 手势 → 生活"的原顺序拼接，
 * 但**不再显示分区标题**，视觉上就是一整片可滚动的 emoji。
 *
 * v0.77.0：可见性从 private 放到 internal —— 用户头像选择器（外观设置页）复用同一份名单，
 * 两处各写一份迟早会漂成"表情面板有的、头像里选不到"。
 */
internal val AllEmojis: List<String> = listOf(
    "😀", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "😉", "😊", "😍", "🥰", "😘", "😜", "🤪", "😎",
    "😢", "😭", "😞", "😔", "😟", "😠", "😡", "🤬", "😱", "😨", "😰", "😳", "🥺", "😴", "🤔", "🤗",
    "👍", "👎", "👌", "✌️", "🤞", "🤝", "👏", "🙏", "💪", "🤙", "👋", "✊", "🫶", "🤟", "☝️", "✋",
    "❤️", "💔", "💯", "🔥", "✨", "🎉", "🎂", "🍚", "🍜", "☕", "🍺", "🌙", "☀️", "⭐", "🌈", "🐱",
)

/**
 * 用户头像选 emoji 的网格（v0.77.0）。
 *
 * 与表情面板的 [EmojiGrid] 同一份名单、同一种"每行 8 个"排法，区别只有两点：
 * 收到的 [onPick] 是"设为头像"而不是"发出去"；选中的那个描一圈强调边。
 * 做成 inline 的一个小 Column（不是 Lazy）—— 本页不滚动到很深，没必要再起滚动容器。
 */
@Composable
fun EmojiAvatarGrid(
    selected: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        AllEmojis.chunked(8).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { e ->
                    val isSel = e == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (isSel) Modifier.background(
                                    NeuTrack,
                                    RoundedCornerShape(8.dp),
                                ) else Modifier
                            )
                            .tapNoRipple { onPick(e) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(e, fontSize = 23.sp, textAlign = TextAlign.Center)
                    }
                }
                repeat(8 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}