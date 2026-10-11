# -*- coding: utf-8 -*-
"""第二类：函数参数声明了但函数体里一次都没用（`applyDelta(connection)` 那一类）。"""
import io, os, re
from collections import Counter

files = {}
for r, d, fs in os.walk("app/src/main/java"):
    for f in fs:
        if f.endswith(".kt"):
            p = os.path.join(r, f).replace("\\", "/")
            files[p] = io.open(p, encoding="utf-8", errors="ignore").read()

FUN = re.compile(r"\bfun\s+(?:<[^>]+>\s*)?(\w+)\s*\(")

print("===== 参数只在签名里出现一次（疑似从未使用）=====")
n_hit = 0
for p, t in files.items():
    freq = Counter(re.findall(r"[A-Za-z_]\w*", t))
    for m in FUN.finditer(t):
        start = m.end() - 1
        depth, i = 0, start
        while i < len(t):
            if t[i] == "(": depth += 1
            elif t[i] == ")":
                depth -= 1
                if depth == 0: break
            i += 1
        raw = t[start + 1:i]
        # 拆顶层逗号
        parts, d2, cur = [], 0, ""
        for ch in raw:
            if ch in "(<[{": d2 += 1
            if ch in ")>]}": d2 -= 1
            if ch == "," and d2 == 0:
                parts.append(cur); cur = ""
            else:
                cur += ch
        if cur.strip(): parts.append(cur)
        line = t[:start].count("\n") + 1
        for pr in parts:
            nm = re.match(r"\s*(?:vararg\s+|noinline\s+|crossinline\s+)*([A-Za-z_]\w*)\s*:", pr)
            if not nm: continue
            pn = nm.group(1)
            if pn in ("it",): continue
            if freq[pn] <= 1:
                n_hit += 1
                print("  %-58s :%-5d %s(%s)  <- %s 未使用" % (p.replace("app/src/main/java/com/menghuan/app/", ""), line, m.group(1), pn, pn))
print("  合计 %d 处" % n_hit)

print()
print("===== @Suppress(\"unused\") =====")
for p, t in files.items():
    for i, l in enumerate(t.split("\n"), 1):
        if 'Suppress("unused")' in l or "Suppress('unused')" in l:
            print("  %-58s :%d %s" % (p.replace("app/src/main/java/com/menghuan/app/", ""), i, l.strip()))

print()
print("===== 可疑注释（承诺/暂缓/不生效）=====")
KW = ["暂时", "预留", "以后再说", "后续再做", "未实现", "不生效", "没生效", "无人调用", "零引用",
      "死代码", "写死了先", "TODO", "FIXME", "HACK", "先这样", "形同虚设", "从来没", "永远不会"]
for p, t in files.items():
    for i, l in enumerate(t.split("\n"), 1):
        s = l.strip()
        if not (s.startswith("//") or s.startswith("*") or s.startswith("/*")):
            continue
        if any(k in l for k in KW):
            print("  %-58s :%d %s" % (p.replace("app/src/main/java/com/menghuan/app/", ""), i, s[:130]))
