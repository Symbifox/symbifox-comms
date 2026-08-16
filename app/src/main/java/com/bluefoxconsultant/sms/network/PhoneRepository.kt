package com.bluefoxconsultant.sms.network

import com.bluefoxconsultant.sms.data.CallRequest
import com.bluefoxconsultant.sms.data.CallResponse
import com.bluefoxconsultant.sms.data.PhoneConfig
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

    /** Asks the PBX to ring [ring] (or the user's default) and dial [number]. */
    suspend fun call(number: String, ring: String? = null): CallResponse =
        withContext(Dispatchers.IO) {
            json.decodeFromString(
                api.postJson("/call", json.encodeToString(CallRequest(number, ring))),
            )
        }
}
