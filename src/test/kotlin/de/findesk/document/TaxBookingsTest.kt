package de.findesk.document

import de.findesk.TestDatabase
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.TaxSource
import de.findesk.model.TransactionItem
import de.findesk.repository.SettingsRepositoryImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The Tax bookings report: which bookings are in, how groups split into income and deductions and how the
 * keyword suggestions pick their candidates. Built from [TransactionItem]s directly, no database needed
 * except where the suggestions' keyword setting is read.
 */
class TaxBookingsTest {
    private val service = ReportService()
    private val from = LocalDate.of(2026, 1, 1)
    private val to = LocalDate.of(2026, 12, 31)

    /** id, amount and date decide a booking; a date after [day] sorts later. */
    private val day = LocalDate.of(2026, 6, 1)

    private fun item(
        id: Int,
        amount: String,
        date: LocalDate = day,
        categoryId: Int? = null,
        categoryName: String? = null,
        categoryColor: String? = null,
        taxRelevant: Boolean = false,
        taxSource: TaxSource = when {
            taxRelevant -> TaxSource.CATEGORY
            else -> TaxSource.NONE
        },
        currency: String = "EUR",
        documentCount: Int = 0,
        partner: String? = null,
        purpose: String? = null,
        taxOverride: Boolean? = null,
    ): TransactionItem = TransactionItem(
        id = id,
        accountId = 1,
        accountIban = "DE89370400440532013000",
        date = date,
        amount = BigDecimal(amount),
        currency = currency,
        partnerName = partner,
        purpose = purpose,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryColor = categoryColor,
        isTaxRelevant = taxRelevant,
        documentCount = documentCount,
        taxSource = taxSource,
        taxOverride = taxOverride,
    )

    @Test
    fun `only effectively tax relevant bookings of the primary currency are included`() {
        val report = service.buildTaxBookings(
            from,
            to,
            listOf(
                item(1, "-30.00", categoryId = 10, categoryName = "Spenden", categoryColor = "#9C27B0", taxRelevant = true),
                item(2, "-10.00", categoryId = 11, categoryName = "Lebensmittel", taxRelevant = false),
                // Tax relevant, but in another currency: counted so the hint can mention it, never summed
                item(3, "-45.00", categoryId = 12, categoryName = "Spenden", categoryColor = "#9C27B0", taxRelevant = true, currency = "USD"),
            ),
            "EUR",
        )

        assertEquals(1, report.bookingCount, "only the tax-relevant EUR booking is included")
        assertEquals(1, report.otherCurrencyBookings, "the tax-relevant USD booking is counted, not summed")
        assertEquals(BigDecimal("30.00"), report.deductionTotal, "only the EUR booking is summed")
        assertEquals(BigDecimal.ZERO, report.incomeTotal)
        assertEquals(listOf(10), report.deductionGroups.map { it.categoryId })
    }

    @Test
    fun `a salary category lands in income and an expense category in deductions`() {
        val report = service.buildTaxBookings(
            from,
            to,
            listOf(
                item(1, "2000.00", date = day.minusDays(3), categoryId = 10, categoryName = "Gehalt", categoryColor = "#4CAF50", taxRelevant = true),
                item(2, "2200.00", date = day, categoryId = 10, categoryName = "Gehalt", categoryColor = "#4CAF50", taxRelevant = true),
                item(3, "-300.00", categoryId = 20, categoryName = "Handwerk", categoryColor = "#FF5722", taxRelevant = true),
                item(4, "-80.00", date = day.plusDays(5), categoryId = 20, categoryName = "Handwerk", categoryColor = "#FF5722", taxRelevant = true),
            ),
            "EUR",
        )

        val salary = report.incomeGroups.single()
        assertEquals(10, salary.categoryId)
        assertEquals("Gehalt", salary.name)
        assertEquals("#4CAF50", salary.color)
        assertEquals(BigDecimal("4200.00"), salary.income)
        assertEquals(BigDecimal.ZERO, salary.expenses)
        assertEquals(BigDecimal("4200.00"), salary.net)

        val handwerk = report.deductionGroups.single()
        assertEquals(20, handwerk.categoryId)
        assertEquals(BigDecimal.ZERO, handwerk.income)
        assertEquals(BigDecimal("380.00"), handwerk.expenses)
        assertEquals(BigDecimal("-380.00"), handwerk.net)

        assertEquals(report.incomeTotal, salary.net)
        assertEquals(report.deductionTotal, handwerk.net.abs())
        assertEquals(4, report.bookingCount)
        assertEquals(4, report.incomeGroups.sumOf { it.bookings.size } + report.deductionGroups.sumOf { it.bookings.size })
        // Every group's bookings are date ascending
        assertTrue(salary.bookings.map { it.id } == listOf(1, 2), "salary bookings date ascending")
        assertTrue(handwerk.bookings.map { it.id } == listOf(3, 4), "deduction bookings date ascending")
    }

