# -*- coding: utf-8 -*-
"""③ 死代码清理 —— 执行删除。

排除（等用户决定的）：
  - `refreshInsights` + `_extracting`/`extracting`/`_extractMessage`/`extractMessage`
    → 它们牵出「数据页提炼按钮消失」这个真问题，删不删取决于用户选哪条路。
"""
import io, re

HOLD = {"refreshInsights"}

TARGETS = [
    ("app/src/main/java/com/menghuan/app/LedgerRoute.kt", "formatYuan"),
    ("app/src/main/java/com/menghuan/app/agent/AgentAccessibilityService.kt", "openAccessibilitySettings"),
    ("app/src/main/java/com/menghuan/app/data/CallBackgroundStore.kt", "isOwn"),
    ("app/src/main/java/com/menghuan/app/data/CallVisualAsset.kt", "stateMapFromManifestJson"),
    ("app/src/main/java/com/menghuan/app/data/CallVisualStore.kt", "hasAnyVideo"),
    ("app/src/main/java/com/menghuan/app/data/CallVisualStore.kt", "discardStaging"),
    ("app/src/main/java/com/menghuan/app/data/ChatAppearance.kt", "hasAny"),
    ("app/src/main/java/com/menghuan/app/data/Live2dActionRepository.kt", "observeInteractionBindings"),
    ("app/src/main/java/com/menghuan/app/data/Live2dSpaceStore.kt", "deleteBackground"),
    ("app/src/main/java/com/menghuan/app/data/Live2dSpaceStore.kt", "videoDurationMs"),
    ("app/src/main/java/com/menghuan/app/data/Live2dSpaceStore.kt", "deleteBackgroundVideo"),
    ("app/src/main/java/com/menghuan/app/data/MemoryScope.kt", "ownedBy"),
    ("app/src/main/java/com/menghuan/app/data/SettingsRepository.kt", "aiCallTime"),
    ("app/src/main/java/com/menghuan/app/data/SettingsRepository.kt", "aiCallIdleDays"),
    ("app/src/main/java/com/menghuan/app/data/SettingsRepository.kt", "setAiCallPersonaId"),
    ("app/src/main/java/com/menghuan/app/data/SettingsRepository.kt", "aiCallCount"),
    ("app/src/main/java/com/menghuan/app/data/SettingsRepository.kt", "bumpAiCallCount"),
    ("app/src/main/java/com/menghuan/app/data/SherpaAsr.kt", "readoutWav"),
    ("app/src/main/java/com/menghuan/app/data/TaskSuggestionMarker.kt", "MARK_OPEN_FULL"),
    ("app/src/main/java/com/menghuan/app/data/VoiceNoteMarker.kt", "MARK"),
    ("app/src/main/java/com/menghuan/app/data/db/AppDatabase.kt", "getLatest"),
    ("app/src/main/java/com/menghuan/app/data/db/AppDatabase.kt", "getBetween"),
    ("app/src/main/java/com/menghuan/app/data/db/AppDatabase.kt", "deleteSession"),
    ("app/src/main/java/com/menghuan/app/data/db/AppDatabase.kt", "deleteTurns"),
    ("app/src/main/java/com/menghuan/app/data/model/Live2dActions.kt", "ActionSlotDisplayOrder"),
    ("app/src/main/java/com/menghuan/app/live2d/BuiltinActions.kt", "randomPool"),
    ("app/src/main/java/com/menghuan/app/live2d/Live2dAction.kt", "editableInPlace"),
    ("app/src/main/java/com/menghuan/app/live2d/ModelCapability.kt", "resolvedIds"),
    ("app/src/main/java/com/menghuan/app/notify/AlarmScheduler.kt", "cancelAiCallOnce"),
    ("app/src/main/java/com/menghuan/app/notify/AlarmScheduler.kt", "cancelInsights"),
    ("app/src/main/java/com/menghuan/app/ui/chat/ChatComponents.kt", "imageMessagePreview"),
    ("app/src/main/java/com/menghuan/app/ui/chat/ChatInputPanels.kt", "EmojiAvatarGrid"),
    ("app/src/main/java/com/menghuan/app/ui/chat/ChatListScreen.kt", "savePersona"),
    ("app/src/main/java/com/menghuan/app/ui/chat/ChatViewModel.kt", "insightLogicText"),
    ("app/src/main/java/com/menghuan/app/ui/chat/ChatViewModel.kt", "followGlobalApi"),
    ("app/src/main/java/com/menghuan/app/ui/chat/ChatViewModel.kt", "imageApiReady"),
    ("app/src/main/java/com/menghuan/app/ui/common/Neu.kt", "NeuPressDpSpec"),
    ("app/src/main/java/com/menghuan/app/ui/data/DataScreen.kt", "dateLabelOf"),
    ("app/src/main/java/com/menghuan/app/ui/data/DataViewModel.kt", "insightsByPeriod"),
    ("app/src/main/java/com/menghuan/app/ui/data/DataViewModel.kt", "suggestionApiReady"),
    ("app/src/main/java/com/menghuan/app/ui/live2d/Live2dSpaceViewModel.kt", "stageActions"),
    ("app/src/main/java/com/menghuan/app/ui/live2d/Live2dSpaceViewModel.kt", "namesOf"),
    ("app/src/main/java/com/menghuan/app/ui/moment/MomentsViewModel.kt", "importImage"),
    ("app/src/main/java/com/menghuan/app/ui/settings/SettingsPage.kt", "SettingsSearchRow"),
    ("app/src/main/java/com/menghuan/app/ui/theme/Theme.kt", "NeuAccentDeep"),
    ("app/src/main/java/com/menghuan/app/ui/theme/Theme.kt", "OutlineGray"),
    ("app/src/main/java/com/menghuan/app/ui/theme/Theme.kt", "SparkBrush"),
]

