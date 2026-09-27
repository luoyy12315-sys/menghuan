# 导师 · 项目长期记忆

## 构建环境（2026-09-27 验证可用）
- JDK17：`C:/Users/16621/.workbuddy/toolchains/jdk17`（17.0.2）
- Android SDK：`C:/Users/16621/.workbuddy/toolchains/android-sdk`（platform 34/35，build-tools 34/35，cmdline-tools/latest）
- Gradle 8.14.3：`C:/Users/16621/.gradle/wrapper/dists/gradle-8.14.3-all/<hash>/gradle-8.14.3/bin/gradle.bat`
- 构建命令：`assembleDebug --no-daemon --console=plain`，**不要用 gradlew**（工程无 wrapper）。完整步骤、已知坑与产物校验见 AGENTS.md §八。
- 已有配置勿删：`local.properties`、`gradle.properties` 的 `android.overridePathCheck=true`、`settings.gradle.kts` 里排在最前的阿里云镜像。
- **中文路径坑之五（单测）**：JDK17 经 @argfile 按 GBK 解码 UTF-8 classpath，中文路径下跑 JVM 单测必 CNFE；先 `subst X: "C:\Users\16621\Desktop\导师·"` 再在 X:\ 下构建（重启失效需重跑）。
- **UI 回归门禁**：`recordRoborazziDebug`（Robolectric+Roborazzi）启动真实 MainActivity 出各页 PNG 到 app/build/screenshots/，改 UI 先跑一遍亲眼比对；截图基建用法见 AGENTS §八。

## UI 方向（2026-09-27 全面重建）
- **Neumorphism 新拟态**（用户拍板，取代 ins 风）：柔和灰蓝 `#E4E9F3` 基底 + 双向软阴影 + 单一靛蓝 `#6C7DF0` 点睛，浅色锁定。设计基准仍是 `preview/all.html`（已同步新拟态），改 UI 先改预览再同步 Compose。
- **浅色锁定必须配 v29 退出 Force Dark**（`values-v29/themes.xml` forceDarkAllowed=false）：否则系统深色模式下 Android 10+ 强制反色整棵 View 树，新拟态被搅成糊色块（v0.2.1 真机"乱码"根因）。系统栏图标用 `SystemBarStyle.light` 锁深色，不用 auto。冷启动底色 `values/colors.xml` neu_background 与 Theme.kt 同步改。
- 新拟态核心在 `ui/common/Neu.kt`：`neuSurface`（凸起/凹陷双向渐变阴影）、`rememberNeuPressState`（按压阴影收缩+scale .97）、`NeuButton/NeuTextField/NeuCheckCircle/NeuChip/NeuSwitch/NeuSheet*`；通用顶栏/头像在 `AdaptiveScreen.kt`（NeuTopBar/NeuAvatar/NeuTextButton）。
- **阴影只能 drawBehind 方向渐变实现**（minSdk 26：BlurMaskFilter 硬件加速不生效、Modifier.blur 需 API31+）；动效令牌 EaseOutExpo（cubic-bezier(.16,1,.3,1)），按压 110ms / 状态 220ms / 页面 340ms。
- 图标走 `ui/theme/Icons.kt` 的 **MIcons**（原 IgIcons 已删）；纯线条 21–23dp，禁用 Material 自带图标。
- 比例类尺寸（气泡 76%、顶栏槽位 19.5%）一律基于 `LocalContentWidth`，不要用 `LocalConfiguration.screenWidthDp`。

## 关键约定
- 人设（Persona）不写死，用户可增删改；对话风格（ToneStyle）≠ 人设；设置入口只在会话列表页；长按头像是风格切换的唯一隐藏入口。
- 数据只存本机，不上云。
- 业务逻辑层（ChatViewModel/TasksViewModel/DataViewModel/Repository/Room/OpenAiClient/notify）在 2026-09-27 UI 重建时未动，全部保留；重建只换了 theme/common/全部 Screen 与 MainActivity 底栏。
