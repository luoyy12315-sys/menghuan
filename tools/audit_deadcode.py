# -*- coding: utf-8 -*-
"""扫描「注释/声明承诺得比实现多」这一类问题（单遍词频，避免 O(n^2)）。

A. 顶层/对象级声明但全工程零引用（死代码）
B. Preferences 键声明了但没有任何一处读它
"""
import io, os, re
from collections import Counter

ROOT = "app/src"
files = {}
for base in ("main", "test"):
    for r, d, fs in os.walk(os.path.join(ROOT, base, "java")):
        for f in fs:
            if f.endswith(".kt"):
                p = os.path.join(r, f).replace("\\", "/")
                files[p] = io.open(p, encoding="utf-8", errors="ignore").read()

IDENT = re.compile(r"[A-Za-z_]\w*")
freq = Counter()
for t in files.values():
    freq.update(IDENT.findall(t))

DECL_FUN = re.compile(r"^(\s*)((?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |protected |open |override |suspend |inline |operator |abstract |tailrec |external |final )*)fun\s+(?:<[^>]+>\s*)?(\w+)\s*\(")
DECL_VAL = re.compile(r"^(\s*)((?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |protected |open |override |const |lateinit |abstract )*)(?:val|var)\s+(\w+)\s*[:=]")

sites = {}   # name -> [(file, line, kind)]
for p, t in files.items():
    if "/test/" in p:
        continue
    for i, l in enumerate(t.split("\n"), 1):
        m = DECL_FUN.match(l)
        if m and len(m.group(1)) <= 8:
            sites.setdefault(m.group(3), []).append((p, i, "fun"))
            continue
        m = DECL_VAL.match(l)
        if m and len(m.group(1)) <= 8:
            sites.setdefault(m.group(3), []).append((p, i, "val"))

dead = []
for name, ss in sites.items():
    if freq[name] <= len(ss):
        dead.append((name, freq[name], ss[0]))

dead.sort(key=lambda x: (x[2][0], x[2][1]))
print("========== A. 疑似零引用声明：%d 条 ==========" % len(dead))
cur = None
for name, n, (p, i, k) in dead:
    if p != cur:
        print("\n[%s]" % p)
        cur = p
    print("   %-48s %-5s :%d" % (name, k, i))

print()
print("========== B. Preferences 键：只声明没读 ==========")
KEY_DECL = re.compile(r"\b(?:string|boolean|int|long|float|double|stringSet)PreferencesKey\s*\(\s*\"([^\"]+)\"\s*\)")
for p, t in files.items():
    if "PreferencesKey" not in t or "/test/" in p:
        continue
    for i, l in enumerate(t.split("\n"), 1):
        m = KEY_DECL.search(l)
        if not m:
            continue
        vm = re.match(r"^\s*(?:private\s+)?val\s+(\w+)", l)
        vn = vm.group(1) if vm else "?"
        cnt = freq[vn]
        if cnt <= 1:
            print("   只声明没读  %-26s %s:%d" % (vn, p, i))
print("   （无输出 = 所有键都至少被读过一次）")
