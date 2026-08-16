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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Calls placed by the PBX, not by this handset.
 *
 * The app carries no SIP stack: it asks the server, the PBX rings the user's own
 * device and then dials the correspondent, so the call goes out with the
 * business line's caller ID instead of a personal number. Nothing here touches
 * the microphone, and nothing here needs a permission.
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
