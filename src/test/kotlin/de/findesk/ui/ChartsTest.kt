package de.findesk.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.Translations
import de.findesk.ui.components.BalanceLineChart
import de.findesk.ui.components.IncomeExpenseBars
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.util.Formats
import de.findesk.viewmodel.MonthBar
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

@OptIn(ExperimentalTestApi::class)
class ChartsTest {
    @Test
    fun `the balance chart shows the last value and the first and last dates`() = runComposeUiTest {
        val points = listOf(
            LocalDate.of(2026, 10, 1) to BigDecimal("2300.00"),
            LocalDate.of(2026, 10, 10) to BigDecimal("2400.00"),
            LocalDate.of(2026, 10, 19) to BigDecimal("2500.00"),
        )
        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme {
                    BalanceLineChart(points = points, modifier = Modifier.size(600.dp, 200.dp))
                }
            }
        }
        onNodeWithText(Formats.money(BigDecimal("2500.00"), "EUR")).assertExists()
        onNodeWithText(Formats.date(points.first().first)).assertExists()
        onNodeWithText(Formats.date(points.last().first)).assertExists()
        onNodeWithText("Max").assertDoesNotExist()
    }

    @Test
    fun `the pointer hover shows a point's date and value in place of the last-value label`() = runComposeUiTest {
        val points = listOf(
            LocalDate.of(2026, 10, 1) to BigDecimal("2300.00"),
            LocalDate.of(2026, 10, 10) to BigDecimal("2400.00"),
            LocalDate.of(2026, 10, 19) to BigDecimal("2500.00"),
        )
        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme {
                    BalanceLineChart(points = points, modifier = Modifier.size(600.dp, 200.dp))
                }
            }
        }
        onRoot().performMouseInput {
            moveTo(Offset(100f, 100f))
            moveTo(Offset(236f, 100f))
        }
        onNodeWithText(Formats.money(BigDecimal("2400.00"), "EUR")).assertExists()
        onNodeWithText(Formats.money(BigDecimal("2500.00"), "EUR")).assertDoesNotExist()
    }

    @Test
    fun `the bars' hover names the month and shows signed income and expenses`() = runComposeUiTest {
        val months = listOf(
            MonthBar(2026, 10, BigDecimal("1200.00"), BigDecimal("800.00")),
            MonthBar(2026, 11, BigDecimal("900.00"), BigDecimal("1100.00")),
        )
        setContent {
            CompositionLocalProvider(LocalStrings provides Translations.of(AppLanguage.EN)) {
                FinDeskTheme {
                    IncomeExpenseBars(months = months, title = "Income and expenses", modifier = Modifier.size(600.dp, 240.dp))
                }
            }
        }
        onRoot().performMouseInput {
            moveTo(Offset(100f, 100f))
            moveTo(Offset(200f, 120f))
        }
        onNodeWithText("Oct 2026").assertExists()
        onNodeWithText(Formats.money(BigDecimal("1200.00"), "EUR", signed = true)).assertExists()
        onNodeWithText(Formats.money(BigDecimal("-800.00"), "EUR", signed = true)).assertExists()
    }
}
