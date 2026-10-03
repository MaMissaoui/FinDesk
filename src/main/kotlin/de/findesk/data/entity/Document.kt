package de.findesk.data.entity

import de.findesk.data.table.DocumentsTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.time.LocalDateTime

class Document(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Document>(DocumentsTable)

    var fileName by DocumentsTable.fileName
    var filePath by DocumentsTable.filePath
    var mimeType by DocumentsTable.mimeType
    var uploadDate by DocumentsTable.uploadDate
    var ocrText by OcrText optionalReferencedOn DocumentsTable.ocrTextId
    var linkedTransaction by Transaction optionalReferencedOn DocumentsTable.linkedTransactionId
}