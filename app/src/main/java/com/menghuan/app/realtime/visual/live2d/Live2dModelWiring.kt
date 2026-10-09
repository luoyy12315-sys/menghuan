package com.menghuan.app.realtime.visual.live2d

import com.live2d.sdk.cubism.framework.CubismDefaultParameterId
import com.live2d.sdk.cubism.framework.CubismModelSettingJson
import com.live2d.sdk.cubism.framework.effect.CubismBreath
import com.live2d.sdk.cubism.framework.effect.CubismEyeBlink
import com.live2d.sdk.cubism.framework.id.CubismId
import com.live2d.sdk.cubism.framework.model.CubismModel
import com.live2d.sdk.cubism.framework.motion.ACubismMotion
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion
import com.live2d.sdk.cubism.framework.motion.CubismMotion
import com.menghuan.app.live2d.InteractionRules
import com.menghuan.app.live2d.ModelActionCatalog
import com.menghuan.app.live2d.ModelCapability

/**
 * 一张模型的**参数接线**（v0.82.0 P2-3 从 `Live2dCharacter` 拆出来）。
 *
 * 所谓"接线"，指的就是加载模型时那条"`model3.json` 清单 → Cubism 框架"的链条：
 * 眨眼用到哪些参数、口型用到哪些参数、有哪些动作组与表情，各自挂到框架的
 * `CubismEyeBlink` / `CubismLipSyncUpdater` / `motionManager` 上。
 *
 * 搬过来的是**代码本身，一行不改**：判定顺序、回退条件、以及哪一步失败算整张降级
 * 都照原样（这也是为什么下面几处看着"啰嗦"的写法都留着 —— 它们各自对应一个真机坑）。
 *
 * 真正需要 `CubismUserModel` **受保护成员**的动作（`loadMotion` / `loadExpression` /
 * `getParameterIndex`）由角色以 lambda 转手进来 —— 老规矩：受保护的调用一律留在角色内部。
 */
internal object Live2dModelWiring {

    /** 一次接线的全部产物 */
    class Loaded(
        /** key = `组名_序号`（同官方） */
        val motions: Map<String, ACubismMotion>,
        /** 这张模型有哪些动作组（顺序 = `model3.json` 里的顺序） */
        val motionGroups: List<String>,
        /** 组名 → 该组真的加载成功了几个（catalog 用） */
        val groupCounts: Map<String, Int>,
        /** 表情名 → 表情动作（`.exp3.json`） */
        val expressions: Map<String, CubismExpressionMotion>,
        /**
         * 眨眼参数（空 = 这张模型眨不了眼）。已带"清单空 → 标准参数"的回退。
         *
         * ⚠️ 只有 `CubismEyeBlink` 吃这一份；原生动作 `setEffectIds` 吃的是
         * [manifestBlinkIds]（原始清单、不回退）。两处不一致是**原样搬过来的**，
         * 看着像漏改，但改了就是改行为 —— 不属于"纯拆分"该动的东西。
         */
        val blinkIds: List<CubismId>,
        /** 口型参数（空 = 张不了嘴） */
        val lipSyncIds: List<CubismId>,
        /** 眨眼效果（参数为 null 时 = 框架拒收这张清单） */
        val eyeBlink: CubismEyeBlink?,
        /** 呼吸效果（它自身不依赖任何参数，所以无条件挂着） */
        val breath: CubismBreath,
        /** 模型自带 HitArea 的 `Head` CubismId；null = 没写 */
        val headHitId: CubismId?,
        /** 模型自带 HitArea 的 `Body` CubismId；null = 没写 */
        val bodyHitId: CubismId?,
        /** 这张模型有哪些通用通道 */
        val capability: ModelCapability,
        /** 对外同步进动作库的清单 */
        val catalog: ModelActionCatalog,
    )

    /** 一组动作加载完的样子（catalog 只需要"每组几个"，不关心具体条目） */
    class Motions(
        val byKey: Map<String, ACubismMotion>,
        val groups: List<String>,
        val counts: Map<String, Int>,
    )

    /** 一组表情加载完的样子（`names` 的顺序 = 加载成功的顺序，catalog 要用） */
    class Expressions(
        val byName: Map<String, CubismExpressionMotion>,
        val names: List<String>,
    )

    /** 模型自带 HitArea 的两个 id（都 null = 走几何兜底） */
    data class HitAreas(val head: CubismId?, val body: CubismId?)

