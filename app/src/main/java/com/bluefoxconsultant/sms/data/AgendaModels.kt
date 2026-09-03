package com.bluefoxconsultant.sms.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Ce que `/ping` répond : l'agenda est-il offert par cette instance. */
@Serializable
data class AgendaPing(
    val ok: Boolean = false,
    val enabled: Boolean = false,
    val version: String = "",
)

/** Ce que le serveur offre à cette session. */
@Serializable
data class AgendaConfig(
    val ok: Boolean = false,
    val user: String = "",
    /**
     * Le fuseau du COMPTE Odoo. L'écran affiche dans le fuseau de l'appareil ;
     * celui-ci sert à prévenir quand les deux diffèrent, ce qui est le cas
     * courant ici (compte à Auckland, parc à Montréal) et fait autrement lire
     * la grille à côté sans que rien ne le dise.
     */
    @SerialName("user_tz") val userTz: String = "",
    @SerialName("snooze_minutes") val snoozeMinutes: List<Int> = emptyList(),
    val features: AgendaFeatures = AgendaFeatures(),
)

@Serializable
data class AgendaFeatures(
    val meetings: Boolean = false,
    val tasks: Boolean = false,
    val rsvp: Boolean = false,
)

/**
 * Un événement tel que la grille en a besoin.
 *
 * ⚠️ [key] est l'identité qui compte pour écrire. `calendar_nextcloud_sync`
 * rase une série récurrente et recrée ses occurrences avec des `id` neufs dès
 * qu'un `.ics` réimporté porte un `RRULE` : un report envoyé avec l'[id] gardé
 * de la veille tomberait dans le vide. On envoie donc les deux, et le serveur
 * retombe sur la clé.
 */
@Serializable
data class AgendaEvent(
    val id: Int = 0,
    val key: String = "",
    val name: String = "",
    val start: String = "",
    val stop: String = "",
    val allday: Boolean = false,
    val duration: Double = 0.0,
    val location: String = "",
    val videocall: String = "",
    @SerialName("show_as") val showAs: String = "",
    val recurring: Boolean = false,
    val attendees: Int = 0,
    @SerialName("my_state") val myState: String = "",
    @SerialName("snoozed_until") val snoozedUntil: String? = null,
    @SerialName("dismissed_at") val dismissedAt: String? = null,
    @SerialName("agenda_state") val agendaState: String = "none",
    @SerialName("minutes_state") val minutesState: String = "none",
    /**
     * Les couleurs viennent du SERVEUR, calculées avec la règle d'Odoo.
     * Les recalculer ici obligerait à recopier une palette de 56 tons et la
     * formule `((clé - 1) % 55) + 1`, donc à les corriger à deux endroits.
     */
    val color: String = "",
    @SerialName("color_soft") val colorSoft: String = "",
    val calendar: String = "",
    @SerialName("skip_agenda") val skipAgenda: Boolean = false,
    @SerialName("skip_dashboard") val skipDashboard: Boolean = false,
    val url: String = "",
    // Présents seulement sur la fiche détaillée.
    val description: String = "",
    val organizer: String = "",
    @SerialName("attendee_list") val attendeeList: List<AgendaAttendee> = emptyList(),
    val agenda: AgendaOdj? = null,
    val minutes: AgendaMinutes? = null,
) {
    val startInstant: Instant? get() = parseInstant(start)
    val stopInstant: Instant? get() = parseInstant(stop)

    fun startAt(zone: ZoneId): ZonedDateTime? = startInstant?.atZone(zone)
    fun stopAt(zone: ZoneId): ZonedDateTime? = stopInstant?.atZone(zone)

    /**
     * Le jour où poser l'événement.
     *
     * ⚠️ Une journée entière est stockée à minuit UTC : la convertir dans le
     * fuseau de l'appareil la ferait glisser d'un jour dès qu'on est à l'ouest
     * de Greenwich. On lit donc sa date en UTC, et seulement la sienne.
     */
    fun dayAt(zone: ZoneId): LocalDate? {
        val instant = startInstant ?: return null
        return if (allday) instant.atZone(ZoneId.of("UTC")).toLocalDate()
        else instant.atZone(zone).toLocalDate()
    }

    val hasAgenda: Boolean get() = agendaState != "none" && agendaState.isNotBlank()
    val hasMinutes: Boolean get() = minutesState != "none" && minutesState.isNotBlank()
}

