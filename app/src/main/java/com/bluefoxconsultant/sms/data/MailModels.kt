package com.bluefoxconsultant.sms.data

import androidx.annotation.StringRes
import com.bluefoxconsultant.sms.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiText

/**
 * Wire types for `bf_email_management`'s mobile API. Contract:
 * `MOBILE_API.md` in the Odoo module.
 *
 * Odoo renders empty many2ones as `false` rather than `null`, so every id and
 * timestamp that can be absent goes through the `FalseAsNull*` serializers
 * already used by the SMS half.
 */

/** Mailbox filters the server accepts on `/threads`. */
enum class MailFilter(val key: String, @StringRes val labelRes: Int) {
    INBOX("inbox", R.string.mail_filter_inbox),
    UNREAD("unread", R.string.mail_filter_unread),
    SNOOZED("snoozed", R.string.mail_filter_snoozed),
    HANDLED("handled", R.string.mail_filter_handled),
    SENT("sent", R.string.mail_filter_sent),
    UNROUTED("unrouted", R.string.mail_filter_unrouted),
    ALL("all", R.string.mail_filter_all),

    /**
     * ⚠️ Le seul filtre qui ne part JAMAIS au serveur : `/threads` n'en connaît
     * pas la clé, et toute nouvelle lecture doit donc l'écarter avant de
     * composer une URL — sinon le serveur répond « Filtre inconnu ».
     *
     * Ce qui ne veut plus dire que le serveur ignore les brouillons : depuis
     * le 2026-08-31 il en garde, et depuis le 2026-09-11 il les sert par
     * `/drafts`, qui est une route à part (#25579). La section en montre donc
     * DEUX piles — celle de l'appareil et celle du poste — sans que ce filtre
     * ait à voyager pour autant.
     */
    DRAFTS("drafts", R.string.mail_filter_drafts),
}

@Serializable
data class MailAccount(
    val id: Int = 0,
    val name: String = "",
    val login: String = "",
    val aliases: String = "",
    val state: String = "",
    /**
     * La couleur de l'avis du compte au bureau, en `#RRGGBB` (bf_email_management
     * 18.0.11.36.1, #25734). Vide quand aucune n'est choisie, ou avec un serveur
     * plus ancien : l'app en attribue une, voir `couleursDesComptes`.
     */
    val color: String = "",
)

@Serializable
data class MailCounts(
    val inbox: Int = 0,
    /** Non-lus non traités, réception ou pas : ce que la section « Non lus » liste. */
    val unread: Int = 0,
    val snoozed: Int = 0,
    val unrouted: Int = 0,
    /**
     * Non-lus de la boîte de réception seulement — ce que la pastille montre
     * (#25717). `null` sur un serveur qui ne le rend pas encore.
     */
    @SerialName("inbox_unread") val inboxUnread: Int? = null,
    /**
     * Les mêmes totaux, boîte par boîte (clé : l'identifiant du compte en
     * texte). Vide avec un serveur qui ne les rend pas (#25734).
     */
    @SerialName("by_account") val byAccount: Map<String, MailAccountCounts> = emptyMap(),
) {
    /**
     * Les totaux à afficher au-dessus d'une liste filtrée sur [accountId].
     *
     * ⚠️ Seulement pour les pastilles des sections : celle de l'ONGLET compte
     * toutes les boîtes, filtre ou pas. Sans les totaux du compte (serveur
     * ancien), on garde les totaux globaux plutôt que d'afficher zéro.
     */
    fun pourCompte(accountId: Int?): MailCounts {
        if (accountId == null) return this
        val c = byAccount[accountId.toString()] ?: return this
        return MailCounts(c.inbox, c.unread, c.snoozed, c.unrouted, c.inboxUnread)
    }
}

@Serializable
data class MailAccountCounts(
    val inbox: Int = 0,
    val unread: Int = 0,
    val snoozed: Int = 0,
    val unrouted: Int = 0,
    @SerialName("inbox_unread") val inboxUnread: Int? = null,
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
    // Faux par défaut : une instance trop vieille ne renvoie pas la clé, et la
    // section ne doit alors afficher que les brouillons de l'appareil plutôt
    // que d'échouer sur une route absente.
    @SerialName("server_drafts") val serverDrafts: Boolean = false,
    /**
     * Ce que le composeur du serveur sait faire (#25764). 2 : réponse
     * préparée, adresse d'envoi, Cci, objet d'une réponse, envoi programmé.
     * 0 avec une instance plus ancienne, qui garde le composeur de la 2.43.
     */
    @SerialName("compose_api") val composeApi: Int = 0,
    /** Les adresses sous lesquelles la personne peut écrire. */
    val identities: List<MailIdentity> = emptyList(),
    /** Les groupes de destinataires sont-ils en service sur l'instance ? */
    @SerialName("recipient_groups") val recipientGroups: Boolean = false,
) {
    val composeurComplet: Boolean get() = composeApi >= 2
}

/**
 * Une adresse d'envoi vérifiée, la même que le « De » du poste (#25764).
 *
 * [signatureText] est la signature que l'envoi posera, en texte : le
 * téléphone la montre sous le message pour qu'on sache comment il partira.
 */
