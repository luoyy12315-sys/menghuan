package com.menghuan.app

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.menghuan.app.data.db.AppDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 数据库迁移回归（v0.74.0 补）。
 *
 * **为什么必须补这个**：v0.74.0 上线后用户反馈「覆盖安装后进某页闪退」。
 * 覆盖安装 = 走迁移路径（v21 → v22 → v23），而本仓库此前**一道迁移测试都没有**，
 * 迁移写错只能在真机上炸，报错还只是"进页面闪退"，完全看不出是数据库。
 *
 * **做法**：从 `app/schemas/` 里**真实导出的 schema JSON** 读出某一版的全部建表语句，
 * 用它建出那个版本的库并塞入真实形状数据；然后**交给 Room 自己跑迁移**
 * （`Room.databaseBuilder(...).addMigrations(...)` 打开同一个文件）——
 * Room 会执行 MIGRATION_21_22 / 22_23 并做 identityHash 校验，
 * 这与真机 `RoomOpenHelper.onUpgrade` 走的**是同一条代码路径**。
 * 迁移语句写错、表形状不符、漏注册迁移，都会在这里当场抛异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DbMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val allMigrations = arrayOf(
        AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
        AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7,
        AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10,
        AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13,
        AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15, AppDatabase.MIGRATION_15_16,
        AppDatabase.MIGRATION_16_17, AppDatabase.MIGRATION_17_18, AppDatabase.MIGRATION_18_19,
        AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_21_22,
        AppDatabase.MIGRATION_22_23,
        AppDatabase.MIGRATION_23_24,
        AppDatabase.MIGRATION_24_25,
    )

    /** 读 `app/schemas/<version>.json`，返回 (表名 → 建表 SQL) */
    private fun schemaTables(version: Int): List<String> {
        val f = java.io.File("schemas/com.menghuan.app.data.db.AppDatabase/$version.json")
        assertTrue("schema $version.json 不存在：${f.absolutePath}", f.isFile)
        val json = JSONObject(f.readText())
        val entities = json.getJSONObject("database").getJSONArray("entities")
        val out = ArrayList<String>()
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            // createSql 里的 ${TABLE_NAME} 是 Room 的占位符，要换回真表名
            out += e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName"))
            val indices: JSONArray = e.optJSONArray("indices") ?: JSONArray()
            for (j in 0 until indices.length()) {
                out += indices.getJSONObject(j).getString("createSql")
                    .replace("\${TABLE_NAME}", e.getString("tableName"))
            }
        }
        return out
    }

    /**
     * 按 schema JSON 建出指定版本的真实库（含 room_master_table），并执行 seed。
     * 调用方负责 close —— 返回的 db 已经关闭。
     */
    private fun createVersionedDb(version: Int, name: String, seed: (SupportSQLiteDatabase) -> Unit = {}) {
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        if (path.exists()) path.delete()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schemaTables(version).forEach { db.execSQL(it) }
                    db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    val hash = java.io.File("schemas/com.menghuan.app.data.db.AppDatabase/$version.json")
                        .readText().let { JSONObject(it).getJSONObject("database").getString("identityHash") }
                    db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '$hash')")
                    seed(db)
                    // ⚠️ 必须显式写 user_version：Robolectric 下 SupportSQLiteOpenHelper
                    // 建库后不一定把版本号落盘，Room 会误判成"新库"而走 onCreate（绕过迁移校验）。
                    db.execSQL("PRAGMA user_version = $version")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, newV: Int) = Unit
                override fun onDowngrade(db: SupportSQLiteDatabase, old: Int, newV: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase.close()
    }

    /** 用 Room 打开旧版本的库 → Room 自己跑迁移 + 校验；返回打开的库，调用方 close */
    private fun openWithRoom(name: String): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*allMigrations)
            .allowMainThreadQueries()
            .build()

    /**
     * v21 → 23：覆盖安装最典型的路径。
     * 存量的完成/未完成任务必须原样保留，新列 isFailed 落到 0（语义零变化）。
     */
    @Test
    fun migrate21To23_keepsTaskDataAndAddsFailureColumn() {
        val name = "mig21.db"
        createVersionedDb(21, name) { db ->
            db.execSQL(
                "INSERT INTO tasks (id, title, note, dueAt, remindAt, isDone, createdBy, repeatDaily, " +
                    "completionDate, createdAt) VALUES (1, '买菜', '西红柿', 0, NULL, 0, 'user', 0, 0, 1700000000000)"
            )
            db.execSQL(
                "INSERT INTO tasks (id, title, note, dueAt, remindAt, isDone, createdBy, repeatDaily, " +
                    "completionDate, createdAt) VALUES (2, '写周报', '', 0, NULL, 1, 'user', 0, 1700000001000, 1700000002000)"
            )
        }

        val room = openWithRoom(name)
        try {
            val db = room.openHelper.writableDatabase
            db.query("SELECT COUNT(*) FROM tasks").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
            // 新列落到默认值 0：升级前的完成/未完成语义零变化
            db.query("SELECT isFailed FROM tasks WHERE id = 2").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            // 新表也一并建出来了
            db.query("SELECT COUNT(*) FROM interaction_bindings").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        } finally {
            room.close()
        }
    }

    /** v22 → 23：只补互动绑定表，存量任务一字不动 */
    @Test
    fun migrate22To23_createsInteractionBindingsTable() {
        val name = "mig22.db"
        createVersionedDb(22, name) { db ->
            db.execSQL(
                "INSERT INTO tasks (id, title, note, dueAt, remindAt, isDone, createdBy, repeatDaily, " +
                    "completionDate, isFailed, createdAt) VALUES (7, '旧任务', '', 0, NULL, 0, 'user', 0, 0, 0, 1700000000000)"
            )
        }

        val room = openWithRoom(name)
        try {
            val db = room.openHelper.writableDatabase
            db.query("SELECT COUNT(*) FROM interaction_bindings").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            db.query("SELECT title FROM tasks WHERE id = 7").use { c ->
                c.moveToFirst()
                assertEquals("旧任务", c.getString(0))
            }
            // ⚠️ v0.74.0 闪退根因回归：迁移里建的索引必须与实体声明**同时存在**。
            // 当时实体没声明索引、迁移却建了 → Room 校验 indices 列表不符 →
            // "Migration didn't properly handle: interaction_bindings" → 覆盖安装一进页面就崩。
            // 这条断言就是"验尸官"：索引要么两边都有、要么两边都没有，单边存在必红。
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_interaction_bindings_actionId'"
            ).use { c ->
                assertTrue("索引 index_interaction_bindings_actionId 必须已建出（实体已声明）", c.moveToFirst())
            }
        } finally {
            room.close()
        }
    }

    /**
     * 复合主键真的生效：同键覆盖、不同键共存。
     * 若建表时主键写成单列或漏声明，这里会红——这正是"绑定时互相顶掉"的根因。
     */
    @Test
    fun interactionBindingsCompositePrimaryKeyWorks() {
        val name = "mig22b.db"
        createVersionedDb(22, name)

        val room = openWithRoom(name)
        try {
            val db = room.openHelper.writableDatabase
            db.execSQL("INSERT INTO interaction_bindings (personaId, region, actionId) VALUES (1, 'HEAD', 5)")
            db.execSQL("INSERT OR REPLACE INTO interaction_bindings (personaId, region, actionId) VALUES (1, 'HEAD', 9)")
            db.execSQL("INSERT INTO interaction_bindings (personaId, region, actionId) VALUES (1, 'BODY', 3)")
            db.execSQL("INSERT INTO interaction_bindings (personaId, region, actionId) VALUES (2, 'HEAD', 7)")
            db.query("SELECT COUNT(*) FROM interaction_bindings").use { c ->
                c.moveToFirst()
                assertEquals(3, c.getInt(0))
            }
            db.query("SELECT actionId FROM interaction_bindings WHERE personaId = 1 AND region = 'HEAD'").use { c ->
                c.moveToFirst()
                assertEquals(9, c.getInt(0))
            }
        } finally {
            room.close()
        }
    }

    /**
     * v23 → 24（v0.76.0 动态多图 + 位置）。
     *
     * **断言的事**：存量动态（正文、点赞人、父子关系）必须一字不动，
     * 两个新列 `images` / `location` 落到空串 —— UI 侧 `MomentMath.decodeImages("")` 得空表、
     * `location.isBlank()` 为真，旧动态就表现为"纯文字动态"，既不显示九宫格也不显示位置胶囊。
     *
     * **反向验尸**：把 `AppDatabase.MIGRATION_23_24` 里任一条 ALTER 删掉，本测试必须变红
     * （Room identityHash 校验会直接抛 "Migration didn't properly handle: moments"）。
     */
    @Test
    fun migrate23To24_keepsMomentsAndAddsImageLocationColumns() {
        val name = "mig23.db"
        createVersionedDb(23, name) { db ->
            db.execSQL(
                "INSERT INTO moments (id, personaId, parentId, text, createdAt, likedBy) " +
                    "VALUES (1, 3, 0, '旧动态正文', 1700000000000, '1,2')"
            )
            db.execSQL(
                "INSERT INTO moments (id, personaId, parentId, text, createdAt, likedBy) " +
                    "VALUES (2, 3, 1, '旧动态的一条回复', 1700000001000, '')"
            )
        }

        val room = openWithRoom(name)
        try {
            val db = room.openHelper.writableDatabase
            db.query("SELECT COUNT(*) FROM moments").use { c ->
                c.moveToFirst()
                assertEquals(2, c.getInt(0))
            }
            // 存量数据与父子层级原样保留（parentId 丢了会让回复并到主时间线上）
            db.query(
                "SELECT text, likedBy, parentId FROM moments WHERE id = 1"
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("旧动态正文", c.getString(0))
                assertEquals("1,2", c.getString(1))
                assertEquals(0, c.getInt(2))
            }
            db.query("SELECT parentId FROM moments WHERE id = 2").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            // 新列：两行都必须是空串（不是 NULL —— 实体声明的是非空 String）
            db.query("SELECT images, location FROM moments ORDER BY id").use { c ->
                var rows = 0
                while (c.moveToNext()) {
                    assertEquals("", c.getString(0))
                    assertEquals("", c.getString(1))
                    rows++
                }
                assertEquals(2, rows)
            }
            // 索引随表保留（ADD COLUMN 不会动索引，实体声明的仍是这两个）
            listOf("index_moments_createdAt", "index_moments_parentId").forEach { idx ->
                db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = '$idx'").use { c ->
                    assertTrue("索引 $idx 迁移后应仍在", c.moveToFirst())
                }
            }
        } finally {
            room.close()
        }
    }

    /**
     * v24 → 25（v0.82.0 P1 记忆增强）。
     *
     * **断言的事**：① 存量记忆一条不动，新列 `lastRecalledAt` 落 0（"从没被想起过"，
     * 自动归档按 max(lastRecalledAt, updatedAt) 算，老数据不会被升级瞬间整批归档）；
     * ② 两张新表 `conversation_summaries` / `memory_embeddings` 建出来且为空。
     *
     * **反向验尸**：把 `AppDatabase.MIGRATION_24_25` 里任一条语句删掉，本测试必须变红
     * （Room identityHash 校验抛 "Migration didn't properly handle"）。
     */
    @Test
    fun migrate24To25_keepsFactsAndCreatesNewTables() {
        val name = "mig24.db"
        createVersionedDb(24, name) { db ->
            db.execSQL(
                "INSERT INTO profile_facts (id, key, value, source, updatedAt, personaId, category, lifecycle) " +
                    "VALUES (1, '口味', '爱吃辣', 'manual', 1700000000000, 2, '', 'active')"
            )
        }

        val room = openWithRoom(name)
        try {
            val db = room.openHelper.writableDatabase
            // 存量记忆原样保留，新列落 0
            db.query("SELECT value, lifecycle, lastRecalledAt FROM profile_facts WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("爱吃辣", c.getString(0))
                assertEquals("active", c.getString(1))
                assertEquals(0L, c.getLong(2))
            }
            listOf("conversation_summaries", "memory_embeddings").forEach { t ->
                db.query("SELECT COUNT(*) FROM $t").use { c ->
                    assertTrue("$t 表应已建出", c.moveToFirst())
                    assertEquals(0, c.getInt(0))
                }
            }
            // 新列能写：召回链路 UPDATE lastRecalledAt 不该在老装机上炸
            db.execSQL("UPDATE profile_facts SET lastRecalledAt = 1700000001000 WHERE id = 1")
            db.query("SELECT lastRecalledAt FROM profile_facts WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals(1700000001000L, c.getLong(0))
            }
        } finally {
            room.close()
        }
    }

    /**
     * v20 → 23：跨两版的路径（v0.73 用户直接升本版）。
     * 这条覆盖了 v20→21（moments 表）之后紧接着 21→22、22→23 的连跳。
     */
    @Test
    fun migrate20To23_chainsAllThreeSteps() {
        val name = "mig20.db"
        createVersionedDb(20, name)

        val room = openWithRoom(name)
        try {
            val db = room.openHelper.writableDatabase
            listOf("moments", "interaction_bindings").forEach { t ->
                db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$t'").use { c ->
                    assertTrue("$t 表应已建出", c.moveToFirst())
                }
            }
        } finally {
            room.close()
        }
    }
}