    /**
     * 把这张模型的清单解析好（GL 线程，在 `loadAssets` 里调一次）。
     *
     * @param stdId 标准参数 enum → CubismId（框架的 IdManager）
     * @param paramIndex 参数 id → 下标（<0 = 这张模型没有这个参数）
     * @param loadMotion / [loadExpression] `CubismUserModel` 里的受保护方法，由角色转手
     */
    fun wire(
        modelJsonRelPath: String,
        setting: CubismModelSettingJson,
        model: CubismModel,
        readRef: (String) -> ByteArray,
        stdId: (CubismDefaultParameterId.ParameterId) -> CubismId,
        /** 参数**名字** → 下标（<0 = 这张模型没有这个参数） */
        paramIndex: (String) -> Int,
        loadMotion: (ByteArray) -> CubismMotion?,
        loadExpression: (ByteArray) -> CubismExpressionMotion?,
    ): Loaded {
        val blinkIds = blinkIds(setting, stdId, paramIndex)
        val lipSyncIds = lipSyncIds(setting, stdId, paramIndex)
        // 原生动作吃的眨眼清单 = 原始清单（不回退），见 [Loaded.motionBlinkIds]
        val motions = loadMotions(setting, readRef, loadMotion, manifestBlinkIds(setting), lipSyncIds)
        val expressions = loadExpressions(setting, readRef, loadExpression)
        val hit = hitAreas(setting)
        return Loaded(
            motions = motions.byKey,
            motionGroups = motions.groups,
            groupCounts = motions.counts,
            expressions = expressions.byName,
            blinkIds = blinkIds,
            lipSyncIds = lipSyncIds,
            eyeBlink = CubismEyeBlink.create(setting),
            breath = breath(stdId),
            headHitId = hit.head,
            bodyHitId = hit.body,
            capability = ModelCapability.detect { name -> paramIndex(name) >= 0 },
            catalog = ModelActionCatalog(
                modelKey = modelJsonRelPath,
                motionGroups = motions.counts,
                expressions = expressions.names,
            ),
        )
    }

    /**
     * 眨眼用哪些参数（**回退版**：喂给 `CubismEyeBlink`）。
     *
     * 正规做法是读 `model3.json` 的 `Groups` 里 `EyeBlink` 那一段，
     * 但**大量免费模型这里是空的** —— 真机用的「奶蛙」包就是 `Groups: [{EyeBlink: []}]`
     * 连 LipSync 段都没有、也没有 Motions，全靠 Cubism 的标准参数名工作（VTube 系都这样）。
     *
     * 只按清单走的话结果不是报错，而是**角色一动不动 / 嘴不张**（最难查的那种失败），
     * 所以清单空就回退标准参数 id，并且**只认这个模型里真存在的那个**：
     * 指一个不存在的参数，框架不会吭声，只会白跑。
     *
     * ⚠️ 这份回退出来的清单必须再喂给眨眼效果一次：框架 `create(setting)` 时自己读的是
     * **原始清单**，清单为空时它一个参数都不认，眨眼整条链子就断了。
     */
    fun blinkIds(
        setting: CubismModelSettingJson,
        stdId: (CubismDefaultParameterId.ParameterId) -> CubismId,
        paramIndex: (String) -> Int,
    ): List<CubismId> {
        val ids = ArrayList<CubismId>()
        ids.addAll(manifestBlinkIds(setting))
        if (ids.isEmpty()) {
            listOf(
                stdId(CubismDefaultParameterId.ParameterId.EYE_L_OPEN),
                stdId(CubismDefaultParameterId.ParameterId.EYE_R_OPEN),
            ).filterTo(ids) { paramIndex(it.string) >= 0 }
        }
        return ids
    }

    /**
     * `model3.json` 里 `Groups → EyeBlink` 那段写的原始参数（**不回退**）。
     *
     * 只有原生动作的 `setEffectIds` 吃这一份，见 [Loaded.motionBlinkIds]。
     */
    fun manifestBlinkIds(setting: CubismModelSettingJson): List<CubismId> {
        val out = ArrayList<CubismId>()
        for (i in 0 until setting.eyeBlinkParameterCount) {
            setting.getEyeBlinkParameterId(i)?.let { out.add(it) }
        }
        return out
    }

    /** 口型用哪些参数（与 [blinkIds] 同一条"清单空就回退标准参数"的纪律） */
    fun lipSyncIds(
        setting: CubismModelSettingJson,
        stdId: (CubismDefaultParameterId.ParameterId) -> CubismId,
        paramIndex: (String) -> Int,
    ): List<CubismId> {
        val ids = ArrayList<CubismId>()
        for (i in 0 until setting.lipSyncParameterCount) {
            setting.getLipSyncParameterId(i)?.let { ids.add(it) }
        }
        if (ids.isEmpty()) {
            val mouthOpen = stdId(CubismDefaultParameterId.ParameterId.MOUTH_OPEN_Y)
            if (paramIndex(mouthOpen.string) >= 0) ids.add(mouthOpen)
        }
        return ids
    }

