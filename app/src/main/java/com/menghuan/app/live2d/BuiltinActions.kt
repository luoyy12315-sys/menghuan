package com.menghuan.app.live2d

/**
 * 通话的四个状态槽（v0.64.0）。
 *
 * 刻意定义在引擎侧而不是直接复用通话页的枚举：动作引擎要在"角色空间"里也被用到，
 * 而角色空间与通话毫无关系（用户第 1 / 第 2 条要求解耦）。
 * 与通话状态的映射写在通话那一侧（`CallVisualState.toActionSlot()`）。
 *
 * [key] 与 `CallVisualSlots` 的四个键**必须一致**：那是落进 metadata.json 的同一套状态名，
 * 两处各写一套就会出现"视频形象切了状态、动作却没跟着换"这种只在真机上看得出的错位。
 */
enum class ActionSlot(val key: String, val label: String) {
    IDLE("idle", "待机"),
    LISTENING("listening", "倾听"),
    THINKING("thinking", "思考"),
    SPEAKING("speaking", "说话"),
    ;

    companion object {
        fun fromKey(raw: String?): ActionSlot? = entries.firstOrNull { it.key == raw }
    }
}

/**
 * 系统内置动作（v0.64.0 · v0.67.0 把四态默认调到"看得出来"）。
 *
 * 一共八条，分两组：
 *
 * **四个状态默认动作**（`builtin_state_*`，循环）——就是用户要求里"系统默认提供四套基础动作"。
 * 它们全是**程序化轨道**，不依赖模型的任何一套素材；模型自己那条 Idle 动作仍然在底下跑
 * （`Live2dCharacter` 一直在空闲时播 idel 组），所以观感是"角色在自己的待机动作上，
 * 再叠一层跟状态对得上的小姿态"。这样做的两个好处：
 * ① 不用赌模型有没有 think/speak/listen 这几个组名 —— 免费模型十有八九没有；
 * ② 用户随时可以把任意状态换成动作库里的别的动作（含摆手这种大幅度动作）。
 *
 * **四个趣味动作**（`builtin_wave` / `builtin_shake` / `builtin_split` / `builtin_flip`）。
 * 它们才是"动作库"的招牌：摆手靠手臂 + 头身摆动，劈叉靠身体侧倾 + 双臂，
 * 翻跟斗靠**舞台通道**（整体旋转 + 跳起落下），所以**任何模型都演得出来**。
 *
 * ⚠️ 内置动作一律**不写 [GenericChannel.MOUTH]**：张嘴归实时语音的口型驱动，
 * 动作去抢这个参数就会出现"AI 没出声但角色一直张着嘴"。要表现情绪用 MOUTH_FORM（嘴型）。
 *
 * ⚠️ 四态默认也一律**不写 [GenericChannel.EYE]（睁眼）**：框架的 `CubismEyeBlink` 在
 * "这一帧没有 motion 写参数"时是 `setParameterValue` **覆盖**眼睛的（不是叠加），
 * 而免费模型根本没有 motion 文件 → 恰好每帧都走覆盖那一路 → 动作里写的睁眼**一句都不生效**。
 * 同一份动作在有的模型上眼睛会睁大、在另一些模型上毫无反应，比"全都不可见"更难解释。
 * 眼睛的戏改由眉毛（BROW）与视线（EYE_BALL_X/Y）承担 —— 那两个参数眨眼不碰。
 *
 * ## 幅度为什么是现在这个量级（v0.67.0）
 *
 * 用户点名"动作显示明显"。v0.64 那四套的轨道值只有 0.03–0.17，乘上
 * [ActionPlayer.AMPLITUDE_SPAN] 之后只吃掉参数可用范围的百分之几 ——
 * `ParamAngleZ` 典型 ±10° 时头只转 0.5°~1.5°，屏幕上基本读不出来"在动"。
 * 现在四态默认的**峰值落在 0.16–0.42**：`±10°` 的头会转 3°~8°，
 * 一眼能读出"它在听 / 在想 / 在说"，又远不到顶格（顶格会看到网格被拉坏）。
 */
