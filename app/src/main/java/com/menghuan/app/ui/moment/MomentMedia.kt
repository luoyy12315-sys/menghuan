package com.menghuan.app.ui.moment

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.menghuan.app.ui.chat.rememberLocalBitmap
import com.menghuan.app.ui.common.EaseOutQuart
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.MutedText
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextSecondary

// ============================================================
// 卡片里的配图
// ============================================================

/**
 * 一张图的原始宽高比（只读文件头，不把整张图读进内存）。
 *
 * 单图那一档要按它**自己的比例**显示：竖构图的照片硬裁成 1:1 会很难看。
 * 读不出来一律回退 4:3 —— 绝不让 UI 因为拿不到尺寸就不显示图。
 */
@Composable
private fun rememberImageAspect(path: String): Float = remember(path) {
    val raw = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight.toFloat() else null
    }.getOrNull()
    val safe = (raw ?: (4f / 3f)).takeIf { it.isFinite() && it > 0f } ?: (4f / 3f)
    safe.coerceIn(0.75f, 1.6f)
}

/** 缩略图一格：临界居中裁满 + 按压回弹；[overlay] 非空时压一层半透明蒙片（"+N" 用） */
@Composable
private fun MomentImageCell(
    path: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    overlay: String? = null,
) {
    val bitmap = rememberLocalBitmap(path)
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(110), label = "thumbPress")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(10.dp))
            .background(NeuTrack)
            .clickable(interactionSource = press, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "动态配图",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                MIcons.ImageIcon,
                contentDescription = null,
                tint = MutedText,
                modifier = Modifier.size(20.dp),
            )
        }
        if (overlay != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
                contentAlignment = Alignment.Center,
            ) {
                Text(overlay, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}

/**
 * 动态卡里的配图区（v0.76.0）：
 *
 * - **1 张**：按图片自己的比例铺满卡宽（比例夹在 0.75–1.6，横图不至于占满一屏、
 *   竖图也不会拉成面条）；
 * - **2 张 / 4 张**：两列；
 * - **其余**：三列九宫格（朋友圈的形状），第 10 张起用 "+N" 压在最后一格上。
 *
 * 只用 [rememberLocalBitmap] 的缩略图（带跨条目缓存），**这里不解全尺寸图**：
 * 相册里一张照片是 3000×4000，九宫格全解就是几十 MB，滚动必然掉帧。
 */
@Composable
fun MomentImageGrid(
    paths: List<String>,
    modifier: Modifier = Modifier,
    /** 最多铺几格；超出部分由 "+N" 承接（全屏查看时仍给出全部） */
    maxVisible: Int = 9,
    onOpen: (Int) -> Unit,
) {
    if (paths.isEmpty()) return
    val visible = paths.take(maxVisible)
    val hidden = paths.size - visible.size

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (visible.size == 1) {
            MomentImageCell(
                path = visible[0],
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(rememberImageAspect(visible[0])),
                onClick = { onOpen(0) },
            )
        } else {
            val columns = if (visible.size == 4) 2 else 3
            visible.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { indexInRow, path ->
                        val index = rowIndex * columns + indexInRow
                        MomentImageCell(
                            path = path,
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f),
                            onClick = { onOpen(index) },
                            overlay = if (index == visible.lastIndex && hidden > 0) "+$hidden" else null,
                        )
                    }
                    // 末行不足一格：补空位占位，而不是让这格被拉宽（九宫格必须对齐）
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
        }
    }
}

// ============================================================
// 位置条
// ============================================================

/**
 * 卡片上的位置（v0.76.0）：一颗定位针 + 地址文字，**纯展示、不可点**。
 *
 * 不可点是故意的：一旦做成可点，用户就会期待"点进去看地图"，而本 App 既不做地图
 * 也不做导航（地址是手写的自由文字，见 MomentPlaces），让人点了却无事发生 = 摆明是坑。
 */
@Composable
fun MomentLocationChip(location: String, modifier: Modifier = Modifier) {
    if (location.isBlank()) return
    Row(
        modifier.padding(top = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            MIcons.MapPin,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            location,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ============================================================
// 全屏查看器
// ============================================================

/** 查看器的一次性解密缓存：同时并存的只有"当前 + 左右各一张"，4 格够了 */
private val fullBitmapCache = object : android.util.LruCache<String, android.graphics.Bitmap>(4) {
    override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = 1
}

@Composable
private fun rememberFullBitmap(path: String): androidx.compose.ui.graphics.ImageBitmap? = remember(path) {
    if (path.isBlank()) return@remember null
    runCatching {
        val cached = fullBitmapCache.get(path)
        val bitmap = cached ?: decodeSampled(path, 1080)?.also { fullBitmapCache.put(path, it) }
        bitmap?.asImageBitmap()
    }.getOrNull()
}

private fun decodeSampled(path: String, targetPx: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * 动态图全屏查看（v0.76.0）：
 *
 * - 左右滑在多张之间切换（[HorizontalPager]），顶部给"第几张"角标；
 * - 捏合缩放 1–5 倍，放大后可拖动；双击 = 放大 / 还原；
 * - **未放大时下拉可关闭**（朋友圈的手势），轻点任意处也关闭；
 * - 换页时把缩放复位（否则上下一张会带着上一张的倍率进场）。
 *
 * ⚠️ 三种手势用**三个独立的 pointerInput**：Compose 会把同一串事件发给每一个，
 * 靠"缩放 <= 1 倍时放手不相干"把职责分开 ——
 * 缩小时不接平移（交给下拉关闭），放大时不接下拉（交给平移）。
 * 用一个 `detectTransformGestures` 通吃会把手势解算打架（键盘那次就栽在这上面）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MomentImageViewer(
    paths: List<String>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    if (paths.isEmpty()) return
    val page = startIndex.coerceIn(0, paths.lastIndex)
    val pager = rememberPagerState(initialPage = page, pageCount = { paths.size })

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dragY by remember { mutableFloatStateOf(0f) }
    // 换页复位：上一张的缩放/位移不该带到下一张
    LaunchedEffect(pager.currentPage) {
        if (scale != 1f || dragY != 0f) {
            scale = 1f
            offset = Offset.Zero
            dragY = 0f
        }
    }

    val dismissPx = with(androidx.compose.ui.platform.LocalDensity.current) { 110.dp.toPx() }
    val zoomed = animateFloatAsState(scale, tween(180, easing = EaseOutQuart), label = "viewerZoom").value
    val drift = animateFloatAsState(dragY, tween(180, easing = EaseOutQuart), label = "viewerDrag").value

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (scale <= 1f) onDismiss() },
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = Offset.Zero
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        } else if (scale > 1f) {
                            offset += pan
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragY = 0f },
                        onDragEnd = {
                            if (dragY > dismissPx && scale <= 1f) onDismiss() else dragY = 0f
                        },
                        onDragCancel = { dragY = 0f },
                        onVerticalDrag = { _, amount ->
                            if (scale <= 1f) dragY = (dragY + amount).coerceAtLeast(0f)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (pager.currentPage == index) {
                        // 只解当前这一页：邻页跟着解码的话，横滑会卡在第一帧上
                        val bitmap = rememberFullBitmap(paths[index])
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = "动态配图",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = zoomed
                                        scaleY = zoomed
                                        translationX = offset.x
                                        translationY = if (dragY > 0f) drift else offset.y
                                        alpha = (1f - dragY / (dismissPx * 4f)).coerceIn(0.3f, 1f)
                                    },
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            Text("图片读取失败", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }
            }
            if (paths.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${paths.size}",
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 18.dp),
                )
            }
        }
    }
}