    /**
     * 呼吸：照官方 Sample 的参数与周期（角度微摆 + `ParamBreath`）。
     *
     * 它不依赖任何模型参数（写的是框架的标准 id），所以无条件挂着 ——
     * 至少让"待机"这件事看上去是活的。
     */
    fun breath(stdId: (CubismDefaultParameterId.ParameterId) -> CubismId): CubismBreath {
        val breath = CubismBreath.create()
        breath.setParameters(
            listOf(
                CubismBreath.BreathParameterData(
                    stdId(CubismDefaultParameterId.ParameterId.ANGLE_Z),
                    0.0f, 15.0f, 6.5345f, 0.5f,
                ),
                CubismBreath.BreathParameterData(
                    stdId(CubismDefaultParameterId.ParameterId.BODY_ANGLE_Z),
                    0.0f, 8.0f, 3.5345f, 0.5f,
                ),
                CubismBreath.BreathParameterData(
                    stdId(CubismDefaultParameterId.ParameterId.BREATH),
                    0.5f, 0.5f, 3.2345f, 0.5f,
                ),
            ),
        )
        return breath
    }

    /**
     * 加载所有动作组（"模型自带动作"那一档就是从这里来的）。
     *
     * 组名找不到就跳过；**读文件失败会让整张模型降级**（原实现里 `readRef` 没包 try，
     * 导入阶段已经校验过包内文件齐全，走到这一步读不出来就是真出事了，不该静默）。
     * 而单个动作格式不对只跳过这一条 —— 缺素材不许报错（方案文档 §二十四）。
     */
    fun loadMotions(
        setting: CubismModelSettingJson,
        readRef: (String) -> ByteArray,
        loadMotion: (ByteArray) -> CubismMotion?,
        blinkIds: List<CubismId>,
        lipSyncIds: List<CubismId>,
    ): Motions {
        val byKey = HashMap<String, ACubismMotion>()
        val groups = ArrayList<String>()
        val counts = LinkedHashMap<String, Int>()
        for (g in 0 until setting.motionGroupCount) {
            val group = setting.getMotionGroupName(g) ?: continue
            if (group !in groups) groups.add(group)
            var loaded = 0
            for (i in 0 until setting.getMotionCount(group)) {
                val file = setting.getMotionFileName(group, i) ?: continue
                if (file.isBlank()) continue
                val motion = loadMotion(readRef(file)) ?: continue
                setting.getMotionFadeInTimeValue(group, i)
                    .takeIf { it != -1.0f }?.let { motion.setFadeInTime(it) }
                setting.getMotionFadeOutTimeValue(group, i)
                    .takeIf { it != -1.0f }?.let { motion.setFadeOutTime(it) }
                // 眨眼的 effect id 用**解析出来的那一份**：清单里有就按清单，空则按标准参数，
                // 与 CubismEyeBlink 用的是同一份（见 blinkIds 的注释）
                motion.setEffectIds(blinkIds, lipSyncIds)
                byKey["${group}_$i"] = motion
                loaded++
            }
            if (loaded > 0) counts[group] = loaded
        }
        return Motions(byKey, groups, counts)
    }

    /**
     * 加载所有表情（`Expressions` 段里的 `.exp3.json`，名字就是 `model3.json` 里的 Name）。
     *
     * 一条加载失败只跳过这一条 —— 缺一个表情不该让整张模型降级。
     */
    fun loadExpressions(
        setting: CubismModelSettingJson,
        readRef: (String) -> ByteArray,
        loadExpression: (ByteArray) -> CubismExpressionMotion?,
    ): Expressions {
        val byName = HashMap<String, CubismExpressionMotion>()
        val names = ArrayList<String>()
        for (i in 0 until setting.expressionCount) {
            val name = setting.getExpressionName(i)?.takeIf { it.isNotBlank() } ?: continue
            val file = setting.getExpressionFileName(i)?.takeIf { it.isNotBlank() } ?: continue
            val motion = runCatching { loadExpression(readRef(file)) }.getOrNull() ?: continue
            byName[name] = motion
            names.add(name)
        }
        return Expressions(byName, names)
    }

    /**
     * 互动区域：优先用模型自带的 `HitArea`（`HitAreas` 段里 Name = Head / Body，
     * 这是 Live2D 官方约定的名字，配合 TapBody / Tap 那套动作组）。
     *
     * 免费模型这块十有八九是空的，那就退回几何分区 —— 两条都要有，
     * 只留一条会在另一半模型上完全点不动（判定怎么走见 [Live2dHitResolver]）。
     */
    fun hitAreas(setting: CubismModelSettingJson): HitAreas {
        var head: CubismId? = null
        var body: CubismId? = null
        for (i in 0 until setting.hitAreasCount) {
            val name = runCatching { setting.getHitAreaName(i) }.getOrNull() ?: continue
            val id = runCatching { setting.getHitAreaId(i) }.getOrNull() ?: continue
            when {
                name.equals(InteractionRules.HIT_AREA_HEAD, ignoreCase = true) -> head = id
                name.equals(InteractionRules.HIT_AREA_BODY, ignoreCase = true) -> body = id
            }
        }
        return HitAreas(head, body)
    }
}
