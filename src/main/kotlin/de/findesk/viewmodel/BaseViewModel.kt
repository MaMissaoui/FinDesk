package de.findesk.viewmodel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts [launchSafely] jobs that have not completed, across all view models, so the headless
 * screenshot harness can wait for loads to finish; the app never reads it.
 */
internal object ViewModelWork {
    private val count = AtomicInteger()

    val active: Int get() = count.get()

    fun started() {
        count.incrementAndGet()
    }

    fun completed() {
        count.decrementAndGet()
    }
}

/**
 * ViewModels run repository/service work on [scope] (IO by default) and publish immutable state
 * via StateFlow. Every action returns its [Job] so tests can `join()` it.
 */
abstract class BaseViewModel(
    protected val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {

    /** Launches [block], routing any exception to [onError] instead of crashing the scope. */
    protected fun launchSafely(onError: (Exception) -> Unit, block: suspend CoroutineScope.() -> Unit): Job {
        // Count synchronously, before launch: a job that has not started yet is still in flight.
        ViewModelWork.started()
        val job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
        // Runs on success, failure and cancellation (even before the job started), so the count cannot leak.
        job.invokeOnCompletion { ViewModelWork.completed() }
        return job
    }

    override fun close() {
        scope.cancel()
    }
}

internal fun Exception.userMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
