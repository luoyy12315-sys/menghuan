package com.menghuan.app

import com.menghuan.app.data.CallVisualAsset
import com.menghuan.app.data.CallVisualManifestCodec
import com.menghuan.app.data.CallVisualSlots
import com.menghuan.app.data.CallVisualStatus
import com.menghuan.app.data.CallVisualType
import com.menghuan.app.data.CallVisualStore
import com.menghuan.app.data.Live2dPackageCheck
import com.menghuan.app.data.ZipEntryGuard
import com.menghuan.app.realtime.CallAudioLevel
import com.menghuan.app.realtime.RealtimeCallState
import com.menghuan.app.realtime.visual.CallVisualState
import com.menghuan.app.realtime.visual.VideoStateResolver
import com.menghuan.app.realtime.visual.toVisualState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频通话（v0.58.0）的纯函数单测 —— 对应方案文档 §三十的 30.1 / 30.2 / 30.3 / 30.4。
 *
 * 只测纯函数是有意的：状态映射、素材回退、manifest 编解码、包校验、路径越界、电平门限，
 * 这些是"通话会不会出怪事"的真正决策点，而且 JVM 就能跑。
 * ExoPlayer / GLSurfaceView 那两层要真机（方案文档 §三十一）。
 */
class CallVisualTest {

    // ---- 30.4 状态机 ----

    @Test
    fun `通话状态到视觉状态的映射守着两个同名不同义的坑`() {
        // 引擎的 LISTENING = "线路通了还没人说话"，视觉的 LISTENING = "用户正在讲话"
        assertEquals(CallVisualState.IDLE, RealtimeCallState.LISTENING.toVisualState())
        assertEquals(CallVisualState.LISTENING, RealtimeCallState.USER_SPEAKING.toVisualState())
        assertEquals(CallVisualState.THINKING, RealtimeCallState.AI_THINKING.toVisualState())
        // SPEAKING 只在 AudioTrack 真出声之后（引擎那边保证了这一点）
        assertEquals(CallVisualState.SPEAKING, RealtimeCallState.AI_SPEAKING.toVisualState())
        // 打断：立刻从 speaking 出去 → 倾听
        assertEquals(CallVisualState.LISTENING, RealtimeCallState.INTERRUPTED.toVisualState())
        assertEquals(CallVisualState.ENDED, RealtimeCallState.ENDED.toVisualState())
        assertEquals(CallVisualState.ERROR, RealtimeCallState.ERROR.toVisualState())
        assertEquals(CallVisualState.IDLE, RealtimeCallState.CONNECTING.toVisualState())
        assertEquals(CallVisualState.IDLE, RealtimeCallState.DISCONNECTING.toVisualState())
    }

    @Test
    fun `六态齐全不许漏`() {
        assertEquals(6, CallVisualState.entries.size)
    }

    // ---- 30.3 伪视频素材回退 ----

    @Test
    fun `只有一条待机也必须可用`() {
        val bound = mapOf(CallVisualSlots.IDLE to "idle.mp4")
        assertEquals("idle.mp4", VideoStateResolver.pick(bound, CallVisualState.SPEAKING))
        assertEquals("idle.mp4", VideoStateResolver.pick(bound, CallVisualState.LISTENING))
        assertEquals("idle.mp4", VideoStateResolver.pick(bound, CallVisualState.THINKING))
        assertEquals("idle.mp4", VideoStateResolver.pick(bound, CallVisualState.IDLE))
    }

    @Test
    fun `有精确状态素材就用它，缺的回退待机`() {
        val bound = mapOf(
            CallVisualSlots.IDLE to "idle.mp4",
            CallVisualSlots.SPEAKING to "speaking.mp4",
        )
        assertEquals("speaking.mp4", VideoStateResolver.pick(bound, CallVisualState.SPEAKING))
        assertEquals("idle.mp4", VideoStateResolver.pick(bound, CallVisualState.LISTENING))
    }

    @Test
    fun `挂断与出错都落回待机而不是冻在说话`() {
        val bound = mapOf(
            CallVisualSlots.IDLE to "idle.mp4",
            CallVisualSlots.SPEAKING to "speaking.mp4",
        )
        assertEquals("idle.mp4", VideoStateResolver.pick(bound, CallVisualState.ENDED))
        assertEquals("idle.mp4", VideoStateResolver.pick(bound, CallVisualState.ERROR))
    }

    @Test
    fun `连待机都没有就不显示形象而不是报错`() {
        val bound = mapOf(CallVisualSlots.SPEAKING to "speaking.mp4")
        assertNull(VideoStateResolver.pick(bound, CallVisualState.LISTENING))
        assertEquals("speaking.mp4", VideoStateResolver.pick(bound, CallVisualState.SPEAKING))
    }

    // ---- 30.1 配置持久化 ----

