package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.MailConfig
import com.bluefoxconsultant.sms.data.MailCounts
import com.bluefoxconsultant.sms.data.MailFilter
import com.bluefoxconsultant.sms.data.MailMessage
import com.bluefoxconsultant.sms.data.PendingAction
import com.bluefoxconsultant.sms.data.isOffline
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE = 25

class MailListViewModel : ViewModel() {

    var threads by mutableStateOf<List<MailMessage>>(emptyList())
        private set
    var config by mutableStateOf(MailConfig())
        private set
    var counts by mutableStateOf(MailCounts())
        private set
    var filter by mutableStateOf(MailFilter.INBOX)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var firstLoadDone by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    /**
     * The last reversible action, offered as "Annuler" for a few seconds.
     *
     * The action runs immediately and undo *reverses* it, rather than the
     * action being delayed until the undo window closes. Delaying reads
     * better — an undone archive would never touch IMAP — but the work would
     * be lost outright if the screen went away inside those seconds. Losing
     * an archive silently is worse than a second IMAP round-trip.
     */
    var undoable by mutableStateOf<(() -> Unit)?>(null)
        private set
    var undoLabel by mutableStateOf<String?>(null)
        private set

    /** Showing cached data because the server could not be reached. */
    var offline by mutableStateOf(false)
        private set
    /** Actions waiting to be replayed. */
    var queued by mutableStateOf(0)
        private set

    var searchActive by mutableStateOf(false)
        private set
    var searchTerm by mutableStateOf("")
        private set

    private var searchJob: Job? = null

    init {
        queued = Graph.outbox.size
        loadConfig()
        refresh()
    }

    private fun loadConfig() {
        // Paint from cache first so menus and badges exist before the network
        // answers — and still exist if it never does.
        Graph.mailCache.loadConfig()?.let {
            config = it
            counts = it.counts
        }
        viewModelScope.launch {
            try {
                config = Graph.mail.config()
                counts = config.counts
                Graph.mailCache.saveConfig(config)
                config.branding?.let {
                    Graph.brandStore.save(it.name, it.primary, it.dark)
                }
            } catch (e: Exception) {
                // Badges and action menus degrade; the list still works.
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            error = null
            // AWAITED, not fired alongside: launching the flush in its own
            // coroutine let the re-fetch overtake it, so the server answered
            // with the state from before the queued action and the row
            // reappeared — then vanished again once the flush landed.
            flushQueue()
            try {
                val resp = Graph.mail.threads(
                    filter = filter,
                    search = searchTerm,
                    offset = 0,
                    limit = PAGE,
                    grouped = Graph.uiPrefs.threadView,
                )
                // Even if the flush failed, the list must not contradict what
                // the user just did: anything still queued stays hidden.
                val stillQueued = Graph.outbox.peek()
                    .flatMap { it.emailIds }
                    .toSet()
                threads = resp.threads.filterNot { it.id in stillQueued }
                hasMore = resp.hasMore
                offline = false
                // Only the plain first page is worth caching; a search result
                // or a later page can't be reconstructed coherently offline.
                if (searchTerm.isBlank()) Graph.mailCache.saveThreads(filter, resp)
            } catch (e: Exception) {
                if (e.isOffline()) {
                    val cached = if (searchTerm.isBlank())
                        Graph.mailCache.loadThreads(filter) else null
                    if (cached != null) {
                        threads = cached.threads
                        // No paging offline: the next page isn't on this device.
                        hasMore = false
                        offline = true
                    } else {
                        error = "Hors ligne, et rien en cache pour « ${filter.label} »."
                    }
                } else {
                    error = "Impossible de charger les courriels."
                }
            } finally {
                refreshing = false
                firstLoadDone = true
            }
        }
    }

    /** Replay queued actions; surface anything the server refused outright. */
    private suspend fun flushQueue() {
        runCatching { Graph.outbox.flush { Graph.mail.replay(it) } }
        Graph.outbox.drainFailures().firstOrNull()?.let { error = it }
        queued = Graph.outbox.size
    }

    fun flushOutbox() {
        viewModelScope.launch { flushQueue() }
    }

    fun loadMore() {
        if (loadingMore || !hasMore || refreshing) return
        viewModelScope.launch {
            loadingMore = true
            try {
                val resp = Graph.mail.threads(
                    filter = filter,
                    search = searchTerm,
                    offset = threads.size,
                    limit = PAGE,
                    grouped = Graph.uiPrefs.threadView,
                )
                // Guard against a page that overlaps: a message arriving between
                // two requests shifts every later row down by one, which would
                // otherwise duplicate the boundary thread.
                val known = threads.mapTo(HashSet()) { it.threadKey }
                threads = threads + resp.threads.filterNot { it.threadKey in known }
                hasMore = resp.hasMore
            } catch (e: Exception) {
                hasMore = false
            } finally {
                loadingMore = false
            }
        }
    }

    fun selectFilter(next: MailFilter) {
        if (filter == next) return
        filter = next
        threads = emptyList()
        refresh()
    }

    fun openSearch() {
        searchActive = true
    }

    fun closeSearch() {
        searchActive = false
        if (searchTerm.isNotEmpty()) {
            searchTerm = ""
            refresh()
        }
    }

    fun onSearchChange(term: String) {
        searchTerm = term
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            refresh()
        }
    }

