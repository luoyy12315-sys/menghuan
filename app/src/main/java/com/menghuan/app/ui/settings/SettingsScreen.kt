package com.menghuan.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.menghuan.app.BuildConfig
import com.menghuan.app.data.DEFAULT_THEME_MODE
import com.menghuan.app.ui.common.NeuTextButton
import com.menghuan.app.ui.theme.MIcons
import com.menghuan.app.ui.theme.TextPrimary
import com.menghuan.app.ui.theme.ThemeMode

/**
 * 设置主页（v0.54.2 定型，**方案二：九张入口卡，点进去才是二级页**）。
 *
 * ## 结构（用户 2026-10-06 拍板）
 * 顶部搜索框，下面就是**九张自题名的入口卡**，一卡一个板块：
 *
 * ① 模型配置 ② 提示词库 ③ 音色 ④ 通话行为 ⑤ 提醒与通知
 * ⑥ 外观 ⑦ 权限 ⑧ 数据与隐私 ⑨ 关于
 *
 * **主页不再有分组小标题**（v0.54.1 那套"模型配置带标题 + 4 张平铺卡"被推翻）：多条目板块
 * （模型配置 / 通话行为）现在也是**一张卡**，点进去才是那几张子卡。
 *
 * ## 搜索（v0.54.4 改成与「消息」页同款）
 * 顶部是**凹陷搜索行**（点一下才进入搜索态）；上滑离开顶部时它收起、顶栏右侧冒出放大镜，
 * 与消息页会话列表完全一致。搜索态下按标题 / 副标题（外加别名）过滤这九张卡。
 *
 * ## v0.54.4 变更
 * 「定时主动消息」与「人设主动关心」**从设置里移除** —— 它们改到**每个角色**的整页设置里
 * （对话框右上角 ⋮ → 整页），设置页不再管这两件事。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val app = settingsContainer()
    val context = LocalContext.current

    var query by remember { mutableStateOf("") }
    var searchMode by remember { mutableStateOf(false) }

    // v0.56.8：页面里那条搜索行已删掉（只留顶栏常驻放大镜），滚动状态只给 SettingsPage 用
    val scrollState = rememberScrollState()

    val themeModeName by app.settingsRepository.themeMode.collectAsState(initial = DEFAULT_THEME_MODE)

    // 权限汇总（v0.53.8）：进页与从系统设置返回时都重查一次
    var permTick by remember { mutableStateOf(0) }
    val permLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(permLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permTick++
        }
        permLifecycle.lifecycle.addObserver(observer)
        onDispose { permLifecycle.lifecycle.removeObserver(observer) }
    }
    val permSubtitle = remember(permTick, context) {
        val (missingRequired, missingOptional) = permissionSummary(context)
        when {
            missingRequired > 0 -> "还有 $missingRequired 项必开权限未开启"
            missingOptional > 0 -> "已就绪 · $missingOptional 项可选未开启"
            else -> "全部已开启"
        }
    }

    // 从放大镜进入搜索态：先把页面拉回顶部（否则输入框在当前滚动位置之上、用户看不见），
    // 然后**立刻聚焦输入框并把键盘拉起来** —— v0.56.8 用户点名"直接打字就能搜，不用再点一次"。
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(searchMode) {
        if (searchMode) {
            scrollState.animateScrollTo(0)
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }

    // 搜索命中判据 = 标题或副标题（含别名）含关键词（不区分大小写）。
    fun hit(vararg parts: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return parts.any { it.contains(q, ignoreCase = true) }
    }

    SettingsPage(
        title = "设置",
        onBack = onBack,
        scrollState = scrollState,
        actions = {
            // 顶栏右侧**常驻**放大镜（无圆圈）：点它直接进搜索态并拉起键盘（v0.56.8 用户点名）
            if (!searchMode) {
                Icon(
                    MIcons.Search,
                    contentDescription = "搜索",
                    tint = TextPrimary,
                    modifier = Modifier
                        .size(21.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { searchMode = true },
                )
            }
        },
    ) {
        if (searchMode) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SettingsSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "搜索设置项",
                        focusRequester = searchFocus,
                    )
                }
                Spacer(Modifier.width(10.dp))
                NeuTextButton(
                    text = "取消",
                    onClick = {
                        searchMode = false
                        query = ""
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        // ① 模型配置（点进去 = 对话 / 生图 / 识别 / 语音 / 联网搜索 / 语义召回 六张卡）
        SettingsRow(
            show = hit("模型配置", "对话模型 生图模型 识别模型 语音模型 联网搜索 记忆语义召回", "模型", "AI 接口"),
            icon = MIcons.Code,
            title = "模型配置",
            subtitle = "对话 · 生图 · 识别 · 语音模型 · 联网搜索 · 语义召回",
            onClick = { onOpen(SettingsRoutes.MODEL_CONFIG) },
        )
        // ② 提示词库
        SettingsRow(
            show = hit("提示词库", "AI 提示词", "人设设定词 基础提示词 档案注入", "提示词"),
            icon = MIcons.ChatBubble,
            title = "提示词库",
            subtitle = "人设设定词 · 基础提示词 · 档案注入说明",
            onClick = { onOpen(SettingsRoutes.PROMPTS) },
        )
        // ③ 音色
        SettingsRow(
            show = hit("音色", "男声 女声 克隆", "声音", "TTS"),
            icon = MIcons.Person,
            title = "音色",
            subtitle = "男声 / 女声 / 我克隆的声音 · 可试听",
            onClick = { onOpen(SettingsRoutes.VOICE_LIBRARY) },
        )
        // ④ 通话行为（点进去 = 语音通话铃声 + 接听延迟；AI 主动打电话 v0.54.5 起按角色在会话设置页里设）
        SettingsRow(
            show = hit("通话行为", "语音通话铃声 接听延迟 来电 去电 铃声 几秒接", "通话"),
            icon = MIcons.Phone,
            title = "通话行为",
            subtitle = "语音通话铃声 · 接听延迟",
            onClick = { onOpen(SettingsRoutes.CALL_BEHAVIOR) },
        )
        // ⑤ 提醒与通知（点进去 = 两个提醒开关；定时消息与主动关心已挪进每个角色的整页）
        SettingsRow(
            show = hit("提醒与通知", "任务到点提醒 提醒 通知", "通知 提醒"),
            icon = MIcons.Bell,
            title = "提醒与通知",
            subtitle = "任务到点提醒 · 手机操作通知",
            onClick = { onOpen(SettingsRoutes.REMINDERS) },
        )
        // ⑥ 外观
        SettingsRow(
            show = hit("外观", "主题 深色 浅色 夜间", ThemeMode.fromName(themeModeName).label),
            icon = MIcons.Palette,
            title = "外观",
            subtitle = "主题 · ${ThemeMode.fromName(themeModeName).label}",
            onClick = { onOpen(SettingsRoutes.APPEARANCE) },
        )
        // ⑦ 权限
        SettingsRow(
            show = hit("权限", permSubtitle, "授权", "通知 麦克风 相机 闹钟"),
            icon = MIcons.Shield,
            title = "权限",
            subtitle = permSubtitle,
            onClick = { onOpen(SettingsRoutes.PERMISSIONS) },
        )
        // ⑧ 数据与隐私
        SettingsRow(
            show = hit("数据与隐私", "导出 导入 清除 重置 备份", "数据"),
            icon = MIcons.FileText,
            title = "数据与隐私",
            subtitle = "导出 · 导入 · 清除数据",
            onClick = { onOpen(SettingsRoutes.DATA_PRIVACY) },
        )
        // ⑨ 关于
        SettingsRow(
            show = hit("关于", "版本 ${BuildConfig.VERSION_NAME}", "崩溃 记录"),
            icon = MIcons.Info,
            title = "关于",
            subtitle = "版本 ${BuildConfig.VERSION_NAME}",
            onClick = { onOpen(SettingsRoutes.ABOUT) },
        )
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * 设置链的路由名（MainActivity 注册、各页跳转都引这里，避免字符串字面量散落各处写错）。
 *
 * v0.54.1：`API_CONFIG` 拆成 `CHAT_MODEL` / `IMAGE_MODEL`；恢复 `APPEARANCE`。
 * v0.54.2：主页改成方案二，新增三个二级页 `MODEL_CONFIG` / `CALL_BEHAVIOR` / `REMINDERS`，
 * 联网搜索从「定时主动消息」里拎出，独立成 `SEARCH_CONFIG`。
 * v0.54.5：`AI_CALL` 删除 —— AI 主动打电话从设置里移除，改在**每个角色会话的整页设置**里按角色配。
 */
