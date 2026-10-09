package com.menghuan.app.realtime.visual.live2d

import com.live2d.sdk.cubism.framework.id.CubismId
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.model.CubismModel
import com.menghuan.app.live2d.InteractionRegion
import com.menghuan.app.live2d.InteractionRules
import kotlin.math.abs

/**
 * 点角色时"点到了哪"——**命中检测**（v0.82.0 P2-3 从 `Live2dCharacter` 拆出来）。
 *
 * 单独成一份有三个理由，都是当年踩过的：
 *
 * 1. **它跨线程**。`hitRegion` 是从 UI 线程（点角色的那条路）调进来的，而它要读的投影是
 *    GL 线程每帧重写的。所以这里存的是**四个 volatile float 的快照**，不是那个矩阵对象
 *    （共享可变对象会读到"半新半旧"的一份，表现为偶发地点错区域：点了头判成身体）。
 * 2. **它要的是"角色静止时画在哪"**，不是"这一帧画在哪"。翻跟斗那类动作会把整张画布转起来，
 *    用转过的矩阵去反算触点，结果会跟着转 —— 用户点脸可能判成点身体。
 * 3. **它有一份几何兜底**。多数正规模型自带 `HitAreas`（`Head` / `Body`，Live2D 官方约定名），
 *    但大量免费模型这块是空的，那就退回按模型包围盒的"上中下三段"。两条都得在，
 *    只留一条会在另一半模型上完全点不动。
 */
internal class Live2dHitResolver {

    /** 模型自带 HitArea（Head / Body）的 CubismId；模型没写 `HitAreas` 时是 null */
    @Volatile private var headHitId: CubismId? = null
    @Volatile private var bodyHitId: CubismId? = null

    /**
     * 是否需要几何兜底命中检测：**只有当模型没定义 `Head`/`Body` 两个 HitArea 时才开**。
     *
     * 开着就要额外求一次模型包围盒（遍历全部顶点），所以刷新频率收在
     * [BOUNDS_INTERVAL_SECONDS] 一次，不是每帧。而绝大多数正规模型都有 HitArea，
     * 压根不该为它们付这份开销。
     */
    @Volatile var needsGeometricHitTest = false
        private set

    /** 命中检测用的投影**四个数**（缩放 X/Y + 平移 X/Y，**不含舞台姿态**） */
    @Volatile private var hitScaleX = 0f
    @Volatile private var hitScaleY = 0f
    @Volatile private var hitTranslateX = 0f
    @Volatile private var hitTranslateY = 0f

    /** 几何兜底用的模型包围盒（模型坐标系，GL 线程刷，UI 线程读） */
    @Volatile private var boundLeft = 0f
    @Volatile private var boundRight = 0f
    @Volatile private var boundTop = 0f
    @Volatile private var boundBottom = 0f
    @Volatile private var boundsReady = false

    /** 距离上一次刷新包围盒过了多少秒，见 [BOUNDS_INTERVAL_SECONDS] */
    private var boundsAgeSeconds = Float.MAX_VALUE

    /**
     * 载入这张模型的点击区域清单（GL 线程，加载时调一次）。
     *
     * 优先用模型自带的 HitArea；两个都没有才开几何兜底。
     */
    fun configure(head: CubismId?, body: CubismId?) {
        headHitId = head
        bodyHitId = body
        needsGeometricHitTest = head == null && body == null
        boundsReady = false
        boundsAgeSeconds = Float.MAX_VALUE
    }

    /**
     * 把这一帧的投影抄一份下来（GL 线程，每帧在 `fitProjection` 末尾调）。
     *
     * 反算要的就是 `model = (ndc - t) / s`，与 `CubismMatrix44.invertTransformX/Y`
     * 同一个式子 —— 只是不共享那个每帧被重写的对象。
     */
    fun snapshot(modelMatrix: CubismMatrix44, projection: CubismMatrix44) {
        val m = modelMatrix.array
        val p = projection.array
        hitScaleX = m[0] * p[0]
        hitScaleY = m[5] * p[5]
        // (M·P) 的平移列 = M 的缩放 × P 的平移 + M 自己的平移 —— 少加那一项的话，
        // 一旦 modelMatrix 带过 setPosition（模型画布不居中）点击就会整体偏一位
        hitTranslateX = m[0] * p[12] + m[12]
        hitTranslateY = m[5] * p[13] + m[13]
    }

