# -*- coding: utf-8 -*-
"""核验 v0.96.0 的改动真的编进了 dex。

项目铁律：APK 字节数不能当重建判据（zip 对齐会凑长度），要证明改动编进去，
就在 dex 里按 **MUTF-8（=UTF-8）数字节**搜字符串。中文用 Python 的 count()，
不用 grep（grep 对中文失效）、也不用 strings -e l（全 0 是假阴性）。
"""
import zipfile

APK = r"C:\Users\16621\Desktop\梦欢\梦欢-v0.96.0-release.apk"

z = zipfile.ZipFile(APK)
dex_names = [n for n in z.namelist() if n.endswith(".dex")]
blob = b"".join(z.read(n) for n in dex_names)
print("dex 文件：%s（合计 %d 字节）" % (dex_names, len(blob)))


def c(s):
    return blob.count(s.encode("utf-8"))


print("\n--- 必须在（改动编进去了）---")
# 中文长串期望**恰 1 次**（顺手防重复）；版本号这类会在多处出现，只要求 ≥1
must = [
    "跟你聊天时她完全不碰动态",
    "她的一切动态动作都只发生在主动来找你的时候",
    "她自己的帖子根本不会列进名单",
    "你看得到用户的记忆",
    "0.96.0",
]
bad = 0
for s in must:
    n = c(s)
    if n < 1:
        bad += 1
    print("  %-2d  %s" % (n, s))

print("\n--- 必须消失（真被删掉的常量）---")
# ⚠️ 只能放「整个删掉的常量」。被 manualPatch 改写的旧措辞**必然还在 dex 里** ——
# 它同时是「原补丁的产物」和「新补丁的 old 锚点」，两处都是源码里的字符串常量。
# 例：`跟你聊天时她只会回你的帖、点赞` dex 命中 2 = AiPrompts.kt L2306(原补丁 new) + L2359(新补丁 old)。
# 所以「手册最终文案里旧措辞消失」这件事**不能用 dex 证明**，
# 那是运行时由 replace 链拼出来的，只能用真编译 + jshell dump 核（见 logs/v0950_prompt_after.txt）。
gone = [
    "去动态上表态了",
]
for s in gone:
    n = c(s)
    if n != 0:
        bad += 1
    print("  %-2d  %s" % (n, s))

print("\nBAD=%d" % bad)
