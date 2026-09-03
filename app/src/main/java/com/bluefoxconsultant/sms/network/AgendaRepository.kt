package com.bluefoxconsultant.sms.network

import com.bluefoxconsultant.sms.data.AgendaCalendarsResponse
import com.bluefoxconsultant.sms.data.AgendaConfig
import com.bluefoxconsultant.sms.data.AgendaEvent
import com.bluefoxconsultant.sms.data.AgendaEventResponse
import com.bluefoxconsultant.sms.data.AgendaEventsResponse
import com.bluefoxconsultant.sms.data.AgendaPing
import com.bluefoxconsultant.sms.data.AgendaTask
import com.bluefoxconsultant.sms.data.AgendaTaskCounts
import com.bluefoxconsultant.sms.data.AgendaTaskOptions
import com.bluefoxconsultant.sms.data.AgendaTaskResponse
import com.bluefoxconsultant.sms.data.AgendaTasksResponse
import com.bluefoxconsultant.sms.data.Service
import com.bluefoxconsultant.sms.data.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * L'agenda et les échéances — `bf_calendar_mobile`.
 *
 * Monte sur le jeton déjà en place, messages ou courriel : voir l'agenda est
 * une capacité de la session, pas un compte de plus. Même parade que GenFox et
 * la dictée, pour la même raison — le module serveur ne dépend pas de la
 * moitié qui a émis le jeton.
 *
 * ⚠️ Les fenêtres partent en UTC. Le serveur refuse au-delà de 62 jours plutôt
 * que de rogner en silence, donc l'appelant ne demande jamais « tout ».
 */
