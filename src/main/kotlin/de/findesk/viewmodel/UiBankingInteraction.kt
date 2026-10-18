package de.findesk.viewmodel

import de.findesk.banking.BankingInteraction
import de.findesk.banking.PinEntry
import de.findesk.banking.TanChallenge
import de.findesk.banking.TanMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A question from the bank waiting for the user. Answer with [UiBankingInteraction.answer]. */
sealed interface BankingPrompt {
    data class Pin(val bankName: String, val userId: String, val offerRemember: Boolean = false) : BankingPrompt
    data class ChooseTanMethod(val options: List<TanMethod>) : BankingPrompt
    data class ChooseTanMedium(val options: List<String>) : BankingPrompt
    data class Tan(val challenge: TanChallenge) : BankingPrompt
    data class Decoupled(val message: String) : BankingPrompt
    data class ConfirmPayee(val message: String) : BankingPrompt
}

/**
 * Bridges blocking banking callbacks (on an IO thread) to Compose dialogs: each call publishes a
 * [BankingPrompt] and waits until the UI answers or cancels. The PIN travels through the answer future, never
 * through the [prompt] StateFlow, and the UI never keeps it.
 */
class UiBankingInteraction(private val timeoutMinutes: Long = 10) : BankingInteraction {
    private val _prompt = MutableStateFlow<BankingPrompt?>(null)
    val prompt: StateFlow<BankingPrompt?> = _prompt.asStateFlow()

    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()

    @Volatile
    private var pending: CompletableFuture<Any?>? = null

    @Volatile
    private var closed = false

    /** Answer the current prompt; null cancels. For yes/no prompts any non-null value means yes. */
    fun answer(value: String?) {
        pending?.complete(value)
    }

    /** Answer the PIN prompt with the entered [pin] and whether to [remember] it; a null [pin] cancels. */
    fun answerPin(pin: String?, remember: Boolean) {
        pending?.complete(pin?.let { PinEntry(it, remember) })
    }

    /**
     * Cancels the open prompt and all future ones, unblocking the banking thread. Called when the
     * owning screen closes; otherwise the thread would wait out the timeout while holding the FinTS lock.
     */
    fun cancelPending() {
        closed = true
        pending?.complete(null)
    }

    fun clearInfo() {
        _info.value = null
    }

    @Synchronized
    private fun ask(prompt: BankingPrompt): Any? {
        if (closed) return null
        val future = CompletableFuture<Any?>()
        pending = future
        if (closed) future.complete(null) // closed between the check and publishing the future
        _prompt.value = prompt
        return try {
            future.get(timeoutMinutes, TimeUnit.MINUTES)
        } catch (e: Exception) {
            null
        } finally {
            pending = null
            _prompt.value = null
        }
    }

    override fun pin(bankName: String, userId: String, offerRemember: Boolean): PinEntry? = ask(BankingPrompt.Pin(bankName, userId, offerRemember)) as? PinEntry

    override fun selectTanMethod(options: List<TanMethod>): String? = ask(BankingPrompt.ChooseTanMethod(options)) as? String

    override fun selectTanMedium(options: List<String>): String? = ask(BankingPrompt.ChooseTanMedium(options)) as? String

    override fun tan(challenge: TanChallenge): String? = ask(BankingPrompt.Tan(challenge)) as? String

    override fun confirmDecoupled(message: String): Boolean = ask(BankingPrompt.Decoupled(message)) != null

    override fun confirmPayee(message: String): Boolean = ask(BankingPrompt.ConfirmPayee(message)) != null

    override fun info(message: String) {
        if (message.isNotBlank()) _info.value = message
    }
}
