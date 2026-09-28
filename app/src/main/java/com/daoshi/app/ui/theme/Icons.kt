package com.daoshi.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 图标库：全部 path 直接照搬设计基准 preview/all.html 里的 SVG（viewBox 24×24）。
 * 设计约定：21–23dp 纯线条符号，stroke 1.6–1.8，圆头圆角，没有底色圆。
 */
private val GlyphInk = Color(0xFF000000)

/** SVG path 字符串 -> PathNode 列表（Compose 1.7 的 PathData 工厂只收 DSL lambda，故走 PathParser） */
private fun nodes(path: String): List<PathNode> = PathParser().parsePathString(path).toNodes()

private fun strokes(
    name: String,
    strokeWidth: Float = 1.8f,
    vararg paths: String,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    paths.forEach { d ->
        addPath(
            pathData = nodes(d),
            stroke = SolidColor(GlyphInk),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}.build()

private fun filled(
    name: String,
    vararg paths: String,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    paths.forEach { d ->
        addPath(pathData = nodes(d), fill = SolidColor(GlyphInk))
    }
}.build()

/** 整圆：用两段半圆弧拼出 circle(cx, cy, r) */
private fun circleData(cx: Float, cy: Float, r: Float): String {
    val d = r * 2
    return "M${cx - r} ${cy}a$r $r 0 1 0 $d 0a$r $r 0 1 0 ${-d} 0"
}

object MIcons {
    // ===== 顶栏 =====
    val Plus = strokes("Plus", 1.8f, "M12 5L12 19", "M5 12L19 12")
    val Sliders = strokes(
        "Sliders", 1.7f,
        "M4 6.5L20 6.5",
        circleData(15f, 6.5f, 2.2f),
        "M4 12L20 12",
        circleData(8.5f, 12f, 2.2f),
        "M4 17.5L20 17.5",
        circleData(17f, 17.5f, 2.2f),
    )
    val Back = strokes("Back", 1.8f, "M15 4.5L7.5 12L15 19.5")
    val Search = strokes(
        "Search", 1.8f,
        circleData(11f, 11f, 6.5f),
        "M16 16L20.5 20.5",
    )

    // ===== 输入栏 =====
    val Heart = strokes(
        "Heart", 1.8f,
        "M12 20.5C7 16.5 3 13.3 3 9.5C3 7 5 5 7.5 5C9.2 5 10.8 5.9 12 7.4C13.2 5.9 14.8 5 16.5 5C19 5 21 7 21 9.5C21 13.3 17 16.5 12 20.5Z",
    )
    val Plane = filled("Plane", "M2 21L23 12L2 3L2 10L17 12L2 14Z")
    val Camera: ImageVector = ImageVector.Builder(
        name = "Camera",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = nodes(
                "M7.5 7L16.5 7a4.5 4.5 0 0 1 4.5 4.5L21 15.5a4.5 4.5 0 0 1 -4.5 4.5" +
                    "L7.5 20a4.5 4.5 0 0 1 -4.5 -4.5L3 11.5a4.5 4.5 0 0 1 4.5 -4.5Z",
            ),
            stroke = SolidColor(GlyphInk),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = nodes(circleData(12f, 13.5f, 4f)),
            stroke = SolidColor(GlyphInk),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(pathData = nodes(circleData(17.2f, 10f, 0.9f)), fill = SolidColor(GlyphInk))
    }.build()

    // ===== 底部导航 =====
    val ChatBubble = strokes(
        "ChatBubble", 1.8f,
        "M12 3C6.5 3 2 6.9 2 11.7C2 14.3 3.3 16.6 5.4 18.2C5.2 19.4 4.7 20.7 3.8 22" +
            "C5.7 21.8 7.4 21.1 8.7 20.3C9.7 20.6 10.8 20.7 12 20.7C17.5 20.7 22 16.8 22 12" +
            "C22 7.2 17.5 3 12 3Z",
    )
    val BarChart = strokes(
        "BarChart", 1.8f,
        "M4 20L4 10", "M10 20L10 4", "M16 20L16 12", "M22 20L2 20",
    )
    val Checklist = strokes(
        "Checklist", 1.8f,
        "M7 3L17 3a3 3 0 0 1 3 3L20 18a3 3 0 0 1 -3 3L7 21a3 3 0 0 1 -3 -3" +
            "L4 6a3 3 0 0 1 3 -3Z",
        "M8 8L16 8", "M8 12L16 12", "M8 16L13 16",
    )

    // ===== 列表 / 设置 =====
    val ChevronRight = strokes("ChevronRight", 1.8f, "M9.5 5.5L15.5 12L9.5 18.5")
    val Play = strokes("Play", 1.8f, "M8 5.5L18 12L8 18.5Z")
    val Copy = strokes(
        "Copy", 1.8f,
        "M9 9h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H11a2 2 0 0 1-2-2V9Z",
        "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1",
    )
    val Delete = strokes(
        "Delete", 1.8f,
        "M3 6h18",
        "M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2",
        "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6h14Z",
    )
    val Person = strokes(
        "Person", 1.6f,
        circleData(12f, 8f, 3.4f),
        "M5.5 19C6.7 16 9.2 14.5 12 14.5C14.8 14.5 17.3 16 18.5 19",
    )
    val Code = strokes(
        "Code", 1.6f,
        "M8.5 8L5 11.5L8.5 15", "M15.5 8L19 11.5L15.5 15", "M13.6 5.5L10.4 17.5",
    )
    val Bell = strokes(
        "Bell", 1.6f,
        "M12 5a5.5 5.5 0 0 0 -5.5 5.5C6.5 14.5 5 16 5 16L19 16C19 16 17.5 14.5 17.5 10.5" +
            "A5.5 5.5 0 0 0 12 5Z",
        "M10 19a2 2 0 0 0 4 0",
    )
    val Shield = strokes(
        "Shield", 1.6f,
        "M12 4.5L5.5 7L5.5 12C5.5 16.3 8.3 19 12 20.5C15.7 19 18.5 16.3 18.5 12L18.5 7Z",
    )
    val Info: ImageVector = ImageVector.Builder(
        name = "Info",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = nodes(circleData(12f, 12f, 7.5f)),
            stroke = SolidColor(GlyphInk),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = nodes("M12 11L12 16"),
            stroke = SolidColor(GlyphInk),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(pathData = nodes(circleData(12f, 7.8f, 0.5f)), fill = SolidColor(GlyphInk))
    }.build()

    // ===== 对话风格（长按头像的隐藏面板） =====
    val ToneGentle = strokes(
        "ToneGentle", 1.8f,
        circleData(12f, 12f, 8f),
        "M8.5 12.5L11 15L15.5 9",
    )
    val ToneStrict = strokes(
        "ToneStrict", 1.8f,
        circleData(12f, 12f, 8f),
        "M12 7L12 12L15.5 14",
    )
    val ToneSocratic: ImageVector = ImageVector.Builder(
        name = "ToneSocratic",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            pathData = nodes(circleData(12f, 12f, 8f)),
            stroke = SolidColor(GlyphInk),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(
            pathData = nodes("M9.5 9.5a2.5 2.5 0 1 1 3.4 2.3C12.1 12.1 12 12.8 12 13.5"),
            stroke = SolidColor(GlyphInk),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        addPath(pathData = nodes(circleData(12f, 17f, 0.4f)), fill = SolidColor(GlyphInk))
    }.build()
}
