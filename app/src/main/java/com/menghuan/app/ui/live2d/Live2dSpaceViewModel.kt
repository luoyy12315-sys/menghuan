package com.menghuan.app.ui.live2d

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.menghuan.app.MenghuanApp
import com.menghuan.app.data.AiActionGenerator
import com.menghuan.app.data.CallVisualStore
import com.menghuan.app.data.CallVisualType
import com.menghuan.app.data.Live2dSpaceStore
import com.menghuan.app.data.model.Persona
import com.menghuan.app.live2d.ActionSlot
import com.menghuan.app.live2d.ActionSource
import com.menghuan.app.live2d.ActionType
import com.menghuan.app.live2d.BuiltinActions
import com.menghuan.app.live2d.Live2dAction
import com.menghuan.app.live2d.ModelCapability
import com.menghuan.app.realtime.visual.Live2dStage
import com.menghuan.app.realtime.visual.Live2dVisualEngine
import com.menghuan.app.realtime.visual.VisualRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 角色空间页的 UI 状态 */
data class Live2dSpaceUiState(
    val persona: Persona? = null,
    /** 这个角色有没有可用的 Live2D 形象；false 时页面显示"先去导入模型包" */
    val hasModel: Boolean = false,
    val runtime: VisualRuntime = VisualRuntime.Empty,
    /** 动作库（内置 + 模型自带 + 我的 + AI），顺序由仓库保证 */
    val actions: List<Live2dAction> = emptyList(),
    /** 当前正在播的那条（用于高亮） */
    val playingId: Long = 0L,
    val playingName: String = "",
    val background: Live2dSpaceStore.SpaceConfig = Live2dSpaceStore.SpaceConfig(),
    val backgroundFile: File? = null,
    val capability: ModelCapability = ModelCapability.EMPTY,
    /** 一句给用户看的结果提示（导入失败 / 保存成功 / AI 没生成出来…） */
    val note: String? = null,
    val busy: Boolean = false,
)

/**
 * 角色 Live2D 空间（v0.64.0）。
 *
 * 这个页面**不属于视频通话**（用户第 1 条：两者解耦），所以它自己建一份引擎实例，
 * 与通话那份互不影响 —— 也正因为如此，"通话里播的是待机、空间里在试摆手"是可能的，
 * 两边各播各的，谁也不会把谁的动作拽走。
 *
 * 页面与引擎之间只有 [Live2dStage] 这一层：这里不出现任何 Cubism / GL 的名字。
 */
