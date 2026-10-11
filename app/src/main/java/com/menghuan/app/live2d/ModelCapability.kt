package com.menghuan.app.live2d

/**
 * 一张模型"能做什么动作"的能力表（v0.64.0，纯函数，JVM 单测）。
 *
 * 用户第 5 / 第 11.16 条要的是这个：**不同模型参数不一样，缺参数不许崩、
 * 要跳过缺的部分、保留其余部分、必要时降低效果**。这里就是那四条规则的唯一落点。
 *
 * 探测完全不碰 Android API：输入只是一个"这个参数 ID 在模型里存不存在"的判定函数
 * （实机上是 `CubismModel.getParameterIndex(id) >= 0`），所以能在 JVM 上打表测。
 */
data class ModelCapability(
    /** 通道 → 这个模型里真实命中的参数 ID（可能多个：左右眼、左右眉）。可空集 = 不支持 */
    val available: Map<GenericChannel, List<String>> = emptyMap(),
) {

    /**
     * 这个通道能不能写。
     *
     * ⚠️ 舞台通道**恒为 true**：它们走渲染矩阵，不需要模型有任何参数。
     * 这一条是"翻跟斗在任何模型上都演得出来"的原因。
     */
    fun supports(channel: GenericChannel): Boolean =
        channel.isStage || !available[channel].isNullOrEmpty()

    /** 参数类通道总数（界面上说"这张模型支持 18 项动作能力"用得到） */
    val paramChannelCount: Int get() = available.count { !it.key.isStage && it.value.isNotEmpty() }

    fun missing(required: Collection<GenericChannel>): Set<GenericChannel> =
        required.filter { !supports(it) }.toSet()

    /**
     * 必需通道的满足度（0..1）。舞台通道不计入分子分母 ——
     * 它们必然满足，算进去等于给所有模型白送分，掩盖真正缺的东西。
     */
    fun confidence(required: Collection<GenericChannel>): Float {
        val judge = required.filter { !it.isStage }
        if (judge.isEmpty()) return 1f
        return judge.count { supports(it) }.toFloat() / judge.size
    }

    /**
     * 缺通道时压低幅度。
     *
     * 为什么不直接"能跑就跑"：一条动作缺了手臂、缺了身体，只剩下头在动的话，
     * 观感是"这个人坏掉了"而不是"这个人没做那个动作"。压低幅度 + 保留能跑的部分，
     * 读起来才像"幅度小一点的同类动作"。下限 0.55 是有意的：再低就等于没在动，
     * 那种情况下调用方应该直接选别的动作（见 [canRun]）。
     */
    fun amplitudeScale(required: Collection<GenericChannel>): Float {
        val c = confidence(required)
        return 0.55f + 0.45f * c
    }

    /**
     * [amplitudeScale] 的重载：直接对一条动作的兼容性声明算。
     *
     * 有这个重载是因为调用点大多手上只有"一条动作"，每次写 `.compatibility.required`
     * 既啰嗦又容易漏（漏了就变成"空集合 → 永不降级"，是个不会报错的错）。
     */
    fun amplitudeScale(compatibility: ModelCompatibility): Float =
        amplitudeScale(compatibility.required)

    /**
     * 整条动作跑不跑得起来。
     *
     * 判定用**动作声明的 required**（不是"所有轨道"）：摆手要求"至少有一条手臂"，
     * 劈叉要求"身体侧倾"，这些作者在定义动作时就写清了。
     * required 全缺才判不能跑 —— 这时候调用方要回退到别的动作（不是播一半）。
     */
    fun canRun(compatibility: ModelCompatibility): Boolean =
        missing(compatibility.required).isEmpty()

    /**
     * 这条轨道值最终该乘多少。
     *
     * 三层相乘，含义各自独立，别合并：
     * · [GenericChannel] 里不需要系数 —— 归一化值本身就是"模型可用范围的一个比例"
     *  （那个比例是 [ActionPlayer.AMPLITUDE_SPAN]，见 TrackKey）；
     * · 动作的强度（用户调的那个滑块）；
     * · 模型能力系数（缺通道时的"降效果"）。
     */
    fun applyScale(action: Live2dAction): Float =
        action.safeAmplitude * amplitudeScale(action.compatibility)

    companion object {

        /** 空能力表：什么参数都没有的模型（只有舞台通道可用） */
        val EMPTY = ModelCapability()

        /**
         * 探测：对每个通道的候选参数名逐个问"在不在"。
         *
         * `exists` 由调用方提供（实机上是查模型参数下标），**同一个 ID 会被问多次**
         * （比如 `ParamMouthOpenY` 只属于一个通道，但 `ParamBrowLY` 同时出现在
         * GENERIC_BROW 里）—— 调用方自己决定要不要缓存，这里不做假设。
         */
        fun detect(exists: (String) -> Boolean): ModelCapability {
            val map = LinkedHashMap<GenericChannel, List<String>>()
            GenericChannel.paramChannels.forEach { ch ->
                val hit = ch.paramIds.filter { runCatching { exists(it) }.getOrDefault(false) }
                if (hit.isNotEmpty()) map[ch] = hit
            }
            return ModelCapability(map)
        }
    }
}
