package de.findesk.banking

/**
 * Removes sensitive values from captured FinTS/HBCI dialog lines before they are shown or shared.
 *
 * HBCI4Java already filters the secrets it knows about (`LogFilter` with `FILTER_SECRETS`); this is a
 * second line of defence that masks the values FinDesk knows (user id, customer id, PIN, TANs, passport
 * passphrase, account numbers) plus any IBAN-looking token, so an exported protocol trace is safe to attach
 * to a support request.
 */
object BankingRedactor {
    /** Replacement for every masked value; a single X, as Hibiscus' support instructions suggest. */
    const val MASK = "X"

    /** Shortest value that is worth masking; below this a "secret" is more likely to damage readable text. */
    private const val MIN_SECRET_LENGTH = 4

    private val IBAN = Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{11,30}\\b")

    fun redact(text: String, secrets: Collection<String>): String {
        var result = text
        // Longest first, so a value that contains another is masked in one go instead of leaving fragments
        secrets.asSequence()
            .filter { it.length >= MIN_SECRET_LENGTH && it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { result = result.replace(it, MASK) }
        return IBAN.replace(result, MASK)
    }
}
