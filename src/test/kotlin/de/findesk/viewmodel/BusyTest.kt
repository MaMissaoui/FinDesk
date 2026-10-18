package de.findesk.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BusyTest {
    @AfterEach
    fun reset() {
        // Busy is process-wide; leave it idle for the next test
        repeat(10) { Busy.end() }
    }

    @Test
    fun `nested sections stay busy until the last one ends`() {
        Busy.begin()
        Busy.begin()
        Busy.end()
        assertTrue(Busy.active.value)
        Busy.end()
        assertFalse(Busy.active.value)
    }

    @Test
    fun `an unmatched end never goes below zero`() {
        Busy.end()
        Busy.end()
        Busy.begin()
        assertTrue(Busy.active.value, "one begin after extra ends is busy again")
        Busy.end()
        assertFalse(Busy.active.value)
    }

    @Test
    fun `track returns the result and ends the section`() = runBlocking {
        assertEquals(42, Busy.track { 42 })
        assertFalse(Busy.active.value)
    }

    @Test
    fun `track ends the section when the work fails`() {
        assertThrows<IllegalStateException> { runBlocking { Busy.track { error("bank refused") } } }
        assertFalse(Busy.active.value)
    }

    @Test
    fun `track ends the section when the work is cancelled`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = async {
            Busy.track {
                started.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        started.await()
        assertTrue(Busy.active.value)
        job.cancelAndJoin()
        assertFalse(Busy.active.value)
    }
}
