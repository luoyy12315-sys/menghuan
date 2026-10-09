package com.menghuan.app.data

/**
 * 聊天外观（v0.80.0 推倒重做，用户点名「聊天气泡和头像功能推倒重来」）。
 *
 * ## v0.77 → v0.80 变了什么
 * 旧版是「六档预设色卡 + 三档字号」；新版是**色盘自由配色**：
 * - 气泡按**明暗主题各存一套**（[light] / [dark]，用户拍板），每套里「自己 / 对方」各一份
 *   [BubbleStyle]（背景色 / 字色 / 边框色 / 圆角）；
 * - 字体**全局一套**（不分明暗）：字体族 / 字号（连续可调）/ 字重；
 * - 是否显示头像沿用 [showAvatar]。
 *
 * ## 颜色为什么存成 ARGB Long、0L 是什么意思
 * 设置仓不 import Compose 的 Color，纯 JVM 单测才能覆盖（全项目纪律）。`0L`（全透明）是
 * **「跟主题」的哨兵**：用户在色盘上选不出纯透明色，所以它天然不会撞值 ——
 * 背景为 0L = 用主题令牌（自己=强调色、对方=表面色），字色为 0L = **按背景亮度自动挑黑/白**，
 * 边框为 0L = 无边框。这就是「明暗主题下保证可读性」的保底：不动色盘的人永远拿到主题配色，
 * 动了背景但没动字色的人拿到自动对比色，只有四个都自己选的人才对自己的选择负责。
 */
data class ChatAppearance(
    val light: BubbleTheme = BubbleTheme(),
    val dark: BubbleTheme = BubbleTheme(),
    val fontFamily: FontFamilyKey = FontFamilyKey.SANS,
    val fontSizeSp: Int = DEFAULT_FONT_SP,
    val fontWeightKey: FontWeightKey = FontWeightKey.NORMAL,
    val showAvatar: Boolean = true,
) {
    /** 一个明暗主题下的一对气泡（自己 / 对方） */
    data class BubbleTheme(
        val mine: BubbleStyle = BubbleStyle(),
        val other: BubbleStyle = BubbleStyle(),
    )

    /**
     * 一方气泡的全部外观。
     *
     * [radiusDp] 用 -1 当「默认」哨兵（0 是合法值 = 直角，不能拿它当哨兵）。
     */
    data class BubbleStyle(
        val bg: Long = 0L,
        val ink: Long = 0L,
        val border: Long = 0L,
        val radiusDp: Int = NO_RADIUS,
    ) {
        /** 编码成 `bg|ink|border|radius` 一行，落 DataStore（见 SettingsRepository 的四颗键） */
        fun encode(): String = "$bg|$ink|$border|$radiusDp"

        companion object {
            const val NO_RADIUS = -1
            const val DEFAULT_RADIUS_DP = 18
            val DEFAULT = BubbleStyle()

            /** 认不出的整段回默认（老数据 / 手改配置都别崩） */
            fun decode(raw: String?): BubbleStyle {
                if (raw.isNullOrBlank()) return DEFAULT
                val p = raw.split('|')
                if (p.size < 4) return DEFAULT
                return BubbleStyle(
                    bg = p[0].toLongOrNull() ?: 0L,
                    ink = p[1].toLongOrNull() ?: 0L,
                    border = p[2].toLongOrNull() ?: 0L,
                    radiusDp = p[3].toIntOrNull() ?: NO_RADIUS,
                )
            }
        }
    }

    /**
     * 字体族（v0.80.0）。不引入字体文件，只挑系统里现成的三族 ——
     * 引一套 ttf 就是几 MB 的包体，为一个选项不值。
     */
    enum class FontFamilyKey(val key: String, val label: String) {
        SANS("sans", "默认"),
        SERIF("serif", "衬线"),
        MONO("mono", "等宽");

        companion object {
            fun fromKey(raw: String?): FontFamilyKey =
                entries.firstOrNull { it.key == raw } ?: SANS
        }
    }

    /** 字重四档，[weight] 对齐 androidx.compose.ui.text.font.FontWeight 的数值 */
    enum class FontWeightKey(val key: String, val label: String, val weight: Int) {
        NORMAL("normal", "常规", 400),
        MEDIUM("medium", "中等", 500),
        SEMIBOLD("semibold", "半粗", 600),
        BOLD("bold", "粗体", 700);

        companion object {
            fun fromKey(raw: String?): FontWeightKey =
                entries.firstOrNull { it.key == raw } ?: NORMAL
        }
    }

    companion object {
        const val MIN_FONT_SP = 12
        const val MAX_FONT_SP = 22
        const val DEFAULT_FONT_SP = 15
        const val MIN_RADIUS_DP = 0
        const val MAX_RADIUS_DP = 28

        /**
         * 按 ARGB 的相对亮度自动挑字色：亮底配近黑字、暗底配近白字（纯函数，可测）。
         * 只在「背景自定义了、字色没自定义」时被 UI 层调用。
         */
        fun autoInkFor(bg: Long): Long {
            val r = (bg shr 16) and 0xFFL
            val g = (bg shr 8) and 0xFFL
            val b = bg and 0xFFL
            // Rec.709 感知亮度（与 Compose luminance() 同一套系数）
            val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
            return if (lum > 0.55) 0xFF111111L else 0xFFFCFCFCL
        }
    }
}

/**
 * 我自己的档案（v0.80.0）：昵称 + 一句话简介，展示在「我的」页顶部的档案卡与个人档案页。
 *
 * 与 `ProfileFact`（基础档案那张 key/value 表）是**两回事**：那张是"AI 读我的画像"，
 * 这份是"我在这个 App 里叫什么、写句什么话"（微信「我的」页顶部那张卡的同款东西）。
 * 两个字段都允许空 —— UI 走兜底文案（昵称空显示「我」、简介空显示"还没写"），
 * **不画空行也不画占位方框**（SessionList 那条「未加载 vs 空 vs 有数据」的纪律）。
 */
data class MyProfile(
    val nickname: String = "",
    val bio: String = "",
) {
    /** 昵称兜底：没填过就叫「我」，不至于让名字那行是空的 */
    val displayNickname: String get() = nickname.trim().ifBlank { "我" }

    val hasAny: Boolean get() = nickname.isNotBlank() || bio.isNotBlank()
}

/**
 * 用户自己的头像（v0.77.0 建，v0.80.0 起归「我的页 → 个人档案」管）。
 * emoji 与图片二选一，**图片优先**（与 Persona 的同一套优先级口径）。
 */
data class UserAvatar(
    val emoji: String = "",
    val imagePath: String = "",
) {
    /** 有没有设置过（两个都空 = 用兜底的「我」字圆，不占位也不难看） */
    val isEmpty: Boolean get() = emoji.isBlank() && imagePath.isBlank()

    /** 图片优先；含 emoji 时用它（UI 层 NeuAvatar 二选一渲染） */
    val effectiveEmoji: String get() = emoji
}
