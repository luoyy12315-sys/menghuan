package com.menghuan.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.menghuan.app.data.model.AiInsight
import com.menghuan.app.data.model.AiWeeklyReport
import com.menghuan.app.data.model.CallActionBindingEntity
import com.menghuan.app.data.model.InteractionBindingEntity
import com.menghuan.app.data.model.CallSession
import com.menghuan.app.data.model.CallTurn
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.data.model.DailySuggestion
import com.menghuan.app.data.model.ImageGenerationRecord
import com.menghuan.app.data.model.LedgerEntry
import com.menghuan.app.data.model.Live2dActionEntity
import com.menghuan.app.data.model.MomentEntry
import com.menghuan.app.data.model.Persona
import com.menghuan.app.data.model.ProfileFact
import com.menghuan.app.data.model.Sticker
import com.menghuan.app.data.model.TaskCompletion
import com.menghuan.app.data.model.TaskItem
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

    /** 按名字查人设（内置「应用助手」提示词编辑用） */
    @Query("SELECT * FROM personas WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Persona?

    /**
     * 全部内置人设，按创建时间（v0.68.0）。
     *
     * 存在的理由只有一个：**内置助手可能被改名**，而"按名字找回助手"这条路一断，
     * 手册就永久停止跟随版本升级、提示词页还会多建一条助手。
     * 这里取出来交给 [PersonaRepository.findAssistant] 用"身上是不是内置手册的形状"再认一次。
     */
    @Query("SELECT * FROM personas WHERE isBuiltIn = 1 ORDER BY createdAt ASC")
    suspend fun getBuiltIns(): List<Persona>

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

    /** 周报上下文：某时间段内的消息（截断限量，防 token 爆炸） */
    @Query(
        "SELECT * FROM messages WHERE timestamp >= :from AND timestamp < :to " +
            "ORDER BY timestamp ASC LIMIT 60"
    )
    suspend fun getBetween(from: Long, to: Long): List<ChatMessage>

    /** 删除单条消息（长按气泡菜单；LLM 历史随 DB 实时变化） */
    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: Long)

    /** 更新单条消息内容（历史乱码修复用：不改动 id / timestamp / 会话归属） */
    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateContent(id: Long, content: String)

    /**
     * 把一条已落库的文字消息**原地升级成语音条**（v0.50.0 AI 语音条）。
     *
     * 为什么升级同一条而不是另插一条：语音条与文字是同一句话的两种形态，另插一条会让用户
     * 看到"一段文字 + 一条说同样内容的语音"。升级同一条天然不重复，而且合成失败时
     * 什么都不用回滚——那条消息本来就是文字。
     */
    @Query(
        "UPDATE messages SET mediaKind = :mediaKind, audioPath = :audioPath, " +
            "audioDurationMs = :audioDurationMs WHERE id = :id",
    )
    suspend fun updateVoice(id: Long, mediaKind: String, audioPath: String, audioDurationMs: Long)

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

    /** 清除全部数据用（v0.36.5） */
    @Query("DELETE FROM tasks")
    suspend fun deleteAll()
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

    /** 清除全部数据用（v0.36.5） */
    @Query("DELETE FROM task_completions")
    suspend fun deleteAll()
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

    /**
     * 分人设之后的同名判据（v0.70.0）：**同 key 在不同角色下是两条独立记忆**。
     * 不再用 [getByKey] 做合并，否则 A 角色聊出的"爱吃辣"会把 B 角色那条覆盖掉。
     * （不重载成同名 `getByKey`：DAO 里一参/两参同名会给代码生成器添无谓风险。）
     */
    @Query("SELECT * FROM profile_facts WHERE key = :key AND personaId = :personaId LIMIT 1")
    suspend fun findScoped(key: String, personaId: Long): ProfileFact?

    /**
     * 某个角色**能读到的全部记忆**：自己的 + 用户的基础档案（personaId = 0）。
     * 基础档案那部分要不要真给，由调用方按「个人档案全部 AI 可读」这个开关再筛一次
     * （SQL 里判不了开关，别把开关逻辑塞进查询——两处各写一份迟早打架）。
     */
    @Query(
        "SELECT * FROM profile_facts " +
            "WHERE personaId IN (0, :personaId) AND lifecycle IN ('active', 'core') " +
            "ORDER BY CASE lifecycle WHEN 'core' THEN 0 ELSE 1 END, id ASC"
    )
    fun observeVisibleFor(personaId: Long): Flow<List<ProfileFact>>

    /** 基础档案页：只看 personaId = 0 这一档（不含各角色的记忆，也不含待入库/已拒的） */
    @Query("SELECT * FROM profile_facts WHERE personaId = 0 AND lifecycle IN ('active', 'core') ORDER BY id ASC")
    fun observeGlobal(): Flow<List<ProfileFact>>

    /** 记忆页：某个角色自己的记忆（含 staging 与 rejected，页面要能列出来给用户处置） */
    @Query("SELECT * FROM profile_facts WHERE personaId = :personaId ORDER BY id ASC")
    fun observeOwnedBy(personaId: Long): Flow<List<ProfileFact>>

    /**
     * 待入库条数（v0.71.0）：会话设置那一行的「N 条待确认」用它。
     *
     * 为什么要单独一个计数查询而不是把整表拉进内存数：这一行在**每个**会话设置页都要显示，
     * 而记忆库会一直长；把全表读进组合层只为了数一个数，是白给的开销。
     */
    @Query("SELECT COUNT(*) FROM profile_facts WHERE personaId = :personaId AND lifecycle = 'staging'")
    fun observeStagingCount(personaId: Long): Flow<Int>

    /** 删角色时级联清掉它自己的记忆（0 是用户本人的档案，**永远不跟着角色删**） */
    @Query("DELETE FROM profile_facts WHERE personaId = :personaId AND personaId != 0")
    suspend fun deleteForPersona(personaId: Long)

    /** 清除全部数据用（v0.36.5） */
    @Query("DELETE FROM profile_facts")
    suspend fun deleteAll()
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

    /** 洞察卡长按删除（v0.36.5） */
    @Query("DELETE FROM ai_insights WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清除全部数据用（v0.36.5） */
    @Query("DELETE FROM ai_insights")
    suspend fun deleteAll()
}

