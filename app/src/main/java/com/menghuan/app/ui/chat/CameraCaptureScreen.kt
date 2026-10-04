package com.menghuan.app.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.menghuan.app.ui.theme.MIcons
import java.io.File
import kotlinx.coroutines.launch

/**
 * App 内自绘拍摄界面（v0.43.0）：加号面板「拍摄」入口进入。
 *
 * 为什么自绘而不是调系统相机：
 * 用户要求拍摄界面**符合 App 风格**。系统相机 App 的 UI 我们控制不了（各家 ROM 长得都不一样），
 * 所以用 CameraX + 一整套 Figma 黑白控件自己搭。
 *
 * ## v0.43.1 返工四项（用户真机反馈）
 * 1. **返回/翻转按钮"纯白没标志"**：CameraX 取景框里白图标铺在白底控件上，
 *    在浅色场景（白墙、户外）彻底看不见。现在改成**白色实心圆底 + 黑色图标**，
 *    黑白对比在任何取景内容上都成立。
 * 2. **底栏布局**：左 = 相册（从系统图库选图）、中 = 快门、右 = 翻转。
 *    相册走 `PickVisualMedia` 而不是自绘图库——系统图库更全（云相册、最近删除等）且零维护。
 * 3. **拍完不直接发**：进入**确认态**——整屏显示刚拍的照片 + 底部「重拍 / 发送」两颗按钮，
 *    点了发送才 `onCaptured`。避免误拍一张废图就发给 AI（还烧 token）。
 * 4. **竖拍照片变横的**：CameraX 的 ImageCapture 默认把 `rotationDegrees` 写进 EXIF，
 *    但**部分机型/部分解码路径读图时不认 EXIF**，于是竖拍原图被当成横的。
 *    现在捕获时按预览方向显式 `targetRotation`，并在**确认态解码后按 EXIF 物理旋转像素**，
 *    落盘一张真正正向的 JPEG——下游（vision 编码、气泡显示）都不用再关心方向。
 *
 * 权限：进页先查 CAMERA，未授权则发起请求；拒绝后显示说明而不是空白页。
 * 产物：拍到 filesDir/camera/ 下的 JPEG，通过 [onCaptured] 把绝对路径交回上一页。
 */
