package de.findesk.banking

/**
 * Questions the bank asks during a FinTS dialog. The banking code calls these from a background
 * thread and blocks until the user answers; returning null (or false) cancels the operation.
 */
interface BankingInteraction {
    /** Online banking PIN. Never stored by FinDesk. */
    fun pin(bankName: String, userId: String): String?

    /** Pick one of the TAN procedures the bank offers. */
    fun selectTanMethod(options: List<TanMethod>): String?

    /** Pick a TAN medium (e.g. a registered phone or TAN generator). */
    fun selectTanMedium(options: List<String>): String?

    /** Enter a TAN for [challenge]. */
    fun tan(challenge: TanChallenge): String?

    /** Decoupled procedures (e.g. pushTAN app): user confirms in the app, then continues here. */
    fun confirmDecoupled(message: String): Boolean

    /**
     * Verification of Payee (EU instant payments regulation): the bank compared the recipient
     * name with the account holder. The transfer only proceeds if this returns true.
     */
    fun confirmPayee(message: String): Boolean

    /** Informational message from the bank. */
    fun info(message: String) {}
}

data class TanChallenge(
    val message: String,
    /** PNG/JPEG for photoTAN or QR-TAN, if the bank sent an image. */
    val image: ByteArray? = null,
    val imageMimeType: String? = null,
    /** chipTAN optical: rendered flicker code (HHD), to be shown as an animation for the TAN generator. */
    val flickerCode: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is TanChallenge && message == other.message && image.contentEquals(other.image) && flickerCode == other.flickerCode

    override fun hashCode(): Int = message.hashCode() * 31 + (image?.contentHashCode() ?: 0)
}

/** Thrown when the user cancels a banking prompt. */
class BankingCancelledException(message: String = "Cancelled by user") : RuntimeException(message)
