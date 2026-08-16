package com.bluefoxconsultant.sms.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.Service
import com.bluefoxconsultant.sms.data.ThemeMode
import kotlinx.coroutines.launch

class SettingsViewModel : ViewModel() {

    val serverUrl: String = Graph.tokenStore.instanceUrl.orEmpty()

    /** Same Odoo user on both halves; take whichever service is signed in. */
    val userName: String = (Graph.tokenStore.userNameFor(Service.SMS)
        ?: Graph.tokenStore.userNameFor(Service.MAIL)).orEmpty()

    fun setTheme(mode: ThemeMode) {
        Graph.tokenStore.saveThemeMode(mode)
    }

    /**
     * Signs out of both halves. One login gave both tokens, so one "Se
     * déconnecter" has to revoke both — leaving the other tab live would look
     * like the button hadn't worked.
     */
    fun logout() {
        viewModelScope.launch {
            val store = Graph.tokenStore
            if (store.tokenFor(Service.SMS) != null) runCatching { Graph.sms.logout() }
            if (store.tokenFor(Service.MAIL) != null) runCatching { Graph.mail.logout() }
            // Cached bodies and any queued actions belong to the session that
            // made them; neither should greet the next person to sign in.
            Graph.mailCache.clear()
            Graph.outbox.clear()
            // The next person to sign in may work for someone else.
            Graph.brandStore.reset()
            store.clearAllTokens()
        }
    }
}