object BuiltinActions {

    // ---- 内置键名：落库与"默认绑定"都靠它，改名即等于让老绑定失效 ----

    fun defaultKeyOf(slot: ActionSlot): String = when (slot) {
        ActionSlot.IDLE -> "builtin_state_idle"
        ActionSlot.LISTENING -> "builtin_state_listening"
        ActionSlot.THINKING -> "builtin_state_thinking"
        ActionSlot.SPEAKING -> "builtin_state_speaking"
    }

    const val WAVE = "builtin_wave"
    const val SHAKE = "builtin_shake"
    const val SPLIT = "builtin_split"
    const val FLIP = "builtin_flip"

    /**
     * 库里"趣味动作"那一档的固定顺序（列表要稳定，不能按插入时间乱跳）。
     *
     * 摇头是第 4 条：用户要求里"点击头部 → 摇头"是**默认互动**，
     * 而摇头本身也是个正经趣味动作，所以它进了内置而不是藏在互动配置里。
     */
    val funKeys: List<String> = listOf(WAVE, SHAKE, SPLIT, FLIP)

    private fun proc(
        name: String,
        builtinKey: String,
        durationMs: Int,
        loop: Boolean,
        fadeInMs: Int,
        fadeOutMs: Int,
        required: Set<GenericChannel>,
        optional: Set<GenericChannel>,
        tracks: List<ParameterTrack>,
    ) = Live2dAction(
        name = name,
        type = ActionType.PROCEDURAL,
        source = ActionSource.BUILTIN,
        builtinKey = builtinKey,
        durationMs = durationMs,
        loop = loop,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        tracks = tracks,
        compatibility = ModelCompatibility(required = required, optional = optional),
        createdAt = 0L,
        updatedAt = 0L,
    )

    // ---- 四态默认动作 ----

    /**
     * 待机：能读出来的轻摆 + 视线游移。呼吸与眨眼由框架自己跑，这里不再插手。
     *
     * 头与身体**反着摆**（Z 歪头时身体回正）是站姿的基本读法：同向摆看着像整体在晃，
     * 反向才像"一个人在那儿轻轻换重心"。
     */
    fun stateIdle(): Live2dAction = proc(
        name = "默认待机",
        builtinKey = defaultKeyOf(ActionSlot.IDLE),
        durationMs = 4_800,
        loop = true,
        fadeInMs = 420,
        fadeOutMs = 420,
        required = emptySet(),
        optional = setOf(GenericChannel.HEAD_Z, GenericChannel.HEAD_X, GenericChannel.BODY_Z, GenericChannel.EYE_BALL_X),
        tracks = listOf(
            ParameterTrack.of(
                GenericChannel.HEAD_Z,
                TrackEasing.IN_OUT,
                0f to 0f, 0.25f to 0.22f, 0.5f to 0f, 0.75f to -0.22f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.HEAD_X,
                TrackEasing.IN_OUT,
                0f to 0f, 0.3f to 0.20f, 0.65f to -0.16f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BODY_Z,
                TrackEasing.IN_OUT,
                0f to 0f, 0.3f to -0.12f, 0.7f to 0.10f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.EYE_BALL_X,
                TrackEasing.IN_OUT,
                0f to 0f, 0.4f to 0.26f, 0.8f to -0.18f, 1f to 0f,
            ),
        ),
    )

