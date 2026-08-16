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
    /**
     * What the phone will display when the PBX rings it through the carrier.
     *
     * Never the correspondent: a trunk may only present a number the account
     * owns, so the carrier substitutes anything else. Said up front, because an
     * unexpected "Blue Fox" call is one you let ring.
     */
    @SerialName("callback_shows_as") val callbackShowsAs: String = "",
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
    @SerialName("shows_as") val showsAs: String = "",
)

@Serializable
data class PhoneContact(
    val id: Int = 0,
    val name: String = "",
    val number: String = "",
)

@Serializable
data class PhoneContactsResponse(val contacts: List<PhoneContact> = emptyList())

/** One line of the call log — what a phone calls "Recents". */
@Serializable
data class CallLogEntry(
    val id: Int = 0,
    /** `incoming`, `outgoing` or `missed`. */
    val direction: String = "",
    val number: String = "",
    val name: String = "",
    val date: String = "",
    val duration: Int = 0,
) {
    val isMissed: Boolean get() = direction == "missed"
    val isOutgoing: Boolean get() = direction == "outgoing"
}

@Serializable
data class CallLogResponse(val calls: List<CallLogEntry> = emptyList())

/**
 * A call the PBX is carrying right now for this user.
 *
 * The handset never held the call, so ending it means asking the server to
 * hang up the channel — hence the channel name travelling back and forth.
 */
@Serializable
data class ActiveCall(
    val channel: String = "",
    val number: String = "",
    val seconds: Int = 0,
    /** Asterisk channel state: `Up`, `Ringing`, `Ring`… */
    val state: String = "",
) {
    val isUp: Boolean get() = state.equals("Up", ignoreCase = true)
    val clock: String get() = "%d:%02d".format(seconds / 60, seconds % 60)
}

@Serializable
data class ActiveCallsResponse(val calls: List<ActiveCall> = emptyList())

@Serializable
data class HangupResponse(
    val ok: Boolean = false,
    @SerialName("hung_up") val hungUp: Int = 0,
)
