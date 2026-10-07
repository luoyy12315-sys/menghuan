package com.menghuan.app

import android.app.Application
import androidx.room.Room
import com.menghuan.app.data.ExtractionEngine
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
    /** 提炼等后台工作的作用域：不随页面退出而取消（监督者作业，单个失败不拖垮其他） */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** AI 任务建议中心：提炼引擎产出 → 聊天页卡片确认后落库 */
    val taskSuggestionHub = TaskSuggestionHub()

    lateinit var extractionEngine: ExtractionEngine
        private set

    /** AI 周报生成器（每周日 21:30 定时 + 启动补生成） */
    lateinit var weeklyReportGenerator: com.menghuan.app.data.WeeklyReportGenerator
        private set

    /** 每日建议生成器（v0.42.0：每天早上定时 + 打开数据页补生成） */
    lateinit var dailySuggestionGenerator: com.menghuan.app.data.DailySuggestionGenerator
        private set

    /** 底栏「消息」tab 未读点（应用级计数，流常驻应用作用域） */
    lateinit var unreadCenter: com.menghuan.app.data.UnreadCenter
        private set

    override fun onCreate() {
        super.onCreate()
        // 崩溃现场先落盘（「关于」页能看堆栈），再干别的——晚装就漏掉启动期的崩
        com.menghuan.app.data.CrashLog.install(this)
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
        personaRepository = PersonaRepository(db, settingsRepository) { id ->
            // 人设删除时连通话视觉素材一起清（几百 MB 的文件不能变孤儿）
            com.menghuan.app.data.CallVisualStore.deletePersona(this, id)
        }
        profileRepository = ProfileRepository(db)
        extractionEngine = ExtractionEngine(db, settingsRepository, taskSuggestionHub, applicationScope)
        weeklyReportGenerator = com.menghuan.app.data.WeeklyReportGenerator(db, settingsRepository)
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
                com.menghuan.app.data.ProactiveChat.scheduleNext(settingsRepository, this@MenghuanApp)
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
        // AI 周报：随进程启动排下一个周日 21:30 的闹钟（到点自排 + BootReceiver 重排）；
        // 缺报补生成放 MainActivity 启动（避免在无界面的 Application 阶段发起 LLM 请求）
        applicationScope.launch {
            runCatching {
                com.menghuan.app.notify.AlarmScheduler.scheduleWeeklyReport(this@MenghuanApp)
            }
        }
        // 每日建议：同周报套路——进程启动只排闹钟，补生成放 MainActivity 启动
        // （Application.onCreate 里绝不发 LLM 请求：无界面阶段失败用户无从感知，还会抢 Room 线程池）
        applicationScope.launch {
            runCatching {
                com.menghuan.app.notify.AlarmScheduler.scheduleDailySuggestion(this@MenghuanApp)
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
