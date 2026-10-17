package de.findesk.banking

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BankCapabilitiesTest {
    @Test
    fun `detect keeps only jobs FinDesk knows`() {
        val detected = BankCapabilities.detect { it == BankCapabilities.JOB_TRANSFER || it == "SOME_UNKNOWN_JOB" }

        assertEquals(setOf(BankCapabilities.JOB_TRANSFER), detected.jobs)
        assertTrue(detected.supportsTransfer)
        assertFalse(detected.supportsStatements)
        assertFalse(detected.supportsStandingOrders)
    }

    @Test
    fun `serialize and parse round-trip`() {
        val capabilities = BankCapabilities(setOf(BankCapabilities.JOB_TRANSFER, BankCapabilities.JOB_BALANCE))

        val roundTripped = BankCapabilities.parse(capabilities.serialize())

        assertEquals(capabilities, roundTripped)
        assertEquals("SaldoReq,UebSEPA", capabilities.serialize())
    }

    @Test
    fun `parse of null or blank means unknown`() {
        assertNull(BankCapabilities.parse(null))
        assertNull(BankCapabilities.parse(""))
        assertNull(BankCapabilities.parse("   "))
        // An empty set is unknown too: a login that reports nothing is treated optimistically.
        assertNull(BankCapabilities.parse(BankCapabilities(emptySet()).serialize()))
    }

    @Test
    fun `statement and standing order support combine jobs`() {
        val capabilities = BankCapabilities(setOf(BankCapabilities.JOB_STATEMENTS_CAMT, BankCapabilities.JOB_STANDING_LIST))

        assertTrue(capabilities.supportsStatements)
        assertFalse(capabilities.supportsMt940)
        assertTrue(capabilities.supportsCamt)
        assertTrue(capabilities.supportsStandingOrders)
        assertFalse(capabilities.supportsScheduledTransfer)
    }
}
