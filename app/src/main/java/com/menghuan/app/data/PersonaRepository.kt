package com.menghuan.app.data

import com.menghuan.app.data.db.AppDatabase
import com.menghuan.app.data.model.Persona
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 人设仓库：新用户仅预置一个内置「应用助手」，其余人设全部由用户自己创建（可增删改）。
 */
class PersonaRepository(
    private val db: AppDatabase,
    private val settingsRepository: SettingsRepository? = null,
) {

    fun observePersonas(): Flow<List<Persona>> =
        db.personaDao().observeAll().onStart { seedOnce() }

    suspend fun getPersona(id: Long): Persona? = db.personaDao().getById(id)

    suspend fun save(persona: Persona): Long = db.personaDao().upsert(persona)

    /**
     * 物理删除人设 = 级联清理：该会话全部消息 + 未读标记 + 提炼水位线 + 会话级接口覆盖。
     * （人设无独立闹钟；任务闹钟按任务独立，不受影响。）
     */
    suspend fun delete(id: Long) {
        db.messageDao().clearConversation(id)
        db.personaDao().deleteById(id)
        settingsRepository?.removeReadMark(id)
        settingsRepository?.removeExtractionWatermark(id)
        settingsRepository?.removePersonaApiProfile(id)
    }

    /** 对话风格按 persona 持久化（长按头像切换后保存） */
    suspend fun updateTone(id: Long, tone: String) = db.personaDao().updateTone(id, tone)

    /** 会话置顶（长按会话行切换） */
    suspend fun setPinned(id: Long, pinned: Boolean) = db.personaDao().setPinned(id, pinned)

    /** 会话删除标记（微信式隐藏，重新打开/新消息即恢复） */
    suspend fun setHidden(id: Long, hidden: Boolean) = db.personaDao().setHidden(id, hidden)

    /**
     * 种子只播一次。此前在 onStart 里"查空再插"没有并发保护：
     * 两个收集器（会话列表 + 数据统计）同时首次订阅时双双判空，种子重复插入，
     * 会话列表出现两条同一人设（截图测试抓到的真 bug）。
     */
    private val seedMutex = Mutex()
    @Volatile
    private var seeded = false

    private suspend fun seedOnce() {
        if (seeded) return
        seedMutex.withLock {
            if (seeded) return
            if (db.personaDao().count() == 0) {
                seed()
            }
            seeded = true
        }
    }

    /**
     * 种子只播一个内置「应用助手」（应用使用指南会话，提示词可在设置里改）。
     * 其他人设一律由用户自己创建——新用户的人设列表、基础档案、聊天消息全部为空
     * （v0.31.0 起取消三个示例人设种子，用户反馈）。
     */
    private suspend fun seed() {
        db.personaDao().upsert(
            Persona(
                name = ASSISTANT_PERSONA_NAME,
                title = ASSISTANT_PERSONA_TITLE,
                avatarEmoji = "🤖",
                isBuiltIn = true,
                systemPrompt = AiPrompts.DEFAULT_ASSISTANT_PROMPT,
            )
        )
    }

    /**
     * 确保内置「应用助手」存在（启动时调用）：缺失则补建——内置助手被删后下次启动回来；
     * 用户自己的其他人设不受影响（只补建缺失的助手，不覆盖任何已存在行）。
     */
    suspend fun ensureAssistant() {
        runCatching {
            if (db.personaDao().getByName(ASSISTANT_PERSONA_NAME) == null) {
                seed()
            }
        }
    }

    /**
     * 应用助手提示词版本升级：仍是上一版默认文案的自动刷新为新版；
     * 用户在设置里改过的（≠旧默认）不动。
     */
    suspend fun refreshAssistantPromptIfNeeded() {
        runCatching {
            val persona = db.personaDao().getByName(ASSISTANT_PERSONA_NAME) ?: return
            val current = persona.systemPrompt.trim()
            // 命中任一历史默认文案即视为"用户没改过"，升到当前版本；
            // 用户自己写过的一律不动（只能整段比，所以这里穷举已知旧默认）
            val isLegacyDefault = LEGACY_ASSISTANT_PROMPTS.any { current == it.trim() }
            if (isLegacyDefault) {
                db.personaDao().upsert(persona.copy(systemPrompt = AiPrompts.DEFAULT_ASSISTANT_PROMPT))
            }
        }
    }

    /** v0.31.0 首发的应用助手提示词（用于识别"未被用户改过"的旧版） */
    private val LEGACY_ASSISTANT_PROMPT_V031 = """
        你是「导师」App 内置的应用助手，帮助用户了解和使用本应用。
        关于本应用：
        - 这是一款单机版 AI 导师应用：用户与人设（聊天对象）对话获得生活、学习、工作方面的指导；数据只保存在本机，不上传云端。
        - 底部四个标签页：消息（会话列表与聊天）、数据（基础档案、每日任务打卡、AI 洞察、AI 周报）、任务（每日任务与基础任务、到点提醒）、我的（人设管理、基础档案、设置）。
        - AI 能力需要用户自行配置 OpenAI 兼容接口：我的 → 设置 → API 配置，填接口地址 / API Key / 模型；可用「常用服务商」预设（DeepSeek、智谱 GLM、通义千问、Moonshot、OpenAI）。
        - 聊天：长按消息可复制或删除；长按会话可置顶 / 导出记录 / 标记已读；输入框左侧按钮是快捷打卡；长按顶部头像可切换对话风格（温和陪伴 / 严格教练 / 苏格拉底反问）。
        - 任务：支持每日重复任务与一次性任务，可设提醒时间；桌面小组件上点圆圈可直接打卡；每晚 21:30 有今日总结通知（可在设置关闭）。
        - 档案与洞察：聊天会定期提炼出用户档案与 AI 洞察，作为背景参考注入 AI；每周日 21:30 自动生成 AI 周报，可在数据页查看与分享。
        - 数据自主权：我的 → 设置 → 数据与隐私，可导出 / 导入全部数据（JSON 文件），或清除聊天记录。
        回答要求：简洁、具体、分步骤说明；用户问怎么用时给出点击路径；与本应用无关的问题正常回答即可，不要编造不存在的功能。
    """.trimIndent()

    /**
     * v0.34.0 的默认文案（六节使用手册版）。v0.35.0 改了首页布局、多接口、
     * 表情包、生图与深色模式，老用户看到的助手指南就成了错的，
     * 所以把 v0.34 全文也登记进"历史默认"集合，启动时自动升到新版。
     */
    private val LEGACY_ASSISTANT_PROMPT_V034 = AiPrompts.DEFAULT_ASSISTANT_PROMPT_V034

    /**
     * v0.35.x 的默认文案（「导师」时代最后一版）。v0.36.0 应用改名「梦欢」、
     * 定位改 AI 陪伴、新增人设适配协议章节，老用户看到的助手指南就成了错的，
     * 所以把 v0.35 全文也登记进"历史默认"集合，启动时自动升到新版。
     */
    private val LEGACY_ASSISTANT_PROMPT_V035 = AiPrompts.DEFAULT_ASSISTANT_PROMPT_V035

    /**
     * v0.36.0–v0.36.4 的默认文案。v0.36.5 把「清除全部聊天记录」升级成
     * 「清除全部数据（保留人设与接口）」，并加了洞察卡 / 周报历史的长按删除，
     * 老用户看到的助手指南就成了错的，所以把 v0.36.0 全文也登记进"历史默认"集合。
     */
    private val LEGACY_ASSISTANT_PROMPT_V036 = AiPrompts.DEFAULT_ASSISTANT_PROMPT_V036

    /** v0.36.5 的默认文案。v0.37.0 新增对话内操控手机，手册三处措辞更新。 */
    private val LEGACY_ASSISTANT_PROMPT_V0365 = AiPrompts.DEFAULT_ASSISTANT_PROMPT_V0365

    /** v0.37.0–v0.37.1 的默认文案。v0.37.2 周报直出全文、AI 提示词改手风琴。 */
    private val LEGACY_ASSISTANT_PROMPT_V0370 = AiPrompts.DEFAULT_ASSISTANT_PROMPT_V0370

    /** v0.37.2 的默认文案。v0.38.0 新增看图/主动关心/带日期建议等，手册多处更新。 */
    private val LEGACY_ASSISTANT_PROMPT_V0372 = AiPrompts.DEFAULT_ASSISTANT_PROMPT_V0372

    /** v0.38.0 的默认文案。v0.41.0 移除单聊快捷打卡面板，手册同步。 */
    private val LEGACY_ASSISTANT_PROMPT_V038 = AiPrompts.DEFAULT_ASSISTANT_PROMPT_V038

    /** 历史默认文案集合（初始化顺序依赖上面几段常量，必须放最后） */
    private val LEGACY_ASSISTANT_PROMPTS by lazy {
        listOf(
            LEGACY_ASSISTANT_PROMPT_V031,
            LEGACY_ASSISTANT_PROMPT_V034,
            LEGACY_ASSISTANT_PROMPT_V035,
            LEGACY_ASSISTANT_PROMPT_V036,
            LEGACY_ASSISTANT_PROMPT_V0365,
            LEGACY_ASSISTANT_PROMPT_V0370,
            LEGACY_ASSISTANT_PROMPT_V0372,
            LEGACY_ASSISTANT_PROMPT_V038,
        )
    }
}
