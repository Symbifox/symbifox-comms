package com.bluefoxconsultant.sms.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for `bf_email_management`'s mobile API. Contract:
 * `MOBILE_API.md` in the Odoo module.
 *
 * Odoo renders empty many2ones as `false` rather than `null`, so every id and
 * timestamp that can be absent goes through the `FalseAsNull*` serializers
 * already used by the SMS half.
 */

/** Mailbox filters the server accepts on `/threads`. */
enum class MailFilter(val key: String, val label: String) {
    INBOX("inbox", "Boîte de réception"),
    UNREAD("unread", "Non lus"),
    SNOOZED("snoozed", "Reportés"),
    HANDLED("handled", "Traités"),
    SENT("sent", "Envoyés"),
    UNROUTED("unrouted", "À router"),
    ALL("all", "Tous"),
}

@Serializable
data class MailAccount(
    val id: Int = 0,
    val name: String = "",
    val login: String = "",
    val aliases: String = "",
    val state: String = "",
)

@Serializable
data class MailCounts(
    val inbox: Int = 0,
    val unread: Int = 0,
    val snoozed: Int = 0,
    val unrouted: Int = 0,
)

@Serializable
data class SnoozePreset(
    val key: String = "",
    val label: String = "",
    @SerialName("until_ms") val untilMs: Long = 0,
)

@Serializable
data class RoutableModel(
    val model: String = "",
    val label: String = "",
)

/** Brand identity reported by the connected instance. */
@Serializable
data class Branding(
    val name: String = "",
    val primary: String? = null,
    val dark: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
)

@Serializable
data class PingResponse(
    val ok: Boolean = false,
    val module: String = "",
    val version: String = "",
    val branding: Branding? = null,
)

@Serializable
data class MailConfig(
    @SerialName("user_name") val userName: String = "",
    val tz: String = "",
    val signature: String = "",
    val accounts: List<MailAccount> = emptyList(),
    val counts: MailCounts = MailCounts(),
    @SerialName("snooze_presets") val snoozePresets: List<SnoozePreset> = emptyList(),
    @SerialName("routable_models") val routableModels: List<RoutableModel> = emptyList(),
    @SerialName("spawn_kinds") val spawnKinds: List<String> = emptyList(),
    val branding: Branding? = null,
)

@Serializable
data class MailExchangeResponse(
    val token: String = "",
    @SerialName("user_id") val userId: Int = 0,
    val config: MailConfig = MailConfig(),
)

@Serializable
data class RecordRef(
    val model: String = "",
    val id: Int = 0,
    val name: String = "",
)

@Serializable
data class MailAttachment(
    val idx: Int = 0,
    val name: String = "",
    val mimetype: String = "",
    val size: Long = 0,
)

/**
 * One message. Doubles as a thread-list row: `/threads` returns the newest
 * message of each conversation with the aggregate fields filled in, so the
 * list and the thread render from the same type.
 */
@Serializable
data class MailMessage(
    val id: Int = 0,
    @SerialName("thread_key") val threadKey: String = "",
    val direction: String = "in",
    val subject: String = "",
    val from: String = "",
    @SerialName("from_label") val fromLabel: String = "",
    @SerialName("date_ms")
    @Serializable(with = FalseAsNullLongSerializer::class)
    val dateMs: Long? = null,
    val preview: String = "",
    val status: String = "new",
    @SerialName("is_handled") val isHandled: Boolean = false,
    @SerialName("snoozed_until_ms")
    @Serializable(with = FalseAsNullLongSerializer::class)
    val snoozedUntilMs: Long? = null,
    val category: String = "",
    val priority: String = "0",
    @SerialName("has_attachments") val hasAttachments: Boolean = false,
    @SerialName("attachment_count") val attachmentCount: Int = 0,
    @SerialName("partner_id")
    @Serializable(with = FalseAsNullIntSerializer::class)
    val partnerId: Int? = null,
    @SerialName("partner_name") val partnerName: String = "",
    @SerialName("account_id")
    @Serializable(with = FalseAsNullIntSerializer::class)
    val accountId: Int? = null,
    @Serializable(with = FalseAsNullRecordSerializer::class)
    val record: RecordRef? = null,
    @SerialName("is_question") val isQuestion: Boolean = false,
    @SerialName("is_action_request") val isActionRequest: Boolean = false,

    // Thread-list aggregates (absent on a plain message).
    @SerialName("last_id") val lastId: Int = 0,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("last_date_ms")
    @Serializable(with = FalseAsNullLongSerializer::class)
    val lastDateMs: Long? = null,

    // Full payloads only.
    val to: String = "",
    val cc: String = "",
    @SerialName("body_html") val bodyHtml: String? = null,
    @SerialName("blocked_images") val blockedImages: Int = 0,
    val attachments: List<MailAttachment> = emptyList(),
    @SerialName("message_id_header") val messageIdHeader: String = "",
) {
    val isOutgoing: Boolean get() = direction == "out"
    val isUnread: Boolean get() = status == "new" && !isOutgoing
    /** True once the server has sent the body — the marker for "already fetched". */
    val isFull: Boolean get() = bodyHtml != null
    val displaySubject: String get() = subject.ifBlank { "(sans objet)" }
    val correspondent: String
        get() = partnerName.ifBlank { fromLabel.ifBlank { from.ifBlank { "Inconnu" } } }
    val sortDate: Long get() = lastDateMs ?: dateMs ?: 0
}

