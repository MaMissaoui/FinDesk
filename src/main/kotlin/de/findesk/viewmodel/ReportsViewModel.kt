package de.findesk.viewmodel

import de.findesk.document.ReportService
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.LocalDate

data class ReportsState(
    val selectedYear: Int = LocalDate.now().year,
    /** Non-null: report over this custom range instead of [selectedYear]. */
    val customRange: Pair<LocalDate, LocalDate>? = null,
    val taxReport: ReportService.TaxReport? = null,
    /** Detected over the last 13 months, independent of the selected period. */
    val recurring: List<de.findesk.document.RecurringDetector.RecurringPayment> = emptyList(),
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class ReportsViewModel(
    private val reportService: ReportService = ReportService(),
    private val transactionRepository: de.findesk.repository.TransactionRepository = de.findesk.repository.TransactionRepositoryImpl(),
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
            val report = reportService.generateTaxReport(year)
            // Ignore stale results if the year changed meanwhile
            _state.update { if (it.selectedYear == year && it.customRange == null) it.copy(taxReport = report, isLoading = false) else it }
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
            _state.update { if (it.customRange == from to to) it.copy(taxReport = report, isLoading = false) else it }
        }
    }

    fun exportToPdf(file: File): Job {
        _state.update { it.copy(isExporting = true, error = null) }
        return launchSafely(onError) {
            val report = _state.value.taxReport ?: reportService.generateTaxReport(_state.value.selectedYear)
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

    fun clearMessages() {
        _state.update { it.copy(error = null, message = null) }
    }
}
