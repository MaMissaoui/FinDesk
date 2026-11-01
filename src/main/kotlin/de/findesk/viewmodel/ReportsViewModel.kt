package de.findesk.viewmodel

import de.findesk.document.ReportService
import de.findesk.document.SystemTaxPrinter
import de.findesk.document.TaxPrinter
import de.findesk.document.TaxReportExporter
import de.findesk.document.TaxSuggestion
import de.findesk.document.TaxSuggestions
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.util.Currencies
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.apache.pdfbox.pdmodel.PDDocument
import java.io.File
import java.time.LocalDate

data class ReportsState(
    val selectedYear: Int = LocalDate.now().year,
    /** Non-null: report over this custom range instead of [selectedYear]. */
    val customRange: Pair<LocalDate, LocalDate>? = null,
    val taxReport: ReportService.TaxReport? = null,
    /** The Tax bookings tab's groups for the selected period. */
    val taxBookings: ReportService.TaxBookings? = null,
    /** The period [taxReport] and [taxBookings] were generated for; null until a load publishes them. */
    val loadedRange: Pair<LocalDate, LocalDate>? = null,
    /** Keyword suggestions for the selected period; accepting or dismissing one stores a manual override. */
    val suggestions: List<TaxSuggestion> = emptyList(),
    /** Whether the tab's rows are filtered to bookings without a receipt (totals stay whole-period). */
    val missingReceiptsOnly: Boolean = false,
    /** Detected over the last 13 months, independent of the selected period. */
    val recurring: List<de.findesk.document.RecurringDetector.RecurringPayment> = emptyList(),
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class ReportsViewModel(
    private val reportService: ReportService = ReportService(),
    private val exporter: TaxReportExporter = TaxReportExporter(),
    private val printer: TaxPrinter = SystemTaxPrinter(),
    private val transactionRepository: de.findesk.repository.TransactionRepository = de.findesk.repository.TransactionRepositoryImpl(),
    private val settingsRepository: de.findesk.repository.SettingsRepository = de.findesk.repository.SettingsRepositoryImpl(),
    private val auditLogRepository: de.findesk.repository.AuditLogRepository = de.findesk.repository.AuditLogRepositoryImpl(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BaseViewModel(scope) {
    private val _state = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = _state.asStateFlow()

    private val onError: (Exception) -> Unit = { e ->
        _state.update { it.copy(isLoading = false, isExporting = false, error = e.userMessage()) }
    }

    fun loadTaxReport(year: Int = _state.value.selectedYear): Job {
        _state.update { it.copy(isLoading = true, error = null, selectedYear = year) }
        return launchSafely(onError) {
            val from = LocalDate.of(year, 1, 1)
            val to = LocalDate.of(year, 12, 31)
            val report = reportService.generateTaxReport(year)
            val taxBookings = reportService.generateTaxBookings(from, to)
            val suggestions = findSuggestions(from, to)
            // Ignore stale results if the year changed meanwhile
            _state.update {
                if (it.selectedYear == year && it.customRange == null) {
                    it.copy(taxReport = report, taxBookings = taxBookings, loadedRange = from to to, suggestions = suggestions, isLoading = false)
                } else {
                    it
                }
            }
        }
    }

    fun loadRecurring(today: LocalDate = LocalDate.now()): Job = launchSafely(onError) {
        val txns = transactionRepository.findItems(de.findesk.model.TransactionFilter(startDate = today.minusDays(400), endDate = today))
        _state.update { it.copy(recurring = de.findesk.document.RecurringDetector.detect(txns, today)) }
    }

    fun updateYear(year: Int): Job {
        _state.update { it.copy(customRange = null) }
        return loadTaxReport(year)
    }

    fun setCustomRange(from: LocalDate, to: LocalDate): Job {
        if (from.isAfter(to)) {
            _state.update { it.copy(error = AppStrings[StringKey.InvalidDateRange]) }
            return Job().apply { complete() }
        }
        _state.update { it.copy(customRange = from to to, isLoading = true, error = null) }
        return launchSafely(onError) {
            val report = reportService.generateRangeReport(from, to)
            val taxBookings = reportService.generateTaxBookings(from, to)
            val suggestions = findSuggestions(from, to)
            _state.update {
                if (it.customRange == from to to) {
                    it.copy(taxReport = report, taxBookings = taxBookings, loadedRange = from to to, suggestions = suggestions, isLoading = false)
                } else {
                    it
                }
            }
        }
    }

    /** Marks the booking tax relevant (the suggestion's accept). */
    fun acceptSuggestion(id: Int): Job = setTaxOverride(listOf(id), true)

    /** Hides the suggestion for good by storing a "not relevant" override. */
    fun dismissSuggestion(id: Int): Job = setTaxOverride(listOf(id), false)

    /** Row action: marks a booking not tax relevant. */
    fun markNotTaxRelevant(id: Int): Job = setTaxOverride(listOf(id), false)

    /** Row action: resets a booking's manual override, so rule and category decide again. */
    fun resetTaxAutomatic(id: Int): Job = setTaxOverride(listOf(id), null)

    /**
     * One manual override for [ids]: a single `SET_TAX_OVERRIDE` audit entry with the count and the value word
     * only (no booking text), like [TransactionsViewModel], then the tab's data and the summary reload, whose
     * tax-relevant total may change.
     */
    private fun setTaxOverride(ids: List<Int>, value: Boolean?): Job = launchSafely(onError) {
        _state.update { it.copy(isLoading = true, error = null) }
        val changed = transactionRepository.setTaxOverride(ids, value)
        if (changed > 0) {
            auditLogRepository.log("SET_TAX_OVERRIDE", "user", "Set tax override for $changed bookings: ${taxOverrideWord(value)}")
            _state.update { it.copy(message = AppStrings.format(StringKey.MessageTaxOverrideSet, changed)) }
        }
        reloadTab()
    }

    /** The override's word for the `SET_TAX_OVERRIDE` audit entry (records stay English; count only, no booking text). */
    private fun taxOverrideWord(value: Boolean?): String = when (value) {
        true -> "relevant"
        false -> "not relevant"
        null -> "automatic"
    }

    /** Reloads the tab's data and the summary for the period the screen shows; stale periods keep their result. */
    private suspend fun reloadTab() {
        val range = _state.value.customRange
        val from: LocalDate
        val to: LocalDate
        if (range != null) {
            from = range.first
            to = range.second
        } else {
            val year = _state.value.selectedYear
            from = LocalDate.of(year, 1, 1)
            to = LocalDate.of(year, 12, 31)
        }
        val report = reportService.generateRangeReport(from, to)
        val taxBookings = reportService.generateTaxBookings(from, to)
        val suggestions = findSuggestions(from, to)
        _state.update {
            val stillCurrent = if (range != null) it.customRange == range else it.customRange == null
            if (stillCurrent) {
                it.copy(taxReport = report, taxBookings = taxBookings, loadedRange = from to to, suggestions = suggestions, isLoading = false)
            } else {
                it
            }
        }
    }

    /** Keyword suggestions over the period's bookings of the primary currency. */
    private suspend fun findSuggestions(from: LocalDate, to: LocalDate): List<TaxSuggestion> {
        val bookings = transactionRepository.findItems(de.findesk.model.TransactionFilter(startDate = from, endDate = to, currency = Currencies.primary(settingsRepository)))
        return TaxSuggestions.find(bookings, TaxSuggestions.keywords(settingsRepository))
    }

    /** Filters the tab's rows only; the totals stay those of the whole period. */
    fun setMissingReceiptsOnly(only: Boolean): Job {
        _state.update { it.copy(missingReceiptsOnly = only) }
        return Job().apply { complete() }
    }

    fun exportToPdf(file: File): Job {
        _state.update { it.copy(isExporting = true, error = null) }
        return launchSafely(onError) {
            val range = currentRange()
            val cached = _state.value.taxReport?.takeIf { _state.value.loadedRange == range }
            // Never put another period's report into a file named for the current one
            val report = cached ?: if (_state.value.customRange != null) {
                reportService.generateRangeReport(range.first, range.second)
            } else {
                reportService.generateTaxReport(_state.value.selectedYear)
            }
            val ok = reportService.exportToPdf(report, file)
            _state.update {
                it.copy(isExporting = false, message = if (ok) AppStrings.format(StringKey.MessageSavedTo, file.name) else null, error = if (ok) null else AppStrings[StringKey.ErrorPdfExportFailed])
            }
        }
    }

    fun exportToCsv(file: File): Job {
        _state.update { it.copy(isExporting = true, error = null) }
        return launchSafely(onError) {
            val range = _state.value.customRange
            val ok = if (range != null) reportService.exportRangeToCsv(range.first, range.second, file) else reportService.exportYearToCsv(_state.value.selectedYear, file)
            _state.update {
                it.copy(isExporting = false, message = if (ok) AppStrings.format(StringKey.MessageSavedTo, file.name) else null, error = if (ok) null else AppStrings[StringKey.ErrorCsvExportFailed])
            }
        }
    }

    /** Exports the tab's data as an Excel workbook (read-only: no audit entry, like the PDF/CSV exports). */
    fun exportTaxXlsx(file: File): Job = exportTax({ exporter.exportXlsx(it, file) }, file)

    /** Exports the tab's data as the printable PDF. */
    fun exportTaxPdf(file: File): Job = exportTax({ exporter.exportPdf(it, file) }, file)

    private fun exportTax(export: (ReportService.TaxBookings) -> Unit, file: File): Job {
        _state.update { it.copy(isExporting = true, error = null) }
        return launchSafely(onError) {
            try {
                export(currentTaxBookings())
                _state.update { it.copy(isExporting = false, message = AppStrings.format(StringKey.MessageSavedTo, file.name)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The exception itself can carry bank data, so only the localized error is shown
                _state.update { it.copy(isExporting = false, error = AppStrings[StringKey.ErrorTaxExportFailed]) }
            }
        }
    }

    /**
     * Prints the tab's PDF through the system print dialog. The document is built in memory and closed in
     * all cases; a cancelled dialog shows no message, a failed print only the localized error.
     */
    fun printTax(): Job {
        _state.update { it.copy(isExporting = true, error = null) }
        return launchSafely(onError) {
            var document: PDDocument? = null
            try {
                val bookings = currentTaxBookings()
                document = exporter.buildPdf(bookings)
                val sent = printer.print(document, AppStrings.format(StringKey.TaxExportTitle, bookings.title))
                _state.update { it.copy(isExporting = false, message = if (sent) AppStrings[StringKey.MessagePrintSent] else null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The exception itself can carry bank data, so only the localized error is shown
                _state.update { it.copy(isExporting = false, error = AppStrings[StringKey.ErrorPrintFailed]) }
            } finally {
                document?.let { runCatching { it.close() } }
            }
        }
    }

    /** The period the tab shows: the custom range, or the selected calendar year. */
    private fun currentRange(): Pair<LocalDate, LocalDate> {
        val range = _state.value.customRange
        if (range != null) return range
        val year = _state.value.selectedYear
        return LocalDate.of(year, 1, 1) to LocalDate.of(year, 12, 31)
    }

    /** The tab's data, loading it first when the screen has not finished loading yet or the period has changed. */
    private suspend fun currentTaxBookings(): ReportService.TaxBookings {
        val range = currentRange()
        _state.value.taxBookings?.takeIf { _state.value.loadedRange == range }?.let { return it }
        return reportService.generateTaxBookings(range.first, range.second)
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, message = null) }
    }
}
