package com.bluefoxconsultant.sms.ui.threads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.Thread
import kotlinx.coroutines.launch

class ArchivedViewModel : ViewModel() {

    var threads by mutableStateOf<List<Thread>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                threads = Graph.sms.threads(archived = 1)
            } catch (e: Exception) {
                error = "Impossible de charger les archives."
            } finally {
                loading = false
            }
        }
    }

    fun unarchive(threadId: Int) {
        threads = threads.filterNot { it.id == threadId }
        viewModelScope.launch {
            try {
                Graph.sms.archive(threadId, archived = false)
            } catch (e: Exception) {
                load()
            }
        }
    }
}
