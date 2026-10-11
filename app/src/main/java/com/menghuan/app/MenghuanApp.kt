package com.menghuan.app

import android.app.Application
import androidx.room.Room
import com.menghuan.app.data.ExtractionEngine
import com.menghuan.app.data.Live2dActionRepository
import com.menghuan.app.data.PersonaRepository
import com.menghuan.app.data.ProfileRepository
import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.data.TaskSuggestionHub
import com.menghuan.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MenghuanApp : Application() {

    lateinit var db: AppDatabase
        private set
    lateinit var personaRepository: PersonaRepository
        private set
    lateinit var profileRepository: ProfileRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set

    /**
     * Live2D 动作库（v0.64.0）。
     *
     * 放在 Application 上而不是每个页面各建一个：动作库是**全机共享**的
     * （内置动作 + 用户自己建的 + AI 生成的），每个角色都要读同一份，
     * 而且内置动作的播种要做成"进程内只跑一次"。
     */
    lateinit var live2dActions: Live2dActionRepository
        private set
    /** 提炼等后台工作的作用域：不随页面退出而取消（监督者作业，单个失败不拖垮其他） */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** AI 任务建议中心：提炼引擎产出 → 聊天页卡片确认后落库 */
    val taskSuggestionHub = TaskSuggestionHub()

    lateinit var extractionEngine: ExtractionEngine
        private set

    /**
     * AI 洞察引擎（v0.88.0）。
     *
     * 日洞察 23:00（当天数据）/ 周洞察周日 23:00（本周数据）/ 月洞察月末 23:00（本月数据）。
     * 三条子链各自独立、各自允许 0 条产出。放在 Application 上是因为它由闹钟与启动自愈两处驱动，
     * 都不在页面作用域内。
     */
    lateinit var insightEngine: com.menghuan.app.data.InsightEngine
        private set

    /** 滚动摘要引擎（v0.82.0 P1-1）：历史预算之外的旧对话压缩成摘要，随 system prompt 注入 */
    lateinit var summaryEngine: com.menghuan.app.data.SummaryEngine
        private set

    /** P3-3：MCP 服务器工具聚合与路由（聊天 tools 回环 + 设置页管理共用） */
    lateinit var mcpManager: com.menghuan.app.mcp.McpManager
        private set

    /** 每日建议生成器（v0.42.0：每天早上定时 + 打开数据页补生成） */
    lateinit var dailySuggestionGenerator: com.menghuan.app.data.DailySuggestionGenerator
        private set

    /**
     * 冷启动开屏动画的总开关（默认开）。
     *
     * 关掉它的唯一用途是**截图测试**：UiRenderTest / UiRenderTabletTest 直接起真的
     * MainActivity，开屏那 1.5 秒会把每张截图都糊住，看不出页面长什么样。
     */
    var coldStartSplashEnabled: Boolean = true

    /** 底栏「消息」tab 未读点（应用级计数，流常驻应用作用域） */
    lateinit var unreadCenter: com.menghuan.app.data.UnreadCenter
        private set

    override fun onCreate() {
        super.onCreate()
        // 崩溃现场先落盘（「关于」页能看堆栈），再干别的——晚装就漏掉启动期的崩
        com.menghuan.app.data.CrashLog.install(this)
        // P4 手册自检（仅 debug 包）：应用助手手册的补丁链若有没打上的（子串静默落空），
        // 写进崩溃日志页（「我的 → 设置 → 关于」可见）。release 包不跑，零开销。
        // 单元测试侧由 AiPromptsTest「补丁链全部命中」兜底，CI 上红的先是它。
        if (BuildConfig.DEBUG) {
            val manualMisses = com.menghuan.app.data.AiPrompts.assistantManualPatchMisses()
            if (manualMisses.isNotEmpty()) {
                com.menghuan.app.data.CrashLog.reportNonFatal(
                    this,
                    "应用助手手册补丁未命中 ${manualMisses.size} 条",
                    manualMisses.joinToString("\n"),
                )
            }
        }
        db = Room.databaseBuilder(this, AppDatabase::class.java, "menghuan.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18,
                AppDatabase.MIGRATION_18_19,
                AppDatabase.MIGRATION_19_20,
                AppDatabase.MIGRATION_20_21,
                AppDatabase.MIGRATION_21_22,
                AppDatabase.MIGRATION_22_23,
                AppDatabase.MIGRATION_23_24,
                AppDatabase.MIGRATION_24_25,
                AppDatabase.MIGRATION_25_26,
                AppDatabase.MIGRATION_26_27,
                AppDatabase.MIGRATION_27_28,
                AppDatabase.MIGRATION_28_29,
            )
            .build()
        settingsRepository = SettingsRepository(this)
        // Keystore 解密预热：趁用户还没点进聊天页，在 IO 线程把全部密文解进内存缓存。
        // 不预热的话，进页 markChatRead 的 DataStore 重发会让主线程逐条同步解密
        // （真机 trace：主线程反复 binder 到 keystore2，进页动画帧距 60ms+）
        applicationScope.launch {
            runCatching { settingsRepository.warmKeyCache() }
        }
        // v0.58.0：废弃的「角色立绘 / Live 2D 单文件」资产清一次（新的通话形象在 call_visuals/）
        applicationScope.launch {
            runCatching { settingsRepository.clearDeprecatedPersonaAssetKeys() }
            runCatching {
                deleteFilesDirTree("persona_art")
                deleteFilesDirTree("persona_live2d")
            }
        }
        personaRepository = PersonaRepository(
            db,
            settingsRepository,
            visualFilesCleaner = { id ->
                // 人设删除时连通话视觉素材一起清（几百 MB 的文件不能变孤儿）
                com.menghuan.app.data.CallVisualStore.deletePersona(this, id)
                // v0.64.0：角色空间（背景图 + 配置）同样级联
                com.menghuan.app.data.Live2dSpaceStore.deletePersona(this, id)
                // v0.66.0：视频通话背景那张图也一样（档位行在 PersonaRepository.delete 里摘掉）
                com.menghuan.app.data.CallBackgroundStore.deletePersona(this, id)
                // v0.64.0：该角色所属的"模型自带动作"与四态绑定一起收掉；
                // 用户自己建的动作是全机共享的，**不删**
                applicationScope.launch {
                    runCatching { live2dActions.clearModelActions(id) }
                }
            },
            // v0.89.0：人设删了，它的定时消息/定时电话条目也一起摘掉（在 PersonaRepository.delete 里做），
            // 但**闹钟还挂在 AlarmManager 上** —— 不取消就会每天白响一次。仓库层没有 Context，在这里补。
            scheduledAlarmCanceller = { ids ->
                ids.forEach {
                    runCatching { com.menghuan.app.notify.AlarmScheduler.cancelScheduledMessage(this, it) }
                }
            },
        )
        live2dActions = Live2dActionRepository(db)
        // 内置动作的播种：进聊天页之前就该在库里（通话页第一次解析绑定时才不需要兜底两档）
        applicationScope.launch {
            runCatching { live2dActions.ensureBuiltins() }
        }
        profileRepository = ProfileRepository(db)
        extractionEngine = ExtractionEngine(db, settingsRepository, applicationScope)
        // AI 洞察引擎（v0.88.0）：日/周/月三条子链，输入「记忆 + 任务 + 记账」。
        // 与提炼链彻底解耦 —— 洞察不再从对话转录里副产，而是到点拿真实数据归纳一次。
        insightEngine = com.menghuan.app.data.InsightEngine(db, settingsRepository)
        summaryEngine = com.menghuan.app.data.SummaryEngine(db, settingsRepository, applicationScope)
        // P3-3：MCP 工具聚合/路由（应用级单例；设置页改服务器清单后调 invalidate() 重连）
        mcpManager = com.menghuan.app.mcp.McpManager(settingsRepository)
        // v0.82.0 P1-3：记忆自动归档——90 天没被召回命中的 active 降 archived（core 豁免）。
        // 纯 DB 操作、一次小查询 + 少量写入，不发 LLM 请求，可以在启动期跑。
        applicationScope.launch {
            runCatching {
                com.menghuan.app.data.MemoryLifecycle.sweepAutoArchive(db, System.currentTimeMillis())
            }
        }
        dailySuggestionGenerator = com.menghuan.app.data.DailySuggestionGenerator(db, settingsRepository)
        unreadCenter = com.menghuan.app.data.UnreadCenter(db, personaRepository, settingsRepository, applicationScope)
        // 每日总结闹钟随进程启动重排（覆盖安装/进程被杀后自愈；重启走 BootReceiver）
        applicationScope.launch {
            runCatching {
                if (settingsRepository.dailySummaryEnabled.first()) {
                    com.menghuan.app.notify.AlarmScheduler.scheduleDailySummary(this@MenghuanApp)
                }
            }
        }
        // 人设主动关心：随进程启动排今晚的闹钟（到点自排明天；重启走 BootReceiver）。
        // v0.54.4 起开关**按角色**（在每个人设的整页里），闹钟一律排上，发送前再逐个复查。
        applicationScope.launch {
            runCatching {
                com.menghuan.app.data.ProactiveChat.scheduleNext(db, settingsRepository, this@MenghuanApp)
                // AI 主动打电话（v0.53.5）：逐个人设按各自规则排闹钟（只排钟，不发请求；
                // v0.54.5 起没有全局总开关，开关在各角色的规则里）
                runCatching { com.menghuan.app.data.AiCall.rearm(settingsRepository, this@MenghuanApp) }
                // 定时主动消息（v0.53.5）：每条一个闹钟，全部重排（只排钟，不发请求）
                runCatching {
                    settingsRepository.scheduledMessages.first()
                        .filter { it.enabled }
                        .forEach {
                            com.menghuan.app.notify.AlarmScheduler
                                .scheduleScheduledMessage(this@MenghuanApp, it.id, it.time)
                        }
                }
            }
        }
        // Agent 执行进度通知（v0.38.0）：应用级订阅运行状态 + 开关，执行中常驻、结束即撤
        applicationScope.launch {
            kotlinx.coroutines.flow.combine(
                com.menghuan.app.agent.AgentRunner.state,
                settingsRepository.agentNotifyEnabled,
            ) { state, enabled -> state to enabled }
                .collect { (state, enabled) ->
                    runCatching {
                        com.menghuan.app.notify.AgentNotifier.update(
                            this@MenghuanApp,
                            state,
                            com.menghuan.app.agent.AgentRunner.runningPersonaId,
                            enabled,
                        )
                    }
                }
        }
        // 每日建议：进程启动只排闹钟，补生成放 MainActivity 启动
        // （Application.onCreate 里绝不发 LLM 请求：无界面阶段失败用户无从感知，还会抢 Room 线程池）
        applicationScope.launch {
            runCatching {
                com.menghuan.app.notify.AlarmScheduler.scheduleDailySuggestion(this@MenghuanApp)
            }
        }
        // AI 洞察（v0.88.0）：同样只排钟不发请求，到点由 InsightReceiver 跑，跑完自排下一次。
        // 三条链共用一个接收器，靠 extra 里的周期区分。
        applicationScope.launch {
            runCatching {
                com.menghuan.app.notify.AlarmScheduler.scheduleInsights(this@MenghuanApp)
            }
        }
        // 内置「应用助手」：缺失即补建（删了下次启动回来）；旧版默认提示词自动升级为新版
        applicationScope.launch {
            runCatching {
                personaRepository.refreshAssistantPromptIfNeeded()
                personaRepository.ensureAssistant()
            }
        }
        // 注意：生图记录的一次性回填**不放在这里**——它曾与启动期的其它协程并发争抢
        // Room 查询线程池，把会话列表流的首值堵死（Robolectric 实测红）。改为图库打开时懒回填
        // （见 MeScreen.GalleryScreen（v0.51.0 起是全屏页）），低频、串行、不占启动路径。
        // 今日任务小组件随启动刷新一次（任务数据可能被导入等旁路改过）
        com.menghuan.app.widget.TodayWidgetRefresher.refresh(this)
    }

    /**
     * 删掉 filesDir 下一个固定名字的目录（v0.58.0：清废弃的立绘 / Live 2D 单文件资产）。
     * 名字由调用方写死，不接受任何外部输入；目录不存在就当成功。
     */
    private fun deleteFilesDirTree(sub: String) {
        val dir = java.io.File(filesDir, sub)
        if (dir.isDirectory) dir.deleteRecursively()
    }
}
