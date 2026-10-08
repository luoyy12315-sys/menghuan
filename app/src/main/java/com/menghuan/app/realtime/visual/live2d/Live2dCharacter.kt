package com.menghuan.app.realtime.visual.live2d

import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLUtils
import com.live2d.sdk.cubism.framework.CubismDefaultParameterId
import com.live2d.sdk.cubism.framework.CubismFramework
import com.live2d.sdk.cubism.framework.CubismModelSettingJson
import com.live2d.sdk.cubism.framework.effect.CubismBreath
import com.live2d.sdk.cubism.framework.effect.CubismEyeBlink
import com.live2d.sdk.cubism.framework.id.CubismId
import com.live2d.sdk.cubism.framework.math.CubismMatrix44
import com.live2d.sdk.cubism.framework.model.CubismModel
import com.live2d.sdk.cubism.framework.model.CubismUserModel
import com.live2d.sdk.cubism.framework.motion.ACubismMotion
import com.live2d.sdk.cubism.framework.motion.CubismBreathUpdater
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion
import com.live2d.sdk.cubism.framework.motion.CubismExpressionUpdater
import com.live2d.sdk.cubism.framework.motion.CubismEyeBlinkUpdater
import com.live2d.sdk.cubism.framework.motion.CubismLipSyncUpdater
import com.live2d.sdk.cubism.framework.motion.CubismPhysicsUpdater
import com.live2d.sdk.cubism.framework.motion.CubismPoseUpdater
import com.live2d.sdk.cubism.framework.motion.IBooleanSupplier
import com.live2d.sdk.cubism.framework.motion.IParameterProvider
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid
import com.menghuan.app.live2d.ActionPlayer
import com.menghuan.app.live2d.ActionType
import com.menghuan.app.live2d.InteractionRegion
import com.menghuan.app.live2d.InteractionRules
import com.menghuan.app.live2d.Live2dAction
import com.menghuan.app.live2d.ModelActionCatalog
import com.menghuan.app.live2d.ModelCapability
import com.menghuan.app.live2d.StagePose
import com.menghuan.app.live2d.fillNdcMatrix
import java.io.File
import kotlin.math.abs
import kotlin.math.exp

/**
 * 一个 Live2D 角色（v0.58.0）。
 *
 * 这是官方 Java Sample 的 `LAppMinimumModel` 的本工程版：**加载顺序、参数更新顺序、
 * 绘制矩阵算法都照它抄**（AGENTS §零.1：别闭门造车），差别只有两处——
 *
 * 1. **从 `filesDir` 的绝对路径读，不走 AssetManager**。官方的 `LAppPal.loadFileAsBytes`
 *    里写死了 `activity.getAssets().open(path)`，而我们的模型是用户导入到私有目录的文件，
 *    所以整个读文件环节换成 [File.readBytes]（框架只吃 `byte[]`，换掉这一个函数就够）。
 * 2. **口型来自实时通话的 PCM 电平**，不是官方那个空壳 `LAppWavFileHandler`
 *    （它的 `getParameter()` 直接 return 0.0f，源码注释写明本示例未实现）。
 *    框架侧的接入口是 `CubismLipSyncUpdater(lipSyncIds, IParameterProvider)`，
 *    它每帧把 [IParameterProvider.getParameter] 的值以 0.8 权重加到 `ParamMouthOpenY` 上，
 *    所以我们只负责把"电平"算准（平滑在这里，见 [MouthProvider]）。
 *
 * ⚠️ 除 [setMouthLevel] 与 [playAction] / [stopAction] / [resetFrameClock] 是跨线程写
 * （字段都是 volatile）之外，其余方法都**必须在 GL 线程**调用
 * （纹路与渲染器的创建/销毁都绑 EGL 上下文）。
 */
