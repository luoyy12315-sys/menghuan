# 梦欢 · 项目细节（PLAYBOOK）

> 从 `MEMORY.md` 拆出来的**装不下的那部分**：命令行、脚本、dump 手法、子系统细节。
> `MEMORY.md` 放"要不要做"（规则、铁律、业务不变量），这里放"怎么做"（命令、脚本、机制细节）。
> 动对应子系统前先读相关小节。

## 构建 / 测试 / 出包
- JDK17：`C:/Users/16621/.workbuddy/toolchains/jdk17`
- Gradle 8.14.3（无 wrapper）：`C:/Users/16621/.gradle/wrapper/dists/gradle-8.14.3-all/10utluxaxniiv4wxiphsi49nj/gradle-8.14.3/bin/gradle`
- 中文路径必须 `subst X: "C:\Users\16621\Desktop\梦欢"`，之后一切 gradle（含测试）都从 `X:` 跑。报错里出现 `ÃÎ»¶` 就是编码问题。
- 每次要 `export JAVA_HOME=...; export PATH="$JAVA_HOME/bin:$PATH"`；日志写 `logs/`。
- 勿删：`local.properties`、`android.overridePathCheck=true`、阿里云镜像配置。
- 出包顺序：升版本号 → `assembleRelease` → 产物拷到根目录 `梦欢-v<版本>-release.apk` → 旧版移入 `archive/` → CHANGELOG / 手册 / 断言同步。
- **APK 字节数不能当"是否重建"的判据**（zip 对齐会凑长度），认 md5；要证某改动真编进去了，比 `classes.dex` 的 md5。
- 全量测试卡住：先 `jps -l` 找 `GradleWorkerMain`，`jstack` 抓栈，再清残留进程重跑。

## dex / 产物里搜中文
```python
open('classes.dex','rb').read().count('中文'.encode('utf-8'))
```
- ⚠️ `strings -e l`、`decode('utf-16-le')` 结果全 0 是**假阴性**；`grep` 对中文也失效 → 一律用 Python 数 UTF-8 字节。
- 短串容易误命中，以长串锚点为准。

## Git 推送（Windows）
- **在 Git Bash 里 `git push` 会失败**：`could not read Username for 'https://github.com': terminal prompts disabled`
  （本机 `credential.helper = helper-selector`，即 GCM；非交互会话里弹不出登录窗）。
- **改用 PowerShell 工具跑 `git push origin main`** → GCM 会弹窗授权，之后就成功。
- ⚠️ PowerShell 会把 git 写到 stderr 的进度当成错误、报 `NativeCommandError` + `CategoryInfo: NotSpecified`
  → **别据此判定失败**，认输出里的 `* [new branch] main -> main`；再用
  `git rev-parse HEAD origin/main` 两边一致来复核（比 `git status` 更硬）。

## 手册 replace 链 · dump 手法
- `jshell` **不在 PATH**，用 `C:/Users/16621/.workbuddy/toolchains/jdk17/bin/jshell.exe`。
- ⚠️ **Git Bash 下调 jshell 必须加 `MSYS_NO_PATHCONV=1`**：类路径是 `;` 分隔的列表，MSYS 会把整串当路径转换
  → 传进去变成 `--class-path` 找不到文件 / 或 `ClassNotFoundException`（我们的类明明在盘上）。加上就正常。
- classpath **Windows 用 `;`** 分隔，路径用 `X:/` 形式；stdlib jar 路径必须先 `cygpath -w` 转成 Windows 形式。
- **必须把 kotlin-stdlib jar 带上**（Kotlin 2.0.21），否则 `<clinit>` 抛 `NoClassDefFoundError: kotlin/text/StringsKt`。
- 读：`AiPrompts.INSTANCE.getDEFAULT_ASSISTANT_PROMPT()`；落空清单：`assistantManualPatchMisses()`。
- 核锚点用 Python `count()`（grep 对中文失效）。
- ⚠️ **别在 `.jsh` 里用 `v.contains("中文")` 找锚点** —— jshell 按**平台编码（GBK）**读脚本，中文串会乱码，
  结果是**恒 false 的假阴性**（v0.98.0 实际栽过）。可靠做法：脚本只输出 ASCII（`LEN=` / `MISSES=`），
  并把全文 dump 成 **UTF-8 文件**，再用 Python `count()` 核锚点。脚本放 `logs/dump_*.jsh`。
