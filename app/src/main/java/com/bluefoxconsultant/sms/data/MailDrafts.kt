package com.bluefoxconsultant.sms.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiText

/**
 * Un courriel commencé et pas envoyé.
 *
 * Distinct d'une [PendingAction] de la file : celle-là est un envoi DÉCIDÉ que
 * le réseau retient, celui-ci est un texte qu'on n'a pas fini d'écrire. Les
 * deux ne se réconcilient pas — rejouer un brouillon l'enverrait sans que
 * personne ne l'ait demandé.
 */
@Serializable
data class MailDraft(
    val id: String = UUID.randomUUID().toString(),
    /** Le mode du composeur : "new", "reply", "reply_all", "forward". */
    val mode: String = "new",
    /** Le courriel auquel on répond ; 0 pour un message neuf. */
    @SerialName("email_id") val emailId: Int = 0,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    /**
     * Les pièces déjà téléversées, gardées telles quelles.
     *
     * Elles vivent côté serveur dès qu'on les choisit : le brouillon n'a besoin
     * que de leur identifiant pour que l'envoi les réclame plus tard, et de
     * leur nom pour que le composeur les réaffiche.
     */
    val attachments: List<StagedUpload> = emptyList(),
    @SerialName("saved_ms") val savedMs: Long = 0,
    /**
     * L'identifiant du brouillon du POSTE dont celui-ci est la reprise, ou 0
     * quand le brouillon n'existe que sur l'appareil (#25579).
     *
     * ⚠️ C'est ce qui rend l'édition d'un brouillon serveur sûre. Quitter
     * l'écran écrit d'abord ICI, sans réseau : le composeur disparaît avec sa
     * portée de coroutines, et une écriture distante lancée au moment de
     * partir serait annulée en vol, ce qui perdrait le texte exactement comme
     * avant que cette section existe. La remontée au poste se fait ensuite,
     * depuis la liste, et se retente tant qu'elle n'a pas abouti.
     */
    @SerialName("server_id") val serverId: Int = 0,
    /** La version lue au poste, à renvoyer pour que le conflit se voie. */
    @SerialName("server_version") val serverVersion: String = "",
    /**
     * Vrai quand [body] est encore le texte du poste, jamais retouché ici.
     *
     * ⚠️ Ce qui en dépend : la remontée n'envoie PAS le corps dans ce cas. Le
     * composeur édite du texte, le brouillon du poste est du HTML, et
     * renvoyer l'aplatissement d'un corps que personne n'a touché
     * détruirait la mise en forme de quelqu'un qui n'a corrigé qu'un objet.
     */
    @SerialName("body_untouched") val bodyUntouched: Boolean = false,
    // ---- #25764 ----
    val bcc: List<String> = emptyList(),
    /** L'adresse d'envoi choisie ; `null` = celle que le serveur propose. */
    @SerialName("identity_id") val identityId: Int? = null,
    /**
     * Vrai quand les destinataires d'une réponse ont été préparés par le
     * serveur puis retouchés ici : [to] et [cc] partent alors tels quels. Faux
     * pour un brouillon d'avant, où une réponse sans destinataire voulait dire
     * « ceux du message d'origine ».
     */
    @SerialName("recipients_prepared") val recipientsPrepared: Boolean = false,
    /** La fiche où classer un message neuf, choisie au composeur. */
    @SerialName("res_model") val resModel: String? = null,
    @SerialName("res_id") val resId: Int? = null,
    @SerialName("record_name") val recordName: String = "",
) {
    /** Un texte qui doit encore remonter au poste. */
    val awaitingPush: Boolean get() = serverId > 0

    val isEmpty: Boolean
        get() = to.isEmpty() && cc.isEmpty() && bcc.isEmpty() && subject.isBlank() &&
            body.replace(com.bluefoxconsultant.sms.ui.mail.RichText.VIDE.toString(), "").isBlank() &&
            attachments.isEmpty()

    /** Ce qui tient lieu de titre dans la liste, à défaut d'objet. */
    val label: UiText
        get() = subject.ifBlank {
            body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80).orEmpty()
        }.let { if (it.isBlank()) uiText(R.string.common_no_subject) else UiText.Raw(it) }

    val recipients: UiText
        get() = to.joinToString(", ")
            .let { if (it.isBlank()) uiText(R.string.common_no_recipient) else UiText.Raw(it) }

    /** Ce qu'on écrit sous le titre : une réponse n'est pas un message neuf. */
    val kindLabel: UiText
        get() = uiText(
            when (mode) {
                "reply" -> R.string.mail_draft_kind_reply
                "reply_all" -> R.string.mail_draft_kind_reply_all
                "forward" -> R.string.mail_draft_kind_forward
                else -> R.string.mail_draft_kind_new
            },
        )
}

