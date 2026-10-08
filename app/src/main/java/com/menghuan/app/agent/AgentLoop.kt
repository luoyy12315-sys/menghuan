package com.menghuan.app.agent

import com.menghuan.app.data.SettingsRepository
import com.menghuan.app.data.model.ChatMessage
import com.menghuan.app.llm.OpenAiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Agent 决策循环（观察 → LLM → 执行 → 等界面稳定，最多 maxSteps 步）：
 * - LLM 复用 OpenAiClient（非流式收集全量回复）
 * - 动作解析失败重试 1 次；连续相同动作两步即视为卡住
 * - 安全围栏：白名单外拒绝执行；敏感关键词动作先经用户确认；悬浮停止按钮随时掐断
 */
class AgentLoop(private val settingsRepository: SettingsRepository) {

    data class Result(val success: Boolean, val summary: String, val steps: Int)

    suspend fun run(
        goal: String,
        onStep: (String) -> Unit,
        confirm: suspend (String) -> Boolean,
        isStopped: () -> Boolean,
        api: com.menghuan.app.data.ApiConfig? = null,
    ): Result {
        val config = settingsRepository.agentConfig.first()
        // 对话内触发（v0.37.0）传本会话生效的接口；任务页手动触发传 null → 走全局
        val apiCfg = api ?: settingsRepository.apiConfig.first()
        if (!apiCfg.isComplete) return Result(false, "未配置 API，自动执行不可用", 0)
        if (!AgentAccessibilityService.isRunning()) return Result(false, "无障碍服务未开启", 0)

        AgentAccessibilityService.instance?.showStopOverlay { AgentRunner.stop() }
        try {
            val history = mutableListOf<String>()
            var lastActionDesc = ""
            var sameActionStreak = 0
            for (step in 1..config.maxSteps) {
                if (isStopped()) return Result(false, "已手动停止", step - 1)

                // 白名单围栏：白名单非空且当前包不在清单内 → 停
                val pkg = ActionExecutor.currentPackage()
                if (!config.allowsPackage(pkg)) {
                    onStep("⛔ 目标应用 $pkg 不在白名单")
                    return Result(false, "目标应用不在白名单（$pkg）", step - 1)
                }

                val elements = ScreenState.snapshot(AgentAccessibilityService.instance?.rootInActiveWindow)
                if (elements.isEmpty()) {
                    onStep("…屏幕未就绪，等待")
                    waitWindowStable()
                    continue
                }

                // 决策：解析失败重试 1 次
                val action = requestAction(apiCfg, goal, elements, history) ?: run {
                    onStep("⚠️ 模型输出无法解析，重试一次")
                    requestAction(apiCfg, goal, elements, history)
                } ?: return Result(false, "模型连续输出无效动作", step - 1)

                if (action.type == "done") {
                    return Result(true, action.summary.ifBlank { "已完成" }, step)
                }

                val desc = execute(action, elements, config, confirm)
                if (desc == null) {
                    history += "动作 ${action.type} 执行失败"
                    onStep("第 $step 步：动作执行失败")
                } else {
                    history += desc
                    onStep("第 $step 步：$desc")
                    sameActionStreak = if (desc == lastActionDesc) sameActionStreak + 1 else 0
                    lastActionDesc = desc
                    if (sameActionStreak >= 2) {
                        return Result(false, "连续重复相同动作，疑似卡住，已停止", step)
                    }
                }
                waitWindowStable()
            }
            return Result(false, "达到步数上限（${config.maxSteps} 步）", config.maxSteps)
        } finally {
            AgentAccessibilityService.instance?.hideStopOverlay()
        }
    }

    private suspend fun requestAction(
        api: com.menghuan.app.data.ApiConfig,
        goal: String,
        elements: List<UiElement>,
        history: List<String>,
    ): AgentPrompts.Action? {
        val prompt = AgentPrompts.userPrompt(goal, ScreenState.toPrompt(elements), history)
        val reply = OpenAiClient(api.baseUrl, api.apiKey, api.model)
            .collectReply(AgentPrompts.SYSTEM, listOf(ChatMessage(conversationId = 0, isFromUser = true, content = prompt)))
        runCatching {
            com.menghuan.app.data.UsageTracker.add(
                settingsRepository, reply.usageTokens ?: 0, api.model,
                reply.promptTokens, reply.cachedPromptTokens,
            )
        }
        return AgentPrompts.parseAction(reply.text)
    }

