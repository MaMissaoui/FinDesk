package de.findesk.banking

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Exercises the real HBCI4Java stack against a closed local port: no bank is contacted. Verifies the
 * client/passport wiring and that a failed connection becomes an error instead of a hang or crash.
 */
class Hbci4JavaGatewaySmokeTest {
    @TempDir
    lateinit var tmp: File

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `unreachable server fails cleanly`() {
        val asked = mutableListOf<String>()
        val ui = object : BankingInteraction {
            override fun pin(bankName: String, userId: String, offerRemember: Boolean) = PinEntry("00000").also { asked += "pin" }
            override fun selectTanMethod(options: List<TanMethod>) = options.firstOrNull()?.id.also { asked += "secmech" }
            override fun selectTanMedium(options: List<String>) = options.firstOrNull()
            override fun tan(challenge: TanChallenge): String? = null
            override fun confirmDecoupled(message: String) = false
            override fun confirmPayee(message: String) = false
        }
        val connection = FinTSConnection(
            bankName = "Nowhere",
            blz = "37050198",
            userId = "nobody",
            serverUrl = "https://localhost/fints", // nothing listens on local 443; nothing leaves the machine
            passportFile = File(tmp, "passport.dat"),
            passportPassphrase = "test-passphrase-123",
        )

        val error = assertThrows(Exception::class.java) {
            Hbci4JavaGateway().fetchAll(connection, ui, { LocalDate.now().minusDays(1) }, LocalDate.now())
        }
        assertFalse(error is BankingCancelledException)
        assertTrue(error.message!!.startsWith("Could not reach the bank's FinTS server"), error.message)
        assertTrue(connection.passportFile.exists(), "passport file created")
        println("SMOKE error: ${error.javaClass.simpleName}: ${error.message?.take(200)} | asked=$asked | passport=${connection.passportFile.exists()}")
    }

    private fun quietInteraction(): BankingInteraction = object : BankingInteraction {
        override fun pin(bankName: String, userId: String, offerRemember: Boolean) = PinEntry("00000")
        override fun selectTanMethod(options: List<TanMethod>) = options.firstOrNull()?.id
        override fun selectTanMedium(options: List<String>) = options.firstOrNull()
        override fun tan(challenge: TanChallenge): String? = null
        override fun confirmDecoupled(message: String) = false
        override fun confirmPayee(message: String) = false
    }

    /**
     * HBCI4Java reads the login from the passport file once and then keeps the file's values, so editing the BLZ
     * or server URL had no effect on a synced bank. [Hbci4JavaGateway.resetPassport] deletes the file; this drives
     * the real stack against closed local ports to show that a reset picks up the new login and that without it the
     * old values stay.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `resetting the passport makes the next dialog use the new login`() {
        val gateway = Hbci4JavaGateway()
        val passport = File(tmp, "regression-passport.dat")
        fun connection(blz: String, url: String) = FinTSConnection(
            bankName = "Regression Bank",
            blz = blz,
            userId = "regression-user",
            serverUrl = url,
            passportFile = passport,
            passportPassphrase = "test-passphrase-123",
        )

        /** Runs one dialog and returns the connection-related trace lines (never exceptions; nothing listens). */
        fun dialog(blz: String, url: String): List<String> {
            ProtocolTraceStore.clear()
            runCatching { gateway.fetchAll(connection(blz, url), quietInteraction(), { LocalDate.now().minusDays(1) }, LocalDate.now()) }
            val lines = ProtocolTraceStore.last()?.lines.orEmpty().map { it.message }
            return lines.filter { it.contains("KIK.blz") || it.contains("Verbinde mit") }
        }

        ProtocolTraceStore.enabled = true
        try {
            val first = dialog("37050198", "https://localhost/fints")
            assertTrue(passport.exists(), "the first dialog writes the passport")
            assertTrue(first.any { it.contains("37050198") }, "dialog 1 names its BLZ: $first")
            assertTrue(first.any { it.contains("localhost") }, "dialog 1 names its host: $first")

            gateway.resetPassport(passport)
            assertFalse(passport.exists(), "the reset deletes the old passport")

            val second = dialog("70020270", "https://127.0.0.1/fints")
            assertTrue(second.any { it.contains("70020270") }, "dialog 2 names the new BLZ: $second")
            assertTrue(second.any { it.contains("127.0.0.1") }, "dialog 2 names the new host: $second")
            assertFalse(second.any { it.contains("37050198") }, "the old BLZ is gone: $second")

            // No reset, but the login changed: the mismatch check heals the stale passport before the dialog, so
            // HBCI4Java uses the connection's new values. This intentionally replaces the old expectation that the
            // passport written by dialog 2 kept winning without an explicit reset: the behaviour changed on purpose.
            val third = dialog("12030000", "https://localhost/fints")
            assertTrue(third.any { it.contains("12030000") }, "dialog 3 names the new BLZ: $third")
            assertTrue(third.any { it.contains("localhost") }, "dialog 3 names the new host: $third")
            assertFalse(third.any { it.contains("70020270") }, "the old BLZ is gone: $third")
            assertFalse(third.any { it.contains("127.0.0.1") }, "the old host is gone: $third")
        } finally {
            ProtocolTraceStore.enabled = false
            ProtocolTraceStore.clear()
        }
    }

    /**
     * The mismatch check must not churn a correct passport: with unchanged values the second dialog keeps the file
     * HBCI4Java wrote. A fresh passport logs "have to create new passport file" while a reused one is only loaded,
     * which is the cheapest reliable signal here: the connect against the closed port fails before any HBCI message,
     * so the new-system-id step ("Rufe neue System-ID ab") never runs and lastModified changes on every save anyway.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `an unchanged passport is kept between dialogs`() {
        val gateway = Hbci4JavaGateway()
        val passport = File(tmp, "kept-passport.dat")
        val connection = FinTSConnection(
            bankName = "Kept Bank",
            blz = "12030000",
            userId = "kept-user",
            serverUrl = "https://localhost/fints",
            passportFile = passport,
            passportPassphrase = "test-passphrase-123",
        )

        /** Runs one dialog and returns the dialog's trace lines (never throws; nothing listens on local 443). */
        fun dialog(): List<String> {
            ProtocolTraceStore.clear()
            runCatching { gateway.fetchAll(connection, quietInteraction(), { LocalDate.now().minusDays(1) }, LocalDate.now()) }
            return ProtocolTraceStore.last()?.lines.orEmpty().map { it.message }
        }

        ProtocolTraceStore.enabled = true
        try {
            val first = dialog()
            assertTrue(passport.exists(), "the first dialog writes the passport")
            assertTrue(first.any { it.contains("have to create new passport file") }, "the first dialog creates the passport: $first")

            val second = dialog()
            assertTrue(passport.exists(), "the passport is not deleted")
            assertFalse(second.any { it.contains("have to create new passport file") }, "the second dialog reuses the passport instead of recreating it: $second")
        } finally {
            ProtocolTraceStore.enabled = false
            ProtocolTraceStore.clear()
        }
    }
}
