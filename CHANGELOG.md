# 导师 · 变更日志

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
