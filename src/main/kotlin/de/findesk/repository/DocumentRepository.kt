package de.findesk.repository

import de.findesk.data.entity.Document
import de.findesk.data.entity.OcrText
import de.findesk.data.entity.Transaction
import de.findesk.data.table.DocumentsTable
import de.findesk.data.table.OcrTextsTable
import de.findesk.model.DocumentItem
import de.findesk.model.toItem
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDateTime

interface DocumentRepository {
    fun findAll(): List<Document>
    fun findById(id: Int): Document?
    fun findByTransaction(transactionId: Int): List<Document>
    fun findUnlinked(): List<Document>
    fun searchByFileName(fileName: String): List<Document>
    fun create(
        fileName: String,
        filePath: String,
        mimeType: String,
        ocrTextId: Int? = null,
        linkedTransactionId: Int? = null,
    ): Document
    fun linkToTransaction(id: Int, transactionId: Int): Document?
    fun unlinkFromTransaction(id: Int): Document?
    fun delete(id: Int): Boolean

    /** Newest-first snapshot. [query] matches file name or OCR text. */
    fun findItems(query: String? = null, unlinkedOnly: Boolean = false, transactionId: Int? = null): List<DocumentItem>

    /** Stores (or replaces) the OCR text for a document and links it via documents.ocr_text_id. */
    fun saveOcrText(documentId: Int, content: String, language: String, confidence: BigDecimal?): Int
    fun getOcrText(documentId: Int): String?
    fun findItem(id: Int): DocumentItem?
}

class DocumentRepositoryImpl : DocumentRepository {
    override fun findAll(): List<Document> = transaction {
        Document.all().toList()
    }

    override fun findById(id: Int): Document? = transaction {
        Document.findById(id)
    }

    override fun findByTransaction(transactionId: Int): List<Document> = transaction {
        Document.find { DocumentsTable.linkedTransactionId eq transactionId }.toList()
    }

    override fun findUnlinked(): List<Document> = transaction {
        Document.find { DocumentsTable.linkedTransactionId.isNull() }.toList()
    }

    override fun searchByFileName(fileName: String): List<Document> = transaction {
        Document.find { DocumentsTable.fileName like "%$fileName%" }.toList()
    }

    override fun create(
        fileName: String,
        filePath: String,
        mimeType: String,
        ocrTextId: Int?,
        linkedTransactionId: Int?,
    ): Document = transaction {
        Document.new {
            this.fileName = fileName
            this.filePath = filePath
            this.mimeType = mimeType
            this.uploadDate = LocalDateTime.now()
            this.ocrText = ocrTextId?.let { de.findesk.data.entity.OcrText.findById(it) }
            this.linkedTransaction = linkedTransactionId?.let { Transaction.findById(it) }
        }
    }

    override fun linkToTransaction(id: Int, transactionId: Int): Document? = transaction {
        Document.findById(id)?.apply {
            linkedTransaction = Transaction.findById(transactionId)
        }
    }

    override fun unlinkFromTransaction(id: Int): Document? = transaction {
        Document.findById(id)?.apply {
            linkedTransaction = null
        }
    }

    override fun delete(id: Int): Boolean = transaction {
        val document = Document.findById(id) ?: return@transaction false
        document.ocrText = null
        OcrText.find { OcrTextsTable.documentId eq id }.forEach { it.delete() }
        document.delete()
        true
    }

    override fun findItems(query: String?, unlinkedOnly: Boolean, transactionId: Int?): List<DocumentItem> = transaction {
        val q = query?.trim()?.takeIf { it.isNotEmpty() }
        val docs = Document.find {
            var op = if (q != null) {
                val pattern = "%$q%"
                (DocumentsTable.fileName like pattern) or
                    (DocumentsTable.id inSubQuery OcrTextsTable.select(OcrTextsTable.documentId).where { OcrTextsTable.content like pattern })
            } else {
                DocumentsTable.id.isNotNull()
            }
            if (unlinkedOnly) op = op and DocumentsTable.linkedTransactionId.isNull()
            if (transactionId != null) op = op and (DocumentsTable.linkedTransactionId eq transactionId)
            op
        }.orderBy(DocumentsTable.uploadDate to SortOrder.DESC, DocumentsTable.id to SortOrder.DESC).toList()

        val ids = docs.map { it.id }
        val ocrByDoc = if (ids.isEmpty()) {
            emptyMap()
        } else {
            OcrTextsTable.select(OcrTextsTable.documentId, OcrTextsTable.confidence)
                .where { OcrTextsTable.documentId inList ids }
                .associate { it[OcrTextsTable.documentId].value to it[OcrTextsTable.confidence] }
        }
        docs.map { it.toItem(hasOcr = it.id.value in ocrByDoc, ocrConfidence = ocrByDoc[it.id.value]) }
    }

    override fun saveOcrText(documentId: Int, content: String, language: String, confidence: BigDecimal?): Int = transaction {
        val document = Document.findById(documentId) ?: throw IllegalArgumentException("Document $documentId not found")
        OcrText.find { OcrTextsTable.documentId eq documentId }.forEach { it.delete() }
        val ocr = OcrText.new {
            this.document = document
            this.content = content
            this.language = language
            this.confidence = confidence
        }
        document.ocrText = ocr
        ocr.id.value
    }

    override fun getOcrText(documentId: Int): String? = transaction {
        OcrText.find { OcrTextsTable.documentId eq documentId }.firstOrNull()?.content
    }

    override fun findItem(id: Int): DocumentItem? = transaction {
        val doc = Document.findById(id) ?: return@transaction null
        val ocr = OcrText.find { OcrTextsTable.documentId eq id }.firstOrNull()
        doc.toItem(hasOcr = ocr != null, ocrConfidence = ocr?.confidence)
    }
}