    @Test
    fun `a refund lowers a deduction category and a category whose refunds exceed its costs moves to income`() {
        val report = service.buildTaxBookings(
            from,
            to,
            listOf(
                item(1, "-300.00", categoryId = 20, categoryName = "Handwerk", categoryColor = "#FF5722", taxRelevant = true),
                item(2, "-80.00", categoryId = 20, categoryName = "Handwerk", categoryColor = "#FF5722", taxRelevant = true),
                item(3, "100.00", date = day.minusDays(10), categoryId = 20, categoryName = "Handwerk", categoryColor = "#FF5722", taxRelevant = true),
                item(4, "-100.00", categoryId = 30, categoryName = "Kleidung", taxRelevant = true),
                item(5, "250.00", date = day.minusDays(1), categoryId = 30, categoryName = "Kleidung", taxRelevant = true),
            ),
            "EUR",
        )

        // The refund lowers the category instead of showing up as income elsewhere
        val handwerk = report.deductionGroups.single()
        assertEquals(BigDecimal("100.00"), handwerk.income, "the refund's income is inside the category")
        assertEquals(BigDecimal("380.00"), handwerk.expenses)
        assertEquals(BigDecimal("-280.00"), handwerk.net)

        // Kleidung's refund money outweighs its costs, so the whole category moves to the income side
        val kleidung = report.incomeGroups.single()
        assertEquals(30, kleidung.categoryId)
        assertEquals(BigDecimal("250.00"), kleidung.income)
        assertEquals(BigDecimal("100.00"), kleidung.expenses)
        assertEquals(BigDecimal("150.00"), kleidung.net)

        assertEquals(report.deductionTotal, BigDecimal("280.00"))
        assertEquals(report.incomeTotal, BigDecimal("150.00"))
        assertEquals(5, report.bookingCount)
        assertEquals(5, report.incomeGroups.sumOf { it.bookings.size } + report.deductionGroups.sumOf { it.bookings.size })
    }

    @Test
    fun `sections sort by absolute net then name and withReceipt counts per group and in total`() {
        val report = service.buildTaxBookings(
            from,
            to,
            listOf(
                // Deductions: 400 net ("Zahnarzt" before "Handwerk" by name, larger net first)
                item(1, "-400.00", categoryId = 20, categoryName = "Handwerk", categoryColor = "#FF5722", taxRelevant = true, documentCount = 1),
                item(2, "-400.00", categoryId = 21, categoryName = "Zahnarzt", taxRelevant = true),
                item(3, "-50.00", categoryId = 22, categoryName = "Krankenhaus", taxRelevant = true),
                // Income
                item(4, "1200.00", categoryId = 10, categoryName = "Gehalt", categoryColor = "#4CAF50", taxRelevant = true),
                item(5, "1200.00", date = day.plusDays(20), categoryId = 10, categoryName = "Gehalt", categoryColor = "#4CAF50", taxRelevant = true),
                item(6, "900.00", date = day.plusDays(1), categoryId = 40, categoryName = "Abmahnkosten", taxRelevant = true, documentCount = 1),
            ),
            "EUR",
        )

        assertEquals(listOf(20, 21, 22), report.deductionGroups.map { it.categoryId }, "by absolute net, then name")
        assertEquals(listOf(10, 40), report.incomeGroups.map { it.categoryId })

        assertEquals(1, report.incomeGroups.first { it.categoryId == 40 }.withReceipt)
        assertEquals(1, report.deductionGroups.first { it.categoryId == 20 }.withReceipt)
        assertEquals(2, report.withReceipt, "one receipt in income plus one in deductions")
        assertEquals(6, report.bookingCount)
        assertEquals(1, report.incomeGroups.count { it.withReceipt > 0 })
        assertEquals(1, report.deductionGroups.count { it.withReceipt > 0 })
    }

