package de.findesk.repository

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.banking.BankingService
import de.findesk.banking.FinTSAccount
import de.findesk.banking.FinTSTransaction
import de.findesk.data.table.DeletedBookingsTable
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.importer.ImportedBooking
import de.findesk.importer.ParsedStatement
import de.findesk.importer.StatementFormat
import de.findesk.importer.StatementImportService
import de.findesk.model.TransactionFilter
import de.findesk.viewmodel.AccountsViewModel
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Deleted bookings must not resurrect through an import or bank sync: deleting remembers the booking's
 * (account, hash) pair, the dedup skips such candidates, and "Allow deleted bookings again" forgets them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeletedBookingsTest {
    private val data = TestData()
    private val day = LocalDate.of(2026, 10, 5)

    @BeforeAll
    fun setup() {
        TestDatabase.init()
    }

    /** Deletes a helper booking that carries exactly [hash], which records the (account, hash) pair. */
    private fun remember(accountId: Int, hash: String) {
        val booking = data.transactionRepo.create(
            accountId = accountId,
            date = day,
            amount = BigDecimal("-1.00"),
            partnerName = "Remembered helper",
            hash = hash,
        )
        assertTrue(data.transactionRepo.delete(booking.id.value))
    }

    /** Rows in DeletedBookingsTable naming [accountId]. */
    private fun rememberedRows(accountId: Int): Int = transaction {
        DeletedBookingsTable.selectAll().where { DeletedBookingsTable.accountId eq accountId }.count().toInt()
    }

    @Test
    fun `delete and deleteAll remember the deleted bookings exactly once`() {
        val account = data.account(iban = "DE89370400440532013010")
        val first = data.transactionRepo.findById(data.txn(account, day, "-5.00", partner = "Cafe"))!!
        val second = data.transactionRepo.findById(data.txn(account, day, "-7.00", partner = "Bakery"))!!

        assertTrue(data.transactionRepo.delete(first.id.value))
        data.transactionRepo.deleteAll(listOf(second.id.value))
        assertEquals(setOf(first.hash, second.hash), data.transactionRepo.deletedHashes(account))
        assertEquals(2, rememberedRows(account), "one row per deleted booking")

        // Deleting again does nothing to the rows, so it must not record the pairs twice
        assertFalse(data.transactionRepo.delete(first.id.value), "already gone")
        data.transactionRepo.deleteAll(listOf(first.id.value, second.id.value))
        assertEquals(setOf(first.hash, second.hash), data.transactionRepo.deletedHashes(account))
        assertEquals(2, rememberedRows(account))
    }

    @Test
    fun `deleteAll with an empty collection remembers nothing`() {
        val account = data.account(iban = "DE89370400440532013011")
        data.txn(account, day, "-5.00", partner = "Cafe")
        assertEquals(0, data.transactionRepo.deleteAll(emptyList()))
        assertEquals(emptySet<String>(), data.transactionRepo.deletedHashes(account))
    }

    @Test
    fun `deletedHashes returns only that account's hashes`() {
        val one = data.account(iban = "DE89370400440532013012")
        val two = data.account(iban = "DE89370400440532013013")
        val hashOne = "1".repeat(64)
        val hashTwo = "2".repeat(64)
        remember(one, hashOne)
        remember(two, hashTwo)
        assertEquals(setOf(hashOne), data.transactionRepo.deletedHashes(one))
        assertEquals(setOf(hashTwo), data.transactionRepo.deletedHashes(two))
    }

    @Test
    fun `deleteWithTransactions removes the account's remembered hashes and leaves another account's alone`() {
        val one = data.account(iban = "DE89370400440532013014")
        val two = data.account(iban = "DE89370400440532013015")
        val hashOne = "3".repeat(64)
        val hashTwo = "4".repeat(64)
        remember(one, hashOne)
        remember(two, hashTwo)

        assertTrue(data.accountRepo.deleteWithTransactions(one))
        assertEquals(emptySet<String>(), data.transactionRepo.deletedHashes(one), "the account is gone, nothing to remember")
        assertEquals(setOf(hashTwo), data.transactionRepo.deletedHashes(two))
    }

    @Test
    fun `import skips a remembered deleted booking and forgetDeleted lets exactly it back in`() = runBlocking {
        val iban = "DE89370400440532013016"
        val account = data.account(iban = iban)
        val service = StatementImportService(data.transactionRepo, data.accountRepo)
        val statement = ParsedStatement(
            StatementFormat.CAMT053,
            bookings = listOf(
                ImportedBooking(day, amount = BigDecimal("-5.00"), partnerName = "Cafe", bankReference = "REF-CAFE"),
                ImportedBooking(day.minusDays(1), amount = BigDecimal("-7.00"), partnerName = "Bakery", bankReference = "REF-BAKERY"),
                ImportedBooking(day.minusDays(2), amount = BigDecimal("-9.00"), partnerName = "Bookshop", bankReference = "REF-BOOKSHOP"),
            ),
            accountIban = iban,
            accounts = listOf(iban),
        )

        assertEquals(3, service.import(account, statement).imported)

        val cafe = data.transactionRepo
            .findItems(TransactionFilter(accountId = account))
            .single { it.partnerName == "Cafe" }
        assertTrue(data.transactionRepo.delete(cafe.id))

        val rerun = service.import(account, statement)
        assertEquals(0, rerun.imported, "the deleted booking stays out")
        assertEquals(1, rerun.previouslyDeleted)
        assertEquals(2, rerun.duplicates, "the two stored bookings count as known")
        assertTrue(rerun.summary().contains(", 1 deleted earlier, skipped"), rerun.summary())

        assertEquals(1, data.transactionRepo.forgetDeleted(account))
        assertEquals(1, service.import(account, statement).imported, "the forgotten booking comes back, exactly it")
    }

    @Test
    fun `a deleted candidate does not change probable-duplicate counting of same-day same-amount candidates`() {
        val account = data.account(iban = "DE89370400440532013017")
        // One stored booking; a remembered-deleted and an unknown candidate share its day and amount.
        // If the deleted candidate counted as known, it would already "account for" the stored row and let
        // the unknown one import; counting it separately keeps the stored row unmatched.
        data.txn(account, day, "-5.00", partner = "Stored Cafe")
        remember(account, "6".repeat(64)) // records the deleted candidate's (account, hash) pair
        val plan = BookingDedup.plan(
            account,
            listOf(
                BookingDedup.Candidate("rememberedDeleted", day, BigDecimal("-5.00"), "6".repeat(64)),
                BookingDedup.Candidate("unknown", day, BigDecimal("-5.00"), "7".repeat(64)),
            ),
            data.transactionRepo,
        )
        assertEquals(0, plan.exactDuplicates)
        assertEquals(1, plan.previouslyDeleted, "the remembered deletion is skipped and counted")
        assertEquals(1, plan.probableDuplicates, "groups only the candidates that may still come in")
        assertTrue(plan.toImport.none { it.item == "rememberedDeleted" })
        assertTrue(plan.toImport.none { it.item == "unknown" }, "the stored row accounts for one same-day booking")
    }

    @Test
    fun `a stored row with a remembered hash counts as known, not deleted`() {
        val account = data.account(iban = "DE89370400440532013018")
        val hash = "8".repeat(64)
        remember(account, hash)
        // The same hash stored again: importing a booking whose row exists is an exact duplicate, regardless
        // of the remembered deletion
        data.transactionRepo.create(accountId = account, date = day, amount = BigDecimal("-1.00"), partnerName = "Back again", hash = hash)
        val plan = BookingDedup.plan(
            account,
            listOf(BookingDedup.Candidate("again", day, BigDecimal("-1.00"), hash)),
            data.transactionRepo,
        )
        assertEquals(1, plan.exactDuplicates)
        assertEquals(0, plan.previouslyDeleted)
        assertEquals(emptyList<String>(), plan.toImport.map { it.item }, "an exact duplicate is skipped")
    }

    @Test
    fun `bank sync skips a remembered deleted booking`() = runBlocking {
        val iban = "DE89370400440532013019"
        val account = data.account(iban = iban)
        val service = BankingService(accountRepository = data.accountRepo, transactionRepository = data.transactionRepo)
        val fintsAccount = FinTSAccount(iban = iban, bic = "COBADEFFXXX", accountNumber = "32013019", bankName = "Test Bank", blz = "37040044", accountType = "1")
        fun fintsTx(amount: String, partner: String) = FinTSTransaction(
            account = fintsAccount,
            date = day,
            valueDate = day,
            amount = BigDecimal(amount),
            partnerName = partner,
            partnerIban = null,
            partnerBic = null,
            purpose = null,
            transactionCode = null,
            reference = null,
            bankId = "BANKID-$partner",
        )
        val bankBookings = listOf(fintsTx("-5.00", "Cafe"), fintsTx("-7.00", "Bakery"))

        assertEquals(2, service.importTransactions(account, bankBookings).imported)

        val cafe = data.transactionRepo
            .findItems(TransactionFilter(accountId = account))
            .single { it.partnerName == "Cafe" }
        assertTrue(data.transactionRepo.delete(cafe.id))

        val rerun = service.importTransactions(account, bankBookings)
        assertEquals(0, rerun.imported, "the deleted booking stays out")
        assertEquals(2, rerun.duplicates, "the stored Bakery row plus the remembered deleted Cafe")
    }

    @Test
    fun `forgetDeletedBookings audits the count and messages the zero case`() = runBlocking {
        val vm = AccountsViewModel()
        vm.loadAccounts().join()
        val audit = AuditLogRepositoryImpl()
        val account = data.account(iban = "DE89370400440532013020")
        remember(account, "9".repeat(64))

        vm.forgetDeletedBookings(account).join()
        val entry = audit.findByAction("FORGET_DELETED_BOOKINGS").single()
        assertEquals("Account 3020: 1 deleted bookings may be imported again", entry.details)
        assertEquals(AppStrings.format(StringKey.MessageDeletedForgotten, 1), vm.state.value.message)

        // Nothing remembered: a message but no audit entry
        val other = data.account(iban = "DE89370400440532013021")
        vm.forgetDeletedBookings(other).join()
        assertEquals(1, audit.findByAction("FORGET_DELETED_BOOKINGS").size, "no audit entry when nothing was remembered")
        assertEquals(AppStrings[StringKey.MessageNoDeletedRemembered], vm.state.value.message)
        vm.close()
    }
}
