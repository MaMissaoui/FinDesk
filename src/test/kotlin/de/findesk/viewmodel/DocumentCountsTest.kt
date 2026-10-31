package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The document filter counts follow the same search as the shown list. */
class DocumentCountsTest {
    @Test
    fun `refresh counts every filter for the current search`() = runBlocking {
        TestDatabase.init()
        val data = TestData()
        val account = data.account()
        val txn = data.txn(account, LocalDate.now(), "-10.00", partner = "Shop")
        data.documentRepo.create("alpha.pdf", "documents/alpha.pdf", "application/pdf")
        data.documentRepo.create("beta.pdf", "documents/beta.pdf", "application/pdf")
        data.documentRepo.create("linked.pdf", "documents/linked.pdf", "application/pdf", linkedTransactionId = txn)

        val vm = DocumentsViewModel()
        vm.loadDocuments().join()

        assertEquals(
            mapOf(DocumentTab.ALL to 3, DocumentTab.UNLINKED to 2, DocumentTab.LINKED to 1),
            vm.state.value.counts,
        )
        assertEquals(3, vm.state.value.documents.size, "the ALL list still shows every document")

        vm.searchDocuments("beta").join()

        assertEquals(
            mapOf(DocumentTab.ALL to 1, DocumentTab.UNLINKED to 1, DocumentTab.LINKED to 0),
            vm.state.value.counts,
        )
        assertEquals(listOf("beta.pdf"), vm.state.value.documents.map { it.fileName })
        vm.close()
    }
}
