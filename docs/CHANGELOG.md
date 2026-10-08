# 梦欢 · 变更日志

> **v0.36.0 起应用改名**：从「导师」改为「梦欢」，Android 包名 `com.daoshi.app` → `com.menghuan.app`。
> 老用户升级路径：先在 v0.35.3 里「设置 · 数据与隐私 · 导出全部数据」，装新版后「导入数据」；
> API Key 因 Keystore 与包名绑定必须重填。

## v0.67.0（versionCode 156）

**视频通话与 Live2D 全面优化：动作承接层（不再"弹一下"）、舞台绕角色自己转、帧节拍封顶 60fps、
四态默认动作提亮，通话页新增点角色与动作胶囊，动作设计系统去掉说明性小字。**

范围是用户点名"全做（推荐）+ 明显档 + 点角色与动作胶囊 + 自检完就出包"那一圈。
施工前只做了一件事：把 `live2d/`、`realtime/visual/`、`ui/realtime/`、`ui/live2d/` 全读了一遍，
下面每一条都对着文件:行号，不是"看起来应该改"。

### 一、动作承接层（新增 `live2d/ActionBlender.kt`，`ActionPlayer` 重写）

**这是"动作不自然"这一族里唯一的根因。** `CubismModel.loadParameters()` 每帧都把参数**恢复成模型
自己的姿态**，所以"没有动作在写这个参数"在屏幕上不是"停在最后一帧"，而是**立刻弹回静止位**。
v0.64 的写法有三处都踩在这上面：

- 换动作时新包络从 0 起（`envelope = elapsed / fadeIn`）→ 旧动作正把头摆在 +0.3，新动作第一帧写成
  `rest + 0`，**先弹回中间再淡进来**。通话里四态来回切，每切一次脖子抖一下。
- 一次性动作播完直接 `current = null`、舞台返回 `NONE` → 翻跟斗转到一半被打断，角色**瞬间转正**。
- 循环动作绕回起点那一下，首尾关键值不等时**硬跳一格**（内置的凑巧相等，用户拼的、AI 生成的几乎必然不等）。

改法只有一个机制：**从"上一帧真正写出去的值"淡到"这一帧该写的值"**，起点在 `startFade` 那一刻冻结。
四态切换 / 停止 / 循环接缝 / 强度改档全走这一条路。三条刻意的设计：

1. **从静止起播时输出 = 目标 × 权重**，与旧的淡入包络同值 —— 所以"从零开始"的动作观感一个字没变，
   改动只落在"从别的姿态接手"的那三种情况。
2. **淡完就不再"拥有"那个通道**（`owns()` 返回 false，`ActionPlayer` 随即停止写它）。
   淡完还继续写 0，等于每帧把这个参数**按在静止值上**，模型自己的 Idle 动作再也摆不动它 ——
   表现是"播过一次摆手，角色自己的待机动作从此没了"。这条有单测守着。
3. **旋转起点折到最近的那一整圈**（`snapToNearestTurn`）：350° 与 -10° 画面上是同一个姿势，
   从 350° 淡回 0 会**倒着转大半圈**，从 -10° 淡回 0 才是"翻过去差一点站稳"。

全部是定长数组、每帧零分配（渲染线程每秒 60 次，这里 new 一个 Map 都是负担）。
`ActionBlender` 与 `fillNdcMatrix` 都是纯函数，进了 `Live2dActionTest`（9 条新用例）。

### 二、舞台旋转绕的是**角色中心**，不是 NDC 原点（`Live2dCharacter` + `StagePose.fillNdcMatrix`）

`applyStagePose` 原来叠的是 `T(offset) · R · S · projection`，绕 NDC 原点 = **屏幕正中**。
而通话页是分区布局：角色被 `bottomInset` 整只抬到上半屏 —— 于是"翻跟斗"画出来是
"贴着一条大圆弧绕屏幕转"，角色空间里把角色拖到边上再翻也一样甩出去。
改成 `T(c) · M · T(-c) · projection`（c = 角色中心，由 `fitProjection` 每帧算好交过来）。
展开后只动平移那一列，所以**每帧只多四次乘法、没有分配**。矩阵换算抽成纯函数 `fillNdcMatrix`，
单测钉住两条：角色中心那一点在变换后纹丝不动；**pivot 为 0 时与旧实现逐元素相同**
（语音通话那条不分区的路径行为不变的凭据就在这条断言里）。

### 三、帧节拍：渲染循环原来在 120Hz 机器上满速跑（`GlTextureView.loop`）

原来只靠 `eglSwapBuffers` 的缓冲队列背压限速，而 TextureView 那条队列有 2~3 块缓冲 ——
结果**满速跑一百多帧每秒**：Live2D 的顶点变形 + 全图元绘制白做两倍，GPU 占用翻倍、机身发热，
热到降频就是"通话打久了越来越卡"。用户报的"卡顿"多半是这条，不是单帧太慢。
现在按 60fps 起表，睡不满就 park，落后太多重新起表而不追帧（追帧的表现是"回前台那几秒飞快地抖"）。
定 60 而不是跟屏幕刷新率走：动作本来就是按墙钟 + 60fps 设计的，120Hz 上多画的帧只是把同一份姿态差插一遍。

### 四、回前台不再跳帧（`Live2dCharacter.update` + `resetFrameClock`）

`dt` 的上限从 **100ms 收到 1/30s**：切后台/锁屏回来那一刻 `nanoTime` 差出几秒，
夹在 100ms 就等于**白跳十分之一秒**，观感是"角色抽一下"；1/30 已经是两帧的量，够吸收偶发丢帧。
另外 `resume()` 现在先 `resetFrameClock()`（字段改 `@Volatile`，因为调它的是主线程那条生命周期回调）。

### 五、四态默认动作按"看得出来"重写（`BuiltinActions`）

原来轨道值只有 0.03–0.17，乘上 `AMPLITUDE_SPAN 0.9` 之后只吃掉参数可用范围的百分之几 ——
`ParamAngleZ` 典型 ±10° 时头只转 0.5°~1.5°，屏幕上基本读不出"在动"。
现在峰值落在 **0.16–0.42**（头的部分 3°~8°），四态各自补了读法：倾听=两次点头 + 歪头 + 抬眉、
思考=仰头 + 视线飘到一角 + 皱眉、说话=头随语句摆三下 + 提嘴角。
顺带把**所有内置动作里的 `GENERIC_EYE`（睁眼）轨删掉**：框架的 `CubismEyeBlink` 在
"这一帧没有 motion 写参数"时是 `setParameterValue` **覆盖**眼睛，而免费模型根本没有 motion 文件
→ 恰好每帧走覆盖那一路，写了等于在这批模型上静默失效、换一张有 motion 的模型又突然生效。
眼睛的戏改由眉毛与视线承担（这两个参数眨眼不碰）。有单测守着"任何内置动作都不写睁眼"。

### 六、通话页新增互动入口（用户挑"点角色 + 动作胶囊"）

- **点角色**：判定与绑定完全复用角色空间那套（`Live2dStage.hit` + `resolveInteraction`），
  点着头部 → 摇头、点着身体 → 摆手、点旁边空白 → 随机一个趣味动作。
  **落在对话台那一条上不算** —— 点字幕是想读字，不是想让 TA 摆手。
- **角色脚边一排胶囊**：收藏的排前面 + 内置四个趣味动作，**上限 4 条**。
  刻意不做成可横滚的 `LazyRow`：横滚容器会跟"右滑进纯净态"抢同一根手指（这一条是自检时发现的，
  第一版就是写成 LazyRow 的）。4 颗 × 80dp 上限 + 间距 = 344dp，360dp 宽的机器排得下，
  长名字用省略号截。要演别的动作去角色空间的动作库。
  只有 Live2D 才出现（伪视频没有可驱动的参数，给一条点了没反应的入口比不给更糟）。
- **演完接得回去**：`ActionPlayer` 只露一个 `finishedCount` 读数，引擎每帧比一下就知道
  "刚演完一条临时动作"，然后把当前状态绑的那条接上。刻意**不做从渲染线程往外打的回调**
  （项目里已经有 `onCatalogReady` 那条"只许转手"的规矩，不想再来第二条）。
  摆完三次手之后角色不会僵在原地等 AI 说下一句。

### 七、右滑手势：一次拖动不再排 60~120 条协程（`RealtimeCallScreen`）

原来每个拖动手件都 `scope.launch { spread.snapTo(…) }`。现在点按与横滑合进**一个
`awaitEachGesture` 循环**：拖动只写一个普通浮点 `dragPx`（非挂起），松手才把它并进 `spread` 去跑弹簧。
两处细节：`awaitEachGesture` 的 lambda 带 `@RestrictSuspension`，在里面调 `snapTo` **编译不过**
（这就是不能照原样"直接在这条协程里 snapTo"的原因）；以及**先 `snapTo(total)` 再清 `dragPx`**，
顺序反了会在松手那一帧跳一下。

### 八、动作幅度档位（`live2d/ActionScale.kt` + 会话设置「通话动作」页顶那一行）

四档 **小 / 标准 / 大 / 特大**（0.7 / 1.0 / 1.4 / 1.8），按角色记，存在 DataStore 的
`call_action_scale`（`id\t档位键` 行式表，与「通话背景」同一套）。
做成四档而不是滑块：每条动作自己已经有一个"强度"滑块，再来一个连续的"全局幅度"，
用户就没法回答"最终这个动作用了多少力是谁定的"。倍率只乘强度、不碰轨道值，
且仍被 `MAX_AMPLITUDE` 夹住（那道夹子是"任何模型都不该被甩到破图"的保险）。

### 九、动作设计系统收紧（AGENTS §四"界面不写说明性小字"）

- 删掉四处说明：动作库"点一行就在角色身上试播…"、编辑器"选一个基础动作，调一下手感就行…"、
  AI 生成那整段流程说明 + "正在让 AI 设计动作…这一步会联网，等一下就好"、
  角色空间"拖动可移动角色 · 点头部 / 身体有反应"、空态那句解释为什么要 Live2D。
  行首那颗播放三角就是"点它会播"的交代。
- 「通话动作」的选择器**不再列出四态默认动作** —— 它们就是第一项「系统默认」本身，
  列出来等于可以把"默认待机"绑给"说话"，那一档播起来和没绑一样。
- 编辑器的强度/速度滑块上限**改取数据层常数**（`MIN/MAX_AMPLITUDE`、`MIN/MAX_SPEED`）：
  原来写死 1.8，比 `MAX_AMPLITUDE = 2.0` 小一号，两套上限并存就是两个东西在说话。
- `TrackKey` / `ModelCapability` 两处注释里那句"±1 = 可用范围的 60%"是 v0.65 把常数提到 0.9 时
  漏改的，现在指回 `ActionPlayer.AMPLITUDE_SPAN` 本身。

### 十、查过、确认不是问题（免得下一轮又被翻出来）

- `CubismEyeBlinkUpdater` 的门是 `if (!motionUpdated) blink()` —— 免费模型没有 motion 文件时
  `motionUpdated` 恒为 false，眨眼照常跑，**不存在"永远不眨"**（读过框架源码 `:49-52`）。
- `Live2dSpaceScreen` 直接写 `statusBarsPadding()` / `navigationBarsPadding()` **不是违规**：
  那条路由刻意不套 `AdaptiveScreen`（`MainActivity:501-503` 写了理由：空间是要铺满的舞台，
  版心会把角色夹窄），insets 由页面自己处理是唯一正确的做法。
- 动作库里预览用的动作 id=0 不会把整列点亮：库里的行都是落过库的（id>0），
  `playing = action.id == playingId` 在 playingId=0 时匹配不到任何一行，正是想要的。
- **命中检测的跨线程共享只剩一处、且是既有的**：`hitRegion` 现在反算用的是四个 `@Volatile` 的
  float 快照（`hitScaleX/Y`、`hitTranslateX/Y`）而不是那个每帧被 GL 重写的 `CubismMatrix44`
  —— 读一个可变矩阵会拿到"半新半旧"的一份，表现为**偶发点错一档**（点了头判成身体）。
  剩下 `isHit(id, mx, my)` 那一路（模型**自带 HitArea** 时才走）仍然在 UI 线程读 drawable 透明度，
  这是 v0.64 起就有的既有共享、不是本轮引入的：它的后果最多是判错一档而不是崩，
  而要彻底消掉就得给 GL 线程加一条事件队列 —— 那是另一个量级的改动，**本轮刻意不做**，
  写在这是为了让下一轮别再把它当"新发现的 bug"翻出来。免费模型（没有 HitArea）走几何兜底那一路，
  全程只读 volatile，已经没有这个问题了。

### 交付与验证

- 编译：`compileDebugKotlin` + `compileDebugUnitTestKotlin` + `assembleRelease` 都通过。
- 单测：`Live2dActionTest`(53) / `AiPromptsTest`(54) / `CallVisualTest`(20) 共 127 条全绿
  （其中 10 条是本轮新增的承接层、舞台矩阵与幅度档位用例）。
  全量 `testDebugUnitTest` 没追：Robolectric 那批（`UiRenderTest` 等）历史上有 7 项环境性红，
  与本轮无关（v0.65 那轮就记过），按"一次定性就停"的口径不往里钻。
- 手册：`DEFAULT_ASSISTANT_PROMPT` 尾部加 4 条定点 replace（§零.5），
  `AiPromptsTest` 配了 `v0670 通话里点角色与动作胶囊与动作幅度 进手册` 一整条，
  并把 v0.64 那条里被改写的"只做绑定"断言同步成新措辞。
- 出包：**`梦欢-v0.67.0-release.apk`**（versionCode 156，38,067,067 字节）。
  根目录只留当前版，`梦欢-v0.66.1-release.apk` 同一步移入 `archive/`（不删）。
  证书 SHA-256 `486d6e776e573b58…3cd5717a` 与历次同一张 debug 证书 → **直接覆盖安装**。
- ⚠️ **本轮全部改动都没上真机**：动作是否"看得出来"、翻跟斗是否真的在原地翻、
  滑动跟手是否不抖、发热是否降下来，都要装机才能定论（AGENTS §零.7 这一族的教训就是
  "看着是白屏"猜掉三个版本）。测试形象仍用**小柚铃**（奶娃要从人设管理卡片右端那颗气泡找回）。
  装机最值得盯的四件事：① 四态来回切时脖子还弹不弹；② 点角色有反应、点字幕没反应；
  ③ 翻跟斗是原地翻还是绕着屏幕转；④ 打到十分钟后有没有变卡（帧节拍那条的疗效）。

### 自检轮次（用户要求"至少 20 轮迭代才能出包"）

改完之后按"一次只盯一个视角"读了 22 轮，抓到并修掉的实际问题（不是走流程刷计数）：

| 轮 | 视角 | 结果 |
|---|---|---|
| 1–4 | 承接层的每帧协议（startFade/beginFrame/advance/settle 的先后） | 协议自洽；确认从静止起播与旧包络同值 |
| 5 | ActionPlayer 的 phase 状态机 | 停/播同帧到达的判序写清楚（`play()` 清 stopRequested，后写的赢） |
| 6 | 四态默认与趣味动作的**循环首尾连续性** | 逐条核对 first==last，接缝靠承接层摊平 |
| 7 | GlTextureView 帧节拍与暂停唤醒 | 唤醒后重新起表（追帧会表现成"回前台狂抖"） |
| 8 | 引擎的演完接回 | `finishedCount` 只在 GL 线程读写；`awaitingRestore` 不会被状态切换误置 |
| 9 | **点击判定的跨线程撕裂** | 抓到：UI 线程在读 GL 每帧重写的 `CubismMatrix44` → 改成四个 volatile 数 |
| 10 | 舞台缩放的夹子 | 抓到：v0.64 的 `coerceIn(0.6,1.6)` 在搬迁时**丢了** → 补回 + 新增单测 |
| 11 | 手势事件流断掉（change==null） | 抓到：`dragPx` 会永远留在一半位移、对话台卡住 → 按已松手收一次 |
| 12 | **胶囊那一排与右滑抢手势** | 抓到：`LazyRow` 横向可滚会吃掉右滑 → 改居中 `Row`、上限 6→4、长名省略号 |
| 13 | 语音通话隔离 | 点角色/胶囊都挂在 `videoPictureOn && stageInteractive` 上，语音路径零改动 |
| 14 | 设计语言一致性 | 抓到：`ScaleRow` 未选中用了 `NeuTrack` → 改回 `NeuSurface`（选中=黑药丸） |
| 15 | 未使用 import / 编译器警告 | touched 文件零新警告 |
| 16 | 手册锚点是否会被前面的 replace 吃掉 | 四条锚点全取自链上更早那条的**产出**；跑 `AiPromptsTest` 实证 |
| 17 | 文档与代码口径 | 抓到：CHANGELOG/ROADMAP 还写着"上限 6 条" → 跟着改成 4 并写清为什么 |
| 18 | 命中检测剩下的那一处共享 | 定性为既有风险（模型自带 HitArea 才走），记进"查过不是问题"，不扩范围 |
| 19 | `owns()` 与"淡完写 0"的反面 | 确认不会把参数按在静止位（模型自己的 Idle 动作保得住） |
| 20 | 单测数量与结论 | 从"126 条"核实成 53+54+20=127 条 |
| 21 | release 构建（R8）与产物 | 出包成功、签名与历次同证书、体积 +16KB（Live2D 那批没有新依赖） |
| 22 | 归档流程 | 老包进 `archive/`、根目录只留当前版，同一步做完 |

## v0.66.1（versionCode 155）

**Live2D 通话这条线做一次体检：修 shader 单例与双视图互踩、把静默失败判死、暂停与主线程减负。纯优化，没有新功能。**

审计范围是 `realtime/visual/`（Live2D 引擎 + `GlTextureView`）、`live2d/`（动作系统）与通话页。
下面每条都有文件:行号与"为什么原来那样是错的"，因为这一条线历史上四次返工里有三次是把
"看不见东西"猜成了别的原因（AGENTS §零.7）。

### 一、shader 单例：原来"先编译、再清表、再拆"（`Live2dVisualEngine.onSurfaceCreated`）
- 原来是 `getInstance().releaseInvalidShaderProgram()` 紧跟 `deleteInstance()`。
  框架源码 `CubismShaderAndroid:53-62` 里 **`getInstance()` 一被调用就 `generateShaders()`**，
  于是先在**新上下文**里编译一批 program；而 `releaseInvalidShaderProgram()` 只做
  `shaderSets.clear()`、**不 `glDeleteProgram`**，清完表之后 `deleteInstance()` 就再也找不到它们
  —— 那批 program 成了没人认领的 GPU 对象，**旋转 / 分屏 / 回前台每来一次漏一份**，
  而且还白编译一遍（编译发生在根本用不上它的时刻）。
- 现在只留 `deleteInstance()`：把绑在死上下文上的那份整丢掉，真正的编译由首帧绘制时
  渲染器自己的 `getInstance()` 完成（那一刻上下文是活的、也确实要画）。
  框架自己也是这个口径——`CubismRendererAndroid.doStaticRelease()` 里就只有 `deleteInstance()`。

### 二、两块视图同时驱动同一个模型（同上文件，新增"视图代数"）
- `AndroidView` 的 factory 会重跑（旋转、分屏、页面重进），而每一块 `GlTextureView`
  自带一条 EGL 线程、一个上下文。旧那块不认得"我已经不是当前这块"，
  它的 `onSurfaceCreated` 会因为 `c.ready==true && viewWidth>0` 走进 `reloadRenderer`
  —— **同一个模型被两个上下文各建一份渲染器与纹理**，表现又是"ready=true、零报错、屏幕空白"。
- 修法：`viewGeneration`（`@Volatile`）每次 `onCreateView` 自增，renderer 改成 `rendererFor(gen)`，
  三个回调进来先比代数，不是当前那代就只收摊、不碰模型；同时 `onCreateView` 先叫旧视图
  `quitSoon()`（只置标志，不在组合期等它 join）。

### 三、静默失败当场判死（§零.7 的口径补上两处）
- `Live2dVisualEngine.load()` 现在**内部 swallow**（`runCatching` → `Log.e` + `Degraded(原因)`，
  `CancellationException` 原样抛出）。§零.7 早就写了"`suspend fun load` 内部 swallow"，
  而原来是裸的——抛出物一路冒泡，调用方吞掉后 runtime 还停在 `Loading`。
- `Live2dSpaceViewModel` 那句裸 `runCatching { e.load(...) }` 补了日志；
  **`Live2dSpaceScreen` 原本压根没读过 `ui.runtime`**，所以现在把 `Degraded` 的原因写在页面中央
  （这不算 §四禁的"说明性小字"：视觉坏了和"还没加载出来"在屏幕上是一回事，不写就只能猜）。
- `RealtimeCallViewModel` 的视觉加载失败也补了 `Log.e`（原来只有一句固定文案、异常对象被丢掉）。

### 四、渲染线程的暂停（`GlTextureView`）
- 原来 `loop()` 把 `eglMakeCurrent` 放在 `paused` 判断**之前**，所以停画期间每 20ms 还要
  真发一次 EGL 调用；锁屏一夜就是几百万次空转与线程唤醒。现在暂停用 `LockSupport.parkNanos` 真的睡着，
  一个 EGL 调用都不发，`setPaused(false)` 与 `quit()` 都 `unpark` 立刻叫醒
  （后者是怕挂断时主线程正卡在 `join` 上等它多睡一个超时）。
  ⚠️ 这一条**第一版写的是 `synchronized(lock) { lock.wait() }`，编译直接被拒**：
  Kotlin 的 `Any` 类型看不见 `wait`/`notify`（报 `Unresolved reference 'wait'`）。记在这里，
  下次别再用 Java 那套监视器写法往 Kotlin 上抄。
- 新增 `pausedByPage`：`onSurfaceTextureAvailable` 每次重开线程都要把页面要求的暂停状态**交给新线程**。
  原来"切后台 + 转一下屏"会让渲染在后台悄悄跑起来。
- `joinQuietly()` 改为返回"线程是否真的退了"，`shutdown()` 把它透出去。

### 五、帧循环里白付的开销
- `Live2dCharacter.update()`：几何兜底的包围盒从**每帧**改成 **15Hz**（`BOUNDS_INTERVAL_SECONDS`）。
  这个值只在用户点下去那一刻用，而算一次要把全部 drawable 的全部顶点读一遍；
  只在"模型没写 Head/Body HitArea"的免费模型上才跑，而那正是真机主力素材。
- `applyStagePose` 不再每帧 `floatArrayOf` 新建 16 个 float（注释本来就写着"复用一个实例"，
  矩阵复用了、数组没复用），改存 `stageMatrixArray`。
- `ActionPlayer.applyTo`：删掉每帧 `val stage = StagePose()`（**死变量**，后面用的是四个局部量）；
  逐通道写参数从 `list.forEach { }` 换成 `for` —— 后者每帧每个通道都要建一个捕获闭包。

### 六、通话主线程
- `ui/common/StageBackground.kt`：注释写着"IO 线程 + 降采样"，实际 `produceState` 的块
  **默认跑在主线程**（降采样那条是真的做了，线程那条是假的）。现在显式 `withContext(Dispatchers.IO)`，
  解码失败补一条 `Log.e`。进视频通话那一下不再因为解相册图卡一帧。
- `RealtimeCallScreen`：`callBackground` 的订阅与文件 stat 用 `videoMode` 门住
  （flow 用 `remember(videoMode)` 钉住，不然每次重组都换一个新 flow 实例、`collectAsState` 重订阅一遍）。
  语音通话按 §零.7 应该完全不碰视觉层，原来只是"不画"，订阅与磁盘都在跑。
- **字幕改成按节拍重铺**：原来是 `remember(ui.turns, ui.userPartial, ui.aiPartial) { buildLines(ui) }`，
  而 `AiDelta` 一秒能来几十次，每次都把最近 200 条整表重铺 + 整列重组。
  现在 80ms 一跳，并且**先比签名**（条数 + 两段进行中的原文）再决定重铺，所以没人说话时一次都不铺。

### 七、`release()` 不再可能当着还在跑的一帧拆模型
`shutdown()` 现在报告线程到底有没有退出；没在预算内退出（有一帧卡住）就**跳过**主线程的
`character.destroy()` 并留下读数。宁可这一份模型随上下文与进程回收，也不制造"挂断时偶发闪退"。

### 八、查过**不是问题**的两条（记下来免得下次又怀疑）
- "上传纹理没有 `glDeleteTextures`"：模型只在 EGL 上下文销毁后重建，旧纹理随上下文回收，
  `textureIds.clear()` 是对的。
- "伪视频 `publishReady()` 没有去重闸门"：`VisualRuntime.Ready` 是 data class，
  `StateFlow` 本身就按 `equals` 丢重复值，所以 `onVideoSizeChanged` 反复发同一个 Ready 不会引起重组。

### 检验与出包（v0.66.1）
- **第一次 `assembleRelease` 失败**（42s，`e: GlTextureView.kt: Unresolved reference 'wait' / 'notifyAll' /
  'shutdown'`）：两处都是本批自己写错的 —— 监视器那套 Java 写法在 Kotlin 里 `Any` 看不见 `wait`/`notify`；
  另一处是 `onSurfaceTextureDestroyed` 里对着 `GlRenderThread` 调了视图上的 `shutdown()`。
  改成 `LockSupport.parkNanos` + 直接调 `shutdown()` 后过。
- 第二次 `assembleRelease` **BUILD SUCCESSFUL 2m41s** → `梦欢-v0.66.1-release.apk`
  （37MB，md5 `f66f3811bab93af2a6421f3f64676bcb`），aapt 核对 **155 / 0.66.1**，
  `apksigner verify` 出的证书与 v0.66.0 是同一条 debug 证书（可直接覆盖升级）；v0.66.0 已进 `archive/`。
- ⚠️ 本轮**没跑单元测试**（只发话了出包）；v0.66.0 记下的那条截图套件跨类串状态挂死仍然挂着。

### ⚠️ 待真机验收
1. 转屏 / 分屏 / 后台回前台后，Live2D 形象**仍然画得出来**（探针 `live2d publish …`、
   `live2d probe corner rgba=` 都还在，读数应为 `0,0,0,0`）。
2. 挂断不闪退；日志里若出现 `live2d release: GL 线程未在预算内退出` 要回报，那条路是兜底不是常态。
3. 角色空间里模型坏了：页面中央应出现一句降级原因，而不是永远"正在准备角色…"。
4. 通话中右滑/左滑、字幕逐字观感（80ms 一刷，正常人眼应看不出与逐 token 的区别）。
5. 免费模型（没有 HitArea 的那种）点头部 / 身体 / 空白仍然判得准——包围盒现在 15Hz 刷。

### 没做的一条（想做要连真机一起）
把 `character.destroy()` 从主线程挪到 GL 线程退出前执行，才是"删 GL 对象"的正解
（GL 对象必须在上下文还绑着的时候删）。这一改动动的正是 §零.7 里那条
"先把 GL 线程停干净再拆模型"的既有不变量，而这轮点名不碰真机，所以只做了保守版。


**联网搜索三家各一份 Key（修"切换国内国外 key 都不变"）；视频通话加「通话背景」。**

### 一、联网搜索：每家一桶（用户点名"联网搜索切换不了，切换国内国外 key 都不变"）
- **根因**：`search_key_enc/plain` 是**博查与 Tavily 共用的一把**。切服务商时输入框显示的还是同一串、
  保存也写回同一串，于是拿博查的 Key 去打 Tavily —— 永远"没搜到"，读起来就像"切换没生效"。
  这与 v0.52.0 语音合成踩过的是**同一个坑**，修法照搬那一版（AGENTS §零.4「每家一套独立配置」）。
- `SettingsRepository`：新增 `searchKeyOf(provider, field)` 分桶键（`search_bocha_key_enc` /
  `search_tavily_key_plain` / `search_custom_url`…）；自定义那一家的老键名本来就长这样，三家天然对齐。
  对外收成 `searchApiKeyOf(provider)` / `searchCustomUrl` / `saveSearchConfig(provider, key, customUrl)` /
  `searchKeyFor` / `searchEndpointFor`；删掉 `setSearchProvider` + `setSearchApiKey` +
  `setSearchCustomApiKey` + `setSearchCustomUrl` 这四条"各家混写"的老入口（少一条路径就少一次串味）。
- ⚠️ **`saveSearchConfig` 里三件事必须在同一个 `edit` 块、顺序不许换**：
  ① 先把老单桶**落到它那一家的桶**里（先清老键会把用户填过的 Key 凭空抹掉 —— v0.52.0 就是这么丢的）；
  ② 只写当前这一家；③ 最后才切 `search_provider`（先切再搬，老数据会记到新选那一家名下）。
- **老数据零操作**：老单桶虚拟归属到**当时生效的那一家**（只在"这家整桶从没写过"时回退，
  不逐字段混读；用 `contains` 而不是判空 —— 用户主动清空 Key 写的是空串，键在=写过，不能被老键复活）。
- **另一个真实故障**：设置页「保存」原来是 `scope.launch { 写库 }` 之后**立刻** `onBack()`，
  而 `rememberCoroutineScope` 挂在这一页的组合上，页面一拆、正在写的协程就被取消
  —— 表现正是"点了保存、切回来还是原来那一家"。现在**写完再返回**。
- 回归：`SettingsRepositoryTest` 新增三条（三家各一份 / 清空一家不碰另外两家 / 老单桶归属并活过保存别家）。

### 二、视频通话背景（用户点名"视频通话加个通话背景选择"，口径：只给视频通话 · 按角色 · 两档）
- **入口**：会话设置页「视频通话形象」下面新增一行**「通话背景」**，右侧直接写当前选的哪一档；
  点开是底部弹层两行：**系统预设**（App 给的那张渐变）/ **自定义背景**（从相册选一张）。
- **存储**：档位存 DataStore 的 `call_background` 表（`id\tpreset|file:<名字>`），
  图片落 `filesDir/call_background/<personaId>/<原图名>`（一张就够，换图先删旧的；
  沿用用户那张图的原名，不编 `asset_<时间戳>` 那种用户没见过的名字）。库里不存绝对路径。
- **只有视频通话读它**（`RealtimeCallScreen` 里挂在 `if (videoMode)` 那一支）：语音通话维持现在的底色。
  它在最底下：Live2D 那层是透出的 GL，画在它背后才看得见；伪视频素材自己满屏，会盖在背景上面。
- **画法与角色空间共用一份**（新文件 `ui/common/StageBackground.kt`）：
  `StagePresetBrush` + 降采样解码 + `StageBackgroundLayer`，**探针**（`stage background custom=… file=… decoded=…`）
  也跟着一份。两处共用不是因为想省代码，是因为上一版"各画一遍"才让角色空间那条淡到看不见、
  而通话页一句"没底色"，两处说的不是同一件事。
  ⚠️ 图读不出来 / 没配过都回落画系统预设 —— 这一层永远有东西。
- 级联清理：删人设时 `CallBackgroundStore.deletePersona`（目录）+ `setCallBackground(id, "")`（档位行）。
- `Live2dSpaceScreen` 里那套私有背景实现（`SpacePresetBrush` / `SpaceBackgroundLayer` /
  `rememberSpaceImage` / `MAX_BACKGROUND_PX`）整块删除，改调共用层。

### 三、工程
- 助手手册：3 条 v0.66.0 定点 replace（联网搜索每家一份 + 老 Key 不用重填 + 「通话背景」那一行与新章节），
  旧那句"自定义家有独立的一份 Key，切回博查 / Tavily 不会互相覆盖掉"必须整段消失；
  `AiPromptsTest` 加 `v0660 联网搜索每家一份 Key 与通话背景 进手册`（双向断言）。

### ⚠️ 待真机验收
1. 联网搜索：升级后原来那把 Key 还在不在（应显示在"当时生效那一家"名下）；切三家各看各的；
   点保存后退回列表再进来，服务商与 Key 都应是刚填的（这条同时验"写完再返回"那个取消竞态）。
2. 通话背景：预设档看得见颜色、自定义图铺满不拉伸变形；Live2D 形象下背景从透明处透出来；
   语音通话没被误铺。

### 检验与出包
- `assembleRelease`：**BUILD SUCCESSFUL 2m05s**；`aapt2` 核对 **versionCode=154 / versionName=0.66.0**，
  证书仍是历次那张 Android Debug（可覆盖升级）。产物 `梦欢-v0.66.0-release.apk`（37MB，
  md5 `b5aeab7d6dbd7989c3cfc164d6c85d2c`，与构建产物一致），v0.65.0 已移入 `archive/`。
- 逻辑单测定向跑：`SettingsRepositoryTest + AiPromptsTest + AssistantManualUpgradeTest +
  Live2dActionTest + CallVisualTest + TtsProviderConfigTest + ScheduledMessageTest`
  → **135 项 / 修复后全绿**（本轮抓到并修掉两处自己的问题：新增测试的返回类型不是 void 被 JUnit
  拒收；v0.66 改写手册打断了两条旧断言 —— 按惯例旧断言翻成 assertFalse 并指路到新测试）。
- ⚠️ **全量套件（含 Robolectric 截图测试）在 `UiRenderTest.render12WeeklyPlaceholder` 处挂死**：
  那一行是主线程 `runBlocking { saveApiConfig(...) }` 等 DataStore，等不到 → 整进程卡住
  （jstack 看到 DataStore 工作线程全空闲）。挂在测试脚手架的跨类共享状态上
  （`settingsStore` 是进程级单例，Robolectric 每个类一套沙箱），不是这批改的功能坏了；
  单独跑或成对跑 `UiRenderTest` / `AssistantManualUpgradeTest` 都不复现。
  **本版没追这条**（用户要求直接出包），留作后续：要么给截图套件分 JVM，
  要么把那行改成 `compose.runOnIdle` 里写库。

## v0.65.0（versionCode 153）

**备忘录收成入口卡 + 全屏卡片列表；视频通话底部对白底改透明；动作幅度 +50%；会话设置行与角色空间背景按用户点名收敛。**

### 一、备忘本换形态（用户点名"和记账本一样大小，表面不再显示任何信息，点进去才是卡片形式的每一条"）
- **数据页**（`DataScreen`）：原来那张能写字、还摊着置顶一条的 `MemoWallCard` 整个删掉，
  换成 `MemoEntryCard`（`ui/memo/MemoComponents.kt`）——与「记账本」入口卡**逐项同尺寸**
  （16dp 圆角白面 / 16dp 内边距 / 38dp 凹陷圆图标 + 19dp 图标 / 15sp 卡名 / 18dp 箭头），
  **表面不显示条目、不显示条数、不能打字**。
- **全屏页**（`ui/memo/MemoScreen.kt`）：`Column + verticalScroll` 的行列表改成
  **LazyColumn + 一条一张卡片**（`MemoRow` → `MemoCard`）；页顶保留那行「记点什么…」。
- **动效照人设管理**：`animateItem(fadeInSpec = tween(340, delayMillis = index*50 封顶 300))`
  ——与人设管理那张卡同一套"逐条错峰展开"。两段式删除（先滑走再收高度、动画跑完才落库）保留，
  它跑在 `animateItem` 之前，所以到退场那一刻高度已经是 0，两套动画不打架。
- 数据页跟着清的：`imePadding()`（本页没有输入框了）、`memoMessage` 那条自动清除协程、
  `DataScreen(onOpenChat=…)` 这个只为"发给 AI 后跳会话"存在的参数（跳转会话现在只有全屏页会触发）。

### 二、视频通话底部（用户点名"下面 UI 的背景白膜换成透明的"）
- `RealtimeCallScreen` 的**对话台**（字幕 + 静音 / 挂断那块）：`videoPictureOn` 时
  **不再铺 `NeuBackground`**，字幕与两颗钮直接浮在画面上（伪视频那一路原来像一条白带压在视频上）。
  没有画面时（语音通话 / 形象坏了回落头像）**仍然铺底色** —— 大头像那一套的字幕与提示条靠它托住。
- 这是 v0.60 那条"有画面时页底不铺 NeuBackground"的**同一口径延伸到底部那一块**，
  AGENTS §零.7 已补一条，别改回无条件透明。

### 三、Live2D 动作幅度 +50%（用户点名"动作显示幅度大一点"）
- `ActionPlayer.AMPLITUDE_SPAN` **0.6f → 0.9f**：1.0 的轨道值现在等于模型该参数可用范围的 90%。
  只改这一个常数（内置动作全部按 1.0 写），不逐条改动作强度 —— 逐条改会让四态默认与用户
  自己存的动作观感不一致。仍留 0.1 余量不推到端点（参数顶到端点会拉坏网格，尤其半身模型的手臂）。
- 用户选的是「参数通道 +50%」那一档，**舞台通道（整体位移 / 旋转 / 缩放）没动**。

### 四、会话设置页（用户点名"取消角色空间和通话动作的小标题…音色和铃声显示现在的选择放在右边"）
- 「角色空间」「通话动作」两行**去掉 subtitle**，与「聊天背景」一样是一行高的单行入口；
  `customActionCount`（"已自定义 N 个状态"）这条查询整段删掉——四个槽播哪条进页面才看得见。
- `SettingsLineRow` 新增 **`trailingText`**：小标题字号（12sp / `MutedText`）挂在箭头左边，
  **不撑高行**（与 `subtitle` 互斥：那个在标题下面、会把行撑成两行）。
  宽度封顶 140dp + 省略号 —— Row 里不带封顶的单行文字会把标题挤没。
- 「音色」右侧 = 当前生效音色的显示名（候选名单里查不到就原样显示 ID，自定义端点手填的那种）；
  「铃声」右侧 = 当前会响的那条，口径与铃声弹层三档一致（角色专属 → 全局默认 → 系统默认）。
- 顺带修掉本页的**错峰序号重复**：② 组用到 index 4..7，③ 组却从 6 重新开始（两行共享同一延迟）。
  现在整页连续排到 13。

### 五、角色空间背景（用户点名"自定义不了，角色出来背景就没了…放两个选项就行"）
- **弹层只剩两行**：「系统预设」/「自定义背景」（`SpaceBackgroundSheet` 去掉 `busy` 参数、
  去掉那句"背景只属于角色空间，和聊天背景是两回事"和 24MB 说明小字）。
  原来六行（主题底色 + 4 档渐变 + 相册图）里，四档渐变是给用户添麻烦的。
- **真根因 = 预设背景画得看不见**：v0.64.0 那四档渐变是品牌色 `alpha = 0.32` 直接铺在白底上，
  肉眼等于没有，而角色是透出的 GL 层 → 读起来就是"角色一出来背景就没了"。
  现在改成拿**页面底色**与品牌色做 `lerp` 混合的实心渐变（浅色主题淡彩 / 深色主题暗彩），
  两档主题下都真的有颜色。
- **老数据零迁移**：`Live2dSpaceStore.SpaceConfig.isCustom` = 只有 `file:` 才算自定义，
  `theme` 与 `builtin:1..3` 一律按系统预设画（新增常量 `BACKGROUND_PRESET = "builtin:0"`，
  `BUILTIN_COUNT` 保留以维持旧下标的合法性校验）。
- 选完相册图**立刻关掉弹层**（背景在角色背后，弹层不关就看不见换没换）；失败仍由页面中间那颗
  `SpaceNote` 说清原因。
- **探针**：`SpaceBackgroundLayer` 里 `Log.e("Live2dSpace", "background config=… file=… decoded=…")`
  —— 下次再说"背景没变化"，一眼分得清是没存进去 / 文件不在 / 解码失败三种。
- 单测：`Live2dActionTest` 加 `v0650 只有带文件的背景算自定义，其余一律系统预设`。

### 六、助手手册（AGENTS §零.5）
- `AiPrompts.kt` 追加 **6 条 v0.65.0 定点 replace**（备忘本形态 ×3、通话台透明、背景两档、
  会话设置行 + 动作幅度），锚点全部取自链里更早那条 replace 的**产出**。
- `AiPromptsTest` 加 `v0650 备忘本入口卡与通话台透明与背景两档 进手册`（新措辞 contains +
  旧措辞 assertFalse 成对）；`v0620 备忘本 进手册` 里被本版换掉的三句旧断言**翻成 assertFalse 注释指路**，
  免得下次有人把它们当真要求加回来。

### 检验与出包
- `testDebugUnitTest`：450 项 / 7 红。**本批两类测试全绿**（`AiPromptsTest` 证明 6 条手册 replace 都命中、
  `Live2dActionTest` 证明背景两档的判定）。7 条红全在本版没碰的面上、且早于本版
  （其中 `UiRenderTest.render6Search` 还在点消息页那行「搜索」，而整条搜索链在 v0.56.8 就按用户点名删掉了）。
- `assembleRelease`：BUILD SUCCESSFUL，`aapt2` 核对 **153 / 0.65.0**；
  产物 `梦欢-v0.65.0-release.apk`（37MB），v0.64.0 已移入 `archive/`。

### ⚠️ 待真机验收
1. 角色空间：换成系统预设后**背景真的看得见**、换自定义图后关掉弹层就能看到图；
   出问题先读 `Live2dSpace background …` 那三个读数。
2. 视频通话：伪视频上底部那块透明之后字幕读不读得清（画面亮的地方会不会把灰字吃掉）。
3. 动作幅度 0.9：真实模型上摆手 / 劈叉有没有把网格拉坏（有的话只回退 `AMPLITUDE_SPAN` 这一个数）。
4. 备忘全屏页：卡片逐条错峰展开的手感；删除时 `animateItem` 与 `shrinkVertically` 有没有打架。
5. 会话设置：音色 / 铃声行右侧那串字在长名字（克隆音色 / 自定义文件名）下截断得是否体面。

## v0.64.0（versionCode 152）

**Live2D 动作系统上线：视频通话改分区布局、角色空间、四态动作可替换、动作库 / 自己做 / AI 生成。**

### 一、视频通话页重做（用户点名的第一件事）
- **上下分区**：上面是角色，下面是「对话台」（字幕 + 静音 / 挂断）。
  字幕与按钮**结构上不压角色** —— 角色由投影完整收进上方那块（引擎新增 `setViewportBottomInset`），
  所以**不再需要任何蒙版**，字幕用正常灰阶就清晰（延续 v0.61.1 删蒙层的决定）。
- **右滑进纯净态**：下方对话台**整块横移出场到右边**（跟手 + 弹簧归位），角色铺满全屏，
  **顶栏的状态与胶囊计时保留**（用户点名"顶部栏的 UI 还要"）；左滑唤回。
  语音通话（没有形象）不启用这个手势。
- 字幕从「一屏 6 行」收敛成**最多 3 行**；上滑仍可翻整通记录，右下角「回到底部」保留。
- 画面层**尺寸恒定**：分区只改投影矩阵、不改视图大小 —— 否则右滑那 320ms 里 GL 表面反复重设，
  观感是"滑动时画面抖"（与 v0.60 那轮的同类坑同源）。

### 二、Live2D 动作引擎（新模块 `com.menghuan.app.live2d`，与通话解耦）
- **通用通道层**：动作只认 `GENERIC_HEAD_X` 这类通道；加载模型时探测并映射到**该模型真有的**参数上
  （老式 `PARAM_ANGLE_X` 命名也认）。**缺参数的模型不会崩**：缺的轨道跳过、其余照常演，幅度自动收一点。
- **舞台通道**（整体位移 / 旋转 / 缩放）走**渲染矩阵、零参数依赖** ——
  所以「翻跟斗」在任何模型上都演得出来（这是调研 VTube Studio 那套参数值组合做法后的结论）。
- **动作播放器**：关键帧轨道 + 淡入淡出 + 循环 + 时长 / 强度 / 速度三层幅度；
  写在 `saveParameters` 之后、物理与口型之前，所以**口型永远由实时语音说了算**，动作抢不走。
- **模型自带动作与表情**也进动作库（从 `model3.json` 扫出来，按人设归属，换模型跟着换）。

### 三、内置动作 8 条
四态默认（待机 / 倾听 / 思考 / 说话，循环）＋ 趣味动作（摆手 / 摇头 / 劈叉 / 翻跟斗）。
全部是**程序化轨道**、不是 `.motion3.json` —— 后者里写的是某一张模型的参数 ID，换模型就整段失效。
内置动作刻意**不碰"张嘴"**：那归实时语音的口型，碰了就会出现"AI 没出声但角色一直张着嘴"。

### 四、通话动作 + 角色 Live2D 空间（会话设置里两个新入口）
- **「通话动作」**：四个状态各绑一个动作，可从动作库任意替换、随时恢复系统默认。
  **只做绑定、不做编辑**（用户第 2 条：通话页只负责调用动作）。
- **「角色空间」**：独立场景（不是通话页的一部分）—— 换背景（主题底色 / 4 张内置渐变 / 相册图）、
  自由拖动角色、点击互动（头 → 摇头、身 → 摆手、空白 → 随机趣味动作；
  模型自带 `HitArea` 优先，没画就按角色包围盒上中下三段判，两条都有所以不会点不动）、
  动作条预览、动作库（点=试播、心=收藏、长按=菜单）。

### 五、自己做动作 + AI 生成动作
- **「自己做」**：选基础动作 → 调强度 / 速度 / 时长 → 勾四个部位（头部 / 身体 / 手臂 / 整体位移）
  → 加表情 → 实时预览 → 存进「我的动作」。**全程不出现任何参数名**（用户第 4 条）。
  基于内置动作改的一律**另存为新动作**，内置那条永不动 —— 它是四态绑定的兜底。
- **「AI 生成」**：一句话 → LLM **结合当前模型的能力**产出动作配置 → 本地预览 → 确认才保存。
  **只有"生成"那一步联网**，动作存下来之后播放完全本地（用户 §十）。
  用户那句话原样存进备注，方便照着再改一版。

### 六、工程
- Room **v19**：新增 `live2d_actions` / `call_action_bindings` 两张全新表（不动存量列，升级零风险）；
  内置动作按 `builtinKey` 在启动时对齐，改幅度 / 改时长能生效，但**不会覆盖用户点的收藏**。
- 纯函数回归 `Live2dActionTest`：通道映射 / 轨道求值 / 编解码宽容性 / 能力探测与降级 /
  互动分区 / 动作拼装 / AI 输出解析（含围栏与畸形输入）。
- 助手手册补「角色与动作（v0.64.0）」整章，`AiPromptsTest` 逐小节双向断言。

### 七、一次事故与恢复（留个记录）
- `AiPrompts.kt` 里 v0.62.0 / v0.63.0 的四条 replace **只存在于工作区、尚未提交**，
  被一次 `git checkout -- AiPrompts.kt` 一起冲掉了。文案已从 `app/build` 下那份 release 版
  `AiPrompts.class` 的**常量池逐字捞回**（与出货版本一致），四条断言全绿。
  教训：**对未提交的文件不要用 `git checkout`**。
- 同时清掉了 `Live2dCharacter` 里被动作系统取代的死代码（`requestState` / `groupFor` 那一条链）。

### ⚠️ 待真机验收（这一版没跑过真机）
1. 视频通话：右滑收/左滑回的跟手感；角色在分区态与纯净态之间切换时是否平滑（不应看到尺寸跳变）。
2. 字幕：3 行窗口 + 上滑翻历史 + 「回到底部」在真实字长下是否够用。
3. 角色空间：拖动跟手；点头部 / 身体 / 空白三档是否判得准（尤其没有 HitArea 的免费模型）。
4. 摆手 / 摇头 / 劈叉 / 翻跟斗在**真实模型**上的观感 —— 手臂方向与幅度是否自然（各模型参数方向有差异）。
5. 四态动作绑定切换后，通话里状态变化时动作是否立刻跟着换。
6. AI 生成一条动作：解析成功率、失败时给的提示是否可读。

## v0.63.0（versionCode 151）

**备忘本收敛成"一句话 + 自动时间、全程不弹窗"；菜单改紧凑；流水能编辑了。**

- **时间自动、地点删掉**（`MemoEntry`，Room **v18**）：v0.62.0 做成了手填 `markedAt` / `place` 三栏表单，
  用户拍板要的是**自动标注**。自动能做到的只有"记下的那一刻"，所以行上的时间直接取 `createdAt`；
  **地点整个删掉**——自动定位要申请定位权限，而国内 ROM 没 GMS、系统 `Geocoder` 基本翻不出地名，
  要翻还得让用户另外填一个高德/腾讯 Key，为一个字段不值当（用户已确认删）。
  ⚠️ 删列走 **v17→v18 重建表**（建新表 → 拷数据 → drop → rename），
  不能用 `ALTER TABLE DROP COLUMN`：那要 SQLite 3.35+，Android 自带的在低版本 API 上没有，照写就是启动即崩。
- **备忘全程不弹窗**：删掉 `MemoEditSheet`（正文/时间/地点那个底部弹层）。
  添加是卡里那行内联输入框；**点某条备忘 = 那一行原地变成可编辑文字**，回车落库、✕ 放弃。
  内联编辑器 `MemoInlineEditor` 添加与编辑共用一个，规则是**空内容或一个字没改都算取消**（不写垃圾进库、
  也不弹"内容不能为空"）。全屏页右上角那颗 ＋ 也删了（页顶就是输入框）；
  唯一还留着的弹层是「发给 AI」的**选人名单**——那是名单不是表单，没处内联。
- **数据页卡里只摊一条**（`visibleCount = 1`）：排序是置顶优先、新建其次，所以这一条
  **有置顶就是置顶那条**、没置顶才是最新那条；看全部走「全部」进全屏页。
- **菜单改紧凑**（新增 `ui/common/NeuMenu.kt`，用户点名"菜单太大、和字体大小不匹配"）：
  ⚠️ M3 的 `DropdownMenuItem` **最小行高写死 48dp**，`Modifier` 压不动——只能自己画 `Box + Text`。
  现在是 13sp / 上下 8dp（整条约 33dp）、12dp 圆角 + 一圈发丝线，和正文 14.5sp 的密度对得上。
  本版换的是**任务页**与**备忘本**这两处（用户点名的两处）；
  会话列表 / 洞察 / 周报那几处仍是 15sp + 48dp 的 M3 默认尺寸，**要不要一起换得用户点头**。
- **记账本流水长按可编辑**（`LedgerScreen` + `LedgerComponents` + `LedgerRoute` + `editLedgerEntry`）：
  长按菜单从只有一项「删除这笔」变成「编辑这笔 / 删除这笔」。
  编辑复用记一笔那张表单（灌回原值、标题换"编辑这笔"），**只改收支方向/金额/备注**——
  发生时间 `occurredAt` 与录入来源 `source` 不动（写错的通常是数字，来源是留痕）。
  顺手把这条菜单也换成上面的紧凑尺寸。

### 手册同步（AGENTS §零.5）
`DEFAULT_ASSISTANT_PROMPT` 里 v0.62.0 那段备忘本措辞**就地改写**成 v0.63.0 的形状
（自动时间 / 没有地点 / 不弹窗 / 只显示一条 / 原地编辑），并补上记账本流水长按编辑一句。
`AiPromptsTest` 的 `v0620 备忘本 进手册` 同步换成新措辞断言，并把**旧形状整段列为 assertFalse**
（「可标的时间 + 可标的地点」「点某条 = 打开编辑」「最近 3 条」「标了时间不会到点提醒」）——
`replace` 的子串对不上时是静默失效的，只有旧措辞消失才证明换上了。

## v0.62.0（versionCode 150）

**数据页新增「备忘本」：记账本下面一面卡内直接能写的便签墙。**

- **形态**（用户拍板：内联便签墙 + 全屏页）：数据页记账本入口卡下面一张 `MemoWallCard`，
  卡里那行「记点什么…」点下去**就地变输入框**、回车即存；只显示最近 3 条，
  右上角「全部」进全屏页（路由 `memo`，`ui/memo/MemoScreen.kt`）。
- **一条备忘 = 正文 + 可标的时间 + 可标的地点 + 可置顶**（`MemoEntry`，Room **v17** 新表 `memos`）。
  时间与地点都是**展示字段**：`markedAt` 不接 `AlarmScheduler`（标了时间不该被打扰，要提醒请建任务），
  `place` 是自由文字、**不做定位**（不为一个字符串申请定位权限）。
- **AI 只在手动发时读到**：长按菜单「发给 AI」→ 选人设 → 把这条连时间地点拼成一句话
  写进 `ChatDrafts` 并跳进那个会话，用户核对后自己发。
  **刻意不做**"备忘常驻注入 system prompt"——那会和当年档案注入一样让 AI 满嘴备忘。
- **招牌动效**（`MemoComponents.kt` 的 `MemoRow`）：新增从上方轻落 + `expandVertically` 撑开高度，
  删除先 `slideOutHorizontally` 向左滑走、再 `shrinkVertically` 收掉高度。
  高度是**真布局变化**，下面的条目每帧跟着平移，所以不需要 LazyColumn 的补位动画也不会瞬移；
  代价是删除必须走两段式（`delay(250)` 播完动画才真的落库删）。
  页面上不写"还没有备忘，记一条吧"那种客套话（v0.42.1 全局规矩）。
- **数据页键盘避让**：滚动 Column 加 `.imePadding()`，卡内打字时整页能被顶起来。
- **手册同步**（AGENTS §零.5）：`DEFAULT_ASSISTANT_PROMPT` 数据页那一章追加备忘本一段
  （含"标了时间不提醒""AI 平时读不到备忘"两条口径），回归断言在 `AiPromptsTest`。
- 全量清除数据（我的 → 数据与隐私）一并清 `memos`。
- **任务页长按改成菜单式**（`ui/tasks/TasksScreen.kt`，用户点名"像消息页那样的菜单，有编辑和删除"）：
  原来长按任务行**直接进编辑弹层**，删除还藏在弹层里、点了再弹一层 `AlertDialog` 确认——
  和全应用"长按一律只弹菜单、点菜单里的删除才真删"的口径不一致。
  现在长按 = **震一下 + 弹出菜单**（`DropdownMenu` 包在贴行右下角的 1dp Box 里锚定，
  与会话列表 / 洞察 / 周报 / 记账本流水同形状），菜单两项：**编辑**、**删除**（点即删，不再叠确认框）。
  弹层里那颗「删除任务」同步去掉确认框，两条入口一个口径。
  顺带说明：`Modifier.animateItem(placementSpec = TaskPlacementSpring)` 是本项目在用的 API，
  勾选/改日期后的跨板块重排弹簧照旧。
- **AI 消息通知有提示音 + 勿扰时只震动**（`notify/NotifyAlert.kt` 新增，用户点名）：
  三路通知里「人设主动关心」与「定时主动消息」原来**没显式设过渠道声音**，现在统一走
  `NotifyAlert.channel(...)` —— 正常态 = 系统默认通知音 + 两短一长震动；
  **系统勿扰时自动换到 `<id>_quiet` 那条只震动、不响铃**（读 `currentInterruptionFilter`，不需权限）。
  ⚠️ 两个必须知道的取舍：① **渠道声音创建即锁死**，`createNotificationChannel` 改不动已存在渠道的
  声音/震动，所以这三条渠道全部**换代成新 id**（`proactive_greeting_v2` / `scheduled_message_v2` /
  `ai_call_v2`）并删掉旧 id —— 不换 id 老装机根本补不上提示音；
  ② 勿扰下**允不允许震动是 ROM 说的算**，要保证震得动得让用户开「勿扰访问权限」，
  那个开关太深、普通用户找不到，与用户确认后**没做**。
  另：普通聊天里 AI 的回复**本来就不发系统通知**（现状如此，本版没改），只在会话列表亮红点。
- **AI 来电穿勿扰**（`data/CallRingtone.kt` + `ui/call/IncomingCallScreen.kt`，用户点名）：
  铃声从 `USAGE_NOTIFICATION_RINGTONE` 改放 **`USAGE_ALARM`（闹钟音轨）** —— 闹钟 App 穿勿扰的
  标准做法、零权限。三条链路一起改：角色专属铃声 / 设置里的默认铃声 / 系统默认铃声。
  ⚠️ 系统默认铃声原来走 `Ringtone.play()`，而 `Ringtone.setAudioAttributes` 要 API 28（本工程 minSdk 26），
  所以那一档也改成 `MediaPlayer` + `content://` Uri，免得 26/27 上悄悄不响。
  代价：来电铃声吃的是**闹钟音量**那条滑杆，拉到 0 就真的听不见。
  只有 **AI 主动打来的那通**传 `ringThroughDnd = true`；用户打给 AI 的回铃、铃声页「试听」照旧走铃声音量。
- **未读点黑 → 红**（`ui/chat/ChatListScreen.kt` 会话行那颗 + `MainActivity.kt` 底栏「消息」那颗）：
  原来是 `NeuAccent`（浅色下=纯黑，看着像选中/已读的记号），换成品牌点缀色 **`BrandSalmon #FF7262`**
  （含那圈淡光晕）。**没有用 `NeuDanger`** —— 那个色在全 App 是"删除/危险"语义，当未读会让人以为出了事。

### 手册同步（AGENTS §零.5）
`DEFAULT_ASSISTANT_PROMPT` 追加四条 replace：备忘本 / 任务长按菜单 / 未读红点与消息提示音 /
AI 来电穿勿扰。其中**「手机开免打扰 / 静音 / 振动时铃声一律不响」这句是 v0.53.9 写下的，
本版对 AI 来电已不成立**，属于会给出错答案的滞后措辞，已改写并把
`AiPromptsTest` 里那条 `assertTrue` 翻成 `assertFalse`。

## v0.60.1（versionCode 144）

**Live2D 通话形象终于能画出来了（根因在 GL 生命周期，不在素材包）+ 会话设置那一行不再显示「未设置」。**

- **① Live2D 画面空白 = 渲染缓冲被建成 0×0**（`realtime/visual/Live2dVisualEngine.kt`）：
  `GLSurfaceView` 保证 `onSurfaceCreated` **先于** `onSurfaceChanged`，而窗口尺寸只在后者才拿得到。
  原来在 `onSurfaceCreated` 里就 `loadAssets(viewWidth, viewHeight)` —— 那两个数此刻还是 **0**。
  `CubismRendererAndroid.create(width, height)` 是拿它们去建**离屏渲染目标**的
  （`model.isBlendModeEnabled()` 为真时建两块 `CubismRenderTargetAndroid`），
  0×0 的缓冲**不会报错**，只会让每一帧都画进一个零尺寸的目标里；
  之后 `onSurfaceChanged` 因为 `c.ready == true` 直接跳过，尺寸再也没人补上。
  ⇒ 表现就是真机看到的样子：模型解压成功、校验通过、`runtime` 是 `Ready`、画面层挂上去了
  （名字/时长因此走了白色系那套），**屏幕上却只有一层灰白渐变托底**。
  官方 Sample 躲过这一刀靠的是 `onSurfaceChanged → setRenderTargetSize(width, height)`
  （`LAppMinimumDelegate` + `LAppMinimumLive2DManager`），本工程把渲染器建出来了却没调过这一步。
  现在：**首帧加载挪到 `onSurfaceChanged`（拿到真实尺寸才建渲染器）**，
  视图重新量过时（旋转/分屏/回前台）补 `setRenderTargetSize`，
  `onSurfaceCreated` 只负责 EGL 上下文重建后的 `reloadRenderer`。
- **② 加了 publish 日志**：`live2d publish size=WxH ready=… fail=…`。
  形象不出画面时，"没加载 / 加载失败 / 加载成功但画不出来"三种情况在屏幕上一模一样，
  只有这一行能分开它们（本项目最难查的就是静默降级）。
- **③ 会话设置「视频通话形象」不再显示「未设置」**（`ui/chat/ChatSettingsScreen.kt`）：
  副标题读的是 `call_visuals/<id>/metadata.json` 里的 `type`，而 `LaunchedEffect` 的 key 是
  `showVisualTypeSheet` 且**只在它为 true 时才读** —— 于是刚进设置页那一行永远是初始值 NONE，
  传完模型包退出来一看还是「未设置」（其实早就存好了，通话页也在用）。
  现在进页面（`personaId` 变化）就读一次，三层弹层任一开/关都再读一次，本地小文件、零成本。
- ⚠️ 用户那个 `免费模型—奶蛙.zip` 顺带确认过：条目名带 UTF-8 标志（GBK 兜底那条路没被触发）、
  `model3.json` 的 Moc/Textures/Physics 引用全部落在包内，**导入校验本来就是过的** ——
  这个包没问题，之前几轮"显示不了"和素材包无关。
  它确实没有 `Motions`、`Groups` 里 `EyeBlink: []`、连 `LipSync` 段都没有，
  所以角色**不会做动作**（待机姿势 + 呼吸 + 眨眼 + 对口型），这是素材本身的形态，不是 bug。
- **④ 视频通话顶栏改名换形**（`ui/realtime/RealtimeCallScreen.kt`，用户点名）：
  有画面时不再显示那行大名字 —— 换成 **42dp 圆头像（`personaBrush` 渐变环）+ 状态胶囊挂在头像右边**，
  **时间在下面一行**，整组居中贴顶（沿用页面既有的居中节奏与暗色托底）。
  只在 `videoVisualOn` 这一支生效：**没有形象时不挂这块顶栏**（否则同屏两颗头像），
  改走下面 ⑤ 那套居中大头像。
  头像上刻意**不加** `breath` 那层 `graphicsLayer`（理由同 v0.60：压在曲面上的控件不做 post-layout 变换）。
- **⑤ 语音通话的主视觉头像按参考图放大、改名不显示**（`ui/realtime/RealtimeCallScreen.kt`，用户点名）：
  照用户给的参考图量出来的比例落进代码 —— **头像直径 = 屏宽 59%，中心落在屏高约 38.7%**。
  外层 `Box` 换成 `BoxWithConstraints`，边长与顶部留白都由 `maxWidth`/`maxHeight` 算出来
  （AGENTS 布局规矩：等比例元素走比例、不写死 dp，换机型才不会走形）；
  脉冲环的画布按 `CallAvatarCanvasRatio`（196:124）跟着等比放大，
  环仍画在恒定比例的画布内（v0.56.5 那条"两态头像位置必须一样"的规矩没动）。
  **那行 20sp 的名字删掉** —— 大圆头像 + 渐变环就是身份。
  状态胶囊、时间、字幕、静音/挂断、缺配置提示条**一个字都没改**（用户明确"其他都不用改"）。
  ⚠️ 视频通话**有画面**那一支走的是 ④ 的顶栏，不受这条影响；
  视频通话**没有形象**回落到头像时，与语音通话共用这一套大头像。
- **⑥ 编辑人设里的「角色立绘」改成一个同款输入框、挪到「身份」下面**（用户点名）：
  原来是一行 15sp 的标题 + 一颗「上传立绘」按钮，字号和其它字段对不上。
  现在：**给 `NeuTextField` 加一个 `onClick` 参数**（非空 = 只读字段，整块凹陷面换成一次点击，
  不弹键盘不聚焦），立绘就用 `label = "角色立绘"` 的输入框本身 ——
  **label 字号与排版天然和「名字/身份」同一份**，不另画一套"看起来像输入框"的东西。
  框里显示**已选图片的文件名**（没传时是"未上传，点这里选择图片"），**点框就是选图**，
  「上传立绘」按钮删掉；已上传时下面留一颗「删除立绘」（和头像那行的「恢复默认头像」同一形状，
  上传入口收进框里之后它是唯一的清除口）。
  ⚠️ 顺带修了文件名：原来复制进 `portraits/` 时统一改名 `art_<时间戳>.jpg`，
  框里会显示一个用户从没见过的名字 —— 现在**沿用原图文件名**
  （`safDisplayName` 查 `OpenableColumns.DISPLAY_NAME` + `portraitTargetFile` 收非法字符、重名加序号）。
- **⑦ 内置「应用助手」手册同步 ⑥**（AGENTS §零.5，不许滞后）：立绘那条从"上传一张…"改写成
  「位置在**「身份」下面那一栏**、**点输入框**选图、框里显示**文件名**、原来的「上传立绘」按钮已删除」，
  并按规矩在 `AiPromptsTest` 补了一对双向断言（新措辞 contains + 旧措辞 assertFalse，
  防 `.replace()` 子串对不上时静默落空）。
- **⑧ 头像那颗钮：改名 + 常驻 + 细线胶囊**（用户点名）：
  「恢复默认头像」→**「恢复默认」**，而且**默认头像时也在**、写**「点击编辑」**（点它 = 选图，
  和点头像框本身同一个动作）。样式从 `NeuTextButton`（40dp 高的裸文字、无容器）换成
  本文件内的 `AvatarPill`：**12sp / Normal** 的字 + `neuSurface(999.dp)` 一圈发丝线 + 胶囊圆角，
  **离头像从 8dp 收到 4dp**（原来那 40dp 的裸文字命中框让间距看着有二十几 dp）。
  仍是全应用统一的"无涟漪 + 轻微缩放"按压（`neuRowPress`）。
  「恢复默认」保持危险色（它会清掉用户选的图），「点击编辑」走次级灰。
- **⑨ 手册再同步一处**：「换过图旁边会出现「恢复默认头像」」这句已经错了，改写成
  「头像下面**恒定有一颗小胶囊**：默认时「点击编辑」、换过图后「恢复默认」」，
  双向断言一并补进 `AiPromptsTest`。
- ✅ ①~⑨ 已 `assembleRelease` 出包：`梦欢-v0.60.1-release.apk`（versionCode 144，37,925,492 字节），
  与 v0.60 同一张 debug 证书（SHA-256 一致）可直接覆盖安装；老的 v0.60 包按流程移进 `archive/`。
  构建日志 `logs/build.log`。**单元测试没跑**（用户只说打包）。


## v0.61.1（versionCode 149）

**加载期间保持居中大头像那一套（角色真画出来才切视频布局）+ 三层暗色蒙层全部删掉、按键各自悬浮。**

- 用户拿三张真机截图点出来两件事：① v0.61.0 那句"把加载提前"没解决观感 ——
  **接通后仍然先看到几秒"空画面 + 顶栏"、角色才蹦出来**，和修复前没区别；
  ② 通话页不要那一层暗色蒙层，两颗钮要"各自悬浮"。
- **① "挂上视图"与"切视频布局"拆成两个状态**：`VisualRuntime.Ready` 新增 `drawn`。
  视图必须尽早挂上（不挂就没有 surface、GL 线程不启动、模型永远加载不完 —— v0.58.0 那个死锁），
  但布局不能跟着切。现在：
  · `Live2dVisualEngine` 在**第一帧真的画完之后**才发 `Ready(drawn=true)`；
  · `VideoVisualEngine` 在 Media3 的 `onRenderedFirstFrame()` 才置位（并补了 `publishReady()`，
    宽高比变化不再把 drawn 冲掉）；
  · `RealtimeCallScreen` 用 `videoVisualOn`（挂画面层）与 `videoPictureOn`（切顶栏）**两个条件**，
    加载那几秒页面就是居中大头像那一套，角色出来时带 260ms 淡入、不会"啪"地出现。
- **② 三层蒙层删除**：顶部 220dp 的黑色渐变、字幕区那层"透明→黑 0.55"、按钮行那条"透明→黑 0.45"。
  两颗钮本来就有自己的圆底（白面 / 危险色），现在就是各自悬浮。
- **③ 顺带清掉三处只为 SurfaceView 存在的历史包袱**（Live2D 已换 TextureView 承载）：
  · 页底恢复**恒定铺 `NeuBackground`**（"有画面就透明"是给曲面挖洞用的，TextureView 不需要，
    角色透明处本来就该透出主题底色）；
  · 字幕/时长的白色系分支与 `CallSubtitlePanel(onVideo=…)` 参数**整个删掉**
    （蒙层没了，白字压浅色角色反而看不见 —— 回到 `TextPrimary/TextSecondary/MutedText` 梯度）；
  · 三处 `staggerEnter` 进场动画**恢复无条件生效**（当初禁用也是 SurfaceView 的合成限制）。
- ⚠️ 代价（已当面告知用户）：没有蒙层之后字幕与顶栏直接压在角色/背景上，深色角色配深灰字可能读着累；
  需要的话下一步单独给文字加描边或半透明底板，而不是再铺一整条蒙层。
- **铃声那条不是本次改动造成的**：`git diff` 确认本次只碰过顶栏的 `Crossfade`，
  铃声那段（`CallRingtone.loopPlayer` / `LaunchedEffect(ui.state)`）一个字没动；
  用户截图状态栏有 🌙 **勿扰模式**，而铃声走 `USAGE_NOTIFICATION_RINGTONE` 音量流，勿扰会压成静音。
  待用户关勿扰复测；若仍无声再查代码。
- ✅ 已出包：`梦欢-v0.61.1-release.apk`（37,952,379 字节），同一张 debug 证书 → 直接覆盖安装；
  v0.61.0 已移进 `archive/`。离线核对：`versionCode 149`、**36 个着色器文件仍在**、
  `assets/asr/silero_vad.onnx` 仍在、签名一致。
- 真机要验的：进视频通话先看到居中大头像（不再有空画面顶栏）→ 角色淡入 → 顶栏才出现；
  挂断键/字幕不再被暗色蒙层压着、各自悬浮；切后台再回来画面还在、挂断不闪退；语音通话不受影响。


## v0.61.0（versionCode 148）

**Live2D 承载层从 GLSurfaceView 换成 TextureView（UI 不再被角色挡住）+ 形象加载提前到"正在呼叫"那几秒。**

- v0.60.4 角色终于出来了，但带出两个问题（用户回报）：**角色盖住挂断键与字幕**、
  **接通后先几秒浅灰渐变、角色才蹦出来**。
- **① 承载层重写**：`SurfaceView` 只有两种摆法且都不通 —— 排在窗口下面要在窗口上挖洞，
  而 Compose 的 `AndroidView` 里整棵 Compose 绘制树比子 View 先画，洞会把顶栏/字幕/挂断一起擦掉；
  `setZOrderOnTop` 排到窗口上面，角色又盖住它覆盖范围内的 UI。
  新增 **`realtime/visual/live2d/GlTextureView.kt`**：`TextureView` + 自建 EGL 上下文 + 自建渲染线程，
  走普通 View 绘制 —— 透明处透出页面、**UI 永远画在它上面**。
  带 8 位 alpha 的 EGLConfig 在这里显式申请（TextureView 没有 `setEGLConfigChooser` 那个入口）。
  `Live2dVisualEngine` 里的 `setZOrderOnTop` / `setEGLConfigChooser` / `PixelFormat.TRANSLUCENT` 全部删掉；
  `release()` 改成**先 quit+join 停干净 GL 线程，再拆模型**（GLSurfaceView 免费给的串行保证现在要自己做，
  否则可能有一帧正在用已 delete 的 moc/纹理）。
- **② 加载提前**：`RealtimeCallViewModel.init` 里就 `prepareVisual()`，
  把"AI 接听延迟"那几秒铃声用来读 model3.json / 解码 2048² 贴图 / 传纹理。
  原来 `attachVisual` 坐在 `start()` 里，而 `start()` 被接听延迟挡着 → 铃声期间什么都没准备，
  接通之后才开始加载，那几秒空画面就是这么来的。
  `start()` 里改成 `visualJob.join()` 之后再 `bindEngineToVisual()`：
  视觉层现在比引擎早就绪，三条订阅拆成"准备"与"接线"两步，不 join 会撞上"引擎先就绪、visual 还是 null"。
- ⚠️ 过程中第一次构建失败：`visualJob` 声明写在了 `init` 块之后，Kotlin 报
  `Variable cannot be initialized before declaration`，已挪到 `init` 前面。
- ✅ 已出包：`梦欢-v0.61.0-release.apk`（37,952,379 字节，与上一版同大小），同一张 debug 证书
  （SHA-256 一致）→ 直接覆盖安装；v0.60.4 已移进 `archive/`。
  离线核对：`versionCode 148`、**36 个着色器文件仍在包里**、`assets/asr/silero_vad.onnx` 仍在、
  dex 里 `Live2dGlThread` 在、`setZOrderOnTop` 与 `setZOrderMediaOverlay` 都已消失。
- 真机要验的：① 角色出来了**且挂断键/字幕在角色之上可点可看**（这次改动的正题）；
  ② 接通时不再有几秒空画面；③ 切后台再回来画面还在、挂断不闪退（GL 线程生命周期是自己写的，
  这块必须回归）；④ 语音通话与伪视频两条路不受影响。


## v0.60.4（versionCode 147）

**Live2D "加载成功却什么都不画" 的真根因：框架的 GLSL 着色器根本没进包，而且没给文件加载器。**

- 破案钥匙是 v0.60.3 埋的那枚探针。真机 adb 抓到（通话页、小柚铃）：
  ```
  E/Live2dVisualEngine: live2d publish size=1200x2488 ready=true fail=null
  E/Live2dVisualEngine: live2d probe corner rgba=0,0,0,0
  ```
  **缓冲是全透明的** → alpha、z-order、EGL 配置三条全部无罪；
  紧接着在同一份日志里挖到 Cubism 框架自己喊了 14 遍：
  ```
  E/Live2D: [CSM][E]File loader is not set.
  ```
- **根因**：Cubism Java 5-r.5 的 GLSL 源码**不是内嵌在 Java 里的**，
  `CubismShaderAndroid.loadShaderProgramFromFile()` 运行时按
  `com/live2d/sdk/cubism/framework/shaders/standardES/*.vert|.frag`
  调 `ICubismLoadFileFunction` 读文件；**读不到只 `cubismLogError` 然后 `return 0`，不抛异常**
  → 所有 shader program 句柄都是 0 → `drawModel()` 一个像素都画不出来。
  而 moc3 / 贴图 / 物理全是我们自己 `File.readBytes()` 读的，一路正常，
  所以 `ready=true`、`fail=null`、零报错 —— 这就是"三版都查不出来"的原因。
- **两处缺失一起补**：
  1. `app/build.gradle.kts` 的 `sourceSets.main` 补
     `assets.srcDir(rootProject.file("cubism/framework/src/main/assets"))`。
     官方 Framework 是 library 模块、着色器在它的 `src/main/assets` 里会自动进 AAR；
     v0.58 为了绕开"library 不能带本地 .aar 出 AAR"改成只挂 `java.srcDirs` 搬源码，
     **assets 就整个漏掉了**（包内着色器文件数：0 → **36**）。
     ⚠️ 用的是**单数 `srcDir`（追加）**：`srcDirs` 语义是替换，会把模块自己的
     `app/src/main/assets/asr/silero_vad.onnx`（离线识别）挤掉、语音当场废。
     出包后已核对：36 个 `.frag/.vert` 在、`silero_vad.onnx` 1,807,522 字节也还在。
  2. `Live2dBootstrap` 补 `cubismOption.loadFileFunction = AssetLoadFileFunction(assets)`。
     官方 Sample 的 `LAppMinimumDelegate` 私有构造里本来就有这句
     （`cubismOption.loadFileFunction = new LAppMinimumPal.LoadFileFunction()`），**照抄时漏了一行**。
- **把这类静默失败变成当场判死**：首帧探针先查 `Live2dBootstrap.shaderLoadFailure`
  （`AssetLoadFileFunction` 记录第一个读不到的路径），非空就立刻
  `Degraded("Live2D 着色器资源没打进包，本次通话只用头像")` —— 头像回落 + 页面写明原因，
  不再让人对着一片白从 z-order 猜起。
- ⚠️ **纠正前两版的归因**：v0.60.1 说的"0×0 渲染缓冲是真根因"、v0.60.2~3 说的
  "z-order / EGL 没 alpha"，**前两条是真 bug、但都不是"看不见"的那一个**，
  第三条（EGL alpha）本来就不成立。AGENTS §七.1 已按事实重写，别再拿旧结论当依据。
- ✅ 已出包：`梦欢-v0.60.4-release.apk`（37,952,379 字节，比上一版 +26KB = 那 36 个着色器文本），
  同一张 debug 证书（SHA-256 一致）→ 直接覆盖安装；v0.60.3 已移进 `archive/`。
- 真机要验的：视频通话里角色**应该真的出现了**；同时确认 `live2d probe corner rgba=` 不再是 `0,0,0,0`
  而是角色覆盖处有色值。⚠️ 角色本体会盖住它覆盖范围内的 UI（`setZOrderOnTop` 的代价），
  如果挂断/字幕被挡住，下一步就是换 TextureView 承载。


## v0.60.3（versionCode 146）

**Live2D 白屏诊断包：强制带 alpha 的 EGLConfig + 首帧回读一个像素。**

- 用户装 v0.60.2 后回报：**没有角色、整屏纯白、而且把挂断键挡住了**。
  这一条反而把 z-order 那刀**证实**了 —— 曲面确实渲染了、也确实提到窗口之上了，
  只是它的内容是**不透明**的（透明处本该露出下面的 UI，结果全被盖住）。
- **假设**：`holder.setFormat(PixelFormat.TRANSLUCENT)` 改的是**窗口**的像素格式，
  **不影响 `GLSurfaceView` 自己选的 EGLConfig**；默认 chooser 很可能给到 8/8/8/**0**（无 alpha），
  于是 `glClearColor(0,0,0,0)` 里那个 a=0 根本没地方存 → 整块表面不透明。
  现在显式 `setEGLConfigChooser(8, 8, 8, 8, 16, 0)`。
- **探针**：首帧 `glReadPixels` 回读**左上角那一个像素**（必定在角色以外 = 应为 `0,0,0,0`），
  打 `live2d probe corner rgba=r,g,b,a`。一次读数分开两种平时长得一样的病：
  `a=0` = 透明通了（那白屏另有原因，下一刀往"模型没画进最终缓冲"查）；
  `a=255` = 帧缓冲压根没 alpha（这一刀方向对但还不够）。只读一次，不进每帧（会强制等 GPU）。
- ⚠️ **本版刻意只改一个变量**：`setZOrderOnTop(true)` **保留没动**。
  因为 Compose 的 `AndroidView` 里整棵 Compose 绘制树比子 View 先画，
  **洞一旦真生效会把同屏 UI 一起擦掉** —— 默认 z-order 在 Compose 里是死路，
  改回去只会退回"什么都不显示"，还会把 alpha 的结果读不出来。
  代价是这一版角色仍可能盖住它覆盖范围内的 UI；真要"UI 永远在角色之上"，
  得换 **TextureView** 承载（和已经能正常显示的伪视频那条同构），那是次不小的重写，动之前先问用户。
- ⚠️ 已出包（versionCode 146，37,925,496 字节，与已装机同一张 debug 证书，可直接覆盖安装），
  **等真机回传 `live2d probe corner rgba=…` 才能定下一刀**。


## v0.60.2（versionCode 145）

**头像那颗小胶囊改小并对齐头像框；被「删除会话」隐藏的会话终于有找回路；Live2D 看不见又挖出第二层坑（GL 曲面 z-order）。**

- **⑩ 头像胶囊改小并与头像框对齐**（用户点名"太大了、没对齐头像框"）：宽度**钉死成头像框的 56dp**
  （头像在整排里是 `CenterStart`、圆心正好在 28dp，同宽就自动左右对齐），
  高度 22dp、字号 12→10sp、去掉左右 13dp 内边距（原来是"内容宽 + 大内边距"，比头像宽一圈还贴着左边起排）。
- **⑪ 被「删除会话」隐藏的会话终于有找回路了**（用户点名"点了删除就找不到了"）：
  根因不是删错东西，而是**全应用没有第二条路能进那个会话** ——
  `clearConversation` 会 `setHidden(true)`，列表按 `hidden && 无消息` 过滤掉它；
  而「我的 → 人设管理」那张卡**只有"点开 = 编辑"**，不进聊天。
  `ChatViewModel.init` 里其实早就有"打开即 `setHidden(false)`"，**只是没人能打开它**。
  现在人设管理每张卡右端那颗**从装饰箭头变成入口**：`MIcons.ChatBubble`、40dp 命中区、21dp 图标、
  `TextSecondary` 色（原来是 18dp 宽的 `FaintText` 装饰箭头，看不见也不能点），
  点它 = `navigate("chat/{id}")`，进去一次会话就回到消息列表。
  沿用全应用"点卡片本体是一件事、点右侧图标是另一件事"的既有口径（见 v0.53.0 的 API 配置卡）；
  **卡片本体仍是编辑**，长按删除不变。
- **⑫ 手册再补两处**（AGENTS §零.5）：底稿那句「删除会话（…发新消息即恢复）」在实践里等于回不来，
  改写成"想再聊回来：我的 → 人设管理 → 点那张卡右边的聊天气泡图标"，
  「我的」那条也补上"每张卡右边那颗气泡图标 = 直接进 TA 的会话"；
  双向断言一并进 `AiPromptsTest`（新句在 + 旧句「人设保留，发新消息即恢复」必须不在）。
- **⑬ Live2D 还是看不见 → adb 上真机取证，定位到第二层坑：`GLSurfaceView` 的 z-order**
  （用户点名"操控手机验证"，`setZOrderMediaOverlay(true)` → `setZOrderOnTop(true)`）：
  ① 那条修完日志确实变了（`live2d publish size=1200x2488 ready=true fail=null`、零 GL 报错、没崩溃），
  但用户回报**屏幕仍然是浅灰渐变** —— 和修复前一模一样，说明 0×0 **不是（或不只是）**看不见的原因。
  从抓到的 1.5MB logcat 里挖出 SurfaceFlinger 自己的合成顺序：
  **GL 曲面在最底层、App 窗口压在它上面**。SurfaceView 排在窗口下面时靠"在窗口上挖洞"露出来，
  而 Compose 的 `AndroidView` 里**整棵 Compose 绘制树比子 View 先画**，那个洞会把顶栏/字幕/挂断一起擦掉
  —— 也就是说"挖洞"这条路在 Compose 里根本不成立，我们看到的浅灰全是窗口自己画的。
  改成 `setZOrderOnTop(true)`：曲面提到窗口之上，**不需要洞**，靠它自己的 alpha 与下面的窗口相混
  —— 角色压在页面上，透明处照常露出 UI。（官方 Live2D Sample 不涉及这一层：它那块曲面是不透明整屏、
  上面没有 UI，所以**不能拿它当这一行的依据**；media-overlay 那行本来就是没有出处的自我发挥。）
- ✅ ⑩⑪⑫⑬ 已 `assembleRelease` 出包：**`梦欢-v0.60.2-release.apk`**（versionCode 145，37,925,496 字节），
  与已装机的 144 同一张 debug 证书（SHA-256 一致）→ 直接覆盖安装；v0.60.1 包按流程移进 `archive/`。
  离线能做的验证都做了：解 dex 查到 `setZOrderOnTop` **在**、`setZOrderMediaOverlay` **已消失**。
  **⑬ 仍未在屏幕上确认过**：手机这一轮反复掉线，`dumpsys SurfaceFlinger` 没抓到，装好后要现场看一次才算数。
- 真机要验的：视频通话里 Live2D 角色出现在全屏、会眨眼会对口型，顶上是头像不是名字、状态在右、时间在下，
  且**角色不会把挂断/字幕盖住**（z-order 提到窗口之上的代价要现场确认）；
  语音通话的大头像位置与大小对得上参考图、不再显示名字，其余（状态胶囊/字幕/静音挂断）不变形不溢出；
  会话设置那一行显示「Live2D 模型包」；编辑人设里立绘在「身份」下面、框里是原图文件名、
  头像下面那颗小胶囊与头像左右对齐；删掉的会话能从人设管理点气泡图标找回来。


## v0.60（versionCode 143）

**视频通话修一批真机反馈：画面改成 9:16 竖屏全屏铺满（不再把通话控件挤出屏幕）；记录卡按类型显示「视频通话」；删除记录当场消失；形象弹层两张卡片加间距。**

- 用户一次提了五条（真机试 v0.59 的走查结果），一次做完不拆版本（AGENTS §六）：
- **① 视频通话页全屏铺满**（`RealtimeCallScreen` + `CallVisualSlot`，第 2、4 条）：
  以前画面是**按素材比例排的一张卡片**、夹在名字与字幕中间，还带 30dp 左右留白——
  竖屏 9:16 素材一进来，这一列比屏幕还高，字幕区被压成 0、静音/挂断被整个推到屏幕外
  （真机上就是"视频通话没法用"）。现在画面是**垫在最底层的背景层**（`fillMaxSize`），
  名字/状态/字幕/操作钮叠在它上面，控件不再和画面对抢高度。
  铺满口径 = **cover**：素材比例与容器一致就正好铺满；不一致时放大到刚好盖住、居中裁掉溢出的一边 ——
  留边违背"铺满"、直接拉伸会把画面压扁（"黑边/变形"两个都不要，这是唯一自洽的做法）。
  比例未知（Live2D 画布要等 GL 侧算）时整块交给引擎自己适配，硬套 9:16 反而把角色挤小。
  ⚠️ 有画面时页底不再铺 `NeuBackground`（改透明）：否则包里那层白会从画面边缘透出来描一道白边。
  可读性：上下各补一层暗色渐变托底，名字/时长/字幕在画面上一律走白色系（最新的纯白、越旧越淡），
  没有画面时仍是原来的主题墨色。
- **② 记录卡按通话类型显示**（`CallRecordCard` + `MIcons.Video`，第 3 条）：
  标题以前写死「语音通话」，视频通话的记录卡也显示语音（内容里其实早就写明「视频通话 · …」）。
  现在从内容前缀判类型 → 标题「视频通话」+ 摄像机图标（未接通仍是危险色 + 「未接通」，靠状态不靠颜色）；
  会话列表预览同步分图标：视频 `📹` / 语音 `📞`。**不新增字段、不动 Room**。
- **③ 删除当场消失**（`ChatViewModel.deleteMessage/deleteMessages`，第 5 条）：
  删除类操作改成**先本地摘掉、再落库**，不再等 Room 重发那一轮查询（用户点名"删了记录条要退出去重进才消失"）。
  落库失败会**把那条按时间戳插回原位**并报一条错误（异步失败必须能看到），
  不许出现"界面上没了、库里还在"的假删除。
- **④ 「视频通话形象」弹层两张卡片加间距**（第 1 条）：以前两张卡上下贴着、描边连成一片，
  看着像一张卡被劈成两半；现在按全应用弹层行的既有节奏空 8dp（同模型列表 / 定时消息弹层）。
- **⑤ 入口兜底：两边不一致以文件为准**（顺手修，属第 2 条的排查范围）：进视频通话的先决条件
  原来只看 `personas.videoVisualType` 这一列；v0.58.0 的编辑人设曾把该列重置成 NONE（v0.58.1 已修），
  素材文件却还在 —— 于是形象明明配好了、点「视频」仍被拦在"还没有通话形象"。
  现在列为 NONE 时**补读一次盘上的 `metadata.json`**，可用就放行（只在必要时读，热路径不摸磁盘）。
- 内置手册：无用户可见的功能变化（记录卡文案与配色调整不改变操作路径），本版不改手册、不加 replace
  （AGENTS §零.5 只要求"入口/功能增删"同步）。
- ⚠️ 本版**未编译、未打包、未跑测试**（改完只交源码，等用户发话）。
- 真机要验的：视频通话（伪视频/ Live2D 两条）画面铺满无白边、静音/挂断都在屏幕内、
  字幕在亮画面上看得清；视频通话记录卡显示「视频通话」；删记录当场消失；
  形象弹层两张卡片有间距；语音通话与聊天回归不变。


## v0.59（versionCode 142）

**长按消息可以朗读了（另有默认关的自动朗读）；语音识别加了在线接口、与离线手动切换；联网搜索加了「自定义」服务商。**

- 一批三件（AGENTS §六：一次需求 = 一个版本，不拆一串），三件都先问清路线再动手：
  用户选的是「**单条朗读 + 自动朗读开关**」「**通用 OpenAI 兼容端点 + 手动切换、不回退**」
  「**自定义 = URL + Key，通用请求形状 + 兜底解析**」。
- **① 朗读**（`ChatViewModel.readAloud`）：长按任意一条消息 → 「朗读」，正在念的那条再点一次 = 「停止朗读」。
  合成**复用语音条那条链**（`TtsClients` + `voiceForConversation` 三级回落音色），
  播放 = 临时文件 + `MediaPlayer`，`UiState.readingId` 让气泡知道自己正在被念。
  自动朗读开关**按角色各开各的**，位置在**该角色会话设置第一组最后那行**（与「联网搜索」并列，
  存法同 `id\t1|0` 表），**默认全关**：
  开着它这个角色的每条回复都要发一次合成请求，那是真花用户额度的，不默认打开、也不做成全局。
  AI 这条回复如果自己已经发了 `【语音条】`，自动朗读**跳过**（同一句不念两遍）。
  ⚠️ 复位不只靠 `onCompletion`——MP3 尾部 padding 时那个回调可能整场不来（语音条踩过），
  所以按 `probeDurationMs` 的真实时长挂兜底；打断一律 `readToken++`，
  让在飞的合成协程回来发现自己过期就放弃，不许覆盖新一轮状态；离开会话（`onCleared`）停掉并释放。
  ⚠️ 这与 v0.50.0 删掉的那套不是一个东西：**不复活** `ReadAloudPlayer` 与"右上角喇叭"，
  当年的错误形状（默认就念、没有停的入口）不再回来。AGENTS §零.4 已改成新口径。
- **② 在线语音识别**（`data/OnlineAsr.kt`）：通用 OpenAI 兼容 `POST {接口地址}/audio/transcriptions`，
  multipart 发 `file` + `model`；地址填到版本段（`https://xxx/v1`）或直接填完整接口都能用（`endpointOf` 兜两种）。
  配置三项存在 DataStore：`asr_online_url` / `asr_online_model` / `asr_online_key_enc|plain`
  （**Key 走 ApiKeyCipher 加密**，与其余 Key 同一套规矩）。
  入口 **设置 → 语音模型（识别）→「识别引擎」**：`离线（本机）` / `在线接口` 两颗 chip + 三个输入框 +
  「测试接口」（发一段 0.4 秒静音 WAV，只证明地址/Key/接口在，**话要说准**：它证明不了识别质量）。
  ⚠️ **手动切换、绝不做"在线失败就自动回落离线"**：当年云端就是坐在自动回退的第二级上，
  端点不支持 → 每次白等超时、"识别为空"哪一级出的事谁也说不清，三个版本后整个删掉。
  现在选了在线就只走在线，失败把 `OnlineAsr.lastFailure` 原样报出来
  （401 是没配好 Key、404 是这家没这个接口、超时是网络——处理动作完全不同，不许糊成"没听清"）；
  聊天页那两处松手提示改成带原因的长提示（`SpeechToText.lastFailure`）。
  ⚠️ **实时通话不受影响**：通话逐段喂 PCM、**始终走端侧**（在线要"一个文件一次请求"，每句多一次网络往返会拖死对话）。
  选了在线时聊天页的"没下载模型就不让按语音"那道拦截会自动放行（`SpeechToText.isOnlineEngine`）。
- **③ 联网搜索「自定义」服务商**（`SEARCH_CUSTOM`）：设置 → 模型配置 → 联网搜索 → 第三颗 chip。
  只填 **接口地址 + API Key**，请求体发 `query` / `max_results` / `count` 三个字段，
  响应端做**多路兜底解析**（结果数组依次找 `webPages`→`results`→`data`→`items`→`documents`，
  还兜了 `{data:{webPages:[…]}}` 这种套一层的；条目字段 title 试 `name`/`title`/`siteName`，
  url 试 `url`/`link`/`displayUrl`，摘要试 `snippet`/`summary`/`content`/`text`/`description`）。
  一家都没解析出来时**把返回开头 180 字贴回给用户**——那种情况多半是字段名不在清单里，
  不是"这家没结果"，不看原文根本排查不动。「测试搜索」走同一条链路并带上新地址。
  ⚠️ **自定义家有独立的一份 Key**（`search_custom_key_enc|plain`）：三家用同一个键的话，
  保存这一家会把博查填好的 Key 抹掉（v0.52.0 语音合成三家互相覆盖就是这么踩的）。
  读取一律走 `searchKeyFor(provider)` / `searchEndpointFor(provider)`，
  两个调用点（聊天页联网、定时主动消息联网）都改了；「模型配置」那张卡的副标题也跟着按当前生效那家取。
  ⚠️ 自定义这一家**不写任何官网地址**（`consoleUrlOf` 返回空串，设置页据此不显示"去 X 注册"那行）：
  用户的接口从哪来只有他自己知道，编一个看起来合理的域名是最坏的做法（AGENTS §零.6）。
- 内置手册同步（AGENTS §零.5）：链尾追加三条（朗读 / 识别引擎在线 / 自定义搜索），
  含"通话始终走端侧"这条最容易答错的话；`AiPromptsTest` 补 `v059 朗读 在线识别 自定义搜索 进手册`。
- AGENTS §零.4 两条红线改写：识别从"单级、云端不要复活"改成"**两条并列、手动切换、绝不自动回退**"；
  "朗读已删除不要复活"改成"以长按 + 默认关的开关回来，**但别复活右上角喇叭**"。
- ⚠️ 本版**未编译、未打包、未跑测试**（改完只交源码）。


## v0.58.1（versionCode 141）


**会话设置排成五组平铺；Live 2D 与伪视频合成一行「视频通话形象」；角色立绘回到编辑人设页，用来让 AI 画自己时长相一致。**

- 用户这一批提了四件事，一次做完不拆版本（AGENTS §六版本节奏）：
- **① 会话设置分五组平铺**（用户给的分组，逐条照搬）：
  ① 编辑人设 / 模型 / 联网搜索 ② 视频通话形象 / 聊天背景 ③ 音色 / 铃声 / 电话
  ④ 人设主动关心 / 定时消息 ⑤ 查看聊天记录。
  **不加小标题、不做折叠展开**（用户原话："不需要小标题，也不是折叠展开，就是平铺"），
  组与组之间是一条浅灰缝隙 `GroupSeam()`（8dp 空白 + 8dp `NeuTrack` 带 + 8dp 空白）；
  每组最后一行传 `divider = false`，否则发丝线断在灰缝前面会"悬着像没画完"。
- **② 定时消息的条目收进弹层**：以前一条条摊在页面上（`v0.54.6` 的老做法），现在页面只留一行
  「定时消息」，点条目改它、底部「＋ 添加定时消息」。**「定时电话」的条目仍在「电话」弹层里没动**
  （同一张表的另一种 kind，入口不同，等用户点头再挪）。
- **③ Live 2D + 伪视频 合成一行「视频通话形象」**（用户拍板）：点开先出类型弹层（Live2D 模型包 /
  伪视频素材），选中就"关掉自己、打开对应的上传弹层"——
  ⚠️ **刻意不叠两层 ModalBottomSheet**：v0.57 已经踩过，两层底部弹层会把标题/返回/遮罩搅成一锅。
  行的副标题读 `metadata.json` 的当前类型（每次展开重读本地小文件），刚传完就能看到，不会以为没生效。
- **④ 角色立绘**（新线，和通话形象是两回事）：入口在**编辑人设页**，
  `personas.artImagePath`（Room **v16**，默认空串），文件复制进 `filesDir/portraits/`（不持有 SAF URI，
  冷启动会失效），换图时删掉上一张不留垃圾。用途：**AI 生图画"自己"时当参考图**——
  `ImageGenerationClient.generate(prompt, dir, refImage)` 把立绘转成 `data:image/…;base64` 塞进
  `"image"` 字段发给 `/images/generations`。
  - ⚠️ **不猜各家参数名**（AGENTS §零.6）：国内兼容网关的 img2img 字段叫法不统一（`image`/`images`/
    `init_image`/`ref_image`…），有的家压根没实现。做法是发一种最通用的写法，
    **被服务端判错就自动去掉参考图重试一次**：出了图就在 `ImageGenResult.note` 里写明
    "这家生图服务不认参考图（原话），这张没按立绘生成"，聊天页照旧出图但把这句话挂在错误条上；
    两次都失败就把两句原话一起带回去。
  - 立绘读不出来 / 超过 **8MB** 时**直接拒绝生成**并说明原因，不许"悄悄当没这张图"出一张不像的。
  - 没传立绘 → 请求体和以前一模一样（多一个字段都不发），存量行为零变化。
- **顺手修掉一个存量 bug**：`PersonaEditorSheet` 保存时构造 `Persona(...)` 漏传 `hidden` 和
  `videoVisualType`，所以**编辑一次人设就会把已绑定的通话形象重置成 NONE**、隐藏过的会话被放回列表。
  现在两处都从原人设带过来。
- 内置手册同步：`会话设置 → 「视频通话形象」**、① 「Live2D 模型包」/② 「伪视频素材」、
  「角色立绘」整段（含"这张没按立绘生成"的降级说明）；`AiPromptsTest` 补 `v058_1` 双向断言
  （旧措辞"里传，那里两行："与"旧的「角色立绘」入口已删除"必须不在）。
- ⚠️ 本版**未编译、未打包、未跑测试**（改完只交源码，等用户发话）。



## v0.58（versionCode 140）

**视频通话上线：语音通话之外多一个独立入口，形象支持 Live2D 与伪视频两种。**

- 需求来自外部方案文档（`~/tabbit/梦欢_实时通话_视频化通话_开发方案与总Prompt.md`），本期明确不做 3D。
  四个岔路口由用户拍板：**① Live2D 走官方 Cubism Java SDK；② 入口是加号面板第五格「视频」；
  ③ 伪视频素材用「App 内逐个状态指认」而不是让用户手写 manifest；④ 人设没配形象时点视频通话先提示去添加**。
- **架构：通话能力与视觉形象彻底解耦。**
  `realtime/visual/` 新增 `CallVisualEngine` + `CallVisualState` + `VisualRuntime` 三个概念，
  三个实现 `NoneVisualEngine` / `VideoVisualEngine` / `Live2dVisualEngine`。
  实时通话引擎（`PipelineVoiceCallEngine`）**一个字都没复制**：视频通话复用同一套
  VAD→ASR→LLM→TTS→AudioTrack 链路，只是 VM 额外订阅它的状态与电平（方案文档 §四 / §十四.一）。
  **语音通话完全不碰这套接口**（不加载 Live2D、不建播放器、不扫素材目录）。
- 状态同步按方案文档 §七 的口径落地：视觉的 `SPEAKING` 跟着**引擎的 AI_SPEAKING**走，
  而引擎只在 `AudioTrack.play()` 之后才写它 → 不会出现"AI 没声音了角色还在张嘴"；
  用户 barge-in 时引擎进 `INTERRUPTED` → 映射到视觉 `LISTENING`，口型同时被推 0。
- **口型电平按播放头报，不按写入速度报**（`CallAudioManager`）：非阻塞写会先把轨道缓冲灌满，
  按写入速度算电平会让嘴比声音先动几百毫秒。现在每 512 采样（≈21ms）算一块 RMS 排队，
  等 `playbackHeadPosition` 真推进到那块才回调；队列积压超 ~1s 直接放行避免冻住；
  `stopPlayback()` 立刻归零。RMS→门限→开平方压缩→0.85 封顶在 `CallAudioLevel`（纯函数、有单测）。
- **官方 Sample 的口型是空壳**：`LAppWavFileHandler.getParameter()` 直接 `return 0.0f`
  （源码注释写明"本示例未实现"）。所以电平抽取全部自己写，映射走框架正规入口
  `CubismLipSyncUpdater(lipSyncIds, IParameterProvider)`（它每帧以 0.8 权重加到 `ParamMouthOpenY`），
  平滑用与帧率无关的一阶低通（attack 45ms / release 120ms）。
- **依赖**：**Media3 钉在 `androidx.media3:media3-exoplayer:1.9.0`**（阿里云 `/repository/google` 可达；
  ⚠️ **1.10 起要求 compileSdk 36** —— 先按 1.11.1 写会在 `:app:checkReleaseAarMetadata` 直接构建失败，
  本工程 compileSdk 35 + AGP 8.7.3 封顶只能用 1.9.0）。
  **不引 media3-ui** —— PlayerView 的 `exo_shutter` 就是黑屏来源，自己挂 TextureView 反而
  "新一帧出来之前一直显示上一帧"，切状态不闪黑，还省 330KB。
  Live2D = 官方 `CubismSdkForJava-5-r.5`：Framework 的 105 个 .java 原样放
  `cubism/framework/src/main/java`，**用 app 的 `sourceSets.main.java.srcDirs` 编进来、不建独立模块**
  （AGP 禁止 library 模块带本地 .aar 出 AAR，实测 `:cubism-framework:bundleReleaseLocalLintAar` 直接 FAILED）；
  闭源 `cubism/libs/Live2DCubismCore.aar` 由 app `implementation(files(rootProject.file(...)))` 引
  （官方 `RedistributableFiles.txt` 明确允许随包分发）。
  **APK 实测净增 688KB**（v0.57 37.20MB → v0.58 37.89MB）：arm64 `libLive2DCubismCoreJNI.so` 110KB
  （这一版 Core 已静态进 JNI so）+ classes.jar 24KB + Framework 收缩后的 dex + Media3 的 dex。
  要盯的是**运行时内存**（模型 + 2048 贴图约 30–80MB），所以形象只在视频通话页加载、挂断即释放。
  R8：`-keep class com.live2d.sdk.cubism.core.** { *; }` 写在 **`app/proguard-rules.pro`**
  （Core 的类被 JNI 按类名/字段名访问，混淆会崩 —— 和 sherpa-onnx 同一条教训；
  dex 抽查已确认 `Lcom/live2d/sdk/cubism/core/*;` 原名保留。
  ⚠️ 官方 Framework 自带的 consumer-rules.pro 是**空文件**，指望它自动带进规则是错的）。
- **视觉资源**：`filesDir/call_visuals/<personaId>/`（`metadata.json` + `video/` + `live2d/`）。
  库里只加一列 `personas.videoVisualType`（NONE/LIVE2D/VIDEO，Room **v14→v15**，
  `ALTER TABLE` 带默认值、旧人设一律 NONE、无破坏性迁移）；素材本体是文件，两边不一致时**以文件为准**。
  ⚠️ 不长期持有 `content://`：SAF 授权是会话级的，冷启动就可能失效，导入一律复制进私有目录。
- **导入即校验**（不可信输入）：zip 条目名过 `ZipEntryGuard`（出现 `..` / 绝对路径 / 盘符直接整包拒绝，
  宁可拒一半也不留下"能加载但缺贴图"的半截模型）、条目数与解压总量上限防 zip bomb、
  定位最浅的 `.model3.json` 后用 `Live2dPackageCheck` 查 moc/贴图/动作/物理引用是否都在包里；
  mp4 用 `MediaExtractor` + `MediaCodecList.findDecoderForFormat` 判"有没有画面轨、这机器解不解得开"，
  而不是按扩展名猜。任何失败都**删临时目录、不动原绑定、给一句能看懂的原因**。
- **故障隔离是硬要求**：视觉层任何失败只让 `VisualRuntime.Degraded(原因)` 出现，
  通话页把画面退回头像并写一行原因，电话照打（方案文档 §二十四）。
- 入口与 UI：加号面板从四格变五格（间距 18→10dp、格子 52→48dp，320dp 窄屏算下来还放得下）；
  上传口沿用**会话设置页**那两行（用户拍板，不新增一级入口、也不在人设编辑页重复一份）；
  没配形象时点「视频」弹一句提示，两个去处：去添加（跳会话设置）/ 先用语音通话。
  素材删除一律"长按→菜单点删除"（补一次长按震动），不直接删。
- **废弃并清理**：旧的「角色立绘」行与 `persona_art` / `persona_live2d` 两个 DataStore 键删除
  （单文件既渲染不出来也没法按状态指认），启动时清一次老键并删掉 `filesDir/persona_art`、
  `filesDir/persona_live2d` 两个目录。人设删除时级联清 `call_visuals/<id>/`（几百 MB 素材不留孤儿）。
- 内置手册同步（AGENTS §零.5）：`AiPrompts` 链尾两条 replace ——「第四格「通话」」改写为「「通话」格」
  （有旧措辞可断言）+ 视频通话整章追加（含"只有「待机」必填""只把画面退回头像，电话照打"），
  `AiPromptsTest` 补双向断言。
- 测试：`CallVisualTest` 覆盖方案文档 §三十的纯函数部分 —— 状态映射表（两个同名不同义的坑）、
  素材回退链、manifest 编解码与坏 JSON、Live2D 包缺件/越界/坏 JSON、Zip Slip、电平门限与单调性、平滑快慢。
- ✅ **release 包已编译通过**：`assembleRelease`（R8 + 资源收缩）全绿，产物 `梦欢-v0.58-release.apk`
  **37.89MB**（v0.57 是 37.20MB → 净增 688KB），v0.57 已归档进 `archive/`；debug 证书签名、
  单 ABI arm64-v8a，`lib/arm64-v8a/libLive2DCubismCoreJNI.so` 进包，dex 里
  `Lcom/live2d/sdk/cubism/core/*;` 原名保留（JNI keep 生效）。
  ⚠️ **单测未跑、真机未验**（按项目约定，测试与真机由用户发话）。真机必须验的：
  语音通话回归、伪视频 idle 循环与状态切换无黑屏、Live2D 待机/眨眼/实时口型、
  打断后口型立即收合、挂断后释放、损坏模型包/损坏视频/删素材/断网/切后台/锁屏/蓝牙耳机切换都不许崩。
  另有一条**留待真机定夺**：竖屏手机录的素材带旋转元数据时，裸 TextureView 会不会把画面转正
  （现在按 Media3 `VideoSize` 的宽高比摆框，`unappliedRotationDegrees` 没单独处理）。



## v0.57（versionCode 139）

**模型配置新增「获取可用模型」：填好接口地址 + Key，一键拉出该接口的模型列表，点一个直接填入 —— 不用再手打模型名。**

- 起因（用户真机）：**模型名手打错**导致"测试连接成功、聊天 404"。各家的 Model ID 是
  "全小写 + 连字符 + 日期后缀"那套写法（方舟 `doubao-seed-2-1-lite-260915`），凭"产品名"手打必错。
- 做法（用户拍板"**手动按钮**" + "**对话 / 生图两页都加**"）：
  - 「编辑模型」弹窗里，API Key 下方新增文字按钮 **「获取可用模型」** —— **点了才联网**（不偷偷发请求）；
  - 请求 `GET {接口地址}/models`（OpenAI 兼容端点），拿到后在**同一个弹层里**切成列表态
    （标题变「选择模型」+ 列表），**点一行 = 填进「模型」栏**并退回表单；
  - **刻意不叠第二个 ModalBottomSheet**：两层底部弹层会把标题 / 返回 / 遮罩搅成一锅，
    改成同一层内换内容 + 一行「← 返回」；
  - ⚠️ 列表**必须给死高度**（320dp）：外层弹层是 `verticalScroll` 的 Column，
    里面再放没有界高度的 LazyColumn 会直接崩（infinity maximum height constraints）。
- **失败一律不阻塞**（不少中转网关压根没实现 `/models`）：按钮下方直接写原因
  （`获取模型失败 · HTTP 404：…（可直接手填模型名）`），照旧能保存。
- 响应解析做四种形状兜底（顶层数组 / `data[]` / `models[]` / `results[]`·`list[]`，元素取 `id`，
  后备 `model`），纯函数 `OpenAiClient.parseModelIds`（去重、保持服务端顺序）。
- **边界说清**：列表来自服务商 `/models`，**不保证只列你已开通的**（方舟的"开通列表"接口
  要 Access Key 鉴权，App 拿不到）——好处是 **ID 是官方原文**，不会因手打错而 404；
  能不能调，选完点一下「测试连接」当场知道。
- 手册同步：`预设一键填入，支持` → `预设一键填入；也能**获取可用模型**（…点一个直接填入模型名，不用手打），还支持`；
  `AiPromptsTest` 新增 `v057` 双向断言（目标子串取自最终文案，全文唯一）。

**长按消息菜单：箭头改到底部正中并放大（用户点名）。**

- 下指箭头从"靠气泡那一侧（用户右 / AI 左）"改到**菜单底部正中**、尖端朝下指向气泡；
- 尺寸放大一圈（16×7 → 24×12），让"这块浮层是从下面那条消息冒出来的"一眼可见；
- 顺手修掉旧写法的一个隐性缺陷：`.width().height()` **之后**又叠 `.padding(horizontal = 14.dp)`，
  把 Canvas 内容宽度挤成 0（箭头实际退化成一条看不见的线）。删掉那层 padding 即可，
  要留白改用外层 Spacer（已写进 `MenuTail` 的注释，防下次再叠回去）。

**会话列表预览：媒体消息改显类别标签（用户点名）。**

- 问题：消息页最下方那条"最新消息"预览直接把 `content` 打出来——图片消息的 content 是图注
  （AI 生图那条是**英文提示词**）、语音条的 content 是**转写文字**、文件消息的 content 是
  **抽出来的整篇正文**，于是预览里出现"一段英文""整段话""一大段字"，
  看不出这是一张图 / 一条语音 / 一个文件。
- 修法：`ChatListScreen.previewOf` 按 `MediaKind` 出标签——`[图片]` / `[语音]` / `[文件]` / `[表情]`
  （口径与聊天记录页 `ChatHistoryScreen` 统一），用户发的仍带「你：」前缀；
  通话记录卡保持 `📞 …` 不变。

**退出对话页后 AI 继续生成（用户点名；生成不再绑在聊天页上）。**

- 起因：`ChatViewModel` 绑在 `chat/{personaId}` 这条导航记录上，**退出聊天页即被销毁**、
  `viewModelScope` 取消 → 正在跑的流式请求连同没落库的回复一起消失。
- 做法（用户拍板"**能看到它还在生成**"）：
  - 生成链路改挂**应用级作用域**（`ChatViewModel.generationScope = appScope ?: viewModelScope`）——
    退出页面不掐断；JVM 单测没有 appScope 时退回 `viewModelScope`，行为不变。
    涉及四处：`send` / `retry` 的启动、`reply()` 里的流收集、`recordUsage` 的记账。
  - 新增 `ChatGenerationBus`（进程级单例，只登记状态不跑逻辑）：登记"哪些会话正在生成文字 /
    正在生图 + 画面提示词"，另存一份"离开期间失败的提示"。重新进入会话时新 VM 靠它
    ① 把三点气泡 / 生图卡接回来，② 补显示离开期间那次失败（`takeError` 取走即清，只显示一次）。
  - `setError()` 成为唯一失败出口：`_uiState` 照旧上屏，**若本 VM 已销毁**（`onCleared` 打的标记）
    再转投总线 —— 否则异步失败就等于没发生（项目铁律：失败不许静默）。
  - 生成协程跑在 `applicationScope` 上，**整段 try/catch 兜底**：那里任何没接住的异常会直接
    冒到默认处理器把进程干掉（同 `CrashLog` / `PipelineVoiceCallEngine` 的教训），
    兜底成"生成失败"错误条，不许一次生成把 App 崩了。
- ⚠️ 行为变化：**退出聊天页不再是"取消生成"的手段**。目前没有「停止生成」按钮，
  单轮生成靠 OkHttp 自身的超时兜底（流式读 120s / 整次 5 分钟），超时会落成错误条。
- 手册同步：在"语音通话记录卡长按也能弹出这个菜单"那句后面追加一句说明；
  `AiPromptsTest` 新增 `v057 退出对话继续生成进手册`（追加式 replace，锚点还在，只有"新句在"这一向）。

> **版本号口径变更（用户 2026-10-07 拍板）**：从本版起用**两段式** `0.57 / 0.58 …`（每次第二位 +1），
> 不再出 `0.56.17` 这种三段补丁号；已出货的 0.56.17 保持原样不改。

---

## v0.56.17（versionCode 138）

**长按消息菜单改浅灰 + 恒贴气泡正上方带下指小尾巴；修「AI 从不发语音条，叫它发也不发」。**

### 一、长按气泡菜单：浅灰底 + 固定在气泡正上方（用户点名）
- **底色深灰 → 浅灰**：`#3A3A40` → `#F2F3F5` + 1dp 发丝描边（固定色，不随主题翻——浅灰在深色模式下同样成立）；
  前景色随之整组从"浅色系"换成"深色系"：普通项近黑 `#1A1A1A`、激活态加深品牌绿 `#00A15F`、
  删除危险红 `#F24E1E`，组间分割线由白 15% 改黑 12%（同一条规矩：固定底色的浮层，前景色必须一起固定，
  否则浅灰底上会出现"浅白图标"的不可见组合）。
- **位置固定**：去掉"气泡在下半屏就弹上方、在上半屏就翻到下方"的翻转逻辑（v0.56.15 的做法），
  现在**始终贴在气泡正上方**，小尾巴恒在菜单下方、尖端朝下指向这条气泡，靠气泡那一侧（用户右 / AI 左）；
  气泡贴着屏幕顶部时靠 `coerceIn` 兜住，宁可压住气泡也不翻面——翻面会让菜单位置随消息位置忽上忽下。
- 尾巴顶边用 `offset(-1dp)` 压住菜单描边，接缝处不留横线；弹出动画（弹簧缩放 + 淡入）与点按震动保留。

### 二、AI 语音条：补上"用户明确要求就必须发"的硬规则（用户报"叫它发也不发"）
- **现象**：语音合成已配齐（语音模型页能看到小米 MiMo 卡片）、界面上没有任何红色错误条，
  但 AI 回复一直是纯文字，**用户明确要求"发条语音"它也只回文字**。
- **排查结论：没找到确定性根因，这条不是小米 MiMo 专有，也不是必现 bug**。逐环节核对都没问题——
  MiMo 协议实现（`/chat/completions` + 目标文本放 `assistant` + `audio.format=wav` +
  取 `choices[0].message.audio.data`）与官方文档一致且有单测覆盖；能力说明的注入条件
  （当前生效那家 TTS 配齐）成立；合成失败一定弹错误条、不会静默；SSE 解析、
  标记解析（`【语音条】`/`[语音条]`）、消息原地升级、气泡渲染均正常。
  **用户随后在同一个旧包（v0.56.16）上又自行恢复**——即"由模型自行决定何时用语音"本身带随机性。
  ⚠️ 记一条经验：**"AI 自选时机"的能力，别默认它 100% 会触发**；报"XX 从不发生"时先分清
  "是链路坏了"还是"模型没选它"，后者在旧包复现不了属正常。
- **本次仍做一处防御性加固**（`VOICE_NOTE_DEFAULT_STRATEGY` 重写，标记格式 `VOICE_NOTE_FRAMEWORK` 不动）：
  旧文案整段只讲"要克制 / 不要连续发 / 不适合说的用文字"，**没有一条讲"用户明确要求时该怎么办"**；
  现在写成三种情况：① 用户明确要求（发条语音 / 用语音说 / 说给我听 / 语音回我）→ **必须**带标记，
  不许只在正文里用文字答应；② 模型自己挑时机 → 短情绪句（问候/安慰/鼓励/道晚安/一句提醒）放心用；
  ③ 长回答、清单、步骤说明一律文字。并补一句"语音条长按能看到正文，不必担心信息丢失"——
  框架里"正文不会另外显示"对谨慎的模型是劝退。
- 影响面：没改过「AI 提示词 → 语音条策略」的用户自动用上新策略；改过的保留自己的文本。

### 三、MiMo 克隆音色"每次声音都不一样"：把风格输入钉死（用户报，音色页试听同一句重合成也不同）
- **机制先说清（已核对代码，App 侧无变量）**：MiMo 没有音色上传/管理接口，所以**每合成一次**都要把
  本机 `filesDir/voices/` 里那段样本 base64 内联进 `audio.voice`、调一次 `mimo-v2.5-tts-voiceclone`。
  音色页「试听」用的是那一行的 `local:` 引用、文本固定为"你好，这是我的声音。"，
  每次请求**逐字节一致**，客户端没有随机、没有换样本的地方。
- **所以漂移出在服务端**：这是零样本克隆，而官方 TTS 文档只暴露 `model / messages / audio / stream`，
  **没有 `seed`、`temperature` 之类能固定随机性的字段**（不去赌未文档化参数）。硅基那种
  "上传一次换 uri"的注册式克隆天生更稳，就因为音色是服务端实体。
- **本次改动（用户拍板"加一条固定风格指令试试"）**：`user` 消息的 content 是官方定义的
  "语气与风格指令"位，我们以前发的是**空串**。现在**克隆时**固定发一条
  `MIMO_CLONE_STYLE_ANCHOR`（"保持与参考音频完全一致的音色、语速和情绪，不要改变音色。"），
  把每次请求的**风格侧**钉成同一句；预置音色没有参考音频、原来就是空串，**不动**。
- ⚠️ 这句只写"与参考音频一致"，**不描述任何具体音色**——描述性文字会被当成风格要求，
  反而可能盖过样本本身（别顺手改写，见 `TtsClient.kt` 该常量注释）。
- 诚实的边界：这条只压**语气/情绪**漂移，**能不能让音色完全稳定由服务端决定**。
  真要音色固定，走预置音色（`白桦`/`冰糖`）或改用硅基的注册式克隆。

### 四、模型预设：补「豆包」+ 刷新生图 Model ID（用户真机接入豆包两次 404）
- **现象**（用户两张截图）：
  1. 生图模型填 `Doubao-Seedream-5.0-lite` → `HTTP 404：The model or endpoint … does not exist
     or you do not have access to it`（接口地址 `https://ark.cn-beijing.volces.com/api/v3` **是对的**）；
  2. 对话模型填 `https://ark.cn-beijing.volces.com/api/v3/chat/completions` 当**接口地址**
     → 404（App 会自己拼 `/chat/completions`，于是变成 `…/chat/completions/chat/completions`），
     且模型 `Doubao-Seed-2.1-lite` 同样不是合法 ID。
- **根因**：方舟的 Model ID 是**全小写 + 连字符 + 日期后缀**（`doubao-seedream-5-0-260128`、
  `doubao-seed-2-1-lite-260915`），用户凭"产品名"手打必然打错；而 App 的「常用服务商」里
  **对话那排根本没有豆包**，生图的豆包预设还停在很老的 `doubao-seedream-3-0-t2i-250415`。
- **修**（`ApiConfigScreen.kt` 两个预设表，只改预设值）：
  - 对话预设新增「豆包」= `https://ark.cn-beijing.volces.com/api/v3` + `doubao-seed-2-1-lite-260915`；
  - 生图豆包预设换成 `doubao-seedream-5-0-260128`（不用 `-5-0-lite-260128`：官方标注 lite 别名即将下线）。
  - 出处写进代码注释：方舟模型列表 `https://docs.volcengine.com/docs/82379/1536429`。
- ⚠️ 记住这类坑：**预设是"防打错"的第一道门槛**，官方改 ID 就要跟着改；
  用户手打 Model ID 出错时，先怀疑 ID 写法（大小写/连字符/日期后缀），再怀疑地址。
  另外方舟 404 有两种含义（ID 不存在 **或** 模型未在控制台开通），报错里要保留服务端原话。

### 五、生图尺寸写死 `1024x1024` → 豆包 Seedream 5.0 永远生不出图（同一批真机反馈的续集）
- **现象**：模型 ID 改对、模型也开通了，报错从 404 变成
  `HTTP 400：The parameter 'size' specified in the request is not valid: image size must be at least 3686400 pixels`。
- **根因（App 的 bug，不是用户的）**：两处都把 `size` 写死 `"1024x1024"`（=1,048,576 像素）——
  - 「测试连接」[`ImageProbe`](app/src/main/java/com/menghuan/app/llm/ImageProbe.kt)；
  - **正式生图** [`ImageGenerationClient.generate`](app/src/main/java/com/menghuan/app/data/ImageGeneration.kt)。
  豆包 Seedream 5.0/4.5/4.0 官方规定**总像素 ∈ [2560x1440=3686400, 4096x4096=16777216]**、
  **默认 2048x2048**、也接受简写 `2K/3K/4K`；官方示例里 `1500x1500` 被点名当无效反例——
  我们的 1024x1024 正属于这一类。
  反过来改成 2048x2048 又会踩 OpenAI gpt-image-1（只吃 1024/1536 那几档）与
  智谱 cogview-3（只吃 1024x1024 等）——**没有任何一个尺寸能同时满足各家**。
- **修**：
  - **两处都彻底不发 `size`**，让各家用自己的默认值（豆包 2048x2048、OpenAI `auto`、
    智谱/万相/SiliconFlow 各有默认）；两处必须一致，否则会出现"测试连接通过、真生图失败"。
  - **失败不再吞服务端原话**：`generate()` 从"失败返回 null"改成返回 `ImageGenResult(path, error)`，
    把 `HTTP 4xx：<服务端原文>` 一路带到聊天页错误条；多模型轮询时逐条列
    （`模型A：原因；模型B：原因`），而不是笼统的"模型未配置或调用失败"。
  - 错误体解析统一成 [`NetworkStack.apiErrorText`](app/src/main/java/com/menghuan/app/llm/NetworkStack.kt)
    （从 ImageProbe 的私有实现提出来共用；本项目在 ImageProbe 404、生图链路、记账识图上
    已经因为"吞掉服务端原话"踩过三次，这次收口）。
- ⚠️ 教训：**同一个请求参数在两个地方各写一份，早晚漂移**——生图尺寸、超时、端点路径都属于
  "必须单一出处"的东西；以及**别替服务商选参数**：能交给默认值的就交给默认值，
  写死一个"业内最通用"的值，恰恰会在最新模型上第一个炸。

### 六、对话模型「测试连接」改成真校验（"测试成功但聊天 404"的假绿）
- **现象**（用户真机）：豆包对话模型地址/Key 都对、模型 ID 也是官方现行的
  `doubao-seed-2-1-lite-260915`，**测试连接显示成功**，但真发消息时聊天页弹红条
  `接口地址或模型名不存在，请检查配置`。
- **根因（App 的假绿）**：`OpenAiClient.testConnection` 打的是 **`GET {base}/models`**——
  它只证明"Key + 地址能通"，**完全不校验用户填的模型名**，也不反映"该模型是否已开通"。
  于是模型没开通时测试全绿、真请求才 404（方舟原话：
  `The model or endpoint … does not exist or you do not have access to it`），
  用户被误导去查本来正确的地址。生图那边的 `ImageProbe` 从 v0.36.2 起就是"发一条最小真请求"的设计，
  对话这边一直没对齐。
- **修**：`testConnection` 改成**真的 POST 一条最小对话请求**（`messages=[{role:user,content:"hi"}]`，
  `stream:false`），成功即 null，失败把 `HTTP 4xx：<服务端原话>` 返回给用户看。
  - **刻意不发 `max_tokens` / `max_completion_tokens`**：o 系 / gpt-5 拒 `max_tokens`，
    部分兼容端点不认 `max_completion_tokens`，为省这点 token 冒"测试被自己发错字段搞挂"的风险不值。
  - 超时 10s → 20s（真跑一次推理比读模型列表慢）。
  - 连带修掉聊天页错误条的"吞原话"：`friendlyApiError` 现在把服务端原文附在括号里
    （截 120 字，错误条只有 2 行），`ApiException.body` 相应公开为属性。
    以前用户只看到"接口地址或模型名不存在，请检查配置"，而方舟原话其实是
    "…does not exist **or you do not have access to it**"——后半句才是真正的原因（没开通）。
- ⚠️ 教训：**"测试连接"必须打到真正会被使用的那条路径上**——只验证鉴权不算验证配置；
  凡是"测试绿、正式红"的反馈，先怀疑测试端点与正式端点不是同一个。
  另外方舟的模型是**逐个开通**的（生图开通了 ≠ 对话模型开通），报错里的
  `does not exist or you do not have access to it` 两种含义都要向用户讲清。

---

## v0.56.16（versionCode 137）

**修小米克隆音色"克隆样本已丢失"——样本引用存的是文件名，合成时却按相对路径找，从建成就没成功过。**

- 现象（0.56.15 真机）：小米下克隆音色的试听/通话/语音条全部报"克隆样本已丢失：voice_xxx.wav"。
- 根因：音色页与会话设置存进 VoiceRef 的本地样本引用是**文件名**（`local:voice_xxx.wav`，
  `localVoiceRef` 的调用方传的都是 `it.name`），而 `XiaomiMimoTts.resolveVoice` 直接
  `File("voice_xxx.wav")` 按相对路径找文件——相对 App 工作目录必然不存在。
  样本本身好好地躺在 `filesDir/voices/` 里。**小米克隆从 v0.42.0 建成就没真正合成成功过**
  （此前一直被更前面的模型错配 400 挡住，v0.56.13/14 修好模型后才暴露到这一步；
  单测只测了预置音色与自定义端点的请求形状，没覆盖本地样本路径，所以漏到今天）。
- 修：
  - `resolveVoice` 把文件名拼回 `filesDir/voices/` 目录再读（`substringAfterLast('/')`
    兼容万一存了完整路径的旧值）；MIME 判断不受影响；
  - `XiaomiMimoTts` / `TtsClients.from` 增加 **context** 参数（可空，JVM 单测传 null）——
    读本机目录必须要有它；四个调用点（语音条 / 通话 / 音色页试听 / 语音合成页试听）同步，
    测试补 null。
- 修复后链路：克隆（选样本落 `voices/`）→ 试听/设默认/配人设 → 合成时读对文件 →
  base64 内联 → `mimo-v2.5-tts-voiceclone` 模型出声。

---

## v0.56.15（versionCode 136）

**头像选择器滚轮棘轮震动（用户点名"像真的在滚动一样，轻微的震动就行"）+
长按消息的震动从两次改一次、菜单改微信式灰底气泡带尾巴指向对话条。**

- **头像选择器滚轮棘轮震动**：触发点在选中判定处——锚点上的格子**每变一格**给一次
  `TextHandleMove` 触觉反馈（Compose 里最轻的一档，正符合"轻微、不用太大"）；
  快速滑动跨几格就连着几声轻 tick，点选自动滚过去时同样逐格 tick。
  初始定位（进页面对齐当前头像）不震——去重标记记上一次 tick 过的格子，首次发射只记录。
  实现要点：震动放 snapshotFlow collect 里按 index 变化触发（不是按滚动事件），
  天然按格去重、天然覆盖手动与点选两条路径。
- **长按消息震动两次 → 一次**：`AiBubble`/`UserBubble` 的 onLongClick 里自己震一次，
  回调 `openMessageMenu`（v0.54.7 的"统一补一次震动"入口）又震一次，叠加成两下。
  删掉气泡里的那次，`openMessageMenu` 成为唯一震动入口（所有消息类型恰好一次）。
- **长按菜单改灰底气泡 + 小尾巴**：
  - 菜单底色从白（NeuSurface）换成**固定深灰 `#3A3A40`**（微信式，不随主题翻色，
    深浅两模式下都成立）；图标色随之固定：普通浅白 / 激活品牌绿 / 删除亮红——
    原来的主题令牌（强调色黑）在深灰底上不可见；
  - **小尾巴**：靠气泡一侧（用户气泡在右、AI 气泡在左）伸出一枚三角，指向对话条；
  - 方向判断提前到组合期：气泡中心在窗口下半 → 菜单弹上方（尾巴朝下）、上半 → 弹下方
    （尾巴朝上），PopupPositionProvider 按同一方向值无条件定位，两处不会各说各话；
  - 弹出动画（弹簧缩放 + 淡入）与点按震动确认保留，只是换了壳。
- **两个删除菜单改小 + 音色菜单补描线**：
  - 音色页长按删除的菜单补上发丝线描边（记账本同款，之前漏了）；
  - 音色页与 AI 图库的长按删除菜单整体改小：字 14→13sp、项高 36dp、内边距收紧、
    圆角 14→10dp。记账本的删除菜单用户没点名，保持原样。
- **AI 图库行卡重排 + 提示词挪进长按菜单**：
  - 卡片上不再显示提示词；剩下的信息分层加强——**模型**提为主行（13sp SemiBold）、
    **人设**次行（11.5sp）、**时间**垫底（10.5sp），三级灰阶一眼分清主次；
  - 长按菜单从只有"删除"变成"提示词 ｜ 删除"（小号规格统一，中间发丝线分隔）；
    点「提示词」弹底部弹层：完整提示词全文 + 一键复制。

---

## v0.56.14（versionCode 135，未出包）

**用户反馈的修正批：恢复被多删的两处动效（本周回顾格子浮现 / 记账本详细页金额滚动）+
底栏黑滑块拖动改"跟手"，修掉一弹一弹 + 真机报错两张截图的两个修复。**

### 一、恢复 v0.56.11 删多的动效（用户点名"我没叫你删的你也删了"）
- v0.56.11 执行"4 个卡片不需要弹条出场 + 金额不跳动"时删宽了范围。本版恢复：
  - **本周回顾 · 7 天格子的逐个浮现**（DayChip 的 appear：alpha + scale 0.7→1、每格错峰 40ms）；
  - **记账本详细页 · 金额滚动**（现金余额卡 40sp 与收入/支出/结余三栏 17sp 的里程表滚动恢复）。
- **保持删除**（这些才是用户点过名的）：三处 `staggerEnter` 卡片弹条出场、今日建议卡上浮淡入、
  数据页入口卡的金额滚动与记一笔弹层的金额滚动。
- **一直保留的**：本周进度条 sweep、点格子展开当日明细的过渡、每期净额走势柱生长动画。

### 二、底栏黑滑块拖动改跟手（用户点名"不丝滑、总是一弹一弹的"）
- 根因：三层动画叠在一个黑块上——弹簧位置（animateDpAsState，跨格翻页 currentPage 一变就开追）
  + 弹簧化的跟手残余（animateFloatAsState 包着 dragOffset，带滞后）+ 翻页瞬间残余位移突变，
  三股劲挤在一起就是"一弹一弹"。
- 改法：黑块位置收敛为**单一 Animatable 来源**——
  - **拖动中：`snapTo` 直接跟手指，零动画零叠加**（丝滑的关键），边界夹住；
  - 跨格翻页逻辑不变（跨满一格 `animateScrollToPage`，翻页走 Pager 自己的弹簧）；
  - **松手：从手指最后位置弹簧滑回目标格**（MediumBouncy 过冲一次）；
  - **点选 tab：弹簧滑到目标格——Q 弹过冲原样保留**（用户点名"不要删除点选的弹簧效果"）。
- `LaunchedEffect(targetPage, dragging)` 统一管理非拖动态的弹簧；拖动中让位，
  `Animatable` 的 MutatorMutex 保证并发动画自动收敛，不会打架。

### 三、真机截图的两个报错（用户问"这些是怎么回事"）
- **MiMo 试听 400 Param Incorrect**：用户把小米的模型填成 `mimo-v2.5-tts-voiceclone`（复刻专用），
  试听发的却是预置音色（默认回落第一个预置"白桦"）——voiceclone 模型**不收预置音色名**，
  官方三模型能力互斥。修：预置音色的模型选择加兜底——配置里填的是 voiceclone/voicedesign 时
  **自动回落 `mimo-v2.5-tts`**（新增 `TtsVoices.MIMO_PRESET_MODEL` / `MIMO_VOICEDESIGN_MODEL`
  常量，默认模型引用前者）。至此 MiMo 三个方向全部自动对模型：预置→preset（防呆回落）、
  克隆→voiceclone（上版已做），用户模型栏填什么都不坏。
- **生图"接口路径不存在"误导**：豆包 ark v3 地址其实是对的，404 是**模型 ID 填错**
  （`Doubao-Seedream-5.0-lite` 大小写不规范；官方形态全小写带日期，如预设里的
  `doubao-seedream-3-0-t2i-250415`；或模型未在火山控制台开通）——而 ImageProbe 的 404 分支
  **把响应体扔了**，只给"检查地址"的固定提示，用户被引去查本来正确的地址。
  修：404 先读响应体原文（"HTTP 404：<服务端原话>（…再检查地址版本段）"），没原文才用旧提示。

### 四、编辑人设保存后对话页立即生效（用户点名"要退到消息页才生效"）
- 根因：`ChatViewModel` 只在 init 里 `getPersona` **读了一次**人设存进 StateFlow，
  之后编辑人设 → 保存（Room 已更新），对话页顶栏的名字/头像、以及**下一轮对话的设定词**
  全还是旧的，要退到消息页重进（ViewModel 重建）才重新读。
- 修：改成 `observePersonas().collect` **持续观察** Room（人设表变化即刷新），
  指定 id 找不到时回落列表第一个（保持原一次性读的兜底语义）。改动即生效：
  回到对话页顶栏立即换新头像新名字，下一轮对话就带新设定词。

### 五、记一笔的语音与识图（用户点名"有麦克风权限却提醒没有 / 识图不生效"）
- **语音记账推倒重做**：原来用的是**系统 SpeechRecognizer**——v0.50.0 语音栈重做时
  判定"国产 ROM 上永远走不通、不要复活"的链路，记账本是漏网之鱼（v0.42.0 建，一直没清）。
  系统识别服务不可用时误报 `ERROR_INSUFFICIENT_PERMISSIONS` → "没有麦克风权限"，
  用户明明授权了。修：`VoiceLedgerController` 改走 **VoiceRecorder 录音 + SherpaAsr 端侧转写**
  （与语音条 / 通话同一条链）；模型没下载时在点语音那一刻就提示
  "语音识别模型还没下载：设置 → 模型配置 → 识别模型"。交互变化：说完点「停止」→
  "正在识别…"（1~3 秒）→ 一次性填表单（端侧离线识别没有逐字 partial，语音条/通话同款）。
- **识图报错不再吞真话**：vision 请求原来 `runCatching{getOrNull()}`，模型不支持图片（400）、
  Key 错（401）、超时全被吞成一句"识别失败，请检查网络或接口配置"。修：带上异常原文，
  用户能直接看到服务端说的真话（如"does not support image"）。识图的接口来源
  （`apiConfig` = 当前生效对话接口）核对无误，未动。
- **识图"没识别出金额"三板斧**（用户真机反馈：请求通了但提示没识别出金额——
  意味着模型自己说看不出金额，可它到底说了什么界面上看不到）：
  1. **识图分辨率 1024 → 1536（JPEG 78 → 85）**：账单是文字密集图，长截图（支付详情页
     高宽比 ≥2:1）压到 1024 金额数字会糊到模型认不出。`VisionImage.encodeDataUrl` 加
     `maxDim/quality` 参数（聊天发图默认不变），识图传 1536。
  2. **模型兜底时要求说明原因**：prompt 的"看不出金额"兜底输出从空 note 改成必须写原因
     （"图片太模糊 / 图里没有金额数字 / 不是账单截图"）。
  3. **失败时把模型的话亮出来**：提示从"没识别出金额，请手动填写"升级为
     "没识别出金额，模型说：<原因>"；且模型的原因说明会进备注框，不再把整段 JSON 串灌进去。
     再识别失败时用户立刻知道是图的问题还是模型跑偏，发一句原话就能继续定位。

---

## v0.56.13（versionCode 134，未出包）

**音色板块去"硅基中心"：自定义 OpenAI 兼容端点的手填音色 ID 从单条升级成多条音色库（与用户讨论后拍板）。**

### 背景（用户点名"音色板块都围绕硅基做的，不选硅基怎么办"）
- 三家排查结论：**MiMo 体验基本齐全**（预置 + 本地样本克隆 + 试听 + 删除都有；合成内联样本
  ≤10 秒 ≈ 640KB 编码后，可接受，且是 MiMo 服务端无音色注册接口所致）；真正的短板是
  **自定义端点**——没预置、没克隆、只能填一条裸 ID（再填就覆盖）、没有显示名、入口只是顶栏小按钮。
- **架构本身没问题**（配置 / 默认音色 / 隐藏表都按家分桶），薄的是自定义端点这条的内容。

### 自定义端点多条音色库
- 存储：新行式小表 `tts_custom_voices`（`id\t名字` 一行一条，与 cloneVoiceNames 同款），
  `customVoices() / addCustomVoice() / removeCustomVoice()`；旧的单值键
  （`tts_custom_manual_voice_id`）**首次读取时自动迁移**进表（名字先用 ID 顶着），搬完清旧键。
- 名单：`TtsVoices.library()` 新增 `customVoices` 参数，自定义端点按它出多行（显示用户起的名，
  没起名显示 ID）；单值手填那行对自定义端点不再生效（避免同一条两行），硅基 / MiMo 照旧。
- 音色页：正文首行**「＋ 添加音色」行卡**（那家唯一的音色来源，给一等公民位置）；
  顶栏「ID」按钮对自定义端点撤掉（硅基 / MiMo 保留）；旧的空状态说明小字删除；
  添加弹层 = ID（必填）+ 显示名（选填）；**第一次添加（还没有默认音色）自动设为默认**，
  已有默认不抢——与硅基克隆完的行为一致。
- **顶栏标明当前生效的服务商**（"音色 · 硅基流动"）：名单 / 克隆 / 试听 / 默认音色都只对
  生效那家有意义，配了多家后不标出来就分不清"这排是谁家的、克隆会进谁家"
  （用户问"点的克隆到底是硅基还是小米"暴露的）；切家仍去语音合成页点卡片。

### MiMo 克隆合成切对模型（对照官方 speech-synthesis-v2.5 文档查出的 bug）
- 官方把 TTS 能力拆成**三个互斥的模型**：`mimo-v2.5-tts`（预置音色）、
  `mimo-v2.5-tts-voicedesign`（文本描述设计）、`mimo-v2.5-tts-voiceclone`（音频样本复刻）；
  **预置音色模型会拒绝内联样本**。而我们的实现克隆与预置共用同一个 model 字段（默认 mimo-v2.5-tts）
  ——按官方原理 MiMo 克隆音色的合成根本调不通。
- 修法：`XiaomiMimoTts` 按 voice 类型**自动切模型**——`local:` 克隆样本的请求强制用
  `mimo-v2.5-tts-voiceclone`（新增 `TtsVoices.MIMO_VOICECLONE_MODEL` 常量），预置音色继续用
  用户配置里的模型。试听 / 通话 / 语音条三条链路全覆盖。
- 其余各项对照结论（均一致，未动）：`/chat/completions` + assistant 传文本、`data:{MIME};base64,`
  前缀、样本编码后 ≤10MB、样本仅收 mp3/wav（我们统一转 WAV）、非流式 `audio.format=wav`、
  鉴权双头。官方预置 9 个音色仍只留白桦/冰糖一男一女（v0.52.0 拍板）。
- 删除：长按菜单（v0.56.12 同款）对自定义端点的行照常可用，删除 = 从库里移除该行；
  删的是当前默认音色时回落（无预置 = 清默认，下次合成前再选）。
- 手册同步：语音章节"加上我克隆的音色和一条「手填音色 ID」"定点改写为自定义端点多条库的说明，
  `AiPromptsTest` 新增 `v05613` 双向断言。

---

## v0.56.12（versionCode 133）

**「音色」页支持长按删除（用户点名）；本版与未出包的 v0.56.11（八条修复）一起出货。**

### 音色页长按删除
- 名单里**预置音色（每家一男一女）不可删**；我克隆的（硅基 uri / MiMo 本机样本）与手填 ID
  都支持**长按弹菜单**——按破坏性操作惯例（2026-10-02 拍板）：长按只弹菜单 + LongPress 震动，
  点菜单里的「删除」才真删，菜单锚定条目右下角。
- 三类音色的删除语义各不相同：
  - **手填 ID**：真删（清 `tts_manual_voice_id` 键）；
  - **MiMo 本机样本**：删 `filesDir/voices/` 里的 wav 文件 + 清中文别名；
  - **硅基克隆**：**本机隐藏**——克隆音色的实体在服务商账号里，App 不调服务商的删除接口
    （账号资产删了就找不回），加进新增的 `tts_hidden_voices` 隐藏表（`PROVIDER\tvoiceId` 行式小表），
    名单的**缓存读取**与**网络刷新**两条路径都按它过滤；服务商账号里那份原样保留。
- 删的正好是当前默认音色时，回落到该家第一个预置（自定义端点没有预置 = 清掉默认）。
- 人设侧的音色候选（对话页右上角菜单 → 音色）与音色页同源，同步过滤隐藏表。
- 删除后页面立即更新：硅基克隆从 state 移除、MiMo 重列本机文件、手填行随 flow 消失；
  底部反馈一行「已删除「…」」。

---

## v0.56.11（versionCode 132，未单独出货，改动并入 v0.56.12）

**一批动效收敛 + 头像选择器补课：emoji 彻底去模糊、框左侧不再漏 emoji、支持点选、
保存头像兜底必中；打卡不再给应用助手发回执；数据页 4 卡去弹条出场；金额全部改静态；底栏黑胶囊拖动方向修正。**

### 一、编辑人设 · emoji 头像（四条反馈一起修）
- **不再模糊**：根因是 emoji 是位图字形，`graphicsLayer` **放大**必糊 —— 滚轮排把 22sp
  放大到 1.22 倍、头像框里把 23.5sp 放大到 1.42 倍。修法 = **按最大态字号渲染、layer 只缩小**：
  滚轮格基准字号 22 → 27sp、scale 从 0.78→1.22 改为 0.64→1.0（框位上那个以 1.0 清晰落定）；
  `NeuAvatar` 新增 `emojiMaxScale` 参数（默认 1f 其它调用点不变），编辑页传 1.42f，
  框内"挤压换头像"的动效观感不变、位图不再被拉糊。
- **滚动时头像框左边不再冒 emoji**：emoji 排原来从头像框底下穿过，圆框盖不住四角。
  现在 LazyRow `drawWithContent + clipRect` **裁剪到框右缘（56dp）以外才绘制**——
  emoji 滑到框后就被"吃掉"，框左边永远干净；裁剪只管画，焦点/选中锚点（框心）不受影响。
- **支持点选**：点哪个 emoji 就把哪个 `animateScrollToItem(i, 0)` 弹回框位
  （contentPadding 6dp + 半格 22dp 正好让它的中心停进锚点）。无 ripple，与输入区约定一致。
- **保存必中**：原来选中值只在"滚动进行中"经 `snapshotFlow` 同步（`isScrollInProgress` 门槛），
  停稳的最后一帧可能漏。现在：初始定位完成后放行同步（`anchorReady`，避免首帧布局把框位
  emoji 盖到 draft 上丢原头像）+ 保存时按框位**兜底重算**（`emojiAtAnchor()`），
  "滚到框上就点保存"也不会丢。

### 二、完成任务不再向应用助手发打卡回执（用户点名）
- `TaskActions.toggleTask` 写"✅ 已完成打卡：…"之前先查目标人设（`MENTOR_PERSONA_ID`），
  **是内置应用助手（`isBuiltIn`）就跳过**——使用手册的会话不再被打卡消息刷屏；
  其他人设会话维持原行为（回执照写、进 LLM 历史）。

### 三、数据页 4 卡去掉弹条出场（用户点名）
- ①本周回顾 / ②今日建议 / ④AI 洞察 三处 `staggerEnter` 包裹整组撤掉，卡片静态出现；
- 7 天格子不再逐个弹出（`DayChip` 的 appear 动画删）、今日建议卡不再上浮淡入；
- **保留**：本周进度条扫出（sweep，切页重播）与"点格子展开当日明细"的过渡动画
  （前者是数据动效、后者是交互反馈，均非出场动画）。

### 四、金额不再跳动（用户点名"记账本的金额也不需要跳动了"）
- 全部 `RollingAmountText` 调用点改回**静态 Text**：记账本页现金余额卡（40sp）、
  收入/支出/结余三栏（17sp）、记一笔弹层的现金余额预览（12sp）、数据页记账本入口卡（18sp）。
- 数据页的 `ledgerRollKey`（切页重滚机制）与 `RollingAmountText` import 一并拆除；
  组件定义保留在 `ui/common/RollingAmount.kt`（暂无调用点，将来要用再接）。

### 五、底栏黑胶囊拖动方向修正（用户点名"不跟手像是搞反方向了"）
- 根因：拖动位移取了负（`raw = -total / cellPx`，沿用了"手指右移 = 往回翻"的内容区 Pager 语义），
  黑块移动方向与手指相反。
- 现在：`raw = total / cellPx` —— **手指往哪边拖，黑块就往哪边走**（右拖 = 黑块右移 =
  滑向右边的 tab），"跨满一格翻一页 + 弹簧 Q 弹"机制原样保留。

---

## v0.56.10（versionCode 131）

**真机反馈后的三条返工：底栏横滑彻底重做 / emoji 放大镜改成"滚进头像框" /
记账本金额滚动去掉延迟、改回 620ms、去掉错峰入场。**

### 一、底栏横滑重做（真机反馈"拖动切页还是没实现，还丧失了 Q 弹感"）
- v0.56.9 出货版用 `dispatchRawDelta` + 黑块连续位置，两处都错了：
  `dispatchRawDelta` **只在 Pager 自己的滚动流程里有效**，外面调等于没调（拖动没反应）；
  黑块用 `currentPage + currentPageOffsetFraction` 连续跟随会把弹簧**过冲吃掉**（Q 弹感没了）。
- 现在：`detectHorizontalDragGestures` 自己累计位移，**每跨过一格**（`Float.toInt()` 向零取整）
  就 `animateScrollToPage` 翻一页（弹簧过冲原样保留）；黑块回到**整数页号的弹簧位置**，
  拖动时**叠加**一段跟手残余位移（`(raw - crossed) * cellPx`，松手弹回 0）。

### 二、emoji 头像：焦点 = 头像框本身（用户三连纠正后定稿）
- **排上的 emoji 不再模糊、不画圆环**（用户点名"其他的 emoji 也不要模糊"），
  只保留大小 + 透明度渐变；"挤进框"的挤压动画只发生在左边的头像框里（`NeuAvatar` emojiScaleX/Y）。
- **焦点锚点 = 头像框自己的中心**（用户原话"焦点就是头像框里面的头像"）：
  emoji 排**从头像框底下穿过**（LazyRow 铺满整行、头像框画在上层），
  谁滑到框位上谁就是 focus=1、谁被选中 —— "滚到头像框里面的就代表选择了"。
  起始 contentPadding 让静止时选中那个的中心正好落在框心。
  ⚠️ 中间版"焦点在排最左 + 半格"是折中，被用户纠正；再往前"排正中 + 圆环 + 模糊"更是理解错。
- 选中判据与焦点**同一个锚点**（框心），"最大的那个"和"进框的那个"永远是同一个。

### 三、记账本金额（真机反馈"延迟了观感非常不好，改得跟详细数据一样"）
- **去掉 90ms 延迟**：数据页一可见就翻。
- **时长 520 → 620ms**（用户点名"改成 620ms"），与记账本页现金余额卡（40sp）同一档。
- **`LedgerEntryCard` 不再套 `staggerEnter` 错峰入场**（用户点名"不需要错峰入场动画"）——
  金额滚动本身就是这一卡的全部动效，再叠一层入场只会互相抢帧。
  前几版的坑（v0.56.5 visible 当 key 没生效 / v0.56.7 重挂载"卡着跳" / v0.56.8 不传永不跳）
  见 v0.56.8 条目，别走回头路。

---

## v0.56.9（versionCode 130）

**底栏可以横着滑切页（初版）/ 编辑人设的 emoji 头像改成循环 + 放大镜效果并扩充头像 /
数据页「记账本」金额改成切到数据页也会跳（初版）。**
（⚠️ 本版真机验证不达标，横滑与放大镜的**返工见 v0.56.10**；以下记录的是出货时的状态。）

### 一、底栏可以横着滑（用户点名"那个黑色块可以滑动切换屏幕"）
- 手指在底栏上**横向拖动**：黑块跟手，跨格翻页。
  ⚠️ 出货版走的是 `dispatchRawDelta` + 黑块连续位置 —— 真机上**拖动没生效**、
  且 Q 弹感丢失，v0.56.10 已彻底重做（见上）。

### 二、编辑人设的 emoji 头像
- **循环滑动**：改成 `Int.MAX_VALUE` 个格子的惰性列表（`index % size` 取真身，
  起点取 size 的整数倍保证对齐），左右都能一直滑，不会再滑到头就停；
  进来时自动对齐到当前正在用的那个头像。
- **放大镜效果（初版，画在 emoji 排上）**：出货版在排的中间画圆环镜框 + 边缘模糊 + 焦点在正中；
  头像框里的 emoji 以挤压动画换上（横向 1.42→1.0、纵向 1.16→1.0，弹簧过冲）——
  `NeuAvatar` 新增 `emojiScaleX/Y`（只缩放 emoji，圆底和环不动），默认 1f，其它调用点不变。
  ⚠️ 真机反馈"放大镜应该是左边那个头像框、其他 emoji 不要模糊"，v0.56.10 已重做。
- **头像加了一批**（用户点名"外星人之类的"）：外星人 👽、怪物 👾、机器人 🤖、幽灵 👻、
  妖怪 👹👺、骷髅 💀、小丑 🤡、天使 👼、巫师 🧙、精灵 🧚、美人鱼 🧜、精灵 🧞、僵尸 🧟、
  忍者 🥷、超级英雄 🦸🦹、侦探 🕵️、精灵 🧝、吸血鬼 🧛、宇航员 👨‍🚀👩‍🚀、歌手 🧑‍🎤、
  画家 🧑‍🎨、国王 🤴、王后 👸、狐狸 🦊、猫 🐱、熊猫 🐼、独角兽 🦄。

### 三、数据页「记账本」金额：切到数据页也跳（初版）
- 出货版：滚法照搬记账本页（从 0 滚到余额），触发由外部 `ledgerRollKey` 给，
  **延后 90ms** 启动、时长 520ms —— 真机反馈"还是不行、延迟观感非常不好"，
  v0.56.10 已去掉延迟并改回 620ms。
- 前几版各自栽在哪（别再走回头路）：
  - v0.56.5 直接把"本页可见"当 `restartKey` → **没生效**（Pager 预组合里被跳过，等于没改）；
  - v0.56.7 改成 `key(restartKey)` 重挂载 → 生效了，但**在切页那一帧就开滚**，
    和页面切换、错峰入场抢同一帧 → 用户形容"卡着在那跳"；
  - v0.56.8 干脆不传 → 只在屏外播过那一次，于是**切页永远不跳**（只有从详情页返回才跳）。
- `LedgerEntryCard` 新增 `rollKey` 参数（默认 `Unit` = 不重播），记账本页那处不受影响。

---

## v0.56.8（versionCode 129）

**主动电话只留「几天没聊」+ 展开箭头 / 用量挪到模型卡片上 / 记账本卡片金额照搬记账本页 /
主动关心改「随机时间 + 按角色一套 + 条数是上限」/ 消息页不做搜索、设置页只留常驻放大镜。**

### 〇、主动关心（第二批，`ProactiveChat` + `AlarmScheduler` + 会话设置弹层）
- **不再固定每晚 20:30**：每个角色各有一个**时间段**（起–止，默认 20:00–23:00），
  调度在**这个角色自己的窗口**里**随机取时刻**；发完再在剩下的窗口里随机取下一次，
  窗口过完（或今天的计划条数发完）就排到明天 —— 每天什么时候来是随机的，不是准点打卡。
  `ProactiveChat.nextFireAt(...)` 是纯函数（`now`/`random` 都作参数），跨零点的窗口也支持。
- **每天最多几条 = 上限，不是定额**（什么数字都能填，默认 1）：当天第一次要发时，
  在 `1..上限` 里**随机摇一个数**当今天的计划，当天就按这个数发完，不超过上限就行
  （设 5 条，今天可能只来 2 条）。计数按角色存 `day:已发:计划`（老数据只有 `day` 仍按 1 条读）。
- **每个角色各一套**时间段与条数（原来是一份全局配置）：于是改成**每个角色一颗闹钟**
  （`PROACTIVE_PERSONA_BASE + id % 10000`，PendingIntent 上带人设 id），
  谁的时间段到了就发谁的；升级前那条**全局**闹钟在重排时主动取消。
- **结合当前时间**：提示词里带上「现在是 HH:mm（早上/中午/下午/晚上/深夜）」并要求贴合时间说话。
- **开关挪进弹层**：会话设置的「人设主动关心」行点开是弹层（跟「模型选择」同一个底部弹层容器，
  从底下升上来）—— 这个角色的开关 + **这个角色的**时间段 + **这个角色的**每天最多几条；
  保存后立刻按这个角色的新窗口重排；开关一关就把它的闹钟摘掉。

### 〇之二、搜索
- **消息页不做搜索**（用户点名）：列表顶部那条"搜索行"与顶栏**放大镜整个去掉**，
  `SearchOverlay` / `SearchResultUi` / `SearchResultRow` / `runSearch` 一并删除 —— 进来就是干净的一列会话。
- **设置页**：删掉搜索行，放大镜改为**常驻**，点一下直接聚焦 + 弹键盘（原来是"点一下才变成可输入的框，还要再点一次"）。
- `SettingsSearchField` 补 `focusRequester` 参数；`SettingsLineRow` 的**按压灰条整个去掉**
  （用户点名"不要那个点击之后的灰色条"，只留箭头按压时轻微右移那点动线）。

### 一、电话弹层
- 「主动电话」的详细设置**删掉「每天几点检查」**：只剩**几天没聊就主动打**（1–30 天，自己填）。
  检查时刻沿用该角色存着的值（默认 21:00），每天固定检查一次。
- 两行标题旁各加一颗**展开箭头**（收起朝右、展开朝下，90° 旋转带 180ms 过渡）——
  之前只有"标题 + 开关"，看不出点标题能展开。
- 两行的展开/收起**不再画点击后的灰色条**（`indication = null`，不画 ripple），
  与项目其它入口（相机页、底栏）同一口径。

### 二、API 用量挪到模型卡片上
- 不再单独列一块"本月 API 用量"：改成**每个模型卡片右侧**直接显示这个模型的用量，
  文字「**共消耗 X tokens**」，字体沿用行内原来的小字（12sp MutedText）；
  没消耗过就不显示这一句。用量仍按模型分别累计（`usage_by_model`，v0.56.7 起）。

### 三、数据页「记账本」卡片金额：照搬记账本页
- **不再传 `restartKey`**：之前两版"强制重播"（v0.56.5 的 `restartKey = 本页可见`、
  v0.56.7 的 `key(restartKey)` 重挂载）都会在**每次切到数据页时先把数字归零再滚一遍**，
  用户反馈"还是生硬"。现在与 `LedgerScreen.CashCard` 同一个组件、同样的参数、
  同样的触发时机（**进页首次 + 余额变化**才滚）。
- `LedgerEntryCard` 相应去掉 `visible` 参数（本周回顾那条仍在用 `visible`，未动）。

---

## v0.56.7（versionCode 128）

**电话更名「主动电话」并支持「X 天未聊天则主动拨打」/ 按住说话按屏幕半区判档 /
用量按模型分别列 / 记账本卡片金额重播改成"重新挂载"。**

### 一、电话：更名 + 点标题展开 + 空闲触发回来
- 「允许主动打电话」**更名为「主动电话」**；电话弹层两行**点标题才展开详细设置**，
  页内**不再有小标题/说明小字**。
- 「主动电话」展开后 = **几天没聊就主动打**（自己填 1–30 天，默认 3）+ **每天几点检查**
  （默认 21:00）：到点检查一次，距上次跟这个人设聊天够了那么多天就主动打过来
  （静默时段 22:30–08:00 不打、每人设每天最多一次）。
  → 上一版去掉的那条规则闹钟**恢复**：`AiCall.rearm` 与 `applyCallRule` 都重新排它。
- 「定时电话」展开后 = 一条条定时电话（**每天几点打给你 + 接通后说什么**），
  与「定时消息」同一套配法（可多条、可单独停用/编辑/删除）。

### 二、按住说话：档位改成按屏幕位置划区
- 原来按"相对按下点的 ±60dp"判档：宽屏要滑很远、窄屏一抖就换档（用户点名"灵敏度"）。
  现在按**手指在屏幕上的绝对位置**划区：**左半区 = 取消、右半区 = 转文字、
  正中间那条灰带 = 录音中**（灰带宽度与蒙层里两块环带弧的中央缺口一致），松手按当前档确认。
- 三态切换更顺：手指动过 `touchSlop` 才开始判档（避免按住偏左一按就取消）、
  换档补一记轻震动、整条的缩放/底色/文字色全部走 120–160ms 过渡。

### 三、API 用量改成按模型分别列
- 新增 `usage_by_model`（`月键\t模型\ttokens`）：六个用量记账点（对话 / 提炼 / 周报 /
  每日建议 / Agent / 识图）现在都带上 `ApiConfig.model`，各记各的账。
- 「对话模型 / 生图模型」页底部由一行总额改成**按模型逐行列出各自 tokens + 合计**。

### 四、音色模块去掉小标题
- 「音色」页顶部那句"点一行 = 设为默认音色…"与音色弹层里那句"选这个 AI 用谁的声音…"
  **一并删掉**（与电话弹层同一口径：页内不留说明性小字）。

### 五、待发送附件条去掉提示文字
- 相册 / 文件挂在输入框上方的那条，不再显示"可再写一句说明一起发…"这类解释文字，
  只留缩略图/文件名 + ✕。

### 六、数据页「记账本」卡片金额（换实现）
- 金额滚动**改回"重新挂载"触发**（`key(restartKey)` + 内部 `LaunchedEffect(Unit)` 播种），
  与记账本页那条金额走**完全同一段代码、同一套时序** —— 之前数据页那条只在自身状态上打转，
  重组时机一变就可能整段被跳过（用户反馈"还是卡/不跳"）。
  顺带撤掉上一版加的 90ms 起步延迟（既然与"里面那条"同构，就不再需要错开）。

---

## v0.56.6（versionCode 127）

**电话改「定时电话」/ 接听延迟自己填秒数 / 音色弹层不再卡一下 / 相册·文件先挂到输入框上方。**

### 一、电话：保留总闸 + 新增「定时电话」（用户点名）
- 「电话」弹层结构改成两块：
  ① **允许主动打电话**（保留，作为"TA 能不能主动打"的总闸）；
  ② **定时电话**开关 + 一条条定时电话 —— 点「＋ 添加定时电话」填**每天几点打给你**与
  **接通后说什么**，可多条、每条能单独停用/编辑/删除，与「定时消息」完全同一套配法。
- **去掉「多久没聊才打」那种触发方式**：不再有"到点检查 + 空闲天数"的规则闹钟，
  改由用户按**具体时间点**自己排（`AiCall.rearm` 也改成排这些定时电话）。
- 实现：`ScheduledMessage` 加 `kind`（`message` / `call`，同一条存储 + 同一套闹钟与表单）；
  `AiCallRule` 加 `scheduledEnabled`；`ScheduledMessageReceiver` 按 kind 分流到
  `ScheduledMessageSender` 或 `AiCall.ring`；`AiCall.ring` 新增
  `instructionOverride` / `byExplicitSchedule` —— 定时电话**只看那两个开关**，
  不看静默时段、不占"每天一次"的名额（时间是他自己定的）。

### 二、接听延迟：改成自己填秒数
- 原来是一排固定档位（立即 / 2 / 3 / 5 / 10 / 15 / 30 秒），现在改成**数字输入框**：
  填多少就是多少（0 = 立即接），**上限放到 600 秒**，填好点「保存」。
- `NeuTextField` 补 `keyboardType` 参数（这一栏用数字键盘）；`setAiAnswerDelaySeconds`
  的钳制从 0–30 放宽到 0–600。

### 三、音色弹层不再"卡一下 + 像弹两次"
- 弹层原来**自己**在 `LaunchedEffect` 里拉数据（配置 → 克隆音色还要一个网络往返），
  于是"弹层先出现、名单后补进来"，内容跳一截。现在改成**纯展示**：
  会话设置页**在打开之前**就把名单备好（克隆音色读本机缓存，毫秒级）再传进去。

### 四、模型弹层去掉「跟随全局」
- 「本会话的 AI 接口」弹层删掉顶部那行「跟随全局」：列表里当前生效的那条直接打勾，
  **点哪条就用哪条**（没单独选过时勾的是全局默认那条）。

### 五、相册 / 文件：选中后先停在输入框上方
- 加号面板里的**相册 / 文件**选中后**不再立刻发出去**：先挂在输入栏上方的一条待发送条上
  （图片带缩略图、文件带文件名），右侧 ✕ 丢掉；可以再写一句说明一起发，**也可以直接点发送**。
- `sendFile` 补 `caption` 参数（用户写的那句话在前、抽出的正文在后），并改为发送后清空输入框。
- 拍摄（App 内相机）保持原样（拍完先确认再发）。

### 六、数据页「记账本」卡片金额（补充排查）
- 切 tab 进数据页时的重播机制在 v0.56.5 已接上（`RollingAmountText.restartKey` = 本页可见）；
  这一版**再把起步延后 90ms**，避免滚动过程被翻页动画盖住而"看着没跳"。
  ⚠️ 若真机仍不跳，需要按"卡片进入可视区"来触发（不再依赖 Pager 的可见信号）。

---

## v0.56.5（versionCode 126）

**真机反馈一批七项：数据页金额动画不重播 / 接听延迟独立成项 / 联网搜索可自测 + 修「联网失效」/
通话页头像位置与大小统一 / 克隆音色不再最后才冒出来 / 聊天页那条浅灰线（第 6 项未定位，源码未改）。**

### 一、数据页 · 记账本卡片金额不跳（bug）
- **症状**：从别的 tab 切到数据页，卡片上的金额一动不动；只有从记账本 push 页返回时才跳。
- **根因**：金额滚动靠"自己被组合"触发，而四个 tab 在 Pager 里**同时组合**，动画在屏外就播完了。
- **修法**：`RollingAmountText` 新增 `restartKey`（值一变就重新从 0 滚），数据页把
  `visible`（`pagerState.settledPage == 1`）当信号喂进去 —— 切 tab 进来 / 从记账本返回**两条路都跳**。
  与本周回顾、整页错峰入场同一套思路：动画不能靠自身"被组合"判断可见性。

### 二、通话行为 · 「接听延迟」独立成项
- 从「语音通话铃声」页里**拎出来**，成为「通话行为」页上与它**并排的一张卡**
  （设置 → 通话行为 → 接听延迟）；那一页直接列出 立即 / 2 / 3 / 5 / 10 / 15 / 30 秒，点一行即生效。
- 新增路由 `call_answer_delay`；铃声页只管铃声；「通话行为」卡副标题改成
  「语音通话铃声 · 接听延迟」，搜索别名同步。

### 三、联网搜索 · 可自测 + 修「配好 Key 也不联网」（bug）
- 配置页新增**「测试搜索」**：用当前草稿 Key 真搜一次「今天的新闻」，就地给出结果 ——
  成功显示条数与耗时，失败把服务端原话（HTTP 状态 / Key 不对 / 额度用完）显示出来。
  为此 `WebSearchClient` 拆出 `searchOrThrow`（失败抛异常带原文），原 `search` 仍吞异常返回空表。
- **修「联网失效」**：`ChatViewModel` 里查询串原来取 `_uiState.value.input`，而 `send()` 在调用它
  **之前**就把输入框清空了 → 查询恒为空 → **整段联网注入从来没跑过**。改为取**最后一条用户消息**。

### 四、通话页 · 头像位置与大小统一
- **症状**：角色"思考中"与"角色听我说话"两态下，头像的位置和大小都不一样（用户点名）。
- **根因**：脉冲环容器**尺寸跟着环走** —— 环只在说话时组合、且自带 `size(196.dp)`，
  于是整块从 124dp 长到 196dp，居中的头像被整体**往下推 36dp**。
  大小差异则来自"只有说话时才呼吸"（0.985↔1.035），其余状态钉在 1.0。
- **修法**：画布尺寸钉死在 196dp（环只决定画不画），头像缩放全程同一套呼吸曲线；
  呼吸值只在 `graphicsLayer` 的 lambda 里读（延迟读），每帧只刷渲染层、不触发重组。

### 五、音色 · 克隆音色不再"最后才冒出来"
- 克隆那几条只有一个来源（服务商 `/audio/voice/list` 的网络往返），于是预置音色已经在了、
  克隆要等一个来回才"啪"地补进来。现在拆两步：**先用本机缓存把整份名单一次渲染齐**，
  再后台拉一次覆盖并落回缓存（新增 `tts_cloned_cache`，按服务商各存一份）。

### 六、聊天页那条浅灰线（**未定位，源码未改**）
- 用户在聊天页看到一条**浅灰的横线**（"有时候有有时候没有、一般给他指令时出现"），
  点名位置在**他自己那条黑气泡的左上方**。
- 已用截图逐像素量化出该页**两条常驻横线**，但用户确认**都不是**他说的那条：
  - y≈676（x 61→662）：上面那条 AI 气泡的底边，位于黑气泡上方 58px；
  - y≈883（x 61→661）：下面那条 AI 气泡的顶边，位于黑气泡下方 57px。
  - 另：顶栏下沿那条全屏线（y≈216）用户明确**要求保留**。
- 期间试过两版改法（①AI 气泡改浅灰面+去描边 ②`neuSurface` 加 `stroke=false` 只去描边），
  用户均判定"不是这条"，**两版都已整块回退**，`Neu.kt` / `MarkdownText.kt` / 气泡三处
  均已回到改动前。
- **下一步（缺一张图）**：需要那条线**出现瞬间的整屏截图（不裁剪）**——
  它是间歇出现的，裁剪图会把线切掉（用户发来的裁剪图逐像素扫过：气泡左侧全白，
  线不在图内），所以必须先拿到含线的整屏图再动手。**在定位到具体绘制元素前不再改任何渲染代码。**

---

## v0.56.4（versionCode 125）

**真机反馈第四次返工：环带块端头圆角化 + 左右镜像对称；数据页卡片金额直接换用记账本页同款滚动。**

### 一、按住说话环带块
- **端头圆角**：不再手拼"外弧 + 内弧 + 闭合"的环带路径（端头是平切的直角），
  改为**一段中径弧线 + 圆帽描边**（`StrokeCap.Round`）——两端天然是半圆角；
  圆帽会沿切线多伸出半个带宽，两端角度各回收到位补偿；
- **左右对称**：旧实现左块外端在 7% 宽、右块外端在 98% 宽，本就不镜像；
  现在两块外端都收在 **47% 半宽**、内端缺口都是 **5% 半宽**，严格镜像；
- 静息态描边：宽条发丝线 + 窄 2dp 面色条叠画，边缘留出 1dp 描边圈。

### 二、数据页 · 记账本入口卡金额
- **删掉自制的老虎机滚轮**（`SlotMachineAmount`，v0.56.0~0.56.3 三轮返工的实现全弃）；
- 直接换用与记账本页现金余额卡**同款** `RollingAmountText`（里程表式按位滚动、
  首次进页也从 0 滚上来、从左到右错峰落定）——两处金额同一套动效语言；
- 墨色、字号、字重不变；与"本周回顾"的同步停表约束随之取消（新动效自带进页触发）。

## v0.56.3（versionCode 124）

**真机反馈第三次返工：修滚轮全滚出 0 的实现错误、降滚速；环带块加大加圆润；气泡压扁拉长、波形平滑。**

### 一、数据页 · 老虎机金额
- **修"全是 000"**：v0.56.2 只量了 "0" 的字形尺寸，却把同一个 "0" 的排版结果画在了
  滚轮每一行上——整根滚轮全是 0，落位自然也是 0。改为每行用对应数字字符串直绘
  （`drawText(textMeasurer, text, …)`，TextMeasurer 自带缓存）；
- **降滚速**：空转两圈（EaseOutExpo 前段极快，视觉上"唰一下"）改为一整圈，总时长仍
  1000ms，与本周回顾同步停表。

### 二、按住说话蒙层
- **环带弧块加大加圆润**：矢高 5%→7.5%（顶边弧更弯）、环带厚 26→34dp、画布高 44→64dp、
  左右块外端各往外扩（7% / 98% 宽处）、标签 15sp；
- 右块标签改「**转文字**」（去掉"滑到这里"）；
- **气泡拉长压扁**：宽 168→200dp、纵向内边距 16→10dp、圆角 16→14dp、波形 150×30→176×20dp、
  秒数 11sp；
- **波形美化**：条数 26→32、条宽 2dp、三口加权均值平滑（相邻三根取均值，消除采样抖动，
  从"乱跳"变"流动"）。

## v0.56.2（versionCode 123）

**真机反馈第二次返工：金额滚轮彻底重写为 Canvas 直绘；按住说话选择区改为截图中的环带弧块。**

### 一、数据页 · 老虎机金额
- v0.56.1 的「裁剪盒 + graphicsLayer 位移」方案真机仍整块空白且无法复现错因 → **推倒重写**：
  每一位数字一个 12×22dp 的 `Canvas`，用 `clipRect` 裁剪 + `drawText` 直绘，每帧只画
  视窗内相交的 1–2 行数字——无嵌套图层、无布局位移，渲染行为在所有设备上确定；
- 滚动节奏不变：两整圈空转后落位，与本周回顾同信号源、同规格（1000ms EaseOutExpo）同步停表；
- 数字墨色（¥ / 负号 / 小数点同色不滚动），仅数据页入口卡。

### 二、按住说话蒙层 · 环带弧块
- 用户指出"扇形"不是半圆：照截图把选择区改成**环带弧块**——两段顶边为大弧（与底部
  "松开发送"大弧同族曲率）的环带、中央留缺口，文字沿切线微微倾斜；
- 标签照截图：「取消」「滑到这里 转文字」；黑白配色不变，拖进哪块哪块点亮（160ms 过渡）；
- 判定逻辑不动（左右滑 ±60dp）。

## v0.56.1（versionCode 122）

**真机反馈返工：记账金额去彩色 + 修空白 bug；按住说话回黑白 + 扇形；会话设置页统一行 / 调顺序。**

### 一、数据页 · 老虎机金额
- 数字颜色**恢复墨色**（用户不要彩色；¥ / 负号 / 小数点同色不变，滚轮动效保留）；
- **修"卡片空白" bug**：滚轮外框此前用了居中对齐——滚柱列高是外框的几十倍，居中自带巨大负偏移，
  动画落位时数字全被推出裁剪区。改为顶对齐（TopStart），translationY 从 0 起算。

### 二、按住说话蒙层
- **配色回黑白**：气泡回到强调色（浅色黑 / 深色白），墨迹 `AccentInk`，不用品牌绿；
- 选择区按用户点名**改回两颗扇形（半圆）**：「取消」「转文字」，拖进哪颗哪颗点亮（160ms 过渡 + 放大 6%），
  判定逻辑不动；底部"松开发送"大弧保留。

### 三、会话设置页
- 所有行**统一单行、小标题全部去掉**（行高完全一致）；
- **带开关的行（联网搜索 / 人设主动关心）与「查看聊天记录」挪到列表底部**；
  其余入口顺序不变（编辑人设 → 电话 → 立绘 → Live 2D → 聊天背景 → 音色 → 模型 → 铃声 → 定时消息 → 底部三行）。

## v0.56.0（versionCode 121）

**一批 UI / 动效优化：对话条间距、按住说话蒙层（照微信截图重做）、会话设置页去分组加大字体、数据页记账金额老虎机滚轮。**

### 一、对话页
- **对话条间距改微信式**：列表基础间距 2dp → 6dp；换说话人时（条目既有 `gapBefore`）顶部再拉开 12dp
  （合计 ≈18dp），同一人连发只吃基础间距，日期头自带内边距不变——整页有了微信式的呼吸感。

### 二、按住说话蒙层（照用户给的微信截图重做，判定逻辑不动）
- **绿气泡**：录音气泡从黑底换**品牌绿**（`BrandGreen`，设计系统允许品牌色作内容点缀），
  墨迹恒定黑（品牌绿深浅两版同值）；波形加宽到 150dp，气泡加宽到 168dp；
- **选择区**：原两颗扇形（半圆）换成**两块大圆角灰块**「取消」「滑到这里 转文字」（42% / 58% 宽，
  62dp 高、20dp 圆角），拖进哪块哪块点亮（品牌绿底黑字，160ms 过渡）；
- **底部大弧**：新增贴屏幕下沿的**半椭圆穹顶**（Canvas 上半椭圆，`NeuTrack` 面），
  「松开 发送 / 取消 / 转文字」字样随档位变化（16sp Medium）；
- 进出场沿用 v0.55.0：蒙层淡入淡出、气泡 0.92 弹簧长出、滑「取消」档气泡先缩 6%；
  左右滑 ±60dp 的档位判定仍在 `VoiceHoldBar`，蒙层只负责显示。

### 三、会话设置页（三点 → 整页）
- **整页取消分组标题**（用户拍板"不要分组"），恢复一张平铺列表，发丝线一直分隔到底；
- 行标题统一加大到 **17sp**（`SettingsLineRow` 新增 `titleSize` 参数，其它设置页默认 15sp 不受影响），
  **行高不加高**；
- 副标题只留有信息量的：删掉「音色 · 这个角色说话的声音」「模型 · 本会话用哪个 AI 接口」这类说明性小字
  与开关行的「已开启/已关闭」（开关本身已表达）；**入口顺序一行未动**。

### 四、数据页 · 记账金额老虎机
- 记账本入口卡的金额改成**老虎机滚轮**：每一位数字是一根竖向滚轮，空转两整圈后落到目标位；
- **与本周回顾同步**：滚轮进度与回顾进度条共用同一信号源（pager `settledPage` 的 `visible`）
  与同一规格（1000ms EaseOutExpo），切到数据页同时起跑、一起停在真实数字；
- 数字按位着品牌色（绿→黄→紫→蓝→橙循环），¥ / 负号 / 小数点保持墨色不滚动；
- **只动数据页入口卡**，点进记账本页的金额保持普通样式。

## v0.55.0（versionCode 120）

**前端动效 / 视觉 / 交互打磨：对话页、会话设置页、通话记录卡、长按消息菜单、
按住说话、通话页六处重做；配色 / 圆角 / 间距 / 字体 / 图标语言一律沿用现有令牌，未引入新视觉体系。**

### 一、对话页（ChatScreen）
- **滚动性能：去掉每条消息逐帧的 state 写入**。原实现给每个条目 `remember { mutableStateOf<Rect?>() }`
  并在 `onGloballyPositioned` 里写它 —— 而该回调滚动时**每帧**都触发，于是每个可见条目每帧重组一次。
  改成一个普通 `HashMap<Any, Rect>`（坐标只在长按那一刻读，不需要可观测），滚动期间零重组。
- **布局层次**：列表往上一翻，顶栏下沿淡入一条发丝线（阈值布尔驱动，跨过 8dp 才翻转一次，
  不会每帧重组），顶栏与消息区有了明确分界。
- 生成中的三点气泡补上重排弹簧（进出不再硬跳）。

### 二、会话设置页（三点 → 整页，v0.54.4 的形态不动）
- **入口顺序一行未动**，只切成 5 组（角色 / 主动联系 / 形象 / 声音与模型 / 定时消息）并各配 caption；
- 整页内容按序号**错峰入场**（淡入 + 上滑 10dp），与 push 滑入叠成"铺开"而不是"弹出"；
- 行按压 = 整行铺灰（90ms 上 / 220ms 退）+ 箭头右移 2dp；每组最后一行不再画"悬空的"发丝线；
- 弹层内容也跟着抬一档（`NeuSheetScaffold` 统一生效）。

### 三、语音通话记录卡重绘
- 从「📞 + 一行灰字」的小药丸改成**四层信息的真卡片**：状态徽标（打通 / 未接通两个图标）
  + 标题「语音通话」+ 方向标记（↑ 呼出 / ↓ 呼入）+ 副题时长（未接通走危险色写「未接通」）
  + 右侧箭头点开看转写。方向沿用 `isFromUser` 既有约定，未新增字段。

### 四、长按消息菜单
- **弹出动画**：整块从 0.88 弹簧放大 + 120ms 淡入。刻意不用 `AnimatedVisibility` ——
  它是"可见才参与布局"，首帧尺寸为 0 会让 `PopupPositionProvider` 算错位置闪一下；
  改用 `graphicsLayer`，布局尺寸恒定、定位第一帧就准。
- **分组与层级**：「查看类开关」｜「复制 / 多选」｜「删除」，组间 1dp 竖发丝线；删除走危险色，
  开关展开态整格铺凹陷灰底 + 图标上强调色。
- **反馈**：36dp 方形热区 + 按压缩放 + 点中补一记轻震动。

### 五、按住说话
- 「按住 说话」条：按住时整条微缩 1.5% + 底色白转灰，条内挂**实时迷你波形**（录音到底录进去没有，
  光靠"松开 发送"四个字看不出来）；滑到取消 / 转文字档位文字转危险色。
- 录音蒙层：进出淡入淡出、气泡从 0.92 弹簧长出；**滑到「取消」档时气泡先缩一点**（松手前就预告
  "这段要丢掉了"）；扇形区拖进去放大 6% + 底色过渡。
- 波形改成 **Canvas 单节点**绘制（原来 26 个 `Box` 每 60ms 重新布局一遍）。

### 六、通话页
- 状态收进**凹陷灰胶囊 + 一颗会呼吸的状态点**：呼吸 = 通话进行中，静止 = 已结束 / 未接通
  （原来只是一行灰字，两种状态长得一样）。
- 新增**通话计时**（打通才走字；纯 UI 层，不碰引擎与仓库）。
- 说话时头像外扩**两圈同心脉冲环**（Canvas 单节点 + 无限循环，不产生重组），声源方向感立得住。
- 按钮补**禁用态淡出**与**静音生效内圈**。
- 进场编排：头像块 → 字幕区 → 操作钮三块依次抬起。

### 七、记账本金额：数字滚动过渡（用户点名）
- 新增 `ui/common/RollingAmount.kt` 的 `RollingAmountText`：金额按位**里程表式滚动** ——
  数字变大则新字从下方滚上来、变小则反向，每位独立、从左到右错峰落定。
- 每位套一个固定高度的 `clipToBounds` 窗口：滚进滚出的那半截被裁掉，才有"从数字窗口里翻过去"的味道。
- **进页面也滚**：`AnimatedContent` 只在 targetState 变化时播动画，所以先摆一串等长的 0
  （符号与小数点原位保留），下一帧滚到真值 —— 进页面那一刻"金额算出来了"的过程被做出来。
- 用在现金余额 Hero 卡（40sp）、收入 / 支出 / 结余三格（17sp）、记一笔弹层里的余额小字（12sp）。
- ⚠️ 流水列表里每一行的金额**不用**滚动：列表复用会让它们在滚动时反复触发，满屏数字乱跳反而更吵。

### 八、其余页面排查清单（9 条全做）
1. **会话列表 `ConversationRow` 不再逐帧重组**：原来"记录手指位置"的 `pointerInput` 把坐标写进
   `mutableStateOf`，而滑动时一帧好几个指针事件 → 每个可见行都在按帧重组。改成普通 `FloatArray`，
   只在长按那一刻读一次。
2. **条目淡入延迟压缩**：会话列表 `340ms + 最多 320ms 延迟`、任务页 10 处 `340ms + 最多 320ms`
   → 统一改成 `200ms + 最多 120ms`。原值在**滚动**时同样生效，快滑就是"一片条目陆续浮现"
   （对话页 v0.41.4 正是因此把淡入整个关掉）。
3. **六处惰性列表补 `contentType`**：会话列表（线 / 行 / 搜索结果）、记账本（现金卡 / 统计卡 /
   分组标题 / 空态 / 流水行）、AI 洞察（洞察 / 周报）、人设管理、表情面板、通话转写 ——
   以前只给了 key，形状不同的条目互相复用组合槽。
4. **`NeuSwitch` 不再每帧布局**：滑块位移从 `animateDpAsState` + `offset`（每帧重新布局）
   改成 `animateFloat` + `graphicsLayer.translationX`（纯渲染层）。开关遍布设置链。
5. **相机页去掉默认 ripple**：全应用 v0.42.1 就去了涟漪，只有相机页五个入口还在吃 Material 默认
   水波。新增私有 `Modifier.cameraPress`：`indication = null` + 按压缩放（快门 0.92，其余 0.94）。
6. **通话转写弹层不再嵌套滚动**：`NeuSheetScaffold` 的内容是 `Column(verticalScroll)`，
   里面还嵌了一个固定 420dp 高的 `LazyColumn` —— 两层都吃竖直手势，实际就是"划不动 / 整块跟着走"。
   改成直接平铺（一通电话对话量有限），每句之间加一条发丝线，并接上错峰入场。
7. **数据页四块统一错峰入场**：以前只有「本周回顾」吃 `visible` 有动画，下面三块硬出现，
   切过去是"上半屏在动、下半屏已经在那儿了"。
8. **任务页 / 我的页补动效**：任务行补按压缩放 0.975（注释里写着"反馈靠卡片按压"，
   但按压那半个从来没做）；我的页整页内容错峰入场（这一页原来一个动画都没有）。
9. **语音条声波改 Canvas**：播放中的三根竖条原来是三个 `Box`、高度绑动画值 → 每帧重新布局。
   改成单节点一次绘制。

## v0.54.7（versionCode 119）

**「电话」弹层与「定时消息」对齐（几点打过来 + 说什么 + 多久没聊天数自填）；
聊天背景改成「原生 / 自定义」二选一并且真的生效；长按消息补震动反馈。**

### 一、「电话」弹层：跟「定时消息」一个路子
- 上一版把「多久没聊」做成了固定几选一（1/3/7/30）、把时间做成了表盘 —— 用户指出正好弄反了，现在改成：
  - **几点打过来**：手填 `HH:mm`（跟「定时消息」同一个填法），默认 21:00；
  - **说什么**：这次打电话要 TA 说的内容（例：「问我今天任务完成没」），可留空 ——
    留空时仍由 TA 按档案 / 任务 / 最近聊天自己组织开场白；
  - **多久没聊才打**：**天数自己填（1–30）**，默认 3 天；填 1 就是到点必打。
- 数据层：`AiCallRule` 新增 `instruction`（含编解码，老数据缺字段时按空串读）；
  `AiCall.generateGreeting` 把它作为**硬性内容要求**排在情境材料前面（用户指定的优先于模型发挥）。

### 二、聊天背景：二选一 + 真的生效
- 点「聊天背景」行 → 底部弹层**两个选项**：「原生背景」（App 自己的底色）/「自定义」（从相册选图，已设置时显示"点此换一张"）。
- **背景真的铺上了**：这个角色的聊天页在消息列表底下渲染选中的图（`ContentScale.Crop`、跟随版心），
  不再是"只存不显"。每个角色各管各的，互不影响。
- 角色立绘 / Live 2D 仍是"只存不显"（展示方式未定）。

### 三、长按消息补震动
- 长按气泡 / 语音条 / 文件 / 通话记录卡弹菜单时统一补一记 `LongPress` 震动
  —— 收进一个 `openMessageMenu(msg, rowBounds)`，四个入口共用（原来是各写各的、都没震动）。

### 四、修复：思考过程盖住了消息气泡
- 展开思考过程时它**压在气泡上**（用户反馈"把消息条都覆盖了"）——根因是消息条目内层容器是 `Box`，
  两个兄弟节点（消息本体 + 思考过程）被 Box 叠在了一起。改成 `Column` 后思考过程正常排在**消息下方**。

### 五、手册与测试
- `AiPrompts` 手册链尾追加 v0.54.7 定点替换（电话弹层四样、聊天背景二选一且生效、长按有震动）。
- `AiPromptsTest` 新增 `v0547` 用例；修订 `v0546` 中被本次措辞取代的断言。

## v0.54.6（versionCode 118）

**会话设置页取消卡片改灰线行（电话 / 铃声 / 定时消息三个底部弹层）；长按菜单只留图标并缩小；
「思考过程」改就地展开；修复通话记录卡长按无反应。**

### 一、会话设置页：取消卡片，改用灰线分隔的行
- 整页不再用 `NeuSheetRow` 白面圆角卡片，改成新增的 `SettingsLineRow`——**一行一条，行间 1dp 发丝线**。
- 「允许主动打电话」卡片改名**「电话」**：卡片上只留状态，点开是**底部弹层**，弹层里三样 ——
  **开关（允许主动打电话）**、**什么时候打过来**（M3 时间表盘直接拨，默认 21:00，不用手输 HH:mm）、
  **多久没聊才打**（1 / 3 / 7 / 30 天）。
- 「**铃声**」行：点开是底部弹层里的**铃声列表**（跟随默认 / 系统默认 / 各条，点一条即选中），
  不再把列表摊在页面上。
- 「**定时消息**」行：点开就是**添加定时消息**的弹层；已设的一条条列在它下面（点一条改它）。
- 其余条目（编辑人设 / 查看聊天记录 / 角色立绘 / Live 2D / 聊天背景 / 音色 / 模型 / 联网 / 人设主动关心）同样改用灰线行。

### 二、所有设置链弹窗改从底部弹出
- 删掉居中的 `SettingsCardDialog`，全部改用 `NeuSheetScaffold`（ModalBottomSheet：圆角 28dp、零阴影、内容可滚动）。
  受影响的调用点：AI 接口详情、语音合成配置、手填音色 ID、定时消息编辑、会话设置三个弹层。

### 三、长按菜单瘦身 + 思考过程就地展开 + 通话记录卡可长按
- 长按消息的图标菜单**只显示图标、不再显示文字**（文字进 `contentDescription`，无障碍照样读），
  图标 20 → 17dp、内边距收紧，整体明显变小（用户反馈"比聊天记录还大"）。
- 「思考过程」由"打开底部弹层"改成**就地展开在消息下方**：字号 11sp、颜色淡（`MutedText`），
  带一行「思考过程 · 点此收起」可点击折叠；展开状态存在列表层（气泡被回收也不丢）。
- 修复「长按语音通话记录卡唤起不了菜单」：那张卡以前只绑了 `clickable`（没有长按），
  现在 `combinedClickable` 同时支持点击（看转写）与长按（弹菜单）。
- 顺手把 `VoiceBubble` / `FileBubble` 的 `combinedClickable2` 从自制的
  `composed { pointerInput { detectTapGestures } }` 换成官方 `combinedClickable`——
  前者 `composed` 已废弃，且 `pointerInput` 用 lambda 当 key 会导致每次重组重启手势检测、长按容易断。

### 四、手册与测试
- `AiPrompts` 手册链尾追加 v0.54.6 定点替换（会话设置灰线行与三个弹层、电话弹层三样、
  长按菜单只留图标、思考过程就地展开、通话记录卡长按）。
- `AiPromptsTest` 新增 `v0546` 用例；修订被取代的断言（`v0543 / v0544 / v0545`）。

## v0.54.5（versionCode 117）

**会话设置页全面卡片化；「AI 主动打电话」从设置里删除、改按角色；铃声页去说明、接听延迟改卡片选择；
修复「查看聊天记录」闪退；提示词库去掉重复标题。**

### 一、会话设置页（⋮ 整页）全面卡片化
- 「允许主动打电话 / 联网搜索 / 人设主动关心」从无卡片的裸行改成**卡片**，开关放在**卡片右侧**
  （`NeuSheetRow` 新增 `trailing` 槽；有 trailing 时不再画默认浅箭头）。
- 「添加一条」定时消息也从文字按钮改成卡片。
- 「允许主动打电话」卡片右侧多一颗箭头：点卡片/开关 = 开或关；点箭头弹出详情，改
  **每天几点（默认 21:00）**与**多久没聊才打（默认 1/3/7/30 天，默认 3 天）**。

### 二、「AI 主动打电话」从设置里删除，改按角色
- 删除「设置 → 通话行为 → AI 主动打电话」整页（`AiCallSettingsScreen.kt` 删除、路由 `AI_CALL` 移除）；
  「通话行为」页只剩**语音通话铃声**。
- **全局总开关删除**：`SettingsRepository.aiCallEnabled / setAiCallEnabled` 移除（老键只留给存量推断读），
  运行时三处门槛（`AiCall.ring` / `AiCall.rearm` / `AiCallReceiver`）改为**只看该角色的 `rule.enabled`**。
  开关、时间、阈值全部收进上一条的角色卡片里，一个人设一套、互不影响。
- 手册同步：AI 主动打电话入口、总开关措辞、全屏通知直达入口（改指「权限」页）全部改写。

### 三、语音通话铃声页去说明 + 接听延迟改卡片
- 页内**解释性小字全部删除**（免打扰提示、交互说明都不再写在页面上）。
- 「添加铃声」旁只标明**可上传的文件**：mp3 / m4a / wav / ogg 等音频，或 mp4 等视频（自动抽音轨）。
- 「接听延迟」从手填秒数的输入框改成**卡片形式**：右侧直接显示当前取值（立即接 / n 秒），
  点卡片或右侧弹出菜单选择（0 / 2 / 3 / 5 / 10 / 15 / 30 秒）。

### 四、修复「查看聊天记录」闪退
- 根因：`ChatHistoryScreen` 把 `LazyColumn` 套在 `SettingsPage` 的 `Column(verticalScroll)` 里，
  LazyColumn 被**无限高约束**测量时 Compose 直接抛异常，点「查看聊天记录」必闪退（真机反馈）。
- 改成普通 `Column` 一次排完（数据量本来有界：默认最近 50 条、搜索 SQL 自带 `LIMIT 50`），
  并留注释防止回退。

### 五、提示词库去掉重复标题
- 输入框**不再重复显示段名**：原来「应用助手提示词」「基础提示词」「档案注入说明」在屏幕上
  各出现**两遍**（段标题 + 输入框 label），「策略」也是两行；现在只留段标题/分层小标签。

### 六、手册与测试
- `AiPrompts` 手册链尾追加 v0.54.5 定点替换（主动打电话改按角色、通话行为瘦身、
  接听延迟卡片、路径清单改写）。
- `AiPromptsTest` 新增 `v0545` 用例；修订被取代的旧断言（`v0536 / v0538 / v0542 / v0544`），
  并顺手修掉 `v0542` 里对「（v0.54.2 定稿）」的失效断言。
- `UiRenderTest.render27` 补「通话行为」子页检查（只剩语音通话铃声）。

## v0.54.4（versionCode 116）

**单聊「三个点」变整页会话设置（含专属铃声 / 定时主动消息 / 主动关心）；铃声可多条并选默认；
设置页瘦身（权限改列表行、崩溃记录挪右上角）；提示词库改版按段恢复保存。**

### 一、设置主页搜索框与消息页对齐
- 顶部改成**凹陷搜索行**（点一下才进入搜索态，不再是常驻输入框）；拉到最顶部时常驻，
  **上滑离开顶部即收起、顶栏右侧冒出放大镜**（无圆圈）点它回到搜索态——与「消息」页行为一致。
- 搜索行与下方第一张卡片之间留出适当间距，不再贴在一起。

### 二、语音通话铃声：可多条 + 选默认
- 铃声**可以添加多条**（音频原样留用；视频自动抽音轨），列表里**点一条即设为默认铃声**，
  长按可删除；「系统默认」永远排第一，点它 = 恢复用系统铃声。
- 换过铃声或进页面即检查**能否播放**，播不出来在界面上标红说明（文件被删 / 本机不支持该格式），
  不静默退回系统铃声。

### 三、单聊右上角「三个点」= 整页「会话设置」
- 不再弹小菜单，改为打开**整页** `ChatSettingsScreen`（`chat_settings/{personaId}`）。
- 页内含原来那九项（编辑人设 / 查看聊天记录 / 允许主动打电话 / 角色立绘 / Live 2D / 聊天背景 /
  联网 / 音色 / 模型），并新增**专属铃声、定时主动消息、人设主动关心**——**三项都只对该角色生效**。
- 专属铃声回落链：**角色专属 → 默认铃声 → 系统默认**。

### 四、设置页移除「定时主动消息」与「人设主动关心」
- 「提醒与通知」页只剩**任务到点提醒 / 手机操作进行中通知**两个开关；
  这两项改到上一条的整页会话设置里，按角色单独设。调度仍一律排（三处自愈），
  运行期逐角色复查开关与当日去重。

### 五、权限页改一行条列表
- 去掉卡片形态与解释性文字，一行只显示**权限名 + 状态（已开启 / 未开启 · 必开|可选）+「去开启」**。

### 六、关于页崩溃记录改右上角红字入口
- 「关于」页正文不再放崩溃卡片；有崩溃时右上角出现**红色「崩溃记录」**，点开是独立整页
  `CrashLogScreen`（可选中复制、页内可清除）。

### 七、提示词库改版
- 段标题**字号加大**；去掉标题与分层标签里的**括号文字**，只保留「框架」「策略」四个字；
  去掉解释性小字，删掉「生成逻辑说明」整段。
- 底部「全部恢复默认 / 保存」两个全局按钮**删除**；改成**改动过的那一段**在其提示词下方
  才显示「恢复 / 保存」两项，没改动的段不显示（draft/persisted 双状态判定）。

### 八、手册与测试
- `AiPrompts` 手册在链尾追加 v0.54.4 定点替换（整页会话设置、设置页瘦身、铃声多条/按角色、
  崩溃记录右上角、提示词库改版、设置主页搜索行、联网入口路径）。
- `AiPromptsTest` 新增 `v0544` 用例（每条 replace 配「新措辞 contains + 旧措辞 assertFalse」双向断言），
  并同步修订 `v0530 / v0536b / v0541 / v0542` 中被本次措辞取代的断言。

## v0.54.3（versionCode 115）

**通话字幕一屏 6 行、越远越小；对话页长按消息改「微信式气泡旁菜单」并新增多选；AI 图库可删记录。**

### 一、通话字幕：一屏最多 6 行 + 越远越小
- 字幕面板可视高度封顶为**一屏 6 行文字**（约 150dp，`SubtitleViewportHeight`），
  单段超长也只占满这一屏（`maxLines = 6` 截断省略号）；整通电话的完整记录仍可上滑回看。
- 渐隐升级为**"远离了"观感**：越旧的字 `alpha` 变淡之外再叠 `scale` 变小
  （`recede = (1 - fromEnd*0.045).coerceAtLeast(0.82)`），最近一句最亮最大；
  淡/小都设了可读下限，翻到开头不会整屏"看不清的灰字"。

### 二、长按消息菜单改「微信式气泡旁菜单」
- 不再从底部滑出卡片（弃用 `NeuSheetScaffold`）；改为 `Popup` + 自定义 `PopupPositionProvider`，
  **在气泡旁边就地弹出**：图标在上、文字在下，**用户消息的菜单靠右、AI 消息靠左**，
  垂直方向优先气泡上方、放不下才落到下方，8dp 边距 + clamp 到窗口内；点空白处收起。
- 气泡位置由条目级 `Modifier.onGloballyPositioned { it.boundsInRoot() }` 上报，交给菜单定位。

### 三、新增「多选」（批量复制 / 转发 / 删除）
- 菜单里多一项**「多选」**（`MIcons.SelectAll`）：点它即选中当前这条并进入多选模式。
- 多选态：每条消息左侧出现勾选圈（`MIcons.Check`），点消息本体切换选中；
  覆盖层 `matchParentSize().clickable` 拦截气泡自身交互。
- 底部工具条：**批量复制 / 转发到其它会话 / 删除**，左侧显示已选条数，右上角「取消」退出
  （返回键也退出并清空选择）。
- **转发**走 `ChatViewModel.forwardMessages`：把选中消息**复制**一份写进目标会话（原消息不动），
  时间戳按顺序递增保持先后；媒体消息直接复用本机文件路径，不复制文件。
- **批量删除**走 `ChatViewModel.deleteMessages`。

### 四、AI 图库可删除记录
- 图库记录行支持**长按 → 右下角菜单 → 删除**（`combinedClickable` + `HapticFeedbackType.LongPress`）。
- 删除 = **记录与本机图片文件一起删**（`File.delete()` + `ImageGenerationDao.deleteById`），删完刷新列表。
- `ImageGenerationDao` 补 `deleteById(id)`（纯查询，无 schema 变更、不需要 Migration）。

### 五、手册与测试
- `AiPrompts` 手册在链尾追加 v0.54.3 定点替换（字幕 6 行/越远越小、微信式长按菜单 + 多选、图库可删）。
- `AiPromptsTest`：`v0540` 去掉已被替换的旧字幕断言，新增 `v0543 微信式长按菜单与图库删除进手册`
  （每条 replace 一对「新措辞 contains + 旧措辞 assertFalse」双向断言）。
- `Icons` 新增 `Check` / `SelectAll` / `Forward`。

## v0.54.2（versionCode 114）

**设置主页定稿「方案二」：只留九张入口卡、点进去才是二级页；「联网搜索」从定时主动消息拎进模型配置；权限卡收紧。**

### 一、设置主页：九张入口卡（不再是分组 / 手风琴 / 平铺）
用户原话："不是手风琴，不是分组。折叠，是点进去。模型配置是一个卡片，点进去就是这 4 个卡片。"

- 主页**去掉全部分组小标题与编号**，只留九张自题名的入口卡：
  **模型配置 / 提示词库 / 音色 / 通话行为 / 提醒与通知 / 外观 / 权限 / 数据与隐私 / 关于**。
- 多条目板块收成**一张卡**，点进去才是那几张子卡：
  - **模型配置**（5 张）：对话模型 / 生图模型 / 识别模型 / 语音模型 / **联网搜索**；
  - **通话行为**（2 张）：AI 主动打电话 / 语音通话铃声；
  - **提醒与通知**：定时主动消息 + 任务到点提醒 / 手机操作进行中通知 / 人设主动关心。
- 顶部搜索框保留，按标题与副标题过滤这九张卡。
- 原有功能项一个不少。

### 二、联网搜索独立成页并归入「模型配置」
- 此前它嵌在「定时主动消息」页底部（用户两次纠正位置）；现在**拎出来独立成页**
  （服务商选择 博查 / Tavily + API Key + 保存），入口挂在 **设置 → 模型配置 → 联网搜索**。
- 「定时主动消息」页只保留「先联网搜一遍再写」开关，提示文案指向新路径。

### 三、权限页卡片收紧
- `PermissionCard`：圆角 18→14dp、内边距 16/14→14/10dp、名称 15→14sp、用途 11.5→11.5sp/行高 16、
  「去开启」由大号描边按钮换成紧凑文字按钮，卡片间距 10→8dp。

### 四、手册与测试
- `AiPrompts` 手册在链尾追加 v0.54.2 定点替换：路径去编号（`设置 → 模型配置 → 识别模型`）、
  联网搜索改挂模型配置、「设置页怎么用（v0.54.2 定稿）」改写成九张卡 + 子卡的说法。
- `AiPromptsTest`：`v0541` 收成跨版本断言，新增 `v0542 设置页方案二进手册`（双侧断言）。
- `UiRenderTest`：`render27SettingsGroups` 改为主页九卡 → 点进模型配置见 5 张子卡、
  点进提醒与通知见 4 项；`render28` / `render29` 点击路径先经「模型配置」。

## v0.54.1（versionCode 113）

**通话字幕收成"最近四段"窗口 + 渐隐翻正；设置页改成九大板块（对话/生图拆两页、外观恢复独立页）。**

### 一、通话字幕：限高四段 + 渐隐方向修正
- **字幕区限高约 4 段**（每段最多 2 行，超出省略号），**更早的历史默认隐藏、上滑才看到**；
  视口固定贴底（底部对齐），不再随内容把整页顶来顶去。
- **渐隐方向修正**：上一版把"离末尾多远"整体套淡出，最新一句拿到最淡的 0.3f——
  整屏像蒙了一层灰。现在**最新一句最亮（TextPrimary）**，越旧越淡（下限 0.6f），
  5 句以外落到 MutedText，焦点自然落在最新对话上。
- 保留 v0.54.0 的可滚动翻阅：贴底时新句自动跟随、一上翻就不再被拽回、右下角「↓」回到底部。

### 二、设置页九大板块（用户 2026-10-05 逐条拍板）
顶部保留搜索框，下面按九个板块排（**只有 ①④⑤ 三个多条目板块带小标题**，其余是自题名的单卡）：

| 板块 | 内容 |
|---|---|
| ① 模型配置 | 对话模型 / 生图模型 / 识别模型 / 语音模型 |
| ② 提示词库 | （原「AI 提示词」，内容不变） |
| ③ 音色 | （原有音色列表不变） |
| ④ 通话行为 | AI 主动打电话 / 语音通话铃声 |
| ⑤ 提醒与通知 | 定时主动消息 + 任务到点提醒 / 手机操作进行中通知 / 人设主动关心 |
| ⑥ 外观 | 独立全屏页，三个主题胶囊 |
| ⑦ 权限 | （内容不变） |
| ⑧ 数据与隐私 | （内容不变） |
| ⑨ 关于 | （内容不变） |

- **对话模型 / 生图模型拆成两个独立页**（原「API 配置」页一页装两类模型 → 各占一页）。
- **外观恢复独立页**（v0.54.0 曾并入主页）。
- 「语音」「通知与提醒」两个中转页保持取消，功能项全部保留、一个不少。

### 三、手册与测试
- `AiPrompts` 手册路径整体改写为九板块写法（`设置 → ① 模型配置 → 识别模型` 等），
  并新增「设置页怎么用（v0.54.1 定稿）」一段；旧路径（`设置 → 语音 →` / `设置 → 通知与提醒` /
  `设置 → 外观` 等）一次不剩。
- `AiPromptsTest`：`v0540b` → `v0541`（九板块断言），并修正 `v0520` / `v0535` 里两条因措辞
  变更而失效的音色断言。
- `UiRenderTest`：`render27SettingsGroups` 改成验九板块布局；`render15AppearanceScreen` 改回
  "外观是独立页"；`render28TtsConfigScreen`（走「语音模型」）、`render29ApiConfigModels`
  （走「对话模型」）的点击路径跟着改。

## v0.54.0（versionCode 112）

**通话字幕改成可翻阅的完整记录；设置页按四个分组重排（消掉三个页面、「AI 提示词」收进设置）。**

### 一、通话字幕：可滚动 + 手动翻阅不被打断
- 以前字幕只保留**最近 6 句**、越旧越淡（透明度低到 0.14），想回看说过的话根本翻不回去。
  现在保留**整通电话的完整记录**（上限 200 条防御），`LazyColumn` 上下滑动即可回看。
- **贴底时新句自动跟随**；**用户一向上翻就停止自动滚动**，流式增量不再把人拽回底部。
  离开底部时右下角浮出一颗「↓」按钮，点一下回到最新并恢复跟随。
- **淡出阶梯只作用在最新 5 句**；更早的历史一律给可读的不透明度
  —— 原来整列表都按"离末尾多远"淡出，翻到通话开头会满屏看不清的灰字。
- 布局：头像区改成**高度由内容决定**（原来上下都是 `Spacer(weight)`，矮屏 +「缺模型」提示
  按钮会被裁掉），剩余高度全给字幕区。

### 二、设置页收纳重构（用户反馈"分组不清晰、层级过深、排列无序、没有搜索"）
**改前**：设置主页7 个入口**一列平铺、无小标题**，「API 配置」和「关于」是同一种卡片；
最深要点 4 层（设置→语音→语音合成→添加接入→选服务商），最浅 1 层（外观里只有 3 个胶囊）；
语音页 5 个入口混装合成 / 识别 / 主动行为 / 通话体验四类东西，「接听延迟」是个裸数字框
夹在行卡中间；19 个叶子项没有任何搜索；「AI 提示词」「基础档案」「AI 图库」三项漏在「我的」页，
把设置域打散在两个页面。

**改后**：设置主页 = **搜索框 + 四个分组标签**（标签只是视觉分区、**不可点不折叠**，
用户明确要"全屏显示"；每张卡片点进去仍是全屏页 + 顶部返回按钮）：

| 组 | 内容 |
|---|---|
| ① AI 与模型 | 对话模型、**AI 提示词（从「我的」搬进来）** |
| ② 语音与通话 | 语音合成 / 音色 / 识别模型 ‖ AI 主动打电话 / 语音通话铃声 / 定时主动消息 |
| ③ 提醒与通知 | 三个开关**就地显示**（任务提醒 / 操作通知 / 主动关心） |
| ④ 数据与系统 | 外观三胶囊**就地平铺** / 权限 / 数据与隐私 / 关于 |

- **消掉三个页面**：`语音`（6 个子项上提为一级卡，取消中转层）、`外观`（并入第 ④ 组）、
  `通知与提醒`（三个开关并入第 ③ 组）。**设置链最深从 4 层降到 2 层。**
- **「接听延迟」并进「语音通话铃声」页**（原来那个裸数字框终于有了归属：它和铃声
  都是"打电话时的手感"）。
- **新增搜索框**：按标题 + 副标题过滤，每行还塞了用户真会搜的别名
  （搜"接口"能找到对话模型、搜"铃声"能找到通话铃声、搜"ASR"能找到识别模型）。
  分组标签不参与过滤、命中项仍显示在自己那组下面 —— 避免搜出来的东西飘在组外看不出归属。
- 「我的」页自此只留 **人设管理 / 基础档案 / AI 图库 + 设置** 四张卡，分工清楚：
  **本页 = 我是谁，设置页 = 怎么配。**

### 三、手册与测试
- **手册 20+ 处路径全部改写**（`设置 → 语音 → 识别模型` 这类一律改成
  `设置 → ② 语音与通话 → 识别模型`），并新增一段「设置页怎么用」讲清新分组与搜索。
  旧路径一次不剩 —— 残留旧路径 = 助手把用户指到一个已删除的页面上，比答不出来更糟。
- `AiPromptsTest` 新增 2 组（共 24 条断言）：字幕可翻阅 6 条 + 设置分组 20 条（新措辞在 /
  旧路径不在，双向夹住）。另修正 `v0535` 里2 条因措辞变更而失效的断言。
- `UiRenderTest`：`render27VoiceScreen` → `render27SettingsGroups`（改成验四组布局）；
  `render15AppearanceScreen` 改成"外观已并入主页、不该再有独立入口行"。

### 出包与验证
- ✅ **已出包（2026-10-05）**：`梦欢-v0.54.0-release.apk`（**37,137,571 B≈ 35.4MB**，放项目根目录；
  上一版 v0.53.9 已归档到 `archive/`）。**BUILD SUCCESSFUL in 2m54s**（编译 + R8 混淆 + 资源收缩，
  debug 签名可覆盖安装）。
- 包内核对：`versionCode=112` / `versionName=0.54.0` / `com.menghuan.app` ✅；
  ABI **只有 arm64-v8a**（8 个 so）✅；`assets/asr/` **只有 silero_vad.onnx（1.72MB）** ✅
  —— 三处构建硬约束都没被"顺手改回"。
- **首次构建失败过一次**（6 个编译错误，见下），已全部修掉后重出。
- ⚠️ **未跑单测与截图回归**。真机待验：通话字幕上下翻 + 翻动时不被新字幕拽回 + 「↓」回到底部；
  设置页四组排布与搜索过滤（含搜"接口"/"铃声"/"ASR"）；深色下搜索框与开关行观感。

#### 首次构建踩到的 6 个编译错误（**静态括号检查查不出来，只有真编译才暴露**）
| 错误 | 原因 |
|---|---|
| `Unresolved reference 'scrollBy'` | 它是 `ScrollableState` 的扩展函数，必须 `import androidx.compose.foundation.gestures.scrollBy` |
| `Unresolved reference 'Box'` | 新写的搜索框用了 `Box` 但 `SettingsPage.kt` 没导入 |
| `@Composable invocations can only happen from...` ×2 | 上一条的连带：连 `Text`/`BasicTextField` 一起认不出来 |
| `No value passed for parameter 'initial'` | `themeMode` 是无初值 Flow，`collectAsState()` 必须给 `initial = DEFAULT_THEME_MODE` |
| `Unresolved reference 'PERMISSIONS'` | 上一轮重构时把 `SettingsRoutes.PERMISSIONS` 当成"三个被删的页面之一"误删了 —— **权限页没删、只是入口挪到第 ④ 组** |

> **教训**：写完 Compose 改动后，**"括号平衡 / 导入看起来齐"这种静态自查基本没用**。
> 唯一可靠的是真跑一次 `:app:compileReleaseKotlin`。下批改完 UI 后建议先跑
> `compileDebugKotlin`（比 assemble 快得多）过一遍，再谈出包。


> 详细方案与取舍过程见 `docs/PLAN-设置页收纳重构方案.md`。

## v0.53.9（versionCode 111）

**铃声：加「试听」+ 播不出来在界面上明说（真机反馈"设了铃声去电却不响"＝免打扰）；通话字幕改成"先出字、后出声"。**

### 一、"设了铃声去电却没声" —— 根因是免打扰
自定义铃声走 `USAGE_NOTIFICATION_RINGTONE`，即**铃声音量通道**；手机开**免打扰 / 静音 / 振动**时
一律不响，跟 App 无关。这条现在**直接写在铃声设置页上**，省得下次再排查一轮。

### 二、铃声「试听」（以前只在设过自定义铃声后才出现）
- 「试听」**常显**：设了自定义铃声就试听它；没设就试听**系统默认铃声**（`RingtoneManager`）。
- 「当前铃声」那行没设时显示**系统默认铃声的真实名字**，能对上试听听到的是什么。

### 三、自定义铃声读不出来 → 界面上明说
- 新增 `CallRingtone.isPlayable(path)`：进页 / 换过铃声时用 `MediaPlayer.prepare()` **真查一次**；
  文件被删、或这台手机不支持这个格式（例如 wma）→ **页面标红说明**，
  并讲清"通话会退回系统默认铃声、点试听也不会响"。
- 以前这种情况是**静默退回系统铃声**，用户只会觉得"我设的铃声没生效"。

### 四、通话字幕改成"先出字、后出声"（真机反馈"他先说完话文字才出来"）
- 根因在 `PipelineVoiceCallEngine.respond()`：字幕以前绑的是**"还没念的那截"**（切句时 `buf` 被清空），
  于是每切一句字幕就**清空重来**，念的时候字幕常常是空的，等到整轮念完才由 `AiFinal` 一次性补全
  —— 观感就是"说完话文字才出现"。
- 现在字幕绑**整轮已生成全文**（`shown` 只增不减）：LLM 一吐字字幕就长出来，
  **文字先于/同步于语音**，念的过程中一直挂着；收口时再转成正式一轮字幕。
- 需求原话："应该同时显示文字同时说话，或者先显示文字再说话"。

### 手册与测试
- 手册同步：试听随时可点 / 播不出来会标红 / 免打扰静音不响。
- 测试：`AiPromptsTest` 新增 1 条（4 个断言，含旧措辞消失）。

> ✅ **已出包（2026-10-05）**：`梦欢-v0.53.9-release.apk`（37.1MB，放项目根目录；旧版 v0.53.8 已归档到 `archive/`）。
> 本次只跑了 `:app:assembleRelease`（编译 + R8 混淆 + 资源收缩，debug 签名可覆盖安装），**未跑单测与截图**。
> 真机待验：铃声页试听（自定义 + 系统默认各一次）、把铃声文件删掉后进页是否标红、
> 免打扰开关下铃声是否确实不响；通话时字幕是否随 AI 开口**同步/先行**出现（不再"说完才出字"）。

## v0.53.8（versionCode 110）

**修复"叫 AI 立刻打电话它没打过来"（四个断点全堵）+ 延迟接听改成填秒数 + 新增「权限」板块。**

### 一、"我叫 AI 打电话，没打过来" —— 四个静默失效的断点
1. **`[打电话:0]`（立刻）被判非法**：代码只认 1~1440 分钟，0 直接丢弃、什么都不发生。
   现在 **0 = 立刻，排在 10 秒后**；排钟前校验的下限也从 1 分钟放宽到 5 秒。
2. **总开关关着就不告诉模型它会打电话** → 它连约定标记都不输出。
   现在打电话能力说明**总是注入**提示词。
3. **还查了"该人设是否允许主动打电话"** → 用户点名要的也被拦。
   现在**用户点名要的那一通不受任何预设限制**（不看总开关、不看人设规则、不避静默、不算次数）
   —— 用户原话："我叫他待会打他也得打"。
4. **通知权限没给**：AI 打电话完全靠通知弹出，没权限就彻底无声，而项目里只有任务提醒页申请过它。
   「AI 主动打电话」设置页**顶部**现在会检查并给一键申请。

另外提示词要求模型**在正文里明确答应**（"好，我这就打给你"）—— 以前它只输出标记、正文不吭声，用户以为它没收到。

### 二、延迟接听改成填秒数
预设选项（立刻/1s/2s…）换成**数字输入框**：**填 0 = 立刻接听**，最多 30；延迟期间一直响铃、显示「正在呼叫…」。

### 三、新增「权限」板块（设置 → 权限）
- 集中列出六项授权：**通知（必开）/ 麦克风（必开）/ 相机 / 精确闹钟 / 全屏通知（锁屏来电）/ 手机操控（无障碍）**。
- 每项：名称 + 用途说明 + 当前状态 + 「去开启」**直达系统设置对应页面**（跳失败的 ROM 兜底去应用详情页）。
- **从系统设置返回自动刷新状态**。
- 设置首页的入口行**动态显示**"⚠️ 还有 N 项必开权限未开启"，不用点进去才知道。
- 为什么值得做：这一版前面几个问题（打电话没响、提醒不响）根因都是**权限缺失导致静默失效**，界面上却毫无提示。

### 手册与测试
- 手册同步：打电话不受限 / 0 = 立刻 / 延迟填秒数 / 权限板块。
- 测试：`AiPromptsTest` 26 条、`AiCallLogicTest` 12 条（0 合法、下限 5 秒）、`ScheduledMessageTest` 4 条 —— **全绿**。

> ⚠️ 本版**没跑 UI 截图回归**。真机待验：聊天里说"立刻打给我"10 秒左右是否来电、
> 通知权限引导是否到位、权限页各项跳转、延迟填 0 是否秒接。

## v0.53.7（versionCode 109）

**语音通话三处修复 + 通话铃声 + AI 延迟接听 + 对话页微信式转场。**

### 一、语音通话的三处修复
- **来电界面真全屏**：以前没关掉 `decorFitsSystemWindows`，状态栏/导航栏把内容挤住，来电页看着不是全屏。
  现在背景铺到系统栏下面、内容用 `systemBarsPadding()` 避让，锁屏上直接盖满。
- **AI 知道这通电话是它自己打的**：根因是通话时拼给模型的指令里**完全没提这通电话是谁发起的**，
  它只能当成"自己接到的电话"，开口就是"喂？你找我有事吗"。现在按发起方分两套措辞：
  它打的 → 明确告诉它"这通电话是你主动打给用户的，接通后由你先开口"并附开场白；
  你打的 → 让它先听你说。
- **"AI 先开口"真正接通了**：新增引擎入口 `speakFirst()`，接通音之后由它说第一句。
- **通话记录卡落在正确的一侧**：以前落库时**恒定写成"用户发的"**，所以 AI 打来的电话也显示在你那侧。
  现在按"谁打的"决定方向。

### 二、语音通话铃声（设置 → 语音 → 「语音通话铃声」）
- 可以传**音频**（mp3 / m4a / wav / ogg…）：原样留用、不重新编码；
- 也可以传**视频**（mp4 / mkv…）：**自动把音轨抽出来**存成音频；
- **来电与去电共用这一个铃声** —— AI 打给你、你打给 AI，响的都是它；
- 带试听与恢复系统默认；只保留一个自定义铃声，换新的会清掉旧的。

### 三、AI 延迟接听（设置 → 语音）
- 「我打给 AI 时，TA 过几秒接听」：立刻 / 1s / 2s / 3s / 5s / 8s（默认 **3 秒**）。
- 延迟期间一直响铃、界面显示「正在呼叫…」，到点 AI 才"接起来"。**只影响去电**，AI 打来的来电不受影响。

### 四、对话页转场改成微信式
- 用户反馈"聊天记录从上往下弹出、输入框从右上往左下滑入，方向与微信完全不符"。
- 根因是**两套动画叠加**：① 路由层进场挂淡入、退场只淡出、返回还叠缩放；
  ② 消息列表 `animateItem` 特意保留了"重排弹簧" → 进页时整屏消息弹一遍。
- 现在改成**纯位移**：新页从右滑入满屏、旧页（会话列表）向左移 1/3、返回时反向；
  消息项的重排弹簧也关掉。**整页只有一件事：从右往左平移**。
- ⚠️ 只改了对话页这一个路由，其它页面仍是原来的转场风格。

### 手册与测试
- 手册同步：通话三条约定、铃声设置、延迟接听。
- 测试：`AiPromptsTest` 25 条、`AiCallLogicTest` 12 条、`ScheduledMessageTest` 4 条 —— **全绿**。

> ⚠️ 本版**没跑 UI 截图回归**。真机待验：锁屏来电是否真全屏、AI 接起来第一句是否主动打招呼、
> 打完电话记录卡是否落在 AI 那侧、铃声（找个视频试转换）、延迟接听的等待感、对话页转场是否干净。

## v0.53.6（versionCode 108）

**「主动打电话」改成每个 AI 独立配置；对话页三点从一个按钮变成九项菜单。**

### 一、AI 主动打电话：按人设独立
- **总开关 + 每人设一套规则**：是否允许 TA 打、每天几点、距上次跟 TA 聊天多久没交流才打。
  打开总开关后展开全部 AI 人设，点某一行手风琴展开它自己的配置。
- 三者是**"与"关系**：到点先比"距上次跟 TA 聊天"够不够久，不够就不打扰
  （想让它每天到点必打，把天数选 1 天）。**各人设互不影响**。
- 每人设每天最多一通；22:30–08:00 静默；接通后 AI 先开口；没接转成一条留言。
- 旧版的全局面板（单时间 / 单阈值 / 单选定人设）在读取时自动迁成那个人的一条规则，不用重配。

### 二、对话页三点 → 九项菜单
以前点三点是**直接打开编辑人设**；现在弹菜单，「编辑人设」降级为其中一项。

| 项 | 行为 |
|---|---|
| 编辑人设 | 独立整页路由（原行为不变） |
| 查看聊天记录 | 新页：按关键词搜**本会话**历史消息（复用已有的 LIKE 查询），不填关键词列最近 50 条 |
| 允许 / 取消主动打电话 | 切换该 AI 的开关（显示当前状态），并顺手排/撤**它自己**的闹钟 |
| 角色立绘 | 选图存起来（**只存不显**） |
| Live 2D | 选资源存起来（**只存不显**） |
| 聊天背景 | 选图存起来（**只存不显**） |
| 联网：已开 / 已关 | 该会话单独的联网开关 |
| 音色 | 选这个 AI 用谁的声音（**从编辑人设移到这里**） |
| 模型 | 与长按头像切换接口是同一件事 |

- **立绘 / Live 2D / 聊天背景「只存不显」**：按用户说明，这三样是"以后打电话时展示"的，
  展示方式还没定，所以只做**选文件 + 存本机 + 可换**，不接任何渲染。
  文件落在应用私有目录，路径存在本地配置里。
- **会话联网**打开后，在对话里问最新消息（如"今天有什么新闻"）会**先搜再答** ——
  用的是「设置 → 通知与提醒 → 定时主动消息 → 联网搜索」里已经配好的那个 Key（博查 / Tavily），
  不用再配一遍。搜不到时强制模型**如实说查不到，不许编**。
- **音色已从编辑人设里移除**（那一页相关的状态与弹层整块清理掉了），改到菜单里选。

### 手册与测试
- 手册同步：九项菜单 / 三项只存不显 / 会话联网 / 音色搬家 / 打电话按人设独立。
- 测试：`AiCallLogicTest` 12 条（新增"按人设判定"3 条）、`ScheduledMessageTest` 4 条、
  `AiPromptsTest` 21 条 —— **全绿**。

> ⚠️ 本版**没跑 UI 截图回归**。真机待验：九项菜单在窄屏上的展开位置、会话联网的实际搜索效果、
> 每个 AI 到点是否各自按自己的规则触发。

## v0.53.5（versionCode 107）

**本版合并了三批需求**（代码注释里出现的 v0.53.5 / v0.53.6 / v0.53.7 都指这一版）：
界面整理七条 + AI 主动打电话 + 定时主动消息（带联网搜索）。

### 一、界面整理（七条）
- **所有菜单统一加发丝灰线框**：会话列表长按、表情删除、AI 洞察删除/分享、建议转任务、记账删除
  —— 全应用去阴影后白底菜单在白底页面上糊成一片，靠这条线把菜单"框"出来。
- **编辑人设改成真正的整页**：新路由 `persona_editor/{personaId}`（0 = 新建），
  三个入口（人设管理 / 会话列表"新建人设" / 单聊右上三点）全部走路由，不再有弹层与 Dialog。
- **编辑人设的音色选择**改成**从下方升起**的弹层（原来贴着选项的那种小菜单）。
- **头像 emoji 只认滚动**：那排改成纯图标，点它不选（所以不会再冒出黑色选中底），只靠左右滑动换。
- **「音色」独立成页**：语音页里它是一张卡片；点进去是独立页，**顶栏右侧**是「克隆音色」与「ID」
  （手填音色 ID 缩成 ID，点开是居中弹窗）。
- **通知与提醒删掉三项**：每日总结、AI 周报生成提醒、系统通知设置。
- **录音蒙层下移 + 选择区改扇形**：整块贴屏幕下部（原来垂直居中，手指够不到）；
  两颗圆按钮换成两块**半圆扇形**，拖进去哪块哪块变深并显字。

### 二、AI 主动打电话（默认关，设置 → 语音 → 「AI 主动打电话」）
- **三路触发**：① 定时（默认 21:00，可改）② 智能（默认 3 天没聊，可设 1/3/7）
  ③ **聊天里口头约定** —— 你说「待会打给我」「半小时后叫我」，AI 输出 `[打电话:N]`
  或 `[打电话:HH:mm]`，App 排一次性闹钟。
- **接通后由 AI 先开口**（按档案 + 今天任务 + 最近对话现生成）；没接/挂断/30 秒超时
  → 那句开场白**转成一条留言**落进会话，不白打。
- 独立开关，与「人设主动关心」**各管各的**；每天上限 2 次；22:30–08:00 静默；可选谁打给你。
- 来电页是**独立 Activity**，锁屏上直接盖上来（`setShowWhenLocked`）。
  全屏通知要 `USE_FULL_SCREEN_INTENT`，**Android 14+ 需用户在系统设置里手动允许**
  （设置页有直达按钮）；拿不到就降级成「高优先级通知 + 铃声」。
- 判定走**纯规则**（不烧 token），只有确定要打了才调一次模型写开场白。

### 三、定时主动消息 + 联网搜索（设置 → 通知与提醒 → 「定时主动消息」）
- **自己配「谁发、几点发、发什么」，可配多条**：例「小美 每天 07:30 发今天的重要新闻」、
  「教练 每天 22:00 问我今天练了没」。
- 每条可打开**「先联网搜一遍再写」**：先搜 → 把结果喂给模型 → 再以该人设口吻写。
  模型自己不联网，不搜只会凭旧记忆编（会给出过期甚至假的信息）。
- **联网搜索两家可选**（Key 需用户自己申请，App 不代劳）：
  - 博查（国内）：https://open.bochaai.com → `POST https://api.bochaai.com/v1/web-search`
  - Tavily（国际）：https://tavily.com → `POST https://api.tavily.com/search`
- **搜不到时强制模型"如实说查不到"，不许编新闻** —— 这是全项目最容易造假信息的地方。

### 手册与测试
- 应用助手手册同步三处（界面整理 / 打电话 / 定时消息，含两家搜索服务的官方地址）。
- 新增 `AiCallLogicTest`（9 条）与 `ScheduledMessageTest`（4 条）纯逻辑单测；`AiPromptsTest` 相应更新。

> ⚠️ 按用户要求**跳过单测与截图回归直接出包**（只做了编译）。真机待验：
> 锁屏全屏来电与响铃、打电话的三路触发、定时消息到点是否送达、联网搜索是否通且是当日结果。

## v0.53.4（versionCode 106）

**这一版全是"看得见"的改动：API 配置按模型重组、编辑人设改全屏、录音换成微信式蒙层。**

### API 配置：按「模型」而非「接口」组织
- 入口 / 详情 / 删除文案统一成「模型」：＋ 添加模型、编辑模型、删除模型；段标题 = 对话模型 / 生图模型。
- 列表**点行 = 切换当前生效**（保留），**点右侧箭头 = 进详情**；去掉那颗多余的 ✓
  （选中态本来就有虚线框 + 副标题「默认 · 」）。
- **生图支持多条模型**，与对话模型同构（复用 `ApiProfile` / `ApiProfileCodec`，新键
  `img_profiles` + `img_active_profile`）；老的单条配置虚拟读成一条「默认生图模型」，
  且在新增/删除前先把它实体化，升级不丢配置。
- **可设默认生图模型**；生图时默认优先，调不通**自动换下一个**，生成记录里写的是实际成功那个模型名。

### 语音页
- 去掉「AI 语音条」「语音通话」两个总开关（本来就默认开、用户不会去关）——功能固定开启，
  数据层的键与加号面板的开关参数一并删掉。
- 「音色」板块留在语音页内；音色行保持轻量普通行（点行设默认、行尾试听）；
  「克隆音色」入口换成右上角一颗小按钮。

### 编辑人设
- 改成**全屏页**（外壳仍是整屏弹窗，三个入口位置不变）。
- 头像与人物 emoji 同一排：点一个、或**左右滑动那一排**，头像框里的形象实时跟着变；
  删掉「点头像换图」那句提示（换图能力保留）；emoji 预设只用**人的形象**。
- 音色只留 男声 / 女声 / 我克隆的声音（去掉「跟随默认音色」与各类 ID 明细，克隆不带前缀）。
- 设定词去掉英文标注与「插入模板」按钮。

### 头像与列表
- **头像渐变环画到最外圈**：以前环画在距边 2~3dp 处、被内缩 3dp 的图片压住，看着像"在头像里面"；
  现在环贴最外圈、图片内缩到环内侧，换自定义图也一定看得到；图片统一裁圆。
- 会话列表加回发丝灰线；长按菜单加**灰线外框**，并改成**跟着手指位置**弹出（不再固定右下角）。

### 录音
- 按住说话换成微信式蒙层：中间气泡 = **实时波形 + 秒数**（真振幅，不是假动画），
  下面「松手发送」，再下面两颗圆按钮「取消」「转文字」；**左滑取消 / 右滑只发文字**，
  识别不出来就照发语音（不丢内容）。蒙层是**从上到下渐浓**的渐变，配色跟随主题。

### 手册
- 应用助手手册随每处改动同步（API 配置 / 语音 / 编辑人设 / 录音各一章），
  `AiPromptsTest` 相应新增与更新断言。

> ⚠️ 本版按用户要求**跳过单测与截图回归直接出包**。真机待验：录音蒙层的波形是否跟声、
> 滑动换档的手感；全屏编辑人设的键盘避让。

## v0.53.3（versionCode 105）

**克隆音色"发抖变调"：是我们自己把参考音频弄坏了两次。**

- 症状定位（用户四问定死范围）：**预置音色一切正常**、克隆音色在**文字语音条**里也一样怪
  → 与通话链路、与网络都无关，问题在**参考音频本身**。CosyVoice2 是零样本克隆
  （参考音频 + 参考文本），参考坏了模型就抖。
- **成因①裸线性插值降采样 = 混叠**：`WavPcm.resampleLinear` 从视频音轨常见的 48 kHz
  直接抽到 24 kHz，高于新奈奎斯特（12 kHz）的成分折回可听频段，毛刺被"烘"进上传的参考里。
  新增 `WavPcm.resample`：降采样先做**两遍滑动平均**（= 三角核低通，滚降比方核干净）再插值，
  升采样不需要低通。`parse`、`TtsAudioDecoder`、`VoiceCloner` 三处统一改走它。
- **成因②15 秒硬切切在半句话上**：参考音频与端侧转写出的逐字稿对不上，零样本克隆的韵律就散。
  新增纯函数 `VoiceCloner.trimToSpeech`：**跳过前导静音**，在 `MAX_SAMPLE_SECONDS` 之内
  **找最近的能量谷下刀**（20ms 帧 + 相对整段峰值 6% 的阈值），结尾留 120ms 呼吸；
  找不到谷或切出来不足 1 秒就退回整段上限（宁可长切，不要切半句）。
  上限同时从 15 秒收到 **10 秒**（官方建议 3~10 秒，长参考反而更容易跨句/带进 BGM）。
- 克隆弹层加一句硬要求：样本要 **8~10 秒干净单人声**，带背景音乐或只截到半句话，
  克隆出来就会发抖变调——这条代码救不了，只能换样本（用户那段是 VTuber 直播切片，大概率带 BGM）。
- 测试：新增 `VoiceClonerSampleTest` 5 条（20 kHz 成分在 48k→24k 后必须被大幅衰减、
  1 kHz 主峰不被低通搬走、前导静音被跳过且在句子间隙收尾、全程无停顿退回上限、短样本原样通过）。
  `WavPcmTest` 10 条、`AiPromptsTest` 12 条同时绿。
- 手册同步：两处"裁到 15 秒内"改成"挑出 10 秒内一句完整的话"，并写明样本必须干净单人声。

## v0.53.2（versionCode 104）

**上一版的崩溃兜底把真凶抓出来了：`WavPcm` 的 chunk 游标会倒退成负数。**

- 装了 v0.53.1 后通话页显示的是「播放出错：StringIndexOutOfBoundsException：
  length=69294; regionStart=-82; regionLength=4」——正是这条把根因钉死了：
  `WavPcm.parse` 按 chunk 遍历时用**原始 size** 推进游标，而我上一版为了处理"流式 WAV 把
  `data` 长度写成 0xFFFFFFFF（按 Int 是 -1）"只钳制了 `dataLen`，**没钳制游标** →
  游标倒退、几轮后变负 → 拿负数去读 4 字节 chunk 名就抛异常 → 冒到通话协程 = 闪退。
  换句话说：**是我这次"修 WAV"修出来的**，不是硅基的锅。
- 修法：`data` 块读完立即 `break`（载荷之后没有我们要的块）；推进前要求 `next > i` 严格前进，
  否则停；循环条件改成 `i in 0..(bytes.size - 8)`（负数直接不进循环）。
- `WavPcmTest` 补三条回归（纯 JVM，秒级）：**流式 WAV 的 data 长度为 -1 必须能解且不许抛**、
  中间块 size 是垃圾负数时安静返回 null、**IEEE float32 的 WAV 能解**（硅基就是这种）。
  另把「立体声取首声道」改成「立体声下混成单声道」——v0.53.0 起取的是均值，测试要跟上实现。
- 崩溃兜底（v0.53.1 的 `CrashLog` + 通话协程 try/catch）保留：这次就是它把异常变成了一行可读的
  屏幕文字，才让我们在没有 logcat 的真机上一次定位。

## v0.53.1（versionCode 103）

**通话闪退：先把异常接住，再把现场留下来。**

- 真机反馈"打电话时 AI 还没出声就闪退"。查引擎结构找到**为什么会是整个 App 没**：
  `ttsPlayer` 与 `mainLoop` 两个协程都跑在 `applicationScope` 上，里面没有 try/catch，
  任何一处抛出（最可能是 AudioTrack 状态类异常——挂断/插话时另一条线程正在 release 轨道）
  都会冒到默认未捕获处理器 → 进程直接死掉，而真机没有 logcat，**一行堆栈都留不下**。
- `PipelineVoiceCallEngine`：`ttsPlayer` 与 `mainLoop` 的循环体各包一层
  `try / catch(CancellationException 原样抛) / catch(Throwable)`，出错时发一条
  `RealtimeCallEvent.Error("播放出错：<类名>：<消息>")` 到通话页并继续走字幕兜底；
  `Log.e` 留一份完整堆栈。
- 新增 `data/CrashLog.kt`：`Application.onCreate` 第一件事装
  `Thread.setDefaultUncaughtExceptionHandler`，崩溃前把「时间 + 版本 + 机型 + 线程名 + 堆栈」
  写进 `filesDir/crash.log`（写完仍交回原处理器，不改变系统行为）。
  「我的 → 设置 → 关于」里若存在记录就出现**「上次崩溃记录」**一行，点开是可选中复制的堆栈弹窗，
  底部一个「清除这条记录」。以后用户报闪退，一张截图就能定位。
- 手册同步：「关于」那条补上崩溃记录入口的说明（`AiPromptsTest` 加一对断言）。

## v0.53.0（versionCode 102）

**接口配置改成「卡片 + 点箭头看详情」；通话 WAV 解析不再挑位深；克隆音色的中文名终于能用了。**

- **纠正 v0.52.0 对手风琴的误解**（用户："我说的展开折叠不是这个样子的"）：不是把"对话接口 / 生图接口"
  两段折起来，而是**每条接口本身**收在卡片里。现在：
  - 每条接口一张 `NeuSheetRow` 卡片，**点卡片本体 = 切换当前生效**；
  - **点卡片右侧那颗箭头 = 从屏幕中间弹出这条的详细信息**（`SettingsCardDialog`）：
    名称 / 接口地址 / API Key / 模型 都能改，还有测试连接、保存、**删除**；
  - 卡片下面那排「编辑」「删除」文字按钮**删掉了**（用户点名，容易误触）；
  - 箭头沿用原形状但**加大加深**（40dp 命中区 + 21dp 图标 + `TextSecondary`），
    由 `NeuSheetRow(onChevronClick=…)` 内置；选中态的「✓」与箭头并存，不再是二选一。
  - 手风琴 `SettingsSectionRow` 退回只给「AI 提示词」那种名单页用。
- **语音合成页同一套原理**（用户："不需要显示那个什么硅基、小米的，就显示已经配置了的模型"）：
  页面只列**已配齐的模型卡片**（标题=模型名、副标题=地址），底部一个「＋ 添加接入」→
  选服务商 → 同一个弹窗填；删掉 v0.52.0 那三段（硅基/小米/自定义）常驻表单。
  「删除这家配置」= 把该家清空，卡片随之消失；若删的是当前生效那家，
  `saveTtsConfig` 会自动落到另一家已配齐的（不会把通话留在哑火上）。
  分桶判"写过没写过"改用 `contains` 而不是判空——否则清空字段会被老键**复活**。
- **克隆失败根因**（真机 HTTP 400：`Invalid custom name. only letters and digits and _ and - are supported`）：
  服务端 `customName` 只收 ASCII，我们把用户输入的「永雏塔菲」原样发了上去。
  现在上传用 `VoiceCloner.asciiVoiceName()` 生成英文标识，**中文名只存本机显示**
  （`SettingsRepository.cloneVoiceNames()` 那张 `uri → 名字` 表，一行一条 `uri\t名字`）；
  板块与人设页显示的都是中文名，克隆完直接把它设为默认音色。
- **通话"服务商回的 WAV 解不开"根因**：`WavPcm.parse` 只认 PCM16 且要求声道 1–2，
  而硅基回的 WAV 是 **IEEE float32**（v0.52.0 刚把通话改成要 WAV，于是从"MP3 解不开"变成"WAV 解不开"）。
  解析器改成按表头解释：PCM 8/16/24/32 + float 32/64、`WAVE_FORMAT_EXTENSIBLE` 从 SubFormat GUID
  取真编码、任意声道数取均值下混、流式 WAV 的 `data` 长度写成 0 / 0xFFFFFFFF 时按"到文件尾"处理。
  （按用户选择：**只改解析，不做换格式重试**。）
- 手册同步（AGENTS 零.5）：接口卡片与详情弹窗的操作语义、语音合成"已配好的模型卡片 + 添加接入"、
  克隆中文名只本地显示；v0.52.0 那句"两段默认收起、点标题展开"已从手册里清除，
  `AiPromptsTest` 新增 `v0530 卡片与详情弹窗进手册` 双向断言组。

## v0.52.0（versionCode 101）

**语音配置整改：三家各存一份、音色收进一个板块、MP3 解码根因换掉、API 配置一律手风琴。**

- **两条真机反馈是同一个根因**（通话"声音合成失败：音频解码失败" + 克隆"文件格式不对"）：
  `realtime/Mp3Decoder.kt` 用 `MediaCodec` 手搓解码，把**输入** `MediaFormat` 硬写成
  `24000Hz / 单声道`，而硅基真实回的是 44.1kHz（文档：`response_format` 默认 mp3、
  `sample_rate` 默认 32000），hint 与真实流不符就吐 0 帧 → 上层只能报"解码失败 / 格式不对"。
  试听之所以正常，是因为它走 `MediaPlayer`（系统自己协商格式）。
  - 新增 `realtime/MediaAudioDecoder.kt`：`MediaExtractor` 自己读真实采样率与声道，
    **mp3 / wav / m4a / aac / mp4 / flac 一次通吃**；带 5 秒卡死预算与 320 万帧上限，
    宁可失败也不吊住调用线程。`Mp3Decoder` 已删除，不要复活。
  - 通话链路更彻底：`TtsTarget.CALL` 直接向服务商要 **WAV + `sample_rate=24000`**
    （等于播放轨道采样率），**回来一个字节都不用解**；语音条/试听仍要 MP3（小、`MediaPlayer` 直接播）。
    `TTS_CALL_SAMPLE_RATE` 是唯一定义点，`CallAudioManager.OUT_RATE` 引用它，写成两个数就白重采样。
  - 24k/44.1k 之争不再靠猜：`https://api-docs.siliconflow.cn/docs/api/audio-speech-post`
    逐字核过 `response_format = mp3|opus|wav|pcm`、`sample_rate` 默认 32000、`voice` 与
    `references` 互斥（克隆音色用 `voice` 原样发 `speech:` uri 是对的，`references` 是"不预上传"的另一条路）。
- **#1 每家一套独立配置**：以前只有一套 `tts_*` 键，切到小米时 Key/地址/模型 还是硅基那份
  （用户："切换就应该像切换 3 个不同界面的样子"）。现在 DataStore 按 provider 分桶
  （`tts_<家>_api_key_enc/plain / _base_url / _model / _default_voice / _manual_voice_id`），
  `tts_provider` 只记"当前生效哪家"；老单桶数据**虚拟归属到当时生效的那一家**，升级零操作；
  **只有配齐的家才能成为当前生效**（切到没填 Key 的家就保存，不该把通话搞哑）。
- **#3 新增「音色」板块**（设置 → 语音）：预置音色**每家只留两个，一男一女**
  （硅基 `alex`/`anna`，小米 `白桦`/`冰糖`，自定义无预置只留手填 ID）；板块 = 预置 + 我克隆的 + 手填 ID，
  **右上角就是「克隆音色」**，样本除 mp3/wav 外**放开 m4a 与 mp4**（抽音轨，不写 MP3 编码器）；
  点一行 = 设为默认，行尾「试听」。那颗看不懂的"刷新我的克隆音色"按钮删掉——进页面自动拉取。
  名单与标签集中在纯函数 `TtsVoices.library()` / `labelOf()`，**人设编辑页的候选就是这一份**，
  那边的试听与"填音色 ID"一并撤掉（用户点名）。音色三级回落：人设专属 → 板块默认 → 该家第一个预置
  （`SettingsRepository.voiceForConversation()`，聊天与通话共用）。
- **#5 语音条播完一定复位**：原本只靠 `MediaPlayer.onCompletion`，而 MP3 尾部 padding / 缺 Xing 头时
  这个回调可能整场不来 → 气泡一直"在播"。`ChatScreen` 加播放看门狗：按 position/duration 轮询判结束，
  外加绝对超时兜底，UI 不可能卡在播放态。
- **#6 API 配置一律手风琴**：`SettingsSectionRow` 提到 `ui/settings/SettingsPage.kt` 共用，
  对话接口 / 生图接口 / 语音合成三家**默认收起**，收起态只给一行摘要（几条、当前生效哪家、配没配齐），
  点开才有输入框与保存——改一处不会误碰另一处。「AI 提示词」页原本就是手风琴，现在共用同一个组件。
- 测试：`TtsProviderConfigTest`（6 条，Robolectric 真 DataStore）钉住分桶、老键虚拟归属、
  "保存别家不能抹掉硅基 Key"（这条正是写测试时抓出来的实现 bug）、只有配齐才切生效、
  音色三级回落；`TtsConfigTest` 改为两家各两个预置 + `library()`/`labelOf()` + `TtsTarget` 格式与采样率；
  `UiRenderTest` 重写语音两页用例并新增 API 手风琴用例（含"没配齐不该出现别家的 Key"）。
- 手册同步（AGENTS.md 零.5）：语音章补"三家各存一份 / 音色板块 / mp4 样本 / 通话要 WAV /
  板块右上角克隆"，删掉"8 个预置""人设里选音色带试听"等已作废措辞，每条 replace 配一对双向断言。

## v0.51.1（versionCode 100）

**修「应用助手手册永远升不上去」：升级判定从"穷举历史文案精确匹配"改成"用户有没有亲手改过"。**

- 真机反馈：装了 v0.51.0 再问内置助手"语音怎么配置"，它照样答不上来。根因**不在手册文案**
  （v0.51.0 那章教程写得全全的），在启动时那道升级闸门：旧实现要求「库里这段设定词
  **正好等于**某个历史默认文案」才肯刷新，而那份历史表只登到 v0.38 全文——每一版都是在
  `DEFAULT_ASSISTANT_PROMPT` 上原地追加 `.replace()`，中间态从来没登记过。于是在 v0.41~v0.50
  之间升过一次级的用户，库里那段成了"v0.38 + 若干替换"的**孤本**，从此任何版本都匹配不上，
  手册永久卡死在旧版。**机制性缺陷，不是某次改错字。**
- **新机制**（`PersonaRepository.refreshAssistantPromptIfNeeded`）只看两件事：
  ① 用户亲手改过吗——DataStore 三态标记 `assistant_prompt_customized`：在「AI 提示词」页或
  人设编辑里存过不同于默认的内容 = 改过 = App 从此不再自动覆盖；点「全部恢复默认」= 重新纳管。
  老装机从没判定过的（键缺失），用"这段是不是内置手册的形状"（新增
  `AiPrompts.isBuiltinAssistantManual`，认「应用概览」+「回答要求」两节与「导师」时代开场白）
  判一次：形状对得上 = 历史版手册 → 升到最新；对不上 = 用户自己写的 → 一字不动。
  ② 没改过就无条件对齐当前版手册。**以后改手册不需要再登记任何"历史版本全文"。**
- 顺带清掉因此变成死码的六份历史手册全文（v0.34 / v0.35 / v0.36 / v0.36.5 / v0.37.0 / v0.37.2，
  约 340 行）；v0.38 那份留着——它现在是当前版的**底稿**，替换在它上做，KDoc 改成说明这层新身份
  与"改措辞会让 replace 静默落空"的警告。
- **手册内容纠偏**：单聊输入栏那段还在说"输入框左侧按钮是发图"——v0.43.0 起发图/拍摄/文件
  早已收进「＋」面板、表情面板也回来了，v0.50.0 起左侧换成了语音键。按真机实况重写：
  左侧语音键（模型没下载时这一下直接跳下载页）／右侧空输入时是表情与「＋」四格
  （相册·拍摄·文件·通话）／有字时右侧是发送键／长按消息弹菜单点选（复制·删除·语音条转文字）。
- 测试：新增 `AssistantManualUpgradeTest`（6 条，Robolectric 真库）——登记过的旧锚点、
  **没登记过的中间态**（本次修复的正主）、「导师」时代古旧版三种都升到当前版；
  用户自己写的、用户改过的两种一字不动；「全部恢复默认」后重新纳管（导入的旧手册也会被刷成最新）。
  `AiPromptsTest` 补两组：输入栏新措辞在 / 旧措辞一次不剩、手册形状识别的正反例。
- AGENTS.md 零.5 改写：明确"**不需要登记历史版本全文**"，并把这次的机制缺陷写成反例存档。

## v0.51.0（versionCode 99）

**「我的」这条线全部改成全屏页；应用助手手册补上语音整章并立为硬约定；识别模型去掉手动导入。**

- **设置链弹层 → 全屏页**（13 个）：基础档案、AI 图库、AI 提示词、设置、API 配置、外观、
  通知与提醒、数据与隐私、关于、语音、语音合成、识别模型，全部注册成 NavHost 路由。
  动机：设置链现在有三层（设置 → 语音 → 语音合成），弹层叠弹层在手机上盖住整屏、
  返回手势也说不清退到哪一层；而识别模型下载要跑几分钟，用户需要能退出去干别的再回来——
  那只有整页有返回栈才做得到。共用新骨架 `ui/settings/SettingsPage.kt`
  （顶栏返回 + 可滚动 + 版心收窄），路由名集中在 `SettingsRoutes`，不再散落字符串字面量。
  转场与单聊/人设管理同一套手感。`DataPrivacySheet` / `NotificationSettingsSheet` 原本由「我的」页
  透传的 stats 与开关回调，改成页面自取 `MeViewModel`，导出导入与 Android 13 通知权限申请一起搬过去。
  单聊页与通话页不再自己内嵌弹层：点语音键缺模型、通话页缺 TTS 配置，都改成 `onOpenSettings(route)` 跳页。
  人设编辑弹层、消息长按菜单、快捷打卡、记账「记一笔」等**保持弹层不变**（表单/菜单类，改全屏反而难用）。
- **应用助手手册补「语音」整章**：先教用户分清「听」（识别模型，App 内下载）与「说」（语音合成，
  **必须去服务商官网注册拿 Key**）。给出逐步教程：`cloud.siliconflow.cn` 注册 →
  `cloud.siliconflow.cn/account/ak` 新建并复制 API Key → 回 App 粘贴 → 保存 → 试听；
  音色克隆另需先在官网完成**实名认证**；MiMo 走 `mimo.mi.com`，且 Token Plan 订阅 Key
  必须填专属接口地址。外加「语音的三种用法」与「排障对照」两段。
  同步修正手册里已过时的措辞（快捷打卡 / 顶栏搜索图标 / 左侧按钮是发图 / 七段提示词 / 手动导入三条路）。
- ⚠️ **修掉一个静默失效的 bug**：手册升级是在 v0.38 全文上做定点 `.replace()`，而锚点经
  `trimIndent()` 后**段首是没有缩进的**——搜索串多写 8 个空格，`replace` 就整段白改，
  不报错也不崩。v0.50.0 那批手册替换里有两条中了这个坑（「外观」行与「数据与隐私」章节头），
  本次一并改正。
- **立为项目硬约定**（AGENTS.md 零.5 / 零.6）：内置应用助手的使用手册必须跟着每一次版本更新走；
  **每加一条 `replace` 必须同时在 `AiPromptsTest` 补一对断言**（新措辞在 + 旧措辞不在），
  只写一边守不住静默失效；手册里的服务商网址必须是查证过的官方地址，
  不抄第三方邀请链接（参考包里那条 `cloud.siliconflow.cn/i/xxxx` 是爱语作者的邀请码）。
- **识别模型去掉手动导入**：普通用户不知道该下哪个文件，SAF 文件选择器、`importFrom`、
  `AsrImportResult` 全部删除，只剩 ModelScope / hf-mirror 两个自动源。
  兜底改成：下载失败时把**官方仓库主页**以可选中形式显示出来，长按复制去浏览器自己试。
- 测试：`AiPromptsTest` 重写为 8 组双向断言（每条手册升级都要"新的在 + 旧的不在"）；
  `UiRenderTest` 语音态用例改为断言"无模型时跳到全屏下载页"。全量通过，
  截图自检设置页与下载页的全屏渲染。

## v0.50.0（versionCode 98）

**语音栈推倒重来：三套识别 + 两套合成 → 一条识别链 + 一条合成链 + 一个标记协议 + 两个开关；识别模型零内置、装后下载。APK 80.8 MB → 35.3 MB。**

- **识别换端侧离线 SenseVoice**（sherpa-onnx `OfflineRecognizer` + `OfflineSenseVoiceModelConfig`，
  int8 228 MB，中/英/日/韩/粤）。原来用的是 `streaming-zipformer-zh-14M`（1400 万参数、官方定性
  "准确率不佳"），而且梦欢是**录完再识别**，用流式模型等于拿错工具（要补尾部静音才不丢字）。
  顺带能出情绪标签与音频事件（`result.emotion/event`），本期只取 text。
- **砍掉三级级联的两级死路**：云端 Whisper（用户配的端点实测不支持 `/audio/transcriptions`，永远空）
  与系统 `SpeechRecognizer` 喂文件（国产 ROM 的识别服务只监听实时麦克风，喂路径等于没喂，
  走满 20s 超时）。`SpeechToText` 收成单级 = 只有端侧。`OpenAiClient.transcribe` 一并删除。
- **识别模型不再随包**：`data/AsrModelManager.kt` 负责下载（**Range 断点续传 + 逐文件 sha256**）、
  校验、SAF 手动导入、删除释放空间；`ui/settings/AsrModelSheet.kt` 是面板。
  下载源三级：**ModelScope 主源 → hf-mirror 备源 → 手动导入**（2026-10-04 实测：GitHub Releases
  直连 25s 超时 0 字节、hf-mirror 证书 SNI 校验失败，只有 ModelScope 秒通且接口给官方 sha256、
  回 `Accept-Ranges: bytes`，所以提为主源）。
  没模型时点语音键**不进语音态**，直接拉起下载面板；通话页进页即检查并给提示条 + 一键下载。
  就绪判定用「`.verified` 标记 + 两文件字节数全等」而不是每次重算 sha256——228 MB 全量哈希要好几秒，
  而它在每次点语音键时都会被调用。
- **语音消息与通话共用同一个识别实例**（删掉 `CallAsr.kt`，通话改调 `SherpaAsr.transcribeSamples`）。
  228 MB 权重建两个 session ≈ 500 MB，中低端机直接 OOM；而这两条链路从不同时工作。
- **统一 `TtsClient`，三家可切**（`data/TtsClient.kt` + `TtsConfig.kt` + `ui/settings/TtsConfigSheet.kt`）：
  硅基流动 CosyVoice2 / 小米 MiMo / 自定义 OpenAI 兼容端点。三家协议**不统一**，各写一个实现：
  硅基 `POST /audio/speech` 回**裸二进制**、预置音色必须写成 `<model>:<alias>`；
  MiMo 走 **`/chat/completions`**、目标文本必须放 `assistant` 消息（放 user 不会被念）、
  不需要 `modalities`、鉴权 `api-key` 与 `Authorization` 都发、回 **WAV** base64；
  自定义端点只发 `model/input/voice/response_format` 四个最小字段。
  错误体有时是 JSON 有时是裸串（硅基 401/403/404 是裸串），且存在"200 里塞错误 JSON"的网关
  → 统一做首字节嗅探。**用户当下三家都没有 Key，真机出声验收做不了**，
  协议逐条查证记录在 `docs/RESEARCH-v0.50.0-TTS协议查证.md`，并用 MockWebServer 单测把
  请求体与响应解析的形状钉死（`TtsClientTest`）。
- **音色克隆（App 内全自动）**：选样本 → 自动裁到 15 秒内（mp3/wav 解码重存为 WAV）→
  **用端侧 SenseVoice 自动转写出逐字稿**（硅基上传接口的 `text` 是必填，不能只给音频）→
  multipart 上传换 `speech:...` uri → 人设编辑器里选。需先在硅基官网完成实名认证；
  未认证的错误码官方没公开，所以原文透传服务端 message。
  音色按人设存（`tts_voice_<personaId>`，值为 `VoiceRef`），通话与语音条共用一套。
- **AI 语音条**：AI 用 `【语音条】` 标记自主决定这句话用"说"的（`data/VoiceNoteMarker.kt`，
  与 `[生图:…]`/`[操作手机:…]` 同族标记协议）。合成成功后把**刚落库的那条文字原地升级**成语音条
  （不另插一条，避免"一段文字 + 一条说同样内容的语音"），失败则原样留文字并提示原因一次。
  长按语音条「转文字」看回正文。`VoiceBubble` 加 `isUser`：AI 侧改成面色 + 细线（与 AI 文字气泡
  同一视觉体系），用户侧保持黑底药丸。提示词段（框架硬编码 + 策略可编）进「我的 · AI 提示词」第⑧段。
- **通话并入统一链路**：`PipelineVoiceCallEngine` 的 Edge TTS → `TtsClient`；字幕改**按 VAD 段出**
  （离线非流式没有逐字 partial）；barge-in 逻辑不变。新增 `TtsAudioDecoder`：按容器嗅探分流解码
  （MP3 走 MediaCodec、WAV 走 `WavPcm`）并**下混单声道 + 重采样到播放轨道采样率**——
  以前 Edge TTS 恒定 24kHz 单声道 MP3 刚好等于 `OUT_RATE` 所以不用管，换成三家后这个巧合没了，
  不归一化会变速变调。TTS 未配置时不出声、走字幕兜底并明确提示。
- **删除**：Edge TTS（`EdgeTtsClient.kt`）、AI 回复语音朗读（`ReadAloudPlayer.kt` + 单聊右上角喇叭 +
  `chat_read_aloud` 键）。朗读是用户明确作废的功能，不再以任何形式回归。
- **两个总开关**（设置 → 语音）：AI 语音条 / 语音通话（关掉后加号面板不再出现「通话」格）。
- **体积**：去掉内置 zipformer 四件套（25 MB）+ 去掉 `armeabi-v7a`（22 MB so）+ `noCompress` 不再需要
  `txt`。`assets/asr/` 只剩 `silero_vad.onnx`（1.8 MB，继续内置）。**35.3 MB**（v0.49.0 为 80.85 MB）。
- 测试：新增 `AsrModelManagerTest`（续传偏移/Range/sha256/体积换算/下载源 URL）、
  `TtsConfigTest`（音色编解码/`voice` 拼法/容器嗅探/MiMo 上限换算）、
  `TtsClientTest`（三家请求体与响应解析）、`VoiceNoteMarkerTest`（标记剥离边界）；
  `SpeechToTextTest` 按单级重写、`OpenAiClientTest` 删云端转写用例、
  `UiRenderTest` 语音态用例改为断言"无模型时拉起下载面板"并新增 4 张截图。全量 303 项通过。
- ⚠️ **必须真机验收**（三家无 Key，JVM 测不了出声）：识别准确率对比旧 14M、模型下载全流程
  （含暂停/续传/手动导入）、通话整通、语音条播放与转文字、三家 TTS 各合成一句、音色克隆上传。

## v0.49.0（versionCode 97）

**喇叭开关的正确定义 = AI 回复语音朗读；通话记录卡进聊天流**（用户纠正 v0.48.0 的两处理解错误）。

- **AI 回复语音朗读**：单聊页右上角喇叭打开后，AI 的每条文字回复**自动读出声音**（跟
  Edge TTS 同一套合成，音色用人设的通话音色）；关掉即恢复纯文字。发送新消息会打断
  正在读的上一条，新回复到达会顶掉旧朗读。默认关。合成前清洗文本（剥 [生图:…]/[操作手机:…]
  标记与 Markdown 记号），只读自然的话。
- **删掉"发送/接收"提示音效**（用户：要的是说话的声音，不是音效）。
- **通话记录卡**：通话挂断后聊天流里出现一条"📞 语音通话 · X分X秒"气泡（会话列表
  预览同步显示），**点开弹出本次通话的逐轮文字转写**（你/人设名标注，底部 sheet 展示）。
  实现上 `MediaKind.CALL` 新枚举 + `attachmentPath` 存 `call_sessions.id`，Room 无需迁移。

## v0.48.0（versionCode 96）

**修：真机通话 AI 仍无声音 + 新增聊天音效（发送/回复）与右上角喇叭开关。**

- **无声的又一根因：`MODE_IN_COMMUNICATION`**。协议在电脑上已实测打通（v0.47.0），
  真机无声指向音频路由——通话模式下部分 ROM 把 USAGE_MEDIA 的播放路由进无声/听筒通道。
  本次**去掉通话模式与外放切换**，录音仍用 VOICE_COMMUNICATION 源（保留系统回声消除），
  播放走普通媒体通道，"AI 说话"不再被路由吞掉。
- **失败原因可见化**：整轮 TTS 合成失败时，通话页错误条直接显示原因
  （如 `声音合成失败：ProtocolException ... http=403`）——真机再无声一眼定位，
  不用再来回猜。播放器初始化失败同样提示。
- **聊天音效（v0.48.0 新功能）**：发送消息"嗖"（上扬滑音）、AI 回复到达"叮咚"，
  与通话音效同源（CallTones 合成，柔和包络），走媒体音量。
- **右上角喇叭开关**（单聊页顶栏，VolumeOn/VolumeOff）：控制聊天音效，
  DataStore `chat_sound_enabled`，默认开。通话页的声音不受它影响（通话页麦克风静音独立）。

## v0.47.1（versionCode 95）

**按用户拍板重构通话设置的归属与形态**（用户真机复核 v0.47.0 后的五条意见）：

- **三个点 = 编辑人设**（不再单独做"通话设置 sheet"）。删除 `PersonaCallSettingsSheet`；
  单聊页三点直接打开人设编辑弹层，保存走 `ChatViewModel.savePersona`。
- **音色选择进人设编辑弹层**，且**按人设各自保存**（DataStore `call_voice_<personaId>`，
  空 = 默认晓晓）——同一张脸打不同的电话可以用不同的声音。全局通话设置键全部移除。
- 音色选择改为**展开菜单**（不平铺 10 行）：单行"通话音色：当前值 ▾"，点开下拉选择，
  菜单末尾"**导入第三方音色…**"= 自定义入口（输入任意 Edge TTS 声音名，同一入口）。
- 删掉"接通/挂断提示音开关"与"保存转写开关"（用户：肯定要开的/肯定要保留的，不留开关）。
  引擎与仓库层相应去掉 `saveTranscript` 参数，转写恒定保存。
- **提示音换柔和自制双音**（`CallTones`：正弦 + 淡入淡出包络 + 45ms 间隙，AudioTrack 静态
  模式播放）：接通 = C5→G5 上行、挂断 = E5→A4 下行，替换急促的系统 ToneGenerator 蜂鸣。
- 通话模型覆盖一并移除：通话用当前对话接口配置的模型（想快就在 API 配置里切 deepseek-chat）。

## v0.47.0（versionCode 94）

**修：通话完全没有声音 + 一批通话体验打磨**（用户真机反馈八条，同一版本内做完）。

### 没声音（根因 = Edge TTS 协议三处错，JVM 直连端点逐层定位）
- **403 层**：Edge 大声朗读端点已升级版本族——`Sec-MS-GEC-Version` 从 130 时代升到
  `1-143.0.3650.75`、Origin 扩展 ID 修正为 `jdiccldimpdaibmpdkjnbmckianbfold`（原先抄错）。
- **静默层**：SSML 请求是**纯文本帧**（此前错包了二进制头，服务端静默丢弃）；
  `X-RequestId` 必须 32 位 hex（此前 16 位）；URL 必须带 `&ConnectionId=<32hex>`；
  speech.config JSON 尾部还有 `\r\n`。全部对照 `rany2/edge-tts` 当前源码逐项修正。
- JVM 真实网络测试（`EdgeTtsLiveTest`/`EdgeTtsProbe`，常态 @Ignore 作诊断工具）实测
  合成出 15.5KB 合法 MP3。
- 播放改 **USAGE_MEDIA**（媒体音量）——此前 USAGE_VOICE_COMMUNICATION 走通话音量，
  用户通话音量常为 0，真机表现即"没声音"；回声消除仍由麦克风 VOICE_COMMUNICATION 源承担。

### 新能力
- **通话设置 sheet**（单聊页顶栏 **三个点** 进入）：AI 音色（10 个中文预设 +
  任意 Edge TTS 声音名自定义导入）、通话模型覆盖（留空=当前接口模型；填 `deepseek-chat`
  可避开 reasoner 深度思考的等待）、接通/挂断提示音开关、转写保存开关。
- **接通 / 挂断提示音**（ToneGenerator，媒体音量，默认开）。
- **通话入口移位**：顶栏电话图标 → 加号面板第四格「通话」（顶栏三点与人设设置并存）。

### 体验打磨
- 通话页删掉顶部头像栏（与中部头像重复）；状态文案 Crossfade 过渡；
  头像在双方说话时呼吸脉动（无限过渡 + 弹簧收敛，静止时保持 1f）。
- 字幕重做成**歌词式**：贴底、最新一条加粗清晰、上一条次清晰、更旧的透明度阶梯渐隐
  （0.14→1.0），最多显示 6 条；不使用 blur（minSdk 26 真机流畅红线）。
- 接话更快：VAD 收口静音窗 0.6s → 0.45s（用户反馈"我说话快，AI 接得慢"）。

## v0.46.1（versionCode 93）

**修：单聊页顶栏电话图标与放大镜图标并排挤压（用户真机反馈），用户拍板「删搜索保通话」**
——单聊页消息搜索功能整体移除（不只是按键）。

- 删 `ChatScreen`：顶栏放大镜、搜索态状态量、返回键分流的搜索分支、整页搜索覆盖层
  （输入框 + LIKE 命中列表）、`ChatSearchRow` 组件、三个只为搜索服务的 import。
- 删 `ChatViewModel`：`searchResults` 流与 `onSearchQueryChange` 查询逻辑。
- 删 `MessageDao.searchInConversation`（单聊内搜索专用 SQL；
  **`searchMessages` 保留**——会话列表顶部的搜索还在用它，那个功能不动）。
- 删截图测试 `render10ChatSearch`。
- 顶栏现在只有一颗电话图标，无挤压。会话列表的搜索（列表顶部搜索框 / 上滑换放大镜）**不受影响**。

## v0.46.0（versionCode 92）

**实时语音通话（管道版）**：任意人设可以像打电话一样与 AI 持续对话——免按住、免等待，
说完即答，随时打断。用户拍板走「DeepSeek 管道」而非 WebRTC 真双工（引擎做成可替换接口，
将来可插 GLM-Realtime），AI 声音用 Edge TTS（免费），"耳朵"复用内置 sherpa 模型 + 新增
Silero VAD 分段。

### 通话链路（`realtime/` 新包，与语音消息链路完全独立）
- `PipelineVoiceCallEngine`：状态机唯一写手（`RealtimeCallState` 11 态，UI 只观察）。
  主循环 = AudioRecord（**VOICE_COMMUNICATION 源**，系统级回声消除/降噪/增益）持续采样
  → Silero VAD 话语分段 + barge-in 检测（AI 出声期间用户开口 ≥0.3s 即打断）
  → 流式 ASR partial 字幕 → 一句收口（停顿 0.6s 判定说完）→ LLM 流式
  → `SentenceSplitter` 按句切（句末标点即切、超长句逗号兜底，压首声延迟）
  → Edge TTS 逐句合成（OkHttp WebSocket，`Sec-MS-GEC` 时钟令牌照 rany2/edge-tts 移植）
  → MediaCodec 解 MP3 → AudioTrack 单一播放链路。
- LLM 复用现有 `OpenAiClient.streamEvents`（DeepSeek 等任意已配置接口）；
  system prompt 与文字聊天同源组装（人设设定词 + 档案注入），另加"语音通话措辞"
  （口语短句、禁 Markdown）。
- `CallAsr` / `CallVad` 独立实例独立生命周期（通话结束 release），**不碰**
  `VoiceRecorder` / `SpeechToText`（按住说话链路原样不动）。
- **零新增依赖**：VAD/ASR 用已有 sherpa-onnx AAR，TTS 走已有 OkHttp；
  assets 新增 `silero_vad.onnx`（1.8MB，ModelScope 镜像下载）。
- 打断（barge-in）：掐 LLM 请求 + 清 TTS 队列 + `AudioTrack.pause+flush`，
  已播出的句子记入转写；非阻塞分块写 PCM 保证打断时协程能退出。
- 挂断：停录音/播放 → 释放 VAD/ASR/AudioRecord/AudioTrack → 恢复 AudioManager
  进通话前状态（MODE/外放）→ 转写一次性落库 → 引擎作用域 cancel。每次通话新引擎实例，
  二次通话无残留。

### UI 与数据
- 单聊页顶栏新增**电话图标**（`MIcons.Phone`，feather 移植，与放大镜同款无圆圈）；
  新路由 `call/{personaId}`（push 转场与单聊一致，平板双栏同样可用）。
- `RealtimeCallScreen`：大头像 + 状态文案主视觉（连接中/正在听你说话/思考中/正在说），
  字幕区为辅助（你：/人设：，用户侧支持流式 partial）；底部静音（Mic/MicOff）+
  红色挂断（PhoneOff）大圆钮，全部无涟漪缩放反馈。返回键 = 挂断，挂断完成后自动退出。
- 权限：无麦克风权限进页即请求，拒绝显示"需要麦克风权限才能开始通话"+ 重试按钮；
  对话接口未配置显示"实时通话尚未配置"。
- 通话记录：Room **v14**（`MIGRATION_13_14`）新增 `call_sessions` / `call_turns`
  两表 + `CallDao`——通话期间转写只在内存，挂断一次性写入；partial 绝不入库。
- 设置：`call_voice`（Edge TTS 音色，默认晓晓 zh-CN-XiaoxiaoNeural）与
  `call_save_transcript`（挂断后保存转写，默认开）入 DataStore。

### 已知限制（一期）
- 首声延迟约 1.5–3 秒（管道方案的固有代价，用户已知并接受）；体验是"对讲机"而非"电话"。
- Edge TTS 为微软非官方接口，理论上有失效风险（失效时该句降级为纯字幕，通话不断）。
- 通话记录暂无查看界面（已落库，展示入口待下期）；无前台服务，App 被杀通话即断。

## v0.45.1（versionCode 91）

**修：点输入框唤起键盘后，消息列表不跟随，看不到最新一条**（用户真机反馈，每次都出现）。

- 根因：聊天页是 `Column` = [顶栏] + [消息列表 `weight(1f)`] + [输入栏]。键盘弹起时输入栏靠底部
  padding 被顶高 → 列表可用高度（视口）变小；而 `LazyColumn` 视口收缩时**不会自动保持贴底**
  （按"首个可见项 + 偏移"锚定），底部最新消息就被挤出屏幕外。
- 修法（用户选定"贴底才跟随"，微信式）：监听列表视口高度，**只在视口收缩、且收缩前本来贴底**时，
  按收缩量 `scrollBy` 跟随到底；正在往上翻历史则不打断。键盘/面板弹起都适用。
  用**收缩前一帧**的贴底状态判断（`prevAtBottom`），这样键盘无动画瞬时弹起、当前帧最后一条
  已被整个裁掉时也能正确跟随。
- 用户明确要求"动手前先问方向"，本版按「贴底才跟随」实现。

## v0.45.0（versionCode 90）

**输入区/拍摄页/「我的」页的一轮细节打磨**（用户一次提 7 条，同一版本内做完）。

### 对话输入区
- **语音态键盘图标重绘 + 调淡**：原版外框 + 3 列 × 2 行六个键帽点，21dp 下显碎；
  改成「外框 + 一排三个键帽点 + 底部空格横线」，线宽 1.8 → 1.6；颜色从纯黑 `NeuAccent`
  压到中灰 `TextSecondary`（语音态已由"按住说话"条表达，不必再靠纯黑点亮）。
- **面板顶部留白收紧**：表情面板顶部 8dp → 2dp，tab 行贴着输入框。
- **「我的表情」改名「收藏」**：添加表情的唯一入口 = 收藏页网格**末尾一个加号格**；
  删掉原来的右上角 ＋ 与两行空状态说明文字（空的时候只剩那个加号格）。
- **加号面板收紧**：高度 132dp → 104dp、四周 padding 14dp → 8dp，只占三个按钮需要的空间。
- **按钮按压反馈统一**：输入栏图标键、面板格子、加号面板三格全部从 Material 默认 ripple
  换成 `rememberNeuPressState`（scale 0.96 / 110ms）——用户反馈"灰色阴影太久太大"。

### 表情气泡
- 新增 `MediaKind.STICKER`：发收藏表情时写入，气泡走新的 `StickerBubble` ——
  **固定 120dp、不可点开**，与可点开全屏的图片气泡区分（用户明确要求）。
  v0.43~0.44 期间发出的表情存成了 `IMAGE`，按落盘目录 `filesDir/stickers/` 识别回来，老表情同样变小。
  会话列表预览对表情显示 `[表情]`（原来 content 为空会显示成"你："）。

### 拍摄页
- **进入相机的过渡动画**：从聊天页进入时 `AnimatedVisibility` 淡入（叠一层极轻的 0.985→1 缩放，
  对齐全局进场语言），退出淡出，不再硬切。用户明确"拍摄页动画指的是进入相机之前的过渡动画"。
- **翻转按钮动效**：点翻转时**按钮本身**绕 Y 轴转半圈（每点累加 180°，满 360° 归零）；
  **预览画面不做任何动画**——用户纠正"翻转动效是指翻转按钮的动效，不是画面那个动效"；翻转期间忽略连点。
- **修侧滑返回直接回桌面**：拍摄页是叠在聊天页上的覆盖层、不在导航栈里，
  补 `BackHandler { onCancel() }`，侧滑/返回键现在退回聊天页。

### 「我的」页
- 设置面板**删掉「人设管理」行**（与「我的」页一级行卡重复，用户反馈"重复了"）；
- **「AI 提示词」从设置面板提出来**，单独成「我的」页一级行卡（人设设定词 / 基础提示词 / 档案注入说明）。
  设置面板现在只留：API 配置 / 外观 / 通知与提醒 / 数据与隐私 / 关于。

## v0.44.0（versionCode 88）

**键盘避让 + 语音采集整体换成业界成熟方案**（用户拍板：先去 GitHub 找优秀案例再动手，
不再自己打补丁）。UI 布局、交互、视觉效果**零变化**，换的是底下的实现。

### 选了哪些案例、为什么（来源与理由）

| 模块 | 借鉴对象 | star / 维护状态 | 取什么、舍什么 |
| --- | --- | --- | --- |
| 键盘 | Google 官方《控制软件键盘并为其添加动画效果》+ `WindowInsetsAnimationCompat` | 官方文档 / androidx.core 内置 | **取**：逐帧插值的 IME 高度 + "导航条用稳定基线"的思路。**舍**：`DSAppTeam/PanelSwitchHelper`（2.4k star，键盘/面板切换的标准库）—— 2021 年后停更且是 View 体系，Compose 项目硬套必须重写整个输入区，违背"复用现有 UI"的约束；只取它的核心思想（面板与键盘互斥 + 底部偏移取一个稳定基线） |
| 语音（采集） | `alphacep/vosk-android-demo`（母项目 vosk-api **15.2k**，2026-08 仍在更新）、`k2-fsa/sherpa-onnx`（**14.3k**，2026-08 仍在更新）、`ggml-org/whisper.cpp`（**51k**，2026 年仍在发版） | 全部活跃 | **取**：三家 Android 采集层**完全一致**的做法 —— `AudioRecord` 直采 16kHz/单声道/16bit PCM + 自己拼 44 字节 WAV 头。**舍**：内置本地离线模型（需下载 40–80MB 中文模型、加设置项，属重大变更，未拍板前不擅自上） |
| 语音（识别） | 保持 v0.43.1 的三级级联 | — | 云端 Whisper 主力（OpenAI `/audio/transcriptions` 协议）→ 系统识别器兜底 → 失败返回 null 不阻断发送。`SpeechToText` 一行没动 |

### 键盘：三块补丁删干净，换成一个稳定基线
- **根因复盘**：旧版在 `max(ime, navigationBars)` 上连打三块补丁 —— 单调收敛
  （`INSET_SETTLE_MS`）、切换静默期（`OUTPUT_SWITCH_QUIET_MS`）、方向性动画（升 0ms/降 90ms）。
  三块互相咬合（改一个误伤另一个，v0.43.1 连着两轮返工就是这么来的），而根因只有一个：
  **`navigationBars` 被当成逐帧实时量**。键盘可见时它被 IME 吸收成 0，收起时再恢复 48dp，
  中间必有一帧 `ime=0 && navBar=0` —— 取 max 只能修终值，修不了中间帧。
- **新实现**（新文件 `ui/chat/KeyboardInsets.kt`，`rememberBottomBarInset(followKeyboard)`）：
  - `navigationBars` 改用**基线**：只在键盘完全不可见时刷新。键盘弹出/收起全程基线恒定，
    空档帧被基线填平，单调收敛补丁从根上不再需要；
  - 形态互斥由调用方显式表达：面板/语音态 `followKeyboard = false` → 直接返回基线、不读 ime，
    静默期补丁不再需要（旧版靠 280ms 时间窗"猜"这轮要不要键盘）；
  - 键盘高度不做二次插值，直接跟随系统逐帧真实值（沿用 v0.43.1 结论）；
  - `WindowInsetsAnimationCompat.onProgress` 作为 API 30+ 的**增强逐帧源**（收不到回调自动
    回落 Compose 值；`ime=0` 时强制归零，保证万一某次收键盘没触发 `onEnd` 也能自愈，
    不会把输入栏永久顶在上面）。
- 删掉 `ChatScreen` 里约 120 行补丁代码（`InsetCode` / `insetFloor` / `LaunchedEffect` /
  `animateDpAsState` 方向性动画），`switchOutputMode` 收敛为纯粹的"三态互斥 + 键盘显隐"。

### 语音：采集层换 `AudioRecord`，ROM 兼容性问题连根拔掉
- 旧实现用 `MediaRecorder` 输出 WAV，而 `OutputFormat.WAV` 在公开 SDK 里不存在（只能写魔法数 6），
  且 format+encoder 组合在各 ROM 合法性不一致 —— 小米上 `prepare()` 直接抛异常，
  被迫加 "WAV→AAC" 回退链，代价是**录出来可能是 m4a，系统识别器只吃 PCM → 转写恒为空**。
- 新实现按 vosk/sherpa-onnx/whisper.cpp 的统一做法：`AudioRecord` 直采
  `16kHz / MONO / PCM_16BIT`，独立抽水线程写文件，收尾回填 44 字节 WAV 头。
  - **不碰 MediaRecorder 的参数组合表 → 不存在 ROM 兼容性问题**；
  - 输出一定是标准 PCM WAV，云端 Whisper / 系统识别器 / 将来的本地引擎都直接能吃；
  - 构造后必须检查 `state == STATE_INITIALIZED`（构造不抛异常 ≠ 拿到了麦克风），
    失败原因照旧写进 `lastError` 回传 UI 提示；
  - 采样率保留 `16kHz → 44.1kHz` 两条候选（16k 是 ASR 通用输入，44.1k 兜底极少数设备），
    即便走兜底也仍是标准 WAV，下游零改动。
- 对外 API 与旧版完全一致（`start/stop/cancel/lastError/isRecording/voiceDir/formatDuration`），
  `ChatScreen` 零改动；`SpeechToText` 与其单测未动。

### 验证
- `compileDebugKotlin` / `testDebugUnitTest` / `recordRoborazziDebug` 全绿；
  Roborazzi 全量截图（手机 + 平板 + 深色）目视比对，输入栏、底栏、各页布局与 v0.43.1 无差异。
- ⚠️ 键盘手感与录音属**真机行为**（Robolectric 无法模拟 IME 动画/麦克风），
  需真机验收：① 打字态键盘弹出/收起是否平滑无弹跳；② 点表情/加号/语音键后中间是否还有空白；
  ③ 按住说话 → 松手 → 语音条是否正常、AI 是否能转写。

## v0.44.1（versionCode 89）

v0.44.0 的真机返工：**面板/语音态下点输入框被键盘遮住** + **语音转写仍然全空**。
用户拍板方向：端侧离线识别（sherpa-onnx）+ 模型直接打进 APK。

### 一、修「点表情/加号后再点输入框，键盘盖住输入栏」
- **根因**：点表情收键盘时**不清焦点**。再点输入框，因为焦点没变，`onFocus` 回调压根不触发
  （v0.44.0 的修复挂在这上面），而系统会对**已经聚焦**的输入框自动弹键盘 →
  面板态 `panel != 0` 还挂着 → `followKeyboard = false` → 底部只按导航条基线避让 → 键盘直接盖住输入栏。
- **修法**（`KeyboardInsets.kt` 新增 `ImeDismissesPanels`）：键盘与面板的互斥**改由键盘状态驱动**，
  而不是靠每个点击入口各自 `hide/show`：只要观察到键盘真的弹起（`ime > 0`）且当前是面板/语音态，
  就立刻收掉面板/语音态（键盘不动 —— 它是用户此刻明确要的）。
  - 这取自 `DSAppTeam/PanelSwitchHelper`（2.4k star）的核心思想：**面板与键盘互斥的唯一可靠信号
    是键盘自己的可见状态**，不是"我刚才点了哪个按钮"。
  - 保留 `armed` 门闩：必须先观察到一次 `ime == 0` 才允许触发，
    否则"面板刚展开、键盘还在做收起动画"的那几帧会被误判成"键盘弹起"、面板秒关。
- 点击入口的 `switchOutputMode` 保留作为正常路径的兜底，两条路并存互不冲突。

### 二、语音转写成主力换成**端侧离线识别**（用户选定方向）
- **为什么必须换**：v0.43.1 / v0.44.0 攒下两个铁的事实 ——
  ① 云端 Whisper 依赖用户配置的那一个接口额外支持 `/audio/transcriptions`，实测不支持 → 永远空；
  ② 系统 `SpeechRecognizer` 的文件输入（`EXTRA_AUDIO_SOURCE`）在国产 ROM 上普遍不生效
  （识别服务只监听实时麦克风，喂文件路径等于没喂）。
  端侧离线识别**同时绕开这两者**：不碰网络、不碰系统识别服务，任何设备行为一致。
- **借鉴对象**（按第 1 条先找案例再动手）：`k2-fsa/sherpa-onnx`（**14.3k star**，Apache-2.0，
  next-gen Kaldi 官方，2026 年仍在发版，有官方 Android AAR）；
  集成姿势参考 `saysrytoyou/Android-Offline-ASR-TTS-Deploy`（中文端侧离线流式 ASR 完整示例）
  与 sherpa-onnx 官方 Android demo（`OnlineRecognizer(AssetManager, Config)` + `acceptWaveform`）。
- **实现**：
  - `data/SherpaAsr.kt`：懒加载单例识别器（2 线程、greedy_search），加载/解码全程 `runCatching`，
    **失败绝不炸发送链**（JVM 单测没有 native 库，同样安静返回 null）；
  - `data/WavPcm.kt`：把录好的 WAV 解析成 16kHz 单声道 `FloatArray`（按 chunk 遍历，
    容忍 LIST 等附加块；非 16bit / 声道数异常 / 截断数据一律返回 null），纯 JVM 可测（8 条单测）；
  - 喂数据是**按 0.1s 分片 + 边喂边解码**（官方示例做法，避免长录音内存堆积），
    末尾补 0.3s 静音再 `inputFinished()`（流式 zipformer 不补会丢最后几个字）。
- **级联顺序调整为**：① 端侧 sherpa-onnx（主力，全离线）→ ② 云端 Whisper（兜底）
  → ③ 系统识别器（最后兜底）→ 都失败返回 null，语音照发、不阻断。
- **模型**：`sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`（中文流式 Zipformer，1400 万参数），
  取**全套 int8 四件套**（encoder 21.6MB + decoder 1.9MB + joiner 1.8MB + tokens 48KB = **25MB**），
  内置在 `app/src/main/assets/asr/`。
  原定的 small-bilingual 中英包 release 归档 **458MB**（既下不动也塞不进 APK），换成 14M 版。
- **打包配套（缺一个就崩，别删）**：
  - `app/build.gradle.kts`：`androidResources.noCompress += onnx/txt`（sherpa 走 NDK `AAssetManager`
    读模型，**压缩过的 asset 无法随机读**）、`ndk.abiFilters = armeabi-v7a + arm64-v8a`
    （AAR 自带 4 套 ABI 共 127MB so，砍掉只活在模拟器的 x86/x86_64）；
  - `app/proguard-rules.pro`：`-keep class com.k2fsa.sherpa.onnx.** { *; }`
    （数据类字段由 JNI 按名字访问，混淆后 native 层找不到字段直接崩）。

### 验证
- `compileDebugKotlin` / `testDebugUnitTest`（249 条，`WavPcmTest` 8 条新增）/ `recordRoborazziDebug` 全绿；
  全量截图目视比对，UI 与 v0.44.0 无差异。
- ⚠️ **转写准确率与首次加载耗时必须真机验收**（JVM 单测没有 native 库，跑不到真实识别）：
  按住说话 → 松手 → 看语音条下方/历史里的转写文字是否符合预期。
- ⚠️ 副作用：**APK 体积从 3.4MB 涨到 ~80MB**（引擎 so ~54MB + 模型 25MB）。
  嫌大可以把 `abiFilters` 收成 `arm64-v8a` 一个（省约 22MB，代价是 32 位老机器装不上），
  或改回"首次使用时下载模型"。

## v0.43.1（versionCode 87）

v0.43.0 的真机试用返工：**语音链路彻底重做**（AI 终于能听见你说的话）+ 输入区手感 + 拍摄页。

### 修好了什么
- **AI 收不到语音内容（核心 bug）**：v0.43.0 两条识别路都是坏的，所以模型只知道
  "用户发了段语音"却不知道说了什么。
  - 录制格式从 `.m4a`(AAC) 换成 **WAV / 16bit PCM / 16kHz 单声道** ——
    `SpeechRecognizer` 的 `EXTRA_AUDIO_SOURCE` 文件输入**只吃未压缩 PCM**，
    喂 AAC 在多数 ROM 上直接 `onError`，转写永远是空。
  - **删掉整个 `LiveSession`（低版本并行识别）**：它和 `MediaRecorder` **抢同一个麦克风**，
    两边都拿不到音频。现在统一"**先录完、再识别**"，录音期间绝不碰识别器。
  - 识别加 20s 超时兜底，失败不阻断发送（模型会收到"用户发了一段语音，但这边没能转成文字"）。
  - 历史里语音消息统一补背景声明：`（我发了一段语音，说的是：…）`。
- **语音按钮/表情按钮去掉外圈**（用户要求）：改成只有符号本身，颜色调淡（`MutedText`），
  激活时才上强调色。新增 `MIcons.Keyboard`（语音态下语音键翻成键盘）。
- **输入框高度恒定**：原来空态占位与有内容时的 `BasicTextField` 内边距不一致，
  一输入就"变矮"。现在外层固定最小高度、占位与输入区都居中，高度不再随内容跳。
- **键盘/返回三连 bug**：原来**完全没有 `BackHandler`**，返回键被系统拿去收键盘（消费掉第一次），
  第二次才到 NavHost 直接退页。现在加返回键分流：录音中 / 搜索态 / 面板打开 / 语音态 / 键盘在
  → 只收起它们，不退出页面。
- **输入框与键盘移动不同步**：输入栏原来对自己的 inset 做 220ms 缓动、键盘是系统在动，
  两条曲线不同必然"慢半拍"。现在**不做二次插值、直接跟随键盘真实高度**，只对回落给 90ms 抹帧。
- **键盘 + 表情面板同屏留白**：点表情时先 `hide()` 键盘，但键盘收起是动画，
  那几百毫秒 `ime` 仍占着键盘高度，而面板又在下面占位 → 中间一大片空白。现在面板一展开就直接忽略 `ime`。

### 改了什么地方
- **语音交互改成开关式**（用户指定）：点语音键 → 输入框变「按住 说话」、**按住才开始录**；
  语音键此时翻成键盘图标，**再点一下就退出按住说话**。语音条保留点击播放/暂停。
- **语音条长按可「转文字」**：长按弹出的菜单里多了这一项，识别文字显示在**语音条下方**；
  再长按同一条 → 菜单变「取消转文字」，收起文字。识别失败的语音条不给这个入口。
- **表情面板**：删掉「常用 / 情绪 / 手势 / 生活」分区标题与中间那条灰线，
  emoji 变成**一整片扁平网格**；面板高度 268dp → 232dp，格高 40 → 38dp。
- **加号面板**：高度 268dp → 132dp，**只占三个按钮需要的空间**；
  图标盒从"随屏宽撑满的正方形"改成固定 52dp。
- **拍摄页四项返工**：
  - 返回/翻转按钮原来白图标叠在半透明白底上，浅色取景内容下等于隐形（用户原话"纯白没标志"）
    → 改成**白色实心圆底 + 黑色图标**，任意取景内容下都清晰。
  - 底栏布局改成 **左「相册」/ 中「快门」/ 右「翻转」**；相册走系统 `PickVisualMedia`。
  - **拍完先确认再发**：拍下后进入确认态（整屏预览 + 「重拍 / 发送」），点发送才真的发给 AI。
  - **修竖拍照片显示成横的**：给 `ImageCapture` 显式设 `targetRotation`（跟随预览方向），
    并在确认态按 EXIF 物理旋转像素后**回写原文件**，下游任何解码路径拿到的都是正向 JPEG。

### 真机试用第二轮返工（4 条反馈）
- **冷启动先闪「新建人设」**：会话列表原来只有「空 / 非空」两态，而 `rows` 的初值就是空列表 ——
  Room 首个结果还没回来时，界面先按"没有人设"渲染出居中的新建入口，数据到了才换。
  现在引入 **`ChatListState`（rows + loaded）三态**：未加载 → 骨架屏；加载完且为空 → 新建入口；
  有会话 → 列表。`loaded` **从 rows 自身派生**（`rows.map { … }`），不额外发查询
  （另发一条 Room 全表查询会与 `combine` 争抢共享 query executor，实测把列表首值堵死）。
- **点「按住说话」弹「录音启动失败」**：`setOutputFormat(6)`(WAV) + `AudioEncoder.DEFAULT`
  在小米等 ROM 上被判定为非法组合，`prepare()` 直接抛异常，而旧代码 `catch (e: Exception)`
  **静默 return false**，真机上只有"启动失败"四个字、无从定位。
  现在：① 参数改成**回退链**（WAV → MPEG_4/AAC），第一组能 `prepare()` 成功的即采用；
  ② 异常写 `Log.e` 并把原因记进 `VoiceRecorder.lastError`，提示语带上真实原因。
- **键盘返回后输入框停在中间 / 再点表情一片空白**：
  - 根因一：底部避让只看 `panel != 0`，而点表情那一瞬间 `panel` 还是 0、`ime` 还是键盘高度
    → 输入栏被残留高度顶着，面板又在下面展开 = 中间一大片空白。
    现在引入 **「输出形态切换静默期」`OUTPUT_SWITCH_QUIET_MS = 280ms`**：
    键盘↔面板/语音态的**任何**切换都走同一个 `switchOutputMode()`，切换后静默期内底部避让归零，
    等键盘真正收干净再放导航条高度；同时那一刻直接放开单调收敛的下界（不再等 120ms）。
  - 根因二：表情键、加号键、语音键、输入框聚焦各自写了一份 hide/show，漏一处就留下
    "键盘还开着 + 面板也开着"的窗口期。现在**四条入口全部收敛到 `switchOutputMode()`**。
  - 顺手去掉 `BackHandler` 面板分支里多余的 `keyboardController.hide()`：
    它会钻"面板还开着 + 用户刚点回输入框"的空子，把刚唤起的键盘又按下去。
- **返回对话列表时底栏先冒出来、输入框往上跳**：`currentBackStackEntryAsState()` 在
  `popBackStack()` **那一刻**就切回 home，而聊天页退场动画要跑 420ms ——
  这 420ms 里底栏已渲染、Scaffold 的 `innerPadding` 随之加大，正在滑出的聊天页被往上挤。
  现在底栏显隐也走 `AnimatedVisibility`：**进场延迟到聊天页滑走之后、退场立刻开始**，
  并抽 `PUSH_TRANSITION_MS = 420` 让所有 push 路由与底栏共用同一时长。
- 顶栏返回键改为走 `switchOutputMode(showKeyboard = false)` 再 pop，避免退场前半段
  `ime` 仍在变化导致输入栏抖动。

### 真机试用第三轮返工（2 条反馈）
上面那轮"输出形态静默期"矫枉过正，误伤了弹键盘的场景；语音识别则是**换格式也救不了**的
ROM 限制。两条都属于"上一版没修透"，本轮从机制上解决。

- **点表情再点输入框，键盘把输入框挡住**：静默期的本意是「这一轮不要键盘了，别再用键盘高度
  顶输入栏」，可我把它套在了**所有**形态切换上 —— 包括"要弹键盘"的那种。于是点输入框时
  底部避让被压成导航条高度，而键盘正在往上弹、已经盖住了输入栏。
  现在 `switchOutputMode()` 区分方向：**只有"收键盘"才记静默期**；
  **要弹键盘时 `outputSwitchAt` 直接置 0（= 无静默），立刻跟随真实 `ime` 高度**。
- **语音识别不了（真根因）**：上一轮把录音格式换成 WAV/PCM 是对的，但 `EXTRA_AUDIO_SOURCE`
  这个 API 31 的"文件输入"能力**在国产 ROM 上普遍不生效** —— 小米等定制系统的识别服务
  只监听实时麦克风，喂它文件路径等于没喂：`onResults` 不回调、`onError` 也可能不给，
  最后走满 20s 超时返回 null。**光换格式救不了这条路**。现在改成**三级级联**：
  1. **云端 Whisper（主力）**：新增 `OpenAiClient.transcribe()`，走 OpenAI 兼容的
     `POST {base}/audio/transcriptions`（multipart：`file` + `model=whisper-1` + `language`）。
     复用的就是用户**已经配好的那个 AI 接口**的 baseUrl / apiKey，无需额外配置；
     按文件扩展名推 MIME（wav→audio/wav、m4a/mp4/aac→audio/mp4、mp3→audio/mpeg）；
     响应先按 JSON 取 `text`，失败则当纯文本（兼容中转商）；60s 超时，失败安静返回 null。
  2. **系统识别器喂文件**：保留为兜底（云端不支持音频转写 / 用户没配接口时才有用）。
  3. 都失败 → 返回 null，语音照发、`content` 为空，历史里写明"用户发了段语音但没转成文字"。
  - 顺带补上 `<queries><intent android:name="android.speech.RecognitionService"/></queries>`：
    Android 11+ 的**包可见性同样管着 `SpeechRecognizer`**，不声明可能导致
    `isRecognitionAvailable()` 恒为 false（识别器连"可用"都不认）。
  - 关键约束：**云端转写是 `suspend (File) -> String?` 由调用方注入** ——
    `SpeechToText` 是纯逻辑对象，不该知道 API 配置从哪来。
  - 新增 `SpeechToTextTest`（7 条，Robolectric）：守级联契约 —— 云端成功即返回、
    云端 null/抛异常/返回空白串一律降级、文件不存在或为空则短路不发请求、未注入云端不崩溃。
  - 新增 `OpenAiClientTest` 4 条：守 `audio/transcriptions` 的路径 / Authorization /
    multipart 字段 / 纯文本兼容 / 404 → null / 空文件不发请求。

## v0.43.0（versionCode 86）

聊天输入区整体换成**微信风**：语音 / 表情 / 加号三件套落地，并第三次修输入框跳动。

### 新功能
- **输入栏改微信风**（用户提供参考图）：左语音圈 / 中间白底圆角输入框（**不放话筒图标**）/
  右表情圈 + 加号圈；输入框有内容时右侧自动换成黑色发送键。
  输入框改成白色面 + 1dp 发丝线描边（原来是凹陷灰胶囊）。
- **按住说话 → 语音条**：左圈按住即开始录音（上滑 80dp 取消），松开后先尝试把音频
  **转成文字**（API 31+ 用 `SpeechRecognizer` 的 `EXTRA_AUDIO_SOURCE` 直接喂文件；
  低版本在录制期并行做实时识别）。消息列表**只显示语音条**，不显示识别文字 ——
  文字只作为上下文喂给模型（用户明确要求）。
  - 语音条宽度随 duration 变化（0.30~0.56 版心比例），点击播放/暂停，长按弹操作菜单。
- **表情面板**：**一页 emoji / 一页我的表情**，上下滑动选择。
  - emoji 直接插入输入框、再点发送（跟系统键盘 emoji 行为一致）；分四区（常用/情绪/手势/生活）。
  - 「我的表情」点图**直接发出**；右上角「+」从相册添加；长按条目右下角弹菜单删除。
- **加号面板**：相册 / 拍摄 / 文件三格。不做"位置 / 名片 / 转账"那类凑数格子。
- **App 内自绘拍摄界面**：CameraX 预览铺满 + 黑白风控件（顶栏退出、底栏翻转镜头 / 快门）。
  自绘而非调系统相机的原因 = 用户要求界面符合 App 风格，系统相机 UI 不可控。
  拍到 `filesDir/camera/` 下的 JPEG，拍完当普通图片发出。
- **文件发送 + 分类型抽取**：文件走系统选择器，选完在**本地抽正文**再喂模型。
  - 纯文本类（txt/md/json/csv/代码…）直读（带 BOM 处理）
  - docx / xlsx / pptx：本质是 zip，解 `word/document.xml` / `xl/sharedStrings.xml` /
    `ppt/slides/slideN.xml`，剥标签取文本（pptx 按页码排序）
  - PDF：抽**未压缩的文本层**（`BT…ET` 里的 `Tj`/`TJ`，含 `\ddd` 八进制转义 → 字节 → UTF-8）
  - 抽不出（扫描件 / 压缩流 / 二进制）→ 退化成"只给文件名"，不阻断发送
  - 单文件上限 20000 字（`MAX_CHARS`），避免一次吃光上下文预算
  - 发送后气泡显示文件名 + 大小 + 是否成功抽出正文
- **聊天记录新增媒体类型**：`ChatMessage` 加 `mediaKind` / `audioPath` / `audioDurationMs` /
  `attachmentPath` / `attachmentName` / `attachmentSize` 六列；新增 `stickers` 表。
  Room 升到 **v13**（`MIGRATION_12_13`）。存量消息 `mediaKind` 为空时按老规则推断
  （有图 = IMAGE、否则 = TEXT），不做批量回填。

### 修复
- **输入框跳动（第三次修，这次从"取 max"改成"单调收敛"）**：v0.41.4 / v0.42.4 两版都取
  `max(ime, navigationBars)`，只能修"最终值"，修不了中间那一帧 —— 键盘收起时 `ime` 先降 0
  （输入栏掉底）、`navigationBars` 后恢复到 48dp（又顶上去），中间那一帧就是"弹"。
  现在改成**单调收敛**：记住本次会话见过的最大底部高度作下界，升高即时生效、
  降低要等 inset 稳定 120ms 后才放开，输入栏因此只降不升、平滑落回。
- **面板展开时输入栏跑到屏幕中间**：首版把 `panelPx` 也算进了输入栏的 bottomInset，
  但面板本身就是输入栏的**下一个兄弟节点**、自己占着 PanelHeight，两处叠加就把输入栏顶起来了。
  现在输入栏 inset 只由键盘 / 导航条决定，面板自己负责底部避让（`navigationBarsPadding`）。
- **emoji 面板一屏只能看两行半**：格子用 `aspectRatio(1f)`，高度 = 屏宽/8 ≈ 48dp 太浪费。
  改成固定 40dp 行高，同样的 268dp 面板里能铺五行。
- **相机的快门没精确居中**：底栏原来靠固定 `Spacer` 撑，改成三等分 weight，任何屏宽都居中。

### 测试
- 新增 `FileTextExtractorTest`（9 例）：纯文本 / BOM / docx / pptx 页码排序 / xlsx /
  不支持格式 / 超长截断 / 压缩 PDF 抽不出 / 未压缩 PDF 抽出。
- 新增 `MediaKindTest`（4 例）：存量数据推断与显式标记优先级。
- 新增 UI 用例 `render24InputPanels`：表情面板 / 我的表情空态 / 加号面板三张截图。
- 全量 **228 项测试通过**；截图 `45-emoji-panel` / `46-sticker-panel-empty` / `47-plus-panel` 目视确认。

## v0.42.4（versionCode 85）

v0.42.3 那两条修复**都没真正生效**（用户复测指出），这版找到真因并补上防回归断言。

### 修复
- **用户发的图片还是显示在左侧**（v0.42.3 二次返工）：v0.42.3 只在 `ImageBubble` **内部**
  切 `horizontalAlignment`，但它的外层 `Box(Modifier.fillMaxWidth())` 用的是默认
  `contentAlignment = TopStart` —— 无论里面怎么对齐，整块都会被摆在左上角。
  现在外层 Box 也按发送方切 `contentAlignment`（用户 `CenterEnd` / AI `CenterStart`）。
  同时给 `render23ImageBubbleSides` 加了**几何位置断言**（比较图注中心 x 与版心中线），
  这类"静默跑偏"以后会被测试直接拦下。
- **退出对话页输入框还是往上弹**（v0.42.3 二次返工）：v0.42.3 判定为"padding 收得比页面快"
  并冻结了 ime padding，但真因是**两套 inset 的更新时序错位**：
  键盘收起时 `ime` 先降到 0（输入栏掉到底部），紧接着 `navigationBars` 从 0 恢复到 48dp
  （键盘可见时它被 ime 吸收）把输入栏又顶上去 —— 中间那一帧就是"弹"。
  而那个 48dp 是外层 `AdaptiveScreen.navigationBarsPadding()` 加的，冻住 ime 根本管不到。
  正解 = 把两个量**合并成一个**，全权交给输入栏：
  `bottomInset = max(ime, navigationBars)`，键盘可见 = 键盘高度、收起 = 导航条高度，
  单次连续无跳变，再配 220ms EaseOutExpo 平滑下落。
  为此给 `AdaptiveScreen` / `AdaptiveSplitScreen` 加了 `applyNavigationBarsPadding` 开关，
  单聊路由传 `false` 把这块让出来。

## v0.42.3（versionCode 84）

五处手感和定位修正：今日建议改成居中对话框、本周回顾去掉无用总数、入场动画这次真的修好了、
退出对话输入框不再上弹、用户发的图片归位到自己那侧。

### 优化
- **今日建议改成屏幕正中央的对话框**（用户要求「从中间弹出」）：原来是底部面板
  （`ModalBottomSheet`），改用居中 `Dialog` —— 白面圆角卡片 + 暗色遮罩，点空白处关闭。
  同时把弹窗从可滚动容器里挪了出来（塞在 `verticalScroll` 里会让弹窗定位异常）。
- **本周回顾条删掉顶部 `已完成 / 总数` 大数字**：7 天格子条已逐日呈现完成情况，
  进度条给出本周合计，再挂一个总数是重复信息。

### 修复
- **本周回顾条的入场动画切 tab 进来时不播（第二次修，这次找到真因）**：
  v0.42.1 用 `Modifier.onPlaced` 试图区分"组合了"和"显示了"——**这招无效**：
  `onPlaced` 只在节点首次进入布局时回调，而 Pager 会把相邻页一并预布局，
  屏外页照样触发，等于没修。
  正解 = **由外部喂"我现在可见"信号**：`DataScreen` 新增 `visible` 参数，
  由 `pagerState.settledPage == 1` 驱动；可见性一变，动画就从头播一遍。
  切 tab 进来、从子页返回都能看到。
- **退出对话页时输入框往上弹**：根因是键盘收起（~250ms）比页面滑出（420ms）快，
  输入栏的 ime padding 收得比页面滑得还快，相对页面就是**往上走**的。
  v0.41.4 的 `animateDpAsState` 只是放慢了这个过程，没解决方向问题。
  现在改为**只在键盘升起时跟随，键盘一开始收起就冻住 padding**——
  退场时输入栏零自身位移，完全由页面的下滑动画带走。返回键同时先收键盘再退出。
  > ⚠️ **本条结论已被 v0.42.4 推翻**：真正原因是 ime 与 navigationBars 两套 inset 的
  > 更新时序错位，冻结 ime padding 管不到外层 AdaptiveScreen 那 48dp。
- **用户发的图片显示到 AI 那一侧**：`ImageBubble` 一直无脑左对齐。
  现在按 `isFromUser` 决定靠边（用户靠右、AI 靠左），图注也跟着走。
  > ⚠️ **本条修复不完整，v0.42.4 已返工**：漏了外层 `Box` 的默认 `contentAlignment = TopStart`。

## v0.42.2（versionCode 83）

减法继续 + 交互收敛：数据页再删两处文案，生成/提炼入口全部撤掉手，今日建议改为点开弹窗，
周报改成默认折叠，并修掉一个只在返回时才看得见的入场动画时机错误。

### 优化
- **数据页再删两处文案**：本周回顾去掉「本周完成度 xx%」与「🔥 连续 N 天全清」徽标，
  卡片只剩 `已完成 / 总数` 主指标、7 天格子条与进度条（完成度其实进度条已经表达）。
- **撤掉区块头的手动动作**：数据页「今日建议」区块头不再有「重新生成」，
  「AI 洞察 / 周报」区块头不再有「提炼」。这两个动作都容易误触把已有内容洗掉，
  现在只保留展示；生成动作收进弹窗（见下条），提炼仍由系统按水位线自动进行。
- **今日建议改成点击弹窗**：点建议卡（或空态卡）弹出面板，完整建议、生成 / 重新生成按钮
  都在面板里；长按仍是「转为任务」。未配置接口时生成按钮置灰。
- **AI 周报改为可折叠且默认折叠**：进 AI 洞察详情页时周报全部收起，点标题行才展开全文。
  此前是自动展开最新一期，十几期全文摆开会淹没上面的洞察区。

### 修复
- **本周回顾条的入场动画只在"返回"时才看得见**：根因是四个顶级 tab 在
  `HorizontalPager(beyondViewportPageCount = 3)` 里**同时组合**——数据页在后台就已经
  组合并把动画（进度条扫出、格子逐个落位）一路播完了，等真正切过来时早已播完。
  改用 `Modifier.onPlaced` 触发（屏外页只组合、不放置，天然过滤后台预热），
  切 tab 进来 / 从 push 页返回都能重播。
  **注意：`onPlaced` 里改 State 必须只放行一次**，无条件自增会「layout → 改 State →
  再 layout」死循环（实测直接把整套截图测试挂成 `AppNotIdleException`）。
- **点周报打不开**：周报卡上铺满的交互热区写的是 `onClick = {}`（只接管长按），
  又盖在真正带 `clickable` 的容器上面，点击被它吞掉。现在点击统一交给这层热区处理。

## v0.42.1（versionCode 82）

一轮「做减法」的整备：把界面上的阴影与说明性小字全部拿掉，记账本入口合二为一，并给记账本补上周度统计。

### 优化
- **全应用去阴影**：所有卡片、弹层、菜单、对话框一律改成纯平面（白面 + 1dp 细线），
  `neuSurface` 的纸叠阴影整体关闭；`ModalBottomSheet` / `DropdownMenu` / `AlertDialog`
  三类 Material 容器的海拔逐个显式归零。
- **删掉说明性小字**：本周回顾的「本周任务全部清完」、热力格的「点格子看当天明细」、
  空态的「这天没有任务」「还没有产出，聊聊就有」「每天早上自动生成 / 配置 AI 接口后即可生成」
  等纯提示文案全部移除。**保留数据标签**（现金余额、收入 / 支出 / 结余、日期等）。
- **记账本入口合成一块卡**：数据页原来「区块头 + 明细预览」两块合并为单张余额卡
  （钱包图标 + 「记账本」+ 余额金额 + 右箭头），**点整块进记账本全屏页**，
  流水明细只在记账本页看。

### 新增
- **记账本周度统计**：月度统计卡的标题变成**可点切换**（月 / 周两档），
  切换后显示「周度统计」+ 周区间（如 `9月28日 – 10月4日`）+ 本周流水笔数，
  细柱固定 7 根（周一 → 周日）。**周起点为周一**，与数据页「本周回顾」口径一致。
  周期左右切换按钮在两档下都可用（月档翻月、周档翻周）。

### 技术
- `LedgerMath` 新增纯函数：`weekStartOf` / `shiftWeek` / `weekTotals` / `weeklyNetCents`
  （周区间 `[weekStart, weekStart+7)` 左闭右开）。
- `DataViewModel` 新增 `LedgerStatsScope { MONTH, WEEK }` 与 `periodStats` 单一数据源，
  取代原先只服务月档的 `monthStats`；5 项新增 JVM 单测覆盖周起点、跨月跨年、同周多笔累加。

## v0.42.0（versionCode 81）

本轮把「我的数据」页从一张报表改成了真正会用的一页：**本周回顾置顶、今日建议独立生成、
记账本落地、AI 洞察与周报合并成一张卡**。

### 新增
- **记账本（新）**：收入与支出双向流水，现金余额 = 全部流水有符号累加
  （支出自动扣减、收入自动累加，逐笔算出，不单独存余额，删记录不会算错）。
  **不分组、不分类**——用户明确说不需要，只有金额、备注、时间、来源四个字段。
  金额以「分」存 Long，规避浮点累加误差。
  - **三种录入**：手动输入 / 语音（系统 SpeechRecognizer，零第三方依赖，
    点「语音」才申请录音权限，不在进页面时突袭）/ 识图（拍账单图走 vision 模型
    解析出金额与收支方向）。
  - **月度统计**：按月切换，收入 / 支出 / 结余三栏 + 每日净额双向细柱。
  - 入口在**数据页底部**，点击进独立全屏页；流水长按只弹菜单，点菜单里的「删除」才真删。
- **今日建议（独立生成链路）**：不再复用洞察提炼，新建 `daily_suggestions` 表 +
  每天 07:30 的独立闹钟 + 独立 prompt（`DailySuggestionMath`，可自定义策略）。
  生成上下文 = 今天该做的事 + 待办标题 + 档案 + 洞察 + 昨天的支出。
  同一天重复生成覆盖不追加；**长按可「转为任务」**，转换后标记已转，不会重复转。
- **AI 洞察与 AI 周报合并入口**：数据页只留一张卡（显示「N 条洞察 · M 期周报」），
  点击进全屏详情页。洞察全文与周报全文都在那边，周报最新一期进页面即默认展开。
- **本周回顾条（置顶）**：把原来口径不同的「打卡热力」与「完成度环」合并成一张卡。
  **口径统一为「今天该做的事 = 每日任务 + 当天到期的基础任务」**（与任务页、
  每日总结通知一致）。**点任意格子展开当天明细**（当天要做什么、做完哪些）。
  连续全清天数沿用宽恕规则：今天没全清不断签，没有任务的天不打断。

### 优化
- 数据页信息层级重排：原来的两大板块（洞察列表 + 周报全文）下移到全屏页，
  首屏只剩「这周做得怎么样 → 今天建议做什么 → 钱花哪了」三件事。

### 技术
- **Room v12**：新增 `ledger_entries`（索引 `occurredAt`）与 `daily_suggestions`
  （`day` 唯一索引）两张表，配 `MIGRATION_11_12`，非破坏性迁移。
- 新增 `RECORD_AUDIO` 权限与 `DailySuggestionReceiver`（到点自排明天 + 补昨天/今天）。
- 新增 `LedgerMath`（余额/月度聚合/金额解析/收支方向判定）与 `LedgerParser`
  （语音文本→草稿、识图 JSON→草稿）两组纯函数，全部有 JVM 单测。
- 数据页两处入口行加 `testTag`（`ledger_entry_row` / `ai_entry_card`）：
  卡片内标题文本与区块头一致，测试点击必须靠 tag 消歧。
- **拍照测试教训**：`verticalScroll` 里首屏之外的节点直接 `performClick()` 不生效，
  而页面自身同名文案会「骗过」后续断言——必须 `performScrollTo().performClick()`，
  并补一条只存在于目标页的文本断言确认真的跳走了。
- 全量 207 项 JVM 单测 + Robolectric 截图回归全绿（新增 `render19LedgerPage` /
  `render20LedgerEntrySheet`，覆盖记账页与记一笔弹层）。

## v0.41.4（versionCode 80）

### 优化
- **AI 回复改为「后台生成、一次性发送」**：回复在后台完整生成完才整段上屏，
  等待期间只显示一个三点跳动的 AI 气泡。此前是逐字打字机（SSE 一到就往外吐），
  现在读起来是"她想完了，一次说完"，不再有半句话悬在屏幕上的中间态。
- **退出对话的动画重做**：① 滑出时长从 280ms 拉到 420ms，并让页面同步轻微缩小
  （1 → 0.96）收束——之前纯平移"唰"一下就走完，观感太仓促；
  ② 修掉**退出时输入框向上闪一下**：输入栏原先直接把 `imePadding` 挂在整行上，
  返回时键盘收起使底部内边距从键盘高度瞬间掉到 0，输入栏在滑出过程中被"顶"一下。
  改为对内边距做 220ms 动画收拢，输入栏随页面一起平滑滑走。
  ③ 退出时不再叠加淡出（边缩边淡会让输入栏在最后几帧透明化，是"闪一下"的另一来源）。

### 新增
- **长按查看思考过程**（推理类模型）：deepseek-reasoner / GLM / qwen-thinking 这类模型
  在正文前会先生成一段推理内容（`reasoning_content`），此前被直接丢弃。现在随回复一起
  存进本机（Room v11，`messages.reasoning`），**长按该条 AI 消息 → 「查看思考过程」**
  即可展开全文。思考链只在菜单里按需查看，不混进气泡文字，也**不会**回送进下一轮请求
  （回送既费 token 又会把模型上一轮的旧思路重新灌进上下文带偏本轮）。
  非推理模型的消息没有这段内容，菜单里不显示该入口。

## v0.40.1（versionCode 75）

### 优化
- **进对话页不再闪烁/卡顿**：两处根因——① 消息从数据库加载完成后的首次定位走的是
  "带动画横扫到底部"（长会话肉眼可见地猛滚），改为**无动画直接跳到底部**；
  ② 每次有新消息落库都强行把列表拽回底部（哪怕你正在翻历史），改为**只在本来就贴着底部时跟随**。
- **生成回复时的卡顿消除**：打字机每跳（45ms 一次）原本会把整个消息列表拷贝一遍再挂尾巴，
  长会话下每秒几十次全列表分配、GC 抖动掉帧。改为**流式尾巴脱离消息列表单独渲染**——
  每跳只重绘正在生成的那一个气泡，列表完全不动；回复结束后再整体合入消息流。

### 修复
- **图库回填的并发缺陷**（本版本自查发现并修复）：v0.40.0 把"老图补录成生成记录"放在
  应用启动期并发执行，实测会与启动期的其它协程争抢 Room 查询线程池、把会话列表的数据流堵死
  （Robolectric 下 100% 复现，真机为潜在风险）。改为**打开图库时懒回填**：低频、串行、
  不占启动路径，用户价值不变（第一次打开图库自动补录老图，仍标"未知模型"）。

## v0.40.0（versionCode 74）

### 新增
- **图库升级为完整生成记录**（Room v10，`image_generations` 表 + 正式迁移）：
  每次生图——不管成功失败——都留痕：**生成时间、画面提示词、用的哪个生图模型、哪个会话发起**。
  失败的请求标红「失败」、成功但文件已不在磁盘的标「已丢失」，成功的带缩略图，点开进全屏查看。
  此前失败只弹一句错误条，事后无从追溯。升级时若记录表为空，会把历史版本生成的旧图
  从消息表一次性补录为"成功"记录（当时没记模型名，显示"未知模型"），图库不丢老图。
- **生图过程动画重做成波点浮动**：深浅两层的圆点散布卡面，各自以不同速度、不同相位
  上下浮沉 + 轻微横向漂移——近层点大且实、远层点小且淡，像水面上的波点。
  （此前的"公转轨道"粒子样式按用户反馈废弃。）

## v0.39.0（versionCode 73）

### 新增
- **上下文按 (0, 200000) 计费档全量携带**：服务商按单次请求长度分档，20 万 token 以内半价、
  超过翻倍（用户服务商实价）。历史从"固定最近 20 条"改为**字数预算**——按 1 字 ≈ 1 token
  保守折算，历史字数封顶 20 万字（扣除设定词/档案与回复余量），从最新往最旧收，
  聊到 20 万字之前就是全量记忆；最新一条永远保留。
- **图片点击全屏查看**：聊天里 AI 生成的图、你发的图都能点开——黑底全屏、捏合缩放（1–5 倍）、
  双击放大/还原、单击关闭；查看器用 ~1080px 高清解码（列表缩略图仍是低分辨率，滚动不卡）。
- **我的页「AI 图库」**：AI 生成的图片合集（含对话里发起的和手动生成的），按时间倒序三列网格，
  点缩略图进全屏查看；图片文件被清掉后自动从图库消失。
- **生图过程粒子特效**：生成期间的小方卡里一团墨色粒子绕中心公转、呼吸、明灭
  （每循环整数圈/整数次，无限播放零接缝），替换此前的 shimmer 光带；
  按用户要求去掉了"正在生成图片…"的文字说明（画面提示词图注保留）。

## v0.38.3（versionCode 72）

### 修复
- **生图自发放滥**（用户实测：让画一次之后开始频繁自发生成，根本没要求）：根因是
  **自我强化循环**——生成过的图片以 assistant 消息留在对话历史里（内容就是那段英文提示词），
  模型看到自己"这样输出过"就照葫芦画瓢继续发 `[生图:…]`。两层修：
  - 历史里已生成的图片一律改写成过去完成时「（此前已生成过一张图片：…）」，
    不再以原始提示词的形态出现在可模仿的位置；
  - 生图策略默认文本收紧：明确"被动能力，只看用户最近一条消息有没有明确要求；
    没人要求却主动发图片标记是严重错误；同一话题最多画一次"。
    *自定义过「生图触发策略」的到 设置 → AI 提示词 → ⑥ 点「本段恢复默认」拿新规则。*

## v0.38.2（versionCode 71）

### 修复
- **提炼不再把 AI 角色当成用户**（用户实测：AI 角色的人设/称呼被提炼进自己的档案，真正的用户画像反而没提炼）：
  - **转录标注角色名**：提炼输入从干巴巴的「AI：」改为「AI角色「角色名」：」，并在开头声明
    「该角色全部内容是虚拟扮演，不是用户本人说的」——模型第一次有信息分清哪边是真人。
  - **提炼框架加铁律**（硬编码层）：AI 角色一方的性格/设定/称呼/台词/情节一律不得进入
    档案/洞察/任务建议；用户与角色的关系、互动方式、对角色的态度也不是用户画像。
  - **默认策略收紧**：facts 明确排除清单（AI 角色属性、一次性事件、当下情绪、扮演情节、
    称呼昵称、琐碎细节）；insights 明确排除单次对话内容与扮演情节。
    *自定义过「AI 洞察策略」的用户到 设置 → AI 提示词 → ④ 点「本段恢复默认」拿新规则。*
- **AI 周报同源修复**：周报提示词明确「标注为（AI）的一方是虚拟角色扮演，周报只写用户本人的真实生活」，
  角色扮演情节不再被写进周报。

### 说明
- 已被污染的存量档案/洞察不会自动删除（避免误删真人信息）：到 我的 → 基础档案 删掉 AI 角色相关的条目，
  洞察在数据页长按删除。
- 灰线问题（消息气泡左缘）即 v0.38.1 已修的描边外溢，装本包即消。

## v0.38.1（versionCode 70）

### 修复
- **退出对话不再跳到任务页**：home 的 deep link（`menghuan://open/{page}`，来自通知/小组件）
  参数会永远留在 back stack entry 的 arguments 里，从单聊等 push 层返回重新组合时
  `LaunchedEffect` 再跑一遍就把 Pager 拽回深链 tab。现在 deep link **只在该 entry 上生效一次**
  （按 entry id 记消费标记，`rememberSaveable` 扛旋转），返回时 Pager 自然恢复离开时的页面。
- **消息气泡左侧的黑线**：`neuSurface` 的描边是居中描边（`Stroke(1dp)` 画在边界上），
  外半圈伸出布局边界 0.5dp——右对齐、宽度随文字变化的气泡落在分数像素上时，
  外溢的半圈在浅色背景上呈一条黑边。改为**描边内缩半宽**，整体落在形状内部
  （全局生效，观感不变）；这也顺手消除同类分数像素毛边。

### 优化
- **生图过程可视化**（ChatGPT 式）：修复 `generatingImage` 从未被置位、占位分支是死代码的问题——
  此前生图期间用户什么都看不到，图"突然"出现。现在生成期间消息列表底部挂一张动画卡：
  小方卡 + 对角 shimmer 光带循环扫过 + "正在生成图片…" + 画面提示词；生成完成由真图接管，
  失败则错误条提示并把占位文案改写成「（图片生成失败）」，不再永远停在"正在生成"。
- **AI 文字打字机节奏**：SSE 块是突发到达的，直接上屏会一顿一顿。现在缓冲全量文本，
  UI 按固定步速匀速追赶显示（45ms 一 tick，落后越多步子越大），观感是自然打出——
  不太快也不太慢。
- **"对方正在输入"三点动效升级**：等高等距圆点 + 错落弹跳（缩放 + 上浮 + 透明度波浪），
  替换原先大小不一的呼吸点。

## v0.38.0（versionCode 69）

### 新增（八件套，2026-10-02 用户拍板"全做"）
- **发图给 AI 看（vision 输入）**：单聊输入栏新增图片按钮，选一张照片立即发出
  （发送时输入框里的话作为图片说明）。图片拷进应用私有目录 chat_images/，请求时现场
  降采样转 base64 走 OpenAI 兼容 vision 格式；落盘的始终是原图。所用模型不支持看图时
  （HTTP 400）错误提示会说明"也可能当前模型不支持看图"。
- **人设主动关心**（默认关，通知设置里开）：每晚 20:30 让最常聊的人设以自己的口吻主动发一条
  消息进会话（基于你的档案与最近 7 天未完成任务，60 字内），通知点击直达会话。
  每天最多一条；没聊过、API 未配置、生成失败都静默跳过——宁缺毋滥。
- **日期型记忆**：AI 识别到你提到未来某天要做的事（面试/约会/截止）时，任务建议带到期日期
  （宽容解析"10月9日 / 2026-10-09 / 10/9"），确认添加即落到任务日期，逾期照常沉底标红。
- **Token 用量透明**：对话 / 提炼 / 周报 / Agent 的 LLM 调用都会在服务商返回 usage 时记账
  （按自然月累计，跨月自动重置），API 配置面板底部显示「本月 API 用量」；
  拿不到用量数据就不显示，绝不猜。
- **Agent 进度通知**（默认开）：AI 操控手机期间一条低打扰常驻通知实时显示第几步，
  点开直达发起会话（新深链 menghuan://chat/{会话}，返回键回首页不闪退）。

### 变更
- **错误条分级**：不是所有错误都给"重试"——网络类保留重试；无障碍未开启给「去开启」
  直达系统设置；API 配置问题给「去配置」就地叠开配置面板；纯提示无按钮。
  （此前任何错误都给重试，而重试会让模型重新回复、可能把手机操作再执行一遍。）
- **Agent 应用白名单**：从手填包名改为**从已安装应用里勾选**（任务页 ▶ 弹层），
  开关默认关 = 不限制；开了但一个都没勾同样不限制，不会"全锁死"。

### 说明
- 清除全部数据 / 一键重置现在会连同本地图片文件（聊天发图 + AI 生成图）一起删除。
- 应用助手手册升版 v0.38.0，v0.37.2 全文登记进历史默认文案链（现七段），未改过的老助手自动升版。
- 测试 124 → 141：新增 AgentChatGate 分级、日期宽容解析、用量月份键与格式化、
  vision 请求体组装、AgentRunner 状态机链路等 17 测。

## v0.37.2（versionCode 68）

### 变更
- **AI 周报卡片删除折叠**（用户要求）：去掉「展开全文」按钮与 2 行预览收起态，
  正文 200–400 字直接完整显示；卡片 = 期标签 + 生成日期 + 全文，点击看历史、长按分享不变。
  之前收起时想看内容得两段式点击（箭头或「展开全文」），信息密度反而低。
- **AI 提示词面板手风琴重构**（用户反馈"太繁琐"）：此前七段平铺 = 一面提示词墙
  （框架只读框 ×4 + 策略输入框 ×7 + 每段恢复默认按钮 + 两段生成逻辑长文）。
  现在**默认全部收起，整页只有 8 行索引**（七段 + 生成逻辑说明）：
  - 点一段才展开编辑，同一时间只展开一段，再点收起；
  - 「已自定义」徽标收起时也在行内可见，改过没改过一眼分清；
  - 「本段恢复默认」只在真的改过时出现，没改过的段不放空按钮；
  - 手机操控段的总开关直接放在行内，不用展开就能切；
  - 「生成逻辑说明」两段长文收进折叠行，需要时再展开。
  分层语义不变：框架硬编码只读（改了 parse 会崩），策略可编可恢复。
- 应用助手手册同步（周报全文 / 提示词折叠两处），v0.37.0 全文登记为历史默认文案，
  未改过的老助手启动时自动升版。
- 测试同步：render16 改为断言全文直接可见且「展开全文」不存在。

## v0.37.1（versionCode 67）

### 变更
- **设置链信息显示优化（纯 UI，无功能变化）**：
  - **设置主面板副标题全部改实时状态**：不用点进二级页，每行一眼看清——
    人设管理「已建 N 个人设」、API 配置「当前：DeepSeek · 生图已配置/未配置」、
    AI 提示词「七段可编 · 手机操控开/关」、外观「跟随系统/浅色/深色」、通知「已开启 N/3 项」、
    数据与隐私「导出 · 导入 · 清除数据」。
  - **API 配置页反馈可见性**：切换 / 删除 / 保存接口、保存 / 清除生图接口的提示原先写进了
    只在编辑表单里渲染的状态——表单没开时点"切换"等于没有任何可见反馈；现在走独立的
    列表层反馈行，始终显示在面板里。接口地址显示统一去掉 `https://` 协议头（更短更扫读）；
    生图接口摘要行标题改为模型名（板块头已写"生图接口"，行内不再重复）。
  - **通知与提醒**：三个开关各配一句功能说明小字（到点通知 / 每日完成汇总 / 周报生成后通知，默认关）。
  - **数据与隐私**：内容分三组——本机数据 / 备份与恢复 / 危险操作，两个危险按钮不再和导出导入混排。
  - **AI 提示词**：七段标题右侧新增「已自定义」徽标（与默认值不同即显示），
    一眼看出哪些段改过、哪些还是出厂默认；点「本段恢复默认」实时消失。

## v0.37.0（versionCode 66）

### 新增
- **对话内 AI 操控手机**：在单聊里直接用文字让 AI 替你操作手机（"帮我打开备忘录记一条明天交稿"），
  不再去任务页手动填目标。与 `[生图:…]` 同构的单行标记协议 `[操作手机:目标]`：
  - **能力注入有门槛**：无障碍服务在线且总开关打开时才把能力说明注入 system prompt，
    否则模型不会发起标记（提了也执行不了的事不吊胃口）。
  - **完全权限、发起即执行**（2026-10-02 用户拍板）：无确认卡、执行中敏感动作不打断；
    保留的围栏 = 应用白名单（沿用任务页配置，默认不限制）+ 悬浮「■ 停止」+ 15 步上限。
  - **接口跟随本会话**（v0.36.4 语义）：决策循环每一步都用该会话生效的接口；
    任务页 ▶ 手动入口仍走全局接口，行为不变。
  - **执行挂在应用级作用域**：Agent 要切走前台去操作别的应用，退出聊天页不再掐断执行
    （任务页入口此前就挂 VM scope，但 Pager 页永不销毁，无实际差异）。
  - **聊天页执行卡**：目标 + 步骤日志实时滚动（新日志自动滚到底、⛔/⚠️ 标红）+「停止」按钮；
    重进会话时执行仍在跑会自动重新挂上卡片。执行结束摘要以「🤖 自动执行「目标」完成/未完成（N 步）：…」
    用户侧消息落库进 LLM 历史（打卡回执同款语义），卡片随后自动收起。
  - 落库文字剥标记：整条回复只有标记时用占位文案「（好的，交给我来操作）」；
    标记独占一行时换行一并剥掉，不留空行。

### 说明
- 设置 · AI 提示词：**六段 → 七段**，新增「⑦ 手机操控」= "允许 AI 在对话中直接操控手机"总开关
  （默认开，关掉即不注入能力）+ 分层策略段（框架只读 / 策略可编 / 本段恢复默认）。
- `AgentRunner` 扩展：`State.Running` 携带 goal、记录发起会话 `runningPersonaId`、
  `start` 新增 api / autoConfirm / personaId / onFinish 参数（任务页调用点零改动）；
  `AgentLoop.run` 支持外部传入 ApiConfig。
- 应用助手使用手册升版 v0.37.0（单聊说明 / 七段提示词 / 自动化两入口），v0.36.5 旧全文登记进
  历史默认文案链，未改过的老助手启动时自动升版。
- 测试 110 → 124：新增 `V037AgentChatTest` 14 测（标记解析 / 剥标记含独占一行吃换行 /
  分层提示词拼接）；全量回归遇到一次 Windows DataStore 文件锁环境抖动（Robolectric 临时目录被占用），
  重跑即消，与改动无关。

## v0.36.5（versionCode 65）

### 变更
- **「清除全部聊天记录」升级为「清除全部数据（保留人设与接口）」**：填的是"只清聊天"与
  "一键重置（连人设与 API 都没了）"之间的空档——清掉积累的数据，留下配置与身份。
  - 删除：全部聊天消息、AI 洞察、AI 周报、用户档案、任务与打卡记录（`task_completions`）。
  - 级联清理：任务提醒闹钟（清库后无法再枚举任务，所以先取消）、提炼水位线 `extract_upto_*`、
    未读标记 `last_read_*`、聊天草稿 `draft_*`、`TaskSuggestionHub` 进程内的待展示建议与"当日已建议"状态。
  - 保留：人设、对话/生图接口配置、提示词覆盖、主题、通知开关、会话级接口覆盖 `persona_api_*`。
  - 二次确认对话框把"将删除"与"保留"两部分都写清楚，避免误删在用的任务清单。

### 新增
- **AI 洞察卡长按 → 菜单「删除」**（v0.35.0 曾整体取消洞察长按菜单，现按需恢复）：
  长按本身不删东西，只弹出锚在卡片右下角的菜单，点菜单里的「删除」才真删——破坏性操作必须有一次可见的确认。
- **AI 周报历史列表长按 → 菜单「删除」**：同一套交互；删掉正在展示的那一期会自动回落到最新一期。

### 说明
- 应用助手使用手册同步更新（数据与隐私一节 + 数据页长按说明），v0.36.0 旧全文登记为历史默认文案，
  没被用户改过的老助手启动时自动升版。
- 设置列表里「数据与隐私」的副标题从"清除记录"改为"清除数据"。
- 测试 107 → 110：新增清除全部数据的派生键/配置键边界单测、清除全部数据端到端 UI 测、
  洞察与周报「长按 → 菜单删除」UI 测；顺带修掉 `render15AppearanceSheet` 的偶发竞态失败。

## v0.36.4（versionCode 64）

### 新增
- **按会话独立选择 AI 接口**：长按单聊头像切换接口现在**只影响当前会话**，
  每个会话可以记住自己用的模型/接口，互不干扰。
  - 弹层改名「本会话的 AI 接口」，顶部新增「跟随全局（当前：X）」一行，
    点它清除本会话覆盖、回落到设置页里的全局默认。
  - 没单独设过的会话自动跟随全局；本会话覆盖指向的接口被删除时自动回落全局。
  - 该会话的 AI 洞察提炼也跟随本会话接口；周报 / Agent / 人设改写仍用全局默认。
  - 存储走 DataStore 前缀键 `persona_api_<personaId>`，删除人设时级联清理。

### 说明
- 切换只作用于"之后"的消息；历史消息不记录当时所用模型。
- 生图接口仍是独立单条配置，不随会话切换。

## v0.36.3（versionCode 63）

### 修复
- **生图接口 `SSLHandshakeException: connection closed`**：桌面 openssl/curl 实测该端点
  证书链完整、TLS 全通、HTTP 200，**仅 Android Conscrypt 握手被服务端掐断**——
  服务端不接受 Android ClientHello 的 TLS1.3/扩展组合。这不是证书信任问题，
  所以 trust-all 修不好，v0.36.2 的 HTTP 层修复也够不着。
- 新增 TLS 兼容回退：默认握手失败时自动降级到 **TLS 1.2 + http/1.1** 重试一次
  （`NetworkStack.callWithTlsFallback`）。只调握手协议参数，**不放松证书信任**；
  握手失败时请求字节未发出，重试不会重复消耗生图额度。
- 接入生图三处：探活、正式生图、图片 CDN 下载。对话接口暂不接（未报此错）。

## v0.36.2（versionCode 62）

### 修复
- **生图接口「测试连接」稳定 HTTP 400**：`ImageProbe.probe()` 之前发的请求体
  `{"prompt":"a dot","n":1,"size":"256x256"}` 三处不合法——
  - 没带 `model`：SiliconFlow / 智谱 CogView / 豆包 Seedream / gpt-image-1 都强制要求
  - `size = 256x256` 是老 DALL-E 2 尺寸，主流服务商不支持（都要求 1024x1024 起）
  - HTTP 400 时响应体被扔了，服务商说的具体原因（"model is required"）看不到
- 修复后：`ImageProbe` 加 `model` 参数，size 改 `1024x1024`，4xx 一律尝试解析
  body 里的 `error.message`（兼容 OpenAI 风格 / 扁平 / 纯文本三种形态）。
  现在报错形如 `连接失败 · HTTP 400：size 256x256 is not supported, please use one of [...]`，
  用户能直接看出哪个字段不对。
- 前端拦截：`ApiConfigSheet` 生图接口未填模型时点「测试连接」直接给可读提示，不发无效请求。

## v0.36.1（versionCode 61）

### 变更
- **任务编辑弹层「提醒」行简化**：去掉「不提醒」文本（未设时右侧只留一个细箭头 `›`），
  整行去掉 Material 默认 ripple 灰色方框，改用无 indication 的 clickable 保持凹陷拟物观感。
  点击行为不变：弹时间选择器；已设时依旧显示 `HH:mm` + `×` 清除。
- **API 配置 UI 统一**：对话接口和生图接口共用「摘要行 + 点开才展开表单」交互模式。
  生图接口未配置时只显示「＋ 配置生图接口」按钮，已配置时显示单行摘要 + 编辑/清除入口。
- **两组预设 chips 尾部新增「自定义」**：选中态 = 当前 baseUrl 不匹配任何预设；
  点击时若当前是预设态则清空 baseUrl+model 让用户从头填，已是自定义态则不动（避免误清）。
- **删除信息噪音**：section header 从「对话接口（可配置多条）」/「生图接口（AI 可生成图片）」
  简化为「对话接口」/「生图接口」；生图区底部小字说明一并删除。

### 修复
- 生图接口「测试连接」的异常翻译改走 `NetworkStack.describeNetworkFailure`，
  与对话接口保持一致，不再把 message 为 null 的异常糊成"网络错误"。

## v0.36.0（versionCode 60）

### 重大变更
- **应用改名**：「导师」→「梦欢」。Android 包名、Kotlin 包路径、Room 数据库文件名、
  Keystore 别名、deep link scheme（`daoshi://` → `menghuan://`）、导出文件名、主题资源名
  （`Theme.Mentor` → `Theme.Menghuan`）全层同步。老用户视为新 App，需要 JSON 导出/导入迁移。
- **应用定位从「AI 导师」调整为「AI 陪伴应用」**：风格由用户创建的人设决定，不再预设导师调性。
  关于面板、应用助手 prompt、基础提示词全部改写为陪伴向措辞。
- **内置应用助手升级**：`ASSISTANT_PERSONA_TITLE` 从「使用指南 · 答疑」改为「应用向导 · 人设助手」；
  prompt 新增「人设适配协议」章节，用户丢一段自由描述可获得结构化【名字】【身份】【设定词】三件套。
  v0.35.x 老用户升级时通过 `LEGACY_ASSISTANT_PROMPT_V035` 检测自动迁移到新版。

### 新增
- **人设编辑页「AI 优化设定词」按钮**：点开子面板粘贴/输入自由描述，
  调当前生效的对话接口，返回结构化三件套一键填入表单。
  实现：新建 `data/PersonaRewriter.kt`（硬编码 JSON schema 改写 prompt + 宽容解析）
  + `ui/persona/AiPersonaOptimizeSheet.kt`（子面板 UI，含加载态/错误态/结果预览）。
- **提示词分层可编**：「设置 · AI 提示词」从原来"3 段可编 + 3 段只读+复制按钮"扩展为**六段全部可编**。
  AI 洞察 / AI 周报 / 生图触发三段采用**框架（硬编码不可改）+ 策略（用户可编）**分层：
  - `ProfileExtractor.FRAMEWORK`：JSON schema + 输出格式约束（改坏 parse 直接崩，故不给用户改）
  - `WeeklyReportMath.FRAMEWORK`：只输出正文纯文本约束（改坏 parseReport 崩）
  - `IMAGE_HINT_FRAMEWORK`：`[生图:...]` 标记格式（改坏 findPromptIn 匹配不到）
  策略部分（宁缺毋滥尺度、字数语气、触发时机）用户可编，每段带独立「本段恢复默认」按钮。
- **`NetworkStack.describeNetworkFailure`**（v0.35.3 起）：所有"测试连接"入口共用异常翻译函数，
  始终带 `t.javaClass.simpleName`，避免 `NetworkOnMainThreadException` 这类 message 为 null 的异常
  被糊成"网络错误"四个字。

### 变更
- **AI 洞察 / 任务建议数量松绑**：`ProfileExtractor` 去掉"最多 1 条"硬编码，改为
  AI 根据信息价值自主决定条数（可以是 0 条）。代码保留宽松软防灾上限
  （`MAX_INSIGHTS = 10`、`MAX_SUGGESTIONS = 5`）——正常输出 0~3 条不受影响，
  仅防模型失控吐几十条撑爆 UI。
- **删除「复制提示词」按钮**：策略既然可编辑，输入框自身就支持复制粘贴，单独按钮多余。
  同步清理 `PromptSettingsSheet` 里的 `ClipboardManager` / `ClipData` / `Context` imports。
- **任务标签措辞**：AI 建议落库的任务标签从「导师安排」改为「AI 建议」（跟"AI 洞察"术语一致）。
- **对话角色标签**：提炼/周报转录里 AI 一方从「导师」改为「AI」（`ExtractionEngine` / `WeeklyReportMath.buildTranscript`）。
- **人设身份示例**：人设编辑页身份输入框 placeholder 从「例：人生导师 / 健身教练」拓宽为
  「例：朋友 / 陪伴者 / 教练 / 心理咨询师」，反映应用不再预设导师定位。

### 修复
- 承接 v0.35.2 与 v0.35.3 的网络层修复（明文 HTTP + HostnameVerifier + `NetworkOnMainThreadException`）。

### 测试
- 单测从 105 增至 **107**：`ProfileExtractorParseTest` 新增 `insights 超过软上限 10 条被裁到 10 条`
  与 `taskSuggestions 超过软上限 5 条被裁到 5 条` 两个防灾测试；旧的"最多 1 条"断言改为"软上限内全部保留"。

## v0.35.3（versionCode 56）

### 修复
- **「测试生图接口」稳定报"网络错误"**：`ImageProbe.probe()` 声明成 `suspend` 但内部是阻塞式
  `http.newCall(...).execute()`，**漏了 `withContext(Dispatchers.IO)`**——调用点是 Compose 的
  `rememberCoroutineScope()`（Main 线程），Android 抛 `NetworkOnMainThreadException`，
  该异常 `message` 为 null，被 `?: "网络错误"` 兜底吞掉真错误。
- 顺带把 `OpenAiClient.testConnection` 与 `ImageProbe.probe` 的异常翻译统一到
  `NetworkStack.describeNetworkFailure`，始终带异常类名，将来同类问题不再被糊。

## v0.35.2（versionCode 55）

### 修复
- **自定义 API 网络兼容**：用户填 `http://` 报 `CLEARTEXT communication to xxx not permitted`；
  填 `https://` 遇到证书 SAN 与访问域名不匹配的服务商报 `Hostname xxx not verified`。
- 新增 `res/xml/network_security_config.xml` 全局放开明文 HTTP，`trust-anchors` 同时信任
  `system` 与 `user` CA（自签端点走"装 CA 到设备"路径，不在代码里 trust-all）。
- 新增 `llm/NetworkStack.kt`：`baseBuilder()` 统一注入 `PermissiveHostnameVerifier`
  （**只跳过域名 vs 证书 CN/SAN 匹配，证书链仍走系统 TrustManager**，MITM/假证书仍被拒）。
  `OpenAiClient` / `ImageGenerationClient` / `ImageProbe` 三处 OkHttpClient 全部改走此入口。

## v0.35.1（versionCode 54）

### 变更
- **人设头像**：编辑时**直接点头像**即可选择/更换自定义图片，不再需要点「自定义」按钮；
  换图后渐变头像框照常保留。删掉了「头像 emoji」输入框与右侧那行提示文字（实际用不上）。
- **外观入口收敛**：主题切换（跟随系统 / 浅色 / 深色）全部移入设置列表的「外观」项里选择，
  取消了直接摊在设置面板底部的快捷切换按钮。
- **AI 周报改为卡片内折叠**：收起时显示 2 行文字预览而不是整张卡消失，
  点卡片右上角箭头或「展开全文」看全文。板块标题右侧不再有折叠箭头。

### 移除
- **表情包功能取消**（需求作废）：聊天输入栏的表情按钮与 emoji 面板已移除。
  用户与 AI 发送的 emoji 仍作为普通文字消息正常显示。

## v0.35.0（versionCode 53）

### 新增
- **暗色模式**：跟随系统 / 浅色 / 深色三选一（我的 → 设置 → 外观）。两套色板严格反相，
  冷启动底色与系统栏图标颜色同步跟随，启动不再先亮后暗闪一下。
- **多 API 接口**：对话接口可配置多条，聊天页**长按头像**即可切换当前生效接口；
  API 配置面板改为「列表 + 行内编辑」，支持改名、改地址/密钥/模型、删除、测连接。
  老版本的单接口配置会自动作为「默认接口」保留，升级无需操作。
- **AI 生图**：设置里可单独配置生图接口；AI 在回复中需要配图时会发起生图，
  图片只存本机并直接显示在聊天气泡里。
- **表情包**：输入栏新增表情按钮，emoji 面板点一下直接发送；AI 发的纯表情也会显示成大表情。
- **人设头像自定义**：人设编辑器可从相册选图作为头像（不再只能用 emoji），
  头像渐变环改为每人稳定随机的一组配色。
- **AI 洞察 / AI 周报 提示词可见**：设置 → AI 提示词面板底部可查看并复制
  提炼提示词、周报提示词、生图触发协议，并说明两者的生成逻辑。

### 变更
- 数据页：顶部「本周任务打卡」与「本周完成度」合并为一张卡；**基础档案移出数据页**，
  只在「我的」页维护；AI 洞察与 AI 周报支持展开/收起。
- **取消「AI 洞察转任务」**。
- 消息页搜索框改为随列表滚动联动：顶部显示搜索框，上滑后收起为顶栏放大镜图标（去掉了圆圈）。
- 「写作规范」补充「写进设定词：AI 能替你做的事」（派发任务、打卡督促、操作手机、生成图片等）。

### 修复
- 修复进入消息页时的闪烁：长会话不再从顶部动画滚到底部，改为直接定位。
- 修复人设管理 / 编辑器等页面头像未跟随自定义图片的问题。

## v0.6.1（versionCode 13）
- 修复：Markdown 代码块加凹陷底视觉（与气泡内嵌语言一致）

## v0.6.0（versionCode 12）
- 新增：会话置顶——长按会话行切换（触觉 + toast），置顶行时间前 📌 染强调色，排序列表最前（Room v5 迁移）
- 测试：Markdown 行分类/代码栅栏切分纯函数单测（4 用例）

## v0.5.1（versionCode 11）
- 测试：OpenAiClientTest（MockWebServer 验证 SSE 解析契约：data 行拆 token、[DONE] 终止、非 2xx 抛 ApiException、非 SSE 200 不崩溃）；ExtractionEngineTest 集成测试（force 提炼全链路：facts/insights 落库、同名覆盖、标题去重、水位线推进）
- 修复：Robolectric 坑之七——preferencesDataStore 委托进程级单例跨测试泄漏，setUp 显式清空
- 打磨：搜索结果命中片段加粗提色

## v0.5.0（versionCode 10）
- 新增：AI 气泡轻量 Markdown 渲染（粗体/行内码/标题/列表/代码栅栏，未配对记号原样输出）
- 新增：任务通知「10 分钟后再提醒」action（TaskSnoozeReceiver）
- 新增：数据与隐私面板「导出全部数据 JSON」（系统分享，密钥不含）
- 新增：气泡长按复制、会话草稿保留（进程内存级）、完成度附上周对比
- 瘦身：packaging 排除 META-INF/*.version 与 DebugProbesKt.bin

## v0.4.0（versionCode 9）
- 新增：AI 提炼流水线（ExtractionEngine：水位线增量 8 条自动提炼，应用级作用域；ProfileFact 按 key 覆盖且 manual 来源不被篡改；AiInsight 标题去重留 20 条）
- 新增：数据页手动「提炼」入口（API 未配置时隐藏）
- Room v4：ai_insights 表 + MIGRATION_3_4
- 数据页：习惯卡改「近 7 天打卡」真数据热力；洞察卡接真数据（空时回退示例并标注）；档案条目 AI 徽标

## v0.3.0（versionCode 8）
- 修复：单聊页输入栏与底栏重叠（push 页隐藏底栏 + AdaptiveScreen 统一 navigationBarsPadding）
- 修复：数据页档案行高异常与排序跳动；回底按钮过期闭包；种子数据并发竞态（列表重复行）
- 新增：未读点闭环（打开会话即已读，未读点带光晕）；通知总开关；通知点击直达任务页（deep link）
- 新增：打卡闭环（任务勾选回执写回主导师会话 + 聊天页快捷打卡面板）
- 新增：会话列表真实搜索（人设名/身份 + 消息内容）
- 优化：流式上屏 60ms 节流；数据页视差渲染期读值；空流明确报错；DB 读写防崩；发送键流式禁用；触觉反馈

## v0.2.x（versionCode 4–7）
- Neumorphism 新拟态全量 UI 重建；真实 LLM（SSE 流式）；消息/任务/档案 Room 化；API Key Keystore 加密；通知权限与精确闹钟

## v0.6.2（versionCode 14）
- 测试：ChatViewModelTest（5 用例，kotlinx-coroutines-test 虚拟调度器 + 真实 IO 轮询泵）——模拟回复路径、草稿写清、流式防重、快捷打卡链路、Room 外部写入实时上屏
- 修复：演示模式（未配置 API）去掉 600ms 人为延迟，回复即时且行为确定化
- SettingsRepository 支持注入独立 DataStore（测试隔离，消除委托单例跨测试残留）
- 移除 mock 回复演示路径中的冗余 delay import

## v0.6.3（versionCode 15）
- 修复：Lint 清理——NeuMuted 签名规范（modifier 前置）、NeuSwitch 用 lambda 重载动画、新增 dataExtractionRules（Android 12+ 备份排除，与 allowBackup=false 配套）；0 errors
- 文档：AGENTS 目录树同步新文件

## v0.7.0（versionCode 16）
- 新增：任务编辑——长按任务行打开编辑弹层（预填标题/备注/日期/提醒），保存即更新并重排提醒闹钟；删除入口移入编辑弹层（保留二次确认）
- 新建/编辑共用 TaskSheet，按钮文案随模式切换（添加/保存）

## v0.7.1（versionCode 17）
- 打磨：数据页洞察区在未配置 API 时显示「配置 API 后可自动提炼」引导（按钮仅配置后出现）
- 打磨：搜索无结果时提供「清除搜索」快捷操作

## v0.8.0（versionCode 18）
- 新增：数据导入——数据与隐私面板选 JSON 文件，合并导入（人设/档案/洞察同名跳过、消息按人设映射追加、任务追加），绝不删除现有数据；结果行内反馈（成功统计/失败原因）
- 新增：手动「提炼」结果反馈——完成后内联显示统计（新洞察/新档案/更新档案），数秒自动消失；ExtractionEngine 返回统计摘要

## v0.8.1（versionCode 19）
- 打磨：人设管理页置顶人设显示 📌 标识（与会话列表一致）

## v0.9.0（versionCode 20）
- 新增：消息气泡长按操作菜单——复制全文 / 删除单条（删除同时从 LLM 对话记忆移除，流式期间不可删；种子演示消息仅可复制）
- MIcons 新增 Copy / Delete 线条图标（与设计语言一致）
- 交互说明：长按气泡从"直接复制"升级为菜单（多一步但可扩展，与主流 IM 一致）

## v0.9.1（versionCode 21）
- 打磨：Markdown 列表折行悬挂缩进（续行与圆点后首字对齐，不再是顶格）
- 打磨：任务列表级联进场动画（与全 App 列表语言一致，每项 45ms 延迟封顶 320ms）
- 打磨：会话空预览改为「开始对话 →」引导文案（新装首屏不再拿身份当预览）
- 打磨：发送键在输入为空时禁用并弱化阴影
