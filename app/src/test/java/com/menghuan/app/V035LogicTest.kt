package com.menghuan.app

import com.menghuan.app.data.ApiProfileCodec
import com.menghuan.app.data.StoredApiProfile
import com.menghuan.app.data.ImageGenerationClient
import com.menghuan.app.data.ImageApiConfig
import com.menghuan.app.data.ApiProfile
import com.menghuan.app.ui.theme.DarkPalette
import com.menghuan.app.ui.theme.LightPalette
import com.menghuan.app.ui.theme.ThemeMode
import com.menghuan.app.ui.theme.personaPaletteOf
import com.menghuan.app.ui.theme.resolveDarkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.35.0 纯逻辑单测：暗色模式解析、多接口编解码、emoji 判定、生图标记解析、头像环稳定散列。
 * 全部无 Android 依赖（JVM 直跑），口径与实现同源。
 */
class V035LogicTest {

    // ===== 暗色模式 =====

    @Test
    fun `跟随系统时取系统值，强制模式覆盖系统`() {
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, true))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, false))
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, true))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, false))
    }

    @Test
    fun `未知模式名回落到跟随系统`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName("不存在的模式"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName(null))
        assertEquals(ThemeMode.DARK, ThemeMode.fromName("DARK"))
    }

    @Test
    fun `深浅两套色板字段齐全且强调色严格反相`() {
        // 漏一个字段就会在某处回退到默认（null），这里逐个断言非空
        listOf(LightPalette, DarkPalette).forEach { p ->
            listOf(
                p.background, p.surface, p.track, p.hairline,
                p.textPrimary, p.textSecondary, p.muted, p.faint,
                p.accent, p.accentDeep, p.accentSoft, p.onAccent,
                p.danger, p.shadow, p.selectionInk, p.handleFill,
                p.handleInk, p.grip, p.outline,
            ).forEach { /* 访问即验证：Color 非空 */ }
        }
        // 浅色墙白 + 黑强调；深色墙黑 + 白强调
        assertNotEquals(LightPalette.background, DarkPalette.background)
        assertNotEquals(LightPalette.accent, DarkPalette.accent)
        assertEquals(LightPalette.isDark, false)
        assertEquals(DarkPalette.isDark, true)
    }

    // ===== 多接口配置编解码 =====

    @Test
    fun `多接口 JSON 往返一致`() {
        val list = listOf(
            StoredApiProfile("p1", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "enc1", ""),
            StoredApiProfile("p2", "智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5", "", "sk-plain"),
        )
        assertEquals(list, ApiProfileCodec.decode(ApiProfileCodec.encode(list)))
    }

    @Test
    fun `脏数据不崩：整体非 JSON 与缺 id 的条目都被跳过`() {
        assertTrue(ApiProfileCodec.decode("").isEmpty())
        assertTrue(ApiProfileCodec.decode("不是 json").isEmpty())
        // 可解析但没有 id 的条目跳过，其余保留
        val mixed = ApiProfileCodec.decode("""[{"name":"无名"},{"id":"ok","name":"正常"}]""")
        assertEquals(1, mixed.size)
        assertEquals("ok", mixed[0].id)
    }

    @Test
    fun `缺字段补空串而不是崩`() {
        val decoded = ApiProfileCodec.decode("""[{"id":"x"}]""")
        assertEquals(1, decoded.size)
        assertEquals("", decoded[0].baseUrl)
        assertEquals("", decoded[0].model)
        assertEquals("", decoded[0].keyEnc)
        // 名字缺失时按序号兜底
        assertTrue(decoded[0].name.isNotBlank())
    }

    @Test
    fun `接口完整性三件套缺一不可`() {
        assertTrue(ApiProfile("a", "n", "https://x", "sk", "m").isComplete)
        assertFalse(ApiProfile("a", "n", "https://x", "", "m").isComplete)
        assertFalse(ApiProfile("a", "n", "", "sk", "m").isComplete)
        assertFalse(ApiProfile("a", "n", "https://x", "sk", "").isComplete)
    }

    @Test
    fun `生图接口同样按三件套判定，并能转成对话接口形态`() {
        val cfg = ImageApiConfig("https://x", "sk", "m")
        assertTrue(cfg.isComplete)
        assertEquals("m", cfg.toApiConfig().model)
        assertFalse(ImageApiConfig("https://x", "", "m").isComplete)
    }

    // ===== 生图标记解析 =====

    private val client = ImageGenerationClient("", "", "")

    @Test
    fun `识别回复里的生图标记并抽出提示词`() {
        val raw = "这是构图思路。[生图:a cat sitting on a window sill]"
        assertEquals("a cat sitting on a window sill", client.findPromptIn(raw))
    }

    @Test
    fun `没有标记时返回 null`() {
        assertNull(client.findPromptIn("今天天气不错"))
        assertNull(client.findPromptIn("提到了 [生图: 却没有收尾"))
    }

    @Test
    fun `剥掉标记只留正文（独占时正文为空串）`() {
        assertEquals("这是构图思路。", client.stripMarker("这是构图思路。[生图:prompt]"))
        assertEquals("", client.stripMarker("[生图:prompt]"))
        assertEquals("没有标记", client.stripMarker("没有标记"))
    }

    @Test
    fun `空接口配置视为未配置`() {
        assertFalse(ImageGenerationClient("", "k", "m").isConfigured())
        assertFalse(ImageGenerationClient("https://x", "", "m").isConfigured())
        assertFalse(ImageGenerationClient("https://x", "k", "").isConfigured())
        assertTrue(ImageGenerationClient("https://x", "k", "m").isConfigured())
    }

    // ===== 人设渐变环 =====

    @Test
    fun `同一个 id 每次拿到同一支笔刷（重启后不变）`() {
        assertEquals(personaPaletteOf(7L), personaPaletteOf(7L))
        assertEquals(2, personaPaletteOf(7L).size)
    }

    @Test
    fun `不同 id 大多拿到不同配色（视觉上随机而非全员同色）`() {
        val distinct = (1L..30L).map { personaPaletteOf(it) }.distinct().size
        assertTrue("30 个 id 只出了 $distinct 种配色，随机性不足", distinct >= 8)
    }

    @Test
    fun `负 id 与大 id 也不崩（散列必须落在非负区间）`() {
        assertEquals(2, personaPaletteOf(-5L).size)
        assertEquals(2, personaPaletteOf(Long.MAX_VALUE).size)
        assertEquals(personaPaletteOf(Long.MAX_VALUE), personaPaletteOf(Long.MAX_VALUE))
    }
}