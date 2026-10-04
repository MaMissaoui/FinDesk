package de.findesk.importer

import java.math.BigDecimal
import java.time.LocalDate

/** One booking read from a statement file, before it is stored. */
data class ImportedBooking(
    val date: LocalDate,
    val valueDate: LocalDate? = null,
    val amount: BigDecimal,
    /** ISO code, or null when the file doesn't say (the booking then takes the account's currency). */
    val currency: String? = null,
    val partnerName: String? = null,
    val partnerIban: String? = null,
    val purpose: String? = null,
    /** Running balance after the booking, if the file has it. */
    val balanceAfter: BigDecimal? = null,
    /** Bank reference (e.g. CAMT AcctSvcrRef), if any. */
    val bankReference: String? = null,
)

enum class StatementFormat(val label: String) { CSV("CSV"), CAMT053("CAMT.053 XML"), MT940("MT940"), OFX("OFX"), QIF("QIF") }

/** Result of reading a file: bookings plus what could be detected about it. */
data class ParsedStatement(
    val format: StatementFormat,
    val bookings: List<ImportedBooking>,
    /** IBAN of the statement's own account, if the file names it. */
    val accountIban: String? = null,
    /** Rows that couldn't be read, with the reason (1-based line/entry number). */
    val skipped: List<String> = emptyList(),
    /** Pending (not yet booked) entries that were left out; they'd be imported twice once booked. */
    val pendingSkipped: Int = 0,
    /** CSV only: header and detected mapping, so the user can adjust it. */
    val csv: CsvLayout? = null,
)

/** Which CSV column holds which field (indexes into [headers]). */
data class CsvMapping(
    val date: Int? = null,
    val valueDate: Int? = null,
    val amount: Int? = null,
    /** Some banks split amounts into debit/credit columns instead of a signed amount. */
    val debit: Int? = null,
    val credit: Int? = null,
    val partner: Int? = null,
    /** Separate payer column (DKB): used as partner for incoming money, [partner] for outgoing. */
    val payer: Int? = null,
    val partnerIban: Int? = null,
    val purpose: Int? = null,
    val currency: Int? = null,
    val balance: Int? = null,
) {
    val isComplete: Boolean get() = date != null && (amount != null || debit != null || credit != null)
}

data class CsvLayout(
    val delimiter: Char,
    val charsetName: String,
    val headerLine: Int,
    val headers: List<String>,
    val mapping: CsvMapping,
)
