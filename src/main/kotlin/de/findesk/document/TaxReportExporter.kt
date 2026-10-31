package de.findesk.document

import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.TaxSource
import de.findesk.model.TransactionItem
import de.findesk.util.BankText
import de.findesk.util.Formats
import de.findesk.util.PaymentProviders
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate

/**
 * Exports the Tax bookings view ([ReportService.TaxBookings]) as an Excel workbook (two sheets, no formulas)
 * and as a printable A4 PDF. The PDF is also what "Print" sends to the printer, so both share one layout.
 * All strings come from [AppStrings], every amount is in the report's currency.
 */
class TaxReportExporter {
    private val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    /**
     * Writes the workbook: an overview sheet (one row per group plus the totals) and a bookings sheet (one
     * row per booking, frozen header with a filter over every column).
     */
    fun exportXlsx(bookings: ReportService.TaxBookings, file: File) {
        val writer = XlsxWriter()
        overviewSheet(writer, bookings)
        bookingsSheet(writer, bookings)
        writer.write(file)
    }

    /** Builds the printable report in memory; "Export PDF" saves it, "Print" hands it to the printer. */
    fun buildPdf(bookings: ReportService.TaxBookings): PDDocument {
        val doc = PDDocument()
        try {
            val layout = PdfLayout(doc)
            layout.text(AppStrings.format(StringKey.TaxExportTitle, bookings.title), bold, 18f)
            layout.text("${AppStrings[StringKey.ColCurrency]}: ${bookings.currency}", regular, 10f)
            layout.text(AppStrings.format(StringKey.PdfGenerated, Formats.date(LocalDate.now())), regular, 9f)
            layout.gap(12f)
            layout.row(AppStrings[StringKey.TaxIncomeTotal], pdfAmount(bookings.incomeTotal))
            layout.row(AppStrings[StringKey.TaxDeductionTotal], pdfAmount(bookings.deductionTotal))
            layout.row(AppStrings[StringKey.NetIncome], pdfAmount(bookings.netTotal))
            layout.row(AppStrings[StringKey.TaxReceipts], AppStrings.format(StringKey.TaxReceiptsOf, bookings.withReceipt, bookings.bookingCount))
            if (bookings.otherCurrencyBookings > 0) {
                layout.text(AppStrings.format(StringKey.OtherCurrenciesExcluded, bookings.currency, bookings.otherCurrencyBookings), regular, 9f)
            }
            layout.gap(10f)
            layout.section(AppStrings[StringKey.TaxSectionIncome], bookings.incomeGroups, bookings.currency)
            layout.section(AppStrings[StringKey.TaxSectionDeductions], bookings.deductionGroups, bookings.currency)
            layout.finish()
            drawFooters(doc)
            return doc
        } catch (e: Exception) {
            runCatching { doc.close() }
            throw e
        }
    }

    /** Saves the report as a PDF file, through a temp file next to the target. */
    fun exportPdf(bookings: ReportService.TaxBookings, file: File) {
        buildPdf(bookings).use { doc ->
            writeAtomically(file) { temp -> doc.save(temp) }
        }
    }

    private fun overviewSheet(writer: XlsxWriter, bookings: ReportService.TaxBookings) {
        writer.sheet(
            AppStrings[StringKey.XlsxSheetOverview],
            listOf(14.0, 34.0, 10.0, 14.0, 14.0, 14.0, 12.0),
        ) {
            row(bold(AppStrings.format(StringKey.TaxExportTitle, bookings.title)))
            row(text("${AppStrings[StringKey.ColCurrency]}: ${bookings.currency}"))
            row(
                bold(AppStrings[StringKey.TaxColSection]),
                bold(AppStrings[StringKey.ColCategory]),
                bold(AppStrings[StringKey.TaxColBookings]),
                bold(AppStrings[StringKey.ColIncome]),
                bold(AppStrings[StringKey.ColExpenses]),
                bold(AppStrings[StringKey.ColNet]),
                bold(AppStrings[StringKey.TaxColWithReceipt]),
            )
            bookings.incomeGroups.forEach { groupRow(StringKey.TaxSectionIncome, it) }
            bookings.deductionGroups.forEach { groupRow(StringKey.TaxSectionDeductions, it) }
            row(empty(), bold(AppStrings[StringKey.TaxIncomeTotal]), empty(), money(bookings.incomeTotal))
            row(empty(), bold(AppStrings[StringKey.TaxDeductionTotal]), empty(), empty(), money(bookings.deductionTotal))
            row(empty(), bold(AppStrings[StringKey.NetIncome]), empty(), empty(), empty(), money(bookings.netTotal))
            row(
                empty(),
                bold(AppStrings[StringKey.TaxReceipts]),
                text(AppStrings.format(StringKey.TaxReceiptsOf, bookings.withReceipt, bookings.bookingCount)),
            )
            if (bookings.otherCurrencyBookings > 0) {
                row(text(AppStrings.format(StringKey.OtherCurrenciesExcluded, bookings.currency, bookings.otherCurrencyBookings)))
            }
        }
    }