    @Test
    fun `manifest 编解码来回一致`() {
        val asset = CallVisualAsset(
            type = CallVisualType.VIDEO,
            status = CallVisualStatus.READY,
            videoStates = mapOf(
                CallVisualSlots.IDLE to "a.mp4",
                CallVisualSlots.SPEAKING to "b.mp4",
            ),
            live2dModelJson = "",
            createdAt = 111L,
            updatedAt = 222L,
        )
        val back = CallVisualManifestCodec.decode(CallVisualManifestCodec.encode(asset))
        assertEquals(asset.type, back.type)
        assertEquals(asset.status, back.status)
        assertEquals(asset.videoStates, back.videoStates)
        assertEquals(111L, back.createdAt)
        assertEquals(222L, back.updatedAt)
    }

    @Test
    fun `素材包只写 default 时当成待机读进来`() {
        val back = CallVisualManifestCodec.decode("""{"version":1,"type":"VIDEO","status":"READY","default":"loop.mp4"}""")
        assertEquals("loop.mp4", back.videoStates[CallVisualSlots.IDLE])
    }

    @Test
    fun `坏 JSON 与空文件都当没有形象，不许抛`() {
        assertEquals(CallVisualAsset.EMPTY, CallVisualManifestCodec.decode(null))
        assertEquals(CallVisualAsset.EMPTY, CallVisualManifestCodec.decode(""))
        assertEquals(CallVisualAsset.EMPTY, CallVisualManifestCodec.decode("{ 这不是 JSON"))
        assertEquals(CallVisualAsset.EMPTY, CallVisualManifestCodec.decode("""{"type":"MARS"}"""))
        assertFalse(CallVisualAsset.EMPTY.isUsable)
    }

    @Test
    fun `旧人设没配形象时类型是 NONE 且不可用`() {
        val legacy = CallVisualAsset()
        assertEquals(CallVisualType.NONE, legacy.type)
        assertFalse(legacy.isUsable)
        // 脏字符串一律收敛到 NONE（列里存的值来自数据库，可能被人手改过）
        assertEquals(CallVisualType.NONE, CallVisualType.fromName("3D"))
        assertEquals(CallVisualType.NONE, CallVisualType.fromName(null))
    }

    @Test
    fun `可用判定要状态 READY 且真有素材`() {
        assertFalse(
            CallVisualAsset(type = CallVisualType.VIDEO, status = CallVisualStatus.READY).isUsable,
        )
        assertTrue(
            CallVisualAsset(
                type = CallVisualType.VIDEO,
                status = CallVisualStatus.READY,
                videoStates = mapOf(CallVisualSlots.IDLE to "a.mp4"),
            ).isUsable,
        )
        // IMPORTING 中途不许被当成可用（绑定是导入成功后一次性提交的）
        assertFalse(
            CallVisualAsset(
                type = CallVisualType.LIVE2D,
                status = CallVisualStatus.IMPORTING,
                live2dModelJson = "x.model3.json",
            ).isUsable,
        )
    }

    // ---- 30.2 Live2D 包校验与路径穿越 ----

    private val paths = listOf(
        "Haru/Haru.model3.json",
        "Haru/Haru.moc3",
        "Haru/Haru.1024/texture_00.png",
        "Haru/motions/mtn_01.motion3.json",
        "Haru/physics_v2/v1.0.0_haru.physics3.json",
    )

    private val modelJson = """
        {
          "Version": 4,
          "FileReferences": {
            "Moc": "Haru.moc3",
            "Textures": ["Haru.1024/texture_00.png"],
            "Physics": "physics_v2/v1.0.0_haru.physics3.json",
            "Motions": { "Idle": [ { "File": "motions/mtn_01.motion3.json" } ] }
          }
        }
    """.trimIndent()

    @Test
    fun `合法模型包通过校验`() {
        val r = Live2dPackageCheck.validate("Haru/Haru.model3.json", modelJson, paths.toSet())
        assertTrue("$r", r is Live2dPackageCheck.Ok)
    }

    @Test
    fun `缺 moc 与缺贴图都要拒绝`() {
        val noMoc = paths.filter { !it.endsWith(".moc3") }
        assertTrue(
            Live2dPackageCheck.validate("Haru/Haru.model3.json", modelJson, noMoc.toSet())
                is Live2dPackageCheck.Invalid,
        )
        val noTex = paths.filter { !it.endsWith(".png") }
        assertTrue(
            Live2dPackageCheck.validate("Haru/Haru.model3.json", modelJson, noTex.toSet())
                is Live2dPackageCheck.Invalid,
        )
    }

    @Test
    fun `引用越出包外一律拒绝`() {
        val escaping = modelJson.replace("\"Moc\": \"Haru.moc3\"", "\"Moc\": \"../../../system/lib.so\"")
        assertTrue(
            Live2dPackageCheck.validate("Haru/Haru.model3.json", escaping, paths.toSet())
                is Live2dPackageCheck.Invalid,
        )
    }

    @Test
    fun `坏 JSON 与没有 model3 入口都拒绝`() {
        assertTrue(
            Live2dPackageCheck.validate("Haru/Haru.model3.json", "{坏了", paths.toSet())
                is Live2dPackageCheck.Invalid,
        )
        assertNull(Live2dPackageCheck.locateModelJson(listOf("a.png", "b.moc3")))
        // 多个模型时取最浅的那个，且忽略 __MACOSX 这类打包噪音
        assertEquals(
            "root.model3.json",
            Live2dPackageCheck.locateModelJson(
                listOf("nested/deep/x.model3.json", "__MACOSX/root.model3.json", "root.model3.json"),
            ),
        )
    }

