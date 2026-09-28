package com.daoshi.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.daoshi.app.data.model.AiInsight
import com.daoshi.app.data.model.ChatMessage
import com.daoshi.app.data.model.Persona
import com.daoshi.app.data.model.ProfileFact
import com.daoshi.app.data.model.TaskCompletion
import com.daoshi.app.data.model.TaskItem
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonaDao {
    // 置顶优先，其次预置排序，最后按创建时间
    @Query("SELECT * FROM personas ORDER BY pinned DESC, isBuiltIn DESC, createdAt ASC")
    fun observeAll(): Flow<List<Persona>>

    @Query("SELECT COUNT(*) FROM personas")
    suspend fun count(): Int

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun getById(id: Long): Persona?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(persona: Persona): Long

    /** 物理删除人设（v0.12.0 起预置也可删；消息级联清理在仓库层） */
    @Query("DELETE FROM personas WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE personas SET tone = :tone WHERE id = :id")
    suspend fun updateTone(id: Long, tone: String)

    /** 会话置顶（长按会话行切换） */
    @Query("UPDATE personas SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    /** 会话删除标记（清空消息后从列表隐藏，重新打开即恢复） */
    @Query("UPDATE personas SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeConversation(conversationId: Long): Flow<List<ChatMessage>>

    /** 每个会话的最后一条消息（会话列表预览用） */
    @Query(
        "SELECT * FROM messages m WHERE m.timestamp = " +
            "(SELECT MAX(timestamp) FROM messages WHERE conversationId = m.conversationId)"
    )
    fun observeLastMessages(): Flow<List<ChatMessage>>

    @Insert
    suspend fun insert(message: ChatMessage): Long

    /** 会话列表搜索：按内容匹配，时间倒序限量（命中后跳转会话） */
    @Query(
        "SELECT * FROM messages WHERE content LIKE '%' || :query || '%' " +
            "ORDER BY timestamp DESC LIMIT 50"
    )
    suspend fun searchMessages(query: String): List<ChatMessage>

    /** 单聊内消息搜索（顶栏搜索态）：限定会话，LIKE 命中时间倒序 */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND content LIKE '%' || :query || '%' " +
            "ORDER BY timestamp DESC LIMIT 50"
    )
    suspend fun searchInConversation(conversationId: Long, query: String): List<ChatMessage>

    /** 删除单条消息（长按气泡菜单；LLM 历史随 DB 实时变化） */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    /** 数据与隐私面板：消息总数 */
    @Query("SELECT COUNT(*) FROM messages")
    fun observeCount(): Flow<Long>

    /** 清除全部聊天记录（数据与隐私面板，需确认） */
    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /** 清空单个会话的消息（长按会话 → 删除会话；人设保留，LLM 历史随之清空） */
    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun clearConversation(conversationId: Long)

    /** 提炼水位线之后的增量消息（只送新内容给 LLM，省 token） */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND id > :afterId " +
            "ORDER BY id ASC LIMIT 40"
    )
    suspend fun getAfterId(conversationId: Long, afterId: Long): List<ChatMessage>

    /** 数据导出：全量消息（本机数据自主权） */
    @Query("SELECT * FROM messages ORDER BY id ASC")
    suspend fun getAll(): List<ChatMessage>
}

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY isDone ASC, remindAt ASC")
    fun observeAll(): Flow<List<TaskItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskItem): Long

    @Update
    suspend fun update(task: TaskItem)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TaskCompletionDao {
    @Query("SELECT * FROM task_completions ORDER BY day ASC")
    fun observeAll(): Flow<List<TaskCompletion>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(completion: TaskCompletion)

    @Query("DELETE FROM task_completions WHERE taskId = :taskId AND day = :day")
    suspend fun delete(taskId: Long, day: Long)

    @Query("DELETE FROM task_completions WHERE taskId = :taskId")
    suspend fun deleteAllForTask(taskId: Long)
}

