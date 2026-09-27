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
    val dueAt: Long = 0,            // 归属日期（当天 0 点毫秒值），0 = 视为今天
    val remindAt: Long? = null,     // 到点弹通知督促
    val isDone: Boolean = false,
    @ColumnInfo(defaultValue = "user")
    val createdBy: String = "user", // user = 用户自建 / ai = 导师安排（二期）
    val createdAt: Long = System.currentTimeMillis(),
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
