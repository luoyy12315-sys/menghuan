package com.menghuan.app

import com.menghuan.app.ui.common.LineKind
import com.menghuan.app.ui.common.classifyLine
import com.menghuan.app.ui.common.buildRenderNodes
import com.menghuan.app.ui.common.splitBlocks
import org.junit.Assert.assertEquals
import org.junit.Test

/** Markdown 行分类与代码栅栏切分（纯字符串逻辑，无 Android 依赖） */
class MarkdownLogicTest {

    @Test
    fun `行分类覆盖五种形态`() {
        assertEquals(LineKind.Blank to "", classifyLine("   "))
        assertEquals(LineKind.Heading to "标题", classifyLine("## 标题"))
        assertEquals(LineKind.Bullet to "项目一", classifyLine("- 项目一"))
        assertEquals(LineKind.Bullet to "项目二", classifyLine("• 项目二"))
        assertEquals(LineKind.Plain to "普通行", classifyLine("普通行"))
        // 有序列表保持原样走 Plain（编号本身就是要展示的内容）
        assertEquals(LineKind.Plain to "1. 步骤", classifyLine("1. 步骤"))
    }

    @Test
    fun `代码栅栏成块`() {
        val blocks = splitBlocks("前文\n```\ncode line\n```\n后文")
        assertEquals(3, blocks.size)
        assertEquals("前文", (blocks[0] as com.menghuan.app.ui.common.MdBlock.Line).text)
        assertEquals(listOf("code line"), (blocks[1] as com.menghuan.app.ui.common.MdBlock.Code).lines)
        assertEquals("后文", (blocks[2] as com.menghuan.app.ui.common.MdBlock.Line).text)
    }

    @Test
    fun `未闭合栅栏的余行按代码处理`() {
        val blocks = splitBlocks("```x\nval a = 1")
        assertEquals(1, blocks.size)
        assertEquals(listOf("val a = 1"), (blocks[0] as com.menghuan.app.ui.common.MdBlock.Code).lines)
    }

    @Test
    fun `渲染节点合并连续同类行`() {
        // 3 行 Plain + 2 行 Bullet → 只有 2 个 Para 节点（旧版是 5 个 Text）
        val nodes = buildRenderNodes("第一行\n第二行\n第三行\n- 甲\n- 乙")
        assertEquals(2, nodes.size)
        val plain = nodes[0] as com.menghuan.app.ui.common.RenderNode.Para
        assertEquals(LineKind.Plain, plain.kind)
        assertEquals(listOf("第一行", "第二行", "第三行"), plain.lines)
        val bullet = nodes[1] as com.menghuan.app.ui.common.RenderNode.Para
        assertEquals(LineKind.Bullet, bullet.kind)
        assertEquals(listOf("甲", "乙"), bullet.lines)
    }

    @Test
    fun `空行切段且连续空行只留一个 Gap`() {
        val nodes = buildRenderNodes("甲\n\n\n\n\n乙")
        assertEquals(3, nodes.size)
        assertEquals(com.menghuan.app.ui.common.RenderNode.Gap, nodes[1])
    }

    @Test
    fun `首尾空行被修剪且代码块切断合并`() {
        val nodes = buildRenderNodes("\n\n前文\n```\n代码\n```\n- 项\n\n")
        // Gap(首)×2 去掉；Plain(前文) → Code → Bullet；尾部 Gap 去掉
        assertEquals(3, nodes.size)
        assertEquals(LineKind.Plain, (nodes[0] as com.menghuan.app.ui.common.RenderNode.Para).kind)
        assertEquals(
            listOf("代码"),
            (nodes[1] as com.menghuan.app.ui.common.RenderNode.CodeBlock).lines,
        )
        assertEquals(LineKind.Bullet, (nodes[2] as com.menghuan.app.ui.common.RenderNode.Para).kind)
    }

    @Test
    fun `空行不会丢`() {
        val blocks = splitBlocks("a\n\nb")
        assertEquals(3, blocks.size)
    }
}
