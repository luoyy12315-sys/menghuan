package com.menghuan.app.live2d

/**
 * 通用动作通道（v0.64.0，纯函数，JVM 单测）。
 *
 * 存在的理由：**动作不能写死在某一个模型上**。
 *
 * Live2D 的动作文件（`.motion3.json`）里记的是**那张模型自己的参数 ID**，
 * 换一张模型就整段失效 —— 所以"摆手 / 劈叉 / 翻跟斗"这类通用动作
 * 绝不能做成 motion 文件（VTube Studio 的热键动作同理，它存的是参数值组合）。
 *
 * 这里的做法是在两者之间插一层**通道**：
 *
 *   动作轨道只认 [GenericChannel]（GENERIC_HEAD_X 这种）
 *        ↓ 加载模型时探测
 *   通道 → 该模型里真实存在的参数 ID（可能是 ParamAngleX，也可能是别的写法）
 *        ↓ 每帧写入
 *   模型参数（不存在就跳过这一条轨道，其余照常跑）
 *
 * ⚠️ 另一条关键设计：**舞台通道（[ChannelKind.STAGE]）不依赖任何参数**。
 * 翻跟斗要的"整体转一圈 + 跳起来再落下"，如果只能靠 `ParamBodyAngleY` 这类参数，
 * 遇到没有这套参数的模型就只剩原地抖一下。舞台通道由渲染矩阵直接实现，**任何模型都能演**，
 * 所以三个趣味动作在什么模型上都看得出在干什么。
 */
enum class ChannelKind {
    /** 模型参数：模型里真有才能写，缺了自动跳过（其余轨道不受影响） */
    PARAM,

    /**
     * 舞台变换：由渲染矩阵实现，**零参数依赖**。
     * 归一化值 1.0 的含义见 [GenericChannel.stageUnit]。
     */
    STAGE,
}

/** 通道归类：只用于「高级模式」里给参数分组显示，不影响动作语义 */
enum class ChannelGroup(val label: String) {
    HEAD("头部"),
    BODY("身体"),
    ARM("手臂"),
    EYE("眼睛"),
    MOUTH("嘴"),
    BROW("眉毛"),
    FACE("表情"),
    BREATH("呼吸"),
    STAGE("整体"),
}

/**
 * @param id 落盘用的稳定键，**改动即等于破坏已存的动作**（用户自定义动作与 AI 生成动作都靠它）
 * @param paramIds 同类模型的常见参数名，按优先级排列；**第一个在模型里存在的就命中**。
 *   正常模型用标准 ID 即可，列多个是为了兼容老式命名（`PARAM_ANGLE_X`）与个别导出差异。
 * @param stageUnit 舞台通道的换算：归一化 1.0 对应多少（像素比例 / 度数），见各处使用点
 */
