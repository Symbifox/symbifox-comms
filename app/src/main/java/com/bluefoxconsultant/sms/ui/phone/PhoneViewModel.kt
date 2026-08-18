package com.bluefoxconsultant.sms.ui.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.ActiveCall
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

    /**
     * Ce qui est tapé dans la recherche par nom, quand elle est ouverte.
     *
     * Un clavier téléphonique ne produit que des chiffres, et le carnet
     * d'adresses est côté serveur : sans champ de texte, un contact dont on ne
     * connaît pas le numéro était inatteignable depuis cet écran.
     */
    var query by mutableStateOf("")
        private set
    var matches by mutableStateOf<List<PhoneContact>>(emptyList())
        private set
    var calls by mutableStateOf<List<CallLogEntry>>(emptyList())
        private set
    var placing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
        private set

    var active by mutableStateOf<List<ActiveCall>>(emptyList())
        private set
    var hangingUp by mutableStateOf(false)
        private set

    private var searchJob: Job? = null
    private var watchJob: Job? = null

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
        // Par search() plutôt que d'effacer : le « C » vide le numéro, il ne
        // doit pas emporter une recherche par nom ouverte à côté.
        search()
    }

    fun set(number: String) {
        dialled = number
        query = ""
        matches = emptyList()
    }

    fun searchName(text: String) {
        query = text
        search()
    }

    fun closeSearch() {
        query = ""
        matches = emptyList()
    }

    /**
     * Watches for a call in progress while the screen is open.
     *
     * The handset does not carry the call, so it cannot know on its own that
     * one is up: the PBX is the only source of truth. Polling stops with the
     * screen — nothing runs in the background.
     */
    fun watch() {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (true) {
                active = runCatching { Graph.phone.active() }.getOrDefault(active)
                // Faster while something is up, so the timer looks alive and a
                // hangup elsewhere is noticed quickly.
                delay(if (active.isEmpty()) IDLE_POLL_MS else BUSY_POLL_MS)
            }
        }
    }

    fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
    }

    var dtmfSent by mutableStateOf("")
        private set

    /** A key pressed during a call answers the menu; it does not dial. */
    fun sendDtmf(key: Char) {
        viewModelScope.launch {
            try {
                Graph.phone.dtmf(key.toString())
                dtmfSent = (dtmfSent + key).takeLast(16)
            } catch (e: Exception) {
                error = "Touche non transmise."
            }
        }
    }

    fun clearDtmf() { dtmfSent = "" }

    fun hangup() {
        if (hangingUp) return
        viewModelScope.launch {
            hangingUp = true
            try {
                Graph.phone.hangup()
                active = emptyList()
                // The log gains a line once the call ends.
                refresh()
            } catch (e: Exception) {
                error = "Impossible de raccrocher."
            } finally {
                hangingUp = false
            }
        }
    }

    /** Vrai pendant que le journal se recharge, pour le geste « tirer ». */
    var refreshingCalls by mutableStateOf(false)
        private set

    fun refresh() {
        viewModelScope.launch {
            refreshingCalls = true
            try {
                calls = Graph.phone.calls()
            } catch (e: Exception) {
                // A missing log is not worth an error banner over a keypad that
                // otherwise works; only say so if nothing at all can be reached.
                if (calls.isEmpty()) error = "Journal d'appels indisponible."
            } finally {
                refreshingCalls = false
            }
        }
    }

    private fun search() {
        searchJob?.cancel()
        val byName = query.isNotBlank()
        val term = (if (byName) query else dialled).trim()
        // Un nom se cherche dès deux lettres — c'est le minimum qu'accepte le
        // serveur. Un numéro attend trois chiffres : plus tôt, chaque touche du
        // clavier partirait chercher une liste qui n'apprend rien.
        if (term.length < (if (byName) 2 else 3)) {
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

    override fun onCleared() {
        watchJob?.cancel()
        searchJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val IDLE_POLL_MS = 4_000L
        const val BUSY_POLL_MS = 1_500L
    }
}