class Live2dSpaceViewModel(
    private val app: MenghuanApp,
    private val personaId: Long,
) : ViewModel() {

    private val _ui = MutableStateFlow(Live2dSpaceUiState())
    val ui: StateFlow<Live2dSpaceUiState> = _ui

    private var engine: Live2dVisualEngine? = null

    /** 给页面挂视图用；null 表示模型还没准备好（页面显示占位） */
    val stage: Live2dStage? get() = engine

    /** 非循环动作播完要自动回到待机，这个 job 就是那个定时器 */
    private var returnToIdleJob: Job? = null

    /** 空间里一直循环的那个"待机"动作 */
    private var idleAction: Live2dAction? = null

    init {
        viewModelScope.launch { boot() }
    }

    private suspend fun boot() {
        val persona = runCatching {
            app.db.personaDao().observeAll().first().find { it.id == personaId }
        }.getOrNull()
        _ui.update { it.copy(persona = persona) }

        // 背景先读出来：它与模型无关，模型没有也该把用户挑过的背景显示对
        val config = withContext(Dispatchers.IO) { Live2dSpaceStore.read(app, personaId) }
        val bgFile = withContext(Dispatchers.IO) {
            Live2dSpaceStore.backgroundFile(app, personaId, config)
        }
        _ui.update { it.copy(background = config, backgroundFile = bgFile) }

        val asset = withContext(Dispatchers.IO) { CallVisualStore.read(app, personaId) }
        val usable = asset.type == CallVisualType.LIVE2D &&
            asset.isUsable &&
            withContext(Dispatchers.IO) { CallVisualStore.live2dModelFile(app, personaId, asset) } != null
        _ui.update { it.copy(hasModel = usable) }
        if (!usable) return

        val e = Live2dVisualEngine(app, asset)
        engine = e
        _ui.update { it.copy(runtime = e.runtime.value) }
        viewModelScope.launch { e.runtime.collect { r -> _ui.update { s -> s.copy(runtime = r) } } }
        // 模型自带的动作 / 表情清单 → 同步进动作库（回调在 GL 线程，
        // 所以里面只做转手，真正的数据库写在协程里）
        e.onCatalogReady { catalog ->
            viewModelScope.launch {
                runCatching { app.live2dActions.syncModelActions(personaId, catalog) }
                reloadLibrary()
                _ui.update { it.copy(capability = e.capability()) }
            }
        }
        runCatching { e.load(personaId, asset) }
        reloadLibrary()
        // 进页面就开始待机 —— 角色要"独立运行待机、呼吸、眨眼"（用户要求 §二）
        playIdle()
    }

    // ---- 动作库 ----

    suspend fun reloadLibrary() {
        val list = runCatching { app.live2dActions.list(personaId) }.getOrDefault(emptyList())
        _ui.update { it.copy(actions = list) }
    }

    /** 空间动作条里显示的那些：**排除四态默认动作**（它们是通话用的，摆在空间里只会占位） */
    fun stageActions(): List<Live2dAction> = stageActionsOf(_ui.value.actions)

    // ---- 播放 ----

    /** 播一条动作（动作条点击 / 动作库里的"预览" / 点击互动都走这里） */
    fun play(action: Live2dAction) {
        val e = engine ?: return
        returnToIdleJob?.cancel()
        e.playAction(action)
        _ui.update { it.copy(playingId = action.id, playingName = action.name) }
        if (!action.loop) {
            // 播完自动回待机：**由时长算出回来的一刻**，而不是等引擎回调。
            // 引擎那边没有"播完了"的信号（它是每帧驱动的纯函数），加一个回调反而要动 GL 线程的时序；
            // 而时长+淡出是已知的，定时回来最省事也最准。
            returnToIdleJob = viewModelScope.launch {
                delay(action.playDurationMs.toLong() + action.safeFadeOutMs + RETURN_SLACK_MS)
                playIdle()
            }
        }
    }

    /** 回到循环待机。模型没了 / 还没加载时什么都不做 */
    fun playIdle() {
        val e = engine ?: return
        viewModelScope.launch {
            val idle = idleAction ?: app.live2dActions.resolveSlot(personaId, ActionSlot.IDLE).also { idleAction = it }
            e.playAction(idle)
            _ui.update { it.copy(playingId = idle.id, playingName = idle.name) }
        }
    }

    fun stop() {
        returnToIdleJob?.cancel()
        engine?.stopAction()
        _ui.update { it.copy(playingId = 0L, playingName = "") }
    }

    // ---- 点击互动与拖动 ----

    /**
     * 点了一下角色：判区域 → 查该区域该播什么 → 播。
     *
     * 判定与"播什么"分在两处（引擎判区域、仓库查绑定），页面只负责把两件事接起来 ——
     * 这样下一版做"区域 → 动作"的配置界面时，改的只有仓库里那张默认表。
     */
    fun onTap(ndcX: Float, ndcY: Float) {
        val e = engine ?: return
        viewModelScope.launch {
            val region = e.hit(ndcX, ndcY)
            val action = runCatching {
                app.live2dActions.resolveInteraction(personaId, region, System.currentTimeMillis())
            }.getOrNull() ?: return@launch
            play(action)
        }
    }

    /** 拖动角色：位移用画面比例（1 = 一整屏） */
    fun drag(dxFraction: Float, dyFraction: Float) {
        val e = engine ?: return
        offsetX = (offsetX + dxFraction).coerceIn(-MAX_OFFSET, MAX_OFFSET)
        offsetY = (offsetY + dyFraction).coerceIn(-MAX_OFFSET, MAX_OFFSET)
        e.setOffset(offsetX, offsetY)
    }

    private var offsetX = 0f
    private var offsetY = 0f

    // ---- 背景 ----

    fun setBackground(config: Live2dSpaceStore.SpaceConfig) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Live2dSpaceStore.write(app, personaId, config) }
            val file = withContext(Dispatchers.IO) { Live2dSpaceStore.backgroundFile(app, personaId, config) }
            _ui.update { it.copy(background = config, backgroundFile = file) }
        }
    }

    fun importBackground(uri: android.net.Uri) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val outcome = withContext(Dispatchers.IO) {
                Live2dSpaceStore.importBackground(app, personaId, uri)
            }
            when (outcome) {
                is Live2dSpaceStore.BackgroundImport.Ok -> {
                    val config = Live2dSpaceStore.SpaceConfig(
                        background = "file:${outcome.fileName}",
                    )
                    withContext(Dispatchers.IO) { Live2dSpaceStore.write(app, personaId, config) }
                    val file = withContext(Dispatchers.IO) { Live2dSpaceStore.backgroundFile(app, personaId, config) }
                    _ui.update { it.copy(busy = false, background = config, backgroundFile = file, note = null) }
                }

                is Live2dSpaceStore.BackgroundImport.Failed ->
                    _ui.update { it.copy(busy = false, note = outcome.reason) }
            }
        }
    }

    // ---- 动作的收藏 / 删除 / 保存 ----

    fun toggleFavorite(action: Live2dAction) {
        viewModelScope.launch {
            runCatching { app.live2dActions.setFavorite(action.id, !action.favorite) }
            reloadLibrary()
        }
    }

    fun delete(action: Live2dAction) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val ok = runCatching { app.live2dActions.delete(action.id) }.getOrDefault(false)
            reloadLibrary()
            _ui.update {
                it.copy(
                    busy = false,
                    note = if (ok) null else "这条动作不能删（内置与模型自带的不让删）",
                )
            }
            // 删掉的正好是正在播的那条 → 回待机，别让角色定格在一个不存在的动作上
            if (ok && _ui.value.playingId == action.id) playIdle()
        }
    }

    /** 保存"我的动作"。返回新动作 id（0 = 失败） */
    fun saveAction(action: Live2dAction, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val id = runCatching { app.live2dActions.save(action) }.getOrDefault(0L)
            reloadLibrary()
            _ui.update {
                it.copy(
                    busy = false,
                    note = if (id > 0L) "已保存到「我的动作」" else "保存失败，换一下名字再试",
                )
            }
            if (id > 0L) onSaved(id)
        }
    }

    fun clearNote() {
        _ui.update { it.copy(note = null) }
    }

    /**
     * AI 生成动作：**整个 App 里唯一一处会为动作联网的地方**（用户 §十）。
     *
     * 三条纪律：
     * ① 生成结果只作为"草稿"返回给页面，**不自动落库、不自动播放** ——
     *    用户要先看一眼再决定（用户 §七：本地预览 → 用户确认 → 保存到我的动作）；
     * ② 用的是**该角色自己那套接口配置**（和聊天、通话同源），不另立一份 Key；
     * ③ 会把当前模型的能力表一起交给 AI，让它优先用这张模型真有的通道
     *    （否则生成出来会"少一半动作"，用户还以为是功能坏了）。
     */
    suspend fun generateWithAi(description: String): kotlin.Result<Live2dAction> {
        val config = runCatching { app.settingsRepository.apiConfigFor(personaId).first() }.getOrNull()
            ?: return kotlin.Result.failure(IllegalStateException("还没配好对话接口，先在聊天里配一个"))
        val capability = engine?.capability() ?: ModelCapability.EMPTY
        return AiActionGenerator.generate(description, config, capability).map { r ->
            r.spec.toAction(
                name = r.name,
                source = ActionSource.AI,
                type = ActionType.PROCEDURAL,
                durationMs = r.durationMs,
                loop = r.loop,
            )
        }
    }

    /** 动作库里"用 AI 生成的那条"有没有重复名字（提示用户可以改） */
    fun namesOf(source: ActionSource): List<String> =
        _ui.value.actions.filter { it.source == source }.map { it.name }

    // ---- 生命周期 ----

    fun pause() {
        engine?.pause()
    }

    fun resume() {
        engine?.resume()
    }

    override fun onCleared() {
        returnToIdleJob?.cancel()
        runCatching { engine?.release() }
        engine = null
        super.onCleared()
    }

    companion object {
        /** 拖动范围：±0.6 屏，再大角色就整个跑出画面了 */
        const val MAX_OFFSET = 0.6f

        /** 播完后多等一点点再回待机：淡出还在跑，早回会让两个动作叠在一起 */
        const val RETURN_SLACK_MS = 60L

        fun Factory(personaId: Long) = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MenghuanApp
                Live2dSpaceViewModel(app, personaId)
            }
        }
    }
}

/**
 * 空间动作条的内容（纯函数）。
 *
 * 排除四态默认动作：它们的存在意义是"通话里那四个状态该做什么"，
 * 摆在角色空间的动作条上只会把真正好玩的动作挤到后面去。
 * 想改它们去「通话动作」页，这里不重复一份入口。
 */
fun stageActionsOf(actions: List<Live2dAction>): List<Live2dAction> {
    val stateKeys = ActionSlot.entries.map { BuiltinActions.defaultKeyOf(it) }.toSet()
    return actions.filter { it.builtinKey !in stateKeys && !it.isEmpty }
}