class Live2dCharacter(
    private val baseDir: File,
    private val modelJsonRelPath: String,
) : CubismUserModel() {

    /** 加载失败的原因（非空 = 视觉层降级，通话继续） */
    var failure: String? = null
        private set

    private var setting: CubismModelSettingJson? = null
    private val textureIds = ArrayList<Int>()

    /** key = `组名_序号`（同官方） */
    private val motions = HashMap<String, ACubismMotion>()
    private val motionGroups = ArrayList<String>()
    private val lipSyncIds = ArrayList<CubismId>()

    private var motionUpdated = false

    /**
     * 上一帧的时刻。`@Volatile` 是因为 [resetFrameClock] 由页面回前台那条路（主线程）调，
     * 而读它的是渲染线程 —— 一个只会"往前提"的时间戳，跨线程读到旧值或新值都不影响正确性。
     */
    @Volatile private var lastFrameNanos = 0L

    private val mouth = MouthProvider()

    /** GL 线程就绪、可以画的标志 */
    var ready = false
        private set

    // ---- v0.64.0：动作系统 ----

    /**
     * 动作播放器。通话页与角色空间都只跟它说话 ——
     * 上层拿到一个 [Live2dAction] 就 `player.play(action)`，不关心参数怎么写。
     */
    val player = ActionPlayer()

    /** 这张模型有哪些通用通道（加载后填）。UI 上"这张模型支持 N 项动作能力"读的就是它 */
    @Volatile
    var capability: ModelCapability = ModelCapability.EMPTY
        private set

    /** 这张模型自带的动作组与表情清单；加载完成后经 [onCatalogReady] 发出去，由仓库同步进动作库 */
    @Volatile
    var catalog: ModelActionCatalog = ModelActionCatalog.EMPTY
        private set

    /**
     * 清单就绪回调。**在 GL 线程调**，实现里只许做"把值转手给别处"这件事，
     * 不许做磁盘或数据库操作（会卡住整条渲染线程）。
     */
    @Volatile
    var onCatalogReady: ((ModelActionCatalog) -> Unit)? = null

    /** 表情名 → 表情动作（`.exp3.json`，v0.64.0） */
    private val expressions = HashMap<String, CubismExpressionMotion>()

    /** 待播的原生动作组 / 序号（跨线程意图，GL 线程下一帧取用） */
    @Volatile
    private var wantMotion: Pair<String, Int>? = null

    /** 待播的表情名（同上） */
    @Volatile
    private var wantExpression: String? = null

    /** 待停表情（同上） */
    @Volatile
    private var wantExpressionStop = false

    /** 上一帧算出来的舞台姿态，画的时候叠加（v0.64.0） */
    private var stagePose = StagePose.NONE

    /**
     * 角色中心在 NDC 里的位置（v0.67.0）。
     *
     * 由 [fitProjection] 每帧算好交给 [applyStagePose]：舞台旋转/缩放**绕这个点**做，
     * 而不是绕 NDC 原点（= 屏幕正中）。分区布局里角色被抬到上半屏，绕屏幕中心转 360°
     * 画出来是"贴着一条大圆弧转圈"，不是"在原地翻跟斗"。
     */
    private var pivotX = 0f
    private var pivotY = 0f

    /**
     * 命中检测用的投影**四个数**（缩放 X/Y + 平移 X/Y，**不含舞台姿态**）。
     *
     * 为什么要专门留一份而不是直接拿那个 `CubismMatrix44`：
     * ① 翻跟斗那类动作会把整张画布转起来，而命中检测必须按"角色静止时画在哪"来算 ——
     *    用转过的矩阵去反算触点，结果会跟着转，用户点脸可能判成点身体；
     * ② ⚠️ 触点是从 **UI 线程**来的（`hitRegion` 由点角色的那条路调），
     *    而矩阵是 GL 线程每帧重写的。共享一个可变对象去读它，会读到"半新半旧"的一份 ——
     *    不至于崩（都是 float），但会**偶发地点错区域**：用户点了头、判成身体。
     *    四个 `@Volatile` 的 float（32 位写入本身是原子的）把这件事彻底消掉：
     *    最坏只是这四个数来自相邻的两帧，对"点哪儿"这种判定毫无影响。
     *
     * 反算就是 `invertTransformX/Y` 的定义：`model = (ndc - t) / s`，
     * 与原来交给 `CubismMatrix44` 做的是同一个式子，只是不再共享那个对象。
     */
    @Volatile private var hitScaleX = 0f
    @Volatile private var hitScaleY = 0f
    @Volatile private var hitTranslateX = 0f
    @Volatile private var hitTranslateY = 0f

    /** 舞台姿态的矩阵（复用一个实例，别每帧 new —— 动作播放是每帧都在跑的） */
    private val stageMatrix = CubismMatrix44.create()

    /** 模型自带 HitArea（`Head` / `Body`）的 CubismId；模型没写 `HitAreas` 时是 null */
    private var headHitId: CubismId? = null
    private var bodyHitId: CubismId? = null

    /**
     * 是否需要几何兜底命中检测：**只有当模型没定义 `Head`/`Body` 两个 HitArea 时才开**。
     *
     * 开着就要额外求一次模型包围盒（遍历全部顶点），所以刷新频率收在
     * [BOUNDS_INTERVAL_SECONDS] 一次，不是每帧（见 [update] 里那段）。
     * 而绝大多数正规模型都有 HitArea，压根不该为它们付这份开销。
     */
    private var needsGeometricHitTest = false

    /** 几何兜底用的模型包围盒（模型坐标系，GL 线程刷，UI 线程读） */
    @Volatile private var boundLeft = 0f
    @Volatile private var boundRight = 0f
    @Volatile private var boundTop = 0f
    @Volatile private var boundBottom = 0f
    @Volatile private var boundsReady = false

    /** 距离上一次刷新包围盒过了多少秒，见 [BOUNDS_INTERVAL_SECONDS] */
    private var boundsAgeSeconds = Float.MAX_VALUE

    /** [stageMatrix] 的临时数组，复用同一个：舞台动作是**每帧**都在跑的 */
    private val stageMatrixArray = FloatArray(16)

    // ---- 加载 ----

    /**
     * 读模型 + 建渲染器 + 上传纹理。**必须在 GL 线程**（`onSurfaceCreated` 里调）。
     */
    fun loadAssets(width: Int, height: Int) {
        runCatching {
            val jsonFile = File(baseDir, modelJsonRelPath)
            val setting = CubismModelSettingJson(jsonFile.readBytes())
            this.setting = setting

            // 1) moc → model（loadModel 里会建 modelMatrix 并 saveParameters）
            val mocRel = setting.modelFileName ?: error("model3.json 里没有 Moc 引用")
            loadModel(readRef(mocRel), true)   // 一致性校验：损坏的 moc 宁可拒绝也不要在通话里崩
            val model = this.model ?: error("模型创建失败")

            val idm = CubismFramework.getIdManager()
            fun std(name: CubismDefaultParameterId.ParameterId) = idm.getId(name.getId())

            // 1.5) v0.64.0 动作系统接上：探测这张模型有哪些通用通道，然后让播放器认住它。
            //
            // ⚠️ 顺序不能挪到 loadModel 之后之外的任何地方：`bindModel` 要读每个参数的
            // **静止值**（模型自己的默认姿态），而那条值只在"还没播过任何动作与原生 motion"
            // 的这一刻是准的。放到第一帧 update 之后，读到的就是 motion 摆过的位置，
            // 之后每次播动作都会从那里"跳"回来。
            val capability = ModelCapability.detect { name -> model.getParameterIndex(idm.getId(name)) >= 0 }
            this.capability = capability
            this.player.bindModel(model, capability) { name -> model.getParameterIndex(idm.getId(name)) }

            // 1.6) 互动区域：优先用模型自带的 HitArea（`HitAreas` 段里 Name = Head / Body，
            // 这是 Live2D 官方约定的名字，配合 TapBody / Tap 那套动作组）。
            // 免费模型这块十有八九是空的，那就退回几何分区 —— 两条都要有，
            // 只留一条会在另一半模型上完全点不动。
            headHitId = null
            bodyHitId = null
            for (i in 0 until setting.hitAreasCount) {
                val name = runCatching { setting.getHitAreaName(i) }.getOrNull() ?: continue
                val id = runCatching { setting.getHitAreaId(i) }.getOrNull() ?: continue
                when {
                    name.equals(InteractionRules.HIT_AREA_HEAD, ignoreCase = true) -> headHitId = id
                    name.equals(InteractionRules.HIT_AREA_BODY, ignoreCase = true) -> bodyHitId = id
                }
            }
            needsGeometricHitTest = headHitId == null && bodyHitId == null
            boundsReady = false

            // 2) 物理与摇摆：引用了才加载，缺文件不算错（导入阶段已校验过）
            setting.physicsFileName?.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    loadPhysics(readRef(it))
                    physics?.let { p -> updateScheduler.addUpdatableList(CubismPhysicsUpdater(p)) }
                }
            }
            setting.poseFileName?.takeIf { it.isNotBlank() }?.let {
                runCatching {
                    loadPose(readRef(it))
                    pose?.let { p -> updateScheduler.addUpdatableList(CubismPoseUpdater(p)) }
                }
            }

            // 3~5) 眨眼与口型的参数来源。
            //
            // 正规做法是读 model3.json 的 `Groups`（EyeBlink / LipSync 两段的参数清单），
            // 但**大量免费模型这里是空的** —— 真机用的"奶蛙"包就是 `Groups: [{EyeBlink: []}]`
            // 连 LipSync 段都没有、也没有 Motions，全靠 Cubism 的标准参数名工作（VTube 系都这样）。
            // 只按清单走的话结果不是报错，而是**角色一动不动 / 嘴不张**（最难查的那种失败），
            // 所以清单空就回退标准参数 id，并且**只认这个模型里真存在的那个**：
            // 指一个不存在的参数，框架不会吭声，只会白跑。

            val blinkIds = ArrayList<CubismId>()
            for (i in 0 until setting.eyeBlinkParameterCount) {
                setting.getEyeBlinkParameterId(i)?.let { blinkIds.add(it) }
            }
            if (blinkIds.isEmpty()) {
                listOf(std(CubismDefaultParameterId.ParameterId.EYE_L_OPEN), std(CubismDefaultParameterId.ParameterId.EYE_R_OPEN))
                    .filterTo(blinkIds) { model.getParameterIndex(it) >= 0 }
            }
            val eyeBlink = CubismEyeBlink.create(setting)
            if (eyeBlink != null && blinkIds.isNotEmpty()) {
                eyeBlink.setParameterIds(blinkIds)
                this.eyeBlink = eyeBlink
                updateScheduler.addUpdatableList(
                    CubismEyeBlinkUpdater(IBooleanSupplier { motionUpdated }, eyeBlink),
                )
            }

            // 4) 呼吸：照官方 Sample 的参数与周期（角度微摆 + ParamBreath）
            val breath = CubismBreath.create()
            breath.setParameters(
                listOf(
                    CubismBreath.BreathParameterData(
                        std(CubismDefaultParameterId.ParameterId.ANGLE_Z),
                        0.0f, 15.0f, 6.5345f, 0.5f,
                    ),
                    CubismBreath.BreathParameterData(
                        std(CubismDefaultParameterId.ParameterId.BODY_ANGLE_Z),
                        0.0f, 8.0f, 3.5345f, 0.5f,
                    ),
                    CubismBreath.BreathParameterData(
                        std(CubismDefaultParameterId.ParameterId.BREATH),
                        0.5f, 0.5f, 3.2345f, 0.5f,
                    ),
                ),
            )
            this.breath = breath
            updateScheduler.addUpdatableList(CubismBreathUpdater(breath))

            // 5) 口型：把实时电平接进框架的 lip-sync 更新器
            for (i in 0 until setting.lipSyncParameterCount) {
                setting.getLipSyncParameterId(i)?.let { lipSyncIds.add(it) }
            }
            if (lipSyncIds.isEmpty()) {
                val mouthOpen = std(CubismDefaultParameterId.ParameterId.MOUTH_OPEN_Y)
                if (model.getParameterIndex(mouthOpen) >= 0) lipSyncIds.add(mouthOpen)
            }
            if (lipSyncIds.isNotEmpty()) {
                updateScheduler.addUpdatableList(CubismLipSyncUpdater(lipSyncIds, mouth))
            }

            // 6) 动作组（"模型自带动作"那一档就是从这个循环里来的）
            val groupCounts = LinkedHashMap<String, Int>()
            for (g in 0 until setting.motionGroupCount) {
                val group = setting.getMotionGroupName(g) ?: continue
                if (group !in motionGroups) motionGroups.add(group)
                var loaded = 0
                for (i in 0 until setting.getMotionCount(group)) {
                    val file = setting.getMotionFileName(group, i) ?: continue
                    if (file.isBlank()) continue
                    val motion = loadMotion(readRef(file)) ?: continue
                    setting.getMotionFadeInTimeValue(group, i)
                        .takeIf { it != -1.0f }?.let { motion.setFadeInTime(it) }
                    setting.getMotionFadeOutTimeValue(group, i)
                        .takeIf { it != -1.0f }?.let { motion.setFadeOutTime(it) }
                    motion.setEffectIds(eyeBlinkIds(), lipSyncIds)
                    motions["${group}_$i"] = motion
                    loaded++
                }
                if (loaded > 0) groupCounts[group] = loaded
            }

            // 6.5) 表情（v0.64.0）：`Expressions` 段里的 `.exp3.json`，名字就是 model3.json
            // 里写的 Name。加载失败只跳过这一条 —— 缺一个表情不该让整张模型降级。
            val expressionNames = ArrayList<String>()
            for (i in 0 until setting.expressionCount) {
                val name = setting.getExpressionName(i)?.takeIf { it.isNotBlank() } ?: continue
                val file = setting.getExpressionFileName(i)?.takeIf { it.isNotBlank() } ?: continue
                val motion = runCatching { loadExpression(readRef(file)) }.getOrNull() ?: continue
                expressions[name] = motion
                expressionNames.add(name)
            }
            if (expressions.isNotEmpty()) {
                // 与官方 Sample 同一个位置：表情也走 updateScheduler，在动作/物理之前叠到脸上
                updateScheduler.addUpdatableList(CubismExpressionUpdater(expressionManager))
            }
            updateScheduler.sortUpdatableList()

            // 6.6) 把"模型自带素材"的清单发出去，由仓库层同步进动作库。
            // 只在**真有东西**时发：一张既没动作组也没表情的模型（免费包常见）
            // 不该在库里留下一批空动作。
            val catalog = ModelActionCatalog(
                modelKey = modelJsonRelPath,
                motionGroups = groupCounts,
                expressions = expressionNames,
            )
            this.catalog = catalog
            onCatalogReady?.invoke(catalog)

            // 7) 渲染器与纹理（都要 EGL 上下文）
            // CubismRendererAndroid.create 的返回类型是基类 CubismRenderer，Android 实现才有
            // bindTexture —— 拿不到具体类型就是这套渲染器不对，直接判失败降级
            val renderer = createRenderer(width, height)
            setupRenderer(renderer)
            setupTextures(renderer)

            lastFrameNanos = System.nanoTime()
            ready = true
        }.onFailure {
            failure = describe(it)
            ready = false
        }
    }

    /** EGL 上下文丢失后重建渲染器与纹理（GL 线程） */
    fun reloadRenderer(width: Int, height: Int) {
        if (setting == null) return
        runCatching { deleteRenderer() }
        textureIds.clear()
        val renderer = createRenderer(width, height)
        setupRenderer(renderer)
        setupTextures(renderer)
        lastFrameNanos = System.nanoTime()
    }

    /**
     * 把帧时钟 reset 成"就是现在"（GL 线程之外**不许**调）。
     *
     * 为什么单独立一条：从后台/锁屏回来那一刻，`lastFrameNanos` 还是几分钟前，
     * 下一帧的 dt 会撞上上限（见 [update]），动作与口型各白跳一格。
     * 时钟归零后第一帧只走正常时长，回来的那一下就是"接着动"而不是"抽一下"。
     */
    fun resetFrameClock() {
        lastFrameNanos = System.nanoTime()
    }

    /**
     * `CubismRendererAndroid.create` 声明返回基类 `CubismRenderer`（框架自己的写法），
     * 但 Android 实现只有 `CubismRendererAndroid` 一个，取不到就是初始化失败 ——
     * 抛出去由调用方降级成头像，不许在这里返回 null 让后面 NPE。
     */
    private fun createRenderer(width: Int, height: Int): CubismRendererAndroid =
        CubismRendererAndroid.create(width, height) as? CubismRendererAndroid
            ?: error("Live2D 渲染器创建失败（这台机器的 OpenGL 环境不支持）")

    private fun setupTextures(renderer: CubismRendererAndroid) {
        for (i in 0 until (setting?.textureCount ?: 0)) {
            val rel = setting?.getTextureFileName(i) ?: continue
            val bytes = runCatching { readRef(rel) }.getOrNull() ?: continue
            val id = uploadTexture(bytes) ?: continue
            renderer.bindTexture(i, id)
            textureIds.add(id)
        }
        renderer.isPremultipliedAlpha(true)
    }

    private fun uploadTexture(bytes: ByteArray): Int? = runCatching {
        val opts = BitmapFactory.Options().apply { inPremultiplied = true }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        bitmap.recycle()
        ids[0]
    }.getOrNull()

    /** model3.json 里的引用都是相对它自己所在目录的 */
    private fun readRef(rel: String): ByteArray {
        val jsonDir = File(baseDir, modelJsonRelPath).parentFile ?: baseDir
        val direct = File(jsonDir, rel)
        if (direct.isFile) return direct.readBytes()
        // 少数导出包会把路径写成相对包根，兜一次
        val atRoot = File(baseDir, rel)
        if (atRoot.isFile) return atRoot.readBytes()
        error("模型包缺少文件 $rel")
    }

    private fun eyeBlinkIds(): List<CubismId> {
        val s = setting ?: return emptyList()
        val out = ArrayList<CubismId>()
        for (i in 0 until s.eyeBlinkParameterCount) {
            s.getEyeBlinkParameterId(i)?.let { out.add(it) }
        }
        return out
    }

    // ---- 每帧 ----

    /** 更新（GL 线程）。顺序严格照官方：loadParameters → motion → saveParameters → lateUpdate → update */
    fun update() {
        val model = model ?: return
        isUpdated(false)

        val now = System.nanoTime()
        // dt 的上限与 ActionPlayer 用的是同一个常数：切后台/锁屏回来后 nanoTime 能差出几秒，
        // 夹得太松（原来是 100ms）就等于**回前台那一帧白跳十分之一秒**，观感是"角色抽一下"。
        val dt = if (lastFrameNanos == 0L) DEFAULT_FRAME_SECONDS
            else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, ActionPlayer.MAX_FRAME_SECONDS)
        lastFrameNanos = now

        motionUpdated = false
        model.loadParameters()
        applyPendingRequests()
        // 模型自己的 Idle 动作**一直在底下跑**：动作系统是叠在它上面的一层姿态，
        // 不是把它换掉 —— 这样"用户没绑任何动作"时角色依然自然（有呼吸、有它自己的小动作）。
        if (motionManager.isFinished()) {
            playGroup(motionCandidates(IDLE_GROUP), PRIORITY_IDLE)
        } else {
            motionUpdated = motionManager.updateMotion(model, dt)
        }
        model.saveParameters()

        // ⚠️ 动作轨道写在这一步的**位置是关键**（写在 saveParameters 之后、onLateUpdate 之前）：
        // · 动作能盖住模型自带 motion 摆出来的姿态，否则摆手会被 idle 一把拽回去；
        // · 眨眼 / 呼吸 / 口型在它之后跑 —— 口型是**加法**叠加，所以动作永远抢不走"说话时的嘴"；
        // · 物理在它之后读身体角度，于是摆手时头发与裙摆会跟着甩 —— 白得一层的观感。
        stagePose = player.applyTo(model, dt)

        // 口型平滑由框架的 CubismLipSyncUpdater 在它自己那一步调 update(dt) 时推进
        updateScheduler.onLateUpdate(model, dt)

        model.update()
        if (needsGeometricHitTest) {
            // 包围盒**不用每帧算**：这个值只在用户点下去的那一刻用，
            // 而算一次要把全部 drawable 的全部顶点读一遍（免费模型几千到几万次数组读）。
            // 角色自己呼吸摆动让包围盒缓慢变形，但点的判档本来就带区间余量，
            // 15Hz 与 60Hz 用户点起来没有任何区别。
            boundsAgeSeconds += dt
            if (boundsAgeSeconds >= BOUNDS_INTERVAL_SECONDS) {
                boundsAgeSeconds = 0f
                refreshModelBounds(model)
            }
        }
        isUpdated(true)
    }

    /** 绘制（GL 线程）。矩阵算法照官方 `LAppMinimumModel.draw`：stage × modelMatrix × projection */
    fun draw(projection: CubismMatrix44) {
        val renderer = getRenderer<CubismRendererAndroid>() ?: return
        if (model == null) return
        applyStagePose(projection)
        CubismMatrix44.multiply(modelMatrix.array, projection.array, projection.array)
        renderer.setMvpMatrix(projection)
        renderer.drawModel()
    }

    /**
     * 把舞台姿态叠到投影上（v0.64.0，GL 线程）。
     *
     * 舞台通道**完全走矩阵**、不碰任何模型参数 —— 这是"翻跟斗在任何模型上都演得出来"的
     * 实现方式（没有 `ParamBodyAngleY` 的模型照样能整体翻一圈）。
     *
     * 归一化值 → NDC 的换算：[StagePose.dx] 是"画面宽度的比例"，而 NDC 横跨 2 个单位，
     * 所以乘 2。
     *
     * ⚠️ 旋转与缩放的**中心是角色自己**（[pivotX] / [pivotY]，由 [fitProjection] 每帧算好交过来），
     * 不是 NDC 原点。v0.64~v0.66 绕的是原点 = 屏幕正中，而通话页把角色整只抬到上半屏 ——
     * 于是"翻跟斗"画出来是"贴着一条大圆弧绕屏幕转"，角色空间里拖到边上再翻也一样甩出去。
     * 矩阵换算本身是纯函数（[fillNdcMatrix]，有 JVM 单测），这里只负责告诉它角色中心在哪。
     *
     * ⚠️ 一定要**乘进投影**而不是改 modelMatrix：modelMatrix 在 `fitProjection` 每帧重置，
     * 改它会被覆盖；投影同样是每帧新算的，所以在这里叠加不会累积。
     */
    private fun applyStagePose(projection: CubismMatrix44) {
        val pose = stagePose
        if (pose.isIdentity) return
        pose.fillNdcMatrix(stageMatrixArray, pivotX, pivotY)
        stageMatrix.setMatrix(stageMatrixArray)
        CubismMatrix44.multiply(stageMatrix.array, projection.array, projection.array)
    }

    /**
     * 把投影矩阵摆成"角色完整可见、不拉伸变形"（GL 线程）。
     *
     * 算法与分支条件都照官方 `LAppMinimumLive2DManager.onUpdate`：
     * 横长模型按宽度铺满并压一下纵向比例，竖长模型按高度铺满——
     * 自己按比例缩放看着没问题，但半身模型会被拉成"长短腿"（真机上最扎眼的变形）。
     */
    fun fitProjection(
        projection: CubismMatrix44,
        viewWidth: Int,
        viewHeight: Int,
        /**
         * 底部留给 UI 的高度比例（0..0.8，v0.64.0）。角色会完整落在**剩下的那块**里。
         *
         * 通话页现在是"角色区在上、对话台在下"的分区布局，字幕与按钮都压在页面底色上、
         * 永远不进入角色那块 —— 这个参数就是把"角色那块有多大"告诉渲染。
         * **不重建渲染目标**，只改投影，所以布局动画期间每帧调也没有代价。
         */
        bottomInset: Float = 0f,
        /** 角色额外位移（画面比例，v0.64.0 角色空间拖动用） */
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) {
        val model = model ?: return
        val inset = bottomInset.coerceIn(0f, MAX_BOTTOM_INSET)
        // 还剩多少比例的画面是"角色的地盘"。inset=0 时以下全部退化成原实现，
        // 所以语音通话与不分区的那条老路径行为一个字都没变。
        val shrink = 1f - inset
        val availHeight = (viewHeight * shrink).coerceAtLeast(1f)

        projection.loadIdentity()
        val aspectRatio = if (viewHeight == 0) 1f else viewWidth.toFloat() / viewHeight.toFloat()
        val displayRatio = availHeight / viewWidth.toFloat().coerceAtLeast(1f)
        val canvasWidth = model.canvasWidth
        val canvasRatio = if (canvasWidth <= 0f) 1f else model.canvasHeight / canvasWidth
        if (canvasRatio < displayRatio) {
            // 横长的模型：按宽度铺满，纵向压一下比例（不压的话半身模型会变成"长短腿"）
            modelMatrix.setWidth(2.0f)
            projection.scale(1.0f, aspectRatio)
        } else {
            // 竖长的模型：按高度铺满**可用区域**（乘 shrink），横向反压
            modelMatrix.setHeight(2.0f)
            projection.scale(shrink / aspectRatio, shrink)
        }
        // 把角色摆到可用区域的中间：NDC 的 y 从 -1（底）到 +1（顶），
        // 可用区域是 [2*inset-1, 1]，它的中点就是 inset。
        projection.translate(offsetX * 2f, inset + offsetY * 2f)

        // **这一帧角色中心落在 NDC 的哪儿** —— 舞台旋转/缩放绕它做（见 applyStagePose）。
        // 上面那句 translate 就是把模型本地原点（= 画布中心）放到的位置，所以两处必须同步改。
        pivotX = offsetX * 2f
        pivotY = inset + offsetY * 2f

        // 命中检测要用完整变换（modelMatrix × projection），且必须是**不含舞台姿态**的那一份，
        // 见 [hitScaleX] 的说明。这里只把反算要用的四个数抄下来 —— 不再每帧做一次矩阵乘法，
        // 也不再把一个可变对象交给 UI 线程去读。
        val m = modelMatrix.array
        val p = projection.array
        hitScaleX = m[0] * p[0]
        hitScaleY = m[5] * p[5]
        // (M·P) 的平移列 = M 的缩放 × P 的平移 + M 自己的平移 —— 少加那一项的话，
        // 一旦 modelMatrix 带过 setPosition（模型画布不居中）点击就会整体偏一位
        hitTranslateX = m[0] * p[12] + m[12]
        hitTranslateY = m[5] * p[13] + m[13]
    }

    // ---- v0.64.0：动作与互动 ----

    /**
     * 播放一条动作（任意线程可调）。**通话页与角色空间唯一的入口。**
     *
     * 三种形态在这里分流，上层不需要知道：
     * - `PROCEDURAL` → 交给 [player]（通用参数轨道 + 舞台姿态）
     * - `NATIVE` → 交回模型自己的 motionManager（只在这张模型真有那个组时才生效）
     * - `EXPRESSION` → 表情
     *
     * 顺带说明一条刻意的取舍：**动作与表情可以同时存在**。
     * 用户绑"摆手"时如果那条动作还带一个表情，两层一起上才是想要的观感。
     */
    fun playAction(action: Live2dAction) {
        // 切动作前先把上一轮的原生动作与表情收干净：
        // 否则"摆动"绑到 NATIVE、下一句绑到 PROCEDURAL 时，原生动作会在后台继续播完，
        // 表现为"角色一边摆手一边还在点头"。
        wantMotion = null
        when (action.type) {
            ActionType.PROCEDURAL -> {
                player.play(action)
                if (action.expression.isNotBlank()) wantExpression = action.expression
            }

            ActionType.NATIVE -> {
                player.stop()
                if (action.motionGroup.isNotBlank()) wantMotion = action.motionGroup to action.motionIndex
                if (action.expression.isNotBlank()) wantExpression = action.expression
            }

            ActionType.EXPRESSION -> {
                player.stop()
                if (action.expression.isNotBlank()) wantExpression = action.expression
            }
        }
    }

    /** 停掉一切动作，回到模型自己的待机（挂断、离开角色空间时调） */
    fun stopAction() {
        player.stop()
        wantMotion = null
        wantExpressionStop = true
    }

    /** 只播一个表情（角色空间的"表情"那一档用） */
    fun playExpression(name: String) {
        if (name.isBlank()) return
        wantExpression = name
    }

    /** 待播意图的落实（GL 线程，在 update 的开头） */
    private fun applyPendingRequests() {
        if (wantExpressionStop) {
            wantExpressionStop = false
            runCatching { expressionManager.stopAllMotions() }
        }
        wantExpression?.let { name ->
            wantExpression = null
            expressions[name]?.let { runCatching { expressionManager.startMotionPriority(it, PRIORITY_FORCE) } }
        }
        wantMotion?.let { (group, index) ->
            wantMotion = null
            playMotion(group, index, PRIORITY_NORMAL)
        }
    }

    /**
     * 播放模型自带的某个动作组里的第 [index] 个动作（GL 线程）。
     *
     * 找法有两级：先按 `组名_序号` 精确取，取不到就退成"该组第一个动作"——
     * 序号是导入时扫出来的，而模型包在别的版本里可能少一个动作文件，
     * 这种情况不该表现为"点了没反应"。
     *
     * ⚠️ 只有"动作库里选了**模型自带**那一档"才会走到这里；
     * 四个通话状态与内置趣味动作全是程序化轨道，与这张表无关。
     */
    private fun playMotion(group: String, index: Int, priority: Int): Boolean {
        val motion = motions["${group}_$index"]
            ?: motions.entries.firstOrNull { it.key.startsWith("${group}_") }?.value
            ?: return false
        if (priority == PRIORITY_FORCE) {
            motionManager.setReservationPriority(priority)
        } else if (!motionManager.reserveMotion(priority)) {
            return false
        }
        motionManager.startMotionPriority(motion, priority)
        return true
    }

    /** 点在某处该算哪个互动区域（**任意线程可调**）。
     *
     * 两级判定：模型自带的 `HitArea` 优先，没有就走几何分区（见 [InteractionRules]）。
     * 传进来的是 **NDC 坐标**（-1..1，y 向上）—— 与 `fitProjection` 建立的投影一致。
     */
    fun hitRegion(ndcX: Float, ndcY: Float): InteractionRegion {
        if (model == null) return InteractionRegion.OTHER
        // NDC → 模型坐标：与 `CubismMatrix44.invertTransformX/Y` 同一个式子，
        // 但读的是四个 volatile 快照，不再跨线程共享那个每帧被重写的矩阵对象（见 [hitScaleX]）
        val sx = hitScaleX
        val sy = hitScaleY
        if (abs(sx) < 1e-6f || abs(sy) < 1e-6f) return InteractionRegion.OTHER
        val mx = (ndcX - hitTranslateX) / sx
        val my = (ndcY - hitTranslateY) / sy

        val head = headHitId
        val body = bodyHitId
        if (head != null || body != null) {
            val headHit = head?.let { runCatching { isHit(it, mx, my) }.getOrDefault(false) } ?: false
            val bodyHit = body?.let { runCatching { isHit(it, mx, my) }.getOrDefault(false) } ?: false
            return InteractionRules.fromHitAreas(headHit, bodyHit)
        }

        if (!boundsReady) return InteractionRegion.OTHER
        val spanX = boundRight - boundLeft
        val spanY = boundBottom - boundTop
        if (spanX <= 1e-4f || spanY <= 1e-4f) return InteractionRegion.OTHER
        val nx = (mx - boundLeft) / spanX
        // 模型坐标 y 向上、屏幕 y 向下，所以这里要翻过来
        val ny = (boundBottom - my) / spanY
        if (nx < 0f || nx > 1f) return InteractionRegion.OTHER
        return InteractionRules.fromBands(ny)
    }

    /** GL 线程：把整张模型所有顶点的包围盒算出来（只在没有 HitArea 的模型上跑） */
    private fun refreshModelBounds(m: CubismModel) {
        var l = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until m.drawableCount) {
            val v = runCatching { m.getDrawableVertexPositions(i) }.getOrNull() ?: continue
            var k = 0
            while (k + 1 < v.size) {
                val x = v[k]
                val y = v[k + 1]
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                k += 2
            }
        }
        if (l > r || t > b) return
        boundLeft = l
        boundRight = r
        boundTop = t
        boundBottom = b
        boundsReady = true
    }

    // ---- 状态与电平（跨线程安全） ----

    /**
     * 播放一组动作（GL 线程）。组名找不到就什么也不做——
     * 官方包里的组名各家不同（Idle/idle_01/Tap…），**缺素材不许报错**（方案文档 §二十四）。
     */
    private fun playGroup(candidates: List<String>, priority: Int): Boolean {
        for (name in candidates) {
            if (name !in motionGroups) continue
            val motion = motions["${name}_0"] ?: motions.entries.firstOrNull { it.key.startsWith("${name}_") }?.value
                ?: continue
            if (priority == PRIORITY_FORCE) {
                motionManager.setReservationPriority(priority)
            } else if (!motionManager.reserveMotion(priority)) {
                continue
            }
            motionManager.startMotionPriority(motion, priority)
            return true
        }
        return false
    }

    /** 主线程调：AI 播放电平（0..1） */
    fun setMouthLevel(level: Float) = mouth.setTarget(level)

    /** 挂断：释放 moc/model/渲染器。语义同官方 delegate.onDestroy */
    fun destroy() {
        ready = false
        // 动作播放器要先松手：它攥着参数下标，模型一没这些下标就全成了野指针
        runCatching { player.release() }
        runCatching { delete() }
        setting = null
        motions.clear()
        motionGroups.clear()
        lipSyncIds.clear()
        textureIds.clear()
        expressions.clear()
        headHitId = null
        bodyHitId = null
        needsGeometricHitTest = false
        boundsReady = false
        stagePose = StagePose.NONE
    }

    private fun motionCandidates(group: String): List<String> =
        if (group == IDLE_GROUP) IDLE_ALIASES else listOf(group) + IDLE_ALIASES

    private fun describe(t: Throwable): String {
        val m = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return "Live2D 模型加载失败：$m"
    }

    /**
     * 口型电平源。
     *
     * 框架的 `CubismLipSyncUpdater` 每帧调 [update]（带 deltaTime）再取 [getParameter]，
     * 所以平滑推进放在 [advance] 里、值放在 [getParameter]。
     *
     * attack（张嘴）比 release（闭嘴）快：**张得慢会跟不上字、闭得慢会像含着东西**，
     * 而闭太干脆又会让句子间的正常停顿看着像"突然合嘴"。这两个常数是量级起点，
     * 真机上按听感调（方案文档 §十一列的五种毛病就是验收清单）。
     */
    private class MouthProvider : IParameterProvider {
        @Volatile private var target = 0f
        private var value = 0f

        fun setTarget(level: Float) {
            target = level.coerceIn(0f, 1f)
        }

        private fun advance(deltaSeconds: Float) {
            val tau = if (target > value) ATTACK_SECONDS else RELEASE_SECONDS
            // 一阶低通：与帧率无关（60/90/120fps 观感一致），比"每帧固定系数"稳
            val k = 1f - exp(-deltaSeconds / tau)
            value += (target - value) * k
            if (value < 0.001f) value = 0f     // 静音尾巴直接归零，别挂着一点点开口
        }

        /** 无 deltaTime 的那条回调（官方接口里给非实时场合用的）：不做平滑 */
        override fun update(): Boolean = true

        /** 框架的 CubismLipSyncUpdater 每帧带时间调这里，平滑就在这一刻推进 */
        override fun update(deltaTimeSeconds: Float): Boolean {
            advance(deltaTimeSeconds)
            return true
        }

        override fun getParameter(): Float = value

        private companion object {
            const val ATTACK_SECONDS = 0.045f
            const val RELEASE_SECONDS = 0.120f
        }
    }

    companion object {
        private const val IDLE_GROUP = "idle"

        /** 官方包里常见的待机组名（首字母大写最常见，全小写与带序号的都试） */
        private val IDLE_ALIASES = listOf("Idle", "idle", "Idle_01", "motion", "Motion")

        /** 优先级同官方 LAppDefine.Priority：IDLE=1 < NORMAL=2 < FORCE=3 */
        private const val PRIORITY_IDLE = 1
        private const val PRIORITY_NORMAL = 2
        private const val PRIORITY_FORCE = 3

        /** 底部 UI 最多能占掉多少画面：超过这个数角色只剩一条缝，那是布局算错了，不是需求 */
        private const val MAX_BOTTOM_INSET = 0.8f

        /** 几何兜底的包围盒多久刷一次（秒）。见 [needsGeometricHitTest] 与 [update] */
        private const val BOUNDS_INTERVAL_SECONDS = 1f / 15f

        /** 第一帧还没有基准时刻时按多少秒推进（60fps 的一帧） */
        private const val DEFAULT_FRAME_SECONDS = 1f / 60f

    }
}
