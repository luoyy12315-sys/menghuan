package com.daoshi.app

import android.app.Application
import androidx.room.Room
import com.daoshi.app.data.ExtractionEngine
import com.daoshi.app.data.PersonaRepository
import com.daoshi.app.data.ProfileRepository
import com.daoshi.app.data.SettingsRepository
import com.daoshi.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
            )
            .build()
        personaRepository = PersonaRepository(db)
        profileRepository = ProfileRepository(db)
        settingsRepository = SettingsRepository(this)
        extractionEngine = ExtractionEngine(db, settingsRepository, applicationScope)
    }
}
