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

    private val FINTS_SYNTAX = Regex("[+:'?@]")

    /** IBANs as banks print them in messages: any case, with or without the usual spaces every 4 characters. */
    private val SPACED_IBAN = Regex("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){2,7}(?: ?[A-Z0-9]{1,4})?\\b", RegexOption.IGNORE_CASE)

    /** Account numbers and bank codes: 6 or more digits on their own. */
    private val LONG_DIGITS = Regex("(?<![\\dA-Za-z])\\d{6,}(?![\\dA-Za-z])")

    /**
     * Account numbers printed in digit groups after an account label ("Konto 9876 5432", "Kto.-Nr. 1234 5678 90",
     * "Account no. 9876 5432"). The label is required: unlabelled digit groups may be return codes ("9050 3076") or
     * amounts. "konto" has no word boundary so compounds match (Empfängerkonto, Girokonto); "kto" has one, for "Oktober".
     */
    private val LABELLED_ACCOUNT = Regex(
        "((?:konto(?:nummer|nr)?|\\bkto|\\baccount(?: no\\.?| number)?|\\bacct)(?:\\.?-?nr\\.?)?:?\\s+)((?>\\d{1,12}(?: \\d{1,12})*))(?![,.]?\\d)",
        RegexOption.IGNORE_CASE,
    )

    private val RETURN_CODE = Regex("(?<![\\w…])[39]\\d{3}(?!\\w)")

    /**
     * FinTS return codes (3xxx warnings, 9xxx errors) in already masked bank text. A code standing right after the
     * mask is the kept tail of an account number ("…3000"), not a code.
     */
    internal fun returnCodes(maskedText: String?): List<String> = maskedText?.let { text -> RETURN_CODE.findAll(text).map { it.value }.distinct().toList() }.orEmpty()

    fun redact(text: String, secrets: Collection<String>): String {
        var result = text
        // Longest first, so a value that contains another is masked in one go instead of leaving fragments
        secrets.asSequence()
            .filter { it.length >= MIN_SECRET_LENGTH && it.isNotBlank() }
            .flatMap { sequenceOf(it, fintsEscaped(it)) }
            .distinct()
            .sortedByDescending { it.length }
            .forEach { result = result.replace(it, MASK) }
        return IBAN.replace(result, MASK)
    }

    /**
     * Masks account identifiers in bank text before it is stored, keeping the last 4 characters so the user can
     * still tell accounts apart; return codes, dates and amounts stay readable. This includes account numbers
     * printed in digit groups after an account label ("Konto 9876 5432").
     */
    fun maskAccountNumbers(text: String): String {
        var result = SPACED_IBAN.replace(text) { "…${it.value.replace(" ", "").takeLast(4)}" }
        result = LABELLED_ACCOUNT.replace(result) { m ->
            val digits = m.groupValues[2].replace(" ", "")
            if (digits.length >= 6) m.groupValues[1] + "…" + digits.takeLast(4) else m.value
        }
        return LONG_DIGITS.replace(result) { "…${it.value.takeLast(4)}" }
    }

    /**
     * The form a value takes inside a raw FinTS message, where the syntax characters `+ : ' ? @` are escaped
     * with `?`. A PIN like `ab+cd` appears there as `ab?+cd` and would slip past a literal match.
     */
    internal fun fintsEscaped(value: String): String = value.replace(FINTS_SYNTAX) { "?" + it.value }
}
