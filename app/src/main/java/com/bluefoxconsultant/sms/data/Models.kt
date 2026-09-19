package com.bluefoxconsultant.sms.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText

@Serializable
data class Line(
    val id: Int = 0,
    val label: String = "",
    val did: String = "",
    @SerialName("sms_enabled") val smsEnabled: Boolean = true,
    @SerialName("mms_enabled") val mmsEnabled: Boolean = false,
    @SerialName("is_default") val isDefault: Boolean = false,
)

@Serializable
data class AppConfig(
    val tz: String? = null,
    @SerialName("vapid_public_key") val vapidPublicKey: String? = null,
)

@Serializable
data class LoginResponse(
    val token: String = "",
    @SerialName("user_id") val userId: Int = 0,
    @SerialName("user_name") val userName: String = "",
    val lines: List<Line> = emptyList(),
    val config: AppConfig = AppConfig(),
)

@Serializable
data class Thread(
    val id: Int = 0,
    @SerialName("contact_name") val contactName: String = "",
    val phone: String = "",
    @SerialName("phone_raw") val phoneRaw: String = "",
    @SerialName("partner_id")
    @Serializable(with = FalseAsNullIntSerializer::class)
    val partnerId: Int? = null,
    @SerialName("partner_name") val partnerName: String = "",
    @SerialName("last_message_date")
    @Serializable(with = FalseAsNullLongSerializer::class)
    val lastMessageDate: Long? = null,
    @SerialName("last_preview") val lastPreview: String = "",
    @SerialName("unread_count") val unreadCount: Int = 0,
    val active: Boolean = true,
    @SerialName("is_hidden") val isHidden: Boolean = false,
    @SerialName("is_pinned") val isPinned: Boolean = false,
    @SerialName("line_label") val lineLabel: String = "",
) {
    /**
     * Le nom du correspondant tel que le serveur le connaît, sinon son numéro.
     * Sans l'un ni l'autre, « Inconnu » dans la langue du téléphone : c'est
     * l'écran qui le résout.
     */
    val displayName: UiText
        get() = contactName.ifBlank { partnerName.ifBlank { phone } }
            .takeIf { it.isNotBlank() }
            ?.let { UiText.Raw(it) }
            ?: UiText.Res(R.string.common_unknown)
}

@Serializable
data class Media(
    val filename: String = "",
    @SerialName("content_type") val contentType: String = "",
    @SerialName("is_image") val isImage: Boolean = false,
    val url: String? = null,
    val text: String? = null,
)

@Serializable
data class Message(
    val id: Int = 0,
    val direction: String = "in",
    val body: String = "",
    @SerialName("date_ms") val dateMs: Long = 0,
    @SerialName("is_mms") val isMms: Boolean = false,
    @SerialName("delivery_state") val deliveryState: String = "",
    val error: String = "",
    val media: List<Media> = emptyList(),
) {
    val isOutgoing: Boolean get() = direction == "out"
}

@Serializable
data class ThreadsResponse(val threads: List<Thread> = emptyList())

@Serializable
data class ConfigResponse(
    @SerialName("user_name") val userName: String = "",
    val lines: List<Line> = emptyList(),
    val config: AppConfig = AppConfig(),
)

@Serializable
data class Contact(
    val id: Int = 0,
    val name: String = "",
    val phone: String = "",
    val mobile: String = "",
) {
    /** Best number to send to: prefer mobile, fall back to phone. */
    val bestNumber: String get() = mobile.ifBlank { phone }
}

@Serializable
data class ContactsResponse(val contacts: List<Contact> = emptyList())

@Serializable
data class PinResponse(
    val ok: Boolean = false,
    @SerialName("is_pinned") val isPinned: Boolean = false,
)

@Serializable
data class ConversationResponse(
    val thread: Thread = Thread(),
    val messages: List<Message> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class SendResponse(
    val ok: Boolean = false,
    @SerialName("thread_id") val threadId: Int = 0,
    val message: Message = Message(),
)

// ---- request bodies ----

@Serializable
data class ExchangeRequest(
    val code: String,
    // The server demands it since napkin #25275 lot A. An intercepted code is
    // worth nothing without it.
    @SerialName("code_verifier") val codeVerifier: String,
)

@Serializable
data class SendThreadRequest(
    @SerialName("thread_id") val threadId: Int,
    val body: String,
    // Omitted when null (explicitNulls = false), and the server then resolves
    // the line from the thread's last message — the historical behaviour.
    @SerialName("line_id") val lineId: Int? = null,
    // Absent = SMS. Présent = MMS : c'est le serveur qui bascule, l'app ne
    // choisit pas de canal.
    val media: List<SendMedia>? = null,
)

@Serializable
data class SendNewRequest(
    val phone: String,
    @SerialName("line_id") val lineId: Int,
    val body: String,
    val media: List<SendMedia>? = null,
)

/**
 * Une pièce jointe sortante, telle que `/send` l'attend.
 *
 * Base64 et non multipart : `action_send` en fait un `data:` URI pour
 * `sendMMS` chez VOIP.ms, donc l'encodage arriverait de toute façon — et les
 * plafonds (1 Mo la pièce, trois pièces) rendent le tiers de surcoût sans
 * conséquence ici, contrairement au courriel qui, lui, téléverse en multipart.
 */
@Serializable
data class SendMedia(
    val filename: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("data_b64") val dataB64: String,
)

@Serializable
data class MarkReadRequest(
    @SerialName("thread_id") val threadId: Int,
)

@Serializable
data class ThreadArchiveRequest(
    @SerialName("thread_id") val threadId: Int,
    val archived: Boolean,
)

@Serializable
data class ThreadPinRequest(
    @SerialName("thread_id") val threadId: Int,
)

/**
 * L'inscription d'un endpoint, avec les clés WebPush de l'appareil.
 *
 * [p256dh] est la clé publique P-256 non compressée (65 octets) et [auth] le
 * secret de 16 octets, les deux en base64url sans remplissage, tels que le
 * connecteur UnifiedPush les donne. Absents (`null`, donc omis du JSON) quand
 * le connecteur n'a pas pu en produire : le serveur pousse alors en clair,
 * comme avant la 2.42.0. Un serveur ancien les ignore de toute façon.
 */
@Serializable
data class RegisterPushRequest(
    val endpoint: String,
    @SerialName("app_version") val appVersion: String,
    val p256dh: String? = null,
    val auth: String? = null,
)

/**
 * Ce que `/register_push` répond.
 *
 * [webpushTypes] : les types que CE serveur chiffre toujours désormais. Un
 * serveur ancien ne répond que `{"ok": true}` : ni l'un ni l'autre, et tout ce
 * qu'il pousse arrive en clair.
 */
@Serializable
data class RegisterPushResponse(
    val ok: Boolean = false,
    val webpush: Boolean = false,
    @SerialName("webpush_types") val webpushTypes: List<String> = emptyList(),
)