@Dao
interface ProfileFactDao {
    // 按录入顺序稳定排列：档案卡是固定条目视图，按 updatedAt 排会在每次编辑后整体跳动
    @Query("SELECT * FROM profile_facts ORDER BY id ASC")
    fun observeAll(): Flow<List<ProfileFact>>

    @Query("SELECT COUNT(*) FROM profile_facts")
    suspend fun count(): Int

    @Query("SELECT * FROM profile_facts WHERE id = :id")
    suspend fun getById(id: Long): ProfileFact?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fact: ProfileFact): Long

    @Query("DELETE FROM profile_facts WHERE id = :id")
    suspend fun delete(id: Long)

    /** 提炼合并用：同名条目覆盖而非新增 */
    @Query("SELECT * FROM profile_facts WHERE key = :key LIMIT 1")
    suspend fun getByKey(key: String): ProfileFact?
}

@Dao
interface AiInsightDao {
    /** 数据页「AI 洞察」真数据源：最新两条 */
    @Query("SELECT * FROM ai_insights ORDER BY createdAt DESC LIMIT 2")
    fun observeLatest(): Flow<List<AiInsight>>

    @Query("SELECT * FROM ai_insights WHERE title = :title LIMIT 1")
    suspend fun getByTitle(title: String): AiInsight?

    /** 周报导出用：最近 20 条全量快照 */
    @Query("SELECT * FROM ai_insights ORDER BY createdAt DESC LIMIT 20")
    suspend fun latest(): List<AiInsight>

    @Insert
    suspend fun insert(insight: AiInsight): Long

    /** 只留最近 20 条，防无限膨胀 */
    @Query(
        "DELETE FROM ai_insights WHERE id NOT IN " +
            "(SELECT id FROM ai_insights ORDER BY createdAt DESC LIMIT 20)"
    )
    suspend fun prune()
}

@Database(
    entities = [
        Persona::class,
        ChatMessage::class,
        TaskItem::class,
        TaskCompletion::class,
        ProfileFact::class,
        AiInsight::class,
    ],
    version = 7,
    // schema JSON 导出到 app/schemas/：迁移出错可 diff 出具体差异
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun personaDao(): PersonaDao
    abstract fun messageDao(): MessageDao
    abstract fun taskDao(): TaskDao
    abstract fun taskCompletionDao(): TaskCompletionDao
    abstract fun profileFactDao(): ProfileFactDao
    abstract fun aiInsightDao(): AiInsightDao

    companion object {
        /** v2：任务加 dueAt/createdBy，人设加 tone（数据只存本机，不允许破坏性重建） */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN dueAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN createdBy TEXT NOT NULL DEFAULT 'user'")
                db.execSQL("ALTER TABLE personas ADD COLUMN tone TEXT NOT NULL DEFAULT 'GENTLE'")
                // 修复旧种子：严格教练 / 苏格拉底 漏标了 isBuiltIn，导致预置人设可被删除
                db.execSQL(
                    "UPDATE personas SET isBuiltIn = 1 " +
                        "WHERE name IN ('严格教练', '苏格拉底') AND isBuiltIn = 0"
                )
            }
        }

        /** v3：messages 加 (conversationId, timestamp) 联合索引——增长最快的表，此前会话查询全表扫描 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 索引名必须与 Room 生成的默认名一致（index_<表>_<列…>），否则启动时 schema 校验失败
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_timestamp` " +
                        "ON `messages` (`conversationId`, `timestamp`)"
                )
            }
        }

        /** v4：AI 洞察表（提炼流水线产出，数据页真数据源） */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_insights` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`detail` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        /** v5：会话置顶 */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE personas ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v6：会话删除标记（微信式：删除会话 = 清空消息 + 列表隐藏，重新打开/新消息即恢复） */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE personas ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v7：每日任务（repeatDaily + completionDate）+ 打卡完成记录表（近 7 天热力/小圆点数据源） */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN repeatDaily INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tasks ADD COLUMN completionDate INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_completions` (" +
                        "`taskId` INTEGER NOT NULL, " +
                        "`day` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`taskId`, `day`))"
                )
            }
        }
    }
}
