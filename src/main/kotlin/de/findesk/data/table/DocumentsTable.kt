package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime

object DocumentsTable : IntIdTable("documents") {
    val fileName = varchar("file_name", 255)
    val filePath = varchar("file_path", 500)
    val mimeType = varchar("mime_type", 100)
    val uploadDate = datetime("upload_date")
    val ocrTextId = reference("ocr_text_id", OcrTextsTable).nullable()
    val linkedTransactionId = reference("linked_transaction_id", TransactionsTable).nullable().index()
}