    private fun XlsxWriter.Sheet.groupRow(section: StringKey, group: ReportService.TaxGroup) {
        row(
            text(AppStrings[section]),
            text(group.name),
            number(BigDecimal(group.bookings.size)),
            money(group.income),
            money(group.expenses),
            money(group.net),
            number(BigDecimal(group.withReceipt)),
        )
    }

    private fun bookingsSheet(writer: XlsxWriter, bookings: ReportService.TaxBookings) {
        writer.sheet(
            AppStrings[StringKey.XlsxSheetBookings],
            listOf(13.0, 24.0, 12.0, 30.0, 18.0, 34.0, 26.0, 12.0, 10.0, 10.0, 44.0),
            freezeHeader = true,
            autoFilter = true,
        ) {
            row(
                bold(AppStrings[StringKey.TaxColSection]),
                bold(AppStrings[StringKey.ColCategory]),
                bold(AppStrings[StringKey.ColDate]),
                bold(AppStrings[StringKey.ColPartner]),
                bold(AppStrings[StringKey.ColShop]),
                bold(AppStrings[StringKey.ColPurpose]),
                bold(AppStrings[StringKey.ColAccount]),
                bold(AppStrings[StringKey.ColAmount]),
                bold(AppStrings[StringKey.ColCurrency]),
                bold(AppStrings[StringKey.TaxColReceipt]),
                bold(AppStrings[StringKey.TaxColSource]),
            )
            bookings.incomeGroups.forEach { group -> group.bookings.forEach { bookingRow(StringKey.TaxSectionIncome, group, it) } }
            bookings.deductionGroups.forEach { group -> group.bookings.forEach { bookingRow(StringKey.TaxSectionDeductions, group, it) } }
        }
    }

    private fun XlsxWriter.Sheet.bookingRow(section: StringKey, group: ReportService.TaxGroup, txn: TransactionItem) {
        row(
            text(AppStrings[section]),
            text(group.name),
            date(txn.date),
            text(txn.partnerName.orEmpty()),
            text(PaymentProviders.merchantOf(txn.purpose, txn.partnerName).orEmpty()),
            text(txn.purpose.orEmpty()),
            text(txn.accountIban),
            money(txn.amount),
            text(txn.currency),
            text(if (txn.documentCount > 0) AppStrings[StringKey.Yes] else AppStrings[StringKey.No]),
            text(sourceLine(txn)),
        )
    }

    /** Which layer decided the booking's tax relevance, in the same wording as the app's details dialog. */
    private fun sourceLine(txn: TransactionItem): String {
        val base = if (txn.isTaxRelevant) AppStrings[StringKey.TaxRelevantChoice] else AppStrings[StringKey.TaxNotRelevantChoice]
        return when (txn.taxSource) {
            TaxSource.MANUAL -> AppStrings.format(StringKey.TaxSetByYou, base)
            TaxSource.RULE -> AppStrings.format(StringKey.TaxFromRule, base, txn.taxRulePattern.orEmpty())
            TaxSource.CATEGORY -> AppStrings.format(StringKey.TaxFromCategory, base, txn.categoryName ?: AppStrings[StringKey.Uncategorized])
            TaxSource.NONE -> base
        }
    }

