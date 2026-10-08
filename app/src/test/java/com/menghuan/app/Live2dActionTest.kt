package com.menghuan.app

import com.menghuan.app.data.AiActionGenerator
import com.menghuan.app.data.Live2dSpaceStore
import com.menghuan.app.data.model.Live2dActionMapper
import com.menghuan.app.live2d.ActionCodec
import com.menghuan.app.live2d.ActionBlender
import com.menghuan.app.live2d.ActionComposer
import com.menghuan.app.live2d.ActionPart
import com.menghuan.app.live2d.ActionScale
import com.menghuan.app.live2d.ActionSlot
import com.menghuan.app.live2d.ActionSource
import com.menghuan.app.live2d.ActionType
import com.menghuan.app.live2d.BuiltinActions
import com.menghuan.app.live2d.GenericChannel
import com.menghuan.app.live2d.InteractionBindings
import com.menghuan.app.live2d.InteractionRegion
import com.menghuan.app.live2d.InteractionRules
import com.menghuan.app.live2d.Live2dAction
import com.menghuan.app.live2d.ModelCapability
import com.menghuan.app.live2d.ModelCompatibility
import com.menghuan.app.live2d.ParameterTrack
import com.menghuan.app.live2d.StagePose
import com.menghuan.app.live2d.TrackEasing
import com.menghuan.app.live2d.TrackKey
import com.menghuan.app.live2d.fillNdcMatrix
import com.menghuan.app.realtime.RealtimeCallState
import com.menghuan.app.realtime.visual.CallVisualState
import com.menghuan.app.realtime.visual.toActionSlot
import com.menghuan.app.realtime.visual.toVisualState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Live2D 动作系统（v0.64.0）的纯函数单测。
 *
 * 只测纯函数是有意的（与 `CallVisualTest` 同一个口径）：通道映射、轨道求值、
 * 动作编解码、能力探测与降级、互动分区、拼装器、AI 输出解析 —— 这些才是
 * "动作会不会播歪、会不会崩"的真正决策点，而且 JVM 就能跑。
 *
 * v0.67.0 起把两件原本"只能上真机看"的事也抽成了纯函数、纳进来了：
 * [ActionBlender]（换动作/停动作/循环接缝的承接）与 [fillNdcMatrix]（舞台姿态的矩阵）。
 * 这两处错了的表现恰好是"说不清但一眼看得出"——弹一下、绕着屏幕转圈，
 * 而真机上没人能凭肉眼判断是哪一帧哪个数错，所以必须在 JVM 上钉住。
 *
 * 剩下真正要 GPU 的那一层（每帧往 `CubismModel` 里写参数）在真机上验，不写假测试充数。
 */
class Live2dActionTest {

    // ---------------------------------------------------------------
    // 通道映射
    // ---------------------------------------------------------------

    @Test
    fun `每个通道的落盘 id 唯一而且能反查回来`() {
        val ids = GenericChannel.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        GenericChannel.entries.forEach { ch ->
            assertEquals(ch, GenericChannel.byId(ch.id))
        }
        assertNull(GenericChannel.byId("GENERIC_NOT_EXIST"))
        assertNull(GenericChannel.byId(null))
    }

    @Test
    fun `舞台通道不依赖任何模型参数`() {
        // 这是"翻跟斗在任何模型上都演得出来"的前提：能力表空到什么都没有，
        // 舞台通道也必须判为可用
        val empty = ModelCapability.EMPTY
        GenericChannel.stageChannels.forEach { ch ->
            assertTrue("${ch.id} 应该不依赖参数", empty.supports(ch))
        }
        // 参数通道则相反
        assertFalse(empty.supports(GenericChannel.HEAD_X))
    }

    // ---------------------------------------------------------------
    // 轨道求值
    // ---------------------------------------------------------------

    @Test
    fun `轨道求值覆盖首尾与中间插值`() {
        val track = ParameterTrack.of(
            GenericChannel.HEAD_Z,
            TrackEasing.LINEAR,
            0f to 0f, 0.5f to 1f, 1f to 0f,
        )
        assertEquals(0f, track.valueAt(0f), 1e-4f)
        assertEquals(1f, track.valueAt(0.5f), 1e-4f)
        assertEquals(0f, track.valueAt(1f), 1e-4f)
        // 线性：中点应该是 0.5
        assertEquals(0.5f, track.valueAt(0.25f), 1e-4f)
        // 越界一律夹到首尾（不做环绕）
        assertEquals(0f, track.valueAt(-1f), 1e-4f)
        assertEquals(0f, track.valueAt(2f), 1e-4f)
    }

    @Test
    fun `关键帧乱序会自己排好，同进度重复帧取后者`() {
        val track = ParameterTrack.of(
            GenericChannel.HEAD_X,
            TrackEasing.LINEAR,
            1f to 0f, 0f to 0f, 0.5f to 0.4f, 0.5f to 0.8f,
        )
        assertEquals(listOf(0f, 0.5f, 1f), track.keys.map { it.at })
        // 重复的那一帧留下的是后写的 0.8
        assertEquals(0.8f, track.keys[1].value, 1e-4f)
    }

