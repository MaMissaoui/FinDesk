package de.findesk.banking

import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.util.FileSecurity
import org.hbci4java.HBCI4JavaBankAccount
import org.hbci4java.HBCI4JavaClient
import org.hbci4java.HBCI4JavaConfig
import org.kapott.hbci.GV.HBCIJob
import org.kapott.hbci.GV_Result.GVRDauerEdit
import org.kapott.hbci.GV_Result.GVRDauerList
import org.kapott.hbci.GV_Result.GVRDauerNew
import org.kapott.hbci.GV_Result.GVRInstUebSEPA
import org.kapott.hbci.GV_Result.GVRKUms
import org.kapott.hbci.GV_Result.GVRSaldoReq
import org.kapott.hbci.GV_Result.GVRTermUebList
import org.kapott.hbci.GV_Result.HBCIJobResult
import org.kapott.hbci.callback.AbstractHBCICallback
import org.kapott.hbci.callback.HBCICallback
import org.kapott.hbci.exceptions.JobNotSupportedException
import org.kapott.hbci.manager.HBCIHandler
import org.kapott.hbci.manager.HBCIUtils
import org.kapott.hbci.manager.MatrixCode
import org.kapott.hbci.manager.QRCode
import org.kapott.hbci.passport.AbstractHBCIPassport
import org.kapott.hbci.passport.HBCIPassport
import org.kapott.hbci.status.HBCIExecStatus
import org.kapott.hbci.status.HBCIStatus
import org.kapott.hbci.structures.Konto
import org.kapott.hbci.structures.Value
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Properties

/**
 * FinTS PIN/TAN via HBCI4Java.
 *
 * NOT verified against a live bank in development: the request/response mapping follows the
 * HBCI4Java 4.x API, and the callback handling is unit-tested, but the first real sync should be
 * watched closely (e.g. with a small account) before relying on it.
 */
class Hbci4JavaGateway : FinTSGateway {
    @Volatile
    override var lastTanMethod: String? = null
        private set

    @Volatile
    override var lastCapabilities: BankCapabilities? = null
        private set

    /**
     * Deletes the passport under [LOCK] so a dialog in flight can't save the old passport after the delete
     * (HBCI4Java writes the passport, including BPD/UPD and system id, when a dialog ends).
     *
     * `File.delete()` can fail silently, so a passport that is still there afterwards is an error: the login
     * would otherwise keep using the old institute's values while the settings show the new ones.
     */
    override fun resetPassport(passportFile: File) {
        synchronized(LOCK) {
            passportFile.delete()
            if (passportFile.exists()) throw IllegalStateException(AppStrings[StringKey.ErrorPassportReset])
        }
    }

    override fun fetchAll(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        since: (iban: String) -> LocalDate?,
        to: LocalDate,
    ): List<AccountSnapshot> = withHandler(connection, interaction) { handler ->
        lastCapabilities = null
        val kontos = handler.kontos()

        class Pending(val konto: Konto, val from: LocalDate?) {
            var saldo: HBCIJob<*>? = null
            var umsatz: HBCIJob<*>? = null
            var statementError: String? = null
        }
        val pending = kontos.map { Pending(it, it.iban?.let(since)) }

        // Balances and statements for all accounts in one message
        pending.forEach { p ->
            p.saldo = runCatching {
                handler.newJob("SaldoReq").apply {
                    setParam("my", p.konto)
                    addToQueue()
                }
            }.getOrNull()
            // MT940 if the bank offers it, otherwise CAMT. Decided up front: a second dialog would resend the PIN
            if (p.from != null) {
                val first = statementJob(handler, "KUmsAll", p.konto, p.from, to)
                val chosen = if (first.first != null) first else statementJob(handler, "KUmsAllCamt", p.konto, p.from, to)
                p.umsatz = chosen.first
                p.statementError = chosen.second
            }
        }
        // A failed dialog (e.g. rejected PIN) must not be followed by anything that sends the PIN again
        checkStatus(handler.execute().let { it.isOK to it.errorString })

        // What this login can do, from the bank's BPD/UPD; stored so we only offer supported operations
        lastCapabilities = BankCapabilities.detect { job -> runCatching { handler.isSupported(job) }.getOrDefault(false) }

        pending.map { p ->
            val account = p.konto.toAccount(connection)
            val info = (p.saldo?.jobResult as? GVRSaldoReq)?.takeIf { it.isOK }?.entries?.firstOrNull()
            val balance = info?.ready?.value?.let { value ->
                FinTSBalance(account = account, balance = value.bigDecimalValue, availableBalance = info.available?.bigDecimalValue, currency = value.curr ?: account.currency)
            }
            // The job's own return codes (never the message text: it names the full account number)
            val balanceCode = if (balance == null) {
                val codes = (p.saldo?.jobResult?.jobStatus)?.let { st ->
                    st.errors.orEmpty().asList() + st.warnings.orEmpty().asList()
                }.orEmpty().mapNotNull { it.code }
                balanceCodes(codes)
            } else {
                null
            }
            val ums = p.umsatz?.jobResult as? GVRKUms
            val transactions = if (ums != null && ums.isOK) ums.flatData.orEmpty().map { it.toTransaction(account) } else emptyList()
            val error = when {
                p.from == null -> null
                p.umsatz == null -> p.statementError ?: AppStrings[StringKey.StatementsNotSupported]
                ums == null || !ums.isOK -> ums?.jobStatus?.toString()?.trim()?.ifEmpty { null } ?: AppStrings[StringKey.ErrorBankRejectedStatements]
                else -> null
            }
            AccountSnapshot(account, balance, transactions, error, balanceCode)
        }
    }

