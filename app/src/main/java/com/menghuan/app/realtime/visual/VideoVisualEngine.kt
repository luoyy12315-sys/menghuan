package com.menghuan.app.realtime.visual

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.menghuan.app.data.CallVisualAsset
import com.menghuan.app.data.CallVisualSlots
import com.menghuan.app.data.CallVisualStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 伪视频视觉引擎（v0.58.0，方案文档 §十三～§十七）。
 *
 * "伪视频"= 一组**短循环素材**按通话状态切换，不是放一条长视频，更不是实时视频源：
 * 真声音永远来自实时 AI 通话，**这里的播放器全程静音**（方案文档 §十三的红线）。
 *
 * 三个刻意的选择：
 *
 * 1. **单播放器 + TextureView，不用 media3 的 PlayerView**。
 *    PlayerView 那块 `exo_shutter` 背景就是黑屏来源；TextureView 在新一帧画出来之前
 *    **一直显示上一帧**，所以 idle↔speaking 的过渡是"画面留住"而不是"闪一下黑"。
 *    §十七要的正是这个，顺带省掉 media3-ui 的体积。
 * 2. **一个播放器实例用到底**，换素材只换 MediaItem（§十七"不重复创建播放器"）。
 * 3. **状态缺素材自动回退待机**（§十四）；连待机都没有才降级成无形象——
 *    用户只传一条 idle.mp4 也必须能用。
 * 4. **v0.60：铺满整屏的口径也在这里**（用户要求"9:16 竖屏全屏铺满、不黑边不拉伸"）——
 *    视图填满页面，画面按 cover 缩放（放大到刚好盖住、居中裁边，见 [applyCoverTransform]）。
 *    页面的 Compose 层只负责"把整屏交给它"，不做任何按比例排卡片的事——
 *    那正是以前竖屏素材把字幕与静音/挂断挤出屏幕的根因。
 */
