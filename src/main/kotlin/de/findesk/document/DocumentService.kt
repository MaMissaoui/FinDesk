package de.findesk.document

import de.findesk.model.DocumentItem
import de.findesk.model.TransactionFilter
import de.findesk.model.TransactionItem
import de.findesk.repository.*
import de.findesk.util.FileSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs

/**
 * Service for document management and storage
 */
class DocumentService(
    private val documentRepository: DocumentRepository = DocumentRepositoryImpl(),
    private val transactionRepository: TransactionRepository = TransactionRepositoryImpl(),
    private val ocrService: OcrService = OcrService(),
    private val auditLogRepository: AuditLogRepository = AuditLogRepositoryImpl(),
    private val documentsDir: File = defaultDocumentsDirectory(),
) {
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val isOcrAvailable: Boolean get() = ocrService.isOcrAvailable

    data class UploadResult(
        val document: DocumentItem,
        val ocr: OcrService.OcrResult?,
    )

    /** A transaction that plausibly matches a receipt, with a 0..1 score. */
    data class MatchSuggestion(
        val transaction: TransactionItem,
        val score: Double,
    )

    companion object {
        private const val MATCH_WINDOW_DAYS = 14L
        val SUPPORTED_EXTENSIONS = setOf("pdf", "jpg", "jpeg", "png", "tif", "tiff")

        fun defaultDocumentsDirectory(): File = de.findesk.util.AppPaths.documentsDir

        fun detectMimeType(file: File): String = when (file.extension.lowercase()) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "tiff", "tif" -> "image/tiff"
            else -> "application/octet-stream"
        }

        /**
         * Makes a user-supplied file name safe to use on disk: keeps the last path segment, drops control
         * characters and bounds the length. The display name is stored separately and unchanged.
         */
        fun sanitizeFileName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            val cleaned = base.filter { !it.isISOControl() }.trim()
            return cleaned.ifBlank { "document" }.take(150)
        }
    }

    /**
     * Upload a new document: copy into the documents directory, record it, and extract text.
     * Throws [IllegalArgumentException] for missing or unsupported files.
     */
    suspend fun uploadDocument(
        sourceFile: File,
        transactionId: Int? = null,
        performOcr: Boolean = true,
    ): UploadResult = withContext(Dispatchers.IO) {
        _isLoading.value = true
        _error.value = null
        try {
            require(sourceFile.isFile) { de.findesk.i18n.AppStrings.format(de.findesk.i18n.StringKey.ErrorFileDoesNotExist, sourceFile.name) }
            require(sourceFile.length() <= DocumentLimits.MAX_DOCUMENT_BYTES) {
                de.findesk.i18n.AppStrings.format(
                    de.findesk.i18n.StringKey.ErrorDocumentTooLarge,
                    sourceFile.name,
                    DocumentLimits.MAX_DOCUMENT_BYTES / (1024 * 1024),
                )
            }
            val mimeType = detectMimeType(sourceFile)
            require(mimeType != "application/octet-stream") { de.findesk.i18n.AppStrings.format(de.findesk.i18n.StringKey.ErrorUnsupportedFileType, sourceFile.extension) }

            FileSecurity.secureDirectory(documentsDir)
            val destFile = File(documentsDir, "${UUID.randomUUID()}_${sanitizeFileName(sourceFile.name)}")
            sourceFile.copyTo(destFile)
            FileSecurity.secureFile(destFile)

            val document = documentRepository.create(
                fileName = sourceFile.name,
                filePath = destFile.absolutePath,
                mimeType = mimeType,
                linkedTransactionId = transactionId,
            )
            val documentId = document.id.value

            val ocr = if (performOcr) ocrService.processDocument(destFile) else null
            if (ocr != null && ocr.text.isNotBlank()) {
                documentRepository.saveOcrText(documentId, ocr.text, ocr.language, ocr.confidence)
                auditLogRepository.log("OCR_PROCESSED", "system", "Text extracted (${ocr.source}) for: ${sourceFile.name}")
            }

            auditLogRepository.log("DOCUMENT_UPLOADED", "user", "Uploaded: ${sourceFile.name}")
            UploadResult(documentRepository.findItem(documentId)!!, ocr)
        } catch (e: Exception) {
            _error.value = e.message
            throw e
        } finally {
            _isLoading.value = false
        }
    }

    /** Re-runs text extraction for an existing document (e.g. after installing Tesseract). */
    suspend fun reprocessOcr(documentId: Int): OcrService.OcrResult? = withContext(Dispatchers.IO) {
        val doc = documentRepository.findItem(documentId) ?: return@withContext null
        val result = ocrService.processDocument(File(doc.filePath))
        if (result != null && result.text.isNotBlank()) {
            documentRepository.saveOcrText(documentId, result.text, result.language, result.confidence)
        }
        result
    }

    suspend fun linkToTransaction(documentId: Int, transactionId: Int): Boolean = withContext(Dispatchers.IO) {
        val linked = documentRepository.linkToTransaction(documentId, transactionId) != null
        if (linked) auditLogRepository.log("DOCUMENT_LINKED", "user", "Linked document $documentId to transaction $transactionId")
        linked
    }

    suspend fun unlinkFromTransaction(documentId: Int): Boolean = withContext(Dispatchers.IO) {
        val unlinked = documentRepository.unlinkFromTransaction(documentId) != null
        if (unlinked) auditLogRepository.log("DOCUMENT_UNLINKED", "user", "Unlinked document $documentId")
        unlinked
    }

    /** Search by file name or OCR text. */
    suspend fun searchDocuments(query: String): List<DocumentItem> = withContext(Dispatchers.IO) {
        documentRepository.findItems(query = query)
    }

    suspend fun listDocuments(query: String? = null, unlinkedOnly: Boolean = false): List<DocumentItem> = withContext(Dispatchers.IO) { documentRepository.findItems(query = query, unlinkedOnly = unlinkedOnly) }

    suspend fun getUnlinkedDocuments(): List<DocumentItem> = withContext(Dispatchers.IO) {
        documentRepository.findItems(unlinkedOnly = true)
    }

    suspend fun getDocumentsForTransaction(transactionId: Int): List<DocumentItem> = withContext(Dispatchers.IO) {
        documentRepository.findItems(transactionId = transactionId)
    }

    suspend fun getOcrText(documentId: Int): String? = withContext(Dispatchers.IO) {
        documentRepository.getOcrText(documentId)
    }

    /**
     * Suggests transactions for a document based on the total and date found in its text:
     * an expense with the same absolute amount scores highest, closer dates score higher.
     */
    suspend fun suggestTransactions(documentId: Int, limit: Int = 5): List<MatchSuggestion> = withContext(Dispatchers.IO) {
        val text = documentRepository.getOcrText(documentId) ?: return@withContext emptyList()
        val fields = ReceiptParser.parse(text)
        if (fields.amounts.isEmpty()) return@withContext emptyList()

        val anchor = fields.date
        val candidates = transactionRepository.findItems(
            TransactionFilter(
                startDate = anchor?.minusDays(MATCH_WINDOW_DAYS),
                endDate = anchor?.plusDays(MATCH_WINDOW_DAYS),
            ),
        )
        candidates.mapNotNull { txn ->
            val amount = txn.amount.abs()
            val amountScore = when {
                fields.total != null && amount.compareTo(fields.total) == 0 -> 1.0
                fields.amounts.any { it.compareTo(amount) == 0 } -> 0.6
                else -> return@mapNotNull null
            }
            val dateScore = anchor?.let {
                val days = abs(ChronoUnit.DAYS.between(it, txn.date)).toDouble()
                1.0 - (days / (MATCH_WINDOW_DAYS + 1))
            } ?: 0.5
            MatchSuggestion(txn, amountScore * 0.7 + dateScore * 0.3)
        }.sortedByDescending { it.score }.take(limit)
    }

    /**
     * Delete a document record and its stored file (only files inside the documents directory are deleted).
     */
    suspend fun deleteDocument(documentId: Int): Boolean = withContext(Dispatchers.IO) {
        val document = documentRepository.findItem(documentId) ?: return@withContext false
        val file = File(document.filePath)
        if (file.exists() && file.canonicalFile.startsWith(documentsDir.canonicalFile)) {
            file.delete()
        }
        documentRepository.delete(documentId)
        auditLogRepository.log("DOCUMENT_DELETED", "user", "Deleted document: ${document.fileName}")
        true
    }
}