    /** @return 动作描述（历史/日志用）；失败返回 null */
    private suspend fun execute(
        action: AgentPrompts.Action,
        elements: List<UiElement>,
        config: com.menghuan.app.agent.AgentConfig,
        confirm: suspend (String) -> Boolean,
    ): String? {
        return when (action.type) {
        "back" -> {
            ActionExecutor.back()
            "返回"
        }
        "home" -> {
            ActionExecutor.home()
            "回桌面"
        }
        "swipe" -> {
            ActionExecutor.swipe(action.dir)
            "滑动（${action.dir.ifBlank { "up" }}）"
        }
        "tap" -> {
            val element = elements.getOrNull(action.element ?: -1)
                ?: return null
            if (config.sensitiveConfirm && AgentPrompts.isSensitive(element)) {
                val ok = confirm("将点击「${element.label}」（疑似敏感操作），继续吗？")
                if (!ok) return null
            }
            ActionExecutor.tap(element.centerX, element.centerY)
            "点击 [${element.index}] ${element.label.take(20)}"
        }
        "text" -> {
            val element = elements.getOrNull(action.element ?: -1)
                ?: return null
            if (config.sensitiveConfirm && AgentPrompts.isSensitive(element)) {
                val ok = confirm("将在「${element.label}」输入内容，继续吗？")
                if (!ok) return null
            }
            val ok = ActionExecutor.setText(element, action.value)
            if (ok) "输入 [${element.index}] = ${action.value.take(20)}" else null
        }
        else -> null
        }
    }

    /** 等界面稳定：窗口 tick 安静 ≥900ms 或超时 2.5s */
    private suspend fun waitWindowStable() {
        var lastTick = AgentAccessibilityService.windowTick.value
        var quietStart = System.currentTimeMillis()
        val deadline = quietStart + 2500
        while (System.currentTimeMillis() < deadline) {
            delay(150)
            val now = AgentAccessibilityService.windowTick.value
            if (now != lastTick) {
                lastTick = now
                quietStart = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - quietStart >= 900) {
                return
            }
        }
    }
}

/**
 * Agent 运行状态（UI 层订阅）：Idle → Running（日志流 + 可选敏感确认）→ Finished。
 * start 挂在调用方作用域：任务页传 VM scope（离开页面即取消，"运行期间必须可见"的安全约束）；
 * 对话内触发（v0.37.0）传应用级 scope——Agent 要切走前台操作别的应用，聊天页退出不该掐断执行。
 */
object AgentRunner {

    sealed interface State {
        data object Idle : State
        data class Running(val goal: String, val logs: List<String>, val waitingConfirm: String?) : State
        data class Finished(val success: Boolean, val summary: String, val steps: Int) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    /** 本次执行由哪个会话发起（对话内触发才有；用于聊天页重进时重新挂上执行卡片） */
    @Volatile
    var runningPersonaId: Long? = null
        private set

    @Volatile
    private var stopped = false

    private var confirmDeferred: CompletableDeferred<Boolean>? = null

    /**
     * [autoConfirm] = 对话内触发的完全权限模式：敏感动作不打断，全部直接执行
     * （用户在对话里发起即视为授权；步数上限 / 白名单 / 悬浮停止仍生效）。
     * [onFinish] 在执行协程里回调（对话内触发用它落库执行摘要，VM 销毁也不丢结果）。
     */
    fun start(
        scope: CoroutineScope,
        settingsRepository: SettingsRepository,
        goal: String,
        api: com.menghuan.app.data.ApiConfig? = null,
        autoConfirm: Boolean = false,
        personaId: Long? = null,
        onFinish: (suspend (AgentLoop.Result) -> Unit)? = null,
    ) {
        if (state.value is State.Running) return
        stopped = false
        runningPersonaId = personaId
        state.value = State.Running(goal, emptyList(), waitingConfirm = null)
        scope.launch {
            val result = AgentLoop(settingsRepository).run(
                goal = goal,
                onStep = { step ->
                    state.update { current ->
                        (current as? State.Running)?.copy(logs = current.logs + step) ?: current
                    }
                },
                confirm = { question ->
                    if (autoConfirm) {
                        // 完全权限：对话里发起即视为授权，敏感动作不打断
                        true
                    } else {
                        state.update { current ->
                            (current as? State.Running)?.copy(waitingConfirm = question) ?: current
                        }
                        val deferred = CompletableDeferred<Boolean>()
                        confirmDeferred = deferred
                        val ok = deferred.await()
                        state.update { current ->
                            (current as? State.Running)?.copy(waitingConfirm = null) ?: current
                        }
                        ok
                    }
                },
                isStopped = { stopped },
                api = api,
            )
            state.value = State.Finished(result.success, result.summary, result.steps)
            onFinish?.invoke(result)
        }
    }

    fun stop() {
        stopped = true
        confirmDeferred?.complete(false)
    }

    fun answerConfirm(ok: Boolean) {
        confirmDeferred?.complete(ok)
    }

    fun reset() {
        if (state.value !is State.Running) {
            state.value = State.Idle
            runningPersonaId = null
        }
    }
}
