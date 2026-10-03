package de.findesk.viewmodel

import de.findesk.data.entity.Document
import de.findesk.document.DocumentService
import de.findesk.document.OcrService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class DocumentsState(
    val documents: List<Document> = emptyList(),
    val unlinkedDocuments: List<Document> = emptyList(),
    val searchQuery: String = "",
    val selectedDocument: Document? = null,
    val isUploading: Boolean = false,
    val isProcessingOcr: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class DocumentsViewModel(
    private val documentService: DocumentService = DocumentService(),
    private val ocrService: OcrService = OcrService(),
) {
    private val _state = MutableStateFlow(DocumentsState())
    val state: StateFlow<DocumentsState> = _state.asStateFlow()

    fun loadDocuments() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        try {
            val unlinked = emptyList<Document>() // TODO: Implement
            _state.value = _state.value.copy(
                unlinkedDocuments = unlinked,
                isLoading = false,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoading = false,
                error = e.message,
            )
        }
    }

    fun uploadDocument(file: File, transactionId: Int? = null) {
        _state.value = _state.value.copy(isUploading = true, error = null)
        try {
            // TODO: Implement document upload
            _state.value = _state.value.copy(isUploading = false)
            loadDocuments()
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isUploading = false,
                error = e.message,
            )
        }
    }

    fun linkToTransaction(documentId: Int, transactionId: Int) {
        try {
            // TODO: Implement linking
            loadDocuments()
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message)
        }
    }

    fun unlinkFromTransaction(documentId: Int) {
        try {
            // TODO: Implement unlinking
            loadDocuments()
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message)
        }
    }

    fun searchDocuments(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        // TODO: Implement search
    }

    fun selectDocument(document: Document?) {
        _state.value = _state.value.copy(selectedDocument = document)
    }

    fun deleteDocument(documentId: Int) {
        try {
            // TODO: Implement delete
            loadDocuments()
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.message)
        }
    }
}
