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
import com.bluefoxconsultant.sms.data.ReplyPrepareResponse
import com.bluefoxconsultant.sms.data.ScheduledMailsResponse
import com.bluefoxconsultant.sms.data.ScheduledRefRequest
import com.bluefoxconsultant.sms.data.UnscheduleResponse
import com.bluefoxconsultant.sms.data.ServerDraft
import com.bluefoxconsultant.sms.data.ServerDraftRefRequest
import com.bluefoxconsultant.sms.data.ServerDraftSaveRequest
import com.bluefoxconsultant.sms.data.ServerDraftSaveResponse
import com.bluefoxconsultant.sms.data.ServerDraftsResponse
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

    suspend fun exchange(code: String, verifier: String): MailExchangeResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(
            api.postJson("/auth/exchange", json.encodeToString(ExchangeRequest(code, verifier))))
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

    /**
     * Les pastilles, seules.
     *
     * ⚠️ Sans cette route, les totaux ne descendaient qu'à l'ouverture de
     * l'écran (`/config`) et dans la réponse d'une mutation faite ICI. Un
     * courriel qui arrive, un ménage fait au navigateur, ou simplement ouvrir
     * un fil — ce qui marque lu côté serveur — les laissaient figés, et tirer
     * pour rafraîchir ne les touchait pas. D'où « Non lus · 5 » au-dessus
     * d'une liste sans rien à lire.
     *
     * [grouped] doit valoir ce que la liste affiche : le serveur compte des
     * conversations quand elles sont repliées, des messages sinon.
     */
    suspend fun counts(grouped: Boolean): MailCounts = withContext(Dispatchers.IO) {
        json.decodeFromString<MailCountsResponse>(
            api.get("/counts?grouped=${if (grouped) 1 else 0}"),
        ).counts
    }

    // ---- triage ----

    suspend fun markRead(emailIds: List<Int>, grouped: Boolean = true): MailCounts =
        withContext(Dispatchers.IO) {
            json.decodeFromString<MailCountsResponse>(
                api.postJson("/mark_read", json.encodeToString(MailIdsRequest(emailIds, grouped))),
            ).counts
        }

    suspend fun setHandled(
        emailIds: List<Int>,
        handled: Boolean,
        grouped: Boolean = true,
    ): MailCounts =
        withContext(Dispatchers.IO) {
            json.decodeFromString<MailCountsResponse>(
                api.postJson(
                    "/handle",
                    json.encodeToString(MailHandleRequest(emailIds, handled, grouped)),
                ),
            ).counts
        }

    suspend fun snooze(
        emailIds: List<Int>,
        untilMs: Long,
        grouped: Boolean = true,
    ): MailCounts =
        withContext(Dispatchers.IO) {
            json.decodeFromString<MailCountsResponse>(
                api.postJson(
                    "/snooze",
                    json.encodeToString(MailSnoozeRequest(emailIds, untilMs, grouped)),
                ),
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
        bcc: List<String>? = null,
        subject: String? = null,
        identityId: Int? = null,
        scheduledMs: Long? = null,
    ): MailActionResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(
            api.postJson("/reply", json.encodeToString(
                MailReplyRequest(emailId, mode, body, to, cc, attachmentIds,
                                 clientToken, bodyIsHtml, bcc, subject,
                                 identityId, scheduledMs))),
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
        bcc: List<String>? = null,
        identityId: Int? = null,
        resModel: String? = null,
        resId: Int? = null,
        scheduledMs: Long? = null,
    ): MailActionResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(
            api.postJson("/compose", json.encodeToString(
                MailComposeRequest(to, subject, body, cc, attachmentIds,
                                   clientToken, bodyIsHtml, bcc, identityId,
                                   resModel, resId, scheduledMs))),
        )
    }

    /**
     * Ce qu'une réponse enverrait : destinataires, objet, adresse d'envoi
     * (#25764). Lu à l'ouverture du composeur, jamais à l'envoi : le serveur
     * ne crée rien pour y répondre.
     */
    suspend fun prepareReply(emailId: Int, mode: String): ReplyPrepareResponse =
        withContext(Dispatchers.IO) {
            json.decodeFromString(
                api.get("/reply/prepare?email_id=$emailId&mode=${enc(mode)}"),
            )
        }

    /** Mes envois programmés, du plus proche au plus lointain. */
    suspend fun scheduled(limit: Int = 25): ScheduledMailsResponse =
        withContext(Dispatchers.IO) {
            json.decodeFromString(api.get("/scheduled?limit=$limit"))
        }

    /** Retenir un envoi programmé : il redevient un brouillon du poste. */
    suspend fun unschedule(id: Int): UnscheduleResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(
            api.postJson("/scheduled/unschedule", json.encodeToString(ScheduledRefRequest(id))),
        )
    }

    // ---- brouillons du poste (#25579) ----

    /**
     * Les brouillons de bf_email, pas ceux de l'appareil.
     *
     * Le serveur n'en rend que les VRAIS : un envoi différé part de lui-même
     * à sa date et reste au poste, une note interne ne sort jamais par
     * courriel.
     */
    suspend fun serverDrafts(offset: Int = 0, limit: Int = 25, search: String = ""):
        ServerDraftsResponse = withContext(Dispatchers.IO) {
        val sb = StringBuilder("/drafts?offset=$offset&limit=$limit")
        if (search.isNotBlank()) sb.append("&search=").append(enc(search))
        json.decodeFromString(api.get(sb.toString()))
    }

    /** Un brouillon au complet : les deux corps et les pièces jointes. */
    suspend fun serverDraft(id: Int): ServerDraft = withContext(Dispatchers.IO) {
        json.decodeFromString(api.get("/draft?id=$id"))
    }

    /**
     * Réécrire, en n'envoyant que ce qui a changé.
     *
     * Un paramètre laissé à `null` ne voyage pas et n'est donc pas touché
     * côté serveur. `version` porte ce qu'on a lu : si le poste a modifié le
     * brouillon depuis, la réponse revient en conflit et rien n'est écrit.
     */
    suspend fun saveServerDraft(
        id: Int,
        version: String? = null,
        subject: String? = null,
        body: String? = null,
        bodyIsHtml: Boolean = false,
        to: List<String>? = null,
        attachmentIds: List<Int>? = null,
    ): ServerDraftSaveResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(
            api.postJson("/draft/save", json.encodeToString(
                ServerDraftSaveRequest(id, version, subject, body, bodyIsHtml,
                                       to, attachmentIds))),
        )
    }

    suspend fun sendServerDraft(id: Int, version: String? = null):
        ServerDraftSaveResponse = withContext(Dispatchers.IO) {
        json.decodeFromString(
            api.postJson("/draft/send",
                         json.encodeToString(ServerDraftRefRequest(id, version))),
        )
    }

    suspend fun deleteServerDraft(id: Int): ServerDraftSaveResponse =
        withContext(Dispatchers.IO) {
            json.decodeFromString(
                api.postJson("/draft/delete",
                             json.encodeToString(ServerDraftRefRequest(id))),
            )
        }

    // ---- Odoo-side actions ----

    suspend fun records(model: String, query: String): List<RecordRef> =
        withContext(Dispatchers.IO) {
            json.decodeFromString<MailRecordsResponse>(
                api.get("/records?model=${enc(model)}&q=${enc(query)}"),
            ).records
        }

    /**
     * [groups] ajoute les groupes de destinataires, dépliés (napkin #25278) :
     * seulement quand l'instance les a en service, un client qui ne sait pas
     * les lire afficherait sinon des contacts sans adresse.
     */
    suspend fun contacts(query: String, groups: Boolean = false): List<MailContact> =
        withContext(Dispatchers.IO) {
            val suffixe = if (groups) "&groups=1" else ""
            json.decodeFromString<MailContactsResponse>(
                api.get("/contacts?q=${enc(query)}$suffixe"),
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
            // 🔴 Tout ce que le composeur avait, et pas seulement le texte :
            // jusqu'à la 2.43, un envoi mis en file hors ligne repartait sans
            // ses pièces jointes, sans les Cc choisis en pastilles, et avec
            // ses `**` en clair (#25764).
            PendingAction.KIND_REPLY -> reply(
                emailId = action.emailId, mode = action.mode, body = action.body,
                to = action.to, cc = action.cc, clientToken = action.token,
                attachmentIds = action.attachments.map { it.attachmentId }.ifEmpty { null },
                bodyIsHtml = action.bodyIsHtml,
                bcc = action.bcc,
                subject = action.subject.ifBlank { null },
                identityId = action.identityId,
                scheduledMs = action.scheduledMs,
            )
            PendingAction.KIND_COMPOSE -> compose(
                to = action.to.orEmpty(), subject = action.subject,
                body = action.body, cc = action.cc, clientToken = action.token,
                attachmentIds = action.attachments.map { it.attachmentId }.ifEmpty { null },
                bodyIsHtml = action.bodyIsHtml,
                bcc = action.bcc,
                identityId = action.identityId,
                resModel = action.resModel,
                resId = action.resId,
                scheduledMs = action.scheduledMs,
            )
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching { api.postJson("/logout", "{}") }
        Unit
    }
}
