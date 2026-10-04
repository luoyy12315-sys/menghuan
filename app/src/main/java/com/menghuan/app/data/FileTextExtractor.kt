package com.menghuan.app.data

import java.io.File
import java.util.zip.ZipInputStream

/**
 * 文件正文抽取（v0.43.0）：把用户选的文件尽可能转成**纯文字**喂给模型。
 *
 * 为什么不用第三方库：OpenAI 兼容接口只认文字和图片，PDF/Word 得在本地抽。
 * 为几个格式引入 PDFBox + POI 会让包大十几 MB，而这几个格式的文字层
 * 用标准库（ZipInputStream + XML 正则）就能抠出来，够用且零依赖。
 *
 * 各格式的取舍（**明确写出来，免得以后有人以为能抽所有文件**）：
 * - txt/md/json/csv/code 等：直接按 UTF-8 读（带 BOM 处理）
 * - docx/pptx/xlsx：本质是 zip，解 `word/document.xml` 等，剥标签取文本
 * - pdf：抽**未压缩的文本层**（`BT…ET` 里的 `Tj/TJ`）。Flate 压缩的流不解——
 *   那需要完整实现 inflate + 字体编码映射，投入产出比太低；抽不到就退化成"只给文件名"
 * - 其它（zip / 图片 / 二进制）：返回 null，调用方按"只有文件名"处理
 *
 * 抽取上限 [MAX_CHARS]：超长文档截断，避免把上下文预算一次性吃光
 * （用户的服务商按请求长度计费，见 v0.39.0 的 selectHistoryByCharBudget）。
 */
object FileTextExtractor {

    /** 单个文件最多抽取的字符数（约等于 20 万 token 预算里的零头，留足对话余量） */
    const val MAX_CHARS = 20_000

    /** 纯文本类扩展名：直接读文件内容 */
    private val PLAIN_TEXT_EXT = setOf(
        "txt", "md", "markdown", "json", "xml", "yaml", "yml", "csv", "tsv",
        "log", "ini", "cfg", "conf", "properties", "toml",
        "kt", "java", "js", "ts", "jsx", "tsx", "py", "rb", "go", "rs", "c", "cpp", "h",
        "html", "htm", "css", "scss", "sh", "bat", "ps1", "sql", "gradle", "kts",
    )

    /**
     * 抽取结果。[text] 为空表示"抽不出来"，调用方应退化成只展示文件名。
     * [truncated] 表示因超长被截断，用于给用户一个说明。
     */
    data class Result(val text: String, val truncated: Boolean = false) {
        val hasText: Boolean get() = text.isNotBlank()
    }

    /**
     * 按扩展名分派抽取。[displayName] 用于取扩展名（真实文件可能没有后缀，
     * 比如 content:// 拷出来的临时文件名，这时靠用户选的原始名判断）。
     */
    fun extract(file: File, displayName: String): Result = runCatching {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        val raw = when {
            ext in PLAIN_TEXT_EXT -> readPlainText(file)
            ext == "docx" -> readOoxml(file, "word/document.xml")
            ext == "pptx" -> readOoxmlAll(file, listOf("ppt/slides/slide", ".xml"))
            ext == "xlsx" -> readOoxml(file, "xl/sharedStrings.xml")
            ext == "pdf" -> readPdfTextLayer(file)
            else -> null
        } ?: return Result("")
        val trimmed = raw.trim()
        if (trimmed.length > MAX_CHARS) Result(trimmed.take(MAX_CHARS), truncated = true)
        else Result(trimmed)
    }.getOrElse { Result("") }

