package com.bluefoxconsultant.sms.ui.genfox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.GenfoxMessage
import com.bluefoxconsultant.sms.data.GenfoxSession
import com.bluefoxconsultant.sms.data.Graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One conversation with GenFox, and the polling that goes with it.
 *
 * The question is recorded server-side and answered later, so this holds an
 * optimistic pair — the question, and a pending answer — and replaces the
 * second when the turn lands. Leaving the screen does not cancel the turn: it
 * finishes on the server and a push announces it.
 */
class GenfoxViewModel : ViewModel() {

    var messages by mutableStateOf<List<GenfoxMessage>>(emptyList())
        private set
    var sessionId by mutableStateOf<Int?>(null)
        private set
    var sessionName by mutableStateOf("")
        private set
    var sessions by mutableStateOf<List<GenfoxSession>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var asking by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var pollJob: Job? = null

    init {
        openLatest()
    }

    fun clearError() { error = null }

    /** Opens the most recent conversation, or an empty one if there is none. */
    fun openLatest() {
        viewModelScope.launch {
            loading = true
            try {
                sessions = Graph.genfox.sessions()
                val latest = sessions.firstOrNull()
                if (latest == null) {
                    reset()
                } else {
                    open(latest.id)
                }
            } catch (e: Exception) {
                error = "Impossible de joindre l'assistant."
            } finally {
                loading = false
            }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            runCatching { sessions = Graph.genfox.sessions() }
        }
    }

    fun open(id: Int) {
        viewModelScope.launch {
            loading = true
            try {
                val resp = Graph.genfox.messages(id)
                sessionId = resp.sessionId
                sessionName = resp.sessionName
                messages = resp.messages
                // Reopening while a turn is still running: pick the polling
                // back up rather than leaving a dot spinning forever.
                resp.messages.lastOrNull()?.takeIf { it.isPending }?.let { poll(it.id) }
            } catch (e: Exception) {
                error = "Conversation illisible."
            } finally {
                loading = false
            }
        }
    }

    fun reset() {
        pollJob?.cancel()
        sessionId = null
        sessionName = ""
        messages = emptyList()
    }

    fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty() || asking) return
        viewModelScope.launch {
            asking = true
            messages = messages + GenfoxMessage(role = "user", content = question) +
                GenfoxMessage(role = "assistant", content = "", state = "pending")
            try {
                val resp = Graph.genfox.ask(question, sessionId)
                sessionId = resp.sessionId
                poll(resp.turnId)
            } catch (e: Exception) {
                replacePending(GenfoxMessage(
                    role = "assistant",
                    content = "La question n'a pas pu être posée.",
                    state = "error",
                ))
                asking = false
            }
        }
    }

    fun delete(id: Int) {
        viewModelScope.launch {
            runCatching { Graph.genfox.deleteSession(id) }
            if (sessionId == id) reset()
            refreshSessions()
        }
    }

    private fun poll(turnId: Int) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            // Fast enough that the answer visibly writes itself, slow enough not
            // to hammer an instance that is busy thinking. Each poll returns the
            // text SO FAR, so progress is real rather than animated for show.
            repeat(MAX_POLLS) {
                delay(POLL_MS)
                val turn = runCatching { Graph.genfox.turn(turnId) }.getOrNull() ?: return@repeat
                replacePending(GenfoxMessage(
                    id = turn.turnId,
                    role = "assistant",
                    content = turn.text,
                    state = turn.state,
                    tools = turn.tools,
                    inputTokens = turn.usage.inputTokens,
                    outputTokens = turn.usage.outputTokens,
                    cacheReadTokens = turn.usage.cacheReadTokens,
                    cacheWriteTokens = turn.usage.cacheWriteTokens,
                    netTokens = turn.usage.netTokens,
                    totalTokens = turn.usage.totalTokens,
                    costUsd = turn.usage.costUsd,
                    durationMs = turn.usage.durationMs,
                ))
                if (turn.state != "pending") {
                    sessionName = turn.sessionName
                    asking = false
                    refreshSessions()
                    return@launch
                }
            }
            replacePending(GenfoxMessage(
                role = "assistant",
                content = "Toujours en cours. La réponse arrivera par notification.",
                state = "error",
            ))
            asking = false
        }
    }

    private fun replacePending(replacement: GenfoxMessage) {
        val index = messages.indexOfLast { it.isPending || it.id == replacement.id }
        messages = if (index < 0) messages + replacement
        else messages.toMutableList().also { it[index] = replacement }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val POLL_MS = 700L
        const val MAX_POLLS = 430
    }
}
