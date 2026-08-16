package com.bluefoxconsultant.sms.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Contact
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.Line
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ComposeViewModel : ViewModel() {

    val lines: List<Line> = Graph.tokenStore.lines

    var selectedLineId by mutableStateOf(
        lines.firstOrNull { it.isDefault }?.id ?: lines.firstOrNull()?.id ?: 0,
    )
        private set

    var recipient by mutableStateOf("")
        private set
    var recipientName by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<Contact>>(emptyList())
        private set
    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var searchJob: Job? = null

    fun selectLine(id: Int) {
        selectedLineId = id
    }

    fun onRecipientChange(text: String) {
        recipient = text
        recipientName = null
        searchJob?.cancel()
        val term = text.trim()
        if (term.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(250)
                try {
                    suggestions = Graph.sms.contacts(term)
                } catch (e: Exception) {
                    suggestions = emptyList()
                }
            }
        } else {
            suggestions = emptyList()
        }
    }

    fun pickContact(contact: Contact) {
        recipient = contact.bestNumber
        recipientName = contact.name
        suggestions = emptyList()
    }

    fun onMessageChange(text: String) {
        message = text
    }

    fun send(onSent: (Int) -> Unit) {
        val phone = recipient.trim()
        val body = message.trim()
        when {
            phone.isBlank() -> { error = "Entrez un destinataire."; return }
            body.isBlank() -> { error = "Entrez un message."; return }
            selectedLineId == 0 -> { error = "Aucune ligne disponible."; return }
            sending -> return
        }
        sending = true
        error = null
        viewModelScope.launch {
            try {
                val resp = Graph.sms.sendNew(phone, selectedLineId, body)
                sending = false
                if (resp.threadId > 0) {
                    onSent(resp.threadId)
                } else {
                    error = "Échec de l'envoi."
                }
            } catch (e: Exception) {
                sending = false
                error = "Échec de l'envoi."
            }
        }
    }
}
