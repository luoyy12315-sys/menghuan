package com.menghuan.app.ui.moment

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalDensity
import com.menghuan.app.data.MomentImageStore
import com.menghuan.app.data.MomentMath
import com.menghuan.app.ui.chat.rememberLocalBitmap
import com.menghuan.app.ui.common.NeuButton
import com.menghuan.app.ui.common.NeuButtonKind
import com.menghuan.app.ui.common.NeuSheetScaffold
import com.menghuan.app.ui.common.NeuTextField
import com.menghuan.app.ui.common.figmaSelection
import com.menghuan.app.ui.common.rememberClickGate
import com.menghuan.app.ui.theme.AccentInk
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuAccent
import com.menghuan.app.ui.theme.NeuDanger
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.TextSecondary

/**
 * 发动态（v0.76.0）：文字 + **最多 9 张图** + **位置**。
 *
 * ## 图为什么是"选完就落盘"，却要在丢弃的时候删掉
 * 选图那一刻就拷进 `filesDir/moment_images/`：预览、发布走的是同一份绝对路径，
 * 不用在两种表示之间来回转换。代价是"选了又反悔"会留下临时文件，所以本组件的
 * 离开路径只有一条（abort），它一定会把临时图删干净 —— 包括下滑与返回键关闭，
 * 因为 ModalBottomSheet 的 `onDismissRequest` 也走这里。
 * 「发布」那条路径则**一个文件都不删**（它们从此属于那条动态）。
 *
 * ## 位置为什么是弹层里的内联一小块，而不是再开一层
 * 位置只是一句话的选择或不填，为它再推一个全屏页 = 为一次点击付一次页面切换的代价。
     * 候选（最近用过的 + 预置几个）就在下面一行，手写一个也只多一点距离。
     *
     * @param maxCharacters 字数上限（帖子 300 / 回复 200，口径在 [MomentMath] 里）。
     * @param allowImages 回复时传 false。
     * @param allowLocation 回复时传 false。
     */