    private fun drawFooters(doc: PDDocument) {
        val pageCount = doc.numberOfPages
        for (index in 0 until pageCount) {
            PDPageContentStream(doc, doc.getPage(index), PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                val text = sanitize(AppStrings.format(StringKey.PdfPageOf, index + 1, pageCount), regular)
                val width = widthOf(text, regular, FOOTER_SIZE)
                stream.beginText()
                stream.setFont(regular, FOOTER_SIZE)
                stream.newLineAtOffset(PAGE_WIDTH - MARGIN - width, FOOTER_Y)
                stream.showText(text)
                stream.endText()
            }
        }
    }

    /**
     * The report's layout: flowing text on A4 pages, then the per-group tables. A group header is only drawn
     * when its table's header and first row fit on the same page, so it never sits alone at a page bottom.
     */
    private inner class PdfLayout(private val doc: PDDocument) {
        private var stream: PDPageContentStream? = null
        private var y = 0f

        private fun newPage() {
            stream?.close()
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            stream = PDPageContentStream(doc, page)
            y = PAGE_HEIGHT - MARGIN
        }

        /** Makes room for [height] measured from the current line, turning the page when needed. */
        private fun ensure(height: Float) {
            if (stream == null || y - height < MARGIN) newPage()
        }

        fun gap(height: Float) {
            y -= height
        }

        fun text(value: String, font: PDType1Font, size: Float) {
            ensure(size + 4f)
            y -= size + 4f
            draw(value, font, size, MARGIN)
        }

        fun row(label: String, value: String, size: Float = 10f) {
            ensure(size + 5f)
            y -= size + 5f
            draw(label, regular, size, MARGIN)
            val safe = sanitize(value, regular)
            draw(safe, regular, size, PAGE_WIDTH - MARGIN - widthOf(safe, regular, size))
        }

        /** One section with its groups; a section without groups is left out entirely. */
        fun section(title: String, groups: List<ReportService.TaxGroup>, currency: String) {
            if (groups.isEmpty()) return
            text(title, bold, 12f)
            gap(2f)
            groups.forEach { group ->
                ensure(GROUP_HEADER_HEIGHT + TABLE_HEADER_HEIGHT + ROW_HEIGHT)
                y -= GROUP_HEADER_HEIGHT
                draw(
                    "${group.name} · ${group.bookings.size} · ${pdfAmount(group.net, signed = true)}",
                    bold,
                    11f,
                    MARGIN,
                )
                tableHeader()
                group.bookings.forEach { txn -> tableRow(txn) }
                gap(6f)
            }
        }

        fun tableHeader() {
            ensure(TABLE_HEADER_HEIGHT + ROW_HEIGHT)
            y -= TABLE_HEADER_HEIGHT
            drawFitted(AppStrings[StringKey.ColDate], bold, 8f, X_DATE, W_DATE)
            drawFitted(AppStrings[StringKey.ColDescription], bold, 8f, X_DESC, W_DESC)
            drawFitted(AppStrings[StringKey.ColAccount], bold, 8f, X_ACCOUNT, W_ACCOUNT)
            drawRightFitted(AppStrings[StringKey.ColAmount], bold, 8f, AMOUNT_RIGHT_EDGE, W_AMOUNT)
            drawRightFitted(AppStrings[StringKey.TaxColReceipt], bold, 8f, RECEIPT_RIGHT_EDGE, W_RECEIPT)
        }

        fun tableRow(txn: TransactionItem) {
            ensure(ROW_HEIGHT)
            y -= ROW_HEIGHT
            drawFitted(Formats.date(txn.date), regular, 9f, X_DATE, W_DATE)
            drawFitted(BankText.displayDescription(txn), regular, 9f, X_DESC, W_DESC)
            drawFitted(shortAccount(txn), regular, 9f, X_ACCOUNT, W_ACCOUNT)
            drawRightFitted(pdfAmount(txn.amount, signed = true), regular, 9f, AMOUNT_RIGHT_EDGE, W_AMOUNT)
            drawRightFitted(
                if (txn.documentCount > 0) AppStrings[StringKey.Yes] else AppStrings[StringKey.No],
                regular,
                9f,
                RECEIPT_RIGHT_EDGE,
                W_RECEIPT,
            )
        }

        fun finish() {
            stream?.close()
            stream = null
        }

        private fun shortAccount(txn: TransactionItem): String = txn.accountIban.takeIf { it.isNotBlank() }?.let { "…${it.takeLast(4)}" }.orEmpty()

        private fun drawFitted(value: String, font: PDType1Font, size: Float, x: Float, width: Float) {
            draw(fit(value, font, size, width), font, size, x)
        }

        private fun drawRightFitted(value: String, font: PDType1Font, size: Float, rightEdge: Float, width: Float) {
            val text = fit(value, font, size, width)
            draw(text, font, size, rightEdge - widthOf(text, font, size))
        }

        private fun draw(value: String, font: PDType1Font, size: Float, x: Float) {
            val current = stream ?: return
            val safe = sanitize(value, font)
            current.beginText()
            current.setFont(font, size)
            current.newLineAtOffset(x, y)
            current.showText(safe)
            current.endText()
        }

        /** Cuts [value] to [width] with an ellipsis, measured with [font], so text never runs into the next column. */
        private fun fit(value: String, font: PDType1Font, size: Float, width: Float): String {
            val text = sanitize(value, font)
            if (widthOf(text, font, size) <= width) return text
            var end = text.length
            while (end > 0) {
                val candidate = text.substring(0, end).trimEnd() + ELLIPSIS
                if (widthOf(candidate, font, size) <= width) return candidate
                end--
            }
            return ELLIPSIS
        }
    }

