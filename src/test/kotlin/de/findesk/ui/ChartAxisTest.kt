package de.findesk.ui

import de.findesk.ui.components.nearestIndex
import de.findesk.ui.components.niceCeiling
import de.findesk.ui.components.niceTicks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChartAxisTest {
    @Test
    fun `the axis maximum rounds up to 1, 2, 2_5 or 5 times a power of ten`() {
        val cases = mapOf(3200.0 to 5000.0, 2000.0 to 2000.0, 2001.0 to 2500.0, 1150.0 to 2000.0, 99.0 to 100.0, 0.4 to 0.5, 7.0 to 10.0)
        cases.forEach { (value, expected) -> assertEquals(expected, niceCeiling(value), 1e-9, "$value") }
    }

    @Test
    fun `an empty chart still gets a positive scale`() {
        assertEquals(1.0, niceCeiling(0.0))
    }

    @Test
    fun `balance ticks cover a narrow range in three round steps`() {
        assertEquals(listOf(2300.0, 2400.0, 2500.0), niceTicks(2300.0, 2500.0))
        assertEquals(listOf(0.0, 2000.0, 4000.0), niceTicks(0.0, 3200.0))
        assertTicks(2300.0, 2500.0, niceTicks(2300.0, 2500.0), expectedCounts = 3..4)
        assertTicks(0.0, 3200.0, niceTicks(0.0, 3200.0), expectedCounts = 3..4)
    }

    @Test
    fun `ticks cross zero and keep the zero line when the range has both signs`() {
        val ticks = niceTicks(-150.0, 420.0)
        assertTrue(ticks.first() <= -150.0, "$ticks")
        assertTrue(ticks.last() >= 420.0, "$ticks")
        assertTrue(ticks.any { it == 0.0 }, "$ticks")
        assertTicks(-150.0, 420.0, ticks, expectedCounts = 3..5)
    }

    @Test
    fun `a flat range is widened and stays ascending`() {
        val ticks = niceTicks(5.0, 5.0)
        assertTrue(ticks.first() < 5.0, "$ticks")
        assertTrue(ticks.last() > 5.0, "$ticks")
        assertTrue(ticks.zipWithNext().all { (a, b) -> b > a }, "$ticks")
        assertTicks(5.0, 5.0, ticks, expectedCounts = 3..5)
    }

    @Test
    fun `ticks are evenly spaced multiples of one round step`() {
        val ticks = niceTicks(115.0, 987.0)
        assertTicks(115.0, 987.0, ticks, expectedCounts = 3..5)
        val step = ticks[1] - ticks[0]
        assertTrue(ticks.all { tick -> Math.abs(tick / step - Math.round(tick / step)) < 1e-9 }, "$ticks")
    }

    @Test
    fun `the nearest point follows the pointer across the plot`() {
        assertEquals(0, nearestIndex(0f, 100f, 5))
        assertEquals(4, nearestIndex(100f, 100f, 5))
        assertEquals(2, nearestIndex(50f, 100f, 5))
    }

    @Test
    fun `the nearest point is clamped outside the plot and count one has one point`() {
        assertEquals(0, nearestIndex(-25f, 100f, 4))
        assertEquals(3, nearestIndex(125f, 100f, 4))
        assertEquals(0, nearestIndex(50f, 100f, 1))
    }

    private fun assertTicks(min: Double, max: Double, ticks: List<Double>, expectedCounts: IntRange) {
        assertTrue(ticks.size in expectedCounts, "$ticks")
        assertTrue(ticks.first() <= min, "$ticks")
        assertTrue(ticks.last() >= max, "$ticks")
        val step = ticks[1] - ticks[0]
        assertTrue(step > 0.0, "$ticks")
        ticks.zipWithNext().forEach { (a, b) -> assertEquals(step, b - a, 1e-9, "$ticks") }
    }
}
