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
import com.live2d.sdk.cubism.framework.model.CubismUserModel
import com.live2d.sdk.cubism.framework.motion.ACubismMotion
import com.live2d.sdk.cubism.framework.motion.CubismBreathUpdater
import com.live2d.sdk.cubism.framework.motion.CubismEyeBlinkUpdater
import com.live2d.sdk.cubism.framework.motion.CubismLipSyncUpdater
import com.live2d.sdk.cubism.framework.motion.CubismPhysicsUpdater
import com.live2d.sdk.cubism.framework.motion.CubismPoseUpdater
import com.live2d.sdk.cubism.framework.motion.IBooleanSupplier
import com.live2d.sdk.cubism.framework.motion.IParameterProvider
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid
import com.menghuan.app.realtime.visual.CallVisualState
import java.io.File
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
 * ⚠️ 除 [setMouthLevel] 与 [requestState] 是跨线程写（volatile 语义）之外，
 * 其余方法都**必须在 GL 线程**调用（纹路与渲染器的创建/销毁都绑 EGL 上下文）。
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
    private var lastFrameNanos = 0L

    private val mouth = MouthProvider()

    /** GL 线程就绪、可以画的标志 */
    var ready = false
        private set

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
            val idm = CubismFramework.getIdManager()
            fun std(name: CubismDefaultParameterId.ParameterId) = idm.getId(name.getId())

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

            // 6) 动作组（待机/思考/说话按组名找，见 [requestState]）
            for (g in 0 until setting.motionGroupCount) {
                val group = setting.getMotionGroupName(g) ?: continue
                if (group !in motionGroups) motionGroups.add(group)
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
                }
            }
            updateScheduler.sortUpdatableList()

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
        val dt = if (lastFrameNanos == 0L) 1f / 60f
            else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastFrameNanos = now

        motionUpdated = false
        model.loadParameters()
        if (motionManager.isFinished()) {
            playGroup(motionCandidates(IDLE_GROUP), PRIORITY_IDLE)
        } else {
            motionUpdated = motionManager.updateMotion(model, dt)
        }
        model.saveParameters()

        // 口型平滑由框架的 CubismLipSyncUpdater 在它自己那一步调 update(dt) 时推进
        updateScheduler.onLateUpdate(model, dt)

        model.update()
        isUpdated(true)
    }

    /** 绘制（GL 线程）。矩阵算法照官方 `LAppMinimumModel.draw`：modelMatrix × projection */
    fun draw(projection: CubismMatrix44) {
        val renderer = getRenderer<CubismRendererAndroid>() ?: return
        if (model == null) return
        CubismMatrix44.multiply(modelMatrix.array, projection.array, projection.array)
        renderer.setMvpMatrix(projection)
        renderer.drawModel()
    }

    /**
     * 把投影矩阵摆成"角色完整可见、不拉伸变形"（GL 线程）。
     *
     * 算法与分支条件都照官方 `LAppMinimumLive2DManager.onUpdate`：
     * 横长模型按宽度铺满并压一下纵向比例，竖长模型按高度铺满——
     * 自己按比例缩放看着没问题，但半身模型会被拉成"长短腿"（真机上最扎眼的变形）。
     */
    fun fitProjection(projection: CubismMatrix44, viewWidth: Int, viewHeight: Int) {
        val model = model ?: return
        projection.loadIdentity()
        val aspectRatio = if (viewHeight == 0) 1f else viewWidth.toFloat() / viewHeight.toFloat()
        val displayRatio = if (viewWidth == 0) 1f else viewHeight.toFloat() / viewWidth.toFloat()
        val canvasWidth = model.canvasWidth
        val canvasRatio = if (canvasWidth <= 0f) 1f else model.canvasHeight / canvasWidth
        if (canvasRatio < displayRatio) {
            modelMatrix.setWidth(2.0f)
            projection.scale(1.0f, aspectRatio)
        } else {
            modelMatrix.setHeight(2.0f)
            projection.scale(1.0f / aspectRatio, 1.0f)
        }
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

    /** 想要的动作组（volatile 跨线程传意图，GL 线程下一帧取用） */
    @Volatile
    private var wantGroup: String? = null

    /** 主线程/VM 调：切换通话状态对应的动作 */
    fun requestState(group: String?) {
        wantGroup = group
    }

    /** GL 线程每帧检查有没有新意图 */
    fun applyPendingState() {
        val g = wantGroup ?: return
        wantGroup = null
        if (g.isNotEmpty()) playGroup(motionCandidates(g), PRIORITY_NORMAL)
    }

    /** 主线程调：AI 播放电平（0..1） */
    fun setMouthLevel(level: Float) = mouth.setTarget(level)

    /** 挂断：释放 moc/model/渲染器。语义同官方 delegate.onDestroy */
    fun destroy() {
        ready = false
        runCatching { delete() }
        setting = null
        motions.clear()
        motionGroups.clear()
        lipSyncIds.clear()
        textureIds.clear()
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
        private const val THINK_GROUP = "think"
        private const val SPEAK_GROUP = "speak"
        private const val LISTEN_GROUP = "listen"

        /** 官方包里常见的待机组名（首字母大写最常见，全小写与带序号的都试） */
        private val IDLE_ALIASES = listOf("Idle", "idle", "Idle_01", "motion", "Motion")

        /** 优先级同官方 LAppDefine.Priority：IDLE=1 < NORMAL=2 < FORCE=3 */
        private const val PRIORITY_IDLE = 1
        private const val PRIORITY_NORMAL = 2
        private const val PRIORITY_FORCE = 3

        /** 通话状态 → 期望的动作组名（找不到会回退待机，见 [motionCandidates]） */
        fun groupFor(state: CallVisualState): String = when (state) {
            CallVisualState.SPEAKING -> SPEAK_GROUP
            CallVisualState.THINKING -> THINK_GROUP
            CallVisualState.LISTENING -> LISTEN_GROUP
            else -> IDLE_GROUP
        }
    }
}
