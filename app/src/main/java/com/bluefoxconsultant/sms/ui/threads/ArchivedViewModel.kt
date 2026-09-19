package com.bluefoxconsultant.sms.ui.threads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.Thread
import kotlinx.coroutines.launch
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiText

class ArchivedViewModel : ViewModel() {

    var threads by mutableStateOf<List<Thread>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<UiText?>(null)
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
                error = uiText(R.string.sms_archived_load_failed)
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