    /** 倾听：两次轻点头 + 歪着头看你 + 眉毛抬起来一点 */
    fun stateListening(): Live2dAction = proc(
        name = "默认倾听",
        builtinKey = defaultKeyOf(ActionSlot.LISTENING),
        durationMs = 3_400,
        loop = true,
        fadeInMs = 300,
        fadeOutMs = 320,
        required = emptySet(),
        optional = setOf(
            GenericChannel.HEAD_Y, GenericChannel.HEAD_Z, GenericChannel.BROW,
            GenericChannel.EYE_BALL_Y, GenericChannel.BODY_Y, GenericChannel.BODY_Z,
        ),
        tracks = listOf(
            ParameterTrack.of(
                GenericChannel.HEAD_Y,
                TrackEasing.IN_OUT,
                0f to 0f, 0.16f to 0.34f, 0.32f to 0.05f, 0.5f to 0.28f, 0.68f to 0.04f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.HEAD_Z,
                TrackEasing.IN_OUT,
                0f to 0f, 0.4f to 0.24f, 0.8f to 0.18f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BROW,
                TrackEasing.IN_OUT,
                0f to 0.16f, 0.4f to 0.34f, 1f to 0.16f,
            ),
            ParameterTrack.of(
                GenericChannel.EYE_BALL_Y,
                TrackEasing.IN_OUT,
                0f to 0f, 0.35f to -0.16f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BODY_Y,
                TrackEasing.IN_OUT,
                0f to 0f, 0.45f to 0.14f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BODY_Z,
                TrackEasing.IN_OUT,
                0f to 0f, 0.45f to -0.10f, 1f to 0f,
            ),
        ),
    )

    /** 思考：仰头 + 视线飘到一角 + 眉微皱，是"在想事情"的通用读法 */
    fun stateThinking(): Live2dAction = proc(
        name = "默认思考",
        builtinKey = defaultKeyOf(ActionSlot.THINKING),
        durationMs = 4_200,
        loop = true,
        fadeInMs = 360,
        fadeOutMs = 360,
        required = emptySet(),
        optional = setOf(
            GenericChannel.HEAD_Y, GenericChannel.HEAD_Z,
            GenericChannel.EYE_BALL_X, GenericChannel.EYE_BALL_Y, GenericChannel.BROW,
            GenericChannel.BODY_X,
        ),
        tracks = listOf(
            ParameterTrack.of(
                GenericChannel.HEAD_Y,
                TrackEasing.IN_OUT,
                0f to 0.10f, 0.35f to 0.38f, 0.75f to 0.12f, 1f to 0.10f,
            ),
            ParameterTrack.of(
                GenericChannel.HEAD_Z,
                TrackEasing.IN_OUT,
                0f to 0f, 0.35f to -0.32f, 0.8f to -0.24f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.EYE_BALL_X,
                TrackEasing.IN_OUT,
                0f to 0.28f, 0.5f to 0.55f, 1f to 0.28f,
            ),
            ParameterTrack.of(
                GenericChannel.EYE_BALL_Y,
                TrackEasing.IN_OUT,
                0f to -0.18f, 0.4f to -0.44f, 1f to -0.18f,
            ),
            ParameterTrack.of(
                GenericChannel.BROW,
                TrackEasing.IN_OUT,
                0f to 0.36f, 0.6f to 0.14f, 1f to 0.36f,
            ),
            ParameterTrack.of(
                GenericChannel.BODY_X,
                TrackEasing.IN_OUT,
                0f to 0f, 0.45f to 0.16f, 1f to 0f,
            ),
        ),
    )

