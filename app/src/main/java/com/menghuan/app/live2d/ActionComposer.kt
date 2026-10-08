package com.menghuan.app.live2d

/**
 * 动作的"零件"分类（v0.64.0）。
 *
 * 这是给**普通用户**看的粒度：他们不该面对 GENERIC_HEAD_X 这种名字（用户第 4 条），
 * 只需要回答"这个动作要不要动手臂"。所以四个开关对应 [GenericChannel] 的九个分组：
 * 头部（含眉眼嘴表情）、身体、手臂、整体位移。
 */
enum class ActionPart(val label: String) {
    HEAD("头部"),
    BODY("身体"),
    ARM("手臂"),
    STAGE("整体位移"),
    ;

    companion object {
        fun of(channel: GenericChannel): ActionPart = when (channel.group) {
            ChannelGroup.HEAD,
            ChannelGroup.EYE,
            ChannelGroup.MOUTH,
            ChannelGroup.BROW,
            ChannelGroup.FACE,
            -> HEAD

            ChannelGroup.BODY,
            ChannelGroup.BREATH,
            -> BODY

            ChannelGroup.ARM -> ARM
            ChannelGroup.STAGE -> STAGE
        }
    }
}

/**
 * 动作拼装器（v0.64.0，纯函数，JVM 单测）。
 *
 * 支撑「新建动作」的**普通模式**（用户拍板第一阶段只做这一层）：
 * 用户面对的是「名字 + 基础动作 + 强度 + 速度 + 时长 + 四个部位开关 + 表情」，
 * **全程不出现任何参数名**。参数级的编辑留给以后的高级模式。
 *
 * 两种来源都汇聚到同一个出口：
 * - 选了基础动作 → [filterTracks] 按部位开关**筛**它的轨道；
 * - 没选基础动作 → [defaultTracks] 按部位开关**造**一套标准轨道。
 *
 * 这样"开关"在两种情况下含义一致（"这个动作要不要动这部分"），
 * 用户不必理解"我现在是在加还是在减"。
 */
object ActionComposer {

    /**
     * 从零造一套轨道。幅度刻意保守（0.25–0.35）：
     * 用户点"头部"要的是"动一下头"，不是"把头甩到极限"。
     * 想更大就调强度滑块 —— 那才是"调幅度"的正确入口。
     */
    fun defaultTracks(parts: Set<ActionPart>): List<ParameterTrack> {
        val out = ArrayList<ParameterTrack>()
        if (ActionPart.HEAD in parts) {
            out.add(
                ParameterTrack.of(
                    GenericChannel.HEAD_Z,
                    TrackEasing.IN_OUT,
                    0f to 0f, 0.35f to 0.35f, 0.7f to -0.3f, 1f to 0f,
                ),
            )
            out.add(
                ParameterTrack.of(
                    GenericChannel.HEAD_Y,
                    TrackEasing.IN_OUT,
                    0f to 0f, 0.5f to 0.25f, 1f to 0f,
                ),
            )
        }
        if (ActionPart.BODY in parts) {
            out.add(
                ParameterTrack.of(
                    GenericChannel.BODY_Z,
                    TrackEasing.IN_OUT,
                    0f to 0f, 0.35f to 0.3f, 0.7f to -0.28f, 1f to 0f,
                ),
            )
            out.add(
                ParameterTrack.of(
                    GenericChannel.BODY_X,
                    TrackEasing.IN_OUT,
                    0f to 0f, 0.5f to 0.18f, 1f to 0f,
                ),
            )
        }
        if (ActionPart.ARM in parts) {
            out.add(
                ParameterTrack.of(
                    GenericChannel.ARM_R,
                    TrackEasing.IN_OUT,
                    0f to 0f, 0.3f to 0.7f, 0.7f to 0.7f, 1f to 0f,
                ),
            )
            out.add(
                ParameterTrack.of(
                    GenericChannel.ARM_L,
                    TrackEasing.IN_OUT,
                    0f to 0f, 0.3f to 0.7f, 0.7f to 0.7f, 1f to 0f,
                ),
            )
        }
        if (ActionPart.STAGE in parts) {
            out.add(
                ParameterTrack.of(
                    GenericChannel.STAGE_Y,
                    TrackEasing.IN_OUT,
                    0f to 0f, 0.5f to 0.15f, 1f to 0f,
                ),
            )
        }
        return out
    }

    /** 按部位开关筛基础动作的轨道（关掉的那部分整条丢掉，不是把幅度调成 0） */
    fun filterTracks(tracks: List<ParameterTrack>, parts: Set<ActionPart>): List<ParameterTrack> =
        tracks.filter { ActionPart.of(it.channel) in parts }

    /**
     * 拼装并落成一条完整动作。
     *
     * [base] 非空时沿用它的轨道与兼容性声明（**只换幅度/速度/时长/表情**），
     * 空时按部位开关现造一套。
     *
     * ⚠️ 结果是"我的动作"（[ActionSource.USER]），**id 一律清 0**：
     * 哪怕是基于内置动作改的，也只能另存成一条新动作，绝不原地改内置那条 ——
     * 内置动作是四态绑定的兜底，被用户改坏了就没有"默认"可回了。
     */
    fun compose(
        base: Live2dAction?,
        name: String,
        amplitude: Float,
        speed: Float,
        durationMs: Int,
        parts: Set<ActionPart>,
        expression: String,
        now: Long = System.currentTimeMillis(),
    ): Live2dAction {
        val tracks = if (base != null && base.hasTracks) {
            filterTracks(base.tracks, parts)
        } else {
            defaultTracks(parts)
        }
        return Live2dAction(
            id = 0L,
            name = name.trim().ifBlank { "我的动作" }.take(12),
            type = ActionType.PROCEDURAL,
            source = ActionSource.USER,
            builtinKey = "",
            durationMs = durationMs.coerceIn(Live2dAction.MIN_DURATION_MS, Live2dAction.MAX_DURATION_MS),
            // 从零拼出来的都是"做一次"的动作；跟着基础动作来的时候保留它的循环设置
            loop = base?.loop ?: false,
            fadeInMs = base?.safeFadeInMs ?: 220,
            fadeOutMs = base?.safeFadeOutMs ?: 300,
            tracks = tracks,
            expression = expression,
            amplitude = amplitude,
            speed = speed,
            compatibility = base?.compatibility ?: ModelCompatibility.NONE,
            favorite = false,
            personaId = 0L,
            note = if (base != null) "基于「${base.name}」调整" else "",
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 哪些部位是这个基础动作真正用到的（编辑页据此把开关置成初始值） */
    fun partsOf(action: Live2dAction?): Set<ActionPart> =
        action?.tracks?.map { ActionPart.of(it.channel) }?.toSet() ?: ActionPart.entries.toSet()
}
