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

/** Intervals offered for new standing orders. FinTS expresses them as a time unit (M = months, W = weeks) and a step. */
enum class StandingOrderInterval(val timeUnit: String, val step: Int) {
    WEEKLY("W", 1),
    MONTHLY("M", 1),
    QUARTERLY("M", 3),
    HALF_YEARLY("M", 6),
    YEARLY("M", 12),
    ;

    companion object {
        fun of(timeUnit: String, step: Int): StandingOrderInterval? = entries.firstOrNull { it.timeUnit == timeUnit && it.step == step }
    }
}

/**
 * When a standing order runs. Kept as the bank's raw time unit and step, so orders with intervals FinDesk doesn't
 * offer (e.g. every 2 months) can still be shown and deleted exactly as the bank knows them.
 */
data class StandingOrderSchedule(
    val firstDate: LocalDate,
    /** "M" (months) or "W" (weeks). */
    val timeUnit: String,
    val step: Int,
    /** Day of the month (1-31, 99 = last day) or of the week (1 = Monday ... 7 = Sunday). */
    val executionDay: Int,
    val lastDate: LocalDate? = null,
) {
    val interval: StandingOrderInterval? get() = StandingOrderInterval.of(timeUnit, step)

    companion object {
        fun of(firstDate: LocalDate, interval: StandingOrderInterval, executionDay: Int, lastDate: LocalDate? = null) = StandingOrderSchedule(firstDate, interval.timeUnit, interval.step, executionDay, lastDate)
    }
}

/** A standing order as the bank reports it. */
data class StandingOrder(
    /** Bank's id; needed to delete it. */
    val orderId: String?,
    val fromIban: String,
    val order: TransferOrder,
    val schedule: StandingOrderSchedule,
    val nextDate: LocalDate? = null,
    val canDelete: Boolean = true,
)

/** One account's state as reported by the bank in a sync. */
data class AccountSnapshot(
    val account: FinTSAccount,
    val balance: FinTSBalance?,
    val transactions: List<FinTSTransaction>,
    /** Why statements couldn't be fetched for this account, if they couldn't. */
    val statementError: String? = null,
    /**
     * The bank's return code(s) for the balance request when it sent no balance (e.g. "3016"), never the bank's
     * message text (it contains the full account number).
     */
    val balanceCode: String? = null,
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

    /** SEPA transfer executed on [date] (Terminüberweisung). */
    fun scheduledTransfer(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        fromIban: String,
        order: TransferOrder,
        date: LocalDate,
    ): FinTSClient.TransferResult = throw UnsupportedOperationException("Scheduled transfers are not supported")

    fun listStandingOrders(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        fromIban: String,
    ): List<StandingOrder> = throw UnsupportedOperationException("Standing orders are not supported")

    /** Returns the bank's id for the new order, if it reports one. */
    fun createStandingOrder(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        fromIban: String,
        order: TransferOrder,
        schedule: StandingOrderSchedule,
    ): String? = throw UnsupportedOperationException("Standing orders are not supported")

    fun deleteStandingOrder(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        existing: StandingOrder,
    ): Unit = throw UnsupportedOperationException("Standing orders are not supported")

    /** The TAN method chosen during the last dialog, so it can be remembered. */
    val lastTanMethod: String? get() = null

    /** The login's supported business transactions, detected during the last dialog, if any. */
    val lastCapabilities: BankCapabilities? get() = null

    /**
     * Deletes an HBCI4Java passport file so the next dialog re-reads the login instead of reusing the values
     * stored in the old passport. Fakes keep the default and need no change.
     */
    fun resetPassport(passportFile: File) {
        passportFile.delete()
    }
}

/**
 * An order that definitely did not take effect: the bank answered it with an error, or it was never sent (rejected
 * PIN, unknown account, job not offered). Correcting and sending it again is safe. Any other failure of an order is
 * ambiguous, because the bank may have executed it before the dialog broke.
 */
class OrderNotExecutedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
