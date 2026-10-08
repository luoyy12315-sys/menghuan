package com.menghuan.app.live2d

/**
 * 通话时的**动作幅度档位**（v0.67.0，纯函数，JVM 单测）。
 *
 * ## 为什么是四档而不是一个滑块
 *
 * 用户已经能在动作编辑器里调每一条动作的"强度"。这里要回答的是另一个问题：
 * "这个角色整体动得太小 / 太大了" —— 那是一次性的、按角色定的偏好。
 * 做成滑块会出现两个都要理解的东西（强度 × 幅度 = ？），而普通人只想要一个"大一点"。
 * 四档的措辞按"看得出来的差别"来定：小 / 标准 / 大 / 特大，相邻两档至少差三成，
 * 一眼能分辨，又不会一档没动、一档扯变形。
 *
 * ## 为什么缺省是 1.0 而不是"明显的更大"
 *
 * 四态默认动作本身的幅度在 v0.67.0 已经整体提上来了（见 [BuiltinActions] 文件头）。
 * 这一档是**给单个角色微调用的第二层**，不是把上一层的保守再翻一遍；
 * 缺省 1.0 才能保证"没调过档的用户看到的就是我们调过的那一版"。
 *
 * ⚠️ 倍率最终仍会被 [Live2dAction.scaledBy] 夹回 `MAX_AMPLITUDE` 以内 ——
 * 那道夹子是"任何模型都不该被甩到破图"的保险，档位不许绕过它。
 */
enum class ActionScale(val key: String, val label: String, val multiplier: Float) {
    SMALL("small", "小", 0.7f),
    STANDARD("standard", "标准", 1.0f),
    LARGE("large", "大", 1.4f),
    XLARGE("xlarge", "特大", 1.8f),
    ;

    companion object {
        /** 认不出来 = 标准档（脏数据不该让角色突然不动，也不该突然乱动） */
        fun fromKey(raw: String?): ActionScale = entries.firstOrNull { it.key == raw } ?: STANDARD
    }
}
