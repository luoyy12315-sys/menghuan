package com.menghuan.app.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/** 开屏纸色：与设计稿一致（比纯白略暖一点） */
private val SplashPaper = Color(0xFFFBFBFA)

/** 气泡墨色 */
private val Ink = Color(0xFF0C0C0C)

/** 「mh」笔画色：画在黑气泡里，只能是白 */
private val OnInk = Color(0xFFFFFFFF)

/**
 * 原动画 3.52 秒 → 压缩到约 1.5 秒。
 *
 * 冷启动等不了原速，但**笔顺和相对节奏一点没改**：所有笔画的起笔时刻与时长统一乘这个系数
 * （时间轴见 [MhLogoPaths]），所以"气泡先勾、填色后压上、m 写完好几拍才起 h"的手感还在。
 * 想再快 / 再慢只改这一个数。
 */
private const val TIME_SCALE = 0.42f

/** 画完之后停一下再淡出，让"画完了"这件事被看见 */
private const val HOLD_MS = 80L

/** logo 在开屏里的边长（1440 画布里内容本身约占 70%，所以看着比这个小一圈） */
private val SplashLogoSize = 240.dp

/**
 * 冷启动开屏：浅色纸面上把 logo 从头画一遍，画完回调 [onFinished]（由调用方淡出）。
 *
 * 底色**固定浅色**、不跟主题：logo 是「黑气泡 + 白 mh」，深色纸上黑气泡直接看不见。
 */
@Composable
internal fun MhLogoSplash(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().background(SplashPaper),
        contentAlignment = Alignment.Center,
    ) {
        MhLogoDraw(modifier = Modifier.size(SplashLogoSize), onFinished = onFinished)
    }
}

/**
 * logo 逐笔绘制动画。画的是 [MhLogoPaths] 里的矢量笔迹，不是预渲染的图片，
 * 所以任意尺寸都清晰，也不用往包里塞一堆位图。
 */
@Composable
internal fun MhLogoDraw(
    modifier: Modifier = Modifier,
    timeScale: Float = TIME_SCALE,
    onFinished: () -> Unit = {},
) {
    val ink = remember(timeScale) { MhLogoInk(timeScale) }
    val progress = remember { Animatable(0f) }
    // progress 走 0→1 就是走完整条压缩后的时间轴
    val totalMs = (MhLogoPaths.TIMELINE_SEC * timeScale * 1000f).roundToInt()
    LaunchedEffect(totalMs) {
        progress.animateTo(1f, tween(durationMillis = totalMs, easing = LinearEasing))
        delay(HOLD_MS)
        onFinished()
    }
    Canvas(modifier) {
        // 所有坐标都在 1440×1440 的画布里，缩到当前 composable 的短边
        val scale = size.minDimension / MhLogoPaths.VIEWPORT
        ink.draw(this, progress.value * totalMs, scale)
    }
}

/**
 * 一整套绘制资源：解析一次、逐帧复用。
 *
 * 怎么实现"沿笔画长出来"：logo 全是折线（只有 M/L/Z），所以**自己按长度截断**就行——
 * 累加顶点间距拿到累计长度表，要画到哪里就在那条线段上插值一个点收尾。
 * 比引贴图或走原生 PathMeasure 都轻，也不用每帧重建整条路径。
 */
private class MhLogoInk(timeScale: Float) {

    private val bubble = Polyline(MhLogoPaths.bubbleOutline)

    private val bubbleStroke = StrokeInk(
        line = bubble,
        width = MhLogoPaths.bubbleStrokeWidth,
        color = Ink,
        beginMs = MhLogoPaths.bubbleStrokeBeginSec * timeScale * 1000f,
        durMs = MhLogoPaths.bubbleStrokeDurSec * timeScale * 1000f,
        // 原 SVG 给气泡勾线挂的是 spline(0.65 0 0.35 1)：两头慢、中间快
        easing = FastOutSlowInEasing,
    )

    private val bubbleFillBeginMs = MhLogoPaths.bubbleFillBeginSec * timeScale * 1000f
    private val bubbleFillDurMs = MhLogoPaths.bubbleFillDurSec * timeScale * 1000f

    /** 「mh」的 36 段笔画；长度 0 的那段是 SVG 里的空占位，画出来也没有东西 */
    private val strokes: List<StrokeInk> = MhLogoPaths.strokes
        .filter { it.length > 0.5f }
        .map { s ->
            StrokeInk(
                line = Polyline(s.d),
                width = s.width,
                color = OnInk,
                beginMs = s.beginSec * timeScale * 1000f,
                durMs = s.durSec * timeScale * 1000f,
                easing = null,
            )
        }

    /** 气泡填充的整条路径缓存（只在尺寸变化时重建） */
    private var fillPath: Path? = null
    private var fillPathScale = -1f

    fun draw(scope: DrawScope, elapsedMs: Float, scale: Float) {
        bubbleStroke.draw(scope, elapsedMs, scale)
        // 填色比勾线晚起：勾线快画完时整块压上来（原 SVG 就是这么排的）
        val fill = ((elapsedMs - bubbleFillBeginMs) / bubbleFillDurMs).coerceIn(0f, 1f)
        if (fill > 0f) {
            if (fillPath == null || scale != fillPathScale) {
                fillPath = Path().also { bubble.buildFull(it, scale) }
                fillPathScale = scale
            }
            scope.drawPath(fillPath!!, Ink.copy(alpha = fill))
        }
        for (s in strokes) s.draw(scope, elapsedMs, scale)
    }

