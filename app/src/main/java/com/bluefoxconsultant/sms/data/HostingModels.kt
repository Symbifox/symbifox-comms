package com.bluefoxconsultant.sms.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Une chose qui ne va pas dans le parc hébergé. */
@Serializable
data class HostingAlert(
    /** "down", "storage", "backup" ou "maintenance". */
    val kind: String = "",
    val id: Int = 0,
    val service: String = "",
    val detail: String = "",
    @SerialName("since_ms") val sinceMs: Long = 0,
) {
    val isDown: Boolean get() = kind == "down"
}

/**
 * L'état du parc, tel que le serveur le résume.
 *
 * [enabled] vaut faux quand l'utilisateur n'a pas l'hébergement : ce n'est pas
 * une erreur, c'est une absence de capacité, et l'app cesse alors d'interroger
 * plutôt que de réessayer toutes les minutes pour rien.
 *
 * ⚠️ [count] est le VRAI total ; [alerts] est bornée côté serveur. Compter la
 * liste ferait dire « 25 alertes » à un parc qui en a quarante.
 */
@Serializable
data class HostingAlerts(
    val ok: Boolean = false,
    val enabled: Boolean = false,
    /** "down", "warning" ou "none". */
    val severity: String = "none",
    val count: Int = 0,
    val summary: String = "",
    val alerts: List<HostingAlert> = emptyList(),
) {
    val isDown: Boolean get() = severity == "down"
    val hasAny: Boolean get() = severity != "none" && count > 0
}
