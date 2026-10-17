package de.findesk.banking

import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Opt-in end-to-end check of the real HBCI4Java stack against a live *test* FinTS server, so the
 * client handshake is exercised against a third party instead of only fakes and a closed port.
 *
 * Skipped unless `FINDESK_FINTS_ONLINE_URL` is set, so it never runs in CI and never touches a real
 * bank. With only the URL set (no credentials) it drives the whole dialog setup and expects a
 * FinTS-level answer (an unknown-user rejection) rather than a transport failure. Set
 * `FINDESK_FINTS_ONLINE_USER` / `FINDESK_FINTS_ONLINE_PIN` for a full login, and optionally
 * `FINDESK_FINTS_ONLINE_BLZ` / `FINDESK_FINTS_ONLINE_BANK`. The Subsembly FinTS Dummy
 * (https://fints.subsembly.net/fints, BLZ 99000354, PIN 123456) documents this exact setup.
 */
class Hbci4JavaGatewayLiveTest {
    @TempDir
    lateinit var tmp: File

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `reaches a live FinTS test server`() {
        val url = System.getenv("FINDESK_FINTS_ONLINE_URL")
        assumeTrue(!url.isNullOrBlank(), "set FINDESK_FINTS_ONLINE_URL to run this opt-in live test")

        val asked = mutableListOf<String>()
        val envPin = System.getenv("FINDESK_FINTS_ONLINE_PIN") ?: "123456"
        val ui = object : BankingInteraction {
            override fun pin(bankName: String, userId: String) = envPin.also { asked += "pin" }
            override fun selectTanMethod(options: List<TanMethod>) = options.firstOrNull()?.id.also { asked += "secmech" }
            override fun selectTanMedium(options: List<String>) = options.firstOrNull()
            override fun tan(challenge: TanChallenge): String? = envPin.also { asked += "tan" }
            override fun confirmDecoupled(message: String) = false.also { asked += "decoupled" }
            override fun confirmPayee(message: String) = false.also { asked += "payee" }
        }
        val connection = FinTSConnection(
            bankName = System.getenv("FINDESK_FINTS_ONLINE_BANK") ?: "Subsembly FinTS Dummy",
            blz = System.getenv("FINDESK_FINTS_ONLINE_BLZ") ?: "99000354",
            userId = System.getenv("FINDESK_FINTS_ONLINE_USER") ?: "9999999999",
            serverUrl = url,
            passportFile = File(tmp, "passport.dat"),
            passportPassphrase = "live-test-passphrase-123",
        )

        val result = runCatching {
            Hbci4JavaGateway().fetchAll(connection, ui, { LocalDate.now().minusDays(30) }, LocalDate.now())
        }
        assertTrue(connection.passportFile.exists(), "passport file created (client setup completed before the bank was contacted)")

        result.fold(
            onSuccess = { snapshots ->
                println("LIVE ok: $url answered with ${snapshots.size} account(s)")
                assertTrue(snapshots.isNotEmpty(), "a reachable bank login should report at least one account")
            },
            onFailure = { e ->
                println("LIVE error from $url: ${e.javaClass.simpleName}: ${e.message?.take(300)} | asked=$asked")
                assertFalse(e is BankingCancelledException, "the test never cancels; a cancel means a callback was mis-handled")
                assertNotEquals(
                    AppStrings[StringKey.ErrorBankUnreachable],
                    e.message,
                    "expected a FinTS-level answer from $url, got a transport failure",
                )
                assertFalse(e.message.isNullOrBlank(), "the bank's answer should carry a message")
            },
        )
    }
}
