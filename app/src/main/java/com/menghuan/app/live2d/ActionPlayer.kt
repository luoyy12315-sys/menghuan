package com.menghuan.app.live2d

import com.live2d.sdk.cubism.framework.model.CubismModel
import kotlin.math.abs
import kotlin.math.max

/**
 * 动作播放器（v0.64.0 · v0.67.0 加承接层）——整套系统的运行时心脏。
 *
 * 职责只有一个：**把一条 [Live2dAction] 的轨道，按当前进度写进模型参数，并吐出舞台姿态。**
 * 它不认识通话、不认识 UI、不联网、不碰数据库。
 *
 * 线程模型（照抄本项目已有的 Live2D 那一套，别改）：
 * - [play] / [stop] 可能来自任意线程（UI、通话引擎），只写 `@Volatile` 的意图字段；
 * - [applyTo] **必须在 GL 线程**，每帧调一次，在它里面把意图落实成参数写入。
 *
 * v0.67.0 起**不再自己管包络**：淡入 / 淡出 / 换动作的承接 / 循环接缝全部交给 [ActionBlender]，
 * 这里只管"这一帧的目标值是多少"和"这条动作还在不在跑"。
 * 原因见 [ActionBlender] 的类注释 —— 包络从 0 起会让切换瞬间弹回静止位，
 * 而"弹一下"正是通话里最扎眼的观感问题。
 *
 * ⚠️ 三层幅度相乘的含义各不相同，别合并（见 [ModelCapability.applyScale]）：
 * `轨道值(±1) × 动作强度 × 缺通道降级系数`，再经淡入/淡出的承接权重。
 * 其中"轨道值 ×1.0"对应模型该参数可用范围的 [AMPLITUDE_SPAN] ——
 * 这道闸管的是 **"任何模型都不该被甩到破图"**：参数范围小的小幅动、
 * 范围大的也不会把角色扯变形。
 *
 * ⚠️ 口型**不在这里处理**。`ParamMouthOpenY` 由框架的 lip-sync 更新器在动作写完之后
 * 以加法叠加（官方实现如此），所以"动作里写张嘴"在安静时生效、在说话时被语音盖过 ——
 * 这正是我们要的互不打架。四态默认动作刻意不碰它（否则角色会一直张着嘴）。
 */
class ActionPlayer {

    // ---- 跨线程意图 ----

    /** 待播放的动作（null = 没有新意图）。放进去由 GL 线程下一帧取用 */
    @Volatile
    private var pending: Live2dAction? = null

    @Volatile
    private var stopRequested = false

    /** 正在播的动作（GL 线程读，也开放给 UI 读，所以是 volatile） */
    @Volatile
    var current: Live2dAction? = null
        private set

    /** 当前进度 0..1（UI 上的进度条 / 预览用） */
    @Volatile
    var progress: Float = 0f
        private set

    /**
     * 一条**一次性**动作"彻底放完"（连淡出都收了）的次数，GL 线程自增。
     *
     * 为什么只留一个计数而不做回调：① 写与读都在 GL 线程的同一次 [applyTo] 前后，
     * 不需要跨线程通知；② 回调要从渲染线程打进页面/仓库层，那是"卡住整条渲染线程"
     * 的经典入口（本项目已经有 [Live2dCharacter.onCatalogReady] 这条必须只许转手的规矩，
     * 不想再来第二条）。调用方每帧比一下数字就知道"刚放完了一条"。
     */
    var finishedCount: Int = 0
        private set

    /** 空闲 = 没有动作在写参数，也没有残留要收 —— 模型回到它自己的待机动作与呼吸眨眼 */
    val isPlaying: Boolean
        get() = current != null || phase != Phase.IDLE

    // ---- GL 线程内部状态 ----

    private enum class Phase { IDLE, PLAYING, ENDING }

    /**
     * GL 线程每帧填：通道序号 → 这张模型里真实存在的参数槽（null = 这张模型没这个通道）。
     *
     * 存成"按通道序号编址的数组"而不是 Map：`applyTo` 现在每帧要遍历的是**全部要写的通道**
     * （承接层淡回去的那些通道并不在当前动作的轨道里），Map 那条路每帧都要新建一个
     * entrySet 迭代器。一帧一个对象听着不多，但这条循环每秒跑 60 次、和通话的识别与合成
     * 抢同一颗 CPU —— 换成定长数组后每帧只是下标读，零分配。
     */
    private var slots: Array<List<ParamSlot>?> = arrayOfNulls(0)

    private val blender = ActionBlender(rotPeriod = STAGE_FULL_TURN_DEG)
    private var phase = Phase.IDLE
    private var elapsedMs = 0f
    private var baseScale = 1f
    private var capability: ModelCapability = ModelCapability.EMPTY