    private fun statementJob(handler: HBCIHandler, gv: String, konto: Konto, from: LocalDate, to: LocalDate): Pair<HBCIJob<*>?, String?> = runCatching {
        handler.newJob(gv).apply {
            setParam("my", konto)
            setParam("startdate", from.toDate())
            setParam("enddate", to.toDate())
            addToQueue()
        }
    }.fold(
        onSuccess = { it to null },
        // A bank simply not offering the job shouldn't look like an error; anything else should be shown
        // HBCI4Java wraps the reason in "error while adding task …"; the innermost message says what is actually wrong
        onFailure = { e -> null to e.takeIf { it !is JobNotSupportedException }?.let { generateSequence(it) { c -> c.cause }.mapNotNull { c -> c.message }.lastOrNull() } },
    )

    override fun transfer(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        fromIban: String,
        order: TransferOrder,
    ): FinTSClient.TransferResult = withHandler(connection, interaction) { handler ->
        val src = handler.findKonto(fromIban)
        val dst = Konto().apply {
            iban = order.recipientIban
            bic = order.recipientBic?.takeIf { it.isNotBlank() }
            name = order.recipientName
        }
        val job = handler.newJob("UebSEPA").apply {
            setParam("src", src)
            setParam("dst", dst)
            setParam("btg", Value(order.amount, "EUR"))
            setParam("usage", order.purpose)
            addToQueue()
        }
        checkOrder(handler.execute(), job)
        FinTSClient.TransferResult(transactionId = job.jobResult.jobId ?: "", status = "ACCEPTED")
    }

    /**
     * SEPA instant transfer (Echtzeitüberweisung, HKIPZ). The job takes the same parameters as a normal transfer;
     * the difference is the answer: the bank reports an order status (1-9) it keeps raw in the result and a
     * cancellation code when it stopped the order. FinDesk says "accepted by the bank", never "executed".
     */
    override fun instantTransfer(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        fromIban: String,
        order: TransferOrder,
    ): FinTSClient.TransferResult = withHandler(connection, interaction) { handler ->
        val src = handler.findKonto(fromIban)
        val dst = Konto().apply {
            iban = order.recipientIban
            bic = order.recipientBic?.takeIf { it.isNotBlank() }
            name = order.recipientName
        }
        val job = handler.newJob("InstUebSEPA").apply {
            setParam("src", src)
            setParam("dst", dst)
            setParam("btg", Value(order.amount, "EUR"))
            setParam("usage", order.purpose)
            addToQueue()
        }
        checkOrder(handler.execute(), job)
        val result = job.jobResult as? GVRInstUebSEPA
        result?.cancellationCode?.takeIf { it.isNotBlank() }?.let {
            throw OrderNotExecutedException(AppStrings.format(StringKey.ErrorInstantCancelled, it))
        }
        FinTSClient.TransferResult(
            transactionId = result?.orderId ?: job.jobResult.jobId ?: "",
            status = "ACCEPTED",
            bankStatusCode = result?.orderStatus,
        )
    }