    @Test
    fun `zip 条目名防穿越`() {
        assertEquals("a/b.png", ZipEntryGuard.safeRelativePath("a/b.png"))
        assertEquals("b.png", ZipEntryGuard.safeRelativePath("./b.png"))
        assertNull(ZipEntryGuard.safeRelativePath("../evil.txt"))
        assertNull(ZipEntryGuard.safeRelativePath("a/../../evil.txt"))
        assertNull(ZipEntryGuard.safeRelativePath("/absolute/evil.txt"))
        assertNull(ZipEntryGuard.safeRelativePath("C:/Windows/evil.txt"))
        assertNull(ZipEntryGuard.safeRelativePath(""))
    }

    // ---- 30.2v2 导入进度与扩展名兜底（v0.82.0 P2-1）----

    @Test
    fun `扩展名兜底只认 zip，大小写不敏感`() {
        assertTrue(CallVisualStore.looksLikeZip("model.zip"))
        assertTrue(CallVisualStore.looksLikeZip("免费模型—奶蛙.ZIP"))
        assertTrue(CallVisualStore.looksLikeZip("  a.zip  "))
        // 关键的两条误判：`.zipx` 与压缩包之外的格式都不许放行
        assertFalse(CallVisualStore.looksLikeZip("model.zipx"))
        assertFalse(CallVisualStore.looksLikeZip("model.rar"))
        assertFalse(CallVisualStore.looksLikeZip("model.7z"))
        assertFalse(CallVisualStore.looksLikeZip("model"))
        assertFalse(CallVisualStore.looksLikeZip("   "))
    }

    @Test
    fun `进度文案与条数只在有分母时才有读数`() {
        // 读取压缩包：服务商给得回总长 → 有百分比、条能画出来
        val reading = CallVisualStore.ImportProgress.Reading(5242880L, 10485760L)
        assertEquals("正在读取压缩包 50%…", reading.label())
        assertEquals(0.5f, reading.fraction()!!, 1e-4f)
        // 问不出总长（老 provider 常见）：只给文字，不画条
        val unknown = CallVisualStore.ImportProgress.Reading(1024L, -1L)
        assertEquals("正在读取压缩包…", unknown.label())
        assertNull(unknown.fraction())

        val unzipping = CallVisualStore.ImportProgress.Unzipping(3, 10)
        assertEquals("正在解压 3/10 项…", unzipping.label())
        assertEquals(0.3f, unzipping.fraction()!!, 1e-4f)

        // 校验与落盘都是一瞬间：只出字、不画条（闪一下比没有更糟）
        assertEquals("正在校验模型包…", CallVisualStore.ImportProgress.Verifying.label())
        assertNull(CallVisualStore.ImportProgress.Verifying.fraction())
        assertEquals("正在落盘…", CallVisualStore.ImportProgress.Moving.label())
        assertNull(CallVisualStore.ImportProgress.Moving.fraction())
    }

    // ---- 30.2 口型电平 ----

    @Test
    fun `静音与停顿不张嘴`() {
        val silence = ShortArray(512)
        assertEquals(0f, CallAudioLevel.toMouth(CallAudioLevel.rms(silence, 0, silence.size)), 1e-4f)
    }

    @Test
    fun `电平随振幅单调上升且不超上限`() {
        var last = -1f
        for (amp in intArrayOf(0, 200, 2000, 8000, 20000, 32000)) {
            val s = ShortArray(512) { amp.toShort() }
            val mouth = CallAudioLevel.toMouth(CallAudioLevel.rms(s, 0, s.size))
            assertTrue("振幅 $amp 的开口 $mouth 比上一档 $last 还小", mouth >= last)
            assertTrue("开口超过上限：$mouth", mouth <= CallAudioLevel.CEILING + 1e-4f)
            last = mouth
        }
    }

    @Test
    fun `门限以下直接归零免得含混不清地嚼`() {
        val tiny = ShortArray(512) { 300 }   // RMS ≈ 0.009，远低于门限
        assertTrue(CallAudioLevel.rms(tiny, 0, tiny.size) < CallAudioLevel.GATE)
        assertEquals(0f, CallAudioLevel.toMouth(CallAudioLevel.rms(tiny, 0, tiny.size)), 0f)
    }

    @Test
    fun `平滑向上快向下慢`() {
        // 比的是"一步走了多少"：张嘴一步走 45%，闭嘴一步只走 18%
        val up = CallAudioLevel.smoothStep(0f, 1f) - 0f
        val down = 1f - CallAudioLevel.smoothStep(1f, 0f)
        assertTrue("张嘴应当比闭嘴快：up=$up down=$down", up > down)
        assertEquals(0f, CallAudioLevel.smoothStep(0f, 0f), 0f)
        assertEquals(1f, CallAudioLevel.smoothStep(1f, 1f), 0f)
    }
}
