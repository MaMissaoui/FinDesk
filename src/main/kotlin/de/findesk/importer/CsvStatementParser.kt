package de.findesk.importer

import de.findesk.util.Formats
import de.findesk.util.Iban
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Reads bank CSV exports (Sparkasse, ING, DKB, Commerzbank, comdirect, N26, … and generic ones).
 * Detects encoding, delimiter, the header row after any preamble, and maps columns by name.
 */
object CsvStatementParser {
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")
    private val UTF_16LE: Charset = Charset.forName("UTF-16LE")
    private val UTF_16BE: Charset = Charset.forName("UTF-16BE")
    private val BOM = 0xFEFF.toChar().toString()

    // Header aliases, normalised (lowercase, umlauts folded, non-alphanumerics removed). Order matters: first match wins.
    private val DATE = listOf(
        "buchungstag", "buchungsdatum", "buchung",
        // Card statements (HVB "Eingangstag", Bank Norwegian "BookDate"): the posting date wins, so date aliases
        // for the document/purchase date ("belegdatum", "transactiondate") only match later.
        "eingangstag", "bookdate",
        "datum", "bookingdate", "date", "transactiondate",
    )
    private val VALUE_DATE = listOf("valutadatum", "valuta", "wertstellung", "wertstellungsdatum", "valuedate", "belegdatum")
    private val AMOUNT = listOf("betrag", "betrageur", "betrageuro", "umsatz", "umsatzineur", "amount", "amounteur", "betragineur")
    private val DEBIT = listOf("soll", "sollbetrag", "ausgang", "debit", "lastschrift", "belastung")
    private val CREDIT = listOf("haben", "habenbetrag", "eingang", "credit", "gutschrift")
    private val PARTNER = listOf(
        "beguenstigterzahlungspflichtiger", "namezahlungsbeteiligter", "auftraggeberbeguenstigter", "zahlungsempfaengerin",
        "zahlungsempfaenger", "empfaenger", "auftraggeber", "partnername", "payee", "counterparty", "name",
    )
    private val PAYER = listOf("zahlungspflichtiger", "zahlungspflichtigerin", "zahlungspflichtige")
    private val PENDING_MARKERS = setOf("umsatz vorgemerkt", "vorgemerkt", "pending", "ausstehend", "nicht gebucht")
    private val PARTNER_IBAN = listOf("ibanzahlungsbeteiligter", "kontonummeriban", "iban", "kontonummer", "partneriban", "counterpartyiban")
    private val PURPOSE = listOf("verwendungszweck", "purpose", "reference", "beschreibung", "description", "buchungstext", "umsatzart", "vorgang", "textverwendungszweck", "text")
    private val CURRENCY = listOf("waehrung", "currency", "whrg")
    private val BALANCE = listOf("saldonachbuchung", "saldo", "kontostand", "balance")

    // FinDesk's own export headers, matched exactly and only when no bank alias matched (see ReportService.exportToCsv)
    private val OWN_PARTNER = listOf("partner", "partenaire")
    private val OWN_AMOUNT = listOf("montant")
    private val OWN_PURPOSE = listOf("libell", "libelle")
    private val OWN_CURRENCY = listOf("devise")
    private val OWN_BALANCE = listOf("solde")

    private val dateFormats = listOf("dd.MM.yyyy", "d.M.yyyy", "dd.MM.yy", "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy").map { DateTimeFormatter.ofPattern(it) }

    fun parse(file: File, override: CsvMapping? = null): ParsedStatement = parse(file.readBytes(), override)

    fun parse(bytes: ByteArray, override: CsvMapping? = null): ParsedStatement {
        val (text, charset) = decode(bytes)
        val clean = text.removePrefix(BOM)
        val lines = clean.lines()
        val delimiter = detectDelimiter(lines)
        val rows = splitRecords(clean, delimiter)
        return parseRows(rows, override, StatementFormat.CSV, charset.name(), delimiter)
    }