class AgendaRepository(
    private val smsApi: ApiClient,
    private val mailApi: ApiClient,
    private val tokenStore: TokenStore,
) {

    private fun api(): ApiClient =
        if (tokenStore.tokenFor(Service.SMS) != null) smsApi else mailApi

    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"))

    private fun q(value: Instant): String =
        URLEncoder.encode(stamp.format(value), "UTF-8")

    /** Le module répond-il ici ? Sans ça, l'onglet ne s'affiche pas. */
    suspend fun ping(): AgendaPing? = withContext(Dispatchers.IO) {
        val client = api()
        runCatching {
            client.json.decodeFromString<AgendaPing>(client.get("/ping"))
        }.getOrNull()?.takeIf { it.ok }
    }

    suspend fun config(): AgendaConfig? = withContext(Dispatchers.IO) {
        val client = api()
        runCatching {
            client.json.decodeFromString<AgendaConfig>(client.get("/config"))
        }.getOrNull()?.takeIf { it.ok }
    }

    suspend fun events(from: Instant, to: Instant): AgendaEventsResponse =
        withContext(Dispatchers.IO) {
            val client = api()
            client.json.decodeFromString(
                client.get("/events?from=${q(from)}&to=${q(to)}"),
            )
        }

    /** La fiche complète : l'OdJ et le compte rendu n'arrivent qu'ici. */
    suspend fun event(id: Int, key: String): AgendaEvent? = withContext(Dispatchers.IO) {
        val client = api()
        val suffix = if (key.isBlank()) "" else "&key=" + URLEncoder.encode(key, "UTF-8")
        runCatching {
            client.json.decodeFromString<AgendaEventResponse>(
                client.get("/event?id=$id$suffix"),
            ).event
        }.getOrNull()
    }

    suspend fun snooze(id: Int, key: String, minutes: Int): Boolean =
        withContext(Dispatchers.IO) {
            val client = api()
            runCatching {
                client.postJson(
                    "/snooze",
                    """{"event_id":$id,"key":${quote(key)},"minutes":$minutes}""",
                )
            }.isSuccess
        }

    suspend fun dismiss(id: Int, key: String): Boolean = withContext(Dispatchers.IO) {
        val client = api()
        runCatching {
            client.postJson("/dismiss", """{"event_id":$id,"key":${quote(key)}}""")
        }.isSuccess
    }

    /** `accepted`, `declined` ou `tentative`. Le serveur refuse le reste. */
    suspend fun rsvp(id: Int, key: String, state: String): Boolean =
        withContext(Dispatchers.IO) {
            val client = api()
            runCatching {
                client.postJson(
                    "/rsvp",
                    """{"event_id":$id,"key":${quote(key)},"state":${quote(state)}}""",
                )
            }.isSuccess
        }

    suspend fun tasks(from: Instant, to: Instant, undated: Boolean = false):
        AgendaTasksResponse = withContext(Dispatchers.IO) {
        val client = api()
        val extra = if (undated) "&undated=1" else ""
        client.json.decodeFromString(
            client.get("/tasks?from=${q(from)}&to=${q(to)}$extra"),
        )
    }

    suspend fun taskCounts(from: Instant, to: Instant): AgendaTaskCounts =
        withContext(Dispatchers.IO) {
            val client = api()
            client.json.decodeFromString(
                client.get("/task_counts?from=${q(from)}&to=${q(to)}"),
            )
        }

    /**
     * ⚠️ Une clé porte des « @ », des tirets et une espace. Concaténée telle
     * quelle dans du JSON, une seule apostrophe double casserait la requête ;
     * on échappe donc plutôt que de faire confiance à la forme.
     */
    private fun quote(value: String): String =
        buildString {
            append('"')
            value.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }

    // ── Écrire sur l'agenda ─────────────────────────────────────────────

    suspend fun calendars(): List<com.bluefoxconsultant.sms.data.AgendaCalendar> =
        withContext(Dispatchers.IO) {
            val client = api()
            runCatching {
                client.json.decodeFromString<AgendaCalendarsResponse>(
                    client.get("/calendars"),
                ).calendars
            }.getOrDefault(emptyList())
        }

    /** Rend l'événement créé, ou null. Le serveur y met le calendrier voulu. */
    suspend fun createEvent(
        name: String,
        startUtc: Instant,
        stopUtc: Instant,
        location: String,
        videocall: String,
        calendarId: Int?,
    ): AgendaEvent? = withContext(Dispatchers.IO) {
        val client = api()
        val corps = buildString {
            append("{")
            append("\"name\":").append(quote(name))
            append(",\"start\":").append(quote(stamp.format(startUtc)))
            append(",\"stop\":").append(quote(stamp.format(stopUtc)))
            if (location.isNotBlank()) append(",\"location\":").append(quote(location))
            if (videocall.isNotBlank()) {
                append(",\"videocall_location\":").append(quote(videocall))
            }
            if (calendarId != null) append(",\"calendar_config_id\":").append(calendarId)
            append("}")
        }
        runCatching {
            client.json.decodeFromString<AgendaEventResponse>(
                client.postJson("/event/create", corps),
            ).event
        }.getOrNull()
    }

    /** Pose ou retire une exclusion. Rend la fiche telle que le serveur la voit. */
    suspend fun setFlags(
        id: Int,
        key: String,
        skipAgenda: Boolean? = null,
        skipDashboard: Boolean? = null,
    ): AgendaEvent? = withContext(Dispatchers.IO) {
        val client = api()
        val corps = buildString {
            append("{\"event_id\":").append(id)
            append(",\"key\":").append(quote(key))
            skipAgenda?.let { append(",\"skip_agenda\":").append(it) }
            skipDashboard?.let { append(",\"skip_dashboard\":").append(it) }
            append("}")
        }
        runCatching {
            client.json.decodeFromString<AgendaEventResponse>(
                client.postJson("/event/flags", corps),
            ).event
        }.getOrNull()
    }

    // ── Écrire sur les tâches ───────────────────────────────────────────

    suspend fun taskOptions(projectId: Int? = null): AgendaTaskOptions =
        withContext(Dispatchers.IO) {
            val client = api()
            val suffixe = projectId?.let { "?project_id=$it" } ?: ""
            runCatching {
                client.json.decodeFromString<AgendaTaskOptions>(
                    client.get("/task/options$suffixe"),
                )
            }.getOrDefault(AgendaTaskOptions())
        }

    /**
     * [values] est un JSON déjà formé : les champs offerts diffèrent d'un geste
     * à l'autre, et une signature par combinaison serait vite illisible. Le
     * serveur filtre en liste blanche de toute façon.
     */
    suspend fun writeTask(id: Int, values: String): AgendaTask? =
        withContext(Dispatchers.IO) {
            val client = api()
            runCatching {
                client.json.decodeFromString<AgendaTaskResponse>(
                    client.postJson(
                        "/task/write",
                        """{"task_id":$id,"values":$values}""",
                    ),
                ).task
            }.getOrNull()
        }

    suspend fun completeTask(id: Int, done: Boolean): AgendaTask? =
        withContext(Dispatchers.IO) {
            val client = api()
            runCatching {
                client.json.decodeFromString<AgendaTaskResponse>(
                    client.postJson("/task/done", """{"task_id":$id,"done":$done}"""),
                ).task
            }.getOrNull()
        }

    suspend fun createTask(
        name: String,
        projectId: Int,
        deadlineUtc: Instant?,
        priority: String,
        tagIds: List<Int>,
    ): AgendaTask? = withContext(Dispatchers.IO) {
        val client = api()
        val corps = buildString {
            append("{\"name\":").append(quote(name))
            append(",\"project_id\":").append(projectId)
            deadlineUtc?.let {
                append(",\"date_deadline\":").append(quote(stamp.format(it)))
            }
            if (priority != "0") append(",\"priority\":").append(quote(priority))
            if (tagIds.isNotEmpty()) {
                append(",\"tag_ids\":").append(tagIds.joinToString(",", "[", "]"))
            }
            append("}")
        }
        runCatching {
            client.json.decodeFromString<AgendaTaskResponse>(
                client.postJson("/task/create", corps),
            ).task
        }.getOrNull()
    }
}