/**
 * Un envoi qui n'est pas parti — annulé pendant son délai, ou refusé par le
 * serveur — redevient le brouillon qu'il était, avec tout ce qu'il portait.
 *
 * 🔴 Avant #25764, un envoi de la file refusé par le serveur était retiré
 * avec « abandonné » : le texte écrit hors ligne disparaissait.
 */
fun brouillonDepuisEnvoi(action: PendingAction, id: String = action.token): MailDraft = MailDraft(
    id = id,
    mode = if (action.kind == PendingAction.KIND_COMPOSE) "new" else action.mode,
    emailId = action.emailId,
    to = action.to.orEmpty(),
    cc = action.cc.orEmpty(),
    bcc = action.bcc.orEmpty(),
    subject = action.subject,
    body = action.bodySource.ifEmpty { action.body },
    attachments = action.attachments,
    identityId = action.identityId,
    recipientsPrepared = action.recipientsPrepared,
    resModel = action.resModel,
    resId = action.resId,
    recordName = action.recordName,
)

/**
 * Les brouillons, sur l'appareil.
 *
 * Locaux, et ce n'est plus faute de mieux : ce qu'ils remplacent, c'est un
 * texte perdu parce qu'on a quitté l'écran. Un brouillon écrit ici ne remonte
 * pas au bureau tout seul, et c'est assumé.
 *
 * ⚠️ Ce commentaire a longtemps dit « le serveur n'a pas de notion de
 * brouillon ». C'était vrai le 2026-08-18 et faux depuis le 2026-08-31, où
 * `bf_email` a appris à en garder (#25125) : treize jours pendant lesquels
 * deux piles ont vécu côte à côte sans que personne ne bâtisse le pont, parce
 * qu'on lisait ici que le serveur ne savait pas faire. Le pont existe depuis
 * le 2026-09-11 (#25579) — voir [MailDraft.serverId] pour ce qui relie une
 * reprise locale au brouillon du poste dont elle vient.
 *
 * L'écriture passe par un fichier temporaire renommé, comme [MailOutbox] : une
 * app tuée en plein `writeText` laisserait sinon un JSON tronqué, et le
 * brouillon qu'on prétendait sauver emporterait tous les autres avec lui.
 */
class MailDrafts(private val file: File) {

    /** Point d'entrée de production ; le constructeur File est celui des tests. */
    constructor(context: Context) : this(File(context.filesDir, "mailcache/drafts.json"))

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val _drafts = MutableStateFlow(read())

    /** Les brouillons, du plus récent au plus ancien. */
    val drafts: StateFlow<List<MailDraft>> = _drafts.asStateFlow()

    val size: Int get() = _drafts.value.size

    fun get(id: String): MailDraft? = _drafts.value.firstOrNull { it.id == id }

    /** La reprise en attente d'un brouillon du poste, s'il y en a une. */
    fun forServer(serverId: Int): MailDraft? =
        _drafts.value.firstOrNull { it.serverId == serverId }

    /** Ce qui attend de remonter au poste, du plus ancien au plus récent. */
    fun pendingPushes(): List<MailDraft> =
        _drafts.value.filter { it.awaitingPush }.sortedBy { it.savedMs }

    /**
     * Enregistre, ou efface si le composeur a été vidé.
     *
     * Vider un brouillon puis quitter veut dire « laisse tomber » ; garder une
     * coquille vide dans la liste ferait de chaque écran de composition ouvert
     * par erreur une ligne à supprimer à la main.
     */
    @Synchronized
    fun save(draft: MailDraft): MailDraft? {
        // ⚠️ Sauf s'il reprend un brouillon du POSTE : vider celui-là est une
        // intention qui doit remonter, et jeter la reprise ici laisserait le
        // serveur porter un texte que la personne vient d'effacer.
        if (draft.isEmpty && !draft.awaitingPush) {
            delete(draft.id)
            return null
        }
        val stamped = draft.copy(savedMs = System.currentTimeMillis())
        write(listOf(stamped) + _drafts.value.filterNot { it.id == stamped.id })
        return stamped
    }

    @Synchronized
    fun delete(id: String) {
        if (id.isBlank()) return
        val rest = _drafts.value.filterNot { it.id == id }
        if (rest.size != _drafts.value.size) write(rest)
    }

    @Synchronized
    fun clear() = write(emptyList())

    private fun read(): List<MailDraft> = runCatching {
        if (!file.exists()) emptyList()
        else json.decodeFromString<List<MailDraft>>(file.readText())
            .sortedByDescending { it.savedMs }
    }.getOrDefault(emptyList())

    private fun write(drafts: List<MailDraft>) {
        val ordered = drafts.sortedByDescending { it.savedMs }
        _drafts.value = ordered
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "drafts.json.tmp")
            tmp.writeText(json.encodeToString(ordered))
            if (!tmp.renameTo(file)) file.writeText(json.encodeToString(ordered))
        }
    }
}
