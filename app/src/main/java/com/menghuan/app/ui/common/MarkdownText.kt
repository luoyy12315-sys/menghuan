package com.menghuan.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menghuan.app.ui.theme.NeuTrack
import com.menghuan.app.ui.theme.TextPrimary

/**
 * 轻量 Markdown 渲染（AI 回复气泡专用）：
 * - **粗体**、`行内代码`、# 标题、- 无序列表、1. 有序列表、``` 代码栅栏
 * - 不认识的语法按普通文本原样呈现，绝不吃字
 * 样式保持气泡排版语言（15sp/21sp 主文字），代码用等宽字体。
 */

internal sealed interface MdBlock {
    data class Line(val text: String) : MdBlock
    data class Code(val lines: List<String>) : MdBlock
}

/** 行分类（纯函数，JVM 单测覆盖）：返回种类与"清理后"的正文 */
internal enum class LineKind { Blank, Heading, Bullet, Plain }

internal fun classifyLine(raw: String): Pair<LineKind, String> {
    val line = raw.trimEnd()
    return when {
        line.isBlank() -> LineKind.Blank to ""
        line.startsWith("#") && line.dropWhile { it == '#' }.startsWith(" ") ->
            LineKind.Heading to line.dropWhile { it == '#' }.trim()
        Regex("^\\s*[-•]\\s+").containsMatchIn(line) ->
            LineKind.Bullet to line.replaceFirst(Regex("^\\s*[-•]\\s+"), "")
        else -> LineKind.Plain to line.trimStart()
    }
}

/** ``` 栅栏整体成块；未闭合栅栏的余下行也按代码处理（纯函数，JVM 单测覆盖） */
internal fun splitBlocks(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    var inFence = false
    val fence = mutableListOf<String>()
    text.replace("\r\n", "\n").split('\n').forEach { line ->
        if (line.trimStart().startsWith("```")) {
            if (inFence) {
                if (fence.isNotEmpty()) out += MdBlock.Code(fence.toList())
                fence.clear()
                inFence = false
            } else {
                inFence = true
            }
        } else if (inFence) {
            fence += line
        } else {
            out += MdBlock.Line(line)
        }
    }
    if (fence.isNotEmpty()) out += MdBlock.Code(fence.toList())
    return out
}

/**
 * 渲染节点：把连续同类文字行合并成一个段落节点。
 * 旧版一行一个 Text，一条 40 行回复 = 40 个 Text 各自做段落排版——
 * 进页首帧与滚动时逐个组合就是肉眼可见的卡顿；合并后降到 O(段落数)。
 */
internal sealed interface RenderNode {
    data class Para(val kind: LineKind, val lines: List<String>) : RenderNode
    data class CodeBlock(val lines: List<String>) : RenderNode
    data object Gap : RenderNode
}

/** 分块 → 渲染节点（纯函数，Blank 作段落分隔、连续同类行合并、首尾与连续 Gap 去重） */
internal fun buildRenderNodes(text: String): List<RenderNode> {
    val nodes = mutableListOf<RenderNode>()
    var curKind: LineKind? = null
    val curLines = mutableListOf<String>()
    fun flush() {
        val k = curKind
        if (k != null && curLines.isNotEmpty()) nodes += RenderNode.Para(k, curLines.toList())
        curLines.clear()
        curKind = null
    }
    splitBlocks(text).forEach { block ->
        when (block) {
            is MdBlock.Code -> {
                flush()
                nodes += RenderNode.CodeBlock(block.lines)
            }
            is MdBlock.Line -> {
                val (kind, content) = classifyLine(block.text)
                when (kind) {
                    LineKind.Blank -> {
                        flush()
                        if (nodes.isNotEmpty() && nodes.last() != RenderNode.Gap) {
                            nodes += RenderNode.Gap
                        }
                    }
                    else -> {
                        if (kind != curKind) flush()
                        curKind = kind
                        curLines += content
                    }
                }
            }
        }
    }
    flush()
    while (nodes.firstOrNull() == RenderNode.Gap) nodes.removeAt(0)
    while (nodes.lastOrNull() == RenderNode.Gap) nodes.removeAt(nodes.size - 1)
    return nodes
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    /**
     * 正文字号（v0.77.0：聊天外观的字号三档）。默认 15sp = 原有的固定值，所有老调用点不变。
     * 只缩放**正文/列表/标题**三类，代码块维持等宽小字不跟着放大（代码变大会一条占半屏）。
     *
     * v0.80.0：字号改成连续可调（12~22sp），默认仍是 15sp。
     */
    fontSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    /** v0.80.0：气泡字体族；null = 沿用系统默认（除聊天外的调用点都不传，行为不变） */
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    /** v0.80.0：气泡字重；null = 常规。标题行仍至少半粗（见 ParaText） */
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
) {
    // 解析+合并一次性记忆化：气泡内容不变时，重组/滚动重入零重复计算
    val nodes = remember(text) { buildRenderNodes(text) }
    val codeBg = NeuTrack
    // 行高跟着字号走（约 1.4 倍），否则放大字号后行会"贴着"
    val lineHeight = fontSize * 1.4f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        nodes.forEach { node ->
            when (node) {
                is RenderNode.Gap -> Spacer(Modifier.height(4.dp))
                is RenderNode.CodeBlock -> CodeLines(node.lines, color)
                is RenderNode.Para -> ParaText(node, color, codeBg, fontSize, lineHeight, fontFamily, fontWeight)
            }
        }
    }
}

