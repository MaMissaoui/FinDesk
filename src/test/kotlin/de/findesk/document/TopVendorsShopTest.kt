package de.findesk.document

import de.findesk.model.TransactionItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The Reports' top vendors group PayPal purchases by the shop named in the purpose ("PayPal · <shop>") instead of
 * letting them all collapse into one PayPal entry. Built from [TransactionItem]s directly, no database needed.
 */
class TopVendorsShopTest {
    private val service = ReportService()
    private val from = LocalDate.of(2026, 1, 1)
    private val to = LocalDate.of(2026, 12, 31)
    private val day = LocalDate.of(2026, 6, 1)
    private var id = 0

    private val paypalPartner = "PayPal Europe S.a.r.l. et Cie S.C.A"

    private fun item(
        amount: String,
        date: LocalDate = day,
        partner: String?,
        purpose: String? = null,
    ): TransactionItem = TransactionItem(
        id = ++id,
        accountId = 1,
        accountIban = "DE89370400440532013000",
        date = date,
        amount = BigDecimal(amount),
        currency = "EUR",
        partnerName = partner,
        purpose = purpose,
        categoryId = null,
        categoryName = null,
        categoryColor = null,
        isTaxRelevant = false,
        documentCount = 0,
    )

    @Test
    fun `paypal purchases group by the shop named in the purpose`() {
        val txns = listOf(
            item("-10.00", partner = paypalPartner, purpose = "Ihr Einkauf bei Beispiel Shop GmbH"),
            item("-20.00", date = day.plusDays(1), partner = paypalPartner, purpose = "Ihr Einkauf bei Beispiel Shop GmbH"),
            item("-40.00", date = day.plusDays(2), partner = paypalPartner, purpose = "Ihr Einkauf bei Anderer Shop GmbH"),
            item("-30.00", date = day.plusDays(3), partner = "Beispiel Inc."),
        )

        val vendors = service.buildReport(from, to, txns).topVendors

        assertEquals(
            listOf(
                "PayPal · Anderer Shop GmbH" to BigDecimal("40.00"),
                "PayPal · Beispiel Shop GmbH" to BigDecimal("30.00"),
                "Beispiel Inc." to BigDecimal("30.00"),
            ),
            vendors,
            "the two Beispiel Shop purchases are summed, Anderer Shop stays separate",
        )
    }

    @Test
    fun `vendor names group case and whitespace insensitively`() {
        val txns = listOf(
            item("-10.00", partner = "BEISPIEL MARKT"),
            item("-20.00", date = day.plusDays(1), partner = "Beispiel Markt"),
            item("-5.00", date = day.plusDays(2), partner = "Beispiel  Markt   "),
        )

        val vendors = service.buildReport(from, to, txns).topVendors

        assertEquals(
            listOf("Beispiel Markt" to BigDecimal("35.00")),
            vendors,
            "the three spellings are one vendor, shown with the spelling two bookings use",
        )
    }

    @Test
    fun `a spelling tie shows the most recent booking's spelling`() {
        val laterBeispiel = listOf(
            item("-10.00", date = day, partner = "BEISPIEL MARKT"),
            item("-20.00", date = day.plusDays(1), partner = "Beispiel Markt"),
        )
        val laterUpper = listOf(
            item("-10.00", date = day.plusDays(1), partner = "BEISPIEL MARKT"),
            item("-20.00", date = day, partner = "Beispiel Markt"),
        )

        assertEquals("Beispiel Markt", service.buildReport(from, to, laterBeispiel).topVendors.single().first)
        assertEquals("BEISPIEL MARKT", service.buildReport(from, to, laterUpper).topVendors.single().first)
    }

    @Test
    fun `a paypal card booking and a marker booking group under one shop ignoring case`() {
        val txns = listOf(
            item("-10.00", date = day, partner = null, purpose = "PAYPAL *BEISPIELSHOP"),
            item("-20.00", date = day.plusDays(1), partner = paypalPartner, purpose = "Ihr Einkauf bei Beispielshop"),
        )

        val vendors = service.buildReport(from, to, txns).topVendors

        assertEquals(
            listOf("PayPal · Beispielshop" to BigDecimal("30.00")),
            vendors,
            "the card descriptor and the marker name the same shop and are summed",
        )
    }

    @Test
    fun `a paypal booking whose purpose has no shop stays under its description`() {
        val txns = listOf(
            item("-15.00", partner = paypalPartner, purpose = "PP.1.PP . 1234"),
            item("-30.00", date = day.plusDays(1), partner = paypalPartner, purpose = "Ihr Einkauf bei Beispiel Shop GmbH"),
        )

        val vendors = service.buildReport(from, to, txns).topVendors

        assertEquals(
            listOf(
                "PayPal · Beispiel Shop GmbH" to BigDecimal("30.00"),
                paypalPartner to BigDecimal("15.00"),
            ),
            vendors,
            "the shopless PayPal booking keeps its raw description as before",
        )
    }
}
