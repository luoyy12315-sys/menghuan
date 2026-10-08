package com.menghuan.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.menghuan.app.live2d.ActionCodec
import com.menghuan.app.live2d.ActionSlot
import com.menghuan.app.live2d.ActionSource
import com.menghuan.app.live2d.ActionType
import com.menghuan.app.live2d.Live2dAction

/**
 * Live2D 动作库（v0.64.0，Room v19）。
 *
 * **为什么动作要进库而不是只写在代码里**：用户要求动作可以被"视频通话 / 角色空间 /
 * 点击互动 / 用户自定义组合 / AI 生成后保存"五处调用，而且**用户可以替换、可以收藏、
 * 可以自己新建**（§四 / §六 / §十一.4 / §十一.11）。代码里写死的常量做不到这四件事。
 *
 * 三种来源共用这一张表，靠 [source] 与 [personaId] 区分：
 * - `BUILTIN`（内置，`personaId = 0`）：四态默认动作 + 摆手/摇头/劈叉/翻跟斗。
 *   首次使用时播种进库，这样"绑定"可以统一用 id 表达，不必在消费端再分两种情况。
 * - `MODEL`（模型自带，`personaId > 0`）：从该角色的模型包里扫出来的动作组 / 表情。
 *   **只有那张模型有**，所以按人设归属；换模型时整批替换。
 * - `USER` / `AI`（`personaId = 0`）：用户拼的与 AI 生成的，全机共享 ——
 *   程序化动作本来就不依赖模型，一个角色能做的动作另一个角色也能做。
 *
 * 动作本体（轨道 + 原生引用 + 强度速度）在 [spec] 里，是 JSON；名称 / 来源 / 收藏
 * 这些"列表要用的字段"留在列上 —— 列一次动作库不必把每行的 JSON 都解一遍。
 */
@Entity(tableName = "live2d_actions")
data class Live2dActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** [ActionType] 的枚举名 */
    val type: String,
    /** [ActionSource] 的枚举名 */
    val source: String,
    /** 内置动作的稳定键；非内置为空串 */
    @ColumnInfo(defaultValue = "")
    val builtinKey: String = "",
    /** 动作本体 JSON（见 [ActionCodec]） */
    val spec: String,
    /** 冗余出来给列表显示的时长（毫秒） */
    @ColumnInfo(defaultValue = "0")
    val durationMs: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val loop: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val favorite: Boolean = false,
    /** 0 = 全机共享；> 0 = 仅该人设可见（模型自带动作） */
    @ColumnInfo(defaultValue = "0")
    val personaId: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = 0,
)

/**
 * 四个通话状态各自绑到哪条动作（v0.64.0，Room v19）。
 *
 * `actionId = 0` = "用这个槽的系统默认动作"（按 [ActionSlot] 去 [com.menghuan.app.live2d.BuiltinActions] 里找）。
 *
 * 为什么要专门留 0 这个哨兵值：用户从没进过设置页时**一行绑定都不存在**，
 * 这时候页面上该显示"默认待机"而不是空白；而且用户把库里某条动作删掉之后，
 * 绑定会退回 0（见仓库层），页面同样读成"默认"，**不会出现"绑到一个不存在的动作、角色卡住不动"**。
 */
@Entity(tableName = "call_action_bindings", primaryKeys = ["personaId", "slot"])
data class CallActionBindingEntity(
    val personaId: Long,
    /** [ActionSlot.key]：idle / listening / thinking / speaking */
    val slot: String,
    @ColumnInfo(defaultValue = "0")
    val actionId: Long = 0,
)

/**
 * 实体 ↔ 领域模型的映射（v0.64.0，纯函数）。
 *
 * 分开的理由：`Live2dAction` 是引擎认识的东西（要能当纯数据传给播放器、能单测），
 * `Live2dActionEntity` 是 Room 认识的东西。混成一个会让 Room 注解决定引擎的字段形状。
 */
object Live2dActionMapper {

    fun toEntity(action: Live2dAction, now: Long = System.currentTimeMillis()): Live2dActionEntity =
        Live2dActionEntity(
            id = action.id,
            name = action.name,
            type = action.type.name,
            source = action.source.name,
            builtinKey = action.builtinKey,
            spec = ActionCodec.encode(action),
            durationMs = action.safeDurationMs.toLong(),
            loop = action.loop,
            favorite = action.favorite,
            personaId = action.personaId,
            createdAt = if (action.createdAt > 0L) action.createdAt else now,
            updatedAt = now,
        )

    /** 落盘内容读不通时**不许抛**：动作库里有一行坏数据，不该让整个库打不开 */
    fun toAction(entity: Live2dActionEntity): Live2dAction {
        val spec = ActionCodec.decode(entity.spec)
        val type = runCatching { ActionType.valueOf(entity.type) }.getOrDefault(ActionType.PROCEDURAL)
        return Live2dAction(
            id = entity.id,
            name = entity.name,
            type = type,
            source = ActionSource.fromName(entity.source),
            builtinKey = entity.builtinKey,
            durationMs = entity.durationMs.toInt().takeIf { it > 0 } ?: Live2dAction.MIN_DURATION_MS,
            loop = entity.loop,
            fadeInMs = spec.fadeInMs,
            fadeOutMs = spec.fadeOutMs,
            tracks = spec.tracks,
            motionGroup = spec.motionGroup,
            motionIndex = spec.motionIndex,
            expression = spec.expression,
            amplitude = spec.amplitude,
            speed = spec.speed,
            compatibility = spec.compatibility,
            favorite = entity.favorite,
            personaId = entity.personaId,
            note = spec.note,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
        )
    }
}

/**
 * 四个状态槽在库里的展示顺序（用户要求里列的顺序：待机 → 倾听 → 思考 → 说话）。
 * 单独抽出来是因为通话设置页与角色空间都要按这个顺序排。
 */
val ActionSlotDisplayOrder: List<ActionSlot> = ActionSlot.entries.toList()
