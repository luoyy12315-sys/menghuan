package com.daoshi.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

private sealed interface MdBlock {
    data class Line(val text: String) : MdBlock
    data class Code(val lines: List<String>) : MdBlock
}

/** ``` 栅栏整体成块；未闭合栅栏的余下行也按代码处理 */
private fun splitBlocks(text: String): List<MdBlock> {
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
    val line = raw.trimEnd()
    when {
        line.isBlank() -> Spacer(Modifier.height(3.dp))

        line.startsWith("#") && line.dropWhile { it == '#' }.startsWith(" ") -> Text(
            inline(line.dropWhile { it == '#' }.trim()),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )

        Regex("^\\s*[-•]\\s+").containsMatchIn(line) -> Text(
            "•  " + inline(line.replaceFirst(Regex("^\\s*[-•]\\s+"), "")),
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = color,
        )

        else -> Text(
            inline(line.trimStart()),
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = color,
        )
    }
}

@Composable
private fun ColumnScope.CodeLines(lines: List<String>, color: Color) {
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
        modifier = Modifier.fillMaxWidth(),
    )
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
