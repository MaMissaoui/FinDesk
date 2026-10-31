package de.findesk.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** [PaymentProviders.merchantOf] extracts the shop from a PayPal purpose; anything else stays null. */
class PaymentProvidersTest {
    private val paypalPartner = "PayPal Europe S.a.r.l. et Cie S.C.A"

    private fun paypalPurpose(shop: String, id: String = "PP.0471.PPX") = "EREF+REF001 SVWZ+$id . $shop, Ihr Einkauf bei $shop"

    @Test
    fun `the shop after the marker is extracted from a purpose with SEPA tags and padding`() {
        val raw = "EREF+REF001          SVWZ+PP.0471.PPX .   Beispiel Shop GmbH,   Ihr Einkauf bei Beispiel Shop GmbH"
        assertEquals("Beispiel Shop GmbH", PaymentProviders.merchantOf(raw, paypalPartner))
    }

    @Test
    fun `a padded partner counts as paypal when its readable part does`() {
        val partner = "PayPal Europe S.a.r.l.      Buchungscode 4711      Abrechnung 10/26"
        assertEquals("Beispiel Shop GmbH", PaymentProviders.merchantOf(paypalPurpose("Beispiel Shop GmbH"), partner))
    }

    @Test
    fun `the marker is matched ignoring case`() {
        val raw = "SVWZ+PP.0471.PPX . Beispiel Shop GmbH, IHR EINKAUF BEI Beispiel Shop GmbH"
        assertEquals("Beispiel Shop GmbH", PaymentProviders.merchantOf(raw, paypalPartner))
    }

    @Test
    fun `trailing separators are stripped but a trailing dot is kept`() {
        assertEquals("Beispiel Shop S.a.r.l.", PaymentProviders.merchantOf(paypalPurpose("Beispiel Shop S.a.r.l."), paypalPartner))
        assertEquals("Beispiel Shop GmbH", PaymentProviders.merchantOf(paypalPurpose("Beispiel Shop GmbH,;:"), paypalPartner))
    }

    @Test
    fun `the shop is extracted without SVWZ and ends at the next tag`() {
        val withoutSvWz = "EREF+REF001 PP.0471.PPX . Beispiel Shop GmbH, Ihr Einkauf bei Beispiel Shop GmbH"
        assertEquals("Beispiel Shop GmbH", PaymentProviders.merchantOf(withoutSvWz, paypalPartner))

        val followedByTag = "EREF+REF001 Ihr Einkauf bei Beispiel Shop GmbH SVWZ+Rechnung 4711"
        assertEquals("Beispiel Shop GmbH", PaymentProviders.merchantOf(followedByTag, paypalPartner))
    }

    @Test
    fun `titleOf combines the provider and the shop`() {
        assertEquals("PayPal · Beispiel Shop GmbH", PaymentProviders.titleOf(paypalPurpose("Beispiel Shop GmbH"), paypalPartner))
        assertNull(PaymentProviders.titleOf(paypalPurpose("Beispiel Shop GmbH"), "Beispiel Shop GmbH"))
    }

    @Test
    fun `an over-long shop name is capped at 70 characters`() {
        val long = "BeispielShop".repeat(12) // 144 characters, no space at the cut
        assertEquals(70, PaymentProviders.merchantOf(paypalPurpose(long), paypalPartner)?.length)
        // A cut that lands after a space leaves no trailing space
        val spaced = "Beispiel Shop ".repeat(10).trimEnd() // character 70 is a space
        assertEquals("Beispiel Shop ".repeat(5).trimEnd(), PaymentProviders.merchantOf(paypalPurpose(spaced), paypalPartner))
    }

    @Test
    fun `a non-paypal partner or none gives null even with a marker`() {
        assertNull(PaymentProviders.merchantOf(paypalPurpose("Beispiel Shop GmbH"), "Beispiel Shop GmbH"))
        assertNull(PaymentProviders.merchantOf(paypalPurpose("Beispiel Shop GmbH"), "PP Muster GmbH"))
        assertNull(PaymentProviders.merchantOf(paypalPurpose("Beispiel Shop GmbH"), null))
        assertNull(PaymentProviders.merchantOf(paypalPurpose("Beispiel Shop GmbH"), "   "))
    }

    @Test
    fun `a purpose without the marker or without text gives null`() {
        assertNull(PaymentProviders.merchantOf("EREF+REF001 SVWZ+PP.0471.PPX . Beispiel Shop GmbH", paypalPartner))
        assertNull(PaymentProviders.merchantOf(null, paypalPartner))
        assertNull(PaymentProviders.merchantOf("   ", paypalPartner))
    }

    @Test
    fun `an empty tail after the marker gives null`() {
        assertNull(PaymentProviders.merchantOf(paypalPurpose("   ., "), paypalPartner))
    }
}
