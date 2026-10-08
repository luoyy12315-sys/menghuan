package com.menghuan.app.ui.theme

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

    // ===== 语音通话（v0.46.0，feather 图标线条化移植；Mic 沿用输入栏已有图标）=====
    /** 声音开（喇叭+声波）：聊天音效开关 */
    val VolumeOn = strokes(
        "VolumeOn", 1.7f,
        "M11 5L6 9L2 9L2 15L6 15L11 19L11 5Z",
        "M15.54 8.46a5 5 0 0 1 0 7.07",
        "M19.07 4.93a10 10 0 0 1 0 14.14",
    )
    /** 声音关（喇叭+叉）：聊天音效关闭态 */
    val VolumeOff = strokes(
        "VolumeOff", 1.7f,
        "M11 5L6 9L2 9L2 15L6 15L11 19L11 5Z",
        "M23 9L17 15",
        "M17 9L23 15",
    )
    /** 三个点（竖排实心）：通话设置入口 */
    val MoreVert = filled(
        "MoreVert",
        circleData(12f, 5f, 1.35f) + " ",
        circleData(12f, 12f, 1.35f) + " ",
        circleData(12f, 19f, 1.35f),
    )
    val Phone = strokes(
        "Phone", 1.7f,
        "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 " +
            "19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 " +
            ".7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 " +
            "12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z",
    )
    val PhoneOff = strokes(
        "PhoneOff", 1.7f,
        "M10.68 13.31a16 16 0 0 0 3.41 2.6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 " +
            "2.81.7A2 2 0 0 1 22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.42 " +
            "19.42 0 0 1-3.33-2.67m-2.67-3.34a19.79 19.79 0 0 1-3.07-8.63A2 2 0 0 1 4.11 2h3a2 " +
            "2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6",
        "M23 1L1 23",
    )
    /** 摄像机（feather "video" 线条化，v0.60）：视频通话的记录卡与入口用 */
    val Video = strokes(
        "Video", 1.7f,
        "M3 6h11a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z",
        "M16 10.5L22 7L22 17L16 13.5Z",
    )
    val MicOff = strokes(
        "MicOff", 1.7f,
        "M1 1l22 22",
        "M9 9v3a3 3 0 0 0 5.12 2.12",
        "M15 9.34V4a3 3 0 0 0-5.94-.6",
        "M17 16.95A7 7 0 0 1 5 12v-2m14 0v2a7 7 0 0 1-.11 1.23",
        "M12 19v4",
        "M8 23h8",
    )

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
    /** v0.54.3：多选勾选圈的 ✓ */
    val Check = strokes("Check", 2f, "M4.5 12.5L9.5 17.5L19.5 7")
    /** v0.54.3：长按菜单「多选」（方框内一个勾） */
    val SelectAll = strokes(
        "SelectAll", 1.8f,
        "M4 4h16v16H4Z",
        "M8.4 12.2L11 14.8L15.8 9.2",
    )
    /** v0.54.3：多选操作栏「转发」（右向箭头 + 引出线） */
    val Forward = strokes(
        "Forward", 1.8f,
        "M13 4.5L20.5 12L13 19.5",
        "M20.5 12H10.5C6.9 12 4 14.9 4 18.5",
    )
    val Person = strokes(
        "Person", 1.6f,
        circleData(12f, 8f, 3.4f),
        "M5.5 19C6.7 16 9.2 14.5 12 14.5C14.8 14.5 17.3 16 18.5 19",
    )
    val EyeOff = strokes(
        "EyeOff", 1.6f,
        // 眼睛轮廓（上半弧 + 下半弧）
        "M2.5 12C4 8.8 7.7 5.8 12 5.8C16.3 5.8 20 8.8 21.5 12C20 15.2 16.3 18.2 12 18.2C7.7 18.2 4 15.2 2.5 12Z",
        circleData(12f, 12f, 3f),
        // 斜杠：隐藏
        "M4.5 4.5L19.5 19.5",
    )
    val FileText = strokes(
        "FileText", 1.6f,
        "M7 3.5L14.5 3.5a2.5 2.5 0 0 1 2.5 2.5L17 18a2.5 2.5 0 0 1 -2.5 2.5L7 20.5A2.5 2.5 0 0 1 4.5 18L4.5 6A2.5 2.5 0 0 1 7 3.5Z",
        "M8.5 9L14 9",
        "M8.5 12.5L14 12.5",
        "M8.5 16L11.5 16",
    )
    val Code = strokes(
        "Code", 1.6f,
        "M8.5 8L5 11.5L8.5 15", "M15.5 8L19 11.5L15.5 15", "M13.6 5.5L10.4 17.5",
    )
    /** 备忘的「标记时间」（v0.62.0） */
    val Clock = strokes(
        "Clock", 1.6f,
        circleData(12f, 12f, 7.8f),
        "M12 7.9L12 12.3L15.2 14",
    )
    /** 备忘的「标记地点」（v0.62.0，自由文字，不做定位） */
    val MapPin = strokes(
        "MapPin", 1.6f,
        "M12 20.6C12 20.6 18 15.1 18 10.6A6 6 0 0 0 6 10.6C6 15.1 12 20.6 12 20.6Z",
        circleData(12f, 10.4f, 2.1f),
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

    // ===== v0.35.0 新增 =====
    /** 展开/收起箭头（洞察板块折叠 + 周报卡片折叠） */
    val ChevronDown = strokes("ChevronDown", 1.8f, "M5.5 9.5L12 16L18.5 9.5")
    /** 收起箭头 */
    val ChevronUp = strokes("ChevronUp", 1.8f, "M5.5 14.5L12 8L18.5 14.5")
    /** 调色板（人设头像自定义入口） */
    val Palette = strokes(
        "Palette", 1.8f,
        "M12 3.8a8.2 8.2 0 1 0 0 16.4c1.1 0 1.8-.8 1.8-1.7 0-.5-.2-.9-.5-1.2-.3-.3-.5-.7-.5-1.2 0-1 .8-1.7 1.8-1.7h1.3a4.3 4.3 0 0 0 4.3-4.3c0-3.6-3.6-6.3-8.2-6.3z",
        circleData(8.4f, 10.4f, 0.9f),
        circleData(11.2f, 7.6f, 0.9f),
        circleData(14.6f, 8.2f, 0.9f),
    )
    /** 图片（生图结果占位） */
    val ImageIcon = strokes(
        "ImageIcon", 1.8f,
        "M4 5.8h16v12.4H4z",
        circleData(9.2f, 10.2f, 1.4f),
        "M4.4 16.6l4.4-4.2 3.2 3 2.6-2.2 5 4",
    )

    /** 思考链（v0.41.4）：左半脑 + 右半脑 + 中间沟，示意"模型的推理过程" */
    val Brain = strokes(
        "Brain", 1.7f,
        // 左半脑
        "M12 5.6A3.1 3.1 0 0 0 6.4 7.1a2.9 2.9 0 0 0-1.2 5.3A3 3 0 0 0 7 17.9a3.1 3.1 0 0 0 5 2.6",
        // 右半脑
        "M12 5.6a3.1 3.1 0 0 1 5.6 1.5 2.9 2.9 0 0 1 1.2 5.3A3 3 0 0 1 17 17.9a3.1 3.1 0 0 1-5 2.6",
        // 中缝
        "M12 5.6L12 20.5",
    )

    // ===== 对话风格图标（v0.32.0 已整体取消调用，保留仅供导入默认值兼容） =====
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

    // ===== v0.42.0 记账本 / 每日建议 =====

    /** 钱包：记账本入口 */
    val Wallet = strokes(
        "Wallet", 1.8f,
        "M3.5 8.5a2.5 2.5 0 0 1 2.5 -2.5L18 6a2.5 2.5 0 0 1 2.5 2.5L20.5 16a2.5 2.5 0 0 1 -2.5 2.5" +
            "L6 18.5a2.5 2.5 0 0 1 -2.5 -2.5Z",
        "M3.5 10L20.5 10",
        "M20.5 10L20.5 13.5L17 13.5a1.75 1.75 0 1 1 0 -3.5Z",
    )

    /** 麦克风：语音录入 */
    val Mic = strokes(
        "Mic", 1.8f,
        "M12 4a2.6 2.6 0 0 1 2.6 2.6L14.6 11.4a2.6 2.6 0 0 1 -5.2 0L9.4 6.6A2.6 2.6 0 0 1 12 4Z",
        "M6.5 11.5a5.5 5.5 0 0 0 11 0",
        "M12 17L12 20",
        "M9.5 20L14.5 20",
    )

    /** 扫描取景框：识图录入 */
    val ScanFrame = strokes(
        "ScanFrame", 1.8f,
        "M4 8.5L4 6a2 2 0 0 1 2 -2L8.5 4",
        "M15.5 4L18 4a2 2 0 0 1 2 2L20 8.5",
        "M20 15.5L20 18a2 2 0 0 1 -2 2L15.5 20",
        "M8.5 20L6 20a2 2 0 0 1 -2 -2L4 15.5",
        circleData(12f, 12f, 3.2f),
    )

    /** 灯泡：每日建议 */
    val Bulb = strokes(
        "Bulb", 1.7f,
        "M12 3.5a5.5 5.5 0 0 1 3.3 9.9c-.5.4-.8 1-.8 1.6L14.5 16.5L9.5 16.5L9.5 15c0-.6-.3-1.2-.8-1.6A5.5 5.5 0 0 1 12 3.5Z",
        "M9.8 19L14.2 19",
        "M10.6 21.5L13.4 21.5",
    )

    /** 上楼箭头（收入） */
    val TrendUp = strokes(
        "TrendUp", 1.8f,
        "M4 17L10 11L13.5 14.5L20 8",
        "M15 8L20 8L20 13",
    )

    /** 下楼箭头（支出） */
    val TrendDown = strokes(
        "TrendDown", 1.8f,
        "M4 7L10 13L13.5 9.5L20 16",
        "M15 16L20 16L20 11",
    )

    /** 钱包里的纸币（现金余额）：方框 + 中心点 */
    val Banknote = strokes(
        "Banknote", 1.8f,
        "M3.5 7.5a1.5 1.5 0 0 1 1.5 -1.5L19 6a1.5 1.5 0 0 1 1.5 1.5L20.5 16.5a1.5 1.5 0 0 1 -1.5 1.5" +
            "L5 18a1.5 1.5 0 0 1 -1.5 -1.5Z",
        circleData(12f, 12f, 2.6f),
    )

    // ===== 输入栏与媒体（v0.43.0）=====

    /** 表情：笑脸（外圈 + 两眼 + 微笑弧） */
    val Smile = strokes(
        "Smile", 1.7f,
        circleData(12f, 12f, 8.6f),
        "M9 10.2L9 10.4",
        "M15 10.2L15 10.4",
        "M8.4 14.2a4.4 4.4 0 0 0 7.2 0",
    )

    /**
     * 语音输入：**无外圈**，五道高低起伏的声波竖线。
     * v0.43.1：用户明确要求"不要外面那个圈，直接用里面符号代替"，
     * 因此删掉原外圆，只留声波本体（外圈/加号两处都靠 [NeuFlatIconButton] 不带底盘）。
     */
    val VoiceWave = strokes(
        "VoiceWave", 1.7f,
        "M5.6 10.6L5.6 13.4",
        "M8.2 8.2L8.2 15.8",
        "M10.8 5.6L10.8 18.4",
        "M13.4 8.2L13.4 15.8",
        "M16 10.6L16 13.4",
        "M18.6 11.4L18.6 12.6",
    )

    /**
     * 键盘（语音态下语音键翻成它，再点一下回打字态）。
     *
     * v0.45.0 按用户反馈"更美观简约"再简化：原版是外框 + 3 列 × 2 行六个键帽点，
     * 元素偏多、21dp 下显碎。现在只留**外框 + 一排三个键帽点 + 底部空格横线**，
     * 线宽 1.7 → 1.6，小尺寸下依然一眼是"键盘"，但干净得多。
     */
    val Keyboard = strokes(
        "Keyboard", 1.6f,
        "M3.4 7.2L20.6 7.2L20.6 16.8L3.4 16.8Z",
        circleData(8.4f, 10.6f, 0.8f),
        circleData(12f, 10.6f, 0.8f),
        circleData(15.6f, 10.6f, 0.8f),
        "M9.9 14.4L14.1 14.4",
    )

    /** 翻转镜头（前后置切换）：圆 + 上方回转箭头 */
    val FlipCamera = strokes(
        "FlipCamera", 1.7f,
        "M4.2 9.6a8 8 0 0 1 13.2 -3.5",
        "M17.4 8.1L13.4 8.6L17.6 11.4Z",
        "M19.8 14.4a8 8 0 0 1 -13.2 3.5",
        "M6.6 15.9L10.6 15.4L6.4 12.6Z",
        circleData(12f, 12f, 3.2f),
    )

    /** 文件：折角纸张 */
    val FileDoc = strokes(
        "FileDoc", 1.7f,
        "M6.5 3.5L13 3.5L18 8.5L18 20.5L6.5 20.5Z",
        "M13 3.5L13 8.5L18 8.5",
        "M9 12.5L15 12.5",
        "M9 16L13 16",
    )

    /** 语音条播放中：暂停双竖条 */
    val Pause = filled(
        "Pause",
        "M8.6 7.4L11 7.4L11 16.6L8.6 16.6Z",
        "M13 7.4L15.4 7.4L15.4 16.6L13 16.6Z",
    )

    /** 关闭（全屏查看 / 相机退出） */
    val Close = strokes(
        "Close", 1.8f,
        "M6.4 6.4L17.6 17.6",
        "M17.6 6.4L6.4 17.6",
    )

    /** 快门（相机底栏中心键）：外圈 + 内实心圆 */
    val Shutter = strokes(
        "Shutter", 1.7f,
        circleData(12f, 12f, 8.8f),
    )
}
