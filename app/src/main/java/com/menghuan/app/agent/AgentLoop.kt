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

    private companion object {
        /** 解析失败重试时追加的提示：约束模型只吐 JSON（原样重发同一请求必然得到同一个错） */
        const val RETRY_HINT = "上一次的输出无法解析。只输出一个 JSON，不要任何其他文字。"
    }

    suspend fun run(
        goal: String,
        onStep: (String) -> Unit,
        confirm: suspend (String) -> Boolean,
        isStopped: () -> Boolean,
        api: com.menghuan.app.data.ApiConfig? = null,
    ): Result {
        val config = settingsRepository.agentConfig.first()
        // 对话内触发（v0.37.0）传本会话生效的接口；任务页手动触发传 null → 走全局
        val baseApi = api ?: settingsRepository.apiConfig.first()
        // M3-I：Agent 专用模型（留空 = 跟随接口自带模型）。地址与 Key 仍用所选接口的，
        // 只换模型名 —— 每步决策是"这一屏该点哪个"的简单任务，小/快模型足够，延迟可能近半。
        val agentModelOverride = settingsRepository.agentModel.first()
        val apiCfg = if (agentModelOverride.isNotBlank()) baseApi.copy(model = agentModelOverride) else baseApi
        if (!apiCfg.isComplete) return Result(false, "未配置 API，自动执行不可用", 0)
        if (!AgentAccessibilityService.isRunning()) return Result(false, "无障碍服务未开启", 0)

        AgentAccessibilityService.instance?.showStopOverlay { AgentRunner.stop() }
        // 保活（默认开）：Agent 真正跑起来才挂前台服务——进程活着，无障碍绑定才不会被系统回收
        // （痛点「每次操作手机都要打开设置开无障碍」的根因就是全工程零保活）
        if (settingsRepository.agentKeepAlive.first()) {
            AgentAccessibilityService.instance?.let { AgentKeepAliveService.ensureStarted(it) }
        }
        try {
            // M4-L：观测层混合输入 = 元素清单（动作地址）+ 屏幕截图（看懂这一屏）。
            // API 30 以下 / 开关关掉 / 截不到图 → 静默退回纯树，不影响任何现有流程。
            val visionOn = settingsRepository.agentVision.first() && ScreenCapture.supported()
            val shotDir = AgentAccessibilityService.instance?.filesDir
            if (visionOn) onStep("…已开屏幕截图（更准，但每步多花一点图片 token）")
            // ===== M3-H①：先试「已学脚本」重放（成功即 0 次模型调用）=====
            val scriptFile = scriptFile()
            val scripts = scriptFile?.let { ActionScriptStore.load(it) }.orEmpty()
            val replay = runCatching { replayScript(scripts, goal, config, confirm, isStopped, onStep) }
                .getOrNull()
            if (replay != null) return replay

            val history = mutableListOf<String>()
            val recorded = mutableListOf<ScriptStep>()
            var lastActionSig = ""
            var sameActionStreak = 0
            var readFailStreak = 0
            // 录制用的起点指纹：**在任何（可能部分执行的）重放之后**再取，否则会存下一个错的起点
            val recordSig = ActionScriptStore.screenSignature(
                ActionExecutor.currentPackage(),
                ScreenState.snapshot(
                    AgentAccessibilityService.instance?.rootInActiveWindow,
                    AgentAccessibilityService.windowTick.value,
                ),
            )
            for (step in 1..config.maxSteps) {
                if (isStopped()) return Result(false, "已手动停止", step - 1)

                // 白名单围栏：白名单非空且当前包不在清单内 → 停
                val pkg = ActionExecutor.currentPackage()
                if (!config.allowsPackage(pkg)) {
                    onStep("⛔ 目标应用 $pkg 不在白名单")
                    return Result(false, "目标应用不在白名单（$pkg）", step - 1)
                }

                val elements = ScreenState.snapshot(
                    AgentAccessibilityService.instance?.rootInActiveWindow,
                    tick = AgentAccessibilityService.windowTick.value,
                )
                if (elements.isEmpty()) {
                    // 自愈止损：连续读不到屏幕 = 无障碍连接异常（被杀/被回收），
                    // 原地空转只会把 15 步烧完，直接中止让用户去重开服务
                    readFailStreak++
                    if (readFailStreak >= 3) {
                        onStep("⛔ 连续读不到屏幕，无障碍连接异常")
                        return Result(false, "无障碍连接异常（连续读屏失败），请到系统设置重开无障碍服务后重试", step - 1)
                    }
                    onStep("…屏幕未就绪，等待")
                    waitWindowStable()
                    continue
                }
                readFailStreak = 0

                // 决策：解析失败重试 1 次（**不再原样重发**——同一输入必然得到同一错误答案，
                // 等于花两次钱买同一个错；重试时追加提示约束输出格式）
                // 截图每步现拍一张（模型看的必须是"当前"这一屏）
                val shotPath = if (visionOn && shotDir != null) {
                    runCatching { ScreenCapture.captureToFile(shotDir) }.getOrNull()
                } else {
                    null
                }
                val action = requestAction(apiCfg, goal, elements, history, shotPath = shotPath) ?: run {
                    onStep("⚠️ 模型输出无法解析，改写提示重试一次")
                    requestAction(apiCfg, goal, elements, history, retryHint = RETRY_HINT, shotPath = shotPath)
                } ?: return Result(false, "模型连续输出无效动作", step - 1)

                if (action.type == "done") {
                    // M3-H②：**只有成功的任务**才落盘（失败任务不记录）；步骤太少不存（重放价值低）
                    scriptFile?.let { file ->
                        if (recorded.size >= ActionScriptStore.MIN_STEPS) {
                            val script = ActionScript(
                                goalKey = ActionScriptStore.normalizeGoal(goal),
                                startPackage = ActionExecutor.currentPackage().orEmpty(),
                                startSignature = recordSig,
                                steps = recorded.toList(),
                            )
                            ActionScriptStore.save(file, ActionScriptStore.upsert(scripts, script))
                        }
                    }
                    return Result(true, action.summary.ifBlank { "已完成" }, step)
                }

                val outcome = execute(action, elements, config, confirm)
                when (outcome) {
                    is StepOutcome.Done -> {
                        history += outcome.desc
                        onStep("第 $step 步：${outcome.desc}")
                        // 录制这一步（带元素指纹；back/home/swipe 无指纹）
                        val acted = elements.getOrNull(action.element ?: -1)
                        recorded += ScriptStep(
                            action = action.type,
                            value = action.value,
                            dir = action.dir,
                            fp = acted?.let { ActionScriptStore.fingerprintOf(it) },
                        )
                    }
                    is StepOutcome.Failed -> {
                        history += "动作 ${action.type} 执行失败（${outcome.reason}）"
                        onStep("第 $step 步：动作执行失败（${outcome.reason}）")
                    }
                }
                // v1.0 M2-E：卡住判定改按**动作签名**（不再按执行结果描述），
                // 于是**失败**动作也计入 —— 旧实现只在成功时比较，反复失败的动作永远触发不了止损。
                val sig = AgentPrompts.actionSignature(action)
                sameActionStreak = if (sig == lastActionSig) sameActionStreak + 1 else 0
                lastActionSig = sig
                if (sameActionStreak >= 2) {
                    return Result(false, "连续重复相同动作（含失败），疑似卡住，已停止", step)
                }
                waitWindowStable()
            }
            return Result(false, "达到步数上限（${config.maxSteps} 步）", config.maxSteps)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协作式取消照规矩重抛，别把它当成"执行失败"吞掉
            throw e
        } catch (e: Throwable) {
            // v0.97.0：**网络异常以前会直接冒泡**（requestAction → collectReply 抛 IOException），
            // scope 没有 CoroutineExceptionHandler → 整个 App 崩。这里把它变成一句可读的失败结果，
            // 让 finally 正常收掉悬浮停止按钮、让调用方拿到 Finished 而不是无限 Running。
            val why = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            onStep("⚠️ 执行中断：$why")
            return Result(false, "执行中断：$why", 0)
        } finally {
            AgentAccessibilityService.instance?.hideStopOverlay()
        }
    }

    private fun scriptFile(): java.io.File? =
        AgentAccessibilityService.instance?.let { java.io.File(it.filesDir, "agent_scripts.json") }

    /**
     * M3-H①：重放已学脚本。
     * @return 成功（0 次模型调用）/ 被停止 → 结果；**任一环节匹配不上 → null**（调用方回退逐屏决策）
     *
     * 设计要点（方案 §6.2-H）：
     * - 每一步**先重新读屏、按指纹（resourceId 优先，无 id 用 类型+标签）找元素**，
     *   找不到或同屏多个命中（不确定）→ 整条脚本作废，绝不按旧坐标盲点；
     * - 白名单与敏感确认**照旧生效**（重放不是免检通道）；
     * - 界面改版导致的失效**只会退化成"这次慢一点"**，不会变成"点错东西"。
     */
    private suspend fun replayScript(
        scripts: List<ActionScript>,
        goal: String,
        config: com.menghuan.app.agent.AgentConfig,
        confirm: suspend (String) -> Boolean,
        isStopped: () -> Boolean,
        onStep: (String) -> Unit,
    ): Result? {
        if (scripts.isEmpty()) return null
        val startPkg = ActionExecutor.currentPackage()
        val startElements = ScreenState.snapshot(
            AgentAccessibilityService.instance?.rootInActiveWindow,
            AgentAccessibilityService.windowTick.value,
        )
        val script = ActionScriptStore.find(
            scripts, goal, startPkg,
            ActionScriptStore.screenSignature(startPkg, startElements),
        ) ?: return null
        onStep("…命中已学脚本（${script.steps.size} 步），尝试本地重放")
        var done = 0
        for (step in script.steps) {
            if (isStopped()) return Result(false, "已手动停止", done)
            val pkg = ActionExecutor.currentPackage()
            if (!config.allowsPackage(pkg)) {
                onStep("⛔ 目标应用 $pkg 不在白名单")
                return Result(false, "目标应用不在白名单（$pkg）", done)
            }
            val elements = ScreenState.snapshot(
                AgentAccessibilityService.instance?.rootInActiveWindow,
                AgentAccessibilityService.windowTick.value,
            )
            when (step.action) {
                "back" -> if (!ActionExecutor.back()) return null
                "home" -> if (!ActionExecutor.home()) return null
                "swipe" -> if (!ActionExecutor.swipe(step.dir)) return null
                "tap", "text" -> {
                    // 指纹匹配不上、或同屏有多个同指纹元素（不确定）→ 整条脚本作废
                    val element = ActionScriptStore.matchByFingerprint(step.fp, elements) ?: return null
                    val sensitive = AgentPrompts.isSensitive(element) ||
                        (step.action == "text" && AgentPrompts.isSensitiveValue(step.value))
                    if (config.sensitiveConfirm && sensitive) {
                        val ok = confirm("将操作「${element.label}」（疑似敏感操作），继续吗？")
                        if (!ok) return Result(false, "用户取消了该动作", done)
                    }
                    val ok = if (step.action == "tap") {
                        ActionExecutor.tapElement(element, allowCoordinateOnly = true)
                    } else {
                        ActionExecutor.setText(element, step.value, allowCoordinateOnly = true)
                    }
                    if (!ok) return null
                }
                else -> return null
            }
            done++
            onStep("第 $done 步（已学脚本）：${step.action}")
            waitWindowStable()
        }
        return Result(true, "这次按学过的步骤本地完成（0 次模型调用）", done)
    }

    private suspend fun requestAction(
        api: com.menghuan.app.data.ApiConfig,
        goal: String,
        elements: List<UiElement>,
        history: List<String>,
        retryHint: String = "",
        shotPath: String? = null,
    ): AgentPrompts.Action? {
        val prompt = AgentPrompts.userPrompt(goal, ScreenState.toPrompt(elements), history, retryHint)
        val reply = OpenAiClient(api.baseUrl, api.apiKey, api.model)
            .collectReply(
                systemPrompt = AgentPrompts.SYSTEM,
                history = listOf(
                    ChatMessage(
                        conversationId = 0,
                        isFromUser = true,
                        content = prompt,
                        // 多模态通道按"消息带 imagePath"走（与聊天发图/识图记账同一条链）
                        imagePath = shotPath.orEmpty(),
                    ),
                ),
                // ⚠️ **这个 lambda 不能省**：`collectReply` 的默认值是 `{ null }`，
                // 只给 ChatMessage 填了 imagePath 而不传它 → 图片**静默不发**（等于白截图）。
                // 与聊天链（ChatViewModel.streamEvents）同一套现场降采样约定。
                imageDataUrl = { path -> com.menghuan.app.data.VisionImage.encodeDataUrl(path) },
            )
        runCatching {
            com.menghuan.app.data.UsageTracker.add(
                settingsRepository, reply.usageTokens ?: 0, api.model,
                reply.promptTokens, reply.cachedPromptTokens,
            )
        }
        return AgentPrompts.parseAction(reply.text)
    }

    /**
     * 单步执行结果。[Failed] 带**可读原因**（写进日志与历史，便于模型与用户看懂为什么没成）。
     */
    private sealed interface StepOutcome {
        data class Done(val desc: String) : StepOutcome
        data class Failed(val reason: String) : StepOutcome
    }

    /**
     * 快照之后界面动过没有（M2-F）：拿元素的出生 tick 与**当前**窗口 tick 比。
     * ⚠️ 必须比 tick，不能比"我们自己的快照序号"——序号只有自己会推进，比它永远相等，等于没校验。
     */
    private fun screenMoved(elements: List<UiElement>): Boolean {
        val born = elements.firstOrNull()?.snapTick ?: return true
        return born != AgentAccessibilityService.windowTick.value
    }

    private suspend fun execute(
        action: AgentPrompts.Action,
        elements: List<UiElement>,
        config: com.menghuan.app.agent.AgentConfig,
        confirm: suspend (String) -> Boolean,
    ): StepOutcome {
        // 编号与快照绑定：快照序号变了，编号→元素的映射就不可信了。
        // 此时只允许**按 resourceId 重新定位**；找不到 id 的元素直接不做，而不是按旧坐标点下去。
        val allowCoordinateOnly = !screenMoved(elements)
        return when (action.type) {
        "back" -> {
            ActionExecutor.back()
            StepOutcome.Done("返回")
        }
        "home" -> {
            ActionExecutor.home()
            StepOutcome.Done("回桌面")
        }
        "swipe" -> {
            ActionExecutor.swipe(action.dir)
            StepOutcome.Done("滑动（${action.dir.ifBlank { "up" }}）")
        }
        "tap" -> {
            val element = elements.getOrNull(action.element ?: -1)
                ?: return StepOutcome.Failed("编号不存在")
            if (config.sensitiveConfirm && AgentPrompts.isSensitive(element)) {
                val ok = confirm("将点击「${element.label}」（疑似敏感操作），继续吗？")
                if (!ok) return StepOutcome.Failed("用户取消了该动作")
            }
            if (!ActionExecutor.tapElement(element, allowCoordinateOnly)) {
                return StepOutcome.Failed(if (allowCoordinateOnly) "元素找不到" else "界面已变化")
            }
            StepOutcome.Done("点击 [${element.index}] ${element.label.take(20)}")
        }
        "text" -> {
            val element = elements.getOrNull(action.element ?: -1)
                ?: return StepOutcome.Failed("编号不存在")
            // M2-G：要**输入**的内容也要过敏感词表（旧实现只看元素文案）
            val sensitive = AgentPrompts.isSensitive(element) || AgentPrompts.isSensitiveValue(action.value)
            if (config.sensitiveConfirm && sensitive) {
                val ok = confirm("将在「${element.label}」输入内容，继续吗？")
                if (!ok) return StepOutcome.Failed("用户取消了该动作")
            }
            val ok = ActionExecutor.setText(element, action.value, allowCoordinateOnly)
            if (ok) StepOutcome.Done("输入 [${element.index}] = ${action.value.take(20)}")
            else StepOutcome.Failed(if (allowCoordinateOnly) "元素找不到或不可输入" else "界面已变化")
        }
        else -> StepOutcome.Failed("未知动作类型 ${action.type}")
        }
    }

    /**
     * 等界面稳定（总上限 2.5s 不变）：
     * 旧实现 quietStart 从函数入口起算，逻辑上**保证最少等满 900ms**，15 步就是 ≥13.5 秒纯等待。
     * 现分两阶段——先等"首次 tick 变化"最多 600ms（完全无变化 = 界面本来就没动，**没有变化本身就是稳定的证据**，
     * 直接返回）；出现变化后再等静默 400ms。常规单步等待从固定 900ms 降到 200–500ms。
     */
    private suspend fun waitWindowStable() {
        val enteredAt = System.currentTimeMillis()
        val deadline = enteredAt + 2500
        val baseTick = AgentAccessibilityService.windowTick.value
        var lastTick = baseTick
        // 阶段一：最多 600ms 等首次变化
        while (System.currentTimeMillis() - enteredAt < 600 && System.currentTimeMillis() < deadline) {
            delay(100)
            val now = AgentAccessibilityService.windowTick.value
            if (now != lastTick) {
                lastTick = now
                break
            }
        }
        if (lastTick == baseTick) return
        // 阶段二：变化后等 400ms 静默
        var quietStart = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            delay(150)
            val now = AgentAccessibilityService.windowTick.value
            if (now != lastTick) {
                lastTick = now
                quietStart = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - quietStart >= 400) {
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
            // v0.97.0：**这层兜底不能省**。以前这里没有 try/catch，run() 里任何漏网的异常
            // （或 onFinish 抛错）都会让 state 永远停在 Running —— 而 start() 开头
            // `if (state.value is Running) return` 会让 Agent **永久无法再启动**，只能重启 App；
            // 同时异常冒泡到没有处理器的 scope = 直接崩溃。
            val result = try {
                AgentLoop(settingsRepository).run(
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val why = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                AgentLoop.Result(false, "执行出错：$why", 0)
            } finally {
                // 确认弹层若还挂着，收掉，别让它留在界面上
                confirmDeferred = null
            }
            state.value = State.Finished(result.success, result.summary, result.steps)
            runCatching { onFinish?.invoke(result) }
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
