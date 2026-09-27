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
import com.daoshi.app.data.model.TaskItem
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY isBuiltIn DESC, createdAt ASC")
    fun observeAll(): Flow<List<Persona>>

    @Query("SELECT COUNT(*) FROM personas")
    suspend fun count(): Int

    @Query("SELECT * FROM personas WHERE id = :id")
    suspend fun getById(id: Long): Persona?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(persona: Persona): Long

    @Query("DELETE FROM personas WHERE id = :id AND isBuiltIn = 0")
    suspend fun deleteCustom(id: Long)

    @Query("UPDATE personas SET tone = :tone WHERE id = :id")
    suspend fun updateTone(id: Long, tone: String)
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

    /** 数据与隐私面板：消息总数 */
    @Query("SELECT COUNT(*) FROM messages")
    fun observeCount(): Flow<Long>

    /** 清除全部聊天记录（数据与隐私面板，需确认） */
    @Query("DELETE FROM messages")
    suspend fun deleteAll()

    /** 提炼水位线之后的增量消息（只送新内容给 LLM，省 token） */
    @Query(
        "SELECT * FROM messages WHERE conversationId = :conversationId AND id > :afterId " +
            "ORDER BY id ASC LIMIT 40"
    )
    suspend fun getAfterId(conversationId: Long, afterId: Long): List<ChatMessage>
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
    entities = [Persona::class, ChatMessage::class, TaskItem::class, ProfileFact::class, AiInsight::class],
    version = 4,
    // schema JSON 导出到 app/schemas/：迁移出错可 diff 出具体差异
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun personaDao(): PersonaDao
    abstract fun messageDao(): MessageDao
    abstract fun taskDao(): TaskDao
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
    }
}
