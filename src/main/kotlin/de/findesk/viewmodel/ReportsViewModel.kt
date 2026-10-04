package de.findesk.viewmodel

import de.findesk.document.ReportService
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
    val taxReport: ReportService.TaxReport? = null,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class ReportsViewModel(
    private val reportService: ReportService = ReportService(),
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
            _state.update { if (it.selectedYear == year) it.copy(taxReport = report, isLoading = false) else it }
        }
    }

    fun updateYear(year: Int): Job = loadTaxReport(year)

    fun exportToPdf(file: File): Job {
        _state.update { it.copy(isExporting = true, error = null) }
        return launchSafely(onError) {
            val report = _state.value.taxReport ?: reportService.generateTaxReport(_state.value.selectedYear)
            val ok = reportService.exportToPdf(report, file)
            _state.update {
                it.copy(isExporting = false, message = if (ok) "Saved ${file.name}" else null, error = if (ok) null else "PDF export failed")
            }
        }
    }

    fun exportToCsv(file: File): Job {
        _state.update { it.copy(isExporting = true, error = null) }
        return launchSafely(onError) {
            val ok = reportService.exportYearToCsv(_state.value.selectedYear, file)
            _state.update {
                it.copy(isExporting = false, message = if (ok) "Saved ${file.name}" else null, error = if (ok) null else "CSV export failed")
            }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, message = null) }
    }
}