    @Test
    fun `IN_OUT 缓动两头慢中间快`() {
        val track = ParameterTrack.of(GenericChannel.HEAD_Y, TrackEasing.IN_OUT, 0f to 0f, 1f to 1f)
        // smoothstep 在 1/4 处小于线性值、3/4 处大于线性值
        assertTrue(track.valueAt(0.25f) < 0.25f)
        assertTrue(track.valueAt(0.75f) > 0.75f)
        // 但中点是同一个
        assertEquals(0.5f, track.valueAt(0.5f), 1e-4f)
    }

    // ---------------------------------------------------------------
    // 动作编解码
    // ---------------------------------------------------------------

    @Test
    fun `动作编解码来回一趟不丢东西`() {
        val src = BuiltinActions.wave()
        val text = ActionCodec.encode(src)
        val back = ActionCodec.decode(text)

        assertEquals(src.tracks.size, back.tracks.size)
        assertEquals(src.safeAmplitude, back.amplitude, 1e-3f)
        assertEquals(src.safeSpeed, back.speed, 1e-3f)
        assertEquals(src.safeFadeInMs, back.fadeInMs)
        assertEquals(src.safeFadeOutMs, back.fadeOutMs)
        assertEquals(src.compatibility.required, back.compatibility.required)
        src.tracks.zip(back.tracks).forEach { (a, b) ->
            assertEquals(a.channel, b.channel)
            assertEquals(a.keys.size, b.keys.size)
        }
        assertEquals(src.tracks.size, ActionCodec.trackCount(text))
    }

    @Test
    fun `编解码对垃圾输入不抛异常`() {
        assertEquals(0, ActionCodec.decode(null).tracks.size)
        assertEquals(0, ActionCodec.decode("").tracks.size)
        assertEquals(0, ActionCodec.decode("not json").tracks.size)
        assertEquals(0, ActionCodec.decode("{\"tracks\":\"oops\"}").tracks.size)
        // 认不出来的通道整条丢掉，别的照常读
        val partial = ActionCodec.decode(
            """{"tracks":[{"ch":"GENERIC_NOT_EXIST","keys":[]},{"ch":"GENERIC_HEAD_X","keys":[{"t":0,"x":0},{"t":1,"x":1}]}]}""",
        )
        assertEquals(1, partial.tracks.size)
        assertEquals(GenericChannel.HEAD_X, partial.tracks[0].channel)
    }

    @Test
    fun `实体映射保留 id 收藏与时间`() {
        val action = BuiltinActions.flip().copy(id = 42L, favorite = true, createdAt = 1000L)
        val entity = Live2dActionMapper.toEntity(action, now = 2000L)
        assertEquals(42L, entity.id)
        assertTrue(entity.favorite)
        assertEquals(1000L, entity.createdAt)
        assertEquals(2000L, entity.updatedAt)
        assertEquals(ActionSource.BUILTIN.name, entity.source)

        val back = Live2dActionMapper.toAction(entity)
        assertEquals(action.name, back.name)
        assertEquals(action.tracks.size, back.tracks.size)
        assertTrue(back.favorite)
        assertEquals(action.safeFadeInMs, back.fadeInMs)
    }

    // ---------------------------------------------------------------
    // 内置动作
    // ---------------------------------------------------------------

    @Test
    fun `内置动作齐全且不写张嘴`() {
        val all = BuiltinActions.all()
        // 四个状态默认 + 四个趣味动作（摆手 / 摇头 / 劈叉 / 翻跟斗）
        assertEquals(8, all.size)
        ActionSlot.entries.forEach { slot ->
            assertNotNull(BuiltinActions.byKey(BuiltinActions.defaultKeyOf(slot)))
        }
        listOf(BuiltinActions.WAVE, BuiltinActions.SHAKE, BuiltinActions.SPLIT, BuiltinActions.FLIP)
            .forEach { assertNotNull("缺少 $it", BuiltinActions.byKey(it)) }

        // ⚠️ 张嘴（GENERIC_MOUTH）归实时语音的口型，任何内置动作都不许碰它 ——
        // 碰了就会出现"AI 没出声但角色一直张着嘴"
        all.forEach { action ->
            assertFalse(action.name + " 不该写张嘴", action.tracks.any { it.channel == GenericChannel.MOUTH })
        }
    }

    @Test
    fun `四态默认动作是循环的，趣味动作是一次性的`() {
        ActionSlot.entries.forEach { assertTrue(BuiltinActions.stateDefault(it).loop) }
        BuiltinActions.funActions().forEach { assertFalse(it.name, it.loop) }
    }

    @Test
    fun `一次性的趣味动作结尾都收回到接近零`() {
        BuiltinActions.funActions().forEach { action ->
            action.tracks.forEach { track ->
                val last = track.valueAt(1f)
                if (track.channel == GenericChannel.STAGE_ROT) {
                    // 整体旋转的"收回"是**转满整圈**（2.0 = 360°），视觉上就是回到直立姿态；
                    // 强行要求它回 0 等于"翻到一半倒着回去"，那才是真的怪。
                    val turns = last / 2f
                    assertTrue(
                        "${action.name} 的整体旋转没停在整圈上：$last",
                        abs(turns - Math.round(turns)) < 1e-3f,
                    )
                } else {
                    assertTrue(
                        "${action.name} 的 ${track.channel.id} 结尾没收回：$last",
                        abs(last) < 0.05f,
                    )
                }
            }
        }
    }

