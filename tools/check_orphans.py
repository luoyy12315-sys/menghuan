# -*- coding: utf-8 -*-
"""清理后自检：找出「孤儿注解」与「孤儿 KDoc」——声明被删了、注解/注释还挂着。"""
import io, os, re, subprocess

FILES = subprocess.run(["git", "diff", "--name-only"], capture_output=True, text=True,
                       encoding="utf-8", errors="ignore").stdout.split("\n")
FILES = [f.replace("/", os.sep) for f in FILES if f.strip().endswith(".kt")]

DECL = re.compile(r"^(?:@|(?:public |internal |private |protected |open |override |suspend |inline |operator |abstract |final |const |lateinit |data |sealed |enum )*(?:fun|val|var|class|object|interface|typealias|constructor)\b)")
bad = 0

for p in FILES:
    if not os.path.exists(p):
        continue
    ls = io.open(p, encoding="utf-8").read().split("\n")
    for i, l in enumerate(ls):
        s = l.strip()
        # ① 孤儿注解
        if re.match(r"^@\w+", s):
            j = i + 1
            while j < len(ls) and ls[j].strip() == "":
                j += 1
            if j < len(ls) and not DECL.match(ls[j].strip()):
                # 注解自带多行参数的情况（下一行是字符串/`)`/`,`）
                nxt = ls[i + 1].lstrip() if i + 1 < len(ls) else ""
                if nxt.startswith(('"', "'", ")")) or nxt.startswith(","):
                    continue
                print("%s:%d  孤儿注解  | %s" % (p, i + 1, s[:70]))
                print("                下一行 | %s" % ls[j].strip()[:80])
                bad += 1
        # ② 孤儿 KDoc
        if s.startswith("/**"):
            k = i
            while k < len(ls) and "*/" not in ls[k]:
                k += 1
            if k >= len(ls):
                print("%s:%d  KDoc 未闭合" % (p, i + 1)); bad += 1; continue
            j = k + 1
            while j < len(ls) and ls[j].strip() == "":
                j += 1
            if j < len(ls) and not DECL.match(ls[j].strip()):
                print("%s:%d  KDoc 无归属  | 结尾 %s" % (p, k + 1, ls[k].strip()[:50]))
                print("                下一行 | %s" % ls[j].strip()[:80])
                bad += 1

print("\n可疑点合计 %d" % bad)
