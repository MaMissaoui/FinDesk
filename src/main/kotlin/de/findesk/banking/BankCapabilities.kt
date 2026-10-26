package de.findesk.banking

/**
 * The business transactions a bank login offers, detected from the bank's BPD/UPD during a dialog and kept per
 * bank access. FinDesk offers only what the login can actually do instead of failing at the bank. An unknown set
 * (never synced, or a login that reports nothing) is treated optimistically: the operation is attempted and the
 * bank decides.
 */
data class BankCapabilities(
    /** BPD/UPD job names the login supports, limited to [PROBED]. */
    val jobs: Set<String> = emptySet(),
) {
    fun supports(job: String): Boolean = job in jobs

    val supportsMt940: Boolean get() = supports(JOB_STATEMENTS_MT940)
    val supportsCamt: Boolean get() = supports(JOB_STATEMENTS_CAMT)
    val supportsStatements: Boolean get() = supportsMt940 || supportsCamt
    val supportsBalance: Boolean get() = supports(JOB_BALANCE)
    val supportsTransfer: Boolean get() = supports(JOB_TRANSFER)
    val supportsInstantTransfer: Boolean get() = supports(JOB_INSTANT_TRANSFER)
    val supportsScheduledTransfer: Boolean get() = supports(JOB_SCHEDULED_TRANSFER)

    /** Reading and creating standing orders are separate jobs at some banks. */
    val supportsStandingOrders: Boolean get() = supports(JOB_STANDING_LIST) || supports(JOB_STANDING_NEW)

    /** Stable form for the `bank_access.capabilities` column. */
    fun serialize(): String = jobs.sorted().joinToString(",")

    companion object {
        const val JOB_STATEMENTS_MT940 = "KUmsAll"
        const val JOB_STATEMENTS_CAMT = "KUmsAllCamt"
        const val JOB_BALANCE = "SaldoReq"
        const val JOB_TRANSFER = "UebSEPA"
        const val JOB_INSTANT_TRANSFER = "InstUebSEPA"
        const val JOB_SCHEDULED_TRANSFER = "TermUebSEPA"
        const val JOB_STANDING_LIST = "DauerSEPAList"
        const val JOB_STANDING_NEW = "DauerSEPANew"
        const val JOB_STANDING_DELETE = "DauerSEPADel"
        const val JOB_STANDING_EDIT = "DauerSEPAEdit"

        /** Jobs FinDesk uses; only these are probed and stored. */
        val PROBED = listOf(
            JOB_STATEMENTS_MT940,
            JOB_STATEMENTS_CAMT,
            JOB_BALANCE,
            JOB_TRANSFER,
            JOB_INSTANT_TRANSFER,
            JOB_SCHEDULED_TRANSFER,
            JOB_STANDING_LIST,
            JOB_STANDING_NEW,
            JOB_STANDING_DELETE,
            JOB_STANDING_EDIT,
        )

        /** From a support predicate (e.g. HBCIHandler.isSupported), keeping only jobs FinDesk knows. */
        fun detect(isSupported: (String) -> Boolean): BankCapabilities = BankCapabilities(PROBED.filter(isSupported).toSet())

        fun parse(serialized: String?): BankCapabilities? = serialized
            ?.takeIf { it.isNotBlank() }
            ?.let { BankCapabilities(it.split(',').map(String::trim).filter(String::isNotEmpty).toSet()) }
    }
}