# 手工修正的边界：(文件, 符号) -> (起始行, 结束行) 1-based，含两端
OVERRIDE = {
    # SparkBrush 的 getter 分三行（val / @Composable / get() = remember { ... }）
    ("app/src/main/java/com/menghuan/app/ui/theme/Theme.kt", "SparkBrush"): (214, 217),
    # editableInPlace 与 copyAsMine 相邻，连中间的 KDoc/空行一起删
    ("app/src/main/java/com/menghuan/app/live2d/Live2dAction.kt", "editableInPlace"): (145, 157),
}

DECL = r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |protected |open |override |suspend |inline |operator |abstract |final |const |lateinit )*(?:fun|val|var)\s+%s\b"

def balance(s):
    d = 0
    for ch in s:
        if ch in "([{": d += 1
        elif ch in ")]}": d -= 1
    return d

def decl_start(ls, sym):
    for i, l in enumerate(ls):
        if re.match(DECL % re.escape(sym), l) and len(l) - len(l.lstrip()) <= 8:
            return i
    return None

def decl_end(ls, i):
    d, j = 0, i
    while j < len(ls):
        s = re.sub(r'"[^"]*"', '""', ls[j])
        s = re.sub(r"//.*$", "", s)
        d += balance(s)
        body = ls[j].rstrip()
        cont = body.endswith(("=", "&&", "||", "+", ",", ".", "->", "(", "[", "{"))
        nxt = ls[j + 1].strip() if j + 1 < len(ls) else ""
        if nxt.startswith((".", "?:", "+", "&&", "||")):
            cont = True
        if d <= 0 and not cont:
            return j
        j += 1
    return j

def kdoc_start(ls, i):
    s = i
    while s - 1 >= 0:
        pv = ls[s - 1].strip()
        if pv.startswith("//") or pv.startswith("*") or pv.startswith("/*"):
            s -= 1
        else:
            break
    return s

# 1) 汇总每个文件的删除区间
ranges = {}
for path, sym in TARGETS:
    if sym in HOLD:
        continue
    ls = io.open(path, encoding="utf-8").read().split("\n")
    if (path, sym) in OVERRIDE:
        a, b = OVERRIDE[(path, sym)]
        ranges.setdefault(path, []).append((a - 1, b - 1, sym))
        continue
    i = decl_start(ls, sym)
    if i is None:
        print("!! MISS %s :: %s" % (sym, path))
        continue
    ranges.setdefault(path, []).append((kdoc_start(ls, i), decl_end(ls, i), sym))

# 2) 逐文件从后往前删
total = 0
for path, rs in ranges.items():
    ls = io.open(path, encoding="utf-8").read().split("\n")
    rs = sorted(rs, key=lambda x: -x[0])
    for a, b, sym in rs:
        del ls[a:b + 1]
        total += 1
        # 收掉因此出现的连续空行
        while a - 1 >= 0 and a < len(ls) and ls[a - 1].strip() == "" and ls[a].strip() == "":
            del ls[a]
    io.open(path, "w", encoding="utf-8", newline="\n").write("\n".join(ls))
    print("%-62s 删 %d 处" % (path.replace("app/src/main/java/com/menghuan/app/", ""), len(rs)))

print("\n合计在 %d 个文件里删掉 %d 处声明。" % (len(ranges), total))
