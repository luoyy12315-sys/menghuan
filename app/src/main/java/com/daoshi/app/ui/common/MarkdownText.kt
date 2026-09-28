package com.daoshi.app.ui.common

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daoshi.app.ui.theme.NeuTrack
import com.daoshi.app.ui.theme.TextPrimary

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

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        splitBlocks(text).forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeLines(block.lines, color)
                is MdBlock.Line -> LineText(block.text, color)
            }
        }
    }
}

@Composable
private fun ColumnScope.LineText(raw: String, color: Color) {
    val (kind, content) = classifyLine(raw)
    when (kind) {
        LineKind.Blank -> Spacer(Modifier.height(3.dp))
        LineKind.Heading -> Text(
            inline(content),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        LineKind.Bullet -> Text(
            // 悬挂缩进：折行与圆点后的首字对齐，而不是顶到圆点下方
            buildAnnotatedString {
                pushStyle(
                    androidx.compose.ui.text.ParagraphStyle(
                        textIndent = androidx.compose.ui.text.style.TextIndent(
                            firstLine = 0.sp,
                            restLine = 13.sp,
                        ),
                    )
                )
                append("•  ")
                append(inline(content))
                pop()
            },
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = color,
        )
        LineKind.Plain -> Text(
            inline(content),
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = color,
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
private fun inline(text: String) = buildAnnotatedString {
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
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = NeuTrack))
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
