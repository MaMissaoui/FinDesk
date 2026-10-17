package de.findesk.banking

import java.io.IOException

/**
 * Decides whether a failed FinTS dialog was a connectivity problem rather than a bank-side
 * rejection.
 *
 * HBCI4Java throws a generic `HBCI_Exception` whose message is localised (English
 * "error while sending message to HBCI server", German "Fehler beim Senden der HBCI-Nachricht zum
 * Server"), and it does not reliably keep the original socket exception as the cause. Neither the
 * message text alone nor the cause chain alone is therefore trustworthy across machines and JVM
 * locales, so both are inspected.
 */
internal object BankingFailures {
    private val NETWORK_MARKERS = listOf(
        // HBCI4Java's send failure, in the languages hbci4java ships
        "sending message to hbci server",
        "senden der hbci-nachricht",
        // Socket-level wording, in case the original exception only survives as text
        "connection refused",
        "connect timed out",
        "connection timed out",
        "connection reset",
        "network is unreachable",
        "no route to host",
        "unknownhostexception",
        "connectexception",
        "sockettimeoutexception",
        "sslexception",
        "handshakeexception",
    )

    fun isNetworkFailure(error: Throwable, diagnostics: List<String>): Boolean {
        val chain = generateSequence(error) { it.cause }.toList()
        if (chain.any { it is IOException }) return true
        val texts = chain.mapNotNull { it.message } + diagnostics
        return texts.any { text -> NETWORK_MARKERS.any { text.contains(it, ignoreCase = true) } }
    }
}