@Dao
interface ImageGenerationDao {
    @Insert
    suspend fun insert(record: ImageGenerationRecord): Long

    @Query("SELECT * FROM image_generations ORDER BY timestamp DESC")
    suspend fun getAll(): List<ImageGenerationRecord>

    /** AI 图库长按删除（v0.54.3）：删记录，图片文件由调用方一并删除 */
    @Query("DELETE FROM image_generations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM image_generations")
    fun observeCount(): Flow<Long>
}

@Dao
interface AiWeeklyReportDao {
    /** 数据页「AI 周报」历史列表（新→旧） */
    @Query("SELECT * FROM ai_weekly_reports ORDER BY weekStart DESC")
    fun observeAll(): Flow<List<AiWeeklyReport>>

    @Query("SELECT * FROM ai_weekly_reports WHERE weekStart = :weekStart LIMIT 1")
    suspend fun getByWeekStart(weekStart: Long): AiWeeklyReport?

    /** 同周重复生成 = 覆盖：weekStart 唯一索引 + REPLACE */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: AiWeeklyReport)

    /** 周报历史长按删除（v0.36.5） */
    @Query("DELETE FROM ai_weekly_reports WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清除全部数据用（v0.36.5） */
    @Query("DELETE FROM ai_weekly_reports")
    suspend fun deleteAll()
}

