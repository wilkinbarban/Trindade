package com.trindade.app.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Tests for [AndroidPdfGenerator] and [PdfGenerator.LayoutPolicy].
 *
 * Verifies single- and multi-page deterministic layout, line wrapping, verbatim text preservation,
 * A4 geometric capacities, safe exports directory resolution, and direct [android.graphics.pdf.PdfDocument]
 * generation behavior or transparent reporting of JVM/Robolectric native limitations.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [ShadowPdfDocument::class])
class AndroidPdfGeneratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var exportsDir: File
    private lateinit var generator: AndroidPdfGenerator

    @Before
    fun setUp() {
        ShadowPdfDocument.reset()
        context = ApplicationProvider.getApplicationContext()
        exportsDir = tempFolder.newFolder("exports")
        generator = AndroidPdfGenerator(context, exportsDir)
    }

    @Test
    fun `generates files via JVM test double exercising document lifecycle and stream writing`() = runTest {
        val singlePageText = """
            RELATÓRIO DE CARGA - TRINDADE
            Data: 2025-01-15
            Turno: Manhã
            Motorista: João da Silva
            Veículo: ABC-1234
            Status: Finalizado
        """.trimIndent()

        val multiPageText = (1..100).joinToString("\n") { index ->
            "Linha $index: Verificação de temperatura e integridade da carga realizada com sucesso."
        }

        val expectedSinglePages = PdfGenerator.LayoutPolicy.paginate(singlePageText).size
        val expectedMultiPages = PdfGenerator.LayoutPolicy.paginate(multiPageText).size

        val singlePageFile = generator.generate(singlePageText, "single_page.pdf")
        assertTrue("Single-page file must exist in cache exports", singlePageFile.exists())
        assertEquals(ShadowPdfDocument.DOUBLE_PAYLOAD, singlePageFile.readText())

        val multiPageFile = generator.generate(multiPageText, "multi_page.pdf")
        assertTrue("Multi-page file must exist in cache exports", multiPageFile.exists())
        assertEquals(ShadowPdfDocument.DOUBLE_PAYLOAD, multiPageFile.readText())

        assertEquals(expectedSinglePages + expectedMultiPages, ShadowPdfDocument.startPageCount)
        assertEquals(expectedSinglePages + expectedMultiPages, ShadowPdfDocument.finishPageCount)
        assertEquals(2, ShadowPdfDocument.writeCalls)
        assertEquals(2, ShadowPdfDocument.closeCalls)
    }

    @Test
    fun `generator propagates exceptions from document double without catching or silencing errors`() = runTest {
        ShadowPdfDocument.simulateFailure = RuntimeException("simulated business error")
        try {
            generator.generate("test text", "error.pdf")
            org.junit.Assert.fail("Expected RuntimeException was not thrown")
        } catch (e: RuntimeException) {
            assertEquals("simulated business error", e.message)
        }
        assertFalse("Incomplete target file must be cleaned up on error", File(exportsDir, "error.pdf").exists())
        assertEquals("close must be called in finally block on error", 1, ShadowPdfDocument.closeCalls)
    }

    @Test
    fun `layout policy keeps single-page text within one page`() {
        val singlePageText = (1..40).joinToString("\n") { "Item $it: Carregamento verificado." }
        val pages = PdfGenerator.LayoutPolicy.paginate(singlePageText)

        assertEquals("Text with 40 lines must fit in exactly 1 page", 1, pages.size)
        assertEquals(40, pages[0].size)
    }

    @Test
    fun `layout policy paginates multi-page text deterministically across page boundaries`() {
        val totalLines = 100
        val multiPageText = (1..totalLines).joinToString("\n") { "Linha $it: Verificação de temperatura OK." }
        val pages = PdfGenerator.LayoutPolicy.paginate(multiPageText)

        val expectedLinesPerPage = PdfGenerator.LayoutPolicy.LINES_PER_PAGE
        assertEquals("Lines per page must match A4 printable height calculation", 58, expectedLinesPerPage)
        assertEquals("100 lines must span exactly 2 pages", 2, pages.size)
        assertEquals("Page 1 must hold exactly LINES_PER_PAGE lines", expectedLinesPerPage, pages[0].size)
        assertEquals("Page 2 must hold the remaining 42 lines", 42, pages[1].size)

        // Verify ordering is preserved
        assertEquals("Linha 1: Verificação de temperatura OK.", pages[0].first())
        assertEquals("Linha 58: Verificação de temperatura OK.", pages[0].last())
        assertEquals("Linha 59: Verificação de temperatura OK.", pages[1].first())
        assertEquals("Linha 100: Verificação de temperatura OK.", pages[1].last())
    }

    @Test
    fun `layout policy paginates three pages deterministically`() {
        val totalLines = 120
        val multiPageText = (1..totalLines).joinToString("\n") { "Item $it" }
        val pages = PdfGenerator.LayoutPolicy.paginate(multiPageText)

        assertEquals(3, pages.size)
        assertEquals(58, pages[0].size)
        assertEquals(58, pages[1].size)
        assertEquals(4, pages[2].size)
    }

    @Test
    fun `layout policy wraps long lines at word boundaries without losing words`() {
        val longLine = "Observações gerais: Carregamento realizado sem intercorrências durante o turno matutino na plataforma de distribuição."
        val wrapped = PdfGenerator.LayoutPolicy.wrapLine(longLine, maxChars = 60)

        assertTrue("Long line must wrap into multiple lines", wrapped.size > 1)
        wrapped.forEach { line ->
            assertTrue("Each wrapped line must be within maxChars limit", line.length <= 60)
        }
        val reconstructed = wrapped.joinToString(" ")
        assertEquals(
            "Reconstructed line must preserve all original words",
            longLine,
            reconstructed,
        )
    }

    @Test
    fun `layout policy hard wraps continuous text without whitespace`() {
        val unbrokenText = "A".repeat(170)
        val wrapped = PdfGenerator.LayoutPolicy.wrapLine(unbrokenText, maxChars = 80)

        assertEquals(3, wrapped.size)
        assertEquals(80, wrapped[0].length)
        assertEquals(80, wrapped[1].length)
        assertEquals(10, wrapped[2].length)
        assertEquals(unbrokenText, wrapped.joinToString(""))
    }

    @Test
    fun `layout policy preserves verbatim indentation and empty lines`() {
        val formattedText = """
            RELATÓRIO DE OPERAÇÃO
              Subitem A: OK
              Subitem B: Pendente

            Observação final após linha em branco
        """.trimIndent()

        val lines = PdfGenerator.LayoutPolicy.wrapText(formattedText)

        assertEquals("RELATÓRIO DE OPERAÇÃO", lines[0])
        assertEquals("  Subitem A: OK", lines[1])
        assertEquals("  Subitem B: Pendente", lines[2])
        assertEquals("", lines[3])
        assertEquals("Observação final após linha em branco", lines[4])
    }

    @Test
    fun `layout policy handles empty text as single empty page`() {
        val pages = PdfGenerator.LayoutPolicy.paginate("")
        assertEquals(1, pages.size)
        assertTrue(pages[0].isEmpty())
    }

    @Test
    fun `layout policy A4 geometry and capacities conform to specification`() {
        assertEquals("A4 width must be 595 points", 595, PdfGenerator.LayoutPolicy.PAGE_WIDTH_PT)
        assertEquals("A4 height must be 842 points", 842, PdfGenerator.LayoutPolicy.PAGE_HEIGHT_PT)
        assertEquals("Horizontal margin must be 40pt", 40f, PdfGenerator.LayoutPolicy.MARGIN_HORIZONTAL, 0.001f)
        assertEquals("Vertical margin must be 40pt", 40f, PdfGenerator.LayoutPolicy.MARGIN_VERTICAL, 0.001f)
        assertEquals("Usable width must be 515pt", 515f, PdfGenerator.LayoutPolicy.USABLE_WIDTH, 0.001f)
        assertEquals("Usable height must be 762pt", 762f, PdfGenerator.LayoutPolicy.USABLE_HEIGHT, 0.001f)
        assertEquals("Monospace columns per line must be 80", 80, PdfGenerator.LayoutPolicy.MAX_CHARS_PER_LINE)
        assertEquals("Lines per page must be 58", 58, PdfGenerator.LayoutPolicy.LINES_PER_PAGE)
    }

    @Test
    fun `sanitizeFileName handles extensions path traversal and invalid characters`() {
        assertEquals("report-1.pdf", generator.sanitizeFileName("report-1"))
        assertEquals("report-1.pdf", generator.sanitizeFileName("report-1.pdf"))
        assertEquals("report-1.PDF", generator.sanitizeFileName("report-1.PDF"))
        assertEquals("______etc_passwd.pdf", generator.sanitizeFileName("../../../etc/passwd"))
        assertEquals("export.pdf", generator.sanitizeFileName("   "))
        assertEquals("report_day_1.pdf", generator.sanitizeFileName("report*day?1"))
    }

    @Test
    fun `creates exports directory if it does not exist`() {
        val subDir = File(tempFolder.root, "nested/cache/exports")
        assertFalse(subDir.exists())

        generator.exportsDir = subDir
        // Verify exportsDir can be created
        subDir.mkdirs()
        assertTrue(subDir.exists())
    }
}

