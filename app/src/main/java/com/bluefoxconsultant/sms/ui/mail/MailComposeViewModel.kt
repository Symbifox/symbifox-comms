package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.MailContact
import com.bluefoxconsultant.sms.data.PendingAction
import com.bluefoxconsultant.sms.data.StagedUpload
import com.bluefoxconsultant.sms.data.isOffline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Composer for all four modes.
 *
 * For `reply` and `reply_all` the recipient fields stay empty and are simply
 * not sent: the server resolves them from the original message, excluding the
 * user's own addresses and the tenant's catchall aliases. Echoing back a list
 * the app rendered would only be a chance to get it wrong. `forward` and a new
 * message have no server-side default, so there the field is required.
 */
class MailComposeViewModel(
    val mode: String,
    private val emailId: Int,
) : ViewModel() {

    val isNew: Boolean get() = mode == "new"
    val needsRecipient: Boolean get() = isNew || mode == "forward"

    var to by mutableStateOf("")
    var cc by mutableStateOf("")

    /** Confirmed recipients, kept apart from what is still being typed. */
    var toChips by mutableStateOf<List<String>>(emptyList())
        private set
    var ccChips by mutableStateOf<List<String>>(emptyList())
        private set

    var suggestions by mutableStateOf<List<MailContact>>(emptyList())
        private set
    /** Which field the suggestions belong to: "to" or "cc". */
    var suggestingFor by mutableStateOf("")
        private set

    private var lookupJob: Job? = null

    /**
     * Look the address book up as the user types, debounced.
     *
     * Typing an address by hand still works — the field is free text and is
     * merged with the chips at send time. Completion is an accelerator, not a
     * gate: an address that isn't in Contacts yet must remain sendable.
     */
    fun onRecipientInput(field: String, value: String) {
        if (field == "to") to = value else cc = value
        suggestingFor = field
        lookupJob?.cancel()
        val term = value.substringAfterLast(',').trim()
        if (term.length < 2) {
            suggestions = emptyList()
            return
        }
        lookupJob = viewModelScope.launch {
            delay(250)
            suggestions = runCatching { Graph.mail.contacts(term) }.getOrDefault(emptyList())
        }
    }

    fun pickSuggestion(contact: MailContact) {
        if (suggestingFor == "cc") {
            ccChips = ccChips + contact.address
            cc = ""
        } else {
            toChips = toChips + contact.address
            to = ""
        }
        suggestions = emptyList()
    }

    fun removeChip(field: String, address: String) {
        if (field == "cc") ccChips = ccChips - address else toChips = toChips - address
    }
    var subject by mutableStateOf("")
    var body by mutableStateOf("")

    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Set when the send was queued instead of delivered. */
    var queuedOffline by mutableStateOf(false)
        private set

    /**
     * Generated once per composer, before the first attempt, and reused if the
     * send is queued and replayed. The server refuses a repeat of the same
     * token, which is what stops a lost response from becoming a second copy
     * in the correspondent's inbox.
     */
    private val clientToken = PendingAction.newToken()

    /** Files already staged server-side, ready for the send to claim. */
    var attachments by mutableStateOf<List<StagedUpload>>(emptyList())
        private set
    var uploading by mutableStateOf(0)
        private set

    val title: String get() = when (mode) {
        "reply" -> "Répondre"
        "reply_all" -> "Répondre à tous"
        "forward" -> "Transférer"
        else -> "Nouveau courriel"
    }

    /**
     * Upload a picked file immediately rather than at send time.
     *
     * The user keeps typing while it goes up, and a failure surfaces now —
     * when there is still a composer to fix it in — instead of turning into a
     * failed send after they hit the button.
     */
    fun attach(context: Context, uri: Uri) {
        uploading += 1
        viewModelScope.launch {
            try {
                val (name, mimetype, bytes) = withContext(Dispatchers.IO) {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                    } ?: uri.lastPathSegment ?: "piece-jointe"
                    val type = resolver.getType(uri) ?: "application/octet-stream"
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: ByteArray(0)
                    Triple(name, type, bytes)
                }
                if (bytes.isEmpty()) {
                    error = "Fichier vide ou illisible."
                    return@launch
                }
                val staged = Graph.mail.uploadAttachment(name, mimetype, bytes)
                attachments = attachments + staged
            } catch (e: Exception) {
                error = if (e.message == "too_large") "Fichier trop volumineux (max 25 Mo)."
                else "Téléversement impossible."
            } finally {
                uploading -= 1
            }
        }
    }

    fun removeAttachment(staged: StagedUpload) {
        attachments = attachments.filterNot { it.attachmentId == staged.attachmentId }
    }

    fun send(onSent: () -> Unit) {
        if (sending) return
        if (uploading > 0) {
            error = "Téléversement en cours…"
            return
        }
        if (body.isBlank()) {
            error = "Le message est vide."
            return
        }
        // Chips plus anything still sitting in the field, so a half-typed
        // address is not silently dropped when Send is tapped.
        val recipients = (toChips + splitAddresses(to)).distinct()
        if (needsRecipient && recipients.isEmpty()) {
            error = "Indiquez au moins un destinataire."
            return
        }
        sending = true
        error = null
        viewModelScope.launch {
            try {
                val formatted = RichText.hasFormatting(body)
                val payload = if (formatted) RichText.toHtml(body) else body
                if (isNew) {
                    Graph.mail.compose(
                        to = recipients,
                        subject = subject,
                        body = payload,
                        bodyIsHtml = formatted,
                        cc = (ccChips + splitAddresses(cc)).distinct().ifEmpty { null },
                        attachmentIds = attachments.map { it.attachmentId }.ifEmpty { null },
                        clientToken = clientToken,
                    )
                } else {
                    Graph.mail.reply(
                        emailId = emailId,
                        mode = mode,
                        body = payload,
                        bodyIsHtml = formatted,
                        to = recipients.ifEmpty { null },
                        cc = (ccChips + splitAddresses(cc)).distinct().ifEmpty { null },
                        attachmentIds = attachments.map { it.attachmentId }.ifEmpty { null },
                        clientToken = clientToken,
                    )
                }
                onSent()
            } catch (e: Exception) {
                if (e.isOffline()) {
                    queueForLater(recipients)
                    onSent()
                } else {
                    error = e.message?.takeIf { it.isNotBlank() && it != "error" }
                        ?: "Envoi impossible."
                }
            } finally {
                sending = false
            }
        }
    }

    /**
     * Park the message instead of losing what the user just wrote.
     *
     * Attachments are deliberately absent here: staging one needs the network,
     * so an offline composer cannot have any. Nothing is silently dropped.
     */
    private fun queueForLater(recipients: List<String>) {
        Graph.outbox.enqueue(
            PendingAction(
                token = clientToken,
                kind = if (isNew) PendingAction.KIND_COMPOSE else PendingAction.KIND_REPLY,
                createdMs = System.currentTimeMillis(),
                emailId = emailId,
                mode = mode,
                body = body,
                subject = subject,
                to = recipients.ifEmpty { null },
                cc = splitAddresses(cc).ifEmpty { null },
            ),
        )
        queuedOffline = true
    }

    fun dismissError() {
        error = null
    }

    private fun splitAddresses(raw: String): List<String> =
        raw.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
}