    @Test
    fun `翻跟斗靠舞台旋转转满一圈`() {
        val flip = BuiltinActions.flip()
        val rot = flip.tracks.first { it.channel == GenericChannel.STAGE_ROT }
        // 2.0 × 180° = 360°，并且它一个模型参数都不需要
        assertEquals(0f, rot.valueAt(0f), 1e-4f)
        assertEquals(2f, rot.valueAt(1f), 1e-4f)
        assertEquals(360f, rot.valueAt(1f) * GenericChannel.STAGE_ROT.stageUnit, 1f)
        assertTrue(flip.compatibility.required.isEmpty())
    }

    @Test
    fun `劈叉把身体侧倾列为必需通道`() {
        val split = BuiltinActions.split()
        assertEquals(setOf(GenericChannel.BODY_Z), split.compatibility.required)
    }

    // ---------------------------------------------------------------
    // 能力探测与降级（用户第 5 / 11.16 条）
    // ---------------------------------------------------------------

    @Test
    fun `只有标准参数齐全的模型才被认成全部支持`() {
        val allIds = GenericChannel.paramChannels.flatMap { it.paramIds }.toSet()
        val cap = ModelCapability.detect { it in allIds }
        assertEquals(GenericChannel.paramChannels.size, cap.paramChannelCount)
        assertTrue(cap.canRun(ModelCompatibility(required = setOf(GenericChannel.HEAD_X))))
        assertEquals(1f, cap.confidence(listOf(GenericChannel.HEAD_X, GenericChannel.ARM_R)), 1e-4f)
    }

    @Test
    fun `什么都没有的模型不会崩，只是动作幅度被压低`() {
        // 这是"不能因为某个模型缺参数就崩"的最小复现：一个参数都不存在
        val cap = ModelCapability.detect { false }
        val headOnly = ModelCompatibility(required = setOf(GenericChannel.HEAD_X))
        assertFalse(cap.supports(GenericChannel.HEAD_X))
        assertFalse(cap.canRun(headOnly))
        // 缺通道时幅度压到下限（0.55），但仍然"能跑"那些不缺的部分
        assertEquals(0.55f, cap.amplitudeScale(headOnly), 1e-4f)
        // 一个参数都没有的模型上，只靠舞台通道的动作依然能跑
        assertTrue(cap.canRun(BuiltinActions.flip().compatibility))
    }

    @Test
    fun `缺一半必需通道时幅度介于两者之间`() {
        val cap = ModelCapability.detect { it == "ParamAngleX" }
        val req = listOf(GenericChannel.HEAD_X, GenericChannel.BODY_Z)
        assertEquals(0.5f, cap.confidence(req), 1e-4f)
        assertEquals(0.55f + 0.45f * 0.5f, cap.amplitudeScale(req), 1e-4f)
    }

    @Test
    fun `老式大写下划线命名的参数也能认出来`() {
        val cap = ModelCapability.detect { it == "PARAM_ANGLE_X" }
        assertTrue(cap.supports(GenericChannel.HEAD_X))
    }

    // ---------------------------------------------------------------
    // 动作强度：三层相乘
    // ---------------------------------------------------------------

    @Test
    fun `强度与能力系数相乘，越界的强度被夹回来`() {
        val cap = ModelCapability.EMPTY
        val loud = BuiltinActions.wave().copy(amplitude = 9f)
        // 9 → 夹到 2.0；空能力表下 required 为空 → 系数 1.0
        assertEquals(2f, cap.applyScale(loud), 1e-4f)
        val quiet = BuiltinActions.split().copy(amplitude = 0.5f)
        // 劈叉要求 BODY_Z，空能力表下 confidence = 0 → 0.55
        assertEquals(0.5f * 0.55f, cap.applyScale(quiet), 1e-4f)
    }

    @Test
    fun `速度只改播放时长不改轨道值`() {
        val base = BuiltinActions.wave()
        val fast = base.copy(speed = 2f)
        assertEquals(base.safeDurationMs, fast.safeDurationMs)
        assertEquals(base.playDurationMs / 2, fast.playDurationMs)
    }

    // ---------------------------------------------------------------
    // 互动区域
    // ---------------------------------------------------------------

    @Test
    fun `HitArea 判定头优先于身体`() {
        // 很多模型的身体 HitArea 会把脑袋一起圈住，先判身体的话点脸也会算成点身体
        assertEquals(InteractionRegion.HEAD, InteractionRules.fromHitAreas(headHit = true, bodyHit = true))
        assertEquals(InteractionRegion.BODY, InteractionRules.fromHitAreas(headHit = false, bodyHit = true))
        assertEquals(InteractionRegion.OTHER, InteractionRules.fromHitAreas(headHit = false, bodyHit = false))
    }

    @Test
    fun `几何兜底按高度分三段，点到外面算其他`() {
        assertEquals(InteractionRegion.HEAD, InteractionRules.fromBands(0.1f))
        assertEquals(InteractionRegion.BODY, InteractionRules.fromBands(0.5f))
        assertEquals(InteractionRegion.OTHER, InteractionRules.fromBands(0.9f))
        // 没点在角色包围盒里
        assertEquals(InteractionRegion.OTHER, InteractionRules.fromBands(null))
        assertEquals(InteractionRegion.OTHER, InteractionRules.fromBands(-0.2f))
        assertEquals(InteractionRegion.OTHER, InteractionRules.fromBands(1.4f))
    }

