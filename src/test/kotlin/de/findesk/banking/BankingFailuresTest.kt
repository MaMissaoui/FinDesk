package de.findesk.banking

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException

class BankingFailuresTest {
    @Test
    fun `a socket exception in the cause chain is a network failure`() {
        assertTrue(BankingFailures.isNetworkFailure(RuntimeException("dialog failed", IOException("boom")), emptyList()))
    }

    @Test
    fun `the English send failure is recognised without a cause`() {
        assertTrue(BankingFailures.isNetworkFailure(RuntimeException("error while sending message to HBCI server"), emptyList()))
    }

    @Test
    fun `the German send failure is recognised in the diagnostics`() {
        val diagnostics = listOf("Fehler beim Senden der HBCI-Nachricht zum Server")
        assertTrue(BankingFailures.isNetworkFailure(RuntimeException(diagnostics.first()), diagnostics))
    }

    @Test
    fun `a socket exception named only in the diagnostics is recognised`() {
        assertTrue(BankingFailures.isNetworkFailure(RuntimeException("dialog failed"), listOf("java.net.ConnectException: Connection refused")))
    }

    @Test
    fun `connection refused counts even without an exception type`() {
        assertTrue(BankingFailures.isNetworkFailure(ConnectException("Connection refused"), emptyList()))
    }

    @Test
    fun `a bank rejection is not a network failure`() {
        assertFalse(BankingFailures.isNetworkFailure(RuntimeException("997 Falscher PIN"), listOf("997 Falscher PIN")))
    }
}
