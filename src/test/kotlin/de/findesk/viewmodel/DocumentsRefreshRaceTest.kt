package de.findesk.viewmodel

import de.findesk.TestData
import de.findesk.TestDatabase
import de.findesk.document.DocumentService
import de.findesk.document.OcrService
import de.findesk.model.DocumentItem
import de.findesk.repository.DocumentRepository
import de.findesk.repository.DocumentRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A slow, older refresh must never overwrite the tab and list of a newer one. */
class DocumentsRefreshRaceTest {
    @TempDir
    lateinit var tmp: File

    private lateinit var data: TestData

    @BeforeEach
    fun setup() {
        TestDatabase.init()
        data = TestData()
    }

    /** Delegates to the real repository and blocks the very first `findItems` until released. */
    private class BlockingDocumentRepository(
        private val delegate: DocumentRepository,
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : DocumentRepository by delegate {
        @Volatile
        private var blocked = false

        override fun findItems(query: String?, unlinkedOnly: Boolean, transactionId: Int?): List<DocumentItem> {
            if (!blocked) {
                blocked = true
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            return delegate.findItems(query = query, unlinkedOnly = unlinkedOnly, transactionId = transactionId)
        }
    }

    @Test
    fun `the newer tab and its list win when an older refresh finishes last`() = runBlocking {
        val account = data.account()
        val txn = data.txn(account, LocalDate.now(), "-10.00", partner = "Shop")
        data.documentRepo.create("unlinked.pdf", "documents/unlinked.pdf", "application/pdf")
        data.documentRepo.create("linked.pdf", "documents/linked.pdf", "application/pdf", linkedTransactionId = txn)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val repository = BlockingDocumentRepository(DocumentRepositoryImpl(), entered, release)
        val service = DocumentService(documentRepository = repository, ocrService = OcrService(engine = null), documentsDir = File(tmp, "documents"))

        val vm = DocumentsViewModel(documentService = service)
        vm.loadDocuments().join()

        val first = vm.selectTab(DocumentTab.UNLINKED)
        entered.await(5, TimeUnit.SECONDS)
        vm.selectTab(DocumentTab.LINKED).join()

        release.countDown()
        first.join()

        assertEquals(DocumentTab.LINKED, vm.state.value.tab)
        assertEquals(listOf("linked.pdf"), vm.state.value.documents.map { it.fileName })
        assertEquals(mapOf(DocumentTab.ALL to 2, DocumentTab.UNLINKED to 1, DocumentTab.LINKED to 1), vm.state.value.counts)
        vm.close()
    }
}
