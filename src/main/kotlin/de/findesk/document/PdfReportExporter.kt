package de.findesk.document

import de.findesk.util.Formats
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Renders a [ReportService.TaxReport] as a simple multi-page A4 PDF.
 */
class PdfReportExporter {
    private val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    fun export(report: ReportService.TaxReport, outputFile: File) {
        PDDocument().use { doc ->
            val writer = PageWriter(doc)
            writer.text(if (report.isCalendarYear) "FinDesk Tax Report ${report.year}" else "FinDesk Report ${report.title}", bold, 18f)
            writer.text("Generated ${Formats.date(LocalDate.now())}", regular, 9f)
            writer.gap(12f)

            writer.heading("Summary")
            writer.row("Total income", Formats.money(report.totalIncome))
            writer.row("Total expenses", Formats.money(report.totalExpenses))
            writer.row("Net income", Formats.money(report.netIncome))
            writer.row("Tax-relevant expenses", Formats.money(report.taxRelevantTotal))
            writer.row("Transactions", report.transactionCount.toString())
            writer.gap(10f)

            writer.heading("Tax-relevant expenses by category")
            if (report.taxRelevantExpenses.isEmpty()) {
                writer.text("No categories are marked as tax-relevant.", regular, 10f)
            }
            report.taxRelevantExpenses.forEach { (category, amount) -> writer.row(category, Formats.money(amount)) }
            writer.gap(10f)

            writer.heading("Monthly breakdown")
            writer.row("Month", "Income / Expenses / Net", font = bold)
            report.monthlyBreakdown.forEach {
                val name = Month.of(it.month).getDisplayName(TextStyle.FULL, Locale.ENGLISH) + if (report.isCalendarYear) "" else " ${it.year}"
                writer.row(name, "${Formats.money(it.income)} / ${Formats.money(it.expenses)} / ${Formats.money(it.net)}")
            }
            writer.gap(10f)

            writer.heading("Expenses by category")
            report.expensesByCategory.forEach { (category, amount) -> writer.row(category, Formats.money(amount)) }

            if (report.taxRelevantTransactions.isNotEmpty()) {
                writer.gap(10f)
                writer.heading("Tax-relevant transactions")
                report.taxRelevantTransactions.sortedBy { it.date }.forEach {
                    writer.row(
                        "${Formats.date(it.date)}  ${it.description.take(55)}  (${it.categoryName.orEmpty()})",
                        Formats.money(it.amount.abs()),
                    )
                }
            }

            writer.close()
            doc.save(outputFile)
        }
    }

    private inner class PageWriter(private val doc: PDDocument) {
        private val margin = 50f
        private val pageSize = PDRectangle.A4
        private var stream: PDPageContentStream? = null
        private var y = 0f

        private fun ensureSpace(height: Float) {
            if (stream == null || y - height < margin) {
                stream?.close()
                val page = PDPage(pageSize)
                doc.addPage(page)
                stream = PDPageContentStream(doc, page)
                y = pageSize.height - margin
            }
        }

        fun gap(height: Float) {
            y -= height
        }

        fun heading(text: String) {
            text(text, bold, 12f)
            gap(2f)
        }

        fun text(text: String, font: PDType1Font, size: Float) {
            ensureSpace(size + 4f)
            y -= size + 4f
            draw(text, font, size, margin)
        }

        fun row(label: String, value: String, font: PDType1Font = regular, size: Float = 10f) {
            ensureSpace(size + 5f)
            y -= size + 5f
            draw(label, font, size, margin)
            val safeValue = sanitize(value, font)
            val width = font.getStringWidth(safeValue) / 1000f * size
            draw(safeValue, font, size, pageSize.width - margin - width)
        }

        private fun draw(text: String, font: PDType1Font, size: Float, x: Float) {
            val s = stream ?: return
            s.beginText()
            s.setFont(font, size)
            s.newLineAtOffset(x, y)
            s.showText(sanitize(text, font))
            s.endText()
        }

        fun close() {
            stream?.close()
            stream = null
        }
    }

    companion object {
        /** Standard 14 fonts use WinAnsiEncoding; replace anything they can't encode. */
        internal fun sanitize(text: String, font: PDType1Font): String = buildString {
            text.forEach { c ->
                val ch = if (c == '\n' || c == '\r' || c == '\t') ' ' else c
                append(
                    try {
                        font.encode(ch.toString())
                        ch
                    } catch (e: IllegalArgumentException) {
                        '?'
                    },
                )
            }
        }
    }
}