    @Test
    fun `互动默认值照用户给的例子`() {
        val b = InteractionBindings.DEFAULT
        assertEquals(BuiltinActions.SHAKE, b.builtinKeyOf(InteractionRegion.HEAD))
        assertEquals(BuiltinActions.WAVE, b.builtinKeyOf(InteractionRegion.BODY))
        assertNull(b.builtinKeyOf(InteractionRegion.OTHER))
        // 随机池取模不会越界，负数种子也不崩
        val pool = b.otherRandomKeys
        // 种子里有负数、也会重复取到同一个（取模必然如此），所以只断言"取到的都在池子里、且不为空"
        val picked = (-3..3).mapNotNull { b.randomKeyFor(it.toLong()) }
        assertEquals(7, picked.size)
        assertTrue(picked.all { it in pool })
        assertTrue(pool.contains(b.randomKeyFor(987654321L)))
    }

    @Test
    fun `区域键名大小写不敏感地认回来`() {
        assertEquals(InteractionRegion.HEAD, InteractionRegion.fromKey("head"))
        assertEquals(InteractionRegion.BODY, InteractionRegion.fromKey("BODY"))
        assertEquals(InteractionRegion.OTHER, InteractionRegion.fromKey("nonsense"))
        assertEquals(InteractionRegion.OTHER, InteractionRegion.fromKey(null))
    }

    // ---------------------------------------------------------------
    // 通话状态 → 动作槽（同名不同义的坑）
    // ---------------------------------------------------------------

    @Test
    fun `通话状态到动作槽的映射是字面的，不走引擎那套错位`() {
        // 引擎状态 → 视觉状态：LISTENING 在这里是"线路通了"，要映射成 IDLE
        assertEquals(CallVisualState.IDLE, RealtimeCallState.LISTENING.toVisualState())
        // 而视觉状态 → 动作槽是字面的：视觉的 LISTENING 就是"用户在说话"，角色该在倾听
        assertEquals(ActionSlot.LISTENING, CallVisualState.LISTENING.toActionSlot())
        assertEquals(ActionSlot.SPEAKING, CallVisualState.SPEAKING.toActionSlot())
        assertEquals(ActionSlot.THINKING, CallVisualState.THINKING.toActionSlot())
        assertEquals(ActionSlot.IDLE, CallVisualState.IDLE.toActionSlot())
        // 挂断与出错一律回待机：不该摆着"惊讶"的脸结束通话
        assertEquals(ActionSlot.IDLE, CallVisualState.ENDED.toActionSlot())
        assertEquals(ActionSlot.IDLE, CallVisualState.ERROR.toActionSlot())
    }

    // ---------------------------------------------------------------
    // 拼装器
    // ---------------------------------------------------------------

    @Test
    fun `从零拼装按勾选的部分造轨道`() {
        val head = ActionComposer.compose(
            base = null, name = "点头", amplitude = 1f, speed = 1f,
            durationMs = 1500, parts = setOf(ActionPart.HEAD), expression = "",
        )
        assertTrue(head.tracks.isNotEmpty())
        assertTrue(head.tracks.all { ActionPart.of(it.channel) == ActionPart.HEAD })
        assertEquals(ActionSource.USER, head.source)
        assertEquals(0L, head.id)

        val both = ActionComposer.compose(
            base = null, name = "全身", amplitude = 1f, speed = 1f,
            durationMs = 1500, parts = setOf(ActionPart.HEAD, ActionPart.ARM), expression = "",
        )
        assertTrue(both.tracks.size > head.tracks.size)
        // 不含身体与整体
        assertFalse(both.tracks.any { ActionPart.of(it.channel) == ActionPart.BODY })
        assertFalse(both.tracks.any { it.channel.isStage })
    }

    @Test
    fun `基于内置动作调整时只筛不造，并且绝不会原地改内置那条`() {
        val base = BuiltinActions.wave()
        val edited = ActionComposer.compose(
            base = base, name = "我的摆手", amplitude = 1.4f, speed = 1.2f,
            durationMs = 3000, parts = setOf(ActionPart.HEAD, ActionPart.ARM), expression = "",
        )
        assertEquals(0L, edited.id)
        assertEquals(ActionSource.USER, edited.source)
        assertEquals("", edited.builtinKey)
        assertEquals(1.4f, edited.amplitude, 1e-4f)
        assertEquals(3000, edited.safeDurationMs)
        // 只剩头与手臂的轨道（摆手本来也没有身体与整体）
        assertTrue(edited.tracks.all { ActionPart.of(it.channel) in setOf(ActionPart.HEAD, ActionPart.ARM) })
        assertTrue(edited.note.contains(base.name))
    }

    @Test
    fun `关掉全部部分就得到一条空动作，UI 据此禁用保存`() {
        val empty = ActionComposer.compose(
            base = null, name = "空的", amplitude = 1f, speed = 1f,
            durationMs = 2000, parts = emptySet(), expression = "",
        )
        assertFalse(empty.hasTracks)
        assertTrue(empty.isEmpty)
    }

