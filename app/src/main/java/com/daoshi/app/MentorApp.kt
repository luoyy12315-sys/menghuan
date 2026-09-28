package com.daoshi.app

import android.app.Application
import androidx.room.Room
import com.daoshi.app.data.ExtractionEngine
import com.daoshi.app.data.PersonaRepository
import com.daoshi.app.data.ProfileRepository
import com.daoshi.app.data.SettingsRepository
import com.daoshi.app.data.TaskSuggestionHub
import com.daoshi.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MentorApp : Application() {

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

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "daoshi.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
            )
            .build()
        settingsRepository = SettingsRepository(this)
        personaRepository = PersonaRepository(db, settingsRepository)
        profileRepository = ProfileRepository(db)
        extractionEngine = ExtractionEngine(db, settingsRepository, taskSuggestionHub, applicationScope)
        // 每日总结闹钟随进程启动重排（覆盖安装/进程被杀后自愈；重启走 BootReceiver）
        applicationScope.launch {
            runCatching {
                if (settingsRepository.dailySummaryEnabled.first()) {
                    com.daoshi.app.notify.AlarmScheduler.scheduleDailySummary(this@MentorApp)
                }
            }
        }
        // 今日任务小组件随启动刷新一次（任务数据可能被导入等旁路改过）
        com.daoshi.app.widget.TodayWidgetRefresher.refresh(this)
    }
}
