package de.findesk.data.entity

import de.findesk.data.table.OcrTextsTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.math.BigDecimal

class OcrText(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<OcrText>(OcrTextsTable)

    var document by Document referencedOn OcrTextsTable.documentId
    var content by OcrTextsTable.content
    var language by OcrTextsTable.language
    var confidence by OcrTextsTable.confidence
}