    companion object {
        private val PAGE_WIDTH = PDRectangle.A4.width
        private val PAGE_HEIGHT = PDRectangle.A4.height
        private const val MARGIN = 50f
        private const val FOOTER_SIZE = 8f
        private const val FOOTER_Y = 28f
        private const val GROUP_HEADER_HEIGHT = 15f
        private const val TABLE_HEADER_HEIGHT = 12f
        private const val ROW_HEIGHT = 14f

        // The table's five columns across the 495 pt between the margins (A4 is 595 pt wide)
        private const val X_DATE = 50f
        private const val W_DATE = 56f
        private const val X_DESC = 112f
        private const val W_DESC = 232f
        private const val X_ACCOUNT = 350f
        private const val W_ACCOUNT = 74f
        private const val AMOUNT_RIGHT_EDGE = 500f
        private const val W_AMOUNT = 76f
        private val RECEIPT_RIGHT_EDGE = PAGE_WIDTH - MARGIN
        private const val W_RECEIPT = 40f
        private const val ELLIPSIS = "…"
        private const val EURO_WIDTH = 0.556f

        /** Standard 14 fonts use WinAnsiEncoding; replace anything they can't encode. */
        private fun sanitize(text: String, font: PDType1Font): String = PdfReportExporter.sanitize(text, font)

        /**
         * The string's drawn width. Helvetica's standard AFM carries no width for the euro sign (it reads 0,
         * which would overlap the next glyph in right-aligned amounts), so the euro is measured at its real
         * WinAnsi width (556/1000 em, like the digits around it).
         */
        private fun widthOf(text: String, font: PDType1Font, size: Float): Float = text.fold(0f) { acc, c ->
            acc + (if (c == '€') EURO_WIDTH else font.getStringWidth(c.toString()) / 1000f)
        } * size
    }
}

/**
 * A PDF amount without the currency symbol (the header states the currency): the Standard 14 fonts carry no
 * width for "€", so the symbol overlapped the first digit. Same separators as [Formats.money].
 */
internal fun pdfAmount(amount: BigDecimal, signed: Boolean = false): String {
    val abs = DecimalFormat("#,##0.00", DecimalFormatSymbols(AppStrings.current.language.locale)).format(amount.abs().setScale(2, RoundingMode.HALF_UP))
    val sign = when {
        amount.signum() < 0 -> "-"
        signed && amount.signum() > 0 -> "+"
        else -> ""
    }
    return "$sign$abs"
}
