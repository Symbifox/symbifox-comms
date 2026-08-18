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
) {
    val isEmpty: Boolean
        get() = to.isEmpty() && cc.isEmpty() && subject.isBlank() &&
            body.isBlank() && attachments.isEmpty()

    /** Ce qui tient lieu de titre dans la liste, à défaut d'objet. */
    val label: String
        get() = subject.ifBlank {
            body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80).orEmpty()
        }.ifBlank { "(sans objet)" }

    val recipients: String
        get() = to.joinToString(", ").ifBlank { "(sans destinataire)" }

    /** Ce qu'on écrit sous le titre : une réponse n'est pas un message neuf. */
    val kindLabel: String
        get() = when (mode) {
            "reply" -> "Réponse"
            "reply_all" -> "Réponse à tous"
            "forward" -> "Transfert"
            else -> "Nouveau"
        }
}

/**
 * Les brouillons, sur l'appareil.
 *
 * ⚠️ Locaux et rien d'autre : le serveur n'a pas de notion de brouillon —
 * `bf_email` ne connaît que du reçu et de l'envoyé. Un brouillon écrit ici ne
 * remonte donc pas au bureau, et c'est assumé : ce qu'il remplace, c'est un
 * texte perdu parce qu'on a quitté l'écran, pas un dossier IMAP.
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

    /**
     * Enregistre, ou efface si le composeur a été vidé.
     *
     * Vider un brouillon puis quitter veut dire « laisse tomber » ; garder une
     * coquille vide dans la liste ferait de chaque écran de composition ouvert
     * par erreur une ligne à supprimer à la main.
     */
    @Synchronized
    fun save(draft: MailDraft): MailDraft? {
        if (draft.isEmpty) {
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
