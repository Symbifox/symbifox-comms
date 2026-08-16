package com.bluefoxconsultant.sms.network

import com.bluefoxconsultant.sms.data.ExchangeRequest
import com.bluefoxconsultant.sms.data.MailActionResponse
import com.bluefoxconsultant.sms.data.MailComposeRequest
import com.bluefoxconsultant.sms.data.MailConfig
import com.bluefoxconsultant.sms.data.MailContact
import com.bluefoxconsultant.sms.data.MailContactsResponse
import com.bluefoxconsultant.sms.data.MailConversationResponse
import com.bluefoxconsultant.sms.data.MailCounts
import com.bluefoxconsultant.sms.data.MailCountsResponse
import com.bluefoxconsultant.sms.data.MailExchangeResponse
import com.bluefoxconsultant.sms.data.MailFilter
import com.bluefoxconsultant.sms.data.MailHandleRequest
import com.bluefoxconsultant.sms.data.MailIdsRequest
import com.bluefoxconsultant.sms.data.MailMessage
import com.bluefoxconsultant.sms.data.MailRecordsResponse
import com.bluefoxconsultant.sms.data.MailReplyRequest
import com.bluefoxconsultant.sms.data.MailRouteRequest
import com.bluefoxconsultant.sms.data.MailSnoozeRequest
import com.bluefoxconsultant.sms.data.MailSpawnRequest
import com.bluefoxconsultant.sms.data.MailThreadsResponse
import com.bluefoxconsultant.sms.data.RecordRef
import com.bluefoxconsultant.sms.data.PendingAction
import com.bluefoxconsultant.sms.data.RegisterPushRequest
import com.bluefoxconsultant.sms.data.StagedUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.URLEncoder

/** Typed calls against `bf_email_management`'s mobile API. */
class MailRepository(private val api: ApiClient) {