@Composable
fun MomentComposeSheet(
    /** 位置候选（最近用过的在前） */
    places: List<String>,
    onDismiss: () -> Unit,
    onSubmit: (text: String, images: List<String>, location: String) -> Unit,
    /**
     * v0.76.0：同一块补丁还要承担详情页的「回复」——回复这一层楼没有图也没有位置，
     * 所以这里给出几组开关，而不是另写一个弹层（形状一样、内容差一点的东西
     * 最容易写着写着就跑偏）。
     */
    title: String = "发动态",
    submitLabel: String = "发布",
    placeholder: String = "此刻在想什么？",
    maxCharacters: Int = MomentMath.POST_MAX,
    allowImages: Boolean = true,
    allowLocation: Boolean = true,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var images by remember { mutableStateOf(listOf<String>()) }
    var location by remember { mutableStateOf("") }
    // 防重复发布（v0.97.0，审查 U20）：弹层的关闭由**调用方**在 onSubmit 里做
    // （`composing = false`），所以按下到收起之间有一段窗口 —— 双击就是两条动态。
    val gateSubmit = rememberClickGate()

    /** 丢弃这条草稿：把临时图删掉再收弹层（上面注释里的"只有一条离开路径"） */
    fun abort() {
        MomentImageStore.deleteAll(context, images)
        onDismiss()
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MomentMath.IMAGES_MAX),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val room = MomentMath.IMAGES_MAX - images.size
        if (room <= 0) return@rememberLauncherForActivityResult
        val imported = uris.take(room).mapNotNull { MomentImageStore.import(context, it) }
        if (imported.isEmpty()) return@rememberLauncherForActivityResult
        images = images + imported
    }

    NeuSheetScaffold(title = title, onDismiss = ::abort) {
        NeuTextField(
            value = text,
            onValueChange = { if (it.length <= maxCharacters) text = it },
            placeholder = placeholder,
            singleLine = false,
            minLines = 3,
            maxLines = 8,
        )
        if (allowImages) {
            Spacer(Modifier.height(12.dp))
            DraftImageGrid(
                paths = images,
                onPick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onRemove = { path ->
                    MomentImageStore.deleteAll(context, listOf(path))
                    images = images - path
                },
            )
        }
        if (allowLocation) {
            Spacer(Modifier.height(12.dp))
            LocationPicker(
                value = location,
                places = places,
                onChange = { location = MomentMath.sanitizeLocation(it) },
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${text.length}/$maxCharacters",
                fontSize = 11.sp,
                color = if (text.length >= maxCharacters) NeuDanger else MutedText,
            )
            if (images.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text("图 ${images.size}/${MomentMath.IMAGES_MAX}", fontSize = 11.sp, color = MutedText)
            }
            Spacer(Modifier.weight(1f))
            NeuButton(
                text = submitLabel,
                onClick = {
                    if (!gateSubmit()) return@NeuButton
                    if (text.isBlank() && images.isEmpty()) return@NeuButton
                    onSubmit(text, images, location)
                },
                kind = NeuButtonKind.Primary,
                enabled = text.isNotBlank() || images.isNotEmpty(),
                modifier = Modifier.width(120.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * 草稿里的图片：已选的缩略图 + 一张「再加一张」的虚线格。
 *
 * 「+」那格用 [figmaSelection] 的虚线与四角手柄（本 App 的签名元素，意思是"可编辑"），
 * 而不是再画一个图标按钮 —— 它与"选中态是虚线框"这套语言本来就是同一回事。
 * 满 9 张时这格消失（此时新选的图也不再被接受，**要让人看得见上限到了**）。
 */
@Composable
private fun DraftImageGrid(
    paths: List<String>,
    onPick: () -> Unit,
    onRemove: (String) -> Unit,
) {
    LazyRow(
        Modifier.fillMaxWidth(),
        // figmaSelection 的虚线 + 四角手柄画在布局边界**外面**（各 6.5dp），
        // 不给这份余量，最外圈的虚线会被 LazyRow 裁掉一截
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(paths, key = { it }) { path ->
            DraftThumb(path) { onRemove(path) }
        }
        if (paths.size < MomentMath.IMAGES_MAX) {
            item("add") {
                Box(
                    Modifier
                        .size(72.dp)
                        .figmaSelection(active = true, cornerRadius = 10.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onPick,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MIcons.Plus,
                        contentDescription = "添加图片",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** 一张待发图的缩略图：右上角一颗删除圆钮（点在图上删，比长按再选菜单直接得多） */
@Composable
private fun DraftThumb(path: String, onRemove: () -> Unit) {
    val bitmap = rememberLocalBitmap(path)
    Box(Modifier.size(72.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(NeuTrack),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "已选图片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    MIcons.ImageIcon,
                    contentDescription = null,
                    tint = MutedText,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(20.dp)
                .background(NeuAccent, RoundedCornerShape(999.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onRemove,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                MIcons.Close,
                contentDescription = "移除这张图",
                tint = AccentInk,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}

/**
 * 位置：一行入口 + 内联展开的候选。
 *
 * 没填 → 「添加地点」；填了 → 地址 + 一颗清除钮。**点同一颗地图针也能收回去**，
 * 不要再给第二个"完成"按钮：一个开关同颗粒控制一块内容，才是 Compose 里最省的移动部件。
 */
@Composable
private fun LocationPicker(
    value: String,
    places: List<String>,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = !expanded },
                )
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                MIcons.MapPin,
                contentDescription = null,
                tint = if (value.isBlank()) MutedText else TextPrimary,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                if (value.isBlank()) "添加地点" else value,
                fontSize = 14.sp,
                color = if (value.isBlank()) MutedText else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (value.isNotBlank()) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onChange("") },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MIcons.Close,
                        contentDescription = "清除地点",
                        tint = MutedText,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)),
        ) {
            Column {
                NeuTextField(
                    value = value,
                    onValueChange = { if (it.length <= MomentMath.LOCATION_MAX) onChange(it) },
                    placeholder = "手动填写地点",
                    singleLine = true,
                )
                if (places.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 候选最多露出 4 个：再多这一行就要开始横滑找"家"了
                        places.take(4).forEach { place ->
                            val selected = place == value
                            Text(
                                place,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                color = if (selected) AccentInk else TextSecondary,
                                maxLines = 1,
                                modifier = Modifier
                                    .background(
                                        if (selected) NeuAccent else NeuTrack,
                                        RoundedCornerShape(999.dp),
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = { onChange(if (selected) "" else place) },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
