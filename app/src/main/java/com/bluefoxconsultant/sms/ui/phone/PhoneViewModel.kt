package com.bluefoxconsultant.sms.ui.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.CallLogEntry
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.PhoneContact
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What has been dialled, what matches it, and what was called before. */
class PhoneViewModel : ViewModel() {

    var dialled by mutableStateOf("")
        private set
    var matches by mutableStateOf<List<PhoneContact>>(emptyList())
        private set
    var calls by mutableStateOf<List<CallLogEntry>>(emptyList())
        private set
    var placing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
        private set

    private var searchJob: Job? = null

    val callable: Boolean get() = isCallable(dialled)

    /** Name of the contact that matches exactly what is dialled, if any. */
    val matchedName: String
        get() = matches.firstOrNull { it.number.filter(Char::isDigit).endsWith(digits()) }
            ?.name.orEmpty()

    private fun digits() = dialledDigits(dialled)

    fun clearError() { error = null }

    fun press(key: Char) {
        dialled += key
        search()
    }

    fun backspace() {
        if (dialled.isNotEmpty()) dialled = dialled.dropLast(1)
        search()
    }

    fun clear() {
        dialled = ""
        matches = emptyList()
    }

    fun set(number: String) {
        dialled = number
        matches = emptyList()
    }

    fun refresh() {
        viewModelScope.launch {
            try {
                calls = Graph.phone.calls()
            } catch (e: Exception) {
                // A missing log is not worth an error banner over a keypad that
                // otherwise works; only say so if nothing at all can be reached.
                if (calls.isEmpty()) error = "Journal d'appels indisponible."
            }
        }
    }

    private fun search() {
        searchJob?.cancel()
        val term = dialled.trim()
        if (term.length < 3) {
            matches = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            // Debounce: a keypad produces one keystroke per tap, and the search
            // is rate-limited server-side.
            delay(250)
            matches = runCatching { Graph.phone.contacts(term) }.getOrDefault(emptyList())
        }
    }
}
