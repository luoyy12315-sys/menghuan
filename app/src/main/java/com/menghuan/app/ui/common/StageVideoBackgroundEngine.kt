package com.menghuan.app.ui.common

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「舞台背景视频」引擎（v0.74.0）。
 *
 * 这是 [com.menghuan.app.realtime.visual.VideoVisualEngine] 的**背景版**：
 * 同一套 ExoPlayer + 裸 `TextureView` 的做法（那套已经在真机上跑过一年），
 * 但目标完全不同 —— 它只是一层**垫在角色底下、静音循环**的背景，不承载任何通话语义。
 *
 * ## 三个刻意不同的地方
 * 1. **静音**：背景视频出声是纯粹的噪音（真声音永远只有实时通话那一条链路）；
 * 2. **循环**：`REPEAT_MODE_ONE`，一段 10 秒的素材就够铺满一整场；
 * 3. **不回退不降级**：播不出来只是"这一层透出预设"（[StageBackgroundLayer] 已经把预设
 *    垫在它下面了），**绝不**让页面进"没有形象"的降级态 —— 背景坏了远没到那一步。
 *
 * ## 为什么不用 media3-ui 的 PlayerView
 * 项目刻意没引 `media3-ui`（`app/build.gradle.kts` 有记录）：那块 `exo_shutter`
 * 背景就是黑屏来源，而且多 330KB 体积。裸 `TextureView` 是这里唯一正确的形态。
 *
 * ## 层叠（照抄 VideoVisualEngine 那条已验证的结论）
 * Live2D 视图是 `TextureView`（普通 View，透明处透出下层），
 * 所以这块背景 `TextureView` 只要写在它**之前**就正确垫在下面。
 * 绝不能为了"省一层"改用 `GLSurfaceView`——它会挖洞擦掉上层子树。
 */
internal class StageVideoBackgroundEngine(private val appContext: Context) {

    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var textureView: TextureView? = null

    /** 画面的显示宽高比（宽/高）；未知时先用 0，播放器报尺寸后再纠 */
    @Volatile
    private var displayAspect = 0f

    private var released = false

    /** 当前在播的那条路径：同一条重复进来不重 prepare */
    private var playingPath: String? = null

    fun onCreateView(context: Context): View = TextureView(context).apply {
        isOpaque = false
        textureView = this
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val s = Surface(st)
                surface = s
                player?.setVideoSurface(s)
                player?.playWhenReady = !released
                applyCoverTransform()
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // 顺序不能反：先摘 surface 再释放，直接 release 会 IllegalStateException
                player?.setVideoSurface(null)
                runCatching { surface?.release() }
                surface = null
                return true
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                applyCoverTransform()
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    /**
     * 播一条背景视频。[path] 空 / 文件不在 → 什么都不做（预设自然露出来）。
     *
     * ⚠️ ExoPlayer 构造要求当前线程有 Looper（与 [com.menghuan.app.realtime.visual.VideoVisualEngine]
     * 同一条坑），所以整个过程钉在主线程。这里由调用方从 `LaunchedEffect`（主线程）进来，
     * 但仍然显式切一次，防以后有别的调用点从 IO 进来。
     */
    suspend fun play(path: String) {
        if (released) return
        if (path.isBlank()) return
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) {
            Log.w(TAG, "stage video missing path=$path")
            return
        }
        withContext(Dispatchers.Main) {
            if (released) return@withContext
            if (playingPath == path && player != null) return@withContext
            val p = player ?: ExoPlayer.Builder(appContext).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = false
                addListener(listener)
                player = this
            }
            playingPath = path
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            p.prepare()
            p.playWhenReady = surface != null
        }
    }

    /**
     * cover 变换：画面放大到"刚好盖住"视图、居中、溢出裁掉。
     * 与 [com.menghuan.app.realtime.visual.VideoVisualEngine.applyCoverTransform] 同口径
     * （背景不分区，所以没有 bottomInset 那一维）。
     */
    private fun applyCoverTransform() {
        val view = textureView ?: return
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        val aspect = displayAspect
        if (vw <= 0f || vh <= 0f || aspect <= 0f) return
        runCatching {
            val scale = maxOf(vw / aspect, vh)
            val m = android.graphics.Matrix()
            m.setScale(scale, scale)
            m.postTranslate((vw - aspect * scale) / 2f, (vh - scale) / 2f)
            view.setTransform(m)
        }
    }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.width <= 0 || size.height <= 0) return
            val rotated = size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270
            val w = size.width * size.pixelWidthHeightRatio
            val h = size.height.toFloat()
            displayAspect = if (rotated) h / w else w / h
            applyCoverTransform()
        }

        override fun onPlayerError(error: PlaybackException) {
            // **只记日志，不降级页面**：背景播不了就退回预设，页面其余部分毫发无损
            Log.e(TAG, "stage video playback failed: ${error.errorCodeName}", error)
            runCatching { player?.playWhenReady = false }
        }
    }

    fun release() {
        if (released) return
        released = true
        runCatching { player?.removeListener(listener) }
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { surface?.release() }
        surface = null
        textureView = null
        playingPath = null
    }

    private companion object {
        const val TAG = "StageVideoBackground"
    }
}
