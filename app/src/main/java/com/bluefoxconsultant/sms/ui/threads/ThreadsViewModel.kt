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

    fun clearUndo() {
        undoable = null
    }

    fun archive(threadId: Int) {
        threads = threads.filterNot { it.id == threadId }
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

}