    /**
     * 说话：头随语句摆三下 + 身体跟着 + 嘴角提着。嘴**不碰**（口型归实时语音）。
     *
     * 这是四态里幅度最大的一档：说话时人是"有能量"的，读起来该比听和想更活。
     * HEAD_X 三个来回用的是 IN_OUT 而不是 LINEAR —— 摆头要在两头有停顿才像说话打拍子，
     * 线性会看着像钟摆（[BuiltinActions.wave] 里那条摆手才是该用 LINEAR 的地方）。
     */
    fun stateSpeaking(): Live2dAction = proc(
        name = "默认说话",
        builtinKey = defaultKeyOf(ActionSlot.SPEAKING),
        durationMs = 2_400,
        loop = true,
        fadeInMs = 240,
        fadeOutMs = 260,
        required = emptySet(),
        optional = setOf(
            GenericChannel.HEAD_X, GenericChannel.HEAD_Z, GenericChannel.HEAD_Y,
            GenericChannel.BODY_X, GenericChannel.BODY_Z, GenericChannel.BROW, GenericChannel.MOUTH_FORM,
        ),
        tracks = listOf(
            ParameterTrack.of(
                GenericChannel.HEAD_X,
                TrackEasing.IN_OUT,
                0f to 0f, 0.2f to 0.32f, 0.45f to -0.24f, 0.7f to 0.20f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.HEAD_Z,
                TrackEasing.IN_OUT,
                0f to 0f, 0.3f to 0.20f, 0.7f to -0.18f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.HEAD_Y,
                TrackEasing.IN_OUT,
                0f to 0f, 0.5f to 0.14f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BODY_X,
                TrackEasing.IN_OUT,
                0f to 0f, 0.4f to 0.16f, 0.8f to -0.12f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BODY_Z,
                TrackEasing.IN_OUT,
                0f to 0f, 0.35f to 0.12f, 0.75f to -0.12f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BROW,
                TrackEasing.IN_OUT,
                0f to 0f, 0.3f to 0.24f, 0.7f to 0.10f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.MOUTH_FORM,
                TrackEasing.IN_OUT,
                0f to 0f, 0.25f to 0.30f, 0.8f to 0.30f, 1f to 0f,
            ),
        ),
    )

    fun stateDefault(slot: ActionSlot): Live2dAction = when (slot) {
        ActionSlot.IDLE -> stateIdle()
        ActionSlot.LISTENING -> stateListening()
        ActionSlot.THINKING -> stateThinking()
        ActionSlot.SPEAKING -> stateSpeaking()
    }

    // ---- 三个趣味动作 ----

    /**
     * 摆手：抬右手来回摆 + 头歪着笑。
     *
     * 手臂与手掌是**可选**通道：没有 `ParamArmRA` 的模型只演头身那一半（幅度会按
     * [ModelCapability.amplitudeScale] 收一点），不会"整个人僵住"。
     * 手臂用 [TrackEasing.LINEAR]：来回摆最怕被缓动抹平，摆到两头停一下就不像挥手了。
     *
     * v0.67.0：原来那条"眼睛睁大一点"（[GenericChannel.EYE]）删掉了 —— 它会被框架的
     * 眨眼覆盖（见文件头那条警告），换模型就有、换模型就没有。眼神改用视线偏移表达。
     */
    fun wave(): Live2dAction = proc(
        name = "摆手",
        builtinKey = WAVE,
        durationMs = 2_400,
        loop = false,
        fadeInMs = 200,
        fadeOutMs = 320,
        required = emptySet(),
        optional = setOf(
            GenericChannel.ARM_R, GenericChannel.HAND_R, GenericChannel.HEAD_Z,
            GenericChannel.HEAD_Y, GenericChannel.BODY_Z, GenericChannel.BROW,
            GenericChannel.MOUTH_FORM, GenericChannel.EYE_BALL_X,
        ),
        tracks = listOf(
            ParameterTrack.of(
                GenericChannel.ARM_R,
                TrackEasing.LINEAR,
                0f to 0f, 0.18f to 0.85f, 0.32f to 0.7f, 0.46f to 0.9f,
                0.62f to 0.7f, 0.76f to 0.88f, 0.9f to 0.72f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.HAND_R,
                TrackEasing.LINEAR,
                0f to 0f, 0.18f to 0.6f, 0.32f to -0.5f, 0.46f to 0.6f,
                0.62f to -0.5f, 0.76f to 0.6f, 0.9f to -0.4f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.HEAD_Z,
                TrackEasing.IN_OUT,
                0f to 0f, 0.25f to 0.28f, 0.5f to -0.24f, 0.75f to 0.20f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.HEAD_Y,
                TrackEasing.IN_OUT,
                0f to 0f, 0.5f to 0.16f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BODY_Z,
                TrackEasing.IN_OUT,
                0f to 0f, 0.3f to 0.14f, 0.7f to -0.14f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BROW,
                TrackEasing.IN_OUT,
                0f to 0f, 0.2f to 0.55f, 0.75f to 0.55f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.MOUTH_FORM,
                TrackEasing.IN_OUT,
                0f to 0f, 0.25f to 0.7f, 0.75f to 0.7f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.EYE_BALL_X,
                TrackEasing.IN_OUT,
                0f to 0f, 0.3f to 0.22f, 0.8f to 0.16f, 1f to 0f,
            ),
        ),
    )