    // ---- 对外接口 ----

    /**
     * 请求播放。**任意线程可调**。
     *
     * 不在这里判"模型支不支持"：能力表只有 GL 线程手上那份是准的
     * （模型是 GL 线程加载的），判定放到 [applyTo] 里，判不过就立刻置空闲，
     * 调用方下一帧就能发现并回退到别的动作。
     */
    fun play(action: Live2dAction) {
        stopRequested = false
        pending = action
    }

    /**
     * 请求停止并回到模型的自然状态。任意线程可调。
     *
     * ⚠️ 这里是"收着回去"而不是"立刻撒手"：[applyTo] 会把当前姿态淡到静止位再交还控制权。
     * 立刻撒手会让舞台旋转/位移在一帧之内归零（摆到手最高点时切状态 = 手臂啪地掉下来）。
     */
    fun stop() {
        stopRequested = true
        pending = null
    }

    /**
     * 把模型绑上来（**GL 线程**，模型加载完成、还没播任何动作时调一次）。
     *
     * 这里记下的 `rest` 是**模型自己的静止值**，不是 0 ——
     * 很多模型的 `ParamArmLA` 静止在 5、`ParamAngleY` 静止在 -2，
     * 一律当成 0 会让每次播动作都先"跳"一下。
     *
     * @param capability 探测出来的通道 → 参数 ID
     * @param indexOf 参数名 → 参数下标（`< 0` = 这张模型没有这个参数）
     */
    fun bindModel(
        model: CubismModel,
        capability: ModelCapability,
        indexOf: (String) -> Int,
    ) {
        this.capability = capability
        val arr = arrayOfNulls<List<ParamSlot>>(GenericChannel.entries.size)
        capability.available.forEach { (channel, ids) ->
            if (channel.isStage) return@forEach
            val list = ArrayList<ParamSlot>(ids.size)
            ids.forEach { id ->
                val index = runCatching { indexOf(id) }.getOrDefault(-1)
                if (index < 0) return@forEach
                val rest = runCatching { model.getParameterValue(index) }.getOrDefault(0f)
                val maxV = runCatching { model.getParameterMaximumValue(index) }.getOrDefault(1f)
                val minV = runCatching { model.getParameterMinimumValue(index) }.getOrDefault(-1f)
                val span = max(abs(maxV - rest), abs(rest - minV)).takeIf { it > 1e-4f } ?: 0f
                if (span > 0f) list.add(ParamSlot(index, rest, span))
            }
            if (list.isNotEmpty()) arr[channel.ordinal] = list
        }
        slots = arr
    }

    /** 模型要没了（挂断 / 换模型）：清干净，别让下一张模型用到上一张的参数下标 */
    fun release() {
        pending = null
        stopRequested = false
        current = null
        phase = Phase.IDLE
        slots = arrayOfNulls(0)
        capability = ModelCapability.EMPTY
        elapsedMs = 0f
        progress = 0f
        baseScale = 1f
        blender.reset()
    }

    /**
     * 每帧推进并写参数（**GL 线程**）。
     *
     * @return 这一帧的舞台姿态；没在动就是 [StagePose.NONE]
     */
    fun applyTo(model: CubismModel, dtSeconds: Float): StagePose {
        val dtMs = dtSeconds.coerceIn(0f, MAX_FRAME_SECONDS) * 1000f

        // ① 落实"停"：先把手上的动作放开，再开一段淡出把姿态收回去。
        //    判序看的是**调用顺序**而不是这里的代码顺序：`play()` 会把 stopRequested 清掉，
        //    所以"先 stop 再 play"落到这里只剩 play（以播为准），
        //    "先 play 再 stop"则只剩 stopRequested（收势回去）。
        //    同帧里两个都在时不可能出现 —— 它们各自都是一次赋值，后写的那个赢。
        if (stopRequested) {
            stopRequested = false
            pending = null
            if (phase != Phase.IDLE || blender.weight() < 1f) {
                phase = Phase.ENDING
                blender.startFade(current?.safeFadeOutMs?.toFloat() ?: FALLBACK_FADE_MS)
            }
            current = null
            elapsedMs = 0f
            progress = 0f
        }

        // ② 落实"播"的意图
        pending?.let { next ->
            pending = null
            // 必需通道全缺 → 这条动作在这张模型上不成立，直接判空闲，调用方去回退
            val runnable = if (capability.canRun(next.compatibility)) next else null
            current = runnable
            elapsedMs = 0f
            progress = 0f
            baseScale = capability.applyScale(next)
            phase = if (runnable != null) Phase.PLAYING else Phase.ENDING
            // 淡入时长就是承接时长：从静止起 = 和旧实现完全一致，从别的姿态接手 = 交叉淡化
            blender.startFade(next.safeFadeInMs.toFloat())
        }

        val action = current
        if (phase == Phase.PLAYING && action != null) {
            advanceTimeline(action, dtMs)
        }

        blender.beginFrame()
        if (phase == Phase.PLAYING && action != null) {
            writeTargets(action)
        }
        blender.advance(dtMs)
        blender.settle()

        // ③ 写参数：只写这一帧"拥有"的通道（见 ActionBlender.owns 那条警告）
        val all = slots
        val channels = GenericChannel.entries
        for (i in all.indices) {
            val list = all[i] ?: continue
            val channel = channels[i]
            if (!blender.owns(channel)) continue
            val v = blender.valueOf(channel) * AMPLITUDE_SPAN
            if (v == 0f) continue
            // 这里每帧都跑，所以用 for 而不是 forEach { lambda }：
            // 后者每帧每个通道都要建一个捕获 model/v/scale 的闭包对象
            for (slot in list) {
                model.setParameterValue(slot.index, slot.rest + v * slot.span)
            }
        }

        // ④ 收尾：淡出跑完 → 彻底交还控制权，并给调用方一个"这条放完了"的读数
        if (phase == Phase.ENDING && blender.isSettled()) {
            phase = Phase.IDLE
            finishedCount++
        }

        return blender.stagePose()
    }

