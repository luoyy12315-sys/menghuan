package com.menghuan.app.ui.common

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import com.menghuan.app.ui.theme.BrandBlue
import com.menghuan.app.ui.theme.BrandGreen
import com.menghuan.app.ui.theme.NeuBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「舞台背景」= **角色空间**与**视频通话**共用的那一层（v0.66.0 抽出来，两处一套画法）。
 *
 * 要的东西一模一样：要么系统预设那张渐变，要么用户从相册挑的一张图；铺满整屏、垫在角色底下。
 * 两边共用而不是各画一遍，是因为上一版正是"各画一遍"才出的事：
 * 角色空间说背景是淡彩、通话页说背景是底色，用户看不出哪个才是设置里那一条。
 *
 * ⚠️ **必须画得出颜色**。v0.64.0 画的是品牌色 `alpha = 0.32` 直接铺在白底上，肉眼等于没有，
 * 而角色那一层是透出的 GL —— 结果就是真机反馈的"角色一出来背景就没了"。
 * 现在拿**页面底色**与品牌色做 [lerp] 混合：浅色主题得淡彩、深色主题得暗彩，
 * 两档主题下都真的有颜色，又不会浓到把角色吃掉。
 *
 * 还带一条**探针**（[StageBackgroundLayer] 里那句 `stage background …`）：
 * 把「档位 / 文件 / 解码结果」三个读数一次打全，"选了背景却没变化"从此分得清是
 * ①没存进去 ②文件不在 ③解码失败 三种里的哪一种。这类"看不见"的问题靠猜要烧掉三个版本
 *（AGENTS §零.2、§零.7 那条 Live2D 着色器的教训是同一件事）。
 */
private const val MAX_STAGE_PX = 1600

/** 系统预设那张渐变（品牌色只做"用户给角色挑的舞台"这种内容点缀，不动界面骨架） */
@Composable
fun StagePresetBrush(): Brush {
    // 颜色令牌只能在 composable 作用域里读，先取成局部变量再传进 Brush
    val base = NeuBackground
    return Brush.linearGradient(
        listOf(
            lerp(base, BrandGreen, 0.42f),
            lerp(base, BrandBlue, 0.20f),
        ),
    )
}

/**
 * 按路径解码背景图（IO 线程 + 降采样）。
 *
 * 降采样是必须的：相册里随手挑一张 4K 照片就是 20MB / 8000×6000，
 * 原样塞进 Image 会直接吃掉几十 MB 显存 —— 而背景只需要铺满一块屏幕。
 */
@Composable
private fun rememberStageImage(path: String) = produceState<ImageBitmap?>(
    initialValue = null,
    path,
) {
    value = if (path.isBlank()) {
        null
    } else {
        // ⚠️ `produceState` 的块默认跑在**主线程**（它挂在组合作用域上）。
        // 相册里随手一张就是 8000×6000，即便降采样到 1600 边也要真的解一遍码 ——
        // 留在主线程就是"进视频通话那一下卡一帧"，所以显式切到 IO。
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > MAX_STAGE_PX || bounds.outHeight / sample > MAX_STAGE_PX) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )?.asImageBitmap()
            }.onFailure { Log.e("StageBackground", "stage image decode failed path=$path", it) }
                .getOrNull()
        }
    }
}.value

/**
 * 背景层。[custom]=false 或图读不出来时都画系统预设 ——
 * 这一层永远有东西，不会"设置选了背景却是一片空页面底色"。
 */
@Composable
fun StageBackgroundLayer(custom: Boolean, filePath: String, modifier: Modifier = Modifier) {
    val bitmap = rememberStageImage(filePath)

    LaunchedEffect(custom, filePath, bitmap != null) {
        Log.e(
            "StageBackground",
            "stage background custom=$custom file=${filePath.ifBlank { "—" }} decoded=${bitmap != null}",
        )
    }

    Box(modifier.fillMaxSize()) {
        if (custom && bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize().background(StagePresetBrush()))
        }
    }
}