    /**
     * Archive optimistically — the row leaves the list at once and comes back
     * on failure. The server also moves the message on the IMAP side, so this
     * is the one action worth reporting when it fails.
     */
    fun clearUndo() {
        undoable = null
        undoLabel = null
    }

    private fun offerUndo(label: String, action: () -> Unit) {
        undoLabel = label
        undoable = action
    }

    fun archive(message: MailMessage) {
        threads = threads.filterNot { it.threadKey == message.threadKey }
        offerUndo("Archivé") { restore(message) }
        viewModelScope.launch {
            try {
                counts = Graph.mail.setHandled(listOf(message.id), handled = true)
            } catch (e: Exception) {
                if (e.isOffline()) queueHandle(message, handled = true)
                else {
                    error = "Archivage impossible."
                    refresh()
                }
            }
        }
    }

    /**
     * The row already left the list; queueing keeps that promise instead of
     * snapping it back and losing the gesture.
     */
    private fun queueHandle(message: MailMessage, handled: Boolean) {
        Graph.outbox.enqueue(
            PendingAction(
                token = PendingAction.newToken(),
                kind = PendingAction.KIND_HANDLE,
                createdMs = System.currentTimeMillis(),
                emailIds = listOf(message.id),
                handled = handled,
            ),
        )
        queued = Graph.outbox.size
        offline = true
        notice = if (handled) "Archivage enregistré, envoi au retour du réseau."
        else "Restauration enregistrée, envoi au retour du réseau."
    }

    fun snooze(message: MailMessage, untilMs: Long) {
        threads = threads.filterNot { it.threadKey == message.threadKey }
        offerUndo("Reporté") { restore(message) }
        viewModelScope.launch {
            try {
                counts = Graph.mail.snooze(listOf(message.id), untilMs)
            } catch (e: Exception) {
                if (e.isOffline()) {
                    Graph.outbox.enqueue(
                        PendingAction(
                            token = PendingAction.newToken(),
                            kind = PendingAction.KIND_SNOOZE,
                            createdMs = System.currentTimeMillis(),
                            emailIds = listOf(message.id),
                            untilMs = untilMs,
                        ),
                    )
                    queued = Graph.outbox.size
                    offline = true
                    notice = "Report enregistré, envoi au retour du réseau."
                } else {
                    error = "Report impossible."
                    refresh()
                }
            }
        }
    }

    fun restore(message: MailMessage) {
        clearUndo()
        threads = threads.filterNot { it.threadKey == message.threadKey }
        viewModelScope.launch {
            try {
                counts = Graph.mail.setHandled(listOf(message.id), handled = false)
                notice = "Remis en boîte de réception."
            } catch (e: Exception) {
                if (e.isOffline()) queueHandle(message, handled = false)
                else {
                    error = "Action impossible."
                    refresh()
                }
            }
        }
    }

    fun markRead(message: MailMessage) {
        viewModelScope.launch {
            try {
                counts = Graph.mail.markRead(listOf(message.id))
                threads = threads.map {
                    if (it.threadKey == message.threadKey) it.copy(status = "read", unreadCount = 0)
                    else it
                }
            } catch (e: Exception) {
                if (e.isOffline()) {
                    Graph.outbox.enqueue(
                        PendingAction(
                            token = PendingAction.newToken(),
                            kind = PendingAction.KIND_MARK_READ,
                            createdMs = System.currentTimeMillis(),
                            emailIds = listOf(message.id),
                        ),
                    )
                    queued = Graph.outbox.size
                }
            }
        }
    }

    fun spawn(message: MailMessage, kind: String) {
        viewModelScope.launch {
            try {
                val resp = Graph.mail.spawn(message.id, kind)
                notice = resp.record?.let { "Créé : ${it.name}" } ?: "Créé."
                refresh()
            } catch (e: Exception) {
                error = e.message ?: "Création impossible."
            }
        }
    }

    fun route(message: MailMessage, model: String, recordId: Int) {
        viewModelScope.launch {
            try {
                val resp = Graph.mail.route(message.id, model, recordId)
                notice = resp.record?.let { "Importé dans ${it.name}" } ?: "Importé."
                refresh()
            } catch (e: Exception) {
                error = e.message ?: "Routage impossible."
            }
        }
    }

    fun dismissNotice() {
        notice = null
        error = null
    }
}
