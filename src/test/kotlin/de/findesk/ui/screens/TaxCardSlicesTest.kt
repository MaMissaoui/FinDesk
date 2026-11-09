package de.findesk.ui.screens

import de.findesk.document.ReportService
import de.findesk.viewmodel.ChartSlice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** The tax card feeds [CategorySplit] the same slices the dashboard computed: names, colours and amounts stay. */
class TaxCardSlicesTest {
    @Test
    fun `tax slices keep every category's name, colour, amount and id`() {
        val slices = listOf(
            ChartSlice("Donations", BigDecimal("100.00"), "#E76530", categoryId = 7),
            ChartSlice(ReportService.UNCATEGORIZED, BigDecimal("12.50"), null, categoryId = null),
        )

        val shares = taxCategoryShares(slices)

        assertEquals(listOf<Int?>(7, null), shares.map { it.categoryId })
        assertEquals(listOf("Donations", ReportService.UNCATEGORIZED), shares.map { it.name })
        assertEquals(listOf(BigDecimal("100.00"), BigDecimal("12.50")), shares.map { it.amount })
        assertEquals("#E76530", shares.first().color)
        assertEquals("", shares.last().color, "the split draws uncategorized in its own neutral colour")
    }
}
