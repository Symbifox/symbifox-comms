package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.MailMessage
import com.bluefoxconsultant.sms.data.isOffline
import kotlinx.coroutines.launch
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiText

class MailThreadViewModel(private val threadKey: String) : ViewModel() {

    var subject by mutableStateOf("")
        private set
    var messages by mutableStateOf<List<MailMessage>>(emptyList())
        private set
    var truncated by mutableStateOf(false)
        private set
    var loading by mutableStateOf(true)
        private set
    // Rédigés à l'écran, dans la langue du téléphone : voir [UiText].
    var error by mutableStateOf<UiText?>(null)
        private set
    var notice by mutableStateOf<UiText?>(null)
        private set
    var offline by mutableStateOf(false)
        private set

    /** Ids the reader expanded — the last message starts open. */
    var expanded by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** Ids for which the reader asked to load remote images. */
    private var imagesAllowed by mutableStateOf<Set<Int>>(emptySet())

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val resp = Graph.mail.conversation(threadKey)
                Graph.mailCache.saveConversation(threadKey, resp)
                offline = false
                subject = resp.subject
                messages = resp.messages
                truncated = resp.truncated
                // The server sends the newest message with its body already
                // attached, so opening a thread costs one request in the common
                // case. Mirror that by expanding it.
                expanded = resp.messages.lastOrNull()?.let { setOf(it.id) } ?: emptySet()
            } catch (e: Exception) {
                // A thread read before going offline is still readable; one
                // never opened simply isn't on the device.
                val cached = if (e.isOffline())
                    Graph.mailCache.loadConversation(threadKey) else null
                if (cached != null) {
                    offline = true
                    subject = cached.subject
                    messages = cached.messages
                    truncated = cached.truncated
                    expanded = cached.messages.lastOrNull()?.let { setOf(it.id) } ?: emptySet()
                } else {
                    error = if (e.isOffline()) uiText(R.string.mail_thread_offline_not_cached)
                    else uiText(R.string.mail_thread_open_failed)
                }
            } finally {
                loading = false
            }
        }
    }

    fun toggle(message: MailMessage) {
        if (message.id in expanded) {
            expanded = expanded - message.id
            return
        }
        expanded = expanded + message.id
        if (!message.isFull) fetchBody(message.id, loadImages = false)
    }

    /** Reload one message with its remote images unblocked. */
    fun loadImages(message: MailMessage) {
        imagesAllowed = imagesAllowed + message.id
        fetchBody(message.id, loadImages = true)
    }

    fun imagesAllowedFor(message: MailMessage): Boolean = message.id in imagesAllowed

    private fun fetchBody(emailId: Int, loadImages: Boolean) {
        viewModelScope.launch {
            try {
                val full = Graph.mail.message(emailId, loadImages = loadImages)
                messages = messages.map { if (it.id == emailId) full else it }
            } catch (e: Exception) {
                error = uiText(R.string.mail_thread_message_load_failed)
            }
        }
    }

    fun archive(message: MailMessage, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                Graph.mail.setHandled(listOf(message.id), handled = true)
                onDone()
            } catch (e: Exception) {
                error = uiText(R.string.mail_archive_failed)
            }
        }
    }

    fun restore(message: MailMessage, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                Graph.mail.setHandled(listOf(message.id), handled = false)
                onDone()
            } catch (e: Exception) {
                error = uiText(R.string.mail_action_failed)
            }
        }
    }

    fun markRead(message: MailMessage) {
        viewModelScope.launch {
            try {
                Graph.mail.markRead(listOf(message.id))
                messages = messages.map {
                    if (it.id == message.id) it.copy(status = "read") else it
                }
            } catch (e: Exception) {
                // best-effort
            }
        }
    }

    fun snooze(message: MailMessage, untilMs: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                Graph.mail.snooze(listOf(message.id), untilMs)
                onDone()
            } catch (e: Exception) {
                error = uiText(R.string.mail_snooze_failed)
            }
        }
    }

    fun spawn(message: MailMessage, kind: String) {
        viewModelScope.launch {
            try {
                val resp = Graph.mail.spawn(message.id, kind)
                notice = resp.record?.let { uiText(R.string.mail_created_record, it.name) }
                    ?: uiText(R.string.mail_created)
            } catch (e: Exception) {
                // Le message d'une ApiException est celui du serveur : tel quel.
                error = e.message?.let { UiText.Raw(it) } ?: uiText(R.string.mail_create_failed)
            }
        }
    }

    fun route(message: MailMessage, model: String, recordId: Int) {
        viewModelScope.launch {
            try {
                val resp = Graph.mail.route(message.id, model, recordId)
                notice = resp.record?.let { uiText(R.string.mail_routed_record, it.name) }
                    ?: uiText(R.string.mail_routed)
                load()
            } catch (e: Exception) {
                error = e.message?.let { UiText.Raw(it) } ?: uiText(R.string.mail_route_failed)
            }
        }
    }

    fun dismissNotice() {
        notice = null
        error = null
    }
}
