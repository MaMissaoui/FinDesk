package de.findesk

import de.findesk.banking.FinTSClient
import de.findesk.repository.*
import java.math.BigDecimal
import java.time.LocalDate

/** Small fixture builder on top of the real repositories. Call after [TestDatabase.init]. */
class TestData {
    val bankAccessRepo = BankAccessRepositoryImpl()
    val accountRepo = AccountRepositoryImpl()
    val categoryRepo = CategoryRepositoryImpl()
    val transactionRepo = TransactionRepositoryImpl()
    val ruleRepo = RuleRepositoryImpl()
    val documentRepo = DocumentRepositoryImpl()
    private var counter = 0

    fun account(iban: String = "DE89370400440532013000", balance: String = "1000.00", currency: String = "EUR"): Int {
        val bank = bankAccessRepo.create("Test Bank", "37040044", "COBADEFFXXX", "user1")
        return accountRepo.create(bank.id.value, iban, balance = BigDecimal(balance), currency = currency).id.value
    }

    fun category(name: String, taxRelevant: Boolean = false, color: String = "#FF5722"): Int = categoryRepo.create(name, color, isTaxRelevant = taxRelevant).id.value

    fun txn(
        accountId: Int,
        date: LocalDate,
        amount: String,
        partner: String? = null,
        purpose: String? = null,
        categoryId: Int? = null,
        currency: String = "EUR",
    ): Int {
        val value = BigDecimal(amount)
        return transactionRepo.create(
            accountId = accountId,
            date = date,
            amount = value,
            currency = currency,
            partnerName = partner,
            purpose = purpose,
            categoryId = categoryId,
            hash = FinTSClient.transactionHash(date, value, partner, "${purpose.orEmpty()}#${counter++}"),
        ).id.value
    }
}