- ⚠️ 离线模拟补丁链不可信（v0.92.0：249 条模拟出 22 条假未命中，从第 87 条起级联失真）→ **只有真编译 + jshell dump 才可信**。

## 死代码审计脚本（`tools/`，每版可重跑）
| 脚本 | 用途 |
|---|---|
| `audit_deadcode.py` | A 段：顶层/对象级声明全工程零引用；B 段：Preferences 键只声明没读（`?` = 动态键工厂，必误报） |
| `audit_where.py <名字...>` | 逐个打印标识符**全部**出现位置（含 `.xml`），人工判真假 |
| `audit_params.py` | 参数只在签名出现一次（`applyDelta(connection)` 类）+ 可疑注释关键词 |
| `plan_deletions.py` | 打印每处待删声明的精确文本区间（含上方 KDoc） |
| `apply_deletions.py` | 执行删除，带 `OVERRIDE` 手工修正表 + `HOLD` 排除集 |
| `check_orphans.py` | 清理后自检孤儿注解 / 孤儿 KDoc |
- 已知局限：
  - 动态键（`companion_state_<id>`、`mcp_token_*`、`tts_*`、`search_*`）脚本判不了，**必然误报**。
  - `LruCache.sizeOf` / Room DAO / ExoPlayer Listener / Glance / GL / CameraX 的"未使用参数"都是**合法 override**。
  - `plan_deletions.py` 的 `decl_end` 靠括号平衡 + 续行判定，**会漏掉声明上方的 `@Query`/`@Composable` 注解行** → 必须手工补删，否则 KSP 报 `Not sure how to convert a Cursor to this method's return type (kotlin.Unit)`。
  - 扫描脚本自身别写成 O(n²)：逐名扫全文会被 SIGTERM；用 `Counter(re.findall(r"[A-Za-z_]\w*", text))` 单遍词频。
  - `DECL_FUN`/`DECL_VAL` 的 `^\s*` 行锚会命中同一行内换行下来的 lambda 参数；`internal fun` 分支要注意 group 吃掉 `fun`。

## 记忆语义召回（默认关）实现细节
- `memory_embeddings` 表按 `baseUrl|model` 分桶（换模型即换桶，不混用向量）。
- 融合打分：词法命中 60 分 / 语义-only 0.75 相似度，**双门槛**，缺一不进。
- 懒索引：一次最多 16 条未索引的记忆。
- 自动升降级：90 天未被召回的 `active` → `archived`（`core` 豁免）；`archived` 高分（≥80）被召回时当场升回 `active`；命中即 `touchRecalled()` 续期。
- `ExtractionEngine` 会把"现有条目名 + key 命名规范（2-6 字、复用优先）"喂给模型，防重复造条目。

## 重写大文件
- 先列顶层声明清单，搬完与 `git show HEAD:<文件>` 逐项对账。
- 禁止 `git checkout -- <文件>`（工作区长期大量未提交改动，曾冲掉 replace 链）。

## Live2D 细节（动它之前必读）
- 动作引擎 4 条铁律：① 动作播放写在 `saveParameters` 之后、`onLateUpdate` 之前（口型永远归实时语音）；② 内置动作一律不碰 `GENERIC_MOUTH`；③ 舞台通道走渲染矩阵、零参数依赖；④ 归一化 1.0 = 参数可用范围的 60%。
- 动作/表情清单从 GL 线程 `onCatalogReady` 回调出来；回调里只许转手，**不许碰 Room**；删动作要顺手清绑定。
- `ZipEntryGuard` 拒绝以 `/` 开头的条目。
- 通话页布局定稿：右滑纯净态 / 左滑唤回 / 顶栏常驻；角色靠 `setViewportBottomInset` 收进上方块，**不许再加蒙版**。
- 橡皮筋缩放 `PinchScale.kt`：先减速再夹结果，按当前值连乘（连捏收敛到 ≈0.411×，不是硬顶）；缩放不持久化。
- 背景：`StageBackground` sealed + `StageVideoBackgroundEngine`（ExoPlayer + TextureView），**绝不引 media3-ui**；三档 = 系统预设 / 图片 / 视频（80MB、30 秒、可解码，三道校验）。
