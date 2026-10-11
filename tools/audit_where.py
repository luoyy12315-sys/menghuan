# -*- coding: utf-8 -*-
"""对 audit_deadcode.py 报出的候选逐个列出「所有出现位置」，人工判定真假。"""
import io, os, re, sys

ROOT = "app"
files = {}
for r, d, fs in os.walk(os.path.join(ROOT, "src")):
    for f in fs:
        if f.endswith(".kt"):
            p = os.path.join(r, f).replace("\\", "/")
            files[p] = io.open(p, encoding="utf-8", errors="ignore").read()
# XML 也要看（Manifest / 布局里引用类名）
xml = {}
for r, d, fs in os.walk(os.path.join(ROOT, "src")):
    for f in fs:
        if f.endswith(".xml"):
            p = os.path.join(r, f).replace("\\", "/")
            xml[p] = io.open(p, encoding="utf-8", errors="ignore").read()

names = sys.argv[1:]
for n in names:
    pat = re.compile(r"(?<![A-Za-z0-9_])" + re.escape(n) + r"(?![A-Za-z0-9_])")
    print("\n================ %s ================" % n)
    hit = 0
    for p, t in list(files.items()) + list(xml.items()):
        for i, l in enumerate(t.split("\n"), 1):
            if pat.search(l):
                hit += 1
                print("  %-72s :%d  %s" % (p.replace("app/src/", ""), i, l.strip()[:120]))
    if hit == 0:
        print("  （零出现）")