@Serializable
data class AgendaAttendee(
    val name: String = "",
    val state: String = "",
    @SerialName("is_me") val isMe: Boolean = false,
)

@Serializable
data class AgendaOdj(
    val id: Int = 0,
    val name: String = "",
    val state: String = "",
    @SerialName("sent_date") val sentDate: String? = null,
    val topics: List<String> = emptyList(),
)

@Serializable
data class AgendaMinutes(
    val id: Int = 0,
    val name: String = "",
    @SerialName("report_state") val reportState: String = "",
    val summary: String = "",
    val decisions: List<String> = emptyList(),
)

@Serializable
data class AgendaEventsResponse(
    val ok: Boolean = false,
    val truncated: Boolean = false,
    val events: List<AgendaEvent> = emptyList(),
)

@Serializable
data class AgendaEventResponse(
    val ok: Boolean = false,
    val event: AgendaEvent? = null,
)

@Serializable
data class AgendaTaskCounts(
    val ok: Boolean = false,
    val tz: String = "",
    /** Clé « 2026-09-04 », dans le fuseau du COMPTE, pas celui de l'appareil. */
    val counts: Map<String, Int> = emptyMap(),
)

@Serializable
data class AgendaTask(
    val id: Int = 0,
    val name: String = "",
    val project: String = "",
    @SerialName("project_id") val projectId: Int = 0,
    val deadline: String? = null,
    val priority: String = "0",
    val state: String = "",
    @SerialName("state_label") val stateLabel: String = "",
    val stage: String = "",
    @SerialName("stage_id") val stageId: Int = 0,
    val partner: String = "",
    val color: String = "",
    val tags: List<AgendaTag> = emptyList(),
    val done: Boolean = false,
    val url: String = "",
) {
    fun deadlineAt(zone: ZoneId): ZonedDateTime? =
        parseInstant(deadline ?: "")?.atZone(zone)
}

@Serializable
data class AgendaTasksResponse(
    val ok: Boolean = false,
    val overdue: List<AgendaTask> = emptyList(),
    val window: List<AgendaTask> = emptyList(),
    @SerialName("undated_count") val undatedCount: Int = 0,
    val undated: List<AgendaTask> = emptyList(),
)

/**
 * Le serveur rend « 2026-09-04T11:00:54Z » partout, sauf les horodatages que
 * `bf_snooze` renvoie tels quels (« 2026-09-03 11:16:13 », sans Z). On accepte
 * les deux plutôt que de laisser une exception traverser un écran.
 */
internal fun parseInstant(raw: String): Instant? {
    if (raw.isBlank()) return null
    return runCatching { Instant.parse(raw) }
        .recoverCatching { Instant.parse(raw.replace(' ', 'T') + "Z") }
        .getOrNull()
}

@Serializable
data class AgendaTag(
    val id: Int = 0,
    val name: String = "",
    /** Déjà en hexadécimal : la palette de kanban vit côté serveur. */
    val color: String = "",
)

@Serializable
data class AgendaCalendar(
    val id: Int = 0,
    val name: String = "",
    val color: String = "",
    @SerialName("color_soft") val colorSoft: String = "",
)

@Serializable
data class AgendaCalendarsResponse(
    val ok: Boolean = false,
    val calendars: List<AgendaCalendar> = emptyList(),
)

@Serializable
data class AgendaOption(val id: Int = 0, val name: String = "", val color: String = "")

@Serializable
data class AgendaChoice(val value: String = "", val label: String = "")

/** De quoi remplir les sélecteurs de l'écran des tâches. */
@Serializable
data class AgendaTaskOptions(
    val ok: Boolean = false,
    val projects: List<AgendaOption> = emptyList(),
    val stages: List<AgendaOption> = emptyList(),
    val tags: List<AgendaOption> = emptyList(),
    val states: List<AgendaChoice> = emptyList(),
    val priorities: List<AgendaChoice> = emptyList(),
)

@Serializable
data class AgendaTaskResponse(
    val ok: Boolean = false,
    val task: AgendaTask? = null,
)
