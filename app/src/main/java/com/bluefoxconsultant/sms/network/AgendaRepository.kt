package com.bluefoxconsultant.sms.network

import com.bluefoxconsultant.sms.data.AgendaConfig
import com.bluefoxconsultant.sms.data.AgendaEvent
import com.bluefoxconsultant.sms.data.AgendaEventResponse
import com.bluefoxconsultant.sms.data.AgendaEventsResponse
import com.bluefoxconsultant.sms.data.AgendaPing
import com.bluefoxconsultant.sms.data.AgendaTaskCounts
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
}
