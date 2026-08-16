package com.bluefoxconsultant.sms.ui.instance

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.bluefoxconsultant.sms.data.Graph

class InstanceViewModel : ViewModel() {

    var error by mutableStateOf<String?>(null)
        private set

    /** Normalise, validate, and store the instance URL. Returns true on success. */
    fun submit(input: String) {
        val normalized = normalize(input)
        if (normalized == null) {
            error = "Adresse invalide."
            return
        }
        error = null
        // Saving flips the instance flow → navigation routes to the Login screen.
        Graph.tokenStore.saveInstance(normalized)
    }

    companion object {
        fun normalize(input: String): String? {
            var s = input.trim()
            if (s.isEmpty()) return null
            if (!s.startsWith("http://", ignoreCase = true) &&
                !s.startsWith("https://", ignoreCase = true)
            ) {
                s = "https://$s"
            }
            s = s.trimEnd('/')
            // Require a non-empty host after the scheme.
            val host = s.substringAfter("://", "")
            if (host.isBlank() || !host.contains(".")) return null
            return s
        }
    }
}