    @Test
    fun `partsOf 能读出基础动作真正动到哪些部分`() {
        // 摆手 = 手臂 + 头歪 + 身体轻摆（三条轨道都在），所以是三个部位
        assertEquals(
            setOf(ActionPart.HEAD, ActionPart.BODY, ActionPart.ARM),
            ActionComposer.partsOf(BuiltinActions.wave()),
        )
        assertEquals(
            setOf(ActionPart.BODY, ActionPart.ARM, ActionPart.HEAD, ActionPart.STAGE),
            ActionComposer.partsOf(BuiltinActions.split()),
        )
    }

    // ---------------------------------------------------------------
    // AI 生成
    // ---------------------------------------------------------------

    @Test
    fun `提示词里的通道清单与代码一一对应`() {
        val prompt = AiActionGenerator.systemPrompt(ModelCapability.EMPTY)
        GenericChannel.entries.forEach { ch ->
            assertTrue("提示词缺 ${ch.id}", prompt.contains(ch.id))
        }
    }

    @Test
    fun `解析能吃下带围栏与前后废话的输出`() {
        val raw = """
            好的，这是你要的动作：
            ```json
            {
              "name": "开心挥手",
              "durationMs": 2400,
              "loop": false,
              "tracks": [
                {"channel": "GENERIC_ARM_R", "keys": [{"at":0,"value":0},{"at":0.3,"value":0.8},{"at":1,"value":0}]},
                {"channel": "ARM_L", "keys": [{"at":0,"value":0},{"at":1,"value":0.3}]}
              ]
            }
            ```
            希望你喜欢！
        """.trimIndent()
        val r = AiActionGenerator.parse(raw)
        assertNotNull(r)
        assertEquals("开心挥手", r!!.name)
        assertEquals(2400, r.durationMs)
        assertEquals(2, r.spec.tracks.size)
        // enum 名也能认出来（有些模型会照抄提示词里的短名）
        assertEquals(GenericChannel.ARM_L, r.spec.tracks[1].channel)
    }

    @Test
    fun `解析对畸形输出给出失败而不是空动作`() {
        assertNull(AiActionGenerator.parse(null))
        assertNull(AiActionGenerator.parse(""))
        assertNull(AiActionGenerator.parse("我做不到"))
        // 有 JSON 但一条轨道都没有 → 判失败（存一条不会动的动作比报错更糟）
        assertNull(AiActionGenerator.parse("""{"name":"x","durationMs":2000,"tracks":[]}"""))
        assertNull(
            AiActionGenerator.parse(
                """{"tracks":[{"channel":"GENERIC_HEAD_X","keys":[]}]}""",
            ),
        )
    }

    @Test
    fun `解析会把越界数值夹回来，舞台旋转允许到 2`() {
        val r = AiActionGenerator.parse(
            """
            {"name":"狂野","durationMs":999999,"tracks":[
              {"channel":"GENERIC_HEAD_X","keys":[{"at":-1,"value":50},{"at":9,"value":-50}]},
              {"channel":"GENERIC_STAGE_ROT","keys":[{"at":0,"value":0},{"at":1,"value":2}]}
            ]}
            """.trimIndent(),
        )
        assertNotNull(r)
        // 时长夹到上限
        assertEquals(20_000, r!!.durationMs)
        val head = r.spec.tracks.first { it.channel == GenericChannel.HEAD_X }
        assertTrue(head.keys.all { it.at in 0f..1f })
        assertTrue(head.keys.all { abs(it.value) <= 1.2f + 1e-4f })
        val rot = r.spec.tracks.first { it.channel == GenericChannel.STAGE_ROT }
        assertEquals(2f, rot.keys.last().value, 1e-4f)
    }

    @Test
    fun `解析补出的淡入淡出随时长走，不会是零`() {
        val r = AiActionGenerator.parse(
            """{"name":"小幅","durationMs":1200,"tracks":[{"channel":"GENERIC_HEAD_X","keys":[{"at":0,"value":0},{"at":1,"value":0.4}]}]}""",
        )!!
        assertTrue(r.fadeInMs in 120..500)
        assertTrue(r.fadeOutMs in 160..700)
        val long = AiActionGenerator.parse(
            """{"name":"长动作","durationMs":10000,"tracks":[{"channel":"GENERIC_HEAD_X","keys":[{"at":0,"value":0},{"at":1,"value":0.4}]}]}""",
        )!!
        assertTrue(long.fadeInMs >= r.fadeInMs)
    }

    // ---------------------------------------------------------------
    // 角色空间配置
    // ---------------------------------------------------------------

    @Test
    fun `背景配置认三种写法，脏值一律回落主题底色`() {
        val theme = Live2dSpaceStore.SpaceConfig()
        assertTrue(theme.isTheme)
        assertNull(theme.builtinIndex)
        assertNull(theme.fileName)

        val builtin = Live2dSpaceStore.SpaceConfig("builtin:2")
        assertEquals(2, builtin.builtinIndex)
        assertFalse(builtin.isTheme)
        // 越界下标不算数（否则会"选了背景但什么都没显示"）
        assertNull(Live2dSpaceStore.SpaceConfig("builtin:99").builtinIndex)
        assertNull(Live2dSpaceStore.SpaceConfig("builtin:abc").builtinIndex)

        val file = Live2dSpaceStore.SpaceConfig("file:bg.jpg")
        assertEquals("bg.jpg", file.fileName)
        assertNull(Live2dSpaceStore.SpaceConfig("file:").fileName)
    }