    /**
     * 点在某处该算哪个互动区域（**任意线程可调**）。
     *
     * 传进来的是 **NDC 坐标**（-1..1，y 向上）—— 与 `fitProjection` 建立的投影一致。
     * 模型自带 HitArea 优先，没有就走几何分区（见 [InteractionRules]）。
     */
    fun regionAt(
        ndcX: Float,
        ndcY: Float,
        isHit: (CubismId, Float, Float) -> Boolean,
    ): InteractionRegion {
        val sx = hitScaleX
        val sy = hitScaleY
        if (abs(sx) < 1e-6f || abs(sy) < 1e-6f) return InteractionRegion.OTHER
        val mx = (ndcX - hitTranslateX) / sx
        val my = (ndcY - hitTranslateY) / sy

        val head = headHitId
        val body = bodyHitId
        if (head != null || body != null) {
            val headHit = head?.let { runCatching { isHit(it, mx, my) }.getOrDefault(false) } ?: false
            val bodyHit = body?.let { runCatching { isHit(it, mx, my) }.getOrDefault(false) } ?: false
            return InteractionRules.fromHitAreas(headHit, bodyHit)
        }

        if (!boundsReady) return InteractionRegion.OTHER
        val spanX = boundRight - boundLeft
        val spanY = boundBottom - boundTop
        if (spanX <= 1e-4f || spanY <= 1e-4f) return InteractionRegion.OTHER
        val nx = (mx - boundLeft) / spanX
        // 模型坐标 y 向上、屏幕 y 向下，所以这里要翻过来
        val ny = (boundBottom - my) / spanY
        if (nx < 0f || nx > 1f) return InteractionRegion.OTHER
        return InteractionRules.fromBands(ny)
    }

    /** 卸载（与 configure 配套，让下一张模型重新起算） */
    fun reset() {
        headHitId = null
        bodyHitId = null
        needsGeometricHitTest = false
        boundsReady = false
        boundsAgeSeconds = Float.MAX_VALUE
        hitScaleX = 0f
        hitScaleY = 0f
        hitTranslateX = 0f
        hitTranslateY = 0f
    }

    /**
     * 几何兜底的包围盒**不用每帧算**：这个值只在用户点下去的那一刻用，
     * 而算一次要把全部 drawable 的全部顶点读一遍（免费模型几千到几万次数组读）。
     * 角色自己呼吸摆动让包围盒缓慢变形，但点的判档本来就带区间余量，
     * 15Hz 与 60Hz 用户点起来没有任何区别。
     */
    fun maybeRefreshBounds(model: CubismModel, dt: Float) {
        // 自带 HitArea 的模型压根用不上包围盒，一次也不算
        if (!needsGeometricHitTest) return
        boundsAgeSeconds += dt
        if (boundsAgeSeconds >= BOUNDS_INTERVAL_SECONDS) {
            boundsAgeSeconds = 0f
            refreshBounds(model)
        }
    }

    /** GL 线程：把整张模型所有顶点的包围盒算出来（只在没有 HitArea 的模型上跑） */
    private fun refreshBounds(m: CubismModel) {
        var l = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until m.drawableCount) {
            val v = runCatching { m.getDrawableVertexPositions(i) }.getOrNull() ?: continue
            var k = 0
            while (k + 1 < v.size) {
                val x = v[k]
                val y = v[k + 1]
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                k += 2
            }
        }
        if (l > r || t > b) return
        boundLeft = l
        boundRight = r
        boundTop = t
        boundBottom = b
        boundsReady = true
    }

    private companion object {
        /** 几何兜底的包围盒多久刷一次（秒）。见 [needsGeometricHitTest] 与 [maybeRefreshBounds] */
        const val BOUNDS_INTERVAL_SECONDS = 1f / 15f
    }
}
