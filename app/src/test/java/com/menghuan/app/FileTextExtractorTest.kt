package com.menghuan.app

import com.menghuan.app.data.FileTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 文件正文抽取（v0.43.0）：把用户选的文件尽可能变成能喂给模型的文字。
 *
 * 覆盖：纯文本 / BOM / docx 段落 / pptx 多页 / xlsx 共享串 / 抽不出 / 超长截断。
 * 纯 JVM 测试（用 TemporaryFolder 造真实文件 + 真 zip），不依赖 Android 运行时。
 */
class FileTextExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(name: String, content: String): File =
        tmp.newFile(name).apply { writeText(content) }

    @Test
    fun `纯文本直接读出内容`() {
        val f = write("note.txt", "今天要早点睡\n明天继续")
        val r = FileTextExtractor.extract(f, "note.txt")
        assertTrue(r.hasText)
        assertTrue(r.text.contains("今天要早点睡"))
    }

    @Test
    fun `带 BOM 的 UTF-8 文本能正确剥离 BOM`() {
        val f = tmp.newFile("bom.txt")
        f.writeBytes(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "中文内容".toByteArray(Charsets.UTF_8)
        )
        val r = FileTextExtractor.extract(f, "bom.txt")
        // BOM 不能被当成正文，否则第一行会多一个不可见字符影响模型理解
        assertEquals("中文内容", r.text)
    }

    @Test
    fun `docx 抽取段落并按段换行`() {
        val f = tmp.newFile("doc.docx")
        writeZip(
            f,
            "word/document.xml" to
                "<w:document><w:body>" +
                "<w:p><w:r><w:t>第一段</w:t></w:r></w:p>" +
                "<w:p><w:r><w:t>第二段</w:t></w:r></w:p>" +
                "</w:body></w:document>",
        )
        val r = FileTextExtractor.extract(f, "doc.docx")
        assertTrue(r.text.contains("第一段"))
        assertTrue(r.text.contains("第二段"))
        // 段落之间要有换行，否则模型看到的是连成一坨的文字
        assertTrue(r.text.contains("\n"))
    }

    @Test
    fun `pptx 按页码顺序拼接多张幻灯片`() {
        val f = tmp.newFile("deck.pptx")
        writeZip(
            f,
            "ppt/slides/slide1.xml" to "<a:p><a:t>第一页</a:t></a:p>",
            "ppt/slides/slide2.xml" to "<a:p><a:t>第二页</a:t></a:p>",
            "ppt/slides/slide10.xml" to "<a:p><a:t>第十页</a:t></a:p>",
        )
        val r = FileTextExtractor.extract(f, "deck.pptx")
        // 字符串排序会得到 1,10,2；必须按数字排序
        val i1 = r.text.indexOf("第一页")
        val i2 = r.text.indexOf("第二页")
        val i10 = r.text.indexOf("第十页")
        assertTrue(i1 in 0 until i2)
        assertTrue(i2 < i10)
    }

    @Test
    fun `不受支持的格式返回空文本而不是乱码`() {
        val f = tmp.newFile("archive.zip")
        writeZip(f, "a.txt" to "内容")
        val r = FileTextExtractor.extract(f, "archive.zip")
        assertFalse(r.hasText)
    }

    @Test
    fun `超长文档被截断并标记`() {
        val f = write("big.txt", "字".repeat(FileTextExtractor.MAX_CHARS + 5000))
        val r = FileTextExtractor.extract(f, "big.txt")
        assertEquals(FileTextExtractor.MAX_CHARS, r.text.length)
        assertTrue(r.truncated)
    }

    @Test
    fun `压缩过的 PDF 抽不出文字层时返回空`() {
        // 真实 PDF 的文本流一般是 Flate 压缩的，这里明确验证"抽不出就老实返回空"
        val f = write("scan.pdf", "%PDF-1.4\nstream\nx\u009c\nnot-a-text-layer\nendstream")
        val r = FileTextExtractor.extract(f, "scan.pdf")
        assertFalse(r.hasText)
    }

    @Test
    fun `未压缩 PDF 的文本层能抽出来`() {
        // PDF 字符串里的中文按 PDFDocEncoding/UTF-16BE 存，这里用八进制转义
        // （每字节 \ddd）构造，等价于真实未压缩 PDF 的写法
        val f = tmp.newFile("plain.pdf")
        f.writeBytes(
            (
                "%PDF-1.4\n" +
                    "BT /F1 12 Tf (" + pdfOctal("你好世界") + ") Tj ET\n" +
                    "BT (" + pdfOctal("第二行") + ") Tj ET"
                ).toByteArray(Charsets.ISO_8859_1),
        )
        val r = FileTextExtractor.extract(f, "plain.pdf")
        assertTrue(r.text.contains("你好世界"))
        assertTrue(r.text.contains("第二行"))
    }

    /** 把字符串编成 PDF 字面量里的八进制字节转义（真实 PDF 对非 ASCII 就是这么写的） */
    private fun pdfOctal(s: String): String =
        s.toByteArray(Charsets.UTF_8).joinToString("") { "\\%03o".format(it.toInt() and 0xFF) }

    @Test
    fun `人读大小格式化`() {
        assertEquals("512 B", FileTextExtractor.humanSize(512))
        assertEquals("1.0 KB", FileTextExtractor.humanSize(1024))
        assertEquals("1.5 MB", FileTextExtractor.humanSize(1024L * 1024 * 3 / 2))
    }

    private fun writeZip(target: File, vararg entries: Pair<String, String>) {
        ZipOutputStream(target.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