    /**
     * v0.65.0：背景在界面上只剩「系统预设 / 自定义背景」两档，
     * 判据就是 [Live2dSpaceStore.SpaceConfig.isCustom] —— **不是 `file:` 的全算预设**。
     *
     * 这条断言守的是"老用户升级后背景不许变成空白"：`theme` 与 `builtin:1..3` 这些老值
     * 都落在预设那一档，UI 不再给第六种选择。
     */
    @Test
    fun `v0650 只有带文件的背景算自定义，其余一律系统预设`() {
        assertFalse(Live2dSpaceStore.SpaceConfig().isCustom)                       // 老默认 theme
        assertFalse(Live2dSpaceStore.SpaceConfig("builtin:3").isCustom)             // 老的四档渐变
        assertFalse(Live2dSpaceStore.SpaceConfig(Live2dSpaceStore.BACKGROUND_PRESET).isCustom)
        assertFalse(Live2dSpaceStore.SpaceConfig("file:").isCustom)                 // 脏值回落预设
        assertTrue(Live2dSpaceStore.SpaceConfig("file:bg.jpg").isCustom)
        // 系统预设那一档存的就是 builtin:0（四档里第一张，老数据读出来仍然合法）
        assertEquals(0, Live2dSpaceStore.SpaceConfig(Live2dSpaceStore.BACKGROUND_PRESET).builtinIndex)
    }

    // ---------------------------------------------------------------
    // 杂项
    // ---------------------------------------------------------------

    @Test
    fun `StagePose 判空、非循环动作的时长算法`() {
        assertTrue(StagePose.NONE.isIdentity)
        assertFalse(StagePose(dx = 0.1f).isIdentity)
        val a = BuiltinActions.wave().copy(durationMs = 100)
        // 时长有下限：写 100ms 会被夹到 400ms（否则动作只剩抖一下）
        assertEquals(Live2dAction.MIN_DURATION_MS, a.safeDurationMs)
    }

    @Test
    fun `不可删与可删的边界`() {
        assertFalse(BuiltinActions.wave().deletable)
        assertFalse(
            BuiltinActions.wave().copy(source = ActionSource.MODEL, personaId = 3L).deletable,
        )
        assertTrue(BuiltinActions.wave().copy(source = ActionSource.USER).deletable)
        assertTrue(BuiltinActions.wave().copy(source = ActionSource.AI).deletable)
    }

    @Test
    fun `动作名与类型在 JSON 里可以被独立替换`() {
        val action = Live2dAction(
            name = "测试",
            type = ActionType.EXPRESSION,
            source = ActionSource.MODEL,
            personaId = 7L,
            expression = "smile",
            durationMs = 1800,
            compatibility = ModelCompatibility(required = setOf(GenericChannel.BODY_Z)),
        )
        val entity = Live2dActionMapper.toEntity(action, now = 1L)
        val back = Live2dActionMapper.toAction(entity)
        assertEquals(ActionType.EXPRESSION, back.type)
        assertEquals("smile", back.expression)
        assertEquals(7L, back.personaId)
        assertEquals(setOf(GenericChannel.BODY_Z), back.compatibility.required)
        assertEquals(ActionSource.MODEL, back.source)
    }

    @Test
    fun `TrackKey 落盘精度是两位小数`() {
        assertEquals(0.33f, TrackKey.round(0.33333f), 1e-4f)
        assertEquals(1f, TrackKey.round(1.004f), 1e-4f)
    }

    // ---------------------------------------------------------------
    // 承接层（v0.67.0）——"换动作啪一下"这一族的回归保护
    // ---------------------------------------------------------------

    @Test
    fun `换动作那一帧输出还是上一帧的值，不跳回静止位`() {
        val b = ActionBlender()
        // 旧动作把头摆在 +0.5
        b.beginFrame(); b.addTarget(GenericChannel.HEAD_Z, 0.5f); b.advance(16f); b.settle()
        assertEquals(0.5f, b.valueOf(GenericChannel.HEAD_Z), 1e-4f)

        // 换动作：新动作这一帧要写 -0.3
        b.startFade(200f)
        b.beginFrame(); b.addTarget(GenericChannel.HEAD_Z, -0.3f); b.advance(0f); b.settle()
        // 起点仍然是上一帧那一头（v0.64 的写法在这里会直接写 0 = 弹回中间）
        assertEquals(0.5f, b.valueOf(GenericChannel.HEAD_Z), 1e-4f)

        b.beginFrame(); b.addTarget(GenericChannel.HEAD_Z, -0.3f); b.advance(200f); b.settle()
        assertEquals(-0.3f, b.valueOf(GenericChannel.HEAD_Z), 1e-4f)
    }

