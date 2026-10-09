package com.menghuan.app.ui.common

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
 * 舞台背景的**档位**（v0.74.0 加视频）。
 *
 * 抽成一个封闭类而不是继续传 `(custom: Boolean, filePath: String)`：
 * 视频档进来之后，"自定义"这一维就**不够用了** —— 图片和视频都得算自定义，
 * 但画法完全不同。三档各自是明确的一种东西，调用方只要 `when` 分一次，
 * 不会出现"传了 custom=true 但 filePath 是视频、图片解码器拿视频去解"这种串档。
 */
sealed interface StageBackground {
    /** 系统预设渐变（没有任何自定义文件时） */
    data object Preset : StageBackground

    /** 用户导入的图片 */
    data class Image(val filePath: String) : StageBackground

    /**
     * 用户导入的视频（v0.74.0）。
     *
     * ⚠️ 视频背景**只在有形象时才有意义**：Live2D 是透出式 GL，背景在它背后；
     * 伪视频素材自己满屏会盖住它。这两件事都如实写在手册里，不要对用户承诺"一定有"。
     */
    data class Video(val filePath: String) : StageBackground
}

/**
 * 背景层（v0.66.0 抽出，v0.74.0 支持视频）。
 *
 * [bg] 是 [StageBackground.Preset] 或文件读不出来时都画系统预设 ——
 * 这一层永远有东西，不会"设置选了背景却是一片空页面底色"。
 * 视频档同理：引擎报错时这一层仍然画着预设（视频层只是叠在预设之上）。
 */
@Composable
fun StageBackgroundLayer(bg: StageBackground, modifier: Modifier = Modifier) {
    val imagePath = (bg as? StageBackground.Image)?.filePath.orEmpty()
    val videoPath = (bg as? StageBackground.Video)?.filePath.orEmpty()
    val bitmap = rememberStageImage(imagePath)

    LaunchedEffect(bg, bitmap != null) {
        Log.e(
            "StageBackground",
            "stage background kind=${bg::class.simpleName} " +
                "file=${imagePath.ifBlank { videoPath.ifBlank { "—" } }} " +
                "decoded=${bitmap != null}",
        )
    }

    Box(modifier.fillMaxSize()) {
        // 预设先铺底：图片/视频都叠在它之上，所以"文件读不出来"永远是"回到预设"，
        // 而不是"露白"（这是这一层最重要的一条不变量）
        Box(Modifier.fillMaxSize().background(StagePresetBrush()))
        when {
            bg is StageBackground.Image && bitmap != null -> Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            bg is StageBackground.Video && videoPath.isNotBlank() ->
                StageVideoLayer(videoPath, Modifier.fillMaxSize())
        }
    }
}

/**
 * 视频背景层（v0.74.0）。
 *
 * 照抄 [com.menghuan.app.realtime.visual.VideoVisualEngine] 那套**已验证**的做法：
 * ExoPlayer + 裸 `TextureView`（**绝不用 media3 的 PlayerView** —— 它那块 `exo_shutter`
 * 就是黑屏来源，而且多 330KB 体积，项目当初刻意没引 media3-ui）。
 *
 * 与伪视频引擎的三点不同，都是为了"它是一层背景"这件事：
 * 1. **永远静音**：背景视频的声音没有任何意义（同伪视频引擎的红线）；
 * 2. **循环播放**（`REPEAT_MODE_ONE`）；
 * 3. **不对外报 runtime**：背景播不出来就让预设露出来，不该让页面进"降级"状态
 *    （角色空间/通话的降级语义是"没有形象"，背景坏了远没到那一步）。
 *
 * ⚠️ 与 GL 层的层叠：Live2D 视图是 `TextureView`（普通 View），
 * 这块背景 `TextureView` 写在它**之前**就是垫在它下面 —— 官方层叠顺序，
 * 同 [VideoVisualEngine] 与 `CallVisualSlot` 里那条"不能回 GLSurfaceView"的规矩。
 */
@Composable
private fun StageVideoLayer(path: String, modifier: Modifier) {
    val context = LocalContext.current
    // 引擎实例绑在这个 composable 上：离开页面（路径变化/页面销毁）时 release 掉播放器。
    // 用 path 作 key，换一段视频就换一个引擎，避免复用时"上一段还没停"。
    val engine = remember(path) { StageVideoBackgroundEngine(context.applicationContext) }
    DisposableEffect(engine) {
        onDispose { engine.release() }
    }
    AndroidView(
        factory = { engine.onCreateView(it) },
        modifier = modifier,
    )
    LaunchedEffect(engine, path) { engine.play(path) }
}
