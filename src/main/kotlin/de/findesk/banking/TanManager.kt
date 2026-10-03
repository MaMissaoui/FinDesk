package de.findesk.banking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TAN method information
 */
data class TanMethod(
    val id: String,
    val name: String,
    val mediumName: String? = null,
    val tanFormat: String? = null
)

/**
 * Manages TAN (Transaction Authentication Number) operations
 * Supports chipTAN, photoTAN, pushTAN, and SMS-TAN
 */
class TanManager {
    private val _pendingTanRequest = MutableStateFlow<TanRequest?>(null)
    val pendingTanRequest: StateFlow<TanRequest?> = _pendingTanRequest.asStateFlow()

    data class TanRequest(
        val method: TanMethod,
        val challenge: String?,
        val challengeHhdUc: ByteArray? = null, // For chipTAN optical
        val tanImageUrl: String? = null, // For photoTAN
        val onResponse: (String?) -> Unit
    )

    /**
     * Request TAN from user
     */
    fun requestTan(method: TanMethod, challenge: String?, callback: (String?) -> Unit) {
        _pendingTanRequest.value = TanRequest(
            method = method,
            challenge = challenge,
            onResponse = callback
        )
    }

    /**
     * Submit TAN response
     */
    fun submitTan(tan: String) {
        _pendingTanRequest.value?.onResponse(tan)
        _pendingTanRequest.value = null
    }

    /**
     * Cancel pending TAN request
     */
    fun cancelTan() {
        _pendingTanRequest.value?.onResponse(null)
        _pendingTanRequest.value = null
    }

    /**
     * Get list of supported TAN methods
     */
    fun getSupportedMethods(): List<TanMethod> = listOf(
        TanMethod(id = "942", name = "chipTAN optisch", mediumName = "TAN-Generator"),
        TanMethod(id = "941", name = "chipTAN manuell", mediumName = "TAN-Generator"),
        TanMethod(id = "922", name = "photoTAN", mediumName = "photoTAN-App"),
        TanMethod(id = "901", name = "pushTAN", mediumName = "pushTAN-App"),
        TanMethod(id = "900", name = "SMS-TAN", mediumName = "Handy")
    )
}