    @Test
    fun `从静止起播与旧的淡入包络一模一样`() {
        val b = ActionBlender()
        b.startFade(100f)
        b.beginFrame(); b.addTarget(GenericChannel.HEAD_X, 1f); b.advance(50f); b.settle()
        // smoothstep(0.5) = 0.5 → 与旧实现 (elapsed / fadeIn) 在中点同值，
        // 差别只在两头（旧实现是直线，这里是缓动）—— 不许因为它改动"从静止起"的观感
        assertEquals(0.5f, b.valueOf(GenericChannel.HEAD_X), 1e-3f)
        b.beginFrame(); b.addTarget(GenericChannel.HEAD_X, 1f); b.advance(50f); b.settle()
        assertEquals(1f, b.valueOf(GenericChannel.HEAD_X), 1e-3f)
    }

    @Test
    fun `新动作没写的通道淡回静止，淡完就把控制权还给模型`() {
        val b = ActionBlender()
        b.beginFrame(); b.addTarget(GenericChannel.HEAD_X, 0.4f); b.advance(16f); b.settle()
        b.startFade(100f)
        // 新动作完全不写 HEAD_X
        b.beginFrame(); b.advance(50f); b.settle()
        assertTrue("淡到一半还得继续写，否则参数会被钉在静止位", b.owns(GenericChannel.HEAD_X))
        assertEquals(0.2f, b.valueOf(GenericChannel.HEAD_X), 1e-3f)

        b.beginFrame(); b.advance(50f); b.settle()
        assertEquals(0f, b.valueOf(GenericChannel.HEAD_X), 1e-4f)
        // ⚠️ 这一条是"播过一次摆手，角色自己的待机动作从此没了"的防线：
        // 淡完了还继续写 = 每帧把这个参数按在静止值上，模型自己的 motion 再也摆不动它
        assertFalse(b.owns(GenericChannel.HEAD_X))
        assertTrue(b.isSettled())
    }

    @Test
    fun `循环绕回起点那一刻被摊平，不硬跳一格`() {
        val b = ActionBlender()
        // 上一圈末尾摆在 0.8
        b.beginFrame(); b.addTarget(GenericChannel.EYE_BALL_X, 0.8f); b.advance(16f); b.settle()
        // 绕回起点：新圈首帧是 0.2（用户/AI 生成的动作里首尾不等是常态）
        b.startFade(120f)
        b.beginFrame(); b.addTarget(GenericChannel.EYE_BALL_X, 0.2f); b.advance(0f); b.settle()
        assertEquals(0.8f, b.valueOf(GenericChannel.EYE_BALL_X), 1e-4f)
        b.beginFrame(); b.addTarget(GenericChannel.EYE_BALL_X, 0.2f); b.advance(120f); b.settle()
        assertEquals(0.2f, b.valueOf(GenericChannel.EYE_BALL_X), 1e-4f)
    }

    @Test
    fun `旋转起点折到最近的那一圈，翻到 350 度不该倒着转回去`() {
        val b = ActionBlender(rotPeriod = 360f)
        assertEquals(-10f, b.snapToNearestTurn(350f, 360f), 1e-4f)
        assertEquals(0f, b.snapToNearestTurn(720f, 360f), 1e-4f)
        // 半圈是**真的**姿势差，不许被折成 0。
        // （落点正负号看 round-half-up：`roundToInt(0.5f)=1` 会把 +180 折成 -180 ——
        //  两者画面上是同一个姿势，这条要守的是"别折成 0"，不是符号）
        assertEquals(180f, abs(b.snapToNearestTurn(180f, 360f)), 1e-4f)
        assertEquals(45f, b.snapToNearestTurn(-315f, 360f), 1e-4f)
    }

    @Test
    fun `舞台姿态的淡出是把位移与旋转一起收干净`() {
        val b = ActionBlender(rotPeriod = 360f)
        b.beginFrame(); b.addStageTarget(0.3f, 0.1f, 200f, 0.2f); b.advance(16f); b.settle()
        assertEquals(StagePose(0.3f, 0.1f, 200f, 1.2f), b.stagePose())
        // 演完了：目标全空，开一段淡出
        b.startFade(100f)
        b.beginFrame(); b.advance(100f); b.settle()
        assertTrue(b.stagePose().isIdentity)
    }

    @Test
    fun `舞台缩放那道夹子不能丢`() {
        // 轨道值不设上限（翻跟斗的旋转要写 2.0），所以缩放必须在这里夹住：
        // 用户拼的动作或 AI 生成的垃圾值一旦把它推到 3 倍，角色会整只冲出屏幕，
        // 淡回来的那半秒里通话上根本看不见人。v0.64 的夹子在 ActionPlayer 里，
        // v0.67 把承接搬进 ActionBlender 时这道夹子必须跟着搬。
        val b = ActionBlender()
        b.beginFrame(); b.addStageTarget(0f, 0f, 0f, 5f); b.advance(16f); b.settle()
        assertEquals(ActionBlender.MAX_STAGE_SCALE, b.stagePose().scale, 1e-4f)
        b.beginFrame(); b.addStageTarget(0f, 0f, 0f, -5f); b.advance(16f); b.settle()
        assertEquals(ActionBlender.MIN_STAGE_SCALE, b.stagePose().scale, 1e-4f)
    }

    // ---------------------------------------------------------------
    // 舞台矩阵绕角色中心（v0.67.0）
    // ---------------------------------------------------------------