    private val json = api.json

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    suspend fun exchange(code: String): MailExchangeResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(api.postJson("/auth/exchange", json.encodeToString(ExchangeRequest(code))))
    }

    suspend fun config(): MailConfig = withContext(Dispatchers.IO) {
        json.decodeFromString(api.get("/config"))
    }

    suspend fun threads(
        filter: MailFilter = MailFilter.INBOX,
        search: String = "",
        accountId: Int? = null,
        offset: Int = 0,
        limit: Int = 25,
        grouped: Boolean = true,
    ): MailThreadsResponse = withContext(Dispatchers.IO) {
        val sb = StringBuilder(
            "/threads?filter=${filter.key}&offset=$offset&limit=$limit" +
                "&grouped=${if (grouped) 1 else 0}",
        )
        if (accountId != null) sb.append("&account_id=").append(accountId)
        if (search.isNotBlank()) sb.append("&search=").append(enc(search))
        json.decodeFromString(api.get(sb.toString()))
    }

    suspend fun conversation(threadKey: String, loadImages: Boolean = false):
        MailConversationResponse = withContext(Dispatchers.IO) {
        val images = if (loadImages) "&load_images=1" else ""
        json.decodeFromString(api.get("/conversation?thread_key=${enc(threadKey)}$images"))
    }

    suspend fun message(emailId: Int, loadImages: Boolean = false): MailMessage =
        withContext(Dispatchers.IO) {
            val images = if (loadImages) "&load_images=1" else ""
            json.decodeFromString(api.get("/message?id=$emailId$images"))
        }

    suspend fun attachment(emailId: Int, idx: Int): ByteArray = withContext(Dispatchers.IO) {
        api.getBytes("/attachment?email_id=$emailId&idx=$idx")
    }

    // ---- triage ----

    suspend fun markRead(emailIds: List<Int>): MailCounts = withContext(Dispatchers.IO) {
        json.decodeFromString<MailCountsResponse>(
            api.postJson("/mark_read", json.encodeToString(MailIdsRequest(emailIds))),
        ).counts
    }

    suspend fun setHandled(emailIds: List<Int>, handled: Boolean): MailCounts =
        withContext(Dispatchers.IO) {
            json.decodeFromString<MailCountsResponse>(
                api.postJson("/handle", json.encodeToString(MailHandleRequest(emailIds, handled))),
            ).counts
        }

    suspend fun snooze(emailIds: List<Int>, untilMs: Long): MailCounts =
        withContext(Dispatchers.IO) {
            json.decodeFromString<MailCountsResponse>(
                api.postJson("/snooze", json.encodeToString(MailSnoozeRequest(emailIds, untilMs))),
            ).counts
        }

    // ---- sending ----

    /**
     * `to`/`cc` stay null unless the user edited them: the server computes the
     * right recipients for reply and reply-all, and sending back what it gave
     * us would just be a chance to get them wrong. A forward has no server-side
     * default, so it always passes `to`.
     */
    suspend fun reply(
        emailId: Int,
        mode: String,
        body: String,
        to: List<String>? = null,
        cc: List<String>? = null,
        attachmentIds: List<Int>? = null,
        clientToken: String? = null,
        bodyIsHtml: Boolean = false,
    ): MailActionResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(
            api.postJson("/reply", json.encodeToString(
                MailReplyRequest(emailId, mode, body, to, cc, attachmentIds,
                                 clientToken, bodyIsHtml))),
        )
    }

    suspend fun compose(
        to: List<String>,
        subject: String,
        body: String,
        cc: List<String>? = null,
        attachmentIds: List<Int>? = null,
        clientToken: String? = null,
        bodyIsHtml: Boolean = false,
    ): MailActionResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(
            api.postJson("/compose", json.encodeToString(
                MailComposeRequest(to, subject, body, cc, attachmentIds,
                                   clientToken, bodyIsHtml))),
        )
    }

    // ---- Odoo-side actions ----

    suspend fun records(model: String, query: String): List<RecordRef> =
        withContext(Dispatchers.IO) {
            json.decodeFromString<MailRecordsResponse>(
                api.get("/records?model=${enc(model)}&q=${enc(query)}"),
            ).records
        }

    suspend fun contacts(query: String): List<MailContact> =
        withContext(Dispatchers.IO) {
            json.decodeFromString<MailContactsResponse>(
                api.get("/contacts?q=${enc(query)}"),
            ).contacts
        }

    suspend fun route(emailId: Int, model: String, recordId: Int): MailActionResponse =
        withContext(Dispatchers.IO) {
            json.decodeFromString(
                api.postJson("/route", json.encodeToString(MailRouteRequest(emailId, model, recordId))),
            )
        }

    suspend fun spawn(emailId: Int, kind: String): MailActionResponse =
        withContext(Dispatchers.IO) {
            json.decodeFromString(
                api.postJson("/spawn", json.encodeToString(MailSpawnRequest(emailId, kind))),
            )
        }

    suspend fun uploadAttachment(
        filename: String,
        mimetype: String,
        bytes: ByteArray,
    ): StagedUpload = withContext(Dispatchers.IO) {
        json.decodeFromString(
            api.postFile("/attachment/upload", "file", filename, mimetype, bytes),
        )
    }

    suspend fun registerPush(endpoint: String, appVersion: String) = withContext(Dispatchers.IO) {
        runCatching {
            api.postJson("/register_push", json.encodeToString(RegisterPushRequest(endpoint, appVersion)))
        }
        Unit
    }

    /** Send one queued action. Used by [com.bluefoxconsultant.sms.data.MailOutbox]. */
    suspend fun replay(action: PendingAction) {
        when (action.kind) {
            PendingAction.KIND_MARK_READ -> markRead(action.emailIds)
            PendingAction.KIND_HANDLE -> setHandled(action.emailIds, action.handled)
            PendingAction.KIND_SNOOZE -> snooze(action.emailIds, action.untilMs)
            PendingAction.KIND_REPLY -> reply(
                emailId = action.emailId, mode = action.mode, body = action.body,
                to = action.to, cc = action.cc, clientToken = action.token,
            )
            PendingAction.KIND_COMPOSE -> compose(
                to = action.to.orEmpty(), subject = action.subject,
                body = action.body, cc = action.cc, clientToken = action.token,
            )
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching { api.postJson("/logout", "{}") }
        Unit
    }
}