    /** Everything [parse] does after decoding and [splitRecords]; also used for XLSX sheets (no delimiter splitting). */
    internal fun parseRows(
        rows: List<List<String>>,
        override: CsvMapping?,
        format: StatementFormat,
        charsetName: String,
        delimiter: Char,
    ): ParsedStatement {
        val headerIndex = findHeader(rows)
        val headers = rows.getOrNull(headerIndex).orEmpty().map { it.trim() }
        val mapping = override ?: detectMapping(headers)
        val layout = CsvLayout(delimiter, charsetName, headerIndex + 1, headers, mapping)

        if (!mapping.isComplete) {
            return ParsedStatement(format, emptyList(), accountIban = preambleIban(rows.take(headerIndex)), skipped = listOf("Date and amount columns not recognised"), csv = layout)
        }

        val bookings = mutableListOf<ImportedBooking>()
        val skipped = mutableListOf<String>()
        var pending = 0
        rows.drop(headerIndex + 1).forEachIndexed { i, row ->
            if (row.all { it.isBlank() }) return@forEachIndexed
            if (row.any { it.trim().lowercase() in PENDING_MARKERS }) {
                pending++
                return@forEachIndexed
            }
            val lineNo = headerIndex + 2 + i
            try {
                bookings += toBooking(row, mapping)
            } catch (e: IllegalArgumentException) {
                skipped += "Line $lineNo: ${e.message}"
            }
        }
        return ParsedStatement(
            format = format,
            bookings = bookings,
            accountIban = preambleIban(rows.take(headerIndex)),
            skipped = skipped,
            pendingSkipped = pending,
            csv = layout,
        )
    }

    private fun toBooking(row: List<String>, m: CsvMapping): ImportedBooking {
        fun cell(i: Int?) = i?.let { row.getOrNull(it)?.trim() }?.takeIf { it.isNotEmpty() }
        val date = parseDate(cell(m.date)) ?: throw IllegalArgumentException("no valid date '${cell(m.date).orEmpty()}'")
        val amount = when {
            m.amount != null -> cell(m.amount)?.let(::parseMoney) ?: throw IllegalArgumentException("no valid amount '${cell(m.amount).orEmpty()}'")

            else -> {
                val debit = cell(m.debit)?.let(::parseMoney)?.abs()
                val credit = cell(m.credit)?.let(::parseMoney)?.abs()
                when {
                    debit != null && debit.signum() != 0 -> debit.negate()
                    credit != null -> credit
                    debit != null -> debit
                    else -> throw IllegalArgumentException("no debit or credit amount")
                }
            }
        }
        val iban = cell(m.partnerIban)?.let(Iban::normalize)?.takeIf { Iban.isValid(it) }
        return ImportedBooking(
            date = date,
            valueDate = parseDate(cell(m.valueDate)),
            amount = amount,
            currency = cell(m.currency)?.uppercase()?.takeIf { it.length == 3 },
            partnerName = (if (amount.signum() > 0 && m.payer != null) cell(m.payer) else null) ?: cell(m.partner),
            partnerIban = iban,
            purpose = cell(m.purpose)?.replace(Regex("\\s+"), " "),
            balanceAfter = cell(m.balance)?.let(::parseMoney),
        )
    }

    private fun parseMoney(s: String): java.math.BigDecimal? {
        // "-1.234,56 €", "1,234.56", "−12,00" (unicode minus), "12,00 S" (Soll suffix)
        var t = s.replace('−', '-').replace("EUR", "").trim()
        var negative = false
        if (t.endsWith(" S") || t.endsWith("-")) {
            negative = true
            t = t.removeSuffix(" S").removeSuffix("-")
        } else if (t.endsWith(" H")) {
            t = t.removeSuffix(" H")
        }
        val value = Formats.parseAmount(t) ?: return null
        return if (negative) value.abs().negate() else value
    }

    internal fun parseDate(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        val t = s.trim().take(10)
        for (f in dateFormats) {
            try {
                return LocalDate.parse(t, f)
            } catch (_: DateTimeParseException) {
                // next
            }
        }
        return null
    }

