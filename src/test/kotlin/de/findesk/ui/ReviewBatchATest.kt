package de.findesk.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import de.findesk.TestDatabase
import de.findesk.i18n.AppLanguage
import de.findesk.i18n.AppStrings
import de.findesk.i18n.LocalStrings
import de.findesk.i18n.StringKey
import de.findesk.i18n.Translations
import de.findesk.repository.DocumentRepositoryImpl
import de.findesk.ui.screens.DocumentsScreen
import de.findesk.ui.theme.FinDeskTheme
import de.findesk.util.Formats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Batch A's wording changes: the new strings, and the one Upload button on an empty document library. */
@OptIn(ExperimentalTestApi::class)
class ReviewBatchATest {
    private val en = Translations.of(AppLanguage.EN)
    private val de = Translations.of(AppLanguage.DE)

    @Test
    fun `batch A keys read as specified in English`() {
        assertEquals(
            "Text in images and scanned PDFs can't be read until Tesseract is installed (see Settings → Data & OCR). PDFs with text work without it.",
            en.format(StringKey.OcrNotInstalled, "Settings → Data & OCR"),
        )
        assertEquals("No bookings to export in 2026", en.format(StringKey.NothingToExport, "2026"))
        assertEquals("No rules yet. Add one, or import a rules file.", en[StringKey.NoRulesYet])
    }

    @Test
    fun `bookings count total pluralises in English and German`() {
        try {
            AppStrings.set(AppLanguage.EN)
            assertEquals(
                "1 booking · +€2,600.00",
                en.format(StringKey.BookingsCountTotal, 1, Formats.money(BigDecimal("2600.00"), "EUR", signed = true)),
            )
            assertEquals(
                "2 bookings · +€5.00",
                en.format(StringKey.BookingsCountTotal, 2, Formats.money(BigDecimal("5.00"), "EUR", signed = true)),
            )
            AppStrings.set(AppLanguage.DE)
            assertEquals(
                "2 Buchungen · +€5,00",
                de.format(StringKey.BookingsCountTotal, 2, Formats.money(BigDecimal("5.00"), "EUR", signed = true)),
            )
        } finally {
            AppStrings.set(AppLanguage.EN)
        }
    }

    @Test
    fun `an empty library shows one Upload and no search field`() = runComposeUiTest {
        TestDatabase.init()

        setContent {
            CompositionLocalProvider(LocalStrings provides en) {
                FinDeskTheme { DocumentsScreen() }
            }
        }

        // The empty state replaces the header's field and Upload once the load settles
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(en[StringKey.UploadHint]).fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText(en[StringKey.Upload]).assertCountEquals(1)
        onAllNodesWithText(en[StringKey.SearchDocuments]).assertCountEquals(0)
    }

    @Test
    fun `a document keeps the search field`() = runComposeUiTest {
        TestDatabase.init()
        DocumentRepositoryImpl().create("receipt.pdf", "documents/receipt.pdf", "application/pdf")

        setContent {
            CompositionLocalProvider(LocalStrings provides en) {
                FinDeskTheme { DocumentsScreen() }
            }
        }

        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("receipt.pdf").fetchSemanticsNodes().isNotEmpty() }
        onAllNodesWithText(en[StringKey.SearchDocuments]).assertCountEquals(1)
    }
}