    override fun scheduledTransfer(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        fromIban: String,
        order: TransferOrder,
        date: LocalDate,
    ): FinTSClient.TransferResult = withHandler(connection, interaction) { handler ->
        val job = handler.newJob("TermUebSEPA").apply {
            setOrderParams(handler.findKonto(fromIban), order)
            setParam("date", date.toDate())
            addToQueue()
        }
        checkOrder(handler.execute(), job)
        FinTSClient.TransferResult(transactionId = job.jobResult.jobId ?: "", status = "SCHEDULED")
    }

    override fun listStandingOrders(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        fromIban: String,
    ): List<StandingOrder> = withHandler(connection, interaction) { handler ->
        val job = handler.newJob("DauerSEPAList").apply {
            setParam("src", handler.findKonto(fromIban))
            addToQueue()
        }
        checkStatus(handler.execute().let { it.isOK to it.errorString })
        checkJob(job.jobResult)
        (job.jobResult as GVRDauerList).entries.orEmpty().mapNotNull { d ->
            val first = d.firstdate ?: return@mapNotNull null
            StandingOrder(
                orderId = d.orderid,
                fromIban = d.my?.iban ?: fromIban,
                order = TransferOrder(
                    recipientName = d.other?.name.orEmpty(),
                    recipientIban = d.other?.iban.orEmpty(),
                    recipientBic = d.other?.bic,
                    amount = d.value?.bigDecimalValue ?: java.math.BigDecimal.ZERO,
                    purpose = d.usage.orEmpty().joinToString(" ").trim(),
                ),
                schedule = StandingOrderSchedule(first.toLocalDate(), d.timeunit.orEmpty().uppercase(), d.turnus, d.execday, d.lastdate?.toLocalDate()),
                nextDate = d.nextdate?.toLocalDate(),
                // Trust the bank: if it says no (or gave no id), deleting is left to online banking
                canDelete = d.can_delete && d.orderid != null,
                canChange = d.can_change && d.orderid != null,
            )
        }
    }

    override fun createStandingOrder(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        fromIban: String,
        order: TransferOrder,
        schedule: StandingOrderSchedule,
    ): String? = withHandler(connection, interaction) { handler ->
        val job = handler.newJob("DauerSEPANew").apply {
            setOrderParams(handler.findKonto(fromIban), order)
            setScheduleParams(schedule)
            addToQueue()
        }
        checkOrder(handler.execute(), job)
        (job.jobResult as? GVRDauerNew)?.orderId
    }

    override fun deleteStandingOrder(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        existing: StandingOrder,
    ) = withHandler(connection, interaction) { handler ->
        // The bank identifies the order by its id but expects the original order data alongside
        val job = handler.newJob("DauerSEPADel").apply {
            setOrderParams(handler.findKonto(existing.fromIban), existing.order)
            setScheduleParams(existing.schedule)
            setParam("orderid", requireNotNull(existing.orderId) { AppStrings[StringKey.ErrorNoStandingOrderId] })
            addToQueue()
        }
        checkOrder(handler.execute(), job)
    }

    override fun editStandingOrder(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        existing: StandingOrder,
        order: TransferOrder,
        schedule: StandingOrderSchedule,
    ): String? = withHandler(connection, interaction) { handler ->
        // The bank identifies the order by its id but takes the changed order data and schedule alongside
        val job = handler.newJob("DauerSEPAEdit").apply {
            setOrderParams(handler.findKonto(existing.fromIban), order)
            setScheduleParams(schedule)
            setParam("orderid", requireNotNull(existing.orderId) { AppStrings[StringKey.ErrorNoStandingOrderId] })
            addToQueue()
        }
        checkOrder(handler.execute(), job)
        (job.jobResult as? GVRDauerEdit)?.orderId
    }

    override fun listScheduledTransfers(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        fromIban: String,
    ): List<ScheduledTransfer> = withHandler(connection, interaction) { handler ->
        val job = handler.newJob("TermUebSEPAList").apply {
            setParam("src", handler.findKonto(fromIban))
            addToQueue()
        }
        checkStatus(handler.execute().let { it.isOK to it.errorString })
        checkJob(job.jobResult)
        (job.jobResult as GVRTermUebList).entries.orEmpty().mapNotNull { t ->
            // An entry the bank reports without a date cannot be shown or cancelled: skip it
            val date = t.date ?: return@mapNotNull null
            ScheduledTransfer(
                orderId = t.orderid,
                fromIban = t.my?.iban ?: fromIban,
                order = TransferOrder(
                    recipientName = t.other?.name.orEmpty(),
                    recipientIban = t.other?.iban.orEmpty(),
                    recipientBic = t.other?.bic,
                    amount = t.value?.bigDecimalValue ?: java.math.BigDecimal.ZERO,
                    purpose = t.usage.orEmpty().joinToString(" ").trim(),
                ),
                date = date.toLocalDate(),
                // Trust the bank: if it says no (or gave no id), deleting is left to online banking
                canDelete = t.can_delete && t.orderid != null,
            )
        }
    }