@Dao
interface LedgerDao {
    /** 全部流水（时间倒序）：余额/月度统计都由 UI 层用 LedgerMath 算，不重复查询 */
    @Query("SELECT * FROM ledger_entries ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<LedgerEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LedgerEntry): Long

    @Query("DELETE FROM ledger_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 清除全部数据用 */
    @Query("DELETE FROM ledger_entries")
    suspend fun deleteAll()
}

/**
 * 动态（v0.73.0）：一张表两种行 —— `parentId = 0` 是帖子，其余是它下面的回复。
 *
 * 排序口径**只在 [MomentMath] 这一处**：DAO 只按时间倒序把全量交给内存，
 * 因为一个陪伴 App 的动态量级天然是几十到几百条，不是社交媒体量级。
 * 真到了要分页的那天再改这里，别提前把简单事情做复杂。
 */
@Dao
interface MomentDao {
    @Query("SELECT * FROM moments ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MomentEntry>>

    @Query("SELECT * FROM moments WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MomentEntry?

    /** 发帖/回复都用这条；id=0 走自增 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(moment: MomentEntry): Long

    /** 删一条帖子要连带它的回复 —— 不然回复成了指向空气的孤儿行，时间线上会算出错的条数 */
    @Query("DELETE FROM moments WHERE id = :id OR parentId = :id")
    suspend fun deleteThread(id: Long)

    @Query("UPDATE moments SET text = :text WHERE id = :id")
    suspend fun setText(id: Long, text: String)

    @Query("UPDATE moments SET likedBy = :likedBy WHERE id = :id")
    suspend fun setLikedBy(id: Long, likedBy: String)

    /** 清除全部数据用（v0.36.5 那一套） */
    @Query("DELETE FROM moments")
    suspend fun deleteAll()
}

@Dao
interface DailySuggestionDao {
    /** 今日建议（按天取）：数据页顶部/中部展示区 */
    @Query("SELECT * FROM daily_suggestions WHERE day = :day LIMIT 1")
    fun observeByDay(day: Long): Flow<DailySuggestion?>

    /** 历史建议（详情页列表，新→旧，留最近 30 条） */
    @Query("SELECT * FROM daily_suggestions ORDER BY day DESC LIMIT 30")
    fun observeRecent(): Flow<List<DailySuggestion>>

    @Query("SELECT * FROM daily_suggestions WHERE day = :day LIMIT 1")
    suspend fun getByDay(day: Long): DailySuggestion?

    /** 同一天重复生成 = 覆盖（day 唯一索引 + REPLACE） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(suggestion: DailySuggestion)

    /** 长按转任务后置位，避免重复转 */
    @Query("UPDATE daily_suggestions SET converted = :converted WHERE day = :day")
    suspend fun setConverted(day: Long, converted: Boolean)

    @Query("DELETE FROM daily_suggestions WHERE day = :day")
    suspend fun deleteByDay(day: Long)

    /** 清除全部数据用 */
    @Query("DELETE FROM daily_suggestions")
    suspend fun deleteAll()
}

@Dao
interface StickerDao {
    /** 表情面板「我的表情」页：新加的在前 */
    @Query("SELECT * FROM stickers ORDER BY addedAt DESC, id DESC")
    fun observeAll(): Flow<List<Sticker>>

    /** 重复导入同一张图时靠 path 去重（不做唯一索引：允许用户删了再加） */
    @Query("SELECT * FROM stickers WHERE path = :path LIMIT 1")
    suspend fun findByPath(path: String): Sticker?

    @Insert
    suspend fun insert(sticker: Sticker): Long

    @Query("DELETE FROM stickers WHERE id = :id")
    suspend fun delete(id: Long)

    /** 清除全部数据用 */
    @Query("DELETE FROM stickers")
    suspend fun deleteAll()
}

@Dao
interface CallDao {
    @Insert
    suspend fun insertSession(session: CallSession): Long

    @Insert
    suspend fun insertTurns(turns: List<CallTurn>)

    /** 某 persona 的通话历史（新→旧），展示层配 turns() 取逐轮转写 */
    @Query("SELECT * FROM call_sessions WHERE personaId = :personaId ORDER BY startedAt DESC")
    fun observeSessions(personaId: Long): Flow<List<CallSession>>

    @Query("SELECT * FROM call_turns WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun turns(sessionId: Long): List<CallTurn>

    @Query("DELETE FROM call_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)

    @Query("DELETE FROM call_turns WHERE sessionId = :sessionId")
    suspend fun deleteTurns(sessionId: Long)
}

/**
 * Live2D 动作库（v0.64.0）。
 *
 * 读取口径固定为「全机共享的动作（`personaId = 0`） + 本角色专属的动作（模型自带的那批）」，
 * 所以每个查询都带 `personaId = 0 OR personaId = :personaId`。**不要**把它拆成两条查询再在
 * Kotlin 里合并 —— 那样"收藏优先、新建在后"的排序要在两处各写一遍。
 */
@Dao
interface Live2dActionDao {

    /** 排序：收藏的在前，其次按建立时间（列表顺序要稳，不能因为改了一次收藏就整片跳） */
    @Query(
        "SELECT * FROM live2d_actions WHERE personaId = 0 OR personaId = :personaId " +
            "ORDER BY favorite DESC, createdAt ASC, id ASC"
    )
    fun observeFor(personaId: Long): Flow<List<Live2dActionEntity>>

    @Query(
        "SELECT * FROM live2d_actions WHERE personaId = 0 OR personaId = :personaId " +
            "ORDER BY favorite DESC, createdAt ASC, id ASC"
    )
    suspend fun listFor(personaId: Long): List<Live2dActionEntity>

    @Query("SELECT * FROM live2d_actions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Live2dActionEntity?

    @Query("SELECT * FROM live2d_actions WHERE builtinKey = :key LIMIT 1")
    suspend fun getByBuiltinKey(key: String): Live2dActionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(action: Live2dActionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(actions: List<Live2dActionEntity>)

    @Query("UPDATE live2d_actions SET favorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, now: Long)

    @Query("DELETE FROM live2d_actions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 换模型时整批替换该角色"模型自带"那一档 */
    @Query("DELETE FROM live2d_actions WHERE source = 'MODEL' AND personaId = :personaId")
    suspend fun deleteModelActions(personaId: Long)

    /** 清除全部数据用 */
    @Query("DELETE FROM live2d_actions")
    suspend fun deleteAll()
}

/**
 * 四个通话状态的动作绑定（v0.64.0）。
 *
 * `actionId = 0` 是"用这个槽的内置默认动作"的哨兵值，不是脏数据。
 */
@Dao
interface CallActionBindingDao {

    @Query("SELECT * FROM call_action_bindings WHERE personaId = :personaId")
    fun observeFor(personaId: Long): Flow<List<CallActionBindingEntity>>

    @Query("SELECT * FROM call_action_bindings WHERE personaId = :personaId")
    suspend fun getFor(personaId: Long): List<CallActionBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: CallActionBindingEntity)

    @Query("DELETE FROM call_action_bindings WHERE personaId = :personaId AND slot = :slot")
    suspend fun clearSlot(personaId: Long, slot: String)

    /** 动作被删掉时，所有指向它的绑定一起收回来（否则会出现绑到空气、角色不动） */
    @Query("DELETE FROM call_action_bindings WHERE actionId = :actionId")
    suspend fun clearByAction(actionId: Long)

    @Query("DELETE FROM call_action_bindings WHERE personaId = :personaId")
    suspend fun clearForPersona(personaId: Long)

    /** 清除全部数据用 */
    @Query("DELETE FROM call_action_bindings")
    suspend fun deleteAll()
}

/**
 * 点击互动（头/身体/其他）的动作绑定（v0.74.0）。
 *
 * `actionId = 0` 是"用这个区的默认动作"的哨兵值，不是脏数据。
 */
@Dao
interface InteractionBindingDao {

    @Query("SELECT * FROM interaction_bindings WHERE personaId = :personaId")
    fun observeFor(personaId: Long): Flow<List<InteractionBindingEntity>>

    @Query("SELECT * FROM interaction_bindings WHERE personaId = :personaId")
    suspend fun getFor(personaId: Long): List<InteractionBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: InteractionBindingEntity)

    @Query("DELETE FROM interaction_bindings WHERE personaId = :personaId AND region = :region")
    suspend fun clearRegion(personaId: Long, region: String)

    /** 动作被删掉时，所有指向它的绑定一起收回来（否则会出现绑到空气、角色不动） */
    @Query("DELETE FROM interaction_bindings WHERE actionId = :actionId")
    suspend fun clearByAction(actionId: Long)

    @Query("DELETE FROM interaction_bindings WHERE personaId = :personaId")
    suspend fun clearForPersona(personaId: Long)

    /** 清除全部数据用 */
    @Query("DELETE FROM interaction_bindings")
    suspend fun deleteAll()
}

@Database(
    entities = [
        Persona::class,
        ChatMessage::class,
        TaskItem::class,
        TaskCompletion::class,
        ProfileFact::class,
        AiInsight::class,
        AiWeeklyReport::class,
        ImageGenerationRecord::class,
        LedgerEntry::class,
        DailySuggestion::class,
        Sticker::class,
        CallSession::class,
        CallTurn::class,
        MomentEntry::class,
        Live2dActionEntity::class,
        CallActionBindingEntity::class,
        InteractionBindingEntity::class,
    ],
    version = 24,
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
    abstract fun aiWeeklyReportDao(): AiWeeklyReportDao
    abstract fun imageGenerationDao(): ImageGenerationDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun momentDao(): MomentDao
    abstract fun dailySuggestionDao(): DailySuggestionDao
    abstract fun stickerDao(): StickerDao
    abstract fun callDao(): CallDao
    abstract fun live2dActionDao(): Live2dActionDao
    abstract fun callActionBindingDao(): CallActionBindingDao
    abstract fun interactionBindingDao(): InteractionBindingDao

    companion object {
        /**
         * v19：Live2D 动作库（v0.64.0）。
         *
         * 两张**全新表**，不动任何存量列，所以升级是零风险的：
         * - `live2d_actions`：动作本体（内置 / 模型自带 / 我的 / AI 生成共用一张表）
         * - `call_action_bindings`：人设 × 四个状态 → 动作 id（0 = 用内置默认）
         *
         * ⚠️ 内置动作**不在迁移里播种**：内置动作的轨道定义在代码里
         * （[com.menghuan.app.live2d.BuiltinActions]），要能在后续版本里改幅度、改时长。
         * 迁移里灌一份快照进去，等于把 v0.64.0 的动作永久冻结在用户机器上。
         * 播种放在仓库层 `ensureBuiltins()`，每次启动对一遍内置键。
         */
        /**
         * v0.70.0：记忆按人设彻底分开。
         *
         * 三列全部**带默认值加列**，一条数据都不动、不重建表（§六 禁破坏性迁移）：
         * 既有行落到 `personaId = 0` = "用户自己的基础档案"，
         * 这正是它们原本的语义（对所有角色可见），所以不需要用户逐条挑归属。
         */
        /**
         * v0.73.0：「动态」上线、备忘本整块下线。
         *
         * 建 moments、**DROP memos**：用户明确说了不需要备忘录功能、也不要迁移旧数据，
         * 所以这里不写 INSERT INTO ... SELECT。AGENTS.md §六 禁的是"破坏性重建"
         * （为了改形状而重建表、丢用户数据），不是禁止按需求删模块 —— 这一条是用户点名的删除。
         */
        /**
         * v0.74.0：任务「失败」终态。
         *
         * **带默认值加一列**，一条数据都不动、不重建表（§六 禁破坏性迁移）：
         * 存量的完成/未完成任务落到 `isFailed = 0`，语义与升级前完全一致。
         * 与 v0.70.0 那三条 ALTER 同一手法。
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `isFailed` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v0.74.0：点击互动的动作绑定（头/身体/其他）。
         *
         * **纯新增一张空表**，不动任何存量数据 —— 老装机升级后一行绑定都没有，
         * 页面读成"用默认动作"（同 [CallActionBindingEntity] 那套哨兵值口径）。
         * 主键是 (personaId, region)，与实体声明逐字一致（Room 校验主键形状）。
         */
        /**
         * v0.76.0：动态支持多图与位置。
         *
         * **两条带默认值的新列**，一条数据都不动、不重建表（§六 禁破坏性迁移）：
         * 存量帖子落到 `images = ''` / `location = ''`，语义就是"这条没有图、没有位置"，
         * 正是它们升级前的样子。与 v0.70.0 / v0.74.0 那几条 ALTER 同一手法。
         *
         * 两列都建在 `moments` 上（回复也占同一张表），但只有帖子会用得上。
         *
         * ⚠️ **没有新索引**：这两列只作显示用，从不进 WHERE / ORDER BY，
         * 给它们建索引是白占空间还拖慢写入 —— 与 v20 给 createdAt / parentId 建索引
         * 是两回事，那两列时间线每次都要按它们排。
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `moments` ADD COLUMN `images` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `moments` ADD COLUMN `location` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `interaction_bindings` (" +
                        "`personaId` INTEGER NOT NULL, " +
                        "`region` TEXT NOT NULL, " +
                        "`actionId` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`personaId`, `region`))"
                )
                // ⚠️ 动作被删时要按 actionId 反查绑定点（InteractionBindingDao.clearByAction），
                // 漏了索引不报错、不崩，只是那句 DELETE 走全表扫 —— 同 v15 给 call_sessions 补索引的写法。
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_interaction_bindings_actionId` " +
                        "ON `interaction_bindings` (`actionId`)"
                )
            }
        }

        val MIGRATION_20_21 = object : Migration(20, 21) {            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `moments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`personaId` INTEGER NOT NULL DEFAULT 0, " +
                        "`parentId` INTEGER NOT NULL DEFAULT 0, " +
                        "`text` TEXT NOT NULL DEFAULT '', " +
                        "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                        "`likedBy` TEXT NOT NULL DEFAULT '')"
                )
                // ⚠️ 索引必须跟着建：[com.menghuan.app.data.model.MomentEntry] 声明了这两条，
                // 漏了不报错、不崩，只是老装机升级后时间线是**全表扫**（量小看不出来，越用越慢）。
                // 同 v15 给 call_sessions 补索引的写法。
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_moments_createdAt` ON `moments` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_moments_parentId` ON `moments` (`parentId`)")
                db.execSQL("DROP TABLE IF EXISTS `memos`")
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `profile_facts` ADD COLUMN `personaId` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `profile_facts` ADD COLUMN `category` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `profile_facts` ADD COLUMN `lifecycle` TEXT NOT NULL DEFAULT 'active'")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `live2d_actions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`builtinKey` TEXT NOT NULL DEFAULT '', " +
                        "`spec` TEXT NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL DEFAULT 0, " +
                        "`loop` INTEGER NOT NULL DEFAULT 0, " +
                        "`favorite` INTEGER NOT NULL DEFAULT 0, " +
                        "`personaId` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL DEFAULT 0, " +
                        "`updatedAt` INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `call_action_bindings` (" +
                        "`personaId` INTEGER NOT NULL, " +
                        "`slot` TEXT NOT NULL, " +
                        "`actionId` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`personaId`, `slot`))"
                )
            }
        }

        /**
         * v16：人设的角色立绘（v0.58.1）。
         *
         * personas 加一列 `artImagePath`，默认空串——立绘是**可选**的，
         * 没上传的人设生图行为与之前完全一致（纯文生图，不发参考图）。
         * 立绘存在的意义是"AI 画自己时长相一致"，所以它必须是整图，
         * 不能拿头像（[Persona.avatarImagePath]，多半是脸部大特写）顶替。
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `personas` ADD COLUMN `artImagePath` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v18：备忘本去掉手填的 `markedAt` / `place`（v0.63.0 用户拍板"时间地点要自动标注"）。
         *
         * 自动能做到的只有"记下的那一刻"（`createdAt` 本来就有），地点那条路要定位权限 +
         * 第三方逆地理编码 Key，用户确认后整个删掉。
         * 删列只能**重建表**：SQLite 的 `ALTER TABLE DROP COLUMN` 要 3.35+，
         * Android 自带的 SQLite 在低版本 API 上没这个能力，照写就是启动即崩。
         * `memos` 是 v17 才建的新表，实际数据量≈0，重建代价可以忽略。
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memos_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`pinned` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "INSERT INTO `memos_new` (`id`, `content`, `pinned`, `createdAt`) " +
                        "SELECT `id`, `content`, `pinned`, `createdAt` FROM `memos`"
                )
                db.execSQL("DROP TABLE `memos`")
                db.execSQL("ALTER TABLE `memos_new` RENAME TO `memos`")
            }
        }

        /**
         * v17：备忘本（v0.62.0）。全新表 `memos`，存量数据零影响。
         *
         * `markedAt` / `place` 都带默认值（0 / 空串 = 用户没标），
         * 所以「只写一句话」这条最常用的路不需要任何占位字段。
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memos` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`markedAt` INTEGER NOT NULL DEFAULT 0, " +
                        "`place` TEXT NOT NULL DEFAULT '', " +
                        "`pinned` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL DEFAULT 0)"
                )
            }
        }

        /**
         * v15：人设的视频通话视觉类型（v0.58.0）。
         *
         * personas 加一列 `videoVisualType`，默认 `'NONE'`——
         * 存量人设升级后一律"没有形象"，语音通话与聊天完全照旧（方案文档 §二十）。
         * 素材本体是文件，不进库（目录形态见 CallVisualStore），库里只留"用哪种视觉"这一句意图。
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `personas` ADD COLUMN `videoVisualType` TEXT NOT NULL DEFAULT 'NONE'"
                )
            }
        }

        /**
         * v14：语音通话记录（v0.46.0）。
         * call_sessions = 每次通话一行；call_turns = 逐轮转写（挂断时一次性写入）。
         * 只存文字不存音频，无破坏性变更——全新表，存量数据零影响。
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `call_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`personaId` INTEGER NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`endedAt` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL)"
                )
                // 索引名必须与 Room 生成的默认名一致（index_<表>_<列>），否则启动时 schema 校验失败
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_call_sessions_personaId` ON `call_sessions` (`personaId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_call_sessions_startedAt` ON `call_sessions` (`startedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `call_turns` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER NOT NULL, " +
                        "`speaker` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_call_turns_sessionId` ON `call_turns` (`sessionId`)")
            }
        }

        /**
         * v13：语音 / 附件 / 自定义表情（v0.43.0）
         * - messages 六个新列：mediaKind / audioPath / audioDurationMs / attachmentPath /
         *   attachmentName / attachmentSize。全部带默认值，**存量消息不改动**——
         *   mediaKind 为空时展示层按 `MediaKind.fromLegacy` 推断（有 imagePath 就是图），
         *   所以不需要批量回填也不会显示错。
         * - stickers：用户从相册导入的自定义表情。
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `mediaKind` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `audioPath` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `audioDurationMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentPath` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentName` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `attachmentSize` INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stickers` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`path` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * v12：记账流水 + 每日建议（v0.42.0）。
         * - ledger_entries：收入/支出双向流水，现金余额不入库（由 LedgerMath 累加保证自洽）
         * - daily_suggestions：每日一条建议，day 唯一索引 = 重复生成即覆盖（同周报套路）
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ledger_entries` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`isIncome` INTEGER NOT NULL, " +
                        "`amountCents` INTEGER NOT NULL, " +
                        "`note` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL DEFAULT 'manual', " +
                        "`occurredAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL DEFAULT 0)"
                )
                // 索引名必须与 Room 生成的默认名一致（index_<表>_<列>），否则启动时 schema 校验失败
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ledger_entries_occurredAt` " +
                        "ON `ledger_entries` (`occurredAt`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_suggestions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`day` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`detail` TEXT NOT NULL, " +
                        "`converted` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_daily_suggestions_day` " +
                        "ON `daily_suggestions` (`day`)"
                )
            }
        }

        /**
         * v11：assistant 消息的思考链（v0.41.4）。
         * 只加一列带默认值的 TEXT，老数据原样保留（旧消息 reasoning = ""，长按菜单不显示该入口）。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `reasoning` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /** v10：AI 生图记录表（图库的生成记录：成功/失败/时间/提示词/模型/发起会话） */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `image_generations` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`personaId` INTEGER NOT NULL, " +
                        "`personaName` TEXT NOT NULL, " +
                        "`model` TEXT NOT NULL, " +
                        "`prompt` TEXT NOT NULL, " +
                        "`success` INTEGER NOT NULL, " +
                        "`imagePath` TEXT NOT NULL)"
                )
            }
        }

        /** v2：任务加 dueAt/createdBy，人设加 tone（不允许破坏性重建） */
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

        /** v8：AI 周报表（每周日 21:30 生成，weekStart 唯一 = 同周覆盖） */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ai_weekly_reports` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`weekStart` INTEGER NOT NULL, " +
                        "`weekEnd` INTEGER NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                // 索引名必须与 Room 生成的默认名一致（index_<表>_<列>），否则启动时 schema 校验失败
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_weekly_reports_weekStart` " +
                        "ON `ai_weekly_reports` (`weekStart`)"
                )
            }
        }

        /**
         * v9：自定义头像 + 图片消息
         * - personas.avatarImagePath：人设自定义头像的本机图片路径（空 = 仍用 emoji）
         * - messages.imagePath：图片消息的本机图片路径（空 = 纯文本消息）
         * 都用可空/带默认值的 TEXT 列，老数据原样保留。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `personas` ADD COLUMN `avatarImagePath` TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `imagePath` TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}
