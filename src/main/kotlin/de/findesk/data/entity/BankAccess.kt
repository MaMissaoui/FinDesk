package de.findesk.data.entity

import de.findesk.data.table.BankAccessTable
import de.findesk.data.table.AccountsTable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID

class BankAccess(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<BankAccess>(BankAccessTable)

    var bankName by BankAccessTable.bankName
    var blz by BankAccessTable.blz
    var bic by BankAccessTable.bic
    var userId by BankAccessTable.userId
    var hbciVersion by BankAccessTable.hbciVersion
    var tanMethod by BankAccessTable.tanMethod
    var lastSync by BankAccessTable.lastSync

    val accounts by Account referrersOn AccountsTable.bankAccessId
}