    /**
     * 摇头：左右转头三次 + 微微歪头，是"不行 / 不要 / 不同意"的通用读法。
     *
     * 用 [GenericChannel.HEAD_X]（左右转头）而不是 `STAGE_X`：这里要的是"人物在摇头"，
     * 不是"整张画布在左右晃动"。画布晃会连背景一起动，读起来像手机在抖。
     */
    fun shake(): Live2dAction = proc(
        name = "摇头",
        builtinKey = SHAKE,
        durationMs = 1_600,
        loop = false,
        fadeInMs = 180,
        fadeOutMs = 260,
        required = emptySet(),
        optional = setOf(GenericChannel.HEAD_X, GenericChannel.HEAD_Z, GenericChannel.BROW, GenericChannel.MOUTH_FORM),
        tracks = listOf(
            ParameterTrack.of(
                GenericChannel.HEAD_X,
                TrackEasing.LINEAR,
                0f to 0f, 0.15f to 0.5f, 0.32f to -0.5f, 0.5f to 0.48f, 0.68f to -0.46f, 0.85f to 0.3f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.HEAD_Z,
                TrackEasing.IN_OUT,
                0f to 0f, 0.3f to -0.08f, 0.7f to 0.08f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BROW,
                TrackEasing.IN_OUT,
                0f to 0f, 0.25f to -0.35f, 0.75f to -0.3f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.MOUTH_FORM,
                TrackEasing.IN_OUT,
                0f to 0f, 0.3f to -0.4f, 0.7f to -0.35f, 1f to 0f,
            ),
        ),
    )

    /**
     * 劈叉：身体侧倾到底 + 双臂打开 + 整体下沉。
     *
     * 标准参数里**没有腿**（Live2D 的通用参数表只管到身体角度与手臂），
     * 所以"劈叉"在通用层做不出字面意义上的劈腿。这里给的是**最接近的读法**：
     * 身体侧倾 + 双手向两侧张开 + 重心下沉 —— 在绝大多数模型上都能读出"用力张开腿"。
     * 有腿部自定义参数的模型当然更准，但那属于模型特例，不在通用层许诺。
     *
     * `required = BODY_Z`：这是整条动作的骨架，缺了就整段跳过（回退到默认动作），
     * 而不是演一个"手张开但身子笔直"的怪东西。
     */
    fun split(): Live2dAction = proc(
        name = "劈叉",
        builtinKey = SPLIT,
        durationMs = 1_800,
        loop = false,
        fadeInMs = 260,
        fadeOutMs = 340,
        required = setOf(GenericChannel.BODY_Z),
        optional = setOf(
            GenericChannel.BODY_Y, GenericChannel.ARM_L, GenericChannel.ARM_R,
            GenericChannel.HEAD_Z, GenericChannel.HEAD_Y, GenericChannel.BROW,
        ),
        tracks = listOf(
            ParameterTrack.of(
                GenericChannel.BODY_Z,
                TrackEasing.IN_OUT,
                0f to 0f, 0.4f to 0.95f, 0.72f to 0.95f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BODY_Y,
                TrackEasing.IN_OUT,
                0f to 0f, 0.4f to 0.35f, 0.72f to 0.35f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.ARM_L,
                TrackEasing.IN_OUT,
                0f to 0f, 0.45f to 1f, 0.75f to 1f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.ARM_R,
                TrackEasing.IN_OUT,
                0f to 0f, 0.45f to 1f, 0.75f to 1f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.HEAD_Z,
                TrackEasing.IN_OUT,
                0f to 0f, 0.5f to -0.35f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.HEAD_Y,
                TrackEasing.IN_OUT,
                0f to 0f, 0.5f to -0.15f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BROW,
                TrackEasing.IN_OUT,
                0f to 0f, 0.4f to -0.4f, 0.7f to -0.35f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.STAGE_Y,
                TrackEasing.IN_OUT,
                0f to 0f, 0.42f to -0.22f, 0.75f to -0.22f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.STAGE_SCALE,
                TrackEasing.IN_OUT,
                0f to 0f, 0.45f to -0.08f, 0.75f to -0.08f, 1f to 0f,
            ),
        ),
    )