@Serializable
data class MailThreadsResponse(
    val threads: List<MailMessage> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class MailConversationResponse(
    @SerialName("thread_key") val threadKey: String = "",
    val subject: String = "",
    val messages: List<MailMessage> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
data class MailCountsResponse(
    val ok: Boolean = false,
    val counts: MailCounts = MailCounts(),
)

@Serializable
data class MailActionResponse(
    val ok: Boolean = false,
    @Serializable(with = FalseAsNullRecordSerializer::class)
    val record: RecordRef? = null,
    @SerialName("email_id") val emailId: Int = 0,
    @SerialName("thread_key") val threadKey: String = "",
)

/** A file staged server-side, waiting for a send to claim it. */
@Serializable
data class StagedUpload(
    val ok: Boolean = false,
    @SerialName("attachment_id") val attachmentId: Int = 0,
    val name: String = "",
    val size: Long = 0,
    val mimetype: String = "",
)

@Serializable
data class MailRecordsResponse(val records: List<RecordRef> = emptyList())

@Serializable
data class MailContact(
    val id: Int = 0,
    val name: String = "",
    val email: String = "",
    val company: String = "",
) {
    /** What actually goes in the header. */
    val address: String get() = email
    val subtitle: String get() = listOf(email, company).filter { it.isNotBlank() }
        .joinToString(" · ")
}

@Serializable
data class MailContactsResponse(val contacts: List<MailContact> = emptyList())

// ---- request bodies ----

@Serializable
data class MailIdsRequest(
    @SerialName("email_ids") val emailIds: List<Int>,
)

@Serializable
data class MailHandleRequest(
    @SerialName("email_ids") val emailIds: List<Int>,
    val handled: Boolean,
)

@Serializable
data class MailSnoozeRequest(
    @SerialName("email_ids") val emailIds: List<Int>,
    @SerialName("until_ms") val untilMs: Long,
)

@Serializable
data class MailReplyRequest(
    @SerialName("email_id") val emailId: Int,
    val mode: String,
    val body: String,
    val to: List<String>? = null,
    val cc: List<String>? = null,
    @SerialName("attachment_ids") val attachmentIds: List<Int>? = null,
    @SerialName("client_token") val clientToken: String? = null,
    @SerialName("body_is_html") val bodyIsHtml: Boolean = false,
)

@Serializable
data class MailComposeRequest(
    val to: List<String>,
    val subject: String,
    val body: String,
    val cc: List<String>? = null,
    @SerialName("attachment_ids") val attachmentIds: List<Int>? = null,
    @SerialName("client_token") val clientToken: String? = null,
    @SerialName("body_is_html") val bodyIsHtml: Boolean = false,
)

@Serializable
data class MailRouteRequest(
    @SerialName("email_id") val emailId: Int,
    @SerialName("res_model") val resModel: String,
    @SerialName("res_id") val resId: Int,
)

@Serializable
data class MailSpawnRequest(
    @SerialName("email_id") val emailId: Int,
    val kind: String,
)