    override fun deleteScheduledTransfer(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        existing: ScheduledTransfer,
    ) = withHandler(connection, interaction) { handler ->
        // The bank identifies the order by its id but expects the original order data and the execution date alongside
        val job = handler.newJob("TermUebSEPADel").apply {
            setOrderParams(handler.findKonto(existing.fromIban), existing.order)
            setParam("date", existing.date.toDate())
            setParam("orderid", requireNotNull(existing.orderId) { AppStrings[StringKey.ErrorNoScheduledTransferId] })
            addToQueue()
        }
        checkOrder(handler.execute(), job)
    }

    private fun HBCIJob<*>.setOrderParams(src: Konto, order: TransferOrder) {
        setParam("src", src)
        setParam(
            "dst",
            Konto().apply {
                iban = order.recipientIban
                bic = order.recipientBic?.takeIf { it.isNotBlank() }
                name = order.recipientName
            },
        )
        setParam("btg", Value(order.amount, "EUR"))
        setParam("usage", order.purpose)
    }

    private fun HBCIJob<*>.setScheduleParams(schedule: StandingOrderSchedule) {
        setParam("firstdate", schedule.firstDate.toDate())
        setParam("timeunit", schedule.timeUnit)
        setParam("turnus", schedule.step)
        setParam("execday", schedule.executionDay)
        schedule.lastDate?.let { setParam("lastdate", it.toDate()) }
    }

    private fun <T> withHandler(connection: FinTSConnection, interaction: BankingInteraction, block: (HBCIHandler) -> T): T {
        connection.passportFile.parentFile?.let { FileSecurity.secureDirectory(it) }
        val recorder = if (ProtocolTraceStore.enabled) {
            TraceRecorder(connection.bankName, connection.blz, connection.hbciVersion).apply {
                addSecret(connection.userId, connection.customerId, connection.passportPassphrase)
            }
        } else {
            null
        }
        val callback = FinDeskHbciCallback(connection, interaction, recorder)
        val props = Properties().apply {
            setProperty("client.passport.default", "PinTan")
            setProperty("client.passport.PinTan.filename", connection.passportFile.absolutePath)
            setProperty("client.passport.PinTan.init", "1")
            // Level 5 also logs the FinTS message bodies; only raise it while the user wants a trace
            setProperty("log.loglevel.default", if (recorder != null) "5" else "1")
        }
        // HBCI4Java keeps per-thread global state; serialise dialogs
        synchronized(LOCK) {
            // Before anything is sent: an existing passport with a different login would silently win over the
            // connection's values, so it is healed (deleted) first and recreated by this dialog from [connection].
            healStalePassport(connection, callback)
            try {
                HBCI4JavaClient(HBCI4JavaConfig(props), callback).use { client ->
                    client.createSession(HBCI4JavaBankAccount(HBCI4JavaBankAccount.Type.PINTAN, connection.passportFile)).use { session ->
                        val result = session.execute { handler ->
                            handler.passport.accounts.orEmpty().forEach { recorder?.addSecret(it.number, it.iban) }
                            block(handler)
                        }
                        lastTanMethod = callback.chosenTanMethod
                        recorder?.let { ProtocolTraceStore.publish(it.build()) }
                        return result
                    }
                }
            } catch (e: Throwable) {
                // A failed dialog is exactly the one worth sharing, so publish its trace too
                recorder?.let { ProtocolTraceStore.publish(it.build()) }
                throw unwrap(e, callback)
            } finally {
                // The passport file holds the bank's keys; keep it readable only by the owner
                FileSecurity.secureFile(connection.passportFile)
            }
        }
    }

