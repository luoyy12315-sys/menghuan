# -*- coding: utf-8 -*-
"""③ dry run（修正版）：逐行累加括号平衡 + 续行判定，打印每处待删的精确文本。"""
import io, re

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
    ("app/src/main/java/com/menghuan/app/live2d/Live2dAction.kt", "copyAsMine"),
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
    ("app/src/main/java/com/menghuan/app/ui/data/DataViewModel.kt", "refreshInsights"),
    ("app/src/main/java/com/menghuan/app/ui/live2d/Live2dSpaceViewModel.kt", "stageActions"),
    ("app/src/main/java/com/menghuan/app/ui/live2d/Live2dSpaceViewModel.kt", "namesOf"),
    ("app/src/main/java/com/menghuan/app/ui/moment/MomentsViewModel.kt", "importImage"),
    ("app/src/main/java/com/menghuan/app/ui/settings/SettingsPage.kt", "SettingsSearchRow"),
    ("app/src/main/java/com/menghuan/app/ui/theme/Theme.kt", "NeuAccentDeep"),
    ("app/src/main/java/com/menghuan/app/ui/theme/Theme.kt", "OutlineGray"),
    ("app/src/main/java/com/menghuan/app/ui/theme/Theme.kt", "SparkBrush"),
]

cache = {}
def lines_of(p):
    if p not in cache:
        cache[p] = io.open(p, encoding="utf-8").read().split("\n")
    return cache[p]

DECL = r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |protected |open |override |suspend |inline |operator |abstract |final |const |lateinit )*(?:fun|val|var)\s+%s\b"

def decl_start(ls, sym):
    for i, l in enumerate(ls):
        if re.match(DECL % re.escape(sym), l) and len(l) - len(l.lstrip()) <= 8:
            return i
    return None

def balance(s):
    d = 0
    for ch in s:
        if ch in "([{": d += 1
        elif ch in ")]}": d -= 1
    return d

def decl_end(ls, i):
    """逐行累加，直到括号平衡且不处于续行。"""
    d = 0
    j = i
    while j < len(ls):
        line = ls[j]
        # 去掉字符串字面量里的括号干扰（粗略：去掉 "..." 内容）
        stripped = re.sub(r'"[^"]*"', '""', line)
        stripped = re.sub(r"//.*$", "", stripped)
        d += balance(stripped)
        body = line.rstrip()
        cont = body.endswith(("=", "&&", "||", "+", ",", ".", "->", "(", "[", "{"))
        # 下一行以续行符开头，也算未结束（Kotlin 的链式调用写法）
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

for path, sym in TARGETS:
    ls = lines_of(path)
    i = decl_start(ls, sym)
    if i is None:
        print("!! MISS %-28s %s" % (sym, path)); continue
    e = decl_end(ls, i)
    s = kdoc_start(ls, i)
    print("\n@@@ %s  [%d-%d] %s" % (sym, s + 1, e + 1, path.replace("app/src/main/java/com/menghuan/app/", "")))
    print("<<<DELETE-FROM>>>")
    for n in range(s, e + 1):
        print(ls[n])
    print("<<<DELETE-TO>>>")