class VideoVisualEngine(
    private val appContext: Context,
    private var asset: CallVisualAsset,
) : CallVisualEngine {

    private val _runtime = MutableStateFlow<VisualRuntime>(VisualRuntime.Loading)
    override val runtime: StateFlow<VisualRuntime> = _runtime.asStateFlow()

    private var player: ExoPlayer? = null
    private var surface: Surface? = null

    /** 显示用的 TextureView（v0.60：cover 矩阵挂在它身上，见 [applyCoverTransform]） */
    private var textureView: TextureView? = null

    /**
     * 画面的**显示**宽高比（宽/高，v0.60）。
     *
     * load 阶段先用素材元数据给一个初值；播放器报出真实尺寸后再校正。
     * 带旋转元数据的素材要**换过来算**：Media3 在部分机型上把旋转留在
     * `unappliedRotationDegrees` 里没应用（CHANGELOG v0.58 已记"留待真机定夺"），
     * 这时按解码帧的宽高铺满会又歪又裁错边。
     */
    @Volatile
    private var displayAspect = 0f

    /** 当前正在播的状态槽：同一个槽重复进来不再换源（状态来回抖动时不该重新 prepare） */
    private var playingSlot: String? = null

    /** 首帧渲染出来了没有（页面据此才切视频布局，见 [VisualRuntime.Ready.drawn]） */
    @Volatile
    private var drawn = false

    private var personaId: Long = 0L
    private var released = false
    private var callState = CallVisualState.IDLE

    override suspend fun load(personaId: Long, asset: CallVisualAsset) {
        this.personaId = personaId
        this.asset = asset
        // 只认"此刻文件真的在"的素材：被手工删过 / 目录被清理时，这就是 §二十四
        // "检测资源不存在 → 降级"的判点，而不是等到通话页黑屏才发现
        val usable = asset.videoStates.keys
            .mapNotNull { key -> CallVisualStore.videoFile(appContext, personaId, asset, key)?.let { key to it } }
            .toMap()
        if (usable.isEmpty()) {
            _runtime.value = VisualRuntime.Degraded("视频素材已不存在，本次通话只用头像")
            return
        }
        // ⚠️ Ready 必须在 load 阶段就发出去，不能等播放器报首帧：
        // 页面只在 Ready 时才创建那块视图，而视图没创建就没有 surface、没有 surface 就不报尺寸——
        // 互相等就永远停在"没有形象"（和 Live2D 那边同一个坑）。
        // 宽高比直接读素材自己的元数据，播放器报出来之后再校正一次。
        val first = usable[CallVisualSlots.IDLE] ?: usable.values.first()
        displayAspect = CallVisualStore.aspectOf(first)
        publishReady()
        // ExoPlayer 的构造要求调用线程有 Looper；VM 那边可能从 IO 协程进来，这里自己钉主线程
        withContext(Dispatchers.Main) {
            if (released) return@withContext
            if (player == null) {
                player = ExoPlayer.Builder(appContext).build().apply {
                    // 视频原声必须关掉：AI 的声音只有实时通话 AudioTrack 这一条链路
                    volume = 0f
                    repeatMode = Player.REPEAT_MODE_ONE
                    playWhenReady = false
                    addListener(listener)
                }
            }
            // 待机先备好，视图一挂上就能出画面
            applySlot(VideoStateResolver.slotFor(callState), usable)
        }
    }

    override fun onCreateView(context: Context): View = TextureView(context).apply {
        isOpaque = false
        textureView = this
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val s = Surface(st)
                surface = s
                player?.setVideoSurface(s)
                // 有地方渲染了才真的开始解码循环（省电）
                player?.playWhenReady = !released
                // 尺寸此刻才定下来，cover 矩阵要在这时补一次
                applyCoverTransform()
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                player?.setVideoSurface(null)
                runCatching { surface?.release() }
                surface = null
                return true
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                // 旋转屏 / 分屏改变视图大小时重算 cover，否则画面会被拉伸
                applyCoverTransform()
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    /**
     * 给 TextureView 设 **cover 矩阵**（v0.60）：画面放大到"刚好盖住"视图、居中，
     * 溢出的一边裁掉 —— 这就是用户要的"铺满且不拉伸不变形"。
     *
     * 为什么放在引擎里而不是 Compose 层：Compose 侧的 `Modifier.size()` 会被父约束
     * **夹回容器大小**（要突破约束得用 `requiredSize` 再解决居中与裁切，绕一大圈还不稳）；
     * 而 TextureView 的 `setTransform` 本来就是干这个的（媒体层标准做法），
     * GL 那边（Live2D）用不上它，它有自己的 `fitProjection`。
     *
     * 口径：把纹理按"显示比例 × 1"的单位矩形算，`scale = max(容器宽 / 比例, 容器高)`，
     * 再把放大后的矩形居中摆进去。比例未知（还没读到元数据）时不设变换，保持默认拉伸 ——
     * 只可能出现在 Ready 之后、首帧之前的极短窗口，播放器一报尺寸就会校正。
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

    override fun setCallState(state: CallVisualState) {
        callState = state
        if (released || player == null) return
        applySlot(VideoStateResolver.slotFor(state), null)
    }

    /**
     * 切到某个状态槽的素材。
     *
     * [usable] 非空时用它做文件定位（加载阶段刚查过一遍，不必再摸磁盘）；
     * 运行期传 null，改为现查现用——素材在半通电话里被动消失也要走回退而不是崩。
     */
    private fun applySlot(slot: String, usable: Map<String, File>?) {
        val p = player ?: return
        if (slot == playingSlot) return
        fun locate(key: String): File? =
            usable?.get(key) ?: CallVisualStore.videoFile(appContext, personaId, asset, key)
        val file = locate(slot)
            // 该状态没素材 → 回落待机（§十六的匹配优先级）
            ?: locate(CallVisualSlots.IDLE)
            ?: run {
                _runtime.value = VisualRuntime.Degraded("视频素材读不到了，本次通话只用头像")
                return
            }
        playingSlot = slot
        // MediaItem.fromUri 只收 String 或 android.net.Uri（不收 java.net.URI），
        // 本地文件走 content-free 的 file Uri
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        p.playWhenReady = surface != null
    }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(size: VideoSize) {
            if (size.width <= 0 || size.height <= 0) return
            // Media3 的 VideoSize 已经是"该显示成多大"（含像素宽高比的处理）：
            // pixelWidthHeightRatio 非 1 的方形像素素材要再乘一次；
            // 旋转留在 unappliedRotationDegrees 里没应用时（部分机型）宽高换过来算，
            // 否则 cover 会按错误的比例裁边（画面又歪又裁错地方）。
            val rotated = size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270
            val w = size.width * size.pixelWidthHeightRatio
            val h = size.height.toFloat()
            displayAspect = if (rotated) h / w else w / h
            publishReady()
            applyCoverTransform()
        }

        /** 首帧真的渲染出来了 —— 页面据此才从大头像布局切到视频布局 */
        override fun onRenderedFirstFrame() {
            drawn = true
            publishReady()
        }

        override fun onPlayerError(error: PlaybackException) {
            // 解码器报错（素材编码这机器不支持、文件半途坏）：**只降级视觉，通话继续**
            _runtime.value = VisualRuntime.Degraded("这段视频播不了（${error.errorCodeName}），本次通话只用头像")
            runCatching { player?.playWhenReady = false }
        }
    }

    private fun publishReady() {
        _runtime.value = VisualRuntime.Ready(displayAspect, drawn)
    }

    /** 页面切后台 / 锁屏：停在当前画面。TextureView 留着上一帧，不会黑 */
    override fun pause() {
        runCatching { player?.playWhenReady = false }
    }

    /** 回前台：只重新订阅当前视觉状态，**绝不去碰通话会话**（方案文档 §二十三） */
    override fun resume() {
        val p = player ?: return
        if (released) return
        p.playWhenReady = surface != null
        applySlot(VideoStateResolver.slotFor(callState), null)
    }

    override fun release() {
        if (released) return
        released = true
        runCatching { player?.removeListener(listener) }
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { surface?.release() }
        surface = null
        textureView = null
        playingSlot = null
        drawn = false
        _runtime.value = VisualRuntime.Empty
    }
}