@Serializable
data class MailIdentity(
    val id: Int = 0,
    val name: String = "",
    val email: String = "",
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("account_id")
    @Serializable(with = FalseAsNullIntSerializer::class)
    val accountId: Int? = null,
    @SerialName("signature_text") val signatureText: String = "",
)

/** Un destinataire tel que le serveur le prépare : un nom et une adresse. */
@Serializable
data class MailAddress(
    val name: String = "",
    val email: String = "",
) {
    /** La forme que l'app garde en pastille et renvoie au serveur. */
    val enPastille: String get() = Adresses.formater(name, email)
}

/**
 * Ce qu'une réponse enverrait, lu AVANT d'écrire (`GET /reply/prepare`).
 *
 * Le serveur la calculait à l'envoi, sans rien montrer ; l'app la montre et
 * renvoie la liste retouchée.
 */
@Serializable
data class ReplyPrepareResponse(
    val mode: String = "",
    val to: List<MailAddress> = emptyList(),
    val cc: List<MailAddress> = emptyList(),
    val subject: String = "",
    @SerialName("identity_id")
    @Serializable(with = FalseAsNullIntSerializer::class)
    val identityId: Int? = null,
    @Serializable(with = FalseAsNullRecordSerializer::class)
    val record: RecordRef? = null,
)

/** Un envoi programmé qui n'est pas encore parti (#25764). */
@Serializable
data class ScheduledMail(
    val id: Int = 0,
    val subject: String = "",
    @SerialName("to_display") val toDisplay: String = "",
    @SerialName("cc_display") val ccDisplay: String = "",
    val preview: String = "",
    @SerialName("scheduled_ms") val scheduledMs: Long = 0,
    @Serializable(with = FalseAsNullRecordSerializer::class)
    val record: RecordRef? = null,
) {
    val label: UiText
        get() = subject.ifBlank { preview.take(80) }
            .let { if (it.isBlank()) uiText(R.string.common_no_subject) else UiText.Raw(it) }

    val recipients: UiText
        get() = if (toDisplay.isBlank()) uiText(R.string.common_no_recipient) else UiText.Raw(toDisplay)
}