    /**
     * 翻跟斗：**整张画布转一圈**（360°）+ 跳起来再落下 + 身体蜷缩。
     *
     * 这条动作是"舞台通道"存在的理由：它一个模型参数都不需要，
     * 所以**任何模型都能翻**。预先向后仰一点（0.12 处的负值）做蓄力，
     * 转完停在 2.0（= 360°，回到直立）再淡出，观感才是"翻过去站稳了"而不是"翻一半被掐断"。
     *
     * 旋转用 [TrackEasing.IN_OUT]：起步与落地都慢、中间快，是真实翻滚的节奏。
     */
    fun flip(): Live2dAction = proc(
        name = "翻跟斗",
        builtinKey = FLIP,
        durationMs = 2_000,
        loop = false,
        fadeInMs = 180,
        fadeOutMs = 260,
        required = emptySet(),
        optional = setOf(
            GenericChannel.BODY_X, GenericChannel.HEAD_Y,
            GenericChannel.ARM_L, GenericChannel.ARM_R, GenericChannel.BROW,
        ),
        tracks = listOf(
            ParameterTrack.of(
                GenericChannel.STAGE_ROT,
                TrackEasing.IN_OUT,
                0f to 0f, 0.12f to -0.35f, 0.85f to 2f, 1f to 2f,
            ),
            ParameterTrack.of(
                GenericChannel.STAGE_Y,
                TrackEasing.IN_OUT,
                0f to 0f, 0.18f to 0.55f, 0.5f to 0.95f, 0.85f to 0.06f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.STAGE_SCALE,
                TrackEasing.IN_OUT,
                0f to 0f, 0.5f to 0.22f, 0.85f to 0.02f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BODY_X,
                TrackEasing.IN_OUT,
                0f to 0f, 0.3f to 0.5f, 0.7f to 0.5f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.HEAD_Y,
                TrackEasing.IN_OUT,
                0f to 0f, 0.4f to 0.3f, 0.8f to -0.1f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.ARM_L,
                TrackEasing.IN_OUT,
                0f to 0f, 0.25f to 0.9f, 0.8f to 0.9f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.ARM_R,
                TrackEasing.IN_OUT,
                0f to 0f, 0.25f to 0.9f, 0.8f to 0.9f, 1f to 0f,
            ),
            ParameterTrack.of(
                GenericChannel.BROW,
                TrackEasing.IN_OUT,
                0f to 0f, 0.3f to 0.6f, 0.8f to 0.6f, 1f to 0f,
            ),
        ),
    )

    /**
     * 全部内置动作，**库里那一档列表的固定顺序**：
     * 四态默认在前（用户最先要对齐的就是它们），趣味动作跟在后面（按 [funKeys] 排）。
     */
    fun all(): List<Live2dAction> =
        ActionSlot.entries.map { stateDefault(it) } + listOf(wave(), shake(), split(), flip())

    private val byKey: Map<String, Live2dAction> by lazy {
        all().associateBy { it.builtinKey }
    }

    fun byKey(key: String?): Live2dAction? = byKey[key.orEmpty()]

    /** 趣味动作那一档（"动作库"里按 [funKeys] 顺序排） */
    fun funActions(): List<Live2dAction> = listOf(wave(), shake(), split(), flip())

    /**
     * 点击互动用的"随机趣味动作"池：内置趣味动作。
     * 用户自建的动作用户可在互动设置里另指（本版用默认，见 InteractionBindings）。
     */
    fun randomPool(): List<Live2dAction> = funActions()
}
