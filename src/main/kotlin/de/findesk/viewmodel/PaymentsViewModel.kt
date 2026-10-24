package de.findesk.viewmodel

import de.findesk.banking.BankCapabilities
import de.findesk.banking.BankDirectory
import de.findesk.banking.BankingResult
import de.findesk.banking.BankingService
import de.findesk.banking.StandingOrder
import de.findesk.banking.StandingOrderSchedule
import de.findesk.banking.TransferOrder
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.AccountItem
import de.findesk.model.PaymentOrderItem
import de.findesk.model.PaymentOrderStatus
import de.findesk.repository.*
import de.findesk.util.AppInfo
import de.findesk.util.Iban
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the user is about to send: a transfer now, on [date], or a standing order with [schedule]. */
data class PaymentReview(
    val account: AccountItem,
    val order: TransferOrder,
    val date: java.time.LocalDate? = null,
    val schedule: StandingOrderSchedule? = null,
    /** An earlier order to the same recipient and amount whose outcome is unknown or still open: maybe already paid. */
    val unconfirmed: PaymentOrderItem? = null,
)

data class PaymentsState(
    val accounts: List<AccountItem> = emptyList(),
    /** Order waiting for the user's explicit confirmation. */
    val review: PaymentReview? = null,
    /** Standing orders of [standingOrdersAccount] as last loaded from the bank; null until loaded. */
    val standingOrders: List<StandingOrder>? = null,
    val standingOrdersAccount: Int? = null,
    val isLoadingStandingOrders: Boolean = false,
    /** Standing order the user asked to delete, waiting for confirmation. */
    val deleteReview: StandingOrder? = null,
    /** Locally recorded orders sent to a bank, newest first, with the bank's answer. */
    val paymentOrders: List<PaymentOrderItem> = emptyList(),
    /** Known capabilities per bank access id; an absent entry means "unknown" (nothing is hidden). */
    val bankCapabilities: Map<Int, BankCapabilities> = emptyMap(),
    val isSending: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class PaymentsViewModel(
    private val accountRepository: AccountRepository = AccountRepositoryImpl(),
    private val bankingService: BankingService = BankingService(),
    private val paymentOrderRepository: PaymentOrderRepository = PaymentOrderRepositoryImpl(),
    private val bankAccessRepository: BankAccessRepository = BankAccessRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
    val bankingInteraction: UiBankingInteraction = UiBankingInteraction(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BaseViewModel(scope) {
    private val _state = MutableStateFlow(PaymentsState())
    val state: StateFlow<PaymentsState> = _state.asStateFlow()

    private val onError: (Exception) -> Unit = { e -> _state.update { it.copy(isSending = false, error = e.userMessage()) } }

    /** Set before anything is sent; a second click while a transfer is in flight is ignored. */
    private val sending = java.util.concurrent.atomic.AtomicBoolean(false)

    fun load(): Job = launchSafely(onError) { refresh() }

    private fun refresh() {
        // Orders left pending by an earlier run that stopped mid-send can't complete any more
        val interrupted = paymentOrderRepository.markInterrupted(AppInfo.startedAt)
        if (interrupted > 0) auditLogRepository.log("PAYMENT_ORDERS_INTERRUPTED", "system", "$interrupted order(s) left pending by an earlier run marked as outcome unknown")
        _state.update {
            // SEPA is EUR only; accounts of offline banks and card identifiers without an IBAN can never be a source
            val offlineBanks = bankAccessRepository.findAllItems().filter { it.offline }.map { it.id }.toSet()
            it.copy(
                accounts = accountRepository.findAllItems().filter { a ->
                    a.currency == "EUR" && a.bankAccessId !in offlineBanks && Iban.isValid(a.iban)
                },
                paymentOrders = paymentOrderRepository.findRecentItems(20),
                bankCapabilities = bankAccessRepository.findAllItems()
                    .mapNotNull { bank -> BankCapabilities.parse(bank.capabilities)?.let { caps -> bank.id to caps } }
                    .toMap(),
            )
        }
    }

    fun validate(order: TransferOrder): String? = bankingService.validateTransfer(order)

    /** BIC for a German recipient IBAN, if the bank directory knows it. */
    fun bicFor(iban: String): String? = BankDirectory.byIban(iban)?.bic?.takeIf { it.isNotBlank() }

    /** Step 1: validate and show the review. Nothing is sent. */
    fun review(from: AccountItem, order: TransferOrder, date: java.time.LocalDate? = null, schedule: StandingOrderSchedule? = null) {
        val error = validate(order)
            ?: date?.let(bankingService::validateExecutionDate)
            ?: schedule?.let(bankingService::validateSchedule)
        val unconfirmed = _state.value.paymentOrders.firstOrNull { o ->
            o.status in setOf(PaymentOrderStatus.UNKNOWN, PaymentOrderStatus.PENDING) &&
                o.recipientIban.equals(Iban.normalize(order.recipientIban), ignoreCase = true) &&
                o.amount?.compareTo(order.amount) == 0
        }
        _state.update { if (error != null) it.copy(error = error) else it.copy(review = PaymentReview(from, order, date, schedule, unconfirmed), error = null) }
    }

    fun cancelReview() {
        _state.update { it.copy(review = null) }
    }

    /** Step 2: the user confirmed the reviewed order. Sends it; the bank will ask for a TAN. */
    fun confirmAndSend(): Job {
        val review = _state.value.review ?: return Job().apply { complete() }
        val (from, order) = review
        if (!sending.compareAndSet(false, true)) return Job().apply { complete() }
        _state.update { it.copy(isSending = true, error = null, message = null) }
        return launchSafely({ e ->
            sending.set(false)
            onError(e)
        }) {
            val result = try {
                Busy.track {
                    when {
                        review.schedule != null -> bankingService.createStandingOrder(from.id, order, review.schedule, bankingInteraction, confirmed = true)
                        review.date != null -> bankingService.scheduleTransfer(from.id, order, review.date, bankingInteraction, confirmed = true)
                        else -> bankingService.initiateTransfer(from.id, order, bankingInteraction, confirmed = true)
                    }
                }
            } finally {
                sending.set(false)
            }
            refresh()
            val sent = when {
                review.schedule != null -> AppStrings.format(StringKey.MessageStandingOrderSetUp, order.recipientName)
                review.date != null -> AppStrings.format(StringKey.MessageTransferScheduled, order.recipientName, de.findesk.util.Formats.date(review.date))
                else -> AppStrings.format(StringKey.MessageTransferSent, order.recipientName)
            }
            // A new standing order makes the loaded list stale
            if (result is BankingResult.Success && review.schedule != null) _state.update { it.copy(standingOrders = null) }
            _state.update {
                when (result) {
                    is BankingResult.Success -> it.copy(isSending = false, review = null, message = sent)

                    // A definite rejection keeps the review open to correct and retry; an unknown outcome closes it, so
                    // sending again takes a fresh review that shows the unconfirmed order
                    is BankingResult.Error -> it.copy(
                        isSending = false,
                        review = if (result.code == "CANCELLED" || result.code == BankingService.OUTCOME_UNKNOWN) null else it.review,
                        error = if (result.code == "CANCELLED") AppStrings[StringKey.MessageTransferCancelled] else result.message,
                    )
                }
            }
        }
    }

    /** Asks the bank for the account's standing orders (needs the PIN). Read-only. */
    fun loadStandingOrders(account: AccountItem): Job {
        _state.update { it.copy(isLoadingStandingOrders = true, error = null, standingOrdersAccount = account.id) }
        return launchSafely({ e -> _state.update { it.copy(isLoadingStandingOrders = false, error = e.userMessage()) } }) {
            val result = bankingService.loadStandingOrders(account.id, bankingInteraction)
            _state.update {
                when (result) {
                    is BankingResult.Success -> it.copy(isLoadingStandingOrders = false, standingOrders = result.data)
                    is BankingResult.Error -> it.copy(isLoadingStandingOrders = false, error = if (result.code == "CANCELLED") null else result.message)
                }
            }
        }
    }

    fun requestDelete(order: StandingOrder) {
        _state.update { it.copy(deleteReview = order) }
    }

    fun cancelDelete() {
        _state.update { it.copy(deleteReview = null) }
    }

    /** The user confirmed deleting [PaymentsState.deleteReview]; this stops its future payments. */
    fun confirmDelete(): Job {
        val existing = _state.value.deleteReview ?: return Job().apply { complete() }
        val accountId = _state.value.standingOrdersAccount ?: return Job().apply { complete() }
        if (!sending.compareAndSet(false, true)) return Job().apply { complete() }
        _state.update { it.copy(isSending = true, error = null, message = null) }
        return launchSafely({ e ->
            sending.set(false)
            onError(e)
        }) {
            val result = try {
                bankingService.deleteStandingOrder(accountId, existing, bankingInteraction, confirmed = true)
            } finally {
                sending.set(false)
            }
            refresh()
            _state.update {
                when (result) {
                    is BankingResult.Success -> it.copy(
                        isSending = false,
                        deleteReview = null,
                        standingOrders = it.standingOrders?.minus(existing),
                        message = AppStrings.format(StringKey.MessageStandingOrderDeleted, existing.order.recipientName),
                    )

                    is BankingResult.Error -> it.copy(
                        isSending = false,
                        deleteReview = if (result.code == "CANCELLED" || result.code == BankingService.OUTCOME_UNKNOWN) null else it.deleteReview,
                        error = if (result.code == "CANCELLED") null else result.message,
                    )
                }
            }
        }
    }

    override fun close() {
        bankingInteraction.cancelPending()
        super.close()
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, message = null) }
    }
}