    /** 时间轴推进：循环动作在绕回起点那一刻重开一段承接，把接缝摊平 */
    private fun advanceTimeline(action: Live2dAction, dtMs: Float) {
        elapsedMs += dtMs
        val cycleMs = action.playDurationMs.toFloat()
        if (elapsedMs < cycleMs) {
            progress = elapsedMs / cycleMs
            return
        }
        if (action.loop) {
            elapsedMs -= cycleMs
            progress = 0f
            blender.startFade(LOOP_SEAM_FADE_MS)
            return
        }
        // 一次性动作到头：不立刻撒手，交给 ENDING 的淡出段收势
        progress = 1f
        phase = Phase.ENDING
        current = null
        blender.startFade(action.safeFadeOutMs.toFloat())
    }

    /** 把这一帧的轨道值累加进承接层 */
    private fun writeTargets(action: Live2dAction) {
        val scale = baseScale
        for (track in action.tracks) {
            val raw = track.valueAt(progress)
            if (raw == 0f) continue
            val v = raw * scale
            val channel = track.channel
            if (channel.isStage) {
                when (channel) {
                    GenericChannel.STAGE_X -> blender.addStageTarget(v * channel.stageUnit, 0f, 0f, 0f)
                    GenericChannel.STAGE_Y -> blender.addStageTarget(0f, v * channel.stageUnit, 0f, 0f)
                    GenericChannel.STAGE_ROT -> blender.addStageTarget(0f, 0f, v * channel.stageUnit, 0f)
                    GenericChannel.STAGE_SCALE -> blender.addStageTarget(0f, 0f, 0f, v * channel.stageUnit)
                    else -> Unit
                }
            } else {
                blender.addTarget(channel, v)
            }
        }
    }

    /** 一个参数槽：下标 + 模型自己的静止值 + 可用跨度的一半 */
    private class ParamSlot(val index: Int, val rest: Float, val span: Float)

    companion object {
        /**
         * 归一化 1.0 对应"参数可用范围的多少"。
         *
         * v0.65.0 从 0.6 提到 **0.9**（用户点名"动作显示幅度大一点"，选的是 +50% 那一档）。
         * 留 0.1 的余量不推到端点：参数顶到端点时网格会明显拉坏（尤其半身模型的手臂），
         * 真机上如果看到"摆手把手臂扯变形"，往回收就改这一个数，不要去改各条动作的强度
         *（内置动作是按 1.0 写的，逐条改会让四态默认与用户自自动作的观感不一致）。
         */
        const val AMPLITUDE_SPAN = 0.9f

        /**
         * 一帧最多推进多少秒。
         *
         * 真机上的"大 dt"有两个来源：切后台/锁屏回来后 `System.nanoTime()` 差了几秒，
         * 以及某帧被 GC 或贴图上传卡住。原来夹到 100ms，等于**回前台那一帧让动作白跳十分之一秒**
         * （观感是"角色抽一下"）。按 60fps 算 1/30 已经是两帧的量，足够吸收偶发丢帧，
         * 又不会把卡顿放大成可见的跳帧。
         */
        const val MAX_FRAME_SECONDS = 1f / 30f

        /** 循环动作绕回起点那一下的承接时长（比淡入短得多，只为抹平接缝） */
        const val LOOP_SEAM_FADE_MS = 120f

        /** 没拿到动作自带淡出时长时的兜底 */
        const val FALLBACK_FADE_MS = 300f
    }
}
