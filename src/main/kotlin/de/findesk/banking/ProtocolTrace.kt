package de.findesk.banking

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A single captured dialog line; [message] is stored raw and only redacted when the trace is rendered. */
data class TraceLine(val level: Int, val message: String)

/**
 * A redacted FinTS/HBCI dialog trace that a user can save and attach to a support request.
 * Capture is opt-in, in memory only, and never uploaded automatically.
 */
data class ProtocolTrace(
    val startedAt: LocalDateTime,
    val bankName: String,
    val blz: String,
    val hbciVersion: String,
    val lines: List<TraceLine>,
) {
    /** Renders the trace as a plain-text file, including a short header with non-sensitive context. */
    fun render(appVersion: String): String = buildString {
        appendLine("FinDesk FinTS protocol trace")
        appendLine("app-version: $appVersion")
        appendLine("created: ${startedAt.format(STAMP)}")
        appendLine("bank: $bankName")
        appendLine("blz: $blz")
        appendLine("hbci-version: $hbciVersion")
        appendLine("entries: ${lines.size}")
        appendLine("note: sensitive values (user id, PIN, TAN, account numbers, passphrase) are masked as '${BankingRedactor.MASK}'")
        appendLine("".padEnd(72, '-'))
        lines.forEach { appendLine("[${levelName(it.level)}] ${it.message}") }
    }

    private fun levelName(level: Int): String = LEVELS.getOrElse(level) { "L$level" }

    private companion object {
        private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        private val LEVELS = listOf("none", "error", "warn", "info", "debug", "debug2", "intern")
    }
}

/**
 * Collects dialog log lines during a single FinTS dialog. PIN/TANs, the user id and account numbers are
 * added as they become known, and everything is redacted together when the dialog ends (see [build]).
 */
internal class TraceRecorder(
    private val bankName: String,
    private val blz: String,
    private val hbciVersion: String,
    private val startedAt: LocalDateTime = LocalDateTime.now(),
) {
    private val secrets = linkedSetOf<String>()
    private val raw = mutableListOf<TraceLine>()

    fun addSecret(vararg values: String?) {
        values.filterNotNull().forEach { if (it.isNotBlank()) secrets += it }
    }

    fun record(level: Int, message: String?) {
        if (!message.isNullOrBlank()) raw += TraceLine(level, message.take(MAX_LINE_CHARS))
    }

    fun build(): ProtocolTrace = ProtocolTrace(
        startedAt = startedAt,
        bankName = bankName,
        blz = blz,
        hbciVersion = hbciVersion,
        lines = raw.map { TraceLine(it.level, BankingRedactor.redact(it.message, secrets)) },
    )

    private companion object {
        private const val MAX_LINE_CHARS = 8_000
    }
}

/**
 * Holds the most recent (opt-in) protocol trace in memory. The app never writes or sends it on its own: the
 * user exports it from Settings. [enabled] is refreshed from the settings before every dialog.
 */
object ProtocolTraceStore {
    /** Settings key for the opt-in flag; read before every dialog (see [de.findesk.banking.FinTSClient.run]). */
    const val SETTING_KEY = "fints.protocol.trace"

    @Volatile
    var enabled: Boolean = false

    private var last: ProtocolTrace? = null

    @Synchronized
    fun publish(trace: ProtocolTrace) {
        last = trace
    }

    @Synchronized
    fun last(): ProtocolTrace? = last

    @Synchronized
    fun clear() {
        last = null
    }

    /** Writes the last trace to [target]. Throws [IllegalStateException] when there is nothing to save. */
    @Synchronized
    fun save(target: File, appVersion: String) {
        val trace = last ?: throw IllegalStateException("No protocol trace captured yet")
        target.parentFile?.mkdirs()
        target.writeText(trace.render(appVersion))
    }
}