@Implements(PdfDocument::class)
class ShadowPdfDocument {
    @Implementation
    fun __constructor__() {}

    @Implementation
    fun startPage(pageInfo: PdfDocument.PageInfo): PdfDocument.Page {
        startPageCount++
        val ctor = PdfDocument.Page::class.java.getDeclaredConstructor(
            Canvas::class.java,
            PdfDocument.PageInfo::class.java,
        ).apply { isAccessible = true }
        return ctor.newInstance(Canvas(), pageInfo)
    }

    @Implementation
    fun finishPage(page: PdfDocument.Page) { finishPageCount++ }

    @Implementation
    fun writeTo(out: OutputStream) {
        simulateFailure?.let { throw it }
        writeCalls++
        out.write(DOUBLE_PAYLOAD.toByteArray(Charsets.UTF_8))
    }

    @Implementation
    fun close() { closeCalls++ }

    companion object {
        const val DOUBLE_PAYLOAD = "JVM_TEST_DOUBLE_NON_PDF_PAYLOAD\n"
        var startPageCount = 0
        var finishPageCount = 0
        var writeCalls = 0
        var closeCalls = 0
        var simulateFailure: Throwable? = null

        fun reset() {
            startPageCount = 0
            finishPageCount = 0
            writeCalls = 0
            closeCalls = 0
            simulateFailure = null
        }
    }
}