    /** 纯文本：按 UTF-8 读；有 BOM 就剥掉（Windows 记事本存的中文 txt 常见） */
    private fun readPlainText(file: File): String? {
        if (file.length() == 0L) return null
        val bytes = file.readBytes()
        val start = if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) 3 else 0
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
    }

    /**
     * OOXML（docx/xlsx）：打开 zip 找指定 entry，剥掉 XML 标签后取文本。
     * `<w:p>` / `</w:p>` 转换成换行，保留段落结构；其余标签直接丢掉。
     */
    private fun readOoxml(file: File, entryName: String): String? {
        val xml = readZipEntry(file, entryName) ?: return null
        return stripXml(xml)
    }

    /** pptx：若干 slideN.xml 逐个抽，编号排序保证页序 */
    private fun readOoxmlAll(file: File, prefixAndSuffix: List<String>): String? {
        val prefix = prefixAndSuffix[0]
        val suffix = prefixAndSuffix[1]
        val names = mutableListOf<String>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                val n = e.name
                if (n.startsWith(prefix) && n.endsWith(suffix)) names += n
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        if (names.isEmpty()) return null
        return names.sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            .mapNotNull { stripXml(readZipEntry(file, it) ?: return@mapNotNull null) }
            .joinToString("\n\n")
    }

    private fun readZipEntry(file: File, entryName: String): String? {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == entryName) return zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        return null
    }

    /** 剥 XML 标签：段落边界转换行，实体还原，压缩多余空白 */
    private fun stripXml(xml: String): String = xml
        .replace(Regex("</w:p>|</a:p>|<w:br\\s*/?>|<w:cr\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&#xA;", "\n")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")

    /**
     * PDF 文本层：只处理**未压缩**的流（`stream … endstream` 里能直接读到 `Tj` / `TJ`）。
     * 压缩流（`/FlateDecode`）整段跳过——解压 + 字体编码映射的复杂度不值当，
     * 抽不到就返回 null 让调用方退化成"只给文件名"。
     */
    private fun readPdfTextLayer(file: File): String? {
        if (file.length() == 0L || file.length() > 40L * 1024 * 1024) return null
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        val sb = StringBuilder()
        // BT…ET 之间是文本对象
        Regex("""BT(.*?)ET""", RegexOption.DOT_MATCHES_ALL).findAll(raw).forEach { block ->
            // (文字) Tj  以及  [(文字) -250 (文字)] TJ
            Regex("""\((?:\\.|[^\\()])*\)""", RegexOption.DOT_MATCHES_ALL).findAll(block.value).forEach { m ->
                val s = decodePdfLiteral(m.value.substring(1, m.value.length - 1))
                if (s.isNotBlank()) sb.append(s)
            }
            sb.append('\n')
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /**
     * 还原 PDF 字面量字符串：`\ddd` 八进制 / `\n` 等转义 → 原始字节，
     * 再按 UTF-8 解码（非 ASCII 文本在 PDF 里通常就是这么存的）。
     * 解不出合法 UTF-8 时退回 ISO-8859-1 逐字节映射，保证不丢内容。
     */
    private fun decodePdfLiteral(body: String): String {
        val out = java.io.ByteArrayOutputStream(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') {
                // 字面量里未转义的字符本身就是单字节（Latin-1 区间）
                out.write(c.code and 0xFF)
                i++
                continue
            }
            if (i + 1 >= body.length) break
            val n = body[i + 1]
            when {
                n == 'n' -> { out.write('\n'.code); i += 2 }
                n == 'r' -> { out.write('\r'.code); i += 2 }
                n == 't' -> { out.write('\t'.code); i += 2 }
                n == 'b' -> { out.write('\b'.code); i += 2 }
                n == 'f' -> { out.write(0x0C); i += 2 }
                n in '0'..'7' -> {
                    var j = i + 1
                    var v = 0
                    var count = 0
                    while (j < body.length && count < 3 && body[j] in '0'..'7') {
                        v = v * 8 + (body[j] - '0')
                        j++
                        count++
                    }
                    out.write(v and 0xFF)
                    i = j
                }
                n == '\n' -> i += 2          // 行尾反斜杠续行
                n == '\r' -> i += if (i + 2 < body.length && body[i + 2] == '\n') 3 else 2
                else -> { out.write(n.code and 0xFF); i += 2 }
            }
        }
        val bytes = out.toByteArray()
        val utf8 = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        // String() 对非法字节会插入 U+FFFD，据此判断能否当 UTF-8 用
        return if (utf8 != null && !utf8.contains('\uFFFD')) utf8 else String(bytes, Charsets.ISO_8859_1)
    }

    /** 按字节数给出人类可读的大小（气泡上显示） */
    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }
}
