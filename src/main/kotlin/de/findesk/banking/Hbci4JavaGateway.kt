package de.findesk.banking

import org.hbci4java.HBCI4JavaBankAccount
import org.hbci4java.HBCI4JavaClient
import org.hbci4java.HBCI4JavaConfig
import org.kapott.hbci.GV.HBCIJob
import org.kapott.hbci.GV_Result.GVRDauerList
import org.kapott.hbci.GV_Result.GVRDauerNew
import org.kapott.hbci.GV_Result.GVRKUms
import org.kapott.hbci.GV_Result.GVRSaldoReq
import org.kapott.hbci.GV_Result.HBCIJobResult
import org.kapott.hbci.callback.AbstractHBCICallback
import org.kapott.hbci.callback.HBCICallback
import org.kapott.hbci.exceptions.JobNotSupportedException
import org.kapott.hbci.manager.HBCIHandler
import org.kapott.hbci.manager.MatrixCode
import org.kapott.hbci.manager.QRCode
import org.kapott.hbci.passport.HBCIPassport
import org.kapott.hbci.structures.Konto
import org.kapott.hbci.structures.Value
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

    override fun fetchAll(
        connection: FinTSConnection,
        interaction: BankingInteraction,
        since: (iban: String) -> LocalDate?,
        to: LocalDate,
    ): List<AccountSnapshot> = withHandler(connection, interaction) { handler ->
        val kontos = handler.passport.accounts.orEmpty().toList()

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
                p.umsatz = statementJob(handler, "KUmsAll", p.konto, p.from, to) ?: statementJob(handler, "KUmsAllCamt", p.konto, p.from, to)
            }
        }
        // A failed dialog (e.g. rejected PIN) must not be followed by anything that sends the PIN again
        checkStatus(handler.execute().let { it.isOK to it.errorString })

        pending.map { p ->
            val account = p.konto.toAccount(connection)
            val info = (p.saldo?.jobResult as? GVRSaldoReq)?.takeIf { it.isOK }?.entries?.firstOrNull()
            val balance = info?.ready?.value?.let { value ->
                FinTSBalance(account = account, balance = value.bigDecimalValue, availableBalance = info.available?.bigDecimalValue, currency = value.curr ?: account.currency)
            }
            val ums = p.umsatz?.jobResult as? GVRKUms
            val transactions = if (ums != null && ums.isOK) ums.flatData.orEmpty().map { it.toTransaction(account) } else emptyList()
            val error = when {
                p.from == null -> null
                ums == null -> "Statements not supported for this account"
                !ums.isOK -> ums.jobStatus?.toString()?.trim()?.ifEmpty { null } ?: "The bank rejected the statement request"
                else -> null
            }
            AccountSnapshot(account, balance, transactions, error)
        }
    }

    private fun statementJob(handler: HBCIHandler, gv: String, konto: Konto, from: LocalDate, to: LocalDate): HBCIJob<*>? = runCatching {
        handler.newJob(gv).apply {
            setParam("my", konto)
            setParam("startdate", from.toDate())
            setParam("enddate", to.toDate())
            addToQueue()
        }
    }.getOrNull()

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
        checkStatus(handler.execute().let { it.isOK to it.errorString })
        checkJob(job.jobResult)
        FinTSClient.TransferResult(transactionId = job.jobResult.jobId ?: "", status = "ACCEPTED")
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
        checkStatus(handler.execute().let { it.isOK to it.errorString })
        checkJob(job.jobResult)
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
        checkStatus(handler.execute().let { it.isOK to it.errorString })
        checkJob(job.jobResult)
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
            setParam("orderid", requireNotNull(existing.orderId) { "The bank did not report an id for this standing order" })
            addToQueue()
        }
        checkStatus(handler.execute().let { it.isOK to it.errorString })
        checkJob(job.jobResult)
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
        connection.passportFile.parentFile?.mkdirs()
        val callback = FinDeskHbciCallback(connection, interaction)
        val props = Properties().apply {
            setProperty("client.passport.default", "PinTan")
            setProperty("client.passport.PinTan.filename", connection.passportFile.absolutePath)
            setProperty("client.passport.PinTan.init", "1")
            setProperty("log.loglevel.default", "1")
        }
        // HBCI4Java keeps per-thread global state; serialise dialogs
        synchronized(LOCK) {
            try {
                HBCI4JavaClient(HBCI4JavaConfig(props), callback).use { client ->
                    client.createSession(HBCI4JavaBankAccount(HBCI4JavaBankAccount.Type.PINTAN, connection.passportFile)).use { session ->
                        return session.execute { handler -> block(handler) }.also { lastTanMethod = callback.chosenTanMethod }
                    }
                }
            } catch (e: Throwable) {
                throw unwrap(e, callback)
            }
        }
    }

    private fun unwrap(e: Throwable, callback: FinDeskHbciCallback): Throwable {
        generateSequence(e) { it.cause }.firstOrNull { it is BankingCancelledException }?.let { return it }
        if (generateSequence(e) { it.cause }.any { it is JobNotSupportedException }) {
            return UnsupportedOperationException("Your bank does not offer this order type over FinTS for this account.", e)
        }
        val networkFailure = generateSequence(e) { it.cause }.any { it is java.io.IOException } ||
            callback.diagnostics.any { it.contains("Senden der HBCI-Nachricht") || it.contains("IOException") }
        if (networkFailure) {
            return IllegalStateException("Could not reach the bank's FinTS server. Check the server URL and your internet connection.", e)
        }
        val bankText = callback.bankMessages.lastOrNull() ?: callback.diagnostics.lastOrNull()?.substringAfter("Exception: ")
        val root = generateSequence(e) { it.cause }.last()
        return IllegalStateException(bankText ?: root.message ?: e.message ?: "FinTS dialog failed", e)
    }

    private fun checkStatus(status: Pair<Boolean, String?>) {
        if (!status.first) throw IllegalStateException(status.second?.trim()?.ifEmpty { null } ?: "The bank rejected the request")
    }

    private fun checkJob(result: HBCIJobResult) {
        if (!result.isOK) throw IllegalStateException(result.jobStatus?.toString()?.trim()?.ifEmpty { null } ?: "The bank rejected the order")
    }

    private fun HBCIHandler.findKonto(iban: String): Konto = passport.accounts.orEmpty().firstOrNull { it.iban.equals(iban, ignoreCase = true) }
        ?: throw IllegalArgumentException("Account ${iban.takeLast(4)} is not available with this bank login")

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

        /** FinTS account type codes: 1-9 checking, 10-19 savings, 30-39 depot, 50-59 credit card. */
        internal fun accountTypeFor(code: String?): String = when (code?.toIntOrNull()) {
            in 1..9 -> "checking"
            in 10..29 -> "savings"
            in 30..49 -> "depot"
            in 50..59 -> "credit_card"
            else -> "checking"
        }

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
) : AbstractHBCICallback() {
    var chosenTanMethod: String? = connection.tanMethod
        private set
    val bankMessages = mutableListOf<String>()
    private var pin: String? = null

    /** Error-level log lines (level 1), used to explain failures. Higher levels may contain message dumps and are dropped. */
    val diagnostics = mutableListOf<String>()

    override fun log(msg: String?, level: Int, date: Date?, trace: StackTraceElement?) {
        if (level <= 1 && !msg.isNullOrBlank()) diagnostics += msg.take(500)
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
                val value = pin ?: interaction.pin(connection.bankName, connection.userId)?.takeIf { it.isNotEmpty() } ?: cancel()
                pin = value
                retData.set(value)
            }

            HBCICallback.NEED_PT_SECMECH -> retData.set(selectTanMethod(retData.toString()))

            HBCICallback.NEED_PT_TANMEDIA -> {
                val media = retData.toString().split('|').map { it.trim() }.filter { it.isNotEmpty() }
                val chosen = if (media.size <= 1) media.firstOrNull().orEmpty() else interaction.selectTanMedium(media) ?: cancel()
                retData.set(chosen)
            }

            HBCICallback.NEED_PT_TAN -> retData.set(interaction.tan(TanChallenge(message, flickerCode = flickerFor(retData.toString()))) ?: cancel())

            HBCICallback.NEED_PT_PHOTOTAN -> {
                val code = MatrixCode.tryParse(retData.toString())
                retData.set(interaction.tan(TanChallenge(message, code?.image, code?.mimetype)) ?: cancel())
            }

            HBCICallback.NEED_PT_QRTAN -> {
                val code = QRCode.tryParse(retData.toString(), message)
                retData.set(interaction.tan(TanChallenge(code?.message ?: message, code?.image, code?.mimetype)) ?: cancel())
            }

            HBCICallback.NEED_PT_DECOUPLED, HBCICallback.NEED_PT_DECOUPLED_RETRY ->
                if (!interaction.confirmDecoupled(message.ifEmpty { "Please confirm the order in your banking app." })) cancel()

            HBCICallback.HAVE_VOP_RESULT ->
                if (!interaction.confirmPayee(message.ifEmpty { retData.toString() })) cancel("Transfer cancelled after payee verification")

            HBCICallback.HAVE_INST_MSG -> interaction.info(message)

            HBCICallback.HAVE_ERROR -> {
                bankMessages += message
                retData.set("ERR") // abort instead of silently continuing past an error
            }

            HBCICallback.WRONG_PIN -> {
                pin = null
                bankMessages += "The bank rejected the PIN"
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

    private fun cancel(message: String = "Cancelled by user"): Nothing = throw BankingCancelledException(message)

    private fun StringBuffer.set(value: String) {
        setLength(0)
        append(value)
    }
}
