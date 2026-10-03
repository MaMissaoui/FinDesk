package de.findesk.repository

import de.findesk.data.entity.Document
import de.findesk.data.entity.Transaction
import de.findesk.data.table.DocumentsTable
import org.jetbrains.exposed.sql.transactions.transaction
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
        Document.findById(id)?.delete() != null
    }
}
