package de.findesk.banking

import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
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

    /** HBCI4Java drops the IOException that carried the status, so it survives only as logged text. */
    private val HTTP_STATUS = Regex("Server returned HTTP response code: (\\d+)")

    fun isNetworkFailure(error: Throwable, diagnostics: List<String>): Boolean {
        val chain = error.chain()
        if (chain.any { it is IOException }) return true
        val texts = chain.mapNotNull { it.message } + diagnostics
        return texts.any { text -> NETWORK_MARKERS.any { text.contains(it, ignoreCase = true) } }
    }

    /**
     * The HTTP status the server answered with, from the cause chain or the logged diagnostics, or
     * null. Checked before [isNetworkFailure] so an "HTTP 500" answer isn't reported as unreachable
     * just because its IOException cause happens to be in the chain.
     */
    fun httpStatus(error: Throwable, diagnostics: List<String>): Int? {
        val texts = error.chain().mapNotNull { it.message } + diagnostics
        return texts.firstNotNullOfOrNull { text -> HTTP_STATUS.find(text)?.groupValues?.get(1)?.toIntOrNull() }
    }

    /**
     * A short message for a failed dialog. Never contains stack frames: text from exception
     * messages and diagnostics is cut at the first line break and capped at 300 chars. An answer
     * that isn't FinTS (a base64 failure) and an HTTP status are transport diagnoses; a bank message
     * outranks the generic HBCI4Java text.
     */
    fun describe(error: Throwable, bankMessages: List<String>, diagnostics: List<String>): String {
        val chain = error.chain()
        val root = chain.last()
        // Matched by text only: an IllegalArgumentException alone can come from anywhere, e.g. a rejected job parameter
        if (chain.any { it.message?.contains("base64", ignoreCase = true) == true }) {
            return AppStrings.format(StringKey.ErrorServerNotFints, root.message.cleanLine().orEmpty())
        }
        httpStatus(error, diagnostics)?.let { return AppStrings.format(StringKey.ErrorServerHttpStatus, it) }
        bankMessages.lastOrNull()?.cleanLine()?.let { return it }
        val top = error.message.cleanLine() ?: diagnostics.lastOrNull()?.substringAfter("Exception: ")?.cleanLine()
        val rootLine = root.message.cleanLine()
        return when {
            top == null -> AppStrings[StringKey.ErrorDialogFailed]
            rootLine != null && rootLine != top -> "$top ($rootLine)"
            else -> top
        }
    }

    /** [StringKey.ErrorBankUnreachable] plus the innermost cause's first line, e.g. "(Connection reset)". */
    fun networkMessage(error: Throwable): String {
        val rootLine = error.chain().last().message.cleanLine()
        return AppStrings[StringKey.ErrorBankUnreachable] + (rootLine?.let { " ($it)" } ?: "")
    }

    private fun Throwable.chain(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    private fun String?.cleanLine(): String? {
        val line = this?.substringBefore('\n')?.trim().orEmpty()
        if (line.isEmpty()) return null
        if (line.startsWith("at ") || line.startsWith("\tat ") || line.startsWith("Caused by")) return null
        return line.take(300)
    }
}