@Composable
private fun ParaText(
    node: RenderNode.Para,
    color: Color,
    codeBg: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily? = null,
    fontWeight: androidx.compose.ui.text.font.FontWeight? = null,
) {
    when (node.kind) {
        LineKind.Bullet -> Text(
            // 多段落单 Text：ParagraphStyle 推入后对每个段落生效（含折行的悬挂缩进），
            // 一串圆点列表只花一个 Text 节点
            remember(node, codeBg) {
                buildAnnotatedString {
                    pushStyle(
                        androidx.compose.ui.text.ParagraphStyle(
                            textIndent = androidx.compose.ui.text.style.TextIndent(
                                firstLine = 0.sp,
                                restLine = 13.sp,
                            ),
                        ),
                    )
                    node.lines.forEachIndexed { i, line ->
                        if (i > 0) append('\n')
                        append("•  ")
                        append(inline(line, codeBg))
                    }
                    pop()
                }
            },
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
        )
        LineKind.Heading -> Text(
            remember(node, codeBg) {
                buildAnnotatedString {
                    node.lines.forEachIndexed { i, line ->
                        if (i > 0) append('\n')
                        append(inline(line, codeBg))
                    }
                }
            },
            fontSize = fontSize,
            // 标题仍要"比正文重"：用户字重比半粗更粗时听用户的，否则至少半粗
            fontWeight = androidx.compose.ui.text.font.FontWeight(
                maxOf(fontWeight?.weight ?: 0, FontWeight.SemiBold.weight)
            ),
            color = color,
            fontFamily = fontFamily,
        )
        else -> Text(
            remember(node, codeBg) {
                buildAnnotatedString {
                    node.lines.forEachIndexed { i, line ->
                        if (i > 0) append('\n')
                        append(inline(line, codeBg))
                    }
                }
            },
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
        )
    }
}

@Composable
private fun ColumnScope.CodeLines(lines: List<String>, color: Color) {
    // 代码块：凹陷纸面 + 等宽字体，与气泡内嵌语言一致
    Column(
        Modifier
            .fillMaxWidth()
            .neuSurface(12.dp, color = MaterialTheme.colorScheme.background, elevation = 3.dp, inset = true)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            buildAnnotatedString {
                lines.forEachIndexed { i, l ->
                    append(l)
                    if (i < lines.lastIndex) append('\n')
                }
            },
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}

/** 行内格式：**粗体** 与 `行内代码`（字符扫描，未配对的记号原样输出） */
private fun inline(text: String, codeBg: Color) = buildAnnotatedString {
    var i = 0
    val n = text.length
    while (i < n) {
        if (text.startsWith("**", i)) {
            val close = text.indexOf("**", i + 2)
            if (close > i + 1) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(text.substring(i + 2, close))
                pop()
                i = close + 2
                continue
            }
        }
        if (text[i] == '`') {
            val close = text.indexOf('`', i + 1)
            if (close > i) {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg))
                append(text.substring(i + 1, close))
                pop()
                i = close + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}
