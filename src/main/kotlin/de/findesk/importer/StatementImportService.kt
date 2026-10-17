package de.findesk.importer

import de.findesk.banking.FinTSClient
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.repository.*
import de.findesk.util.Iban
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.math.BigDecimal

/**
 * Imports statement files (CSV, CAMT.053 XML/ZIP) into an account, skipping bookings imported before.
 */
class StatementImportService(
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val accountRepository: AccountRepository = AccountRepositoryImpl(),
    private val ruleRepository: RuleRepository = RuleRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
) {
    data class ImportReport(
        val imported: Int,
        val duplicates: Int,
        val categorized: Int,
        val balanceUpdated: BigDecimal?,
        /** Same account, date and amount as an existing booking from another source (sync, manual entry). */
        val probableDuplicates: Int = 0,
        /** Bookings in another currency than the account; never mixed in, since amounts can't be converted. */
        val wrongCurrency: Int = 0,
        val currency: String = "EUR",
    ) {
        fun summary(): String = buildString {
            append(AppStrings.format(StringKey.ImportSummary, imported))
            if (categorized > 0) append(AppStrings.format(StringKey.ImportSummaryAuto, categorized))
            if (duplicates > 0) append(AppStrings.format(StringKey.ImportSummaryKnown, duplicates))
            if (probableDuplicates > 0) append(AppStrings.format(StringKey.ImportSummaryProbable, probableDuplicates))
            if (wrongCurrency > 0) append(AppStrings.format(StringKey.ImportSummaryWrongCurrency, wrongCurrency))
            balanceUpdated?.let { append(AppStrings.format(StringKey.ImportSummaryBalance, de.findesk.util.Formats.money(it, currency))) }
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 100L * 1024 * 1024
        val EXTENSIONS = setOf("csv", "txt", "xml", "zip", "sta", "mt940", "ofx", "qfx", "qif")
    }

    /** Reads and parses a file. [csvMapping] overrides the detected CSV column mapping. */
    suspend fun read(file: File, csvMapping: CsvMapping? = null): ParsedStatement = withContext(Dispatchers.IO) {
        require(file.isFile) { AppStrings.format(StringKey.ErrorFileNotFound, file.name) }
        require(file.length() <= MAX_FILE_BYTES) { AppStrings[StringKey.ErrorFileTooLarge] }
        val bytes = file.readBytes()
        val ext = file.extension.lowercase()
        when {
            ext == "xml" || ext == "zip" -> CamtParser.parse(bytes)
            ext == "ofx" || ext == "qfx" -> OfxParser.parse(bytes)
            ext == "qif" -> QifParser.parse(bytes)
            ext == "sta" || ext == "mt940" -> Mt940Parser.parse(bytes)
            ext == "csv" -> CsvStatementParser.parse(bytes, csvMapping)
            Mt940Parser.looksLike(bytes) -> Mt940Parser.parse(bytes)
            CamtParser.looksLikeCamt(bytes) -> CamtParser.parse(bytes)
            OfxParser.looksLike(bytes) -> OfxParser.parse(bytes)
            QifParser.looksLike(bytes) -> QifParser.parse(bytes)
            else -> CsvStatementParser.parse(bytes, csvMapping)
        }
    }

    /** Account whose IBAN the statement names, if it exists locally. */
    fun matchAccount(statement: ParsedStatement): Int? {
        val iban = statement.accountIban?.let(Iban::normalize) ?: return null
        return accountRepository.findAllItems().firstOrNull { it.iban == iban }?.id
    }

    /**
     * Stores the bookings. If [updateBalance] and the file reports running balances, the account balance is set
     * to the balance after the newest booking.
     */
    suspend fun import(accountId: Int, statement: ParsedStatement, updateBalance: Boolean = true): ImportReport = withContext(Dispatchers.IO) {
        val account = accountRepository.findAllItems().firstOrNull { it.id == accountId } ?: throw IllegalArgumentException(AppStrings[StringKey.ErrorAccountNotFound])
        // Decided before inserting anything, so the balance check and dedup see the account as it was
        val (bookings, wrongCurrency) = statement.bookings.partition { it.currency == null || it.currency == account.currency }
        val balanceAllowed = balanceUpdateAllowed(accountId, statement.copy(bookings = bookings))
        val plan = de.findesk.repository.BookingDedup.plan(
            accountId,
            hashes(account.iban, bookings).map { (b, h) -> de.findesk.repository.BookingDedup.Candidate(b, b.date, b.amount, h) },
            transactionRepository,
        )
        val rules = ruleRepository.matcher()
        val rows = plan.toImport.map { candidate ->
            val booking = candidate.item
            de.findesk.repository.NewTransaction(
                accountId = accountId,
                date = booking.date,
                amount = booking.amount,
                currency = account.currency,
                partnerName = booking.partnerName?.take(255),
                purpose = booking.purpose,
                categoryId = rules.categoryFor(booking.purpose, booking.partnerName),
                hash = candidate.hash,
            )
        }
        val imported = transactionRepository.createAll(rows)
        val categorized = rows.count { it.categoryId != null }

        // Newest booking with a balance; within a day the file order is the booking order
        val latestBalance = bookings.withIndex()
            .filter { it.value.balanceAfter != null }
            .maxWithOrNull(compareBy({ it.value.date }, { it.index }))
            ?.value?.balanceAfter
        val newBalance = if (updateBalance && balanceAllowed && latestBalance != null) {
            accountRepository.updateBalance(accountId, latestBalance)
            latestBalance
        } else {
            null
        }

        auditLogRepository.log(
            "IMPORT_STATEMENT",
            "user",
            "${statement.format.label} into …${account.iban.takeLast(4)}: $imported new, ${plan.exactDuplicates} known, ${plan.probableDuplicates} probable duplicates" +
                if (wrongCurrency.isNotEmpty()) ", ${wrongCurrency.size} in another currency skipped" else "",
        )
        ImportReport(imported, plan.exactDuplicates, categorized, newBalance, plan.probableDuplicates, wrongCurrency.size, account.currency)
    }

    /**
     * The file's running balance may only replace the account balance if the file is at least as recent as the
     * account's newest booking; back-filling an old statement must not reset today's balance.
     */
    fun balanceUpdateAllowed(accountId: Int, statement: ParsedStatement): Boolean {
        if (statement.bookings.none { it.balanceAfter != null }) return false
        val fileNewest = statement.bookings.maxOf { it.date }
        val accountNewest = transactionRepository.findItems(de.findesk.model.TransactionFilter(accountId = accountId), limit = 1).firstOrNull()?.date
        return accountNewest == null || !fileNewest.isBefore(accountNewest)
    }

    /**
     * Dedup hashes. Identifies a booking by its fields plus the bank reference or running balance; without either,
     * by its position among identical bookings that day, so genuine repeats (two coffees) are kept while
     * re-importing the same or an overlapping export is recognised.
     */
    internal fun hashes(iban: String, bookings: List<ImportedBooking>): List<Pair<ImportedBooking, String>> {
        val seen = mutableMapOf<String, Int>()
        return bookings.map { b ->
            val key = "${b.date}|${b.amount.stripTrailingZeros().toPlainString()}|${b.partnerName.orEmpty()}|${b.purpose.orEmpty()}"
            val discriminator = b.bankReference ?: b.balanceAfter?.stripTrailingZeros()?.toPlainString() ?: run {
                val n = seen.merge(key, 1, Int::plus)!!
                "occ$n"
            }
            b to FinTSClient.transactionHash(b.date, b.amount, b.partnerName, "${b.purpose.orEmpty()}#$iban#file:$discriminator")
        }
    }
}
