package de.findesk.viewmodel

import de.findesk.banking.BankDirectory
import de.findesk.banking.BankingResult
import de.findesk.banking.BankingService
import de.findesk.banking.TransferOrder
import de.findesk.model.AccountItem
import de.findesk.repository.*
import de.findesk.util.Iban
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PaymentsState(
    val accounts: List<AccountItem> = emptyList(),
    /** Order waiting for the user's explicit confirmation. */
    val review: Pair<AccountItem, TransferOrder>? = null,
    val recentTransfers: List<AuditEntry> = emptyList(),
    val isSending: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class PaymentsViewModel(
    private val accountRepository: AccountRepository = AccountRepositoryImpl(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
    private val bankingService: BankingService = BankingService(),
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
        _state.update {
            it.copy(
                accounts = accountRepository.findAllItems().filter { it.currency == "EUR" }, // SEPA is EUR only
                recentTransfers = auditLogRepository.findByAction("TRANSFER").sortedByDescending { e -> e.timestamp }.take(20)
                    .map { e -> AuditEntry(e.timestamp, e.action, e.details) },
            )
        }
    }

    fun validate(order: TransferOrder): String? = bankingService.validateTransfer(order)

    /** BIC for a German recipient IBAN (BLZ = characters 5-12), if known. */
    fun bicFor(iban: String): String? {
        val n = Iban.normalize(iban)
        return if (n.startsWith("DE") && n.length == 22) BankDirectory.lookup(n.substring(4, 12))?.bic?.takeIf { it.isNotBlank() } else null
    }

    /** Step 1: validate and show the review. Nothing is sent. */
    fun review(from: AccountItem, order: TransferOrder) {
        val error = validate(order)
        _state.update { if (error != null) it.copy(error = error) else it.copy(review = from to order, error = null) }
    }

    fun cancelReview() {
        _state.update { it.copy(review = null) }
    }

    /** Step 2: the user confirmed the reviewed order. Sends it; the bank will ask for a TAN. */
    fun confirmAndSend(): Job {
        val (from, order) = _state.value.review ?: return Job().apply { complete() }
        if (!sending.compareAndSet(false, true)) return Job().apply { complete() }
        _state.update { it.copy(isSending = true, error = null, message = null) }
        return launchSafely({ e ->
            sending.set(false)
            onError(e)
        }) {
            val result = try {
                bankingService.initiateTransfer(from.id, order, bankingInteraction, confirmed = true)
            } finally {
                sending.set(false)
            }
            refresh()
            _state.update {
                when (result) {
                    is BankingResult.Success -> it.copy(isSending = false, review = null, message = "Transfer to ${order.recipientName} sent")
                    is BankingResult.Error -> it.copy(isSending = false, review = if (result.code == "CANCELLED") null else it.review, error = if (result.code == "CANCELLED") "Transfer cancelled" else result.message)
                    is BankingResult.TanRequired -> it.copy(isSending = false, error = "TAN required")
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
