package de.findesk.ui.screens

import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import de.findesk.i18n.Translations
import de.findesk.util.Formats
import de.findesk.viewmodel.DashboardState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** The month statement: the lines the Dashboard shows and the fact that their amounts add up to the result. */
class DashboardStatementTest {
    private val en = Translations.of(AppLanguage.EN)
    private val de = Translations.of(AppLanguage.DE)
    private val loadedMonth = YearMonth.of(2026, 10)

    private fun state(
        carryForward: BigDecimal? = BigDecimal("4566.89"),
        income: String = "567.74",
        expenses: String = "3056.62",
        movements: BigDecimal? = BigDecimal("-1760.00"),
        available: String = "318.01",
    ) = DashboardState(
        currency = "EUR",
        carryForward = carryForward,
        otherMovements = movements,
        monthlyIncome = BigDecimal(income),
        monthlyExpenses = BigDecimal(expenses),
        availableBalance = BigDecimal(available),
        balanceDate = LocalDate.of(2026, 10, 31),
    )

    @Test
    fun `with carry-forward the five lines add up to the result`() {
        val lines = statementLines(state(), loadedMonth, en)

        assertEquals(
            listOf(StatementKind.CARRY, StatementKind.IN, StatementKind.OUT, StatementKind.MOVEMENT, StatementKind.RESULT),
            lines.map { it.kind },
        )
        val parts = lines.filter { it.kind != StatementKind.RESULT }
        val sum = parts.fold(BigDecimal.ZERO) { acc, line ->
            val amount = Formats.parseAmount(line.amountText)
            assertNotNull(amount, "amount parses back: ${line.amountText}")
            acc + amount!!
        }
        val result = Formats.parseAmount(lines.last().amountText)!!
        assertEquals(0, sum.compareTo(result), "carry-forward + income - expenses + movements equals the result")
    }

    @Test
    fun `without carry-forward there is no carry and no movement line`() {
        val lines = statementLines(state(carryForward = null, movements = null), loadedMonth, en)

        assertEquals(listOf(StatementKind.IN, StatementKind.OUT, StatementKind.RESULT), lines.map { it.kind })
        assertFalse(lines.any { it.kind == StatementKind.CARRY })
        assertFalse(lines.any { it.kind == StatementKind.MOVEMENT })
    }

    @Test
    fun `a zero movement hides its line`() {
        val lines = statementLines(state(movements = BigDecimal.ZERO), loadedMonth, en)

        assertFalse(lines.any { it.kind == StatementKind.MOVEMENT }, "no 'Transfers and loans' line for zero")
        assertTrue(lines.any { it.kind == StatementKind.CARRY }, "the carry-forward still shows")
    }

    @Test
    fun `German amounts keep the statement's exact formatting`() = withLanguage(AppLanguage.DE) {
        val lines = statementLines(state(), loadedMonth, de)

        assertEquals("Übertrag aus September 2026", lines[0].label)
        assertEquals("4.566,89\u00A0€", lines[0].amountText)
        assertEquals("+567,74\u00A0€", lines[1].amountText)
        assertEquals("-3.056,62\u00A0€", lines[2].amountText)
        assertEquals("-1.760,00\u00A0€", lines[3].amountText)
        assertEquals("318,01\u00A0€", lines[4].amountText)
        assertTrue(lines[2].amountText.contains('\u00A0'), "a no-break space separates amount and symbol")
    }

    @Test
    fun `a single expense prints its minus and zeros keep their signs`() = withLanguage(AppLanguage.DE) {
        val expense = statementLines(state(carryForward = null, income = "0", expenses = "145.00", movements = null, available = "-145.00"), loadedMonth, de)
        assertEquals("-145,00\u00A0€", expense[1].amountText)

        val zero = statementLines(state(carryForward = null, income = "0", expenses = "0", movements = null, available = "0"), loadedMonth, de)
        assertEquals("+0,00\u00A0€", zero[0].amountText, "zero income still reads as incoming")
        assertEquals("0,00\u00A0€", zero[1].amountText, "zero expenses carry no minus")
    }

    private fun withLanguage(language: AppLanguage, block: () -> Unit) {
        val previous = AppStrings.current.language
        AppStrings.set(language)
        try {
            block()
        } finally {
            AppStrings.set(previous)
        }
    }
}
