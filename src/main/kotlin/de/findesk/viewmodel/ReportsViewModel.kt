package de.findesk.viewmodel

import de.findesk.document.ReportService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigDecimal
import java.time.LocalDate

data class ReportsState(
    val selectedYear: Int = LocalDate.now().year,
    val selectedMonth: Int = LocalDate.now().monthValue,
    val taxReport: ReportService.TaxReport? = null,
    val monthlyReport: ReportService.MonthlyReport? = null,
    val categoryTrends: List<ReportService.CategoryTrend> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class ReportsViewModel(
    private val reportService: ReportService = ReportService(),
) {
    private val _state = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = _state.asStateFlow()

    fun loadTaxReport(year: Int) {
        _state.value = _state.value.copy(isLoading = true, error = null, selectedYear = year)
        try {
            // TODO: Implement coroutine launch
            // val report = reportService.generateTaxReport(year)
            // _state.value = _state.value.copy(taxReport = report, isLoading = false)
            _state.value = _state.value.copy(isLoading = false)
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false, error = e.message)
        }
    }

    fun loadMonthlyReport(year: Int, month: Int) {
        _state.value = _state.value.copy(
            isLoading = true,
            error = null,
            selectedYear = year,
            selectedMonth = month,
        )
        try {
            // TODO: Implement coroutine launch
            _state.value = _state.value.copy(isLoading = false)
        } catch (e: Exception) {
            _state.value = _state.value.copy(isLoading = false, error = e.message)
        }
    }

    fun loadCategoryTrends(categoryId: Int, months: Int = 12) {
        try {
            // TODO: Implement coroutine launch
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message)
        }
    }

    fun exportToPdf(outputPath: String) {
        try {
            // TODO: Implement PDF export
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message)
        }
    }

    fun exportToCsv(outputPath: String) {
        try {
            // TODO: Implement CSV export
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message)
        }
    }

    fun updateYear(year: Int) {
        _state.value = _state.value.copy(selectedYear = year)
        loadTaxReport(year)
    }

    fun updateMonth(month: Int) {
        _state.value = _state.value.copy(selectedMonth = month)
        loadMonthlyReport(_state.value.selectedYear, month)
    }
}
