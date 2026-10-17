package de.findesk.viewmodel

import de.findesk.document.DocumentService
import de.findesk.i18n.AppStrings
import de.findesk.i18n.StringKey
import de.findesk.model.DocumentItem
import de.findesk.model.TransactionFilter
import de.findesk.model.TransactionItem
import de.findesk.repository.TransactionRepository
import de.findesk.repository.TransactionRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

enum class DocumentTab(val label: String) { ALL("All Documents"), UNLINKED("Unlinked"), LINKED("Linked to transaction") }

data class DocumentsState(
    val documents: List<DocumentItem> = emptyList(),
    val tab: DocumentTab = DocumentTab.ALL,
    val searchQuery: String = "",
    val selectedDocument: DocumentItem? = null,
    val selectedOcrText: String? = null,
    val suggestions: List<DocumentService.MatchSuggestion> = emptyList(),
    /** Transactions offered in the manual link picker. */
    val linkCandidates: List<TransactionItem> = emptyList(),
    val isOcrAvailable: Boolean = false,
    val isUploading: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

class DocumentsViewModel(
    private val documentService: DocumentService = DocumentService(),
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : BaseViewModel(scope) {
    private val _state = MutableStateFlow(DocumentsState(isOcrAvailable = documentService.isOcrAvailable))
    val state: StateFlow<DocumentsState> = _state.asStateFlow()

    private val onError: (Exception) -> Unit = { e ->
        _state.update { it.copy(isLoading = false, isUploading = false, error = e.userMessage()) }
    }
    private var searchJob: Job? = null

    fun loadDocuments(): Job {
        _state.update { it.copy(isLoading = true, error = null) }
        return launchSafely(onError) { refresh() }
    }

    private suspend fun refresh() {
        val s = _state.value
        val docs = documentService.listDocuments(
            query = s.searchQuery,
            unlinkedOnly = s.tab == DocumentTab.UNLINKED,
        ).let { list -> if (s.tab == DocumentTab.LINKED) list.filter { it.linkedTransactionId != null } else list }
        val selected = s.selectedDocument?.let { sel -> docs.firstOrNull { it.id == sel.id } }
        _state.update { it.copy(documents = docs, selectedDocument = selected, isLoading = false) }
    }

    fun selectTab(tab: DocumentTab): Job {
        _state.update { it.copy(tab = tab) }
        return loadDocuments()
    }

    fun searchDocuments(query: String): Job {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        return launchSafely(onError) {
            delay(TransactionsViewModel.SEARCH_DEBOUNCE_MS)
            refresh()
        }.also { searchJob = it }
    }

    fun uploadDocuments(files: List<File>, transactionId: Int? = null): Job {
        _state.update { it.copy(isUploading = true, error = null) }
        return launchSafely(onError) {
            val failures = mutableListOf<String>()
            var withText = 0
            files.forEach { file ->
                try {
                    val result = documentService.uploadDocument(file, transactionId)
                    if (result.ocr?.text?.isNotBlank() == true) withText++
                } catch (e: Exception) {
                    failures += "${file.name}: ${e.userMessage()}"
                }
            }
            refresh()
            val uploaded = files.size - failures.size
            _state.update {
                it.copy(
                    isUploading = false,
                    message = AppStrings.format(StringKey.MessageUploaded, uploaded, withText).takeIf { uploaded > 0 },
                    error = failures.takeIf { f -> f.isNotEmpty() }?.joinToString("\n"),
                )
            }
        }
    }

    /** Selects a document and loads its OCR text and matching suggestions. */
    fun selectDocument(document: DocumentItem?): Job {
        _state.update { it.copy(selectedDocument = document, selectedOcrText = null, suggestions = emptyList(), linkCandidates = emptyList()) }
        if (document == null) return Job().apply { complete() }
        return launchSafely(onError) {
            val text = documentService.getOcrText(document.id)
            val suggestions = documentService.suggestTransactions(document.id)
            val candidates = transactionRepository.findItems(TransactionFilter(), limit = 200)
            _state.update {
                if (it.selectedDocument?.id != document.id) {
                    it
                } else {
                    it.copy(selectedOcrText = text, suggestions = suggestions, linkCandidates = candidates)
                }
            }
        }
    }

    fun linkToTransaction(documentId: Int, transactionId: Int): Job = launchSafely(onError) {
        documentService.linkToTransaction(documentId, transactionId)
        refresh()
        _state.update { it.copy(message = AppStrings[StringKey.MessageDocumentLinked]) }
    }

    fun unlinkFromTransaction(documentId: Int): Job = launchSafely(onError) {
        documentService.unlinkFromTransaction(documentId)
        refresh()
    }

    fun reprocessOcr(documentId: Int): Job = launchSafely(onError) {
        val result = documentService.reprocessOcr(documentId)
        refresh()
        _state.value.selectedDocument?.takeIf { it.id == documentId }?.let { selectDocument(it) }
        _state.update {
            it.copy(message = if (result?.text?.isNotBlank() == true) AppStrings[StringKey.TextExtracted] else AppStrings[StringKey.MessageNoTextExtracted])
        }
    }

    fun deleteDocument(documentId: Int): Job = launchSafely(onError) {
        documentService.deleteDocument(documentId)
        if (_state.value.selectedDocument?.id == documentId) {
            _state.update { it.copy(selectedDocument = null, selectedOcrText = null, suggestions = emptyList()) }
        }
        refresh()
        _state.update { it.copy(message = AppStrings[StringKey.MessageDocumentDeleted]) }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, message = null) }
    }
}
