package com.bluefoxconsultant.sms.network

import com.bluefoxconsultant.sms.data.Contact
import com.bluefoxconsultant.sms.data.ContactsResponse
import com.bluefoxconsultant.sms.data.ConfigResponse
import com.bluefoxconsultant.sms.data.ConversationResponse
import com.bluefoxconsultant.sms.data.ExchangeRequest
import com.bluefoxconsultant.sms.data.LoginResponse
import com.bluefoxconsultant.sms.data.MarkReadRequest
import com.bluefoxconsultant.sms.data.PinResponse
import com.bluefoxconsultant.sms.data.SendNewRequest
import com.bluefoxconsultant.sms.data.SendResponse
import com.bluefoxconsultant.sms.data.SendThreadRequest
import com.bluefoxconsultant.sms.data.Thread
import com.bluefoxconsultant.sms.data.ThreadArchiveRequest
import com.bluefoxconsultant.sms.data.ThreadPinRequest
import com.bluefoxconsultant.sms.data.ThreadsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.URLEncoder

class Repository(private val api: ApiClient) {

    private val json = api.json

    suspend fun exchange(code: String): LoginResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(ExchangeRequest(code))
        json.decodeFromString(api.postJson("/auth/exchange", body))
    }

    suspend fun threads(
        archived: Int = 0,
        lineId: Int? = null,
        search: String = "",
    ): List<Thread> = withContext(Dispatchers.IO) {
        val sb = StringBuilder("/threads?archived=$archived")
        if (lineId != null) sb.append("&line_id=").append(lineId)
        if (search.isNotBlank()) sb.append("&search=").append(URLEncoder.encode(search, "UTF-8"))
        json.decodeFromString<ThreadsResponse>(api.get(sb.toString())).threads
    }

    suspend fun config(): ConfigResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(api.get("/config"))
    }

    suspend fun archive(threadId: Int, archived: Boolean) = withContext(Dispatchers.IO) {
        api.postJson("/thread/archive", json.encodeToString(ThreadArchiveRequest(threadId, archived)))
        Unit
    }

    suspend fun pin(threadId: Int): Boolean = withContext(Dispatchers.IO) {
        val resp = json.decodeFromString<PinResponse>(
            api.postJson("/thread/pin", json.encodeToString(ThreadPinRequest(threadId))),
        )
        resp.isPinned
    }

    suspend fun contacts(query: String): List<Contact> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        json.decodeFromString<ContactsResponse>(api.get("/contacts?q=$q")).contacts
    }

    suspend fun conversation(threadId: Int, beforeId: Int?): ConversationResponse =
        withContext(Dispatchers.IO) {
            val before = if (beforeId != null) "&before_id=$beforeId" else ""
            json.decodeFromString(api.get("/conversation?thread_id=$threadId$before"))
        }

    suspend fun send(threadId: Int, body: String, lineId: Int? = null): SendResponse =
        withContext(Dispatchers.IO) {
            json.decodeFromString(
                api.postJson("/send", json.encodeToString(SendThreadRequest(threadId, body, lineId))),
            )
        }

    suspend fun sendNew(phone: String, lineId: Int, body: String): SendResponse =
        withContext(Dispatchers.IO) {
            json.decodeFromString(api.postJson("/send", json.encodeToString(SendNewRequest(phone, lineId, body))))
        }

    suspend fun markRead(threadId: Int) = withContext(Dispatchers.IO) {
        runCatching { api.postJson("/mark_read", json.encodeToString(MarkReadRequest(threadId))) }
        Unit
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching { api.postJson("/logout", "{}") }
        Unit
    }
}
