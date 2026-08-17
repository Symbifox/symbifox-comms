package com.bluefoxconsultant.sms.network

import com.bluefoxconsultant.sms.data.CallRequest
import com.bluefoxconsultant.sms.data.CallResponse
import com.bluefoxconsultant.sms.data.ActiveCall
import com.bluefoxconsultant.sms.data.ActiveCallsResponse
import com.bluefoxconsultant.sms.data.CallLogEntry
import com.bluefoxconsultant.sms.data.DtmfResponse
import com.bluefoxconsultant.sms.data.HangupResponse
import com.bluefoxconsultant.sms.data.CallLogResponse
import com.bluefoxconsultant.sms.data.PhoneConfig
import com.bluefoxconsultant.sms.data.PhoneContact
import com.bluefoxconsultant.sms.data.PhoneContactsResponse
import com.bluefoxconsultant.sms.data.SipConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Le téléphone, côté réseau : demander au PBX, et — depuis la phase B —
 * s'enregistrer comme poste.
 *
 * Deux façons d'appeler cohabitent, et c'est voulu. [call] demande au PBX de
 * faire sonner un AUTRE appareil (un cellulaire, le poste du bureau) : aucun
 * micro, aucune permission, ça marche même app fermée. [sipConfig] sert
 * l'autre : les identifiants qui font de l'appareil un poste à part entière,
 * capable de composer lui-même et de porter la conversation.
 *
 * Rides on the Messages bearer token — `bf_softphone` depends on
 * `bf_sms_archive` server-side, so the phone is a capability of that session
 * rather than a third account to sign into.
 */
class PhoneRepository(private val api: ApiClient) {

    private val json = api.json

    /** Whether the instance serves the phone module at all. */
    suspend fun available(instance: String): Boolean = withContext(Dispatchers.IO) {
        api.ping(instance)
    }

    suspend fun config(): PhoneConfig = withContext(Dispatchers.IO) {
        json.decodeFromString(api.get("/config"))
    }

    /**
     * Les identifiants SIP de cet utilisateur, pour que l'app devienne un poste.
     *
     * ⚠️ Le mot de passe SIP arrive en clair : il ne doit ni être journalisé,
     * ni écrit sur le disque. Il est passé à la WebView et vit en mémoire le
     * temps de la session — c'est déjà ce que fait le navigateur.
     *
     * Le poste rendu est le MÊME que celui du navigateur. Le PBX fait sonner
     * tous les appareils enregistrés, donc s'enregistrer ici ajoute un endroit
     * où décrocher ; ça n'en retire aucun.
     */
    suspend fun sipConfig(): SipConfig = withContext(Dispatchers.IO) {
        json.decodeFromString(api.get("/sip"))
    }

    /** Contacts matching what has been dialled so far. */
    suspend fun contacts(query: String): List<PhoneContact> = withContext(Dispatchers.IO) {
        json.decodeFromString<PhoneContactsResponse>(
            api.get("/contacts?q=" + java.net.URLEncoder.encode(query, "UTF-8")),
        ).contacts
    }

    /** The user's own call log, newest first. */
    suspend fun calls(limit: Int = 40): List<CallLogEntry> = withContext(Dispatchers.IO) {
        json.decodeFromString<CallLogResponse>(api.get("/calls?limit=$limit")).calls
    }

    /** Calls the PBX is carrying for this user right now. */
    suspend fun active(): List<ActiveCall> = withContext(Dispatchers.IO) {
        json.decodeFromString<ActiveCallsResponse>(api.get("/active")).calls
    }

    /**
     * Ends the call. Without a channel the server hangs up everything of this
     * user's — which is what a "Raccrocher" button means when a call has two
     * legs and the person pressing it thinks of it as one call.
     */
    suspend fun hangup(channel: String? = null): HangupResponse = withContext(Dispatchers.IO) {
        val body = channel?.let { """{"channel":"$it"}""" } ?: "{}"
        json.decodeFromString(api.postJson("/hangup", body))
    }

    /**
     * Sends keys to the correspondent — answering a phone menu.
     *
     * The PBX plays them into the outbound leg, because the handset carries no
     * audio of its own. On a call answered on a real phone, that phone's own
     * keypad works too; this is what makes menus answerable when the audio is
     * on the desk softphone instead.
     */
    suspend fun dtmf(digits: String): DtmfResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(api.postJson("/dtmf", """{"digits":"$digits"}"""))
    }

    /** Asks the PBX to ring [ring] (or the user's default) and dial [number]. */
    suspend fun call(number: String, ring: String? = null): CallResponse =
        withContext(Dispatchers.IO) {
            json.decodeFromString(
                api.postJson("/call", json.encodeToString(CallRequest(number, ring))),
            )
        }
}
