package de.findesk.data.entity

import de.findesk.data.table.AccountsTable
import de.findesk.data.table.BankAccessTable
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
    var serverUrl by BankAccessTable.serverUrl
    var lastSync by BankAccessTable.lastSync
    var syncFrom by BankAccessTable.syncFrom
    var capabilities by BankAccessTable.capabilities
    var includeInSyncAll by BankAccessTable.includeInSyncAll
    var offline by BankAccessTable.offline

    val accounts by Account referrersOn AccountsTable.bankAccessId
}
