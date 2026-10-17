package de.findesk.banking

/**
 * TAN method information
 */
data class TanMethod(
    val id: String,
    val name: String,
    val mediumName: String? = null,
    val tanFormat: String? = null,
)
