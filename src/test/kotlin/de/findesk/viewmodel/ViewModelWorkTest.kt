package de.findesk.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ViewModelWorkTest {
    private class TestViewModel(
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    ) : BaseViewModel(scope) {
        fun run(onError: (Exception) -> Unit = {}, block: suspend () -> Unit): Job = launchSafely(onError) { block() }
    }

    @Test
    fun `counts a job until it completes`() = runBlocking {
        val vm = TestViewModel()
        val gate = CompletableDeferred<Unit>()
        val baseline = ViewModelWork.active

        val job = vm.run { gate.await() }
        assertEquals(baseline + 1, ViewModelWork.active, "the waiting job is in flight")

        gate.complete(Unit)
        job.join()
        assertEquals(baseline, ViewModelWork.active)
        vm.close()
    }

    @Test
    fun `counts a failing job down and reports the error`() = runBlocking {
        val vm = TestViewModel()
        val baseline = ViewModelWork.active
        val failure = CompletableDeferred<Exception>()

        val job = vm.run(onError = { failure.complete(it) }) { error("boom") }
        val error = withTimeout(5_000) { failure.await() }
        assertEquals("boom", error.message)
        job.join()
        assertEquals(baseline, ViewModelWork.active)
        vm.close()
    }

    @Test
    fun `counts a job cancelled before it ran down`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.cancel()
        val vm = TestViewModel(scope)
        val baseline = ViewModelWork.active
        var ran = false

        val job = vm.run { ran = true }
        job.join()
        assertFalse(ran, "a cancelled job must not run its block")
        assertEquals(baseline, ViewModelWork.active)
        vm.close()
    }
}