    /**
     * UTF-8 if the bytes are valid UTF-8, otherwise Windows-1252 (what most German bank exports use).
     * UTF-16 is detected first, before the strict UTF-8 attempt: card-export CSVs are UTF-16 without a BOM,
     * which would "decode" as UTF-8 with NUL bytes between the characters and then fail to map anything.
     */
    internal fun decode(bytes: ByteArray): Pair<String, Charset> {
        utf16Charset(bytes)?.let { return String(bytes, it) to it }
        return try {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes)).toString() to Charsets.UTF_8
        } catch (e: CharacterCodingException) {
            String(bytes, WINDOWS_1252) to WINDOWS_1252
        }
    }

    /** UTF-16LE/BE by BOM or (without one) by where the NUL bytes sit: every second byte is 0. */
    internal fun utf16Charset(bytes: ByteArray): Charset? {
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return UTF_16LE
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return UTF_16BE
        }
        val n = minOf(bytes.size, 512)
        if (n < 2) return null
        var evenZeros = 0
        var oddZeros = 0
        for (i in 0 until n) {
            if (bytes[i] == 0.toByte()) if (i % 2 == 0) evenZeros++ else oddZeros++
        }
        val evenTotal = n - n / 2
        val oddTotal = n / 2
        // LE: NULs on the odd (high-byte) indexes; BE: on the even ones
        val lowHalfZero = oddZeros * 100 >= 30 * oddTotal && evenZeros * 100 < 5 * evenTotal
        val mirrorZero = evenZeros * 100 >= 30 * evenTotal && oddZeros * 100 < 5 * oddTotal
        return when {
            lowHalfZero -> UTF_16LE
            mirrorZero -> UTF_16BE
            else -> null
        }
    }

    private fun detectDelimiter(lines: List<String>): Char {
        val sample = lines.filter { it.isNotBlank() }.take(30)
        return listOf(';', ',', '\t', '|').maxBy { d ->
            // Prefer the delimiter that gives the most rows with the same (>1) field count
            sample.map { line -> line.count { it == d } }.filter { it > 0 }.groupingBy { it }.eachCount().maxOfOrNull { it.value } ?: 0
        }
    }

    /** The header is the first row (within the first 30) that maps both a date and an amount column. */
    private fun findHeader(rows: List<List<String>>): Int = rows.take(30).indexOfFirst { detectMapping(it.map(String::trim)).isComplete }.takeIf { it >= 0 } ?: 0

    internal fun normalize(header: String): String = header.lowercase()
        .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
        .replace(Regex("[^a-z0-9]"), "")

    fun detectMapping(headers: List<String>): CsvMapping {
        val norm = headers.map(::normalize)
        val used = mutableSetOf<Int>()
        fun find(aliases: List<String>, exact: Boolean = false): Int? {
            for (alias in aliases) {
                val i = norm.indices.firstOrNull { it !in used && (norm[it] == alias) }
                    ?: if (exact) null else norm.indices.firstOrNull { it !in used && alias.length >= 5 && norm[it].startsWith(alias) }
                if (i != null) {
                    used += i
                    return i
                }
            }
            return null
        }
        // Order matters so that e.g. "Valutadatum" isn't taken as the booking date
        val valueDate = find(VALUE_DATE)
        val date = find(DATE)
        val debit = find(DEBIT, exact = true)
        val credit = find(CREDIT, exact = true)
        var balance = find(BALANCE)
        var amount = find(AMOUNT)
        val partnerIban = find(PARTNER_IBAN)
        val payer = find(PAYER)
        var partner = find(PARTNER)
        var purpose = find(PURPOSE)
        var currency = find(CURRENCY)
        val currencyAliasFound = currency != null
        // With both an "Amount" and a "Currency Amount" column (Bank Norwegian card exports) the "Currency" column
        // describes the original purchase amount, not the booked one; no currency column is mapped then, so the
        // booking takes the account currency.
        if (currency != null && norm.any { it == "currencyamount" } && norm.any { it == "amount" }) currency = null
        // FinDesk's own exports: exact fallbacks, used only where no bank alias matched.
        if (partner == null && payer == null) partner = find(OWN_PARTNER, exact = true)
        if (amount == null && debit == null && credit == null) amount = find(OWN_AMOUNT, exact = true)
        if (purpose == null) purpose = find(OWN_PURPOSE, exact = true)
        if (currency == null && !currencyAliasFound) currency = find(OWN_CURRENCY, exact = true)
        if (balance == null) balance = find(OWN_BALANCE, exact = true)
        return CsvMapping(
            date = date,
            valueDate = valueDate,
            amount = amount,
            debit = debit.takeIf { amount == null },
            credit = credit.takeIf { amount == null },
            partner = partner ?: payer,
            payer = payer.takeIf { partner != null },
            partnerIban = partnerIban,
            purpose = purpose,
            currency = currency,
            balance = balance,
        )
    }

    private val IBAN_PATTERN = Regex("[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}")

    /** ING/DKB name the account in a preamble line like "IBAN;DE12 …" or "Konto;Girokonto DE12…". */
    private fun preambleIban(preamble: List<List<String>>): String? = preamble.asSequence()
        .flatMap { it.asSequence() }
        .flatMap { cell -> IBAN_PATTERN.findAll(cell.uppercase()).map { Iban.normalize(it.value) } }
        .firstOrNull { Iban.isValid(it) }

    /** RFC 4180 record splitting: quoted fields may contain delimiters, doubled quotes and newlines. */
    internal fun splitRecords(text: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var field = StringBuilder()
        var record = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }

                c == '"' && (inQuotes || field.isBlank()) -> {
                    inQuotes = !inQuotes
                    if (inQuotes) field = StringBuilder()
                }

                !inQuotes && c == delimiter -> {
                    record += field.toString()
                    field = StringBuilder()
                }

                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    record += field.toString()
                    records += record
                    record = mutableListOf()
                    field = StringBuilder()
                }

                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) {
            record += field.toString()
            records += record
        }
        return records
    }
}