    /**
     * HBCI4Java takes the BLZ, user id and host from an existing passport file and ignores what FinDesk passes in,
     * so a connection edited before FinDesk started resetting the passport (or while a reset was still in flight)
     * would silently keep the old login. Read the passport's stored values offline and delete it on any mismatch;
     * this dialog then creates a fresh passport from [connection] in the same call.
     *
     * Reading uses the same callback (so the passphrase is answered, no dialog is shown) and only local file and
     * bank-directory lookups: no HBCI session, handler or network traffic happens before this check returns.
     */
    private fun healStalePassport(connection: FinTSConnection, callback: FinDeskHbciCallback) {
        if (!connection.passportFile.exists()) return
        val stored = try {
            storedLogin(connection, callback)
        } catch (e: Exception) {
            // A wrong passphrase must never destroy a passport: keep it and let the dialog report the failure
            callback.log("FinDesk: could not read the stored bank registration (${e.javaClass.simpleName}); keeping it", HBCIUtils.LOG_WARN, null, null)
            return
        }
        if (!passportMismatch(stored, connection)) return
        // Field names only, never values: shows up in a protocol trace if a passport is ever reset unexpectedly
        val fields = listOfNotNull(
            "blz".takeIf { stored.blz != connection.blz },
            "user id".takeIf { stored.userId.trim() != connection.userId.trim() },
            "host".takeIf { normaliseHost(stored.host) != normaliseHost(FinDeskHbciCallback.hostFor(connection.serverUrl)) },
        )
        callback.log("FinDesk: stored bank registration differs from the connection (${fields.joinToString()}); resetting it", HBCIUtils.LOG_WARN, null, null)
        connection.passportFile.delete()
        if (connection.passportFile.exists()) throw IllegalStateException(AppStrings[StringKey.ErrorPassportReset])
    }

    /**
     * Reads the login HBCI4Java stored in the passport file. A throwaway client registers our callback (for the
     * passport's passphrase) and, like the real dialog, loads the PinTan passport from disk; the Passport is closed
     * right away so it is never written.
     */
    private fun storedLogin(connection: FinTSConnection, callback: FinDeskHbciCallback): StoredLogin {
        val props = Properties().apply {
            setProperty("client.passport.default", "PinTan")
            setProperty("client.passport.PinTan.filename", connection.passportFile.absolutePath)
            setProperty("client.passport.PinTan.init", "1")
            setProperty("log.loglevel.default", "1")
        }
        return HBCI4JavaClient(HBCI4JavaConfig(props), callback).use {
            val passport = AbstractHBCIPassport.getInstance("PinTan", connection.passportFile)
            try {
                StoredLogin(blz = passport.blz, userId = passport.userId, host = passport.host)
            } finally {
                passport.close()
            }
        }
    }

    private fun unwrap(e: Throwable, callback: FinDeskHbciCallback): Throwable {
        generateSequence(e) { it.cause }.firstOrNull { it is BankingCancelledException || it is OrderNotExecutedException }?.let { return it }
        // A rejected PIN fails the dialog before any order in it is sent
        if (callback.pinRejected) {
            return OrderNotExecutedException(callback.bankMessages.lastOrNull() ?: AppStrings[StringKey.ErrorBankRejectedPin], e)
        }
        if (generateSequence(e) { it.cause }.any { it is JobNotSupportedException }) {
            return UnsupportedOperationException(AppStrings[StringKey.ErrorOrderNotOffered], e)
        }
        // An HTTP answer is the real cause even when an IOException cause survived: don't call it "unreachable"
        BankingFailures.httpStatus(e, callback.diagnostics)?.let {
            return IllegalStateException(AppStrings.format(StringKey.ErrorServerHttpStatus, it), e)
        }
        if (BankingFailures.isNetworkFailure(e, callback.diagnostics)) {
            return IllegalStateException(BankingFailures.networkMessage(e), e)
        }
        return IllegalStateException(BankingFailures.describe(e, callback.bankMessages, callback.diagnostics), e)
    }

    private fun checkStatus(status: Pair<Boolean, String?>) {
        if (!status.first) throw IllegalStateException(status.second?.trim()?.ifEmpty { null } ?: AppStrings[StringKey.ErrorBankRejected])
    }

    private fun checkJob(result: HBCIJobResult) {
        if (!result.isOK) throw IllegalStateException(result.jobStatus?.toString()?.trim()?.ifEmpty { null } ?: AppStrings[StringKey.ErrorBankRejected])
    }

