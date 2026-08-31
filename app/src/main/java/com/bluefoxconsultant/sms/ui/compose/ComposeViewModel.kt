package com.bluefoxconsultant.sms.ui.compose

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Contact
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.Line
import com.bluefoxconsultant.sms.data.MediaPrep
import com.bluefoxconsultant.sms.data.OutgoingMedia
import com.bluefoxconsultant.sms.data.SendMedia
import com.bluefoxconsultant.sms.data.SharedContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ComposeViewModel : ViewModel() {

    val lines: List<Line> = Graph.tokenStore.lines

    var selectedLineId by mutableStateOf(
        lines.firstOrNull { it.isDefault }?.id ?: lines.firstOrNull()?.id ?: 0,
    )
        private set

    var recipient by mutableStateOf("")
        private set
    var recipientName by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<Contact>>(emptyList())
        private set
    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Pièces jointes déjà lues et mises au gabarit, prêtes à partir en MMS. */
    var attachments by mutableStateOf<List<OutgoingMedia>>(emptyList())
        private set
    /** Combien de fichiers sont encore en cours de lecture. */
    var preparing by mutableStateOf(0)
        private set

    private var searchJob: Job? = null

    /**
     * La ligne choisie sait-elle envoyer un MMS ?
     *
     * ⚠️ Le contrôle est ici et pas seulement au serveur : une ligne sans MMS
     * rendrait « Ligne non activée pour les MMS » APRÈS l'envoi, sur un écran
     * qui vient de se vider. Mieux vaut griser le bouton.
     */
    val lineDoesMms: Boolean
        get() = lines.firstOrNull { it.id == selectedLineId }?.mmsEnabled ?: false

    /**
     * Reste-t-il de la place ?
     *
     * ⚠️ Les jointes ET celles en cours de lecture. Trois appels lancés coup
     * sur coup — ce que fait un partage multiple — verraient tous
     * `attachments` encore vide et passeraient le contrôle ensemble.
     */
    val canAttachMore: Boolean
        get() = attachments.size + preparing < MediaPrep.MAX_PARTS

    fun selectLine(id: Int) {
        selectedLineId = id
    }

    /**
     * Reprend ce qu'une autre app vient de partager.
     *
     * Appelé par l'écran, une seule fois : [SharedContent] est consommé par
     * l'appelant, pas ici, pour que le composeur courriel puisse recevoir le
     * même partage si l'usager change d'avis dans le sélecteur.
     */
    fun adopt(context: Context, shared: SharedContent) {
        // Un objet de partage (page web, courriel transféré) n'a pas de place
        // dans un texto : il rejoint le corps, au-dessus du lien.
        val parts = listOf(shared.subject, shared.text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (parts.isNotEmpty()) message = parts.joinToString("\n")
        // ⚠️ Le surplus se DIT. Partager cinq photos d'un coup est banal ; un
        // MMS n'en porte que trois, et les deux qui restent disparaîtraient
        // sans un mot — l'usager croirait les avoir envoyées.
        val files = shared.uris.take(MediaPrep.MAX_PARTS)
        if (shared.uris.size > files.size) {
            error = "Un MMS ne porte que ${MediaPrep.MAX_PARTS} pièces : " +
                "${shared.uris.size - files.size} de plus ont été laissées de côté."
        }
        files.forEach { attach(context, it) }
    }

    fun onRecipientChange(text: String) {
        recipient = text
        recipientName = null
        searchJob?.cancel()
        val term = text.trim()
        if (term.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(250)
                try {
                    suggestions = Graph.sms.contacts(term)
                } catch (e: Exception) {
                    suggestions = emptyList()
                }
            }
        } else {
            suggestions = emptyList()
        }
    }

    fun pickContact(contact: Contact) {
        recipient = contact.bestNumber
        recipientName = contact.name
        suggestions = emptyList()
    }

    fun onMessageChange(text: String) {
        message = text
    }

    /**
     * Lit et met au gabarit tout de suite, plutôt qu'au moment de l'envoi.
     *
     * Deux raisons : la permission de lire l'URI partagée vaut pour cette
     * activité et pas pour toujours, et un fichier trop lourd doit se dire
     * pendant qu'il y a encore un écran pour le retirer — pas après un appui
     * sur « Envoyer ».
     */
    fun attach(context: Context, uri: Uri) {
        if (!canAttachMore) {
            error = "Maximum ${MediaPrep.MAX_PARTS} pièces jointes."
            return
        }
        preparing += 1
        viewModelScope.launch {
            try {
                val media = withContext(Dispatchers.IO) { MediaPrep.read(context, uri) }
                attachments = attachments + media
            } catch (e: MediaPrep.TooLarge) {
                error = "« ${e.name} » dépasse 1 Mo : le MMS ne le portera pas."
            } catch (e: Exception) {
                error = "Fichier illisible."
            } finally {
                preparing -= 1
            }
        }
    }

    fun removeAttachment(media: OutgoingMedia) {
        attachments = attachments.filterNot { it === media }
    }

    fun dismissError() {
        error = null
    }

    fun send(onSent: (Int) -> Unit) {
        val phone = recipient.trim()
        val body = message.trim()
        when {
            phone.isBlank() -> { error = "Entrez un destinataire."; return }
            // Une photo seule est un message complet : le corps n'est exigé
            // que lorsqu'il n'y a rien d'autre à envoyer.
            body.isBlank() && attachments.isEmpty() -> {
                error = "Entrez un message."; return
            }
            selectedLineId == 0 -> { error = "Aucune ligne disponible."; return }
            attachments.isNotEmpty() && !lineDoesMms -> {
                error = "Cette ligne n'envoie pas de MMS."; return
            }
            preparing > 0 -> { error = "Préparation en cours…"; return }
            sending -> return
        }
        sending = true
        error = null
        viewModelScope.launch {
            try {
                val media = attachments.map {
                    SendMedia(it.filename, it.contentType, it.dataB64)
                }.ifEmpty { null }
                val resp = Graph.sms.sendNew(phone, selectedLineId, body, media)
                sending = false
                if (resp.threadId > 0) {
                    onSent(resp.threadId)
                } else {
                    error = "Échec de l'envoi."
                }
            } catch (e: Exception) {
                sending = false
                error = e.message?.takeIf { it.isNotBlank() && it != "error" }
                    ?: "Échec de l'envoi."
            }
        }
    }
}
