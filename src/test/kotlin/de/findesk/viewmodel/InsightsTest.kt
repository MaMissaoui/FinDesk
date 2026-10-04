package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.document.DocumentPreview
import de.findesk.document.ReportService
import de.findesk.model.TransactionItem
import de.findesk.util.BalanceHistory
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import javax.imageio.ImageIO

class InsightsTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData
    private val today = LocalDate.of(2026, 3, 15)
    private var account = 0
    private var food = 0

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
        account = data.account(balance = "1000.00")
        food = data.category("Food")
        data.categoryRepo.setBudget(food, BigDecimal("100.00"))
    }

    @Test
    fun `budgets compare this month's spending with the limit`() = runBlocking {
        val leisure = data.category("Fun")
        data.categoryRepo.setBudget(leisure, BigDecimal("50.00"))
        data.txn(account, LocalDate.of(2026, 3, 2), "-120.00", partner = "REWE", categoryId = food)
        data.txn(account, LocalDate.of(2026, 2, 20), "-500.00", partner = "Last month", categoryId = food)
        data.txn(account, LocalDate.of(2026, 3, 3), "-10.00", partner = "Kino", categoryId = leisure)

        val vm = DashboardViewModel(today = { today })
        vm.loadDashboard().join()
        val budgets = vm.state.value.budgets

        assertEquals(listOf("Food", "Fun"), budgets.map { it.categoryName }, "most used first")
        val foodBudget = budgets.first()
        assertEquals(BigDecimal("120.00"), foodBudget.spent, "only this month counts")
        assertTrue(foodBudget.isOver)
        assertEquals(BigDecimal("40.00"), budgets[1].remaining)
        assertEquals(15f / 31f, vm.state.value.monthProgress, 0.001f)
        vm.close()
    }

    @Test
    fun `a budget can be removed again`() = runBlocking {
        val vm = SettingsViewModel(dataDirectory = tmp.absolutePath)
        vm.load().join()
        vm.saveCategory(food, "Food", "#FF5722", null, false).join()
        assertNull(data.categoryRepo.findAllItems().first { it.id == food }.budgetLimit)
        vm.close()
    }

    private fun item(date: LocalDate, amount: String) = TransactionItem(
        id = 0, accountId = 1, accountIban = "", date = date, amount = BigDecimal(amount), currency = "EUR", partnerName = null,
        purpose = null, categoryId = null, categoryName = null, categoryColor = null, isTaxRelevant = false, documentCount = 0,
    )

    @Test
    fun `balance history is rewound from the current balance`() {
        val txns = listOf(
            item(LocalDate.of(2026, 3, 14), "-100.00"),
            item(LocalDate.of(2026, 3, 12), "500.00"),
            item(LocalDate.of(2026, 3, 16), "-50.00"), // after the window: undone first
        )
        val history = BalanceHistory.compute(BigDecimal("950.00"), txns, LocalDate.of(2026, 3, 11), LocalDate.of(2026, 3, 15))

        assertEquals(5, history.size)
        assertEquals(LocalDate.of(2026, 3, 11) to BigDecimal("600.00"), history.first())
        assertEquals(BigDecimal("1100.00"), history[1].second, "after +500 on the 12th")
        assertEquals(BigDecimal("1000.00"), history.last().second, "end of the 15th, before the 16th's booking")
    }

    @Test
    fun `account detail shows history and period totals`() = runBlocking {
        data.txn(account, today.minusDays(5), "-200.00", partner = "Miete")
        data.txn(account, today.minusDays(40), "3000.00", partner = "Gehalt")
        val vm = AccountsViewModel(today = { today })
        vm.loadAccounts().join()

        vm.openDetail(account, days = 30).join()
        val d = vm.state.value.detail!!
        assertEquals(30, d.history.size)
        assertEquals(BigDecimal("1000.00"), d.history.last().second)
        assertEquals(BigDecimal("1200.00"), d.history.first().second)
        assertEquals(BigDecimal("200.00"), d.expensesInPeriod)
        assertEquals(BigDecimal.ZERO, d.incomeInPeriod)
        assertEquals(listOf("Miete"), d.recent.map { it.partnerName })
        vm.close()
    }

    @Test
    fun `custom range reports span years and ignore late year results`() = runBlocking {
        data.txn(account, LocalDate.of(2025, 12, 20), "-30.00", partner = "Dec")
        data.txn(account, LocalDate.of(2026, 1, 10), "-20.00", partner = "Jan")
        data.txn(account, LocalDate.of(2026, 2, 10), "-99.00", partner = "Outside")

        val report = ReportService().generateRangeReport(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 1, 31))
        assertEquals(listOf(2025 to 12, 2026 to 1), report.monthlyBreakdown.map { it.year to it.month })
        assertEquals(BigDecimal("50.00"), report.totalExpenses)
        assertFalse(report.isCalendarYear)
        assertEquals("01.12.2025 – 31.01.2026", report.title)

        val vm = ReportsViewModel()
        vm.loadTaxReport(2026) // not joined: may finish after the range request
        vm.setCustomRange(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 1, 31)).join()
        Thread.sleep(200)
        assertEquals(BigDecimal("50.00"), vm.state.value.taxReport!!.totalExpenses, "a late year result must not replace the range")
        vm.close()
    }

    @Test
    fun `previews for pdf and images, null for missing files`() {
        val pdf = File(tmp, "a.pdf")
        PDDocument().use {
            it.addPage(PDPage())
            it.save(pdf)
        }
        val png = File(tmp, "big.png").also { ImageIO.write(BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB), "png", it) }

        val pdfPreview = DocumentPreview.render(pdf, maxWidth = 300)!!
        assertTrue(pdfPreview.width in 290..300, "width ${pdfPreview.width}")
        val pngPreview = DocumentPreview.render(png, maxWidth = 300)!!
        assertEquals(300, pngPreview.width)
        assertEquals(150, pngPreview.height)
        assertNull(DocumentPreview.render(File(tmp, "missing.pdf")))
        assertNull(DocumentPreview.render(File(tmp, "broken.png").apply { writeText("not an image") }))
    }
}