    @Test
    fun `uncategorized tax relevant bookings form their own group with the localized label`() {
        val report = service.buildTaxBookings(
            from,
            to,
            listOf(
                item(1, "-49.00", taxRelevant = true, taxSource = TaxSource.MANUAL, taxOverride = true),
                item(2, "15.00", date = day.plusDays(1), taxRelevant = true, taxSource = TaxSource.MANUAL, taxOverride = true),
            ),
            "EUR",
        )

        val uncategorized = report.deductionGroups.single()
        assertEquals(null, uncategorized.categoryId, "null category id means the uncategorized group")
        assertEquals(AppStrings[StringKey.Uncategorized], uncategorized.name)
        assertEquals(listOf(1, 2), uncategorized.bookings.map { it.id }, "date ascending, then id")
        assertEquals(2, report.bookingCount)
    }

    @Test
    fun `find suggests keyword matches on partner or purpose and skips decided and transfer bookings`() {
        val keywords = listOf("Spende", "Handwerker", "Kita")
        val suggestions = TaxSuggestions.find(
            listOf(
                // Not relevant, keyword in purpose
                item(1, "-30.00", purpose = "Spende für Beispielprojekt"),
                // Not relevant, keyword in partner (case-insensitive)
                item(2, "-200.00", date = day.plusDays(1), partner = "Beispiel-Handwerker GmbH"),
                // Already tax relevant: never suggested
                item(3, "-30.00", partner = "Spendenaktion", taxRelevant = true),
                // Manually decided to not be tax relevant: never suggested again
                item(4, "-30.00", partner = "Spendenaktion", taxSource = TaxSource.MANUAL, taxOverride = false),
                // A transfer category: never suggested
                item(5, "-30.00", partner = "Spendenaktion", taxRelevant = false, taxSource = TaxSource.NONE).let {
                    it.copy(isTransfer = true)
                },
                // No keyword match: not a suggestion
                item(6, "-9.00", partner = "Bäckerei Beispiel"),
                // Match, but its date is older so it sorts last still above nothing
                item(7, "-280.00", date = day.minusDays(40), purpose = "Handwerkerleistung Bad"),
            ),
            keywords,
        )

        assertEquals(listOf(2, 1, 7), suggestions.map { it.booking.id }, "date descending")
        assertEquals("Spende", suggestions.first { it.booking.id == 1 }.keyword, "the first keyword (list order) wins")
        assertEquals("Handwerker", suggestions.first { it.booking.id == 2 }.keyword)
        assertEquals("Handwerker", suggestions.first { it.booking.id == 7 }.keyword)
    }

    @Test
    fun `find matches a keyword only at the start of a word`() {
        val suggestions = TaxSuggestions.find(
            listOf(
                item(1, "-30.00", partner = "Beispiel Kita-Beitrag"),
                item(2, "-30.00", date = day.plusDays(1), partner = "Akita Import"),
                item(3, "-30.00", date = day.plusDays(2), purpose = "SPENDEN Aktion"),
                item(4, "-30.00", date = day.plusDays(3), purpose = "Gegenspende"),
            ),
            listOf("Kita", "Spende"),
        )
        assertEquals(listOf(3, 1), suggestions.map { it.booking.id }, "word starts match in any case; inside a word never")
    }

    @Test
    fun `find ignores blank and short keywords and needs a keyword of at least three characters`() {
        val suggestions = TaxSuggestions.find(
            listOf(item(1, "-30.00", partner = "a b Kitax t", purpose = "relevant later")),
            listOf("  ", "", "ab", "Kitax"),
        )
        assertEquals(listOf(1), suggestions.map { it.booking.id }, "'Kitax' matches, blanks and sub-3-char words are ignored")
        assertTrue(TaxSuggestions.find(listOf(item(1, "-30.00", partner = "a b c")), listOf("  ", "", "ab")).isEmpty(), "no usable keyword, no suggestion")
    }

    @Test
    fun `keywords returns the defaults without the setting and the stored list with it`() {
        TestDatabase.init()
        val settings = SettingsRepositoryImpl()
        assertEquals(TaxSuggestions.DEFAULT_KEYWORDS, TaxSuggestions.keywords(settings))

        settings.setValue(TaxSuggestions.KEY_KEYWORDS, "Steuerberater\n\n Küche \nKita")
        assertEquals(listOf("Steuerberater", "Küche", "Kita"), TaxSuggestions.keywords(settings), "trimmed, blank lines dropped, one per line")
        assertFalse(TaxSuggestions.keywords(settings).contains("Handwerker"))

        val suggestions = TaxSuggestions.find(
            listOf(item(1, "-30.00", partner = "Spendenbeispiel"), item(2, "-9.00", partner = "Bäckerei Beispiel")),
            TaxSuggestions.keywords(settings),
        )
        assertTrue(suggestions.isEmpty(), "no stored keyword matches the Spende partner any more")
    }
}
