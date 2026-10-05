package com.trindade.app.export

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native Android PDF generator backed by [PdfDocument].
 *
 * Emits A4 PDFs into the application's cache `exports` directory, which is declared in
 * `file_paths.xml` for FileProvider exposure without requiring shared storage permissions.
 *
 * Text layout and pagination follow [PdfGenerator.LayoutPolicy], which deterministically
 * wraps server-provided text in monospace type and paginates across multi-page boundaries.
 *
 * All platform resources (the [PdfDocument] native handle and file output streams) are
 * enclosed in try-finally blocks to guarantee safe resource closure on both success and error.
 */
@Singleton
class AndroidPdfGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
) : PdfGenerator {

    internal var exportsDir: File = File(context.cacheDir, EXPORTS_DIR)
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Testing constructor allowing a custom exports directory and dispatcher.
     */
    internal constructor(
        context: Context,
        customExportsDir: File,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(context) {
        this.exportsDir = customExportsDir
        this.ioDispatcher = dispatcher
    }

    override suspend fun generate(text: String, fileName: String): File = withContext(ioDispatcher) {
        val safeFileName = sanitizeFileName(fileName)
        if (!exportsDir.exists()) {
            exportsDir.mkdirs()
        }

        val targetFile = File(exportsDir, safeFileName)
        val pages = PdfGenerator.LayoutPolicy.paginate(text)

        val document = PdfDocument()
        try {
            val paint = Paint().apply {
                typeface = Typeface.MONOSPACE
                textSize = PdfGenerator.LayoutPolicy.FONT_SIZE
                color = Color.BLACK
                isAntiAlias = true
            }

            for ((pageIndex, pageLines) in pages.withIndex()) {
                val pageInfo = PdfDocument.PageInfo.Builder(
                    PdfGenerator.LayoutPolicy.PAGE_WIDTH_PT,
                    PdfGenerator.LayoutPolicy.PAGE_HEIGHT_PT,
                    pageIndex + 1,
                ).create()

                val page = document.startPage(pageInfo)
                try {
                    val canvas = page.canvas
                    var currentY = PdfGenerator.LayoutPolicy.MARGIN_VERTICAL + PdfGenerator.LayoutPolicy.FONT_SIZE
                    for (line in pageLines) {
                        canvas.drawText(
                            line,
                            PdfGenerator.LayoutPolicy.MARGIN_HORIZONTAL,
                            currentY,
                            paint,
                        )
                        currentY += PdfGenerator.LayoutPolicy.LINE_HEIGHT
                    }
                } finally {
                    document.finishPage(page)
                }
            }

            FileOutputStream(targetFile).use { out ->
                document.writeTo(out)
            }

            targetFile
        } catch (t: Throwable) {
            // Clean up incomplete target file on failure
            if (targetFile.exists()) {
                targetFile.delete()
            }
            throw t
        } finally {
            document.close()
        }
    }

    internal fun sanitizeFileName(name: String): String {
        val trimmed = name.trim()
        val hasPdfExtension = trimmed.endsWith(".pdf", ignoreCase = true)
        val rawBase = if (hasPdfExtension) {
            trimmed.substring(0, trimmed.length - 4)
        } else {
            trimmed
        }
        val extension = if (hasPdfExtension) {
            trimmed.substring(trimmed.length - 4)
        } else {
            ".pdf"
        }
        val baseName = if (rawBase.isBlank()) "export" else rawBase
        val sanitizedBase = baseName.replace(Regex("[/\\\\?%*:|\"<>]|\\.+"), "_")
        val finalBase = if (sanitizedBase.isBlank()) "export" else sanitizedBase
        return "$finalBase$extension"
    }

    companion object {
        const val EXPORTS_DIR = "exports"
    }
}
