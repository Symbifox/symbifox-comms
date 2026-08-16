package com.bluefoxconsultant.sms.network

import com.bluefoxconsultant.sms.data.Service
import com.bluefoxconsultant.sms.data.SpeechConfig
import com.bluefoxconsultant.sms.data.TokenStore
import com.bluefoxconsultant.sms.data.TranscribeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.io.File

/**
 * Dictation: audio in, text back.
 *
 * The `bf_speech` module depends on neither mailbox module, so it answers to
 * whichever device token this install happens to have. Hence two clients and a
 * pick per call rather than one fixed binding: an install signed into mail only
 * still gets a microphone.
 */
class SpeechRepository(
    private val smsApi: ApiClient,
    private val mailApi: ApiClient,
    private val tokenStore: TokenStore,
) {

    private fun api(): ApiClient =
        if (tokenStore.tokenFor(Service.SMS) != null) smsApi else mailApi

    /** Null when the module is absent — the caller keeps the mic hidden. */
    suspend fun config(): SpeechConfig? = withContext(Dispatchers.IO) {
        val client = api()
        runCatching {
            client.json.decodeFromString<SpeechConfig>(client.get("/ping"))
        }.getOrNull()?.takeIf { it.ok }
    }

    /** Uploads the recording and returns what was heard. */
    suspend fun transcribe(file: File): String = withContext(Dispatchers.IO) {
        val client = api()
        val response = client.postFile(
            "/transcribe", "audio", file.name, "audio/mp4", file.readBytes(),
        )
        client.json.decodeFromString<TranscribeResponse>(response).text
    }
}
