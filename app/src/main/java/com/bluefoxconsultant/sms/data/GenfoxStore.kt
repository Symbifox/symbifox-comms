package com.bluefoxconsultant.sms.data

import com.bluefoxconsultant.sms.network.GenfoxRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Whether the assistant is offered here, asked once per session. */
class GenfoxStore(private val repo: GenfoxRepository) {

    private val _config = MutableStateFlow(GenfoxConfig())
    val config: StateFlow<GenfoxConfig> = _config.asStateFlow()

    private val mutex = Mutex()
    private var loaded = false

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            // `loaded` seulement sur réponse : voir `AgendaStore`.
            val config = repo.config()
            _config.value = config ?: GenfoxConfig()
            loaded = config != null
        }
    }

    fun invalidate() {
        loaded = false
        _config.value = GenfoxConfig()
    }
}
