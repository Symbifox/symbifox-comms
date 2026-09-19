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
import com.bluefoxconsultant.sms.data.LectureBornee
import com.bluefoxconsultant.sms.data.Line
import com.bluefoxconsultant.sms.data.MediaPrep
import com.bluefoxconsultant.sms.data.OutgoingMedia
import com.bluefoxconsultant.sms.data.PieceProposee
import com.bluefoxconsultant.sms.data.SendMedia
import com.bluefoxconsultant.sms.data.SharedContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiPlural
import com.bluefoxconsultant.sms.ui.uiText

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
    var error by mutableStateOf<UiText?>(null)
        private set

    /** Pièces jointes déjà lues et mises au gabarit, prêtes à partir en MMS. */
    var attachments by mutableStateOf<List<OutgoingMedia>>(emptyList())
        private set
    /** Combien de fichiers sont encore en cours de lecture. */
    var preparing by mutableStateOf(0)
        private set

    /**
     * Fichiers partagés, pas encore lus : ils attendent « Joindre » ou
     * « Envoyer ». Même règle que le composeur courriel (C-M2) : partager vers
     * Comms ne vaut pas accord pour lire.
     */
    var proposees by mutableStateOf<List<PieceProposee>>(emptyList())
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
        get() = attachments.size + preparing + proposees.size < MediaPrep.MAX_PARTS

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
            val laissees = shared.uris.size - files.size
            error = uiPlural(R.plurals.sms_compose_mms_overflow, laissees, MediaPrep.MAX_PARTS, laissees)
        }
        if (files.isEmpty()) return
        // 🔴 Proposées, pas lues (C-M2) : la lecture et la mise au gabarit
        // attendent le geste. Seuls le nom et la taille sont demandés.
        proposees = files.map { PieceProposee(it, it.lastPathSegment ?: "piece-jointe") }
        val resolver = context.applicationContext.contentResolver
        viewModelScope.launch {
            val lues = withContext(Dispatchers.IO) {
                files.associateWith { LectureBornee.metadonnees(resolver, it) }
            }
            proposees = proposees.map { p ->
                lues[p.uri]?.let { p.copy(nom = it.nom, taille = it.taille) } ?: p
            }
        }
    }

    /** « Joindre » : le geste qui autorise la lecture. */
    fun joindreProposee(context: Context, piece: PieceProposee) {
        proposees = proposees - piece
        attach(context, piece.uri)
    }

    /** Écartée sans avoir été lue. */
    fun ecarterProposee(piece: PieceProposee) {
        proposees = proposees - piece
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
            error = uiPlural(R.plurals.sms_compose_max_attachments, MediaPrep.MAX_PARTS)
            return
        }
        // Le contexte d'APPLICATION : la coroutine survit à une rotation.
        val app = context.applicationContext
        preparing += 1
        viewModelScope.launch {
            try {
                preparer(app, uri)
            } finally {
                preparing -= 1
            }
        }
    }

    /** Lit et met au gabarit ; rend faux et pose [error] si ça n'a pas pris. */
    private suspend fun preparer(context: Context, uri: Uri): Boolean = try {
        val media = withContext(Dispatchers.IO) { MediaPrep.read(context, uri) }
        attachments = attachments + media
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: MediaPrep.TooLarge) {
        error = if (e.plafond > MediaPrep.MAX_BYTES) {
            uiText(R.string.sms_compose_file_too_large_to_prepare, e.name)
        } else {
            uiText(R.string.sms_compose_file_too_large_for_mms, e.name)
        }
        false
    } catch (e: LectureBornee.MemoireInsuffisante) {
        error = uiText(R.string.sms_compose_out_of_memory)
        false
    } catch (e: Exception) {
        error = uiText(R.string.sms_compose_file_unreadable)
        false
    }

    fun removeAttachment(media: OutgoingMedia) {
        attachments = attachments.filterNot { it === media }
    }

    fun dismissError() {
        error = null
    }

    fun send(context: Context, onSent: (Int) -> Unit) {
        val phone = recipient.trim()
        val body = message.trim()
        when {
            phone.isBlank() -> { error = uiText(R.string.sms_compose_enter_recipient); return }
            // Une photo seule est un message complet : le corps n'est exigé
            // que lorsqu'il n'y a rien d'autre à envoyer.
            body.isBlank() && attachments.isEmpty() && proposees.isEmpty() -> {
                error = uiText(R.string.sms_compose_enter_message); return
            }
            selectedLineId == 0 -> { error = uiText(R.string.sms_compose_no_line); return }
            (attachments.isNotEmpty() || proposees.isNotEmpty()) && !lineDoesMms -> {
                error = uiText(R.string.sms_compose_line_no_mms); return
            }
            preparing > 0 -> { error = uiText(R.string.sms_compose_still_preparing); return }
            sending -> return
        }
        sending = true
        error = null
        val app = context.applicationContext
        viewModelScope.launch {
            // Toucher « Envoyer » vaut accord pour les pièces encore proposées :
            // elles sont lues maintenant, et une seule qui ne passe pas arrête
            // l'envoi plutôt que de faire partir un MMS amputé.
            if (proposees.isNotEmpty()) {
                val aLire = proposees
                proposees = emptyList()
                var toutes = true
                preparing += aLire.size
                try {
                    for (piece in aLire) {
                        try {
                            if (!preparer(app, piece.uri)) toutes = false
                        } finally {
                            preparing -= 1
                        }
                    }
                } finally {
                    if (!toutes || !isActive) sending = false
                }
                if (!toutes) return@launch
            }
            try {
                val media = attachments.map {
                    SendMedia(it.filename, it.contentType, it.dataB64)
                }.ifEmpty { null }
                val resp = Graph.sms.sendNew(phone, selectedLineId, body, media)
                sending = false
                if (resp.threadId > 0) {
                    onSent(resp.threadId)
                } else {
                    error = uiText(R.string.sms_compose_send_failed)
                }
            } catch (e: Exception) {
                sending = false
                // Le message du serveur passe tel quel : il n'est pas à nous.
                error = e.message?.takeIf { it.isNotBlank() && it != "error" }
                    ?.let { UiText.Raw(it) }
                    ?: uiText(R.string.sms_compose_send_failed)
            }
        }
    }
}