    /** 一段笔画：到 [beginMs] 起笔、用 [durMs] 画完，之后一直保持画完的样子 */
    private class StrokeInk(
        private val line: Polyline,
        private val width: Float,
        private val color: Color,
        private val beginMs: Float,
        private val durMs: Float,
        private val easing: Easing?,
    ) {
        /** 画完之后的整条路径缓存（只在尺寸变化时重建） */
        private var full: Path? = null
        private var fullScale = -1f
        /** 每帧复用的临时路径：只有正在画的那几段会用到 */
        private val scratch = Path()
        private var style: Stroke? = null
        private var styleScale = -1f

        fun draw(scope: DrawScope, elapsedMs: Float, scale: Float) {
            val raw = ((elapsedMs - beginMs) / durMs).coerceIn(0f, 1f)
            if (raw <= 0f) return
            val local = easing?.transform(raw) ?: raw
            val path: Path
            if (local >= 1f) {
                if (full == null || scale != fullScale) {
                    full = Path().also { line.buildFull(it, scale) }
                    fullScale = scale
                }
                path = full!!
            } else {
                line.buildInto(scratch, line.length * local, scale)
                path = scratch
            }
            if (style == null || scale != styleScale) {
                style = Stroke(
                    width = width * scale,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
                styleScale = scale
            }
            scope.drawPath(path, color, style = style!!)
        }
    }
}

/**
 * 一条折线：顶点表 + 每个顶点到起点的累计长度（截断就靠它）。
 *
 * @param d 只认 `M / L / Z`（梦欢 logo 与气泡轮廓全是折线，没有曲线），所以直接把数字顺序成对读出来即可。
 *   `Z`（闭合）会把起点再追加到末尾，这样"闭合那一段"也会被算进总长度、参与逐段生长。
 */
private class Polyline(d: String) {

    private val closed: Boolean = d.indexOf('Z') >= 0 || d.indexOf('z') >= 0
    private val pts: FloatArray
    /** cum[i] = 从起点走到第 i 个顶点的长度 */
    private val cum: FloatArray
    private val count: Int
    val length: Float

    init {
        val raw = numbers(d)
        val n = raw.size / 2
        count = if (closed && n > 1) n + 1 else n
        pts = FloatArray(count * 2)
        raw.copyInto(pts, 0, 0, (n * 2).coerceAtMost(raw.size))
        if (count > n) {
            pts[n * 2] = raw[0]
            pts[n * 2 + 1] = raw[1]
        }
        cum = FloatArray(count)
        var acc = 0f
        for (i in 1 until count) {
            val dx = pts[i * 2] - pts[(i - 1) * 2]
            val dy = pts[i * 2 + 1] - pts[(i - 1) * 2 + 1]
            acc += sqrt(dx * dx + dy * dy)
            cum[i] = acc
        }
        length = acc
    }

    /**
     * 把"已画 [drawn] 长度"的这一段写进 [out]（坐标整体乘 [scale]）。
     * 整段顶点照抄，最后落在半截的那条线段上按剩余比例插值一个点收尾。
     */
    fun buildInto(out: Path, drawn: Float, scale: Float) {
        out.reset()
        if (count == 0) return
        out.moveTo(pts[0] * scale, pts[1] * scale)
        var i = 1
        while (i < count && cum[i] <= drawn) {
            out.lineTo(pts[i * 2] * scale, pts[i * 2 + 1] * scale)
            i++
        }
        if (i < count) {
            val prev = cum[i - 1]
            val seg = cum[i] - prev
            val t = if (seg > 0.0001f) ((drawn - prev) / seg).coerceIn(0f, 1f) else 0f
            out.lineTo(
                (pts[(i - 1) * 2] + (pts[i * 2] - pts[(i - 1) * 2]) * t) * scale,
                (pts[(i - 1) * 2 + 1] + (pts[i * 2 + 1] - pts[(i - 1) * 2 + 1]) * t) * scale,
            )
        }
    }

    /** 整条路径（画完之后用）；闭合的会 [Path.close]，填充才不会缺一条边 */
    fun buildFull(out: Path, scale: Float) {
        buildInto(out, length, scale)
        if (closed) out.close()
    }

    /** 把路径串里的数字按顺序抠出来（M/L 后面都是成对的 x y，所以不用管命令字） */
    private fun numbers(d: String): FloatArray {
        val out = ArrayList<Float>(256)
        var i = 0
        while (i < d.length) {
            val c = d[i]
            if ((c in '0'..'9') || c == '-' || c == '+' || c == '.') {
                val start = i
                i++
                while (i < d.length && ((d[i] in '0'..'9') || d[i] == '.')) i++
                out.add(d.substring(start, i).toFloatOrNull() ?: 0f)
            } else {
                i++
            }
        }
        return out.toFloatArray()
    }
}
