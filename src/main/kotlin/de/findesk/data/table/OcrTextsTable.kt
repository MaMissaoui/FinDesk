package de.findesk.data.table

import org.jetbrains.exposed.dao.id.IntIdTable

object OcrTextsTable : IntIdTable("ocr_texts") {
    val documentId = reference("document_id", DocumentsTable).index()
    val content = text("content")
    val language = varchar("language", 10).default("de")
    val confidence = decimal("confidence", 5, 2).nullable()
}
