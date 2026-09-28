package com.daoshi.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 人设：完全由用户定义，不写死。
 * 默认只预置一个"人生导师"作为示例，用户可修改、删除、新增任意人设
 * （学习教练、健身教练、心理咨询师……甚至可以不是"导师"）。
 */
@Entity(tableName = "personas")
data class Persona(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,               // 名字，如"阿布"
    val title: String,              // 身份，如"人生导师"
    val avatarEmoji: String,        // 头像（MVP 用 emoji，后续可换图片）
    val systemPrompt: String,       // 用户可自由编辑的设定词
    val isBuiltIn: Boolean = false, // 预置人设标记（仍可编辑，仅不可删除）
    @ColumnInfo(defaultValue = "GENTLE")
    val tone: String = ToneStyle.GENTLE.name,  // 该会话当前对话风格（持久化）
    @ColumnInfo(defaultValue = "0")
    val pinned: Boolean = false,    // 会话置顶（长按会话行切换）
    @ColumnInfo(defaultValue = "0")
    val hidden: Boolean = false,    // 已删除的会话（微信式：清空消息后从列表隐藏，新消息/重新打开即恢复）
    val createdAt: Long = System.currentTimeMillis(),
)

/** 对话风格：附加在人设之上的语气维度，长按头像快速切换 */
enum class ToneStyle(val label: String, val promptSnippet: String) {
    GENTLE(
        "温和陪伴",
        "你以温和、共情的方式交流，先接纳情绪，再给出建议。",
    ),
    STRICT(
        "严格教练",
        "你以严格、直接的方式交流，指出问题毫不客气，重视执行与结果。",
    ),
    SOCRATIC(
        "苏格拉底反问",
        "你以苏格拉底式追问引导用户自己找到答案，少给结论，多问问题。",
    ),
}

@Entity(
    tableName = "messages",
    // 会话加载（WHERE conversationId ORDER BY timestamp）与列表预览都按此路径查，没索引就是全表扫描
    indices = [Index(value = ["conversationId", "timestamp"])],
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val content: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)

@Entity(tableName = "tasks")
data class TaskItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val note: String = "",
    @ColumnInfo(defaultValue = "0")
    val dueAt: Long = 0,            // 归属日期（当天 0 点毫秒值），0 = 视为今天；每日任务不使用
    val remindAt: Long? = null,     // 到点弹通知督促；每日任务 = 每日重复提醒的时刻锚点（只取时刻）
    val isDone: Boolean = false,
    @ColumnInfo(defaultValue = "user")
    val createdBy: String = "user", // user = 用户自建 / ai = 导师安排（AI 建议落库也标记 ai）
    @ColumnInfo(defaultValue = "0")
    val repeatDaily: Boolean = false, // 每日任务：无日期概念，每天重置未完成，提醒走每日重复闹钟
    @ColumnInfo(defaultValue = "0")
    val completionDate: Long = 0,   // 完成归属日（epochDay）：每日任务跨天即视为未完成
    val createdAt: Long = System.currentTimeMillis(),
)

/** 当天有效完成状态：每日任务跨天自动回到未完成（展示层判定，不等 BootReceiver 归一化） */
fun TaskItem.isEffectivelyDone(todayEpochDay: Long = java.time.LocalDate.now().toEpochDay()): Boolean =
    if (repeatDaily) isDone && completionDate == todayEpochDay else isDone

/** 打卡完成记录：每次勾选完成写一行（近 7 天热力 / 打卡小圆点 / AI 提炼任务上下文的数据源） */
@Entity(tableName = "task_completions", primaryKeys = ["taskId", "day"])
data class TaskCompletion(
    val taskId: Long,
    val day: Long, // epochDay
)

/** 用户档案：手动填写 + AI 从对话中提炼，注入人设上下文 */
@Entity(tableName = "profile_facts")
data class ProfileFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val source: String = "manual",  // manual / ai
    val updatedAt: Long = System.currentTimeMillis(),
)

/** AI 洞察：提炼流水线从对话中产出的行为模式观察（数据页「AI 洞察」真数据源） */
@Entity(tableName = "ai_insights")
data class AiInsight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val detail: String,
    val createdAt: Long = System.currentTimeMillis(),
)