    @Test
    fun `旋转绕的是角色中心 —— 那一点在变换后纹丝不动`() {
        val pivotX = 0.2f
        val pivotY = 0.6f   // 分区布局里角色被抬到上半屏，中心就是这个位置
        val pose = StagePose(dx = 0f, dy = 0f, rotationDeg = 90f, scale = 1f)
        val a = FloatArray(16)
        pose.fillNdcMatrix(a, pivotX, pivotY)
        // 与 CubismMatrix44 同一套摆法：x' = m[0]*x + m[4]*y + m[12]
        val tx = a[0] * pivotX + a[4] * pivotY + a[12]
        val ty = a[1] * pivotX + a[5] * pivotY + a[13]
        assertEquals(pivotX, tx, 1e-5f)
        assertEquals(pivotY, ty, 1e-5f)
    }

    @Test
    fun `中心在原点时与旧实现（绕屏幕正中）逐元素相同`() {
        // 这条是"语音通话 / 不分区那条老路径行为一个字都没变"的回归保护
        val pose = StagePose(dx = 0.3f, dy = -0.15f, rotationDeg = 37f, scale = 1.25f)
        val a = FloatArray(16)
        pose.fillNdcMatrix(a, 0f, 0f)
        val rad = Math.toRadians(37.0)
        val cs = kotlin.math.cos(rad).toFloat() * 1.25f
        val sn = kotlin.math.sin(rad).toFloat() * 1.25f
        assertEquals(cs, a[0], 1e-5f)
        assertEquals(sn, a[1], 1e-5f)
        assertEquals(-sn, a[4], 1e-5f)
        assertEquals(cs, a[5], 1e-5f)
        assertEquals(0.6f, a[12], 1e-5f)   // dx × 2（NDC 横跨 2 个单位）
        assertEquals(-0.3f, a[13], 1e-5f)
        assertEquals(1f, a[10], 1e-5f)
        assertEquals(1f, a[15], 1e-5f)
    }

    @Test
    fun `只有位移的动作不受中心影响`() {
        val a = FloatArray(16)
        StagePose(dx = 0.25f, dy = 0.1f).fillNdcMatrix(a, 0f, 0.7f)
        assertEquals(0.5f, a[12], 1e-5f)
        assertEquals(0.2f, a[13], 1e-5f)
    }

    // ---------------------------------------------------------------
    // 动作幅度档位（v0.67.0）
    // ---------------------------------------------------------------

    @Test
    fun `倍率只改强度不改轨道值，而且绝不越过 MAX_AMPLITUDE`() {
        val base = BuiltinActions.wave()
        val tracksBefore = base.tracks.map { it.keys.map { k -> k.value } }
        val big = base.scaledBy(1.8f)
        assertEquals(tracksBefore, big.tracks.map { it.keys.map { k -> k.value } })
        assertEquals(1.8f, big.safeAmplitude, 1e-4f)
        // 已经很强的动作再乘一档，必须被那道夹子拦住（"任何模型都不该被甩到破图"）
        assertEquals(
            Live2dAction.MAX_AMPLITUDE,
            base.copy(amplitude = 1.5f).scaledBy(1.8f).safeAmplitude,
            1e-4f,
        )
        // 1.0 档原样返回：通话每帧都在拿动作，白复制一份数据类没道理
        assertTrue(base.scaledBy(1f) === base)
    }

    @Test
    fun `档位键认不出来就落回标准档`() {
        assertEquals(ActionScale.STANDARD, ActionScale.fromKey(null))
        assertEquals(ActionScale.STANDARD, ActionScale.fromKey("垃圾"))
        assertEquals(ActionScale.STANDARD, ActionScale.fromKey(ActionScale.STANDARD.key))
        assertEquals(1.4f, ActionScale.LARGE.multiplier, 1e-4f)
    }

    // ---------------------------------------------------------------
    // 四态默认的幅度（v0.67.0 "动作要看得出来"）
    // ---------------------------------------------------------------

    @Test
    fun `四态默认动作的峰值都在看得出来那一档`() {
        ActionSlot.entries.forEach { slot ->
            val action = BuiltinActions.stateDefault(slot)
            val peak = action.tracks.maxOf { t -> t.keys.maxOf { k -> abs(k.value) } }
            assertTrue(
                "${action.name} 峰值只有 $peak —— 又回到「看不见在动」那一档了",
                peak >= 0.18f,
            )
            // 也不能一路拉到顶格：那是"扯变形"那一档
            assertTrue("${action.name} 峰值过头：$peak", peak <= 0.6f)
        }
    }

    @Test
    fun `任何内置动作都不写睁眼通道`() {
        // 框架的 CubismEyeBlink 在"这一帧没有 motion 写参数"时是 setParameterValue **覆盖**眼睛，
        // 而免费模型没有 motion 文件 → 恰好每帧走覆盖那一路。写了 = 在这批模型上静默失效，
        // 而且换一张有 motion 的模型又突然生效，比一直不可见更难解释（见 BuiltinActions 文件头）。
        BuiltinActions.all().forEach { action ->
            assertFalse(
                "${action.name} 写了睁眼通道，它会被框架的眨眼覆盖掉",
                action.tracks.any { it.channel == GenericChannel.EYE },
            )
        }
    }
}
