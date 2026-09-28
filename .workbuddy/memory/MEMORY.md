# 导师 · 项目长期记忆

## 构建环境（2026-09-27 验证可用）
- JDK17：`C:/Users/16621/.workbuddy/toolchains/jdk17`（17.0.2）
- Android SDK：`C:/Users/16621/.workbuddy/toolchains/android-sdk`（platform 34/35，build-tools 34/35，cmdline-tools/latest）
- Gradle 8.14.3：`C:/Users/16621/.gradle/wrapper/dists/gradle-8.14.3-all/<hash>/gradle-8.14.3/bin/gradle.bat`
- 构建命令：`assembleDebug --no-daemon --console=plain`，**不要用 gradlew**（工程无 wrapper）。完整步骤、已知坑与产物校验见 AGENTS.md §八。
- 已有配置勿删：`local.properties`、`gradle.properties` 的 `android.overridePathCheck=true`、`settings.gradle.kts` 里排在最前的阿里云镜像。
- **中文路径坑之五（单测）**：JDK17 经 @argfile 按 GBK 解码 UTF-8 classpath，中文路径下跑 JVM 单测必 CNFE；先 `subst X: "C:\Users\16621\Desktop\导师·"` 再在 X:\ 下构建（重启失效需重跑）。
- **UI 回归门禁**：`recordRoborazziDebug`（Robolectric+Roborazzi）启动真实 MainActivity 出各页 PNG 到 app/build/screenshots/，改 UI 先跑一遍亲眼比对；截图基建用法见 AGENTS §八。

## UI 方向（2026-09-28 Figma 风全面换装，取代 2026-09-27 新拟态）
- **Figma 风**（用户拍板）：黑白界面骨架 + 鲜亮品牌色只作内容点缀，浅色锁定。设计参考 `DESIGN.md`（Figma 官方风设计系统，已拷到项目根）。**预览工作流已废弃：UI 直接改 Compose，不需要预览确认。**
- 令牌：`NeuBackground`/`NeuSurface`=#FFFFFF 画廊白，`NeuTrack`=#F2F3F5 凹陷灰，`NeuHairline`=黑8% 细线，`NeuAccent`=#000000（强调色=纯黑），`NeuDanger`=#F24E1E；品牌色 BrandGreen #0ACF83 / BrandYellow #FFC700 / BrandPurple #A259FF / BrandBlue #1ABCFE / BrandSalmon #FF7262 **只用于内容**（Hero 渐变卡/热力/persona 头像环/Spark），绝不用于界面骨架。
- 组件核心在 `ui/common/Neu.kt`：`neuSurface`（inset=NeuTrack 灰平涂；否则白面+1dp hairline+黑7%纸叠阴影，无模糊）、**`figmaSelection`**（虚线黑框+四角手柄，Figma 选中框隐喻）、NeuButton 药丸 999dp（Primary 黑底白字）、NeuChip 选中黑药丸、NeuSwitch 黑轨白钮。组件名沿用旧 API，内部已全部换装。
- 动效：弹簧优先（NeuSpring MediumBouncy / NeuSnappySpring）；底栏指示器 animateDpAsState 弹簧滑移（白色药丸+黑色选中胶囊，选中图标翻白）；NavHost 进场加 scaleIn 0.985→1；EaseOutExpo 管进出场。
- **浅色锁定必须配 v29 退出 Force Dark**（同前，v0.2.1 真机"乱码"根因）；冷启动底色 neu_background 现为 #FFFFFF，与 Theme.kt 同步改。
- 图标走 `ui/theme/Icons.kt` 的 **MIcons**；纯线条 21–23dp，墨色改 #000000，禁用 Material 自带图标。
- 比例类尺寸（气泡 76%、顶栏槽位 19.5%）一律基于 `LocalContentWidth`，不要用 `LocalConfiguration.screenWidthDp`。

## 关键约定
- 人设（Persona）不写死，用户可增删改；对话风格（ToneStyle）≠ 人设；设置入口只在会话列表页；长按头像是风格切换的唯一隐藏入口。
- 数据只存本机，不上云。
- 业务逻辑层（ChatViewModel/TasksViewModel/DataViewModel/Repository/Room/OpenAiClient/notify）在 2026-09-27/28 两轮 UI 重建时均未动，全部保留；重建只换 theme/common/各 Screen 与 MainActivity 底栏。