@Composable
fun CameraCaptureScreen(
    onCaptured: (String) -> Unit,
    onCancel: () -> Unit,
) {
    // v0.45.0：接住系统返回 / 侧滑返回。拍摄页是叠在聊天页上的覆盖层、不在导航栈里，
    // 不接的话侧滑会一路 pop 到 home（用户反馈"直接回到桌面了"）。现在退回到聊天页。
    BackHandler { onCancel() }

    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    // 全屏黑底（相机页与 App 主题无关，永远是深色取景框）
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            CameraContent(onCaptured = onCaptured, onCancel = onCancel)
        } else {
            CameraPermissionNotice(
                denied = permissionDenied,
                onRetry = { launcher.launch(Manifest.permission.CAMERA) },
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun CameraContent(onCaptured: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val executor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
    // 相机实例跨重组保留：每次重组都 createCameraProvider 会反复开关相机（闪屏 + 卡顿）
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var capturing by remember { mutableStateOf(false) }
    // 翻转动效（v0.45.0）：动的是**翻转按钮本身**（绕 Y 轴转半圈），不是预览画面——
    // 用户明确纠正"翻转动效是指翻转按钮的动效，不是画面那个动效"。
    // 每点一次累加 180°（转满 360° 视觉等价于 0°，归零防止数值无限增长）。
    val flipScope = rememberCoroutineScope()
    val flipSpin = remember { Animatable(0f) }
    var flipping by remember { mutableStateOf(false) }
    val latestCapture = remember { mutableStateOf<ImageCapture?>(null) }
    val currentOnCaptured by rememberUpdatedState(onCaptured)

    // 相机预览挂载点 + 最近一次预览方向（用于给 ImageCapture 设 targetRotation）
    val previewViewRef = remember { mutableStateOf<PreviewView?>(null) }
    var previewRotation by remember { mutableStateOf(0) }

    // 确认态：刚拍下的文件（null = 还在取景）
    var pendingShot by remember { mutableStateOf<File?>(null) }
    // 修好方向的位图（异步解码，未就绪时先显示占位黑）
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // 相册：系统图库选图（PickVisualMedia 是官方推荐，零权限、零维护）
    val albumPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val local = copyUriToCache(context, uri)
            if (local != null) currentOnCaptured(local.absolutePath)
        }
    }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    // 拍了照就异步把像素摆正（EXIF 方向 + 物理旋转），摆正前先让确认态挂个空位
    LaunchedEffect(pendingShot) {
        val f = pendingShot ?: run {
            pendingBitmap = null
            return@LaunchedEffect
        }
        pendingBitmap = null
        val fixed = with(java.util.concurrent.Executors.newSingleThreadExecutor()) {
            submit<Bitmap?> { normalizeOrientation(f) }
        }.get()
        // 像素摆正后**回写原文件**：下游无论怎么解码（含不读 EXIF 的路径）都是正向的
        if (fixed != null) {
            runCatching {
                f.outputStream().use { out ->
                    fixed.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }
        }
        pendingBitmap = fixed
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    previewViewRef.value = this
                }
            },
            update = { previewView ->
                previewViewRef.value = previewView
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                latestCapture.value = capture
                // 关键修正（v0.43.1）：把当前显示方向告诉 ImageCapture。
                // 不设这个的话，横竖屏/机型差异下 EXIF Orientation 会写错，
                // 竖着拍的照片在后续显示时被当成横的。
                previewView.display?.rotation?.let { r ->
                    previewRotation = r
                    capture.targetRotation = r
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                        preview,
                        capture,
                    )
                }
            },
        )

        if (pendingShot == null) {
            // ============ 取景态 ============
            // 顶栏：退出（左）+ 提示（中）
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // v0.43.1：白圆底 + 黑叉号。原来白图标叠在 16% 白底上，
                // 遇到浅色取景内容等于"隐形"（用户原话：纯白没标志）。
                CameraRoundIcon(
                    onClick = onCancel,
                    contentDescription = "退出拍摄",
                ) { Icon(MIcons.Close, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.weight(1f))
                Text(
                    "拍摄",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(40.dp)) // 对称占位，标题居中
            }

            // 底栏：相册（左）/ 快门（中）/ 翻转（右），weight 三等分保证快门精确居中
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 34.dp, start = 24.dp, end = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // 相册：白圆底 + 黑图标（同返回键的处理，保证可见）
                    CameraRoundIcon(
                        onClick = {
                            albumPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                )
                            )
                        },
                        contentDescription = "从相册选择",
                        size = 46.dp,
                    ) {
                        Icon(MIcons.ImageIcon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(21.dp))
                    }
                }

                // 快门：白色实心大圆（黑白风里唯一的高对比按钮）
                Box(
                    Modifier
                        .weight(1f)
                        .height(72.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable(enabled = !capturing) {
                                val capture = latestCapture.value ?: return@clickable
                                capturing = true
                                val dir = File(context.filesDir, "camera").apply { if (!exists()) mkdirs() }
                                val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
                                capture.takePicture(
                                    ImageCapture.OutputFileOptions.Builder(file).build(),
                                    executor,
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                            capturing = false
                                            // v0.43.1：不在回调里直接发出去，先进确认态
                                            pendingShot = file
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            capturing = false
                                        }
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.10f)),
                        )
                    }
                }

                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    // 翻转按钮：点击时**按钮自身**绕 Y 轴转半圈（预览画面不动）
                    Box(
                        Modifier.graphicsLayer {
                            rotationY = flipSpin.value
                            cameraDistance = 16f * density.density
                        },
                    ) {
                        CameraRoundIcon(
                            onClick = {
                                // 翻转期间忽略连点，避免动画互相打断
                                if (!flipping) {
                                    flipping = true
                                    // 镜头立即切换（画面不参与动画）
                                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                        CameraSelector.LENS_FACING_FRONT
                                    } else {
                                        CameraSelector.LENS_FACING_BACK
                                    }
                                    flipScope.launch {
                                        flipSpin.animateTo(flipSpin.value + 180f, tween(340))
                                        // 转满一圈后归零（360° ≡ 0°），避免数值无限增长
                                        if (flipSpin.value >= 360f) flipSpin.snapTo(flipSpin.value - 360f)
                                        flipping = false
                                    }
                                }
                            },
                            contentDescription = "翻转镜头",
                            size = 46.dp,
                        ) {
                            Icon(MIcons.FlipCamera, contentDescription = null, tint = Color.Black, modifier = Modifier.size(21.dp))
                        }
                    }
                }
            }
        } else {
            // ============ 确认态 ============
            // 整屏显示刚拍的照片（已物理摆正），底部「重拍 / 发送」。
            // 用户要求：拍完要有"发送"才真的发给 AI。
            val bmp = pendingBitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "刚拍摄的照片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 重拍：半透明白底 + 白字（次要动作）
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable {
                            pendingShot?.delete()
                            pendingShot = null
                            pendingBitmap = null
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("重拍", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                // 发送：白底黑字（主要动作）
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White)
                        .clickable(enabled = pendingBitmap != null) {
                            val f = pendingShot ?: return@clickable
                            currentOnCaptured(f.absolutePath)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "发送",
                        color = Color.Black,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * 相机页的圆形图标按钮（v0.43.1）：**白色实心圆底 + 黑色图标**。
 *
 * 为什么不是半透明底 + 白图标：白色图标在 16% 白底上几乎无对比，
 * 取景内容偏亮时完全看不见（用户真机反馈"纯白没标志"）。
 * 实心白底 + 黑图标在任意取景内容上都清晰，且符合 App 的黑白骨架语言。
 */
@Composable
private fun CameraRoundIcon(
    onClick: () -> Unit,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * 把图库 Uri 拷进私有缓存（v0.43.1）：PickVisualMedia 给的是临时读权限 Uri，
 * 出了回调可能就失效，而且 vision 编码需要真实文件路径，所以立刻落一份到 filesDir。
 */
private fun copyUriToCache(context: Context, uri: Uri): File? = runCatching {
    val dir = File(context.filesDir, "picked").apply { if (!exists()) mkdirs() }
    val out = File(dir, "pick_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { ins ->
        out.outputStream().use { outs -> ins.copyTo(outs) }
    } ?: return null
    out.takeIf { it.length() > 0 }
}.getOrNull()

/**
 * 把 JPEG 像素按 EXIF 方向 + 相机旋转要求摆正（v0.43.1 修"竖拍变横"）。
 *
 * 分两步：
 * 1. 读 EXIF `TAG_ORIENTATION`，得到需要旋转的角度（6=90°、3=180°、8=270°）；
 *    镜像情形（2/4/5/7）一并处理，避免前置摄像头自拍左右反。
 * 2. 没有 EXIF（部分机型不写）时兜底：按 `ORIENTATION_UNDEFINED` 原样返回，
 *    由调用方写入的 `targetRotation` 已经保证了方向。
 *
 * 返回 null 表示解码失败（调用方保留原文件，退化成"至少能发出去"）。
 */
private fun normalizeOrientation(file: File): Bitmap? = runCatching {
    val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
    val exif = runCatching {
        android.media.ExifInterface(file.absolutePath)
    }.getOrNull() ?: return decoded

    val orientation = exif.getAttributeInt(
        android.media.ExifInterface.TAG_ORIENTATION,
        android.media.ExifInterface.ORIENTATION_NORMAL,
    )
    val matrix = Matrix()
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        android.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f); matrix.postScale(-1f, 1f)
        }
        android.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f); matrix.postScale(-1f, 1f)
        }
        else -> return decoded
    }
    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        .also { if (it != decoded) decoded.recycle() }
}.getOrNull()

@Composable
private fun CameraPermissionNotice(denied: Boolean, onRetry: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(MIcons.Camera, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            if (denied) "需要相机权限才能拍摄\n请在系统设置里允许「梦欢」使用相机" else "正在准备相机…",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (denied) {
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text("重试授权", color = Color.Black, fontSize = 14.sp)
                }
            }
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text("返回", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}
