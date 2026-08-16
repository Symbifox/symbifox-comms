package com.bluefoxconsultant.sms.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whether this instance offers the assistant, and on what terms.
 *
 * `readonly` is not a setting the app can change: mobile conversations go
 * through the bridge's read-only endpoint by construction. It is surfaced so
 * the screen can say so rather than let someone ask for something that will
 * quietly not happen.
 */
@Serializable
data class GenfoxConfig(
    val ok: Boolean = false,
    val enabled: Boolean = false,
    val readonly: Boolean = true,
    val version: String = "",
)

@Serializable
data class GenfoxSession(
    val id: Int = 0,
    val name: String = "",
    @SerialName("write_date") val writeDate: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
)

@Serializable
data class GenfoxSessionsResponse(val sessions: List<GenfoxSession> = emptyList())

@Serializable
data class GenfoxMessage(
    val id: Int = 0,
    val role: String = "",
    val content: String = "",
    /** `pending` while the assistant is still working on that turn. */
    val state: String = "done",
) {
    val isUser: Boolean get() = role == "user"
    val isPending: Boolean get() = state == "pending"
    val isError: Boolean get() = state == "error"
}

@Serializable
data class GenfoxMessagesResponse(
    @SerialName("session_id") val sessionId: Int = 0,
    @SerialName("session_name") val sessionName: String = "",
    val messages: List<GenfoxMessage> = emptyList(),
)

@Serializable
data class AskRequest(
    val message: String,
    @SerialName("session_id") val sessionId: Int? = null,
)

@Serializable
data class AskResponse(
    val ok: Boolean = false,
    @SerialName("session_id") val sessionId: Int = 0,
    @SerialName("turn_id") val turnId: Int = 0,
    val state: String = "pending",
)

@Serializable
data class TurnResponse(
    @SerialName("turn_id") val turnId: Int = 0,
    @SerialName("session_id") val sessionId: Int = 0,
    @SerialName("session_name") val sessionName: String = "",
    val state: String = "pending",
    val text: String = "",
)
