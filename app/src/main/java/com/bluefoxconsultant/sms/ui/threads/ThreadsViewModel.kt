package com.bluefoxconsultant.sms.ui.threads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.Line
import com.bluefoxconsultant.sms.data.Thread
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ThreadsViewModel : ViewModel() {

    var threads by mutableStateOf<List<Thread>>(emptyList())
        private set
    var lines by mutableStateOf<List<Line>>(emptyList())
        private set
    var refreshing by mutableStateOf(false)
        private set
    var firstLoadDone by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var selectedLineId by mutableStateOf<Int?>(null)
        private set
    var searchActive by mutableStateOf(false)
        private set
    var searchTerm by mutableStateOf("")
        private set

    private var searchJob: Job? = null

    init {
        lines = Graph.tokenStore.lines
        refresh()
        if (lines.isEmpty()) refreshLines()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            error = null
            try {
                threads = Graph.sms.threads(
                    archived = 0,
                    lineId = selectedLineId,
                    search = searchTerm,
                )
            } catch (e: Exception) {
                error = "Impossible de charger les messages."
            } finally {
                refreshing = false
                firstLoadDone = true
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
                // keep whatever we have
            }
        }
    }

    fun selectLine(lineId: Int?) {
        if (selectedLineId == lineId) return
        selectedLineId = lineId
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

    /** Last archive, offered as "Annuler" for a few seconds. */
    var undoable by mutableStateOf<(() -> Unit)?>(null)
        private set
    /** Ce que l'annulation défera — « Archivé » ou « 5 archivées ». */
    var undoLabel by mutableStateOf("Archivé")
        private set

    fun clearUndo() {
        undoable = null
    }

    fun archive(threadId: Int) {
        threads = threads.filterNot { it.id == threadId }
        undoLabel = "Archivé"
        undoable = { unarchive(threadId) }
        viewModelScope.launch {
            try {
                Graph.sms.archive(threadId, archived = true)
            } catch (e: Exception) {
                refresh()
            }
        }
    }

    private fun unarchive(threadId: Int) {
        undoable = null
        viewModelScope.launch {
            try {
                Graph.sms.archive(threadId, archived = false)
                refresh()
            } catch (e: Exception) {
                error = "Annulation impossible."
            }
        }
    }

    fun togglePin(threadId: Int) {
        viewModelScope.launch {
            try {
                Graph.sms.pin(threadId)
                refresh() // server re-sorts pinned first
            } catch (e: Exception) {
                error = "Action impossible."
            }
        }
    }

    fun dismissError() {
        error = null
    }

    // ── Sélection multiple ────────────────────────────────────────────
    /** Les fils cochés. Vide = liste normale ; c'est la sélection qui EST le mode. */
    var selection by mutableStateOf<Set<Int>>(emptySet())
        private set

    val selectionMode: Boolean get() = selection.isNotEmpty()

    private val selectedThreads: List<Thread>
        get() = threads.filter { it.id in selection }

    /** Tout coché est déjà épinglé : le bouton propose alors l'inverse. */
    val allSelectedPinned: Boolean
        get() = selectedThreads.isNotEmpty() && selectedThreads.all { it.isPinned }

    fun toggleSelect(threadId: Int) {
        selection = if (threadId in selection) selection - threadId else selection + threadId
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun selectAll() {
        selection = threads.mapTo(LinkedHashSet()) { it.id }
    }

    /**
     * Archive toute la sélection.
     *
     * ⚠️ Une requête PAR fil : `/thread/archive` ne prend qu'un identifiant,
     * contrairement au côté courriel. Elles partent en séquence plutôt qu'en
     * parallèle — vingt écritures simultanées sur la même base valent une
     * seconde d'attente de plus.
     */
    fun archiveSelected() {
        val targets = selection.toList()
        if (targets.isEmpty()) return
        clearSelection()
        threads = threads.filterNot { it.id in targets }
        undoLabel = if (targets.size == 1) "Archivé" else "${targets.size} archivées"
        undoable = { unarchiveMany(targets) }
        viewModelScope.launch {
            var failed = 0
            targets.forEach { id ->
                try {
                    Graph.sms.archive(id, archived = true)
                } catch (e: Exception) {
                    failed++
                }
            }
            if (failed > 0) {
                error = "Archivage impossible pour $failed conversation(s)."
                refresh()
            }
        }
    }

    private fun unarchiveMany(threadIds: List<Int>) {
        undoable = null
        viewModelScope.launch {
            var failed = 0
            threadIds.forEach { id ->
                try {
                    Graph.sms.archive(id, archived = false)
                } catch (e: Exception) {
                    failed++
                }
            }
            if (failed > 0) error = "Annulation incomplète."
            refresh()
        }
    }

    /**
     * Épingle ou désépingle toute la sélection.
     *
     * Le point d'entrée du serveur BASCULE l'état ; appelé sur une sélection
     * mêlant épinglés et non épinglés, il les inverserait tous les deux et ne
     * réglerait rien. On n'appelle donc que les fils qui ne sont pas déjà dans
     * l'état voulu.
     */
    fun pinSelected(pin: Boolean) {
        val targets = selectedThreads.filter { it.isPinned != pin }.map { it.id }
        clearSelection()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            targets.forEach { id ->
                try {
                    Graph.sms.pin(id)
                } catch (e: Exception) {
                    failed++
                }
            }
            if (failed > 0) error = "Action impossible pour $failed conversation(s)."
            refresh() // le serveur retrie, épinglés d'abord
        }
    }
}