@Serializable
data class ScheduledMailsResponse(
    val scheduled: List<ScheduledMail> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class UnscheduleResponse(
    val ok: Boolean = false,
    @SerialName("draft_id") val draftId: Int = 0,
)

/**
 * Les adresses en pastille : `"Nom" <adresse>` ou l'adresse seule.
 *
 * ⚠️ Pas d'encodage RFC 2047 : un nom accentué voyage tel quel, et le serveur
 * le relit par `parseaddr`. Encodé, le contact créé à l'envoi s'appellerait
 * `=?utf-8?b?…?=` (piège déjà rencontré au poste, voir `split_address_list`).
 */
object Adresses {
    private val AVEC_NOM = Regex("""^\s*"?(.*?)"?\s*<([^<>]+)>\s*$""")

    fun formater(nom: String, adresse: String): String {
        val n = nom.trim()
        val a = adresse.trim()
        if (n.isEmpty() || n.equals(a, ignoreCase = true)) return a
        val echappe = n.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$echappe\" <$a>"
    }

    /** (nom, adresse) d'une pastille ; le nom est vide pour une adresse seule. */
    fun decouper(pastille: String): Pair<String, String> {
        val m = AVEC_NOM.matchEntire(pastille) ?: return "" to pastille.trim()
        val nom = m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\").trim()
        return nom to m.groupValues[2].trim()
    }

    /** Ce qu'on lit sur la pastille : le nom s'il y en a un. */
    fun libelle(pastille: String): String = decouper(pastille).let { (n, a) -> n.ifBlank { a } }

    /** L'adresse seule, en minuscules, pour comparer deux pastilles. */
    fun cle(pastille: String): String = decouper(pastille).second.lowercase()
}

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
    /**
     * L'objet et le correspondant tels quels quand le serveur les donne ; à
     * défaut, un repli dans la langue du téléphone, d'où un [UiText] et non
     * une chaîne.
     */
    val displaySubject: UiText
        get() = if (subject.isBlank()) uiText(R.string.common_no_subject) else UiText.Raw(subject)
    val correspondent: UiText
        get() = partnerName.ifBlank { fromLabel.ifBlank { from } }
            .let { if (it.isBlank()) uiText(R.string.common_unknown) else UiText.Raw(it) }
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
    /** Vrai quand l'envoi a été programmé plutôt qu'envoyé (#25764). */
    val scheduled: Boolean = false,
    @SerialName("scheduled_ms") val scheduledMs: Long = 0,
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

/**
 * Un brouillon écrit AU POSTE, dans bf_email (#25579).
 *
 * Distinct d'un [MailDraft], et les deux vivent côte à côte dans la même
 * section « Brouillons » : celui-ci est un `mail.scheduled.message` sur le
 * serveur, accroché à une fiche Odoo, et le téléphone le lit, le modifie et
 * l'envoie à distance. Le [MailDraft], lui, n'existe que sur l'appareil.
 *
 * [version] est un jeton d'aller-retour, pas une date à afficher : rendu par
 * le serveur, renvoyé à l'écriture, et comparé avant d'écrire. C'est ce qui
 * fait qu'une modification faite au poste entretemps n'est pas écrasée.
 */
@Serializable
data class ServerDraft(
    val id: Int = 0,
    val subject: String = "",
    val to: List<String> = emptyList(),
    @SerialName("to_display") val toDisplay: String = "",
    @SerialName("cc_display") val ccDisplay: String = "",
    val preview: String = "",
    @Serializable(with = FalseAsNullRecordSerializer::class)
    val record: RecordRef? = null,
    @SerialName("saved_ms") val savedMs: Long = 0,
    val version: String = "",
    val attachments: List<ServerDraftAttachment> = emptyList(),
    // Absents de la liste, présents sur la fiche complète.
    @SerialName("body_html") val bodyHtml: String = "",
    @SerialName("body_text") val bodyText: String = "",
) {
    /** Ce qui tient lieu de titre, à défaut d'objet. */
    val label: UiText
        get() = subject.ifBlank {
            preview.take(80)
        }.let { if (it.isBlank()) uiText(R.string.common_no_subject) else UiText.Raw(it) }

    val recipients: UiText
        get() = if (toDisplay.isBlank()) uiText(R.string.common_no_recipient) else UiText.Raw(toDisplay)
}

@Serializable
data class ServerDraftAttachment(
    val id: Int = 0,
    val name: String = "",
    val size: Long = 0,
    val mimetype: String = "",
)

@Serializable
data class ServerDraftsResponse(
    val drafts: List<ServerDraft> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

/**
 * L'issue d'une écriture ou d'un envoi.
 *
 * [conflict] arrive en HTTP 200, pas en 409 : le client lit l'issue dans la
 * charge utile, comme pour un envoi en double. [draft] porte alors ce que le
 * SERVEUR contient, pour que la personne tranche en voyant les deux textes
 * plutôt qu'en devinant.
 */
@Serializable
data class ServerDraftSaveResponse(
    val ok: Boolean = false,
    val conflict: Boolean = false,
    val draft: ServerDraft? = null,
)

@Serializable
data class MailRecordsResponse(val records: List<RecordRef> = emptyList())

@Serializable
data class MailContact(
    val id: Int = 0,
    val name: String = "",
    val email: String = "",
    val company: String = "",
    /**
     * Un groupe de destinataires (`/contacts?groups=1`) : pas d'adresse à lui,
     * ses [members] déjà dépliés, et le champ où le groupe range ses membres
     * ([field] : `to`, `cc` ou `bcc`), comme au poste.
     */
    @SerialName("is_group") val isGroup: Boolean = false,
    val field: String = "",
    val members: List<MailAddress> = emptyList(),
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
    // Le repli affiché, pour que les totaux renvoyés comptent des LIGNES et
    // non des messages. Voir MailRepository.counts.
    val grouped: Boolean = true,
)

@Serializable
data class MailHandleRequest(
    @SerialName("email_ids") val emailIds: List<Int>,
    val handled: Boolean,
    val grouped: Boolean = true,
)

@Serializable
data class MailSnoozeRequest(
    @SerialName("email_ids") val emailIds: List<Int>,
    @SerialName("until_ms") val untilMs: Long,
    val grouped: Boolean = true,
)

/**
 * ⚠️ Les champs de #25764 (`bcc`, `subject`, `identity_id`, `scheduled_ms`)
 * sont nuls par défaut, et `explicitNulls = false` les retire du JSON : un
 * serveur plus ancien ne reçoit rien qu'il ne connaisse.
 */
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
    val bcc: List<String>? = null,
    val subject: String? = null,
    @SerialName("identity_id") val identityId: Int? = null,
    @SerialName("scheduled_ms") val scheduledMs: Long? = null,
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
    val bcc: List<String>? = null,
    @SerialName("identity_id") val identityId: Int? = null,
    @SerialName("res_model") val resModel: String? = null,
    @SerialName("res_id") val resId: Int? = null,
    @SerialName("scheduled_ms") val scheduledMs: Long? = null,
)

@Serializable
data class ScheduledRefRequest(val id: Int)

/**
 * Écriture PARTIELLE d'un brouillon du poste.
 *
 * ⚠️ Tout est nullable, et `explicitNulls = false` retire du JSON les clés
 * nulles : le serveur ne touche donc pas à ce que l'app n'envoie pas. C'est ce
 * qui permet de corriger un objet au téléphone sans réécrire un corps HTML
 * qu'on aurait dû aplatir en texte pour l'afficher, ni perdre une copie
 * conforme que `mail.scheduled.message` ne sait même pas stocker en propre.
 */
@Serializable
data class ServerDraftSaveRequest(
    val id: Int,
    val version: String? = null,
    val subject: String? = null,
    val body: String? = null,
    @SerialName("body_is_html") val bodyIsHtml: Boolean = false,
    val to: List<String>? = null,
    @SerialName("attachment_ids") val attachmentIds: List<Int>? = null,
)

@Serializable
data class ServerDraftRefRequest(
    val id: Int,
    val version: String? = null,
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
