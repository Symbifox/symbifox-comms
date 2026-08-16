package com.bluefoxconsultant.sms.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the instance's phone module says this user can do.
 *
 * [enabled] is the only field the UI should branch on: the server has already
 * decided whether the group, the PBX link and a ringable device are all in
 * place. A phone that guesses would offer a button that cannot work.
 */
@Serializable
data class PhoneConfig(
    val enabled: Boolean = false,
    /** SIP extension, when the user has one. Empty otherwise. */
    val extension: String = "",
    /** Number the PBX rings back, in +1XXXXXXXXXX. Empty when unset. */
    @SerialName("callback_number") val callbackNumber: String = "",
    /** `callback` or `extension` — what to preselect. */
    @SerialName("default_ring") val defaultRing: String = RING_CALLBACK,
) {
    val canRingCallback: Boolean get() = callbackNumber.isNotBlank()
    val canRingExtension: Boolean get() = extension.isNotBlank()

    companion object {
        const val RING_CALLBACK = "callback"
        const val RING_EXTENSION = "extension"
    }
}

@Serializable
data class CallRequest(
    val number: String,
    val ring: String? = null,
)

@Serializable
data class CallResponse(
    val ok: Boolean = false,
    val ring: String = "",
    /** Human-readable description of what is about to ring. */
    @SerialName("ring_label") val ringLabel: String = "",
    val number: String = "",
    @SerialName("contact_name") val contactName: String = "",
)
