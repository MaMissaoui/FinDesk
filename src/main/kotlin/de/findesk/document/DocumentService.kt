package de.findesk.document

import de.findesk.data.entity.Document
import de.findesk.data.entity.OcrText
import de.findesk.data.entity.Transaction
import de.findesk.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/**
 * Service for document management and storage
 */
class DocumentService(
    private val documentRepository: DocumentRepository = DocumentRepositoryImpl(),
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val ocrService: OcrService = OcrService(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
) {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    companion object {
        private const val DOCUMENTS_DIR = "documents"
        private val SUPPORTED_TYPES = setOf(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/tiff",
        )
    }

    /**
     * Upload a new document
     */
    suspend fun uploadDocument(
        sourceFile: File,
        transactionId: Int? = null,
        performOcr: Boolean = true,
    ): Document? = withContext(Dispatchers.IO) {
        _isLoading.value = true
        _error.value = null

        try {
            // Validate file
            if (!sourceFile.exists()) {
                _error.value = "File does not exist"
                return@withContext null
            }

            val mimeType = detectMimeType(sourceFile)
            if (mimeType !in SUPPORTED_TYPES) {
                _error.value = "Unsupported file type: $mimeType"
                return@withContext null
            }

            // Copy file to documents directory
            val documentsDir = getDocumentsDirectory()
            val fileName = "${UUID.randomUUID()}_${sourceFile.name}"
            val destFile = File(documentsDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)

            // Create document record
            val document = documentRepository.create(
                fileName = sourceFile.name,
                filePath = destFile.absolutePath,
                mimeType = mimeType,
                linkedTransactionId = transactionId,
            )

            // Perform OCR if requested
            if (performOcr && (mimeType == "application/pdf" || mimeType.startsWith("image/"))) {
                val ocrResult = ocrService.processDocument(destFile)
                if (ocrResult != null) {
                    // Update document with OCR text
                    // Note: In a real implementation, we'd link the OcrText to the document
                    auditLogRepository.log("OCR_PROCESSED", "system", "OCR completed for: ${sourceFile.name}")
                }
            }

            auditLogRepository.log("DOCUMENT_UPLOADED", "system", "Uploaded: ${sourceFile.name}")
            document
        } catch (e: Exception) {
            _error.value = e.message
            null
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Link a document to a transaction
     */
    suspend fun linkToTransaction(documentId: Int, transactionId: Int): Boolean = try {
        documentRepository.linkToTransaction(documentId, transactionId)
        auditLogRepository.log("DOCUMENT_LINKED", "system", "Linked document $documentId to transaction $transactionId")
        true
    } catch (e: Exception) {
        _error.value = e.message
        false
    }

    /**
     * Unlink a document from a transaction
     */
    suspend fun unlinkFromTransaction(documentId: Int): Boolean = try {
        documentRepository.unlinkFromTransaction(documentId)
        auditLogRepository.log("DOCUMENT_UNLINKED", "system", "Unlinked document $documentId")
        true
    } catch (e: Exception) {
        _error.value = e.message
        false
    }

    /**
     * Search documents by filename or OCR text
     */
    suspend fun searchDocuments(query: String): List<Document> = withContext(Dispatchers.IO) {
        try {
            val byFileName = documentRepository.searchByFileName(query)
            // TODO: Also search OCR text
            byFileName
        } catch (e: Exception) {
            _error.value = e.message
            emptyList()
        }
    }

    /**
     * Get all unlinked documents
     */
    suspend fun getUnlinkedDocuments(): List<Document> = withContext(Dispatchers.IO) {
        documentRepository.findUnlinked()
    }

    /**
     * Get documents for a transaction
     */
    suspend fun getDocumentsForTransaction(transactionId: Int): List<Document> = withContext(Dispatchers.IO) {
        documentRepository.findByTransaction(transactionId)
    }

    /**
     * Delete a document
     */
    suspend fun deleteDocument(documentId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val document = documentRepository.findById(documentId)
            if (document != null) {
                // Delete file
                val file = File(document.filePath)
                if (file.exists()) {
                    file.delete()
                }
                // Delete record
                documentRepository.delete(documentId)
                auditLogRepository.log("DOCUMENT_DELETED", "system", "Deleted document: ${document.fileName}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            _error.value = e.message
            false
        }
    }

    /**
     * Get the documents storage directory
     */
    private fun getDocumentsDirectory(): File {
        val userHome = System.getProperty("user.home")
        val dir = File(userHome, ".findesk/$DOCUMENTS_DIR")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Detect MIME type from file extension
     */
    private fun detectMimeType(file: File): String = when (file.extension.lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "tiff", "tif" -> "image/tiff"
        else -> "application/octet-stream"
    }
}
