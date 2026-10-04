package de.findesk.banking

import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/** Everything needed to open a FinTS PIN/TAN dialog with one bank login. */
data class FinTSConnection(
    val bankName: String,
    val blz: String,
    val userId: String,
    val customerId: String = userId,
    val serverUrl: String,
    val hbciVersion: String = "300",
    /** HBCI4Java passport file (bank parameters, system id; never the PIN). */
    val passportFile: File,
    /** Encrypts the passport file. */
    val passportPassphrase: String,
    /** Remembered TAN procedure id, if any. */
    val tanMethod: String? = null,
)

data class TransferOrder(
    val recipientName: String,
    val recipientIban: String,
    val recipientBic: String?,
    val amount: BigDecimal,
    val purpose: String,
)

/** One account's state as reported by the bank in a sync. */
data class AccountSnapshot(
    val account: FinTSAccount,
    val balance: FinTSBalance?,
    val transactions: List<FinTSTransaction>,
    /** Why statements couldn't be fetched for this account, if they couldn't. */
    val statementError: String? = null,
)

/** Port to the bank. [Hbci4JavaGateway] talks FinTS; tests use a fake. */
interface FinTSGateway {
    /**
     * Fetches all accounts of the login with balances and the bookings from [since] (per IBAN;
     * null skips statements for that account) up to [to], within a single FinTS dialog.
     */
    fun fetchAll(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        since: (iban: String) -> LocalDate?,
        to: LocalDate,
    ): List<AccountSnapshot>

    fun transfer(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        fromIban: String,
        order: TransferOrder,
    ): FinTSClient.TransferResult

    /** The TAN method chosen during the last dialog, so it can be remembered. */
    val lastTanMethod: String? get() = null
}