object SettingsRoutes {
    const val SETTINGS = "settings"
    const val MODEL_CONFIG = "model_config"
    const val CALL_BEHAVIOR = "call_behavior"
    const val REMINDERS = "reminders"
    const val SEARCH_CONFIG = "search_config"
    /** v0.82.0 P1-2：记忆语义召回（embeddings 端点配置，默认关） */
    const val EMBEDDING_CONFIG = "embedding_config"
    const val MCP_SERVERS = "mcp_servers"
    const val CHAT_MODEL = "chat_model"
    const val IMAGE_MODEL = "image_model"
    const val DATA_PRIVACY = "data_privacy"
    const val PERMISSIONS = "permissions"
    const val ABOUT = "about"
    const val APPEARANCE = "appearance"
    const val TTS_CONFIG = "tts_config"
    const val VOICE_LIBRARY = "voice_library"
    const val CALL_RINGTONE = "call_ringtone"
    /** v0.56.5：接听延迟从「语音通话铃声」页里拎出，成为「通话行为」页上同级的一项 */
    const val CALL_ANSWER_DELAY = "call_answer_delay"
    const val SCHEDULED_MESSAGE = "scheduled_message"
    const val ASR_MODEL = "asr_model"
    const val PROMPTS = "prompts"
    /**
     * v0.80.0：取消了「基础档案」行卡，它的画像条目并入本页；
     * 路由名也一并换成 `me_profile`（旧名 `profile` 不再注册，避免两条入口指向一处）。
     */
    const val ME_PROFILE = "me_profile"
    const val GALLERY = "gallery"
    /** v0.54.4：崩溃记录独立成页（关于页右上角红字入口） */
    const val CRASH_LOG = "crash_log"
    /** v0.54.4：某个角色的整页设置（对话框右上角 ⋮ → 整页） */
    const val CHAT_SETTINGS = "chat_settings"
}