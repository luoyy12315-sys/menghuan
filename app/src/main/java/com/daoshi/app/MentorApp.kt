package com.daoshi.app

import android.app.Application
import androidx.room.Room
import com.daoshi.app.data.PersonaRepository
import com.daoshi.app.data.ProfileRepository
import com.daoshi.app.data.SettingsRepository
import com.daoshi.app.data.db.AppDatabase

class MentorApp : Application() {

    lateinit var db: AppDatabase
        private set
    lateinit var personaRepository: PersonaRepository
        private set
    lateinit var profileRepository: ProfileRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDatabase::class.java, "daoshi.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
        personaRepository = PersonaRepository(db)
        profileRepository = ProfileRepository(db)
        settingsRepository = SettingsRepository(this)
    }
}
