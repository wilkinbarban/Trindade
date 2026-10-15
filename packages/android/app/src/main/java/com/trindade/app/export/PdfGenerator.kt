package com.trindade.app.export

import java.io.File

/**
 * Generates PDF documents from text content.
 *
 * An interface for the same architectural reason [com.trindade.app.reports.PhotoCompressor] and
 * [com.trindade.app.auth.TokenStore] are: the platform implementation ([AndroidPdfGenerator])
 * requires Android's native graphics and PDF runtime ([android.graphics.pdf.PdfDocument]),
 * while the layout and pagination policy ([LayoutPolicy]) is pure and fully testable on the JVM.
 */
interface PdfGenerator {

    /**
     * Generates an A4 PDF document containing the server-provided [text] in monospace format
     * inside the application's cache exports directory.
     *
     * @param text The verbatim text to be laid out and paginated onto the PDF.
     * @param fileName The desired output file name (e.g. "report-123.pdf").
     * @return The generated [File] located in the cache exports directory.
     */
    suspend fun generate(text: String, fileName: String): File

    /**
     * Pure layout and pagination specifications and policy for A4 monospace PDF generation.
     *
     * Kept pure and portable so that text wrapping, line limits, and deterministic multi-page
     * pagination can be verified on the JVM without requiring Android native graphics binaries.
     */
    object LayoutPolicy {
        /** Standard A4 page width in PostScript points (72 points/inch: 210mm = 8.27in * 72 = 595pt). */
        const val PAGE_WIDTH_PT = 595

        /** Standard A4 page height in PostScript points (72 points/inch: 297mm = 11.69in * 72 = 842pt). */
        const val PAGE_HEIGHT_PT = 842

        /** Horizontal margins (left and right) in points. */
        const val MARGIN_HORIZONTAL = 40f

        /** Vertical margins (top and bottom) in points. */
        const val MARGIN_VERTICAL = 40f

        /** Font size for monospace text in points. */
        const val FONT_SIZE = 9f

        /** Line height in points (proportional to font size for readable monospace density). */
        const val LINE_HEIGHT = 13f

        /** Usable printable width between margins. */
        const val USABLE_WIDTH = PAGE_WIDTH_PT - (MARGIN_HORIZONTAL * 2f)

        /** Usable printable height between margins. */
        const val USABLE_HEIGHT = PAGE_HEIGHT_PT - (MARGIN_VERTICAL * 2f)

        /**
         * Maximum characters per line in monospace layout.
         *
         * Standard monospace glyph width at 9pt is approximately 5.4pt.
         * 515pt usable width / 5.4pt = ~95 chars. Using 80 columns ensures standard terminal
         * and report text fits comfortably within margins across all platform monospace typefaces.
         */
        const val MAX_CHARS_PER_LINE = 80

        /**
         * Maximum number of lines that fit on a single A4 page within vertical margins.
         */
        val LINES_PER_PAGE: Int = (USABLE_HEIGHT / LINE_HEIGHT).toInt()

        /**
         * Wraps a single line of text into one or more lines that fit within [maxChars],
         * preserving spaces and verbatim content as faithfully as possible.
         */
        fun wrapLine(line: String, maxChars: Int = MAX_CHARS_PER_LINE): List<String> {
            val sanitized = line.replace("\t", "    ")
            if (sanitized.length <= maxChars) return listOf(sanitized)

            val wrapped = mutableListOf<String>()
            var remaining = sanitized
            while (remaining.length > maxChars) {
                // Find last whitespace within maxChars to wrap cleanly at words
                val breakIdx = remaining.lastIndexOf(' ', maxChars)
                if (breakIdx > 0) {
                    wrapped.add(remaining.substring(0, breakIdx))
                    remaining = remaining.substring(breakIdx + 1)
                } else {
                    // No whitespace available: break strictly at maxChars
                    wrapped.add(remaining.substring(0, maxChars))
                    remaining = remaining.substring(maxChars)
                }
            }
            if (remaining.isNotEmpty()) {
                wrapped.add(remaining)
            }
            return wrapped
        }

        /**
         * Breaks server-provided text into wrapped lines, preserving explicit line breaks and empty lines.
         */
        fun wrapText(text: String, maxChars: Int = MAX_CHARS_PER_LINE): List<String> {
            if (text.isEmpty()) return emptyList()
            val rawLines = text.lines()
            return rawLines.flatMap { wrapLine(it, maxChars) }
        }

        /**
         * Paginates wrapped lines into deterministic pages.
         *
         * Each page contains at most [linesPerPage] lines.
         * Empty text produces a single empty page.
         */
        fun paginate(
            text: String,
            maxChars: Int = MAX_CHARS_PER_LINE,
            linesPerPage: Int = LINES_PER_PAGE,
        ): List<List<String>> {
            val lines = wrapText(text, maxChars)
            if (lines.isEmpty()) {
                return listOf(emptyList())
            }
            return lines.chunked(linesPerPage)
        }
    }
}
