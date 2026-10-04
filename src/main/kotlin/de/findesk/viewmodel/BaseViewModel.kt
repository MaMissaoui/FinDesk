package de.findesk.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ViewModels run repository/service work on [scope] (IO by default) and publish immutable state
 * via StateFlow. Every action returns its [Job] so tests can `join()` it.
 */
abstract class BaseViewModel(
    protected val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {

    /** Launches [block], routing any exception to [onError] instead of crashing the scope. */
    protected fun launchSafely(onError: (Exception) -> Unit, block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }

    override fun close() {
        scope.cancel()
    }
}

internal fun Exception.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
