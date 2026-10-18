package de.findesk.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether work runs that must not be interrupted: a sync, a payment being sent, an import, a backup or restore, a
 * document upload or OCR. Switching the language recreates the current screen and closes its view model, so the
 * top bar's switchers stay disabled while this is active.
 */
object Busy {
    private val running = AtomicInteger(0)
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun begin() {
        _active.value = running.incrementAndGet() > 0
    }

    /** Never goes below zero, so an unmatched call can't leave the switchers enabled during later work. */
    fun end() {
        _active.value = running.updateAndGet { (it - 1).coerceAtLeast(0) } > 0
    }

    /** Runs [block] as busy; a failure or a cancellation still ends the busy section. */
    suspend fun <T> track(block: suspend () -> T): T {
        begin()
        try {
            return block()
        } finally {
            end()
        }
    }
}