enum class GenericChannel(
    val id: String,
    val label: String,
    val group: ChannelGroup,
    val kind: ChannelKind,
    val paramIds: List<String> = emptyList(),
    val stageUnit: Float = 0f,
) {
    // ---- 头部 ----
    HEAD_X("GENERIC_HEAD_X", "左右转头", ChannelGroup.HEAD, ChannelKind.PARAM, listOf("ParamAngleX", "PARAM_ANGLE_X")),
    HEAD_Y("GENERIC_HEAD_Y", "上下点头", ChannelGroup.HEAD, ChannelKind.PARAM, listOf("ParamAngleY", "PARAM_ANGLE_Y")),
    HEAD_Z("GENERIC_HEAD_Z", "左右歪头", ChannelGroup.HEAD, ChannelKind.PARAM, listOf("ParamAngleZ", "PARAM_ANGLE_Z")),

    // ---- 身体 ----
    BODY_X("GENERIC_BODY_X", "身体左右转", ChannelGroup.BODY, ChannelKind.PARAM, listOf("ParamBodyAngleX", "PARAM_BODY_ANGLE_X")),
    BODY_Y("GENERIC_BODY_Y", "身体前后倾", ChannelGroup.BODY, ChannelKind.PARAM, listOf("ParamBodyAngleY", "PARAM_BODY_ANGLE_Y")),
    BODY_Z("GENERIC_BODY_Z", "身体侧倾", ChannelGroup.BODY, ChannelKind.PARAM, listOf("ParamBodyAngleZ", "PARAM_BODY_ANGLE_Z")),

    // ---- 手臂 / 手（摆手、劈叉要用；缺了并不会让动作崩，只是少了手臂那一层） ----
    ARM_L("GENERIC_ARM_L", "左臂", ChannelGroup.ARM, ChannelKind.PARAM, listOf("ParamArmLA", "ParamArmLB")),
    ARM_R("GENERIC_ARM_R", "右臂", ChannelGroup.ARM, ChannelKind.PARAM, listOf("ParamArmRA", "ParamArmRB")),
    HAND_L("GENERIC_HAND_L", "左手", ChannelGroup.ARM, ChannelKind.PARAM, listOf("ParamHandL")),
    HAND_R("GENERIC_HAND_R", "右手", ChannelGroup.ARM, ChannelKind.PARAM, listOf("ParamHandR")),

    // ---- 眼 ----
    EYE("GENERIC_EYE", "睁眼", ChannelGroup.EYE, ChannelKind.PARAM, listOf("ParamEyeLOpen", "ParamEyeROpen")),
    EYE_BALL_X("GENERIC_EYE_BALL_X", "视线左右", ChannelGroup.EYE, ChannelKind.PARAM, listOf("ParamEyeBallX")),
    EYE_BALL_Y("GENERIC_EYE_BALL_Y", "视线上下", ChannelGroup.EYE, ChannelKind.PARAM, listOf("ParamEyeBallY")),

    // ---- 嘴（只碰"嘴型"，不碰"张嘴"——张嘴归实时语音的口型，见 ActionPlayer 的说明） ----
    MOUTH("GENERIC_MOUTH", "张嘴", ChannelGroup.MOUTH, ChannelKind.PARAM, listOf("ParamMouthOpenY")),
    MOUTH_FORM("GENERIC_MOUTH_FORM", "嘴型", ChannelGroup.MOUTH, ChannelKind.PARAM, listOf("ParamMouthForm")),

    // ---- 眉 ----
    BROW("GENERIC_BROW", "眉毛上下", ChannelGroup.BROW, ChannelKind.PARAM, listOf("ParamBrowLY", "ParamBrowRY")),
    BROW_FORM("GENERIC_BROW_FORM", "眉毛形状", ChannelGroup.BROW, ChannelKind.PARAM, listOf("ParamBrowLForm", "ParamBrowRForm")),

    // ---- 脸 ----
    CHEEK("GENERIC_CHEEK", "脸红", ChannelGroup.FACE, ChannelKind.PARAM, listOf("ParamCheek")),
    BREATH("GENERIC_BREATH", "呼吸", ChannelGroup.BREATH, ChannelKind.PARAM, listOf("ParamBreath")),
    BUST_X("GENERIC_BUST_X", "胸部左右", ChannelGroup.BODY, ChannelKind.PARAM, listOf("ParamBustX")),
    BUST_Y("GENERIC_BUST_Y", "胸部上下", ChannelGroup.BODY, ChannelKind.PARAM, listOf("ParamBustY")),

    // ---- 头发（甩头发一类的动作要用） ----
    HAIR_FRONT("GENERIC_HAIR_FRONT", "前发", ChannelGroup.FACE, ChannelKind.PARAM, listOf("ParamHairFront")),
    HAIR_SIDE("GENERIC_HAIR_SIDE", "侧发", ChannelGroup.FACE, ChannelKind.PARAM, listOf("ParamHairSide")),
    HAIR_BACK("GENERIC_HAIR_BACK", "后发", ChannelGroup.FACE, ChannelKind.PARAM, listOf("ParamHairBack")),

    // ---- 舞台：渲染矩阵实现，任何模型都能跑（翻跟斗 / 跳起 / 整体摇摆靠它） ----
    /** 归一化 1.0 = 画布宽度的 30% */
    STAGE_X("GENERIC_STAGE_X", "整体左右位移", ChannelGroup.STAGE, ChannelKind.STAGE, stageUnit = 0.30f),
    /** 归一化 1.0 = 画布高度的 30% */
    STAGE_Y("GENERIC_STAGE_Y", "整体上下位移", ChannelGroup.STAGE, ChannelKind.STAGE, stageUnit = 0.30f),
    /** 归一化 1.0 = 180°，所以"整个翻一圈"写 2.0（允许超出 ±1，动作求值不夹这个范围） */
    STAGE_ROT("GENERIC_STAGE_ROT", "整体旋转", ChannelGroup.STAGE, ChannelKind.STAGE, stageUnit = 180f),
    /** 归一化 1.0 = 放大 20%（跳起时的远小近大） */
    STAGE_SCALE("GENERIC_STAGE_SCALE", "整体缩放", ChannelGroup.STAGE, ChannelKind.STAGE, stageUnit = 0.20f),
    ;

    val isStage: Boolean get() = kind == ChannelKind.STAGE

    companion object {
        private val byId = entries.associateBy { it.id }

        /** 落盘 id → 通道；认不出来返回 null（老动作里删掉的通道不该让整条动作读不出来） */
        fun byId(id: String?): GenericChannel? = byId[id.orEmpty()]

        /** 参数类通道（舞台通道不在其中） */
        val paramChannels: List<GenericChannel> = entries.filter { it.kind == ChannelKind.PARAM }

        /** 舞台通道 */
        val stageChannels: List<GenericChannel> = entries.filter { it.kind == ChannelKind.STAGE }
    }
}
