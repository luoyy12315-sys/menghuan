"""由 logo.json 生成 Kotlin 数据文件 + 启动图标 foreground XML。"""
import json
import re

SRC = r"C:/Users/16621/Desktop/梦欢/tools/logo/logo.json"
DATA = json.load(open(SRC, encoding="utf-8"))


def rnd(d: str) -> str:
    """坐标降精度到 1 位小数（1440 画布上 0.1 的误差肉眼不可见）"""

    def f(m):
        v = float(m.group(0))
        s = f"{v:.1f}"
        return s.rstrip("0").rstrip(".") if "." in s else s

    return re.sub(r"-?\d+\.\d+", f, d)


bubble = rnd(DATA["bubble_d"])
strokes = DATA["strokes"]

# ---------- Kotlin ----------
lines = []
lines.append("package com.menghuan.app.ui.common")
lines.append("")
lines.append("/**")
lines.append(" * 梦欢 logo 的矢量笔迹（由 `mh-logo-draw.svg` 提取，坐标降精度到 1 位小数）。")
lines.append(" *")
lines.append(" * 结构 = **一条气泡轮廓**（填充 + 描边共用）+ **36 段白笔画**拼出手写「mh」。")
lines.append(" * 时间轴沿用原 SVG（单位秒），绘制时统一乘 `TIME_SCALE` 压缩——改速度只动那一个常量。")
lines.append(" *")
lines.append(" * ⚠️ 这是**生成文件**：改 logo 请用脚本从 SVG 重新导出，别手改下面的坐标串。")
lines.append(" */")
lines.append("internal object MhLogoPaths {")
lines.append("")
lines.append("    /** 原 SVG 画布边长：下面所有坐标都在 1440×1440 这个框里，内容已居中 */")
lines.append("    const val VIEWPORT = 1440f")
lines.append("")
lines.append("    /** 原动画总时长（秒）：最后一段笔画画完的时刻 */")
lines.append("    const val TIMELINE_SEC = %.2ff" % max(s["begin"] + s["dur"] for s in strokes))
lines.append("")
lines.append("    /** 气泡轮廓：填充层与描边层共用同一条路径 */")
lines.append("    val bubbleOutline: String =")
lines.append('        "%s"' % bubble)
lines.append("")
lines.append("    /** 气泡描边宽度（原 SVG 值） */")
lines.append("    const val bubbleStrokeWidth = %.1ff" % DATA["bubble_stroke"]["width"])
lines.append("")
lines.append("    /** 气泡描边：起笔时刻 / 画完用时（秒） */")
lines.append("    const val bubbleStrokeBeginSec = %.3ff" % DATA["bubble_stroke"]["begin"])
lines.append("    const val bubbleStrokeDurSec = %.3ff" % DATA["bubble_stroke"]["dur"])
lines.append("")
lines.append("    /** 气泡填充淡入：开始 / 持续（秒）。填充比描边晚起，描边快画完时才压上来 */")
lines.append("    const val bubbleFillBeginSec = %.3ff" % float(DATA["fill_anim"][0].rstrip("s")))
lines.append("    const val bubbleFillDurSec = %.3ff" % float(DATA["fill_anim"][1].rstrip("s")))
lines.append("")
lines.append("    /** 一段连续笔画：路径 + 线宽 + 路径长 + 起笔时刻 + 画完用时 */")
lines.append("    data class Stroke(")
lines.append("        val d: String,")
lines.append("        val width: Float,")
lines.append("        val length: Float,")
lines.append("        val beginSec: Float,")
lines.append("        val durSec: Float,")
lines.append("    )")
lines.append("")
lines.append("    /** 「mh」的 36 段笔画，顺序 = 书写顺序（先 m 后 h，中间抬笔自然断开） */")
lines.append("    val strokes: List<Stroke> = listOf(")
for s in strokes:
    lines.append(
        '        Stroke("%s", %.2ff, %.2ff, %.3ff, %.3ff),'
        % (rnd(s["d"]), s["width"], s["length"], s["begin"], s["dur"])
    )
lines.append("    )")
lines.append("}")
lines.append("")

kt = "\n".join(lines)
out_kt = r"C:/Users/16621/Desktop/梦欢/app/src/main/java/com/menghuan/app/ui/common/MhLogoPaths.kt"
open(out_kt, "w", encoding="utf-8").write(kt)
print("Kotlin:", len(kt), "chars,", len(kt.encode("utf-8")), "bytes ->", out_kt)

# ---------- 启动图标 foreground XML ----------
# 108dp 画布：内容收进 66dp 安全圈内（气泡本身是圆角方形，四角本来就空）
xs = []
ys = []
for d in [DATA["bubble_d"]] + [s["d"] for s in strokes]:
    for m in re.finditer(r"(-?\d+\.?\d*)\s+(-?\d+\.?\d*)", d):
        xs.append(float(m.group(1)))
        ys.append(float(m.group(2)))
minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
print("bbox", minx, maxx, miny, maxy)

# 自适应图标的安全区是 66dp 直径的圆：气泡是圆角方形（圆角约边长的 17%），
# 按"最远墨点也落进安全圆"反推边长 = 2*33/0.624 ≈ 53dp。
# 顺带对齐老图标的视觉重量（老前景 22 单位 × 2.2 = 48.4dp），53dp 略大一点点。
TARGET = 53.0
scale = TARGET / max(maxx - minx, maxy - miny)
tx = 54.0 - (minx + maxx) / 2.0 * scale
ty = 54.0 - (miny + maxy) / 2.0 * scale

x = []
x.append('<?xml version="1.0" encoding="utf-8"?>')
x.append("<!-- 启动图标前景：黑色气泡 + 白色手写「mh」（梦欢新 logo，逐笔画动画的终帧）。")
x.append("     底色见 ic_launcher_background（近白），气泡描黑、字反白 -->")
x.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"')
x.append('    android:width="108dp"')
x.append('    android:height="108dp"')
x.append('    android:viewportWidth="108"')
x.append('    android:viewportHeight="108">')
x.append("    <group")
x.append('        android:scaleX="%.6f"' % scale)
x.append('        android:scaleY="%.6f"' % scale)
x.append('        android:translateX="%.6f"' % tx)
x.append('        android:translateY="%.6f">' % ty)
x.append("        <!-- 气泡：填充 + 描边同一条路径，描边补上圆角外的那一圈厚度 -->")
x.append("        <path")
x.append('            android:fillColor="#0C0C0C"')
x.append('            android:strokeColor="#0C0C0C"')
x.append('            android:strokeWidth="%.1f"' % DATA["bubble_stroke"]["width"])
x.append('            android:strokeLineCap="round"')
x.append('            android:strokeLineJoin="round"')
x.append('            android:pathData="%s" />' % bubble)
for s in strokes:
    x.append("        <path")
    x.append('            android:strokeColor="#FFFFFF"')
    x.append('            android:strokeWidth="%.2f"' % s["width"])
    x.append('            android:strokeLineCap="round"')
    x.append('            android:strokeLineJoin="round"')
    x.append('            android:pathData="%s" />' % rnd(s["d"]))
x.append("    </group>")
x.append("</vector>")

out_xml = r"C:/Users/16621/Desktop/梦欢/app/src/main/res/drawable/ic_launcher_foreground.xml"
open(out_xml, "w", encoding="utf-8").write("\n".join(x) + "\n")
print("XML:", len("\n".join(x)), "chars ->", out_xml)

# ---------- monochrome（主题图标用：只取 alpha，气泡描边 + 字同色） ----------
m = []
m.append('<?xml version="1.0" encoding="utf-8"?>')
m.append("<!-- 主题图标（Android 13+ 单色）：系统只取 alpha 并统一上色，")
m.append("     所以这里**不能**用填充气泡（会糊成一整块），只画气泡轮廓 + 「mh」笔画 -->")
m.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"')
m.append('    android:width="108dp"')
m.append('    android:height="108dp"')
m.append('    android:viewportWidth="108"')
m.append('    android:viewportHeight="108">')
m.append("    <group")
m.append('        android:scaleX="%.6f"' % scale)
m.append('        android:scaleY="%.6f"' % scale)
m.append('        android:translateX="%.6f"' % tx)
m.append('        android:translateY="%.6f">' % ty)
m.append("        <path")
m.append('            android:strokeColor="#FFFFFF"')
m.append('            android:strokeWidth="%.1f"' % (DATA["bubble_stroke"]["width"] * 1.35))
m.append('            android:strokeLineCap="round"')
m.append('            android:strokeLineJoin="round"')
m.append('            android:pathData="%s" />' % bubble)
for s in strokes:
    m.append("        <path")
    m.append('            android:strokeColor="#FFFFFF"')
    m.append('            android:strokeWidth="%.2f"' % s["width"])
    m.append('            android:strokeLineCap="round"')
    m.append('            android:strokeLineJoin="round"')
    m.append('            android:pathData="%s" />' % rnd(s["d"]))
m.append("    </group>")
m.append("</vector>")
out_mono = r"C:/Users/16621/Desktop/梦欢/app/src/main/res/drawable/ic_launcher_monochrome.xml"
open(out_mono, "w", encoding="utf-8").write("\n".join(m) + "\n")
print("mono XML:", len("\n".join(m)), "chars ->", out_mono)
