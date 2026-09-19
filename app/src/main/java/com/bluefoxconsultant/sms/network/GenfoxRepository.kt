package com.bluefoxconsultant.sms.network

import com.bluefoxconsultant.sms.data.AskRequest
import com.bluefoxconsultant.sms.data.AskResponse
import com.bluefoxconsultant.sms.data.GenfoxConfig
import com.bluefoxconsultant.sms.data.GenfoxMessagesResponse
import com.bluefoxconsultant.sms.data.GenfoxSession
import com.bluefoxconsultant.sms.data.GenfoxSessionsResponse
import com.bluefoxconsultant.sms.data.Service
import com.bluefoxconsultant.sms.data.TokenStore
import com.bluefoxconsultant.sms.data.TurnResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * GenFox, asked from the phone.
 *
 * A turn is not a request/response: [ask] returns as soon as the question is
 * recorded, and the answer is collected later through [turn] or announced by a
 * push. That shape is deliberate — an agentic turn can run for minutes, and a
 * phone cannot hold a connection open that long without dropping it, which on
 * this bridge used to cost the *next* turn its memory.
 */
class GenfoxRepository(
    private val smsApi: ApiClient,
    private val mailApi: ApiClient,
    private val tokenStore: TokenStore,
) {

    private fun api(): ApiClient =
        if (tokenStore.tokenFor(Service.SMS) != null) smsApi else mailApi

    suspend fun config(): GenfoxConfig? = withContext(Dispatchers.IO) {
        val client = api()
        runCatching {
            client.json.decodeFromString<GenfoxConfig>(client.get("/ping"))
        }.getOrNull()?.takeIf { it.ok }
    }

    suspend fun sessions(): List<GenfoxSession> = withContext(Dispatchers.IO) {
        val client = api()
        client.json.decodeFromString<GenfoxSessionsResponse>(
            client.get("/sessions"),
        ).sessions
    }

    suspend fun messages(sessionId: Int): GenfoxMessagesResponse = withContext(Dispatchers.IO) {
        val client = api()
        client.json.decodeFromString(client.get("/messages?session_id=$sessionId"))
    }

    /** Records the question; the answer is not in the reply. */
    suspend fun ask(message: String, sessionId: Int?): AskResponse = withContext(Dispatchers.IO) {
        val client = api()
        client.json.decodeFromString(
            client.postJson("/ask", client.json.encodeToString(AskRequest(message, sessionId))),
        )
    }

    suspend fun turn(turnId: Int): TurnResponse = withContext(Dispatchers.IO) {
        val client = api()
        client.json.decodeFromString(client.get("/turn?turn_id=$turnId"))
    }

    /**
     * Le bouton Arrêter. Le tour s'enregistre ensuite avec ce qu'il avait
     * écrit ; c'est [turn] qui le dit, pas cette réponse.
     */
    suspend fun stop(turnId: Int) = withContext(Dispatchers.IO) {
        val client = api()
        client.postJson("/stop", """{"turn_id":$turnId}""")
        Unit
    }

    suspend fun deleteSession(sessionId: Int) = withContext(Dispatchers.IO) {
        val client = api()
        client.postJson("/delete-session", """{"session_id":$sessionId}""")
        Unit
    }
}
