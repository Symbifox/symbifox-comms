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

/** One tool the assistant reached for, in the order it did. */
@Serializable
data class GenfoxTool(
    val name: String = "",
    /** Character offset in the answer when the call started — used to order. */
    val at: Int = 0,
) {
    /** `mcp__tentaclaude-bf__odoo_get_task` reads as `odoo_get_task`. */
    val short: String get() = name.substringAfterLast("__").ifBlank { name }
}

/**
 * Ce qu'un tour a consommé.
 *
 * ⚠️ [totalTokens] additionne le contexte RELU, qui n'est pas du travail neuf :
 * c'est le même contexte relu à chaque pas interne du tour, facturé au dixième
 * du prix. Il représentait ~93 % du volume mensuel, et c'est ce qui faisait
 * afficher « 50 k jetons » pour un bonjour — vrai, et incompréhensible.
 * L'écran montre donc [displayTokens], la même grandeur que le Cockpit Odoo et
 * que le panneau web. Trois surfaces, un seul chiffre.
 */
@Serializable
data class GenfoxUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Int = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Int = 0,
    @SerialName("net_tokens") val netTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("cost_usd") val costUsd: Double = 0.0,
    @SerialName("duration_ms") val durationMs: Int = 0,
) {
    val hasAny: Boolean get() = totalTokens > 0 || outputTokens > 0

    /**
     * Les jetons à afficher : neufs, sans le contexte relu.
     *
     * Recalculé à défaut, pour qu'un serveur plus ancien — qui ne sert pas
     * `net_tokens` — donne quand même le bon chiffre au lieu de zéro.
     */
    val displayTokens: Int
        get() = if (netTokens > 0) netTokens
        else inputTokens + cacheWriteTokens + outputTokens
}

@Serializable
data class GenfoxMessage(
    val id: Int = 0,
    val role: String = "",
    val content: String = "",
    /** `pending` while the assistant is still working on that turn. */
    val state: String = "done",
    val tools: List<GenfoxTool> = emptyList(),
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Int = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Int = 0,
    @SerialName("net_tokens") val netTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("cost_usd") val costUsd: Double = 0.0,
    @SerialName("duration_ms") val durationMs: Int = 0,
) {
    val usage: GenfoxUsage
        get() = GenfoxUsage(inputTokens, outputTokens, cacheReadTokens,
                            cacheWriteTokens, netTokens, totalTokens,
                            costUsd, durationMs)

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
    /** Grows between polls while the answer is being written. */
    val text: String = "",
    val tools: List<GenfoxTool> = emptyList(),
    val usage: GenfoxUsage = GenfoxUsage(),
)
