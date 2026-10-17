package de.findesk.ui

import de.findesk.ui.components.niceCeiling
import org.junit.jupiter.api.Assertions.assertEquals
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
}
