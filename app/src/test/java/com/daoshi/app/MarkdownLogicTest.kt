package com.daoshi.app

import com.daoshi.app.ui.common.LineKind
import com.daoshi.app.ui.common.classifyLine
import com.daoshi.app.ui.common.splitBlocks
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
        assertEquals("前文", (blocks[0] as com.daoshi.app.ui.common.MdBlock.Line).text)
        assertEquals(listOf("code line"), (blocks[1] as com.daoshi.app.ui.common.MdBlock.Code).lines)
        assertEquals("后文", (blocks[2] as com.daoshi.app.ui.common.MdBlock.Line).text)
    }

    @Test
    fun `未闭合栅栏的余行按代码处理`() {
        val blocks = splitBlocks("```x\nval a = 1")
        assertEquals(1, blocks.size)
        assertEquals(listOf("val a = 1"), (blocks[0] as com.daoshi.app.ui.common.MdBlock.Code).lines)
    }

    @Test
    fun `空行不会丢`() {
        val blocks = splitBlocks("a\n\nb")
        assertEquals(3, blocks.size)
    }
}