    /**
     * An order's outcome comes from its own job status, not the dialog's: an order the bank accepted counts as sent
     * even if a later step of the dialog failed, so it is never offered for sending again.
     */
    private fun checkOrder(exec: HBCIExecStatus, job: HBCIJob<*>) {
        orderOutcome(job.jobResult.jobStatus, exec.errorString)?.let { throw it }
    }

    /** The login's accounts with their BIC filled in where the bank left it out; see [bicFor]. */
    private fun HBCIHandler.kontos(): List<Konto> = passport.accounts.orEmpty().toList().onEach { it.bic = bicFor(it.bic, it.blz, it.iban, passport.blz) }

    private fun HBCIHandler.findKonto(iban: String): Konto = kontos().firstOrNull { it.iban.equals(iban, ignoreCase = true) }
        ?: throw OrderNotExecutedException("Account ${iban.takeLast(4)} is not available with this bank login")

    private fun Konto.toAccount(connection: FinTSConnection) = FinTSAccount(
        iban = iban.orEmpty(),
        bic = bic.orEmpty(),
        accountNumber = number.orEmpty(),
        subAccountNumber = subnumber,
        bankName = connection.bankName,
        blz = blz ?: connection.blz,
        accountType = accountTypeFor(acctype),
        currency = curr ?: "EUR",
        productName = type,
    )

    private fun GVRKUms.UmsLine.toTransaction(account: FinTSAccount) = FinTSTransaction(
        account = account,
        date = (bdate ?: valuta).toLocalDate(),
        valueDate = (valuta ?: bdate).toLocalDate(),
        amount = value.bigDecimalValue,
        currency = value.curr ?: account.currency,
        partnerName = other?.let { listOfNotNull(it.name, it.name2).joinToString(" ").trim().ifEmpty { null } },
        partnerIban = other?.iban,
        partnerBic = other?.bic,
        purpose = usage.orEmpty().joinToString(" ").trim().ifEmpty { text },
        transactionCode = gvcode,
        reference = endToEndId?.takeIf { it != "NOTPROVIDED" } ?: customerref?.takeIf { it != "NONREF" },
        primaNota = primanota,
        balanceAfter = saldo?.value?.bigDecimalValue,
        bankId = id,
    )

    companion object {
        private val LOCK = Any()

        /**
         * Null when the bank accepted the job. Only a bank answer made of error codes (9xxx) without local exceptions is a
         * definite rejection ([OrderNotExecutedException]); anything else (no answer, an exception while sending or reading
         * the response) leaves open whether the bank executed the order.
         */
        internal fun orderOutcome(status: HBCIStatus?, dialogError: String?): Exception? {
            if (status?.isOK == true) return null
            val reason = status?.toString()?.trim()?.ifEmpty { null } ?: dialogError?.trim()?.ifEmpty { null } ?: AppStrings[StringKey.ErrorBankRejected]
            return if (status != null && status.hasErrors() && !status.hasExceptions()) OrderNotExecutedException(reason) else IllegalStateException(reason)
        }

        /**
         * HBCI4Java requires the account's own BIC for balances, CAMT statements and every SEPA order, but takes it only
         * from the bank's account data (UPD), and some banks (DKB) leave it out. Without it the job can't even be queued
         * ("error while adding task KUmsZeitCamt1"). The BIC follows from the BLZ, so it is looked up in the bank directory.
         */
        internal fun bicFor(bic: String?, blz: String?, iban: String?, loginBlz: String?): String? = bic?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(blz, iban?.let(BankDirectory::blzFromIban), loginBlz)
                .firstNotNullOfOrNull { BankDirectory.lookup(it)?.bic?.takeIf(String::isNotBlank) }
            ?: bic

        /**
         * FinTS account type codes: 1-9 checking, 10-29 savings (savings, fixed-term), 30-39 depot, 40-49 loan,
         * 50-59 credit card, 60-69 depot (fund depot), 70-79 savings (building savings contract); anything else
         * (or null) is "checking".
         */
        internal fun accountTypeFor(code: String?): String = when (code?.toIntOrNull()) {
            in 1..9 -> "checking"
            in 10..29 -> "savings"
            in 30..39 -> "depot"
            in 40..49 -> "loan"
            in 50..59 -> "credit_card"
            in 60..69 -> "depot"
            in 70..79 -> "savings"
            else -> "checking"
        }

        /**
         * The bank's return codes when it sent no balance, or null when only success codes and dialog-wide notes
         * remain. Codes starting with "0" are successes; 3060 and 3076 are notes about the whole dialog, not reasons
         * for this account.
         */
        internal fun balanceCodes(codes: List<String>): String? = codes
            .filterNot { it.startsWith("0") || it == "3060" || it == "3076" }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", ")

