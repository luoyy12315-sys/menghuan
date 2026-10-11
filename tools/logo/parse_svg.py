"""解析 mh-logo-draw.svg：提取气泡路径 + mh 各笔画，输出中间 JSON 供生成 Kotlin / XML。"""
import json
import re
import xml.etree.ElementTree as ET

SRC = r"C:/Users/16621/Downloads/mh-logo-draw.svg"
OUT = r"C:/Users/16621/Desktop/梦欢/tools/logo/logo.json"

NS = "{http://www.w3.org/2000/svg}"
tree = ET.parse(SRC)
root = tree.getroot()

clip = root.find(f".//{NS}clipPath[@id='bubbleClip']")
bubble_d = clip.find(f"{NS}path").get("d")

# 气泡：填充层 + 描边层（d 与 clipPath 一致）
fill_anim = None
stroke_begin = stroke_dur = None
bubble_stroke_width = None
for g in root.iter(f"{NS}g"):
    if g.get("clip-path"):
        paths = list(g.findall(f"{NS}path"))
        for p in paths:
            if p.get("fill") and p.get("fill") != "none":
                a = p.find(f"{NS}animate")
                fill_anim = (a.get("begin"), a.get("dur"))
            else:
                anims = p.findall(f"{NS}animate")
                for a in anims:
                    if a.get("attributeName") == "stroke-dashoffset":
                        stroke_begin = a.get("begin")
                        stroke_dur = a.get("dur")
                bubble_stroke_width = p.get("stroke-width")


def secs(v):
    return float(v.replace("s", ""))


strokes = []
for g in root.iter(f"{NS}g"):
    if g.get("stroke") != "#ffffff":
        continue
    for p in g.findall(f"{NS}path"):
        d = p.get("d")
        width = float(p.get("stroke-width"))
        length = float(p.get("stroke-dasharray"))
        begin = dur = None
        for a in p.findall(f"{NS}animate"):
            if a.get("attributeName") == "stroke-dashoffset":
                begin = secs(a.get("begin"))
                dur = secs(a.get("dur"))
        strokes.append(dict(d=d, width=width, length=length, begin=begin, dur=dur))


def pts(d):
    return [tuple(map(float, m)) for m in re.findall(r"(-?\d+\.?\d*)\s+(-?\d+\.?\d*)", d)]


all_pts = pts(bubble_d)
for s in strokes:
    all_pts += pts(s["d"])
xs = [p[0] for p in all_pts]
ys = [p[1] for p in all_pts]

# 命令字母检查（确认只有 M/L）
letters = set(re.findall(r"[A-Za-z]", bubble_d)) | set().union(
    *[set(re.findall(r"[A-Za-z]", s["d"])) for s in strokes]
)

info = dict(
    bubble_d_len=len(bubble_d),
    strokes=len(strokes),
    bubble_fill_anim=fill_anim,
    bubble_stroke=(stroke_begin, stroke_dur, bubble_stroke_width),
    bbox=dict(minx=min(xs), maxx=max(xs), miny=min(ys), maxy=max(ys)),
    letters=sorted(letters),
    total_end=max(s["begin"] + s["dur"] for s in strokes),
    mh_d_len=sum(len(s["d"]) for s in strokes),
)
print(json.dumps({k: v for k, v in info.items() if k != "bubble_d"}, ensure_ascii=False, indent=2))

json.dump(
    dict(bubble_d=bubble_d, strokes=strokes, fill_anim=fill_anim,
         bubble_stroke=dict(begin=secs(stroke_begin), dur=secs(stroke_dur),
                            width=float(bubble_stroke_width))),
    open(OUT, "w", encoding="utf-8"),
)
