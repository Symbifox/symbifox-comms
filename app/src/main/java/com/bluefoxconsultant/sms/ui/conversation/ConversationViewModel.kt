package com.bluefoxconsultant.sms.ui.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.Line
import com.bluefoxconsultant.sms.data.Message
import com.bluefoxconsultant.sms.data.Thread
import kotlinx.coroutines.launch

class ConversationViewModel(private val threadId: Int) : ViewModel() {

    var thread by mutableStateOf<Thread?>(null)
        private set
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    /** The account's numbers, for the "send from" picker. */
    var lines by mutableStateOf<List<Line>>(emptyList())
        private set

    /**
     * Number the next message goes out from.
     *
     * Null means "let the server decide", which is what it did before this
     * existed: it follows the thread's last outgoing message. Only a deliberate
     * pick sets it, so an untouched conversation keeps behaving exactly as it
     * always has.
     */
    var selectedLineId by mutableStateOf<Int?>(null)
        private set

    /** Label to show in the app bar: the pick, else the thread's own line. */
    val currentLineLabel: String
        get() = lines.firstOrNull { it.id == selectedLineId }?.label
            ?: thread?.lineLabel.orEmpty()

    /** SMS-capable only — a voice-only DID can't carry a text. */
    fun disabledReason(line: Line): String? =
        if (line.smsEnabled) null else "Pas de texto sur cette ligne"

    init {
        lines = Graph.tokenStore.lines
        open()
    }

    private fun open() {
        viewModelScope.launch {
            loading = true
            try {
                val resp = Graph.sms.conversation(threadId, beforeId = null)
                thread = resp.thread
                messages = resp.messages
                hasMore = resp.hasMore
                if (lines.isEmpty()) refreshLines()
                Graph.sms.markRead(threadId)
            } catch (e: Exception) {
                error = "Impossible de charger la conversation."
            } finally {
                loading = false
            }
        }
    }

    fun loadOlder() {
        if (loadingMore || !hasMore || messages.isEmpty()) return
        val oldestId = messages.minByOrNull { it.id }?.id ?: return
        viewModelScope.launch {
            loadingMore = true
            try {
                val resp = Graph.sms.conversation(threadId, beforeId = oldestId)
                val older = resp.messages.filter { m -> messages.none { it.id == m.id } }
                messages = older + messages
                hasMore = resp.hasMore
            } catch (e: Exception) {
                // keep what we have
            } finally {
                loadingMore = false
            }
        }
    }

    private fun refreshLines() {
        viewModelScope.launch {
            try {
                val fresh = Graph.sms.config().lines
                if (fresh.isNotEmpty()) {
                    lines = fresh
                    Graph.tokenStore.saveLines(fresh)
                }
            } catch (e: Exception) {
                // Picker stays hidden; sending still works on the server default.
            }
        }
    }

    /** Choose the number the next message goes out from. */
    fun selectLine(line: Line) {
        selectedLineId = line.id
        notice = "Prochain envoi depuis ${line.label.ifBlank { line.did }}"
    }

    fun send(text: String) {
        val body = text.trim()
        if (body.isEmpty() || sending) return
        sending = true
        error = null
        viewModelScope.launch {
            try {
                val resp = Graph.sms.send(threadId, body, lineId = selectedLineId)
                if (messages.none { it.id == resp.message.id } || resp.message.id == 0) {
                    messages = messages + resp.message
                }
            } catch (e: Exception) {
                error = "Échec de l'envoi du message."
            } finally {
                sending = false
            }
        }
    }

    fun clearError() {
        error = null
    }

    fun clearNotice() {
        notice = null
    }

    val title: String
        get() = thread?.displayName ?: ""
}