        private fun LocalDate.toDate(): Date = Date.from(atStartOfDay(ZoneId.systemDefault()).toInstant())

        private fun Date.toLocalDate(): LocalDate = toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
    }
}

/**
 * Answers HBCI4Java's callbacks: connection data from [connection], everything the user must decide
 * through [interaction]. Throws [BankingCancelledException] to abort the dialog.
 */
internal class FinDeskHbciCallback(
    private val connection: FinTSConnection,
    private val interaction: BankingInteraction,
    /** Opt-in capture of the dialog for support; null when tracing is off. */
    private val recorder: TraceRecorder? = null,
) : AbstractHBCICallback() {
    var chosenTanMethod: String? = connection.tanMethod
        private set
    val bankMessages = mutableListOf<String>()
    private var pin: String? = null

    /** The bank rejected the last PIN entered; the dialog then fails at its start, before any order is sent. */
    var pinRejected = false
        private set

    /** Error-level log lines (level 1), used to explain failures. Higher levels may contain message dumps and are dropped. */
    val diagnostics = mutableListOf<String>()

    override fun log(msg: String?, level: Int, date: Date?, trace: StackTraceElement?) {
        if (msg.isNullOrBlank()) return
        recorder?.record(level, msg)
        if (level <= 1) diagnostics += msg.take(500)
    }

    override fun status(passport: HBCIPassport?, statusTag: Int, o: Array<out Any?>?) {}

    override fun callback(passport: HBCIPassport?, reason: Int, msg: String?, datatype: Int, retData: StringBuffer) {
        val message = msg.orEmpty()
        when (reason) {
            HBCICallback.NEED_PASSPHRASE_LOAD, HBCICallback.NEED_PASSPHRASE_SAVE -> retData.set(connection.passportPassphrase)

            HBCICallback.NEED_COUNTRY -> retData.set("DE")

            HBCICallback.NEED_BLZ -> retData.set(connection.blz)

            // PIN/TAN host is the URL without protocol, e.g. "banking.example.de/fints30"
            HBCICallback.NEED_HOST -> retData.set(hostFor(connection.serverUrl))

            HBCICallback.NEED_PORT -> retData.set("443")

            HBCICallback.NEED_FILTER -> retData.set("Base64")

            HBCICallback.NEED_USERID -> retData.set(connection.userId)

            HBCICallback.NEED_CUSTOMERID -> retData.set(connection.customerId)

            HBCICallback.NEED_CONNECTION, HBCICallback.CLOSE_CONNECTION -> Unit

            HBCICallback.NEED_PT_PIN -> {
                val value = pin ?: interaction.pin(connection.bankName, connection.userId)?.pin?.takeIf { it.isNotEmpty() } ?: cancel()
                // A new PIN after a rejected one may open the dialog, and then its orders do get sent
                if (pin == null) pinRejected = false
                pin = value
                recorder?.addSecret(value)
                retData.set(value)
            }

            HBCICallback.NEED_PT_SECMECH -> retData.set(selectTanMethod(retData.toString()))

            HBCICallback.NEED_PT_TANMEDIA -> {
                val media = retData.toString().split('|').map { it.trim() }.filter { it.isNotEmpty() }
                val chosen = if (media.size <= 1) media.firstOrNull().orEmpty() else interaction.selectTanMedium(media) ?: cancel()
                retData.set(chosen)
            }

            HBCICallback.NEED_PT_TAN -> {
                val tan = interaction.tan(TanChallenge(message, flickerCode = flickerFor(retData.toString()))) ?: cancel()
                recorder?.addSecret(tan)
                retData.set(tan)
            }

            HBCICallback.NEED_PT_PHOTOTAN -> {
                val code = MatrixCode.tryParse(retData.toString())
                val tan = interaction.tan(TanChallenge(message, code?.image, code?.mimetype)) ?: cancel()
                recorder?.addSecret(tan)
                retData.set(tan)
            }

            HBCICallback.NEED_PT_QRTAN -> {
                val code = QRCode.tryParse(retData.toString(), message)
                val tan = interaction.tan(TanChallenge(code?.message ?: message, code?.image, code?.mimetype)) ?: cancel()
                recorder?.addSecret(tan)
                retData.set(tan)
            }

            HBCICallback.NEED_PT_DECOUPLED, HBCICallback.NEED_PT_DECOUPLED_RETRY ->
                if (!interaction.confirmDecoupled(message.ifEmpty { AppStrings[StringKey.ConfirmInBankingApp] })) cancel()

            HBCICallback.HAVE_VOP_RESULT -> {
                // HBCI4Java reads this buffer back with StringUtil.toBoolean() and aborts on false,
                // so a confirmed payee must be written back or every VoP transfer would fail.
                val confirmed = interaction.confirmPayee(message.ifEmpty { retData.toString() })
                if (!confirmed) cancel(AppStrings[StringKey.ErrorPayeeCancelled])
                retData.set("true")
            }

            HBCICallback.HAVE_INST_MSG -> interaction.info(message)

            HBCICallback.HAVE_ERROR -> {
                bankMessages += message
                retData.set("ERR") // abort instead of silently continuing past an error
            }

            HBCICallback.WRONG_PIN -> {
                pin = null
                pinRejected = true
                bankMessages += AppStrings[StringKey.ErrorBankRejectedPin]
            }

            else -> Unit // NEED_INFOPOINT_ACK etc.: acknowledge by leaving retData unchanged
        }
    }

    /** Options arrive as "id:name|id:name". Keeps the remembered method if the bank still offers it. */
    private fun selectTanMethod(options: String): String {
        val methods = options.split('|').mapNotNull { entry ->
            val idx = entry.indexOf(':')
            if (idx <= 0) null else TanMethod(id = entry.substring(0, idx).trim(), name = entry.substring(idx + 1).trim())
        }
        val chosen = when {
            methods.isEmpty() -> options.trim()
            chosenTanMethod != null && methods.any { it.id == chosenTanMethod } -> chosenTanMethod!!
            methods.size == 1 -> methods.single().id
            else -> interaction.selectTanMethod(methods) ?: cancel()
        }
        chosenTanMethod = chosen
        return chosen
    }

    companion object {
        /** chipTAN optical: the challenge's HHD code rendered for flickering, or null if there is none. */
        fun flickerFor(challengeData: String): String? = challengeData.trim().takeIf { it.isNotEmpty() }?.let { runCatching { org.kapott.hbci.manager.FlickerCode(it).render() }.getOrNull() }

        /**
         * HBCI4Java wants the PIN/TAN address without protocol and builds the URL with port 443 itself,
         * so "https://bank.de:443/fints" becomes "bank.de/fints". Other explicit ports aren't supported.
         */
        fun hostFor(serverUrl: String): String {
            val withoutScheme = serverUrl.trim().removePrefix("https://").removePrefix("http://")
            val host = withoutScheme.substringBefore('/')
            val path = withoutScheme.substring(host.length)
            val port = host.substringAfter(':', "")
            require(port.isEmpty() || port == "443") { "FinTS server URLs with port $port aren't supported (only 443)" }
            return host.substringBefore(':') + path
        }
    }

    private fun cancel(message: String = AppStrings[StringKey.ErrorCancelledByUser]): Nothing = throw BankingCancelledException(message)

    private fun StringBuffer.set(value: String) {
        setLength(0)
        append(value)
    }
}

/** The login HBCI4Java has stored in an existing passport file, read offline (see [Hbci4JavaGateway.storedLogin]). */
internal data class StoredLogin(val blz: String, val userId: String, val host: String)

/**
 * Whether an existing passport's stored login differs from [connection]. HBCI4Java takes the BLZ, user id and
 * host from the passport file and ignores what is passed in, so any mismatch means the passport has to be reset.
 *
 * The BLZ is compared exactly and the user id after trimming; hosts are normalised because HBCI4Java may store
 * them with an explicit `:443` port or a trailing slash.
 */
internal fun passportMismatch(stored: StoredLogin, connection: FinTSConnection): Boolean {
    if (stored.blz != connection.blz) return true
    if (stored.userId.trim() != connection.userId.trim()) return true
    return normaliseHost(stored.host) != normaliseHost(FinDeskHbciCallback.hostFor(connection.serverUrl))
}

/** Lower-cases a stored host and drops a trailing slash and an explicit `:443` port that HBCI4Java may add. */
internal fun normaliseHost(host: String): String {
    val lower = host.trim().lowercase()
    return lower.replace(":443/", "/").removeSuffix(":443").removeSuffix("/")
}
