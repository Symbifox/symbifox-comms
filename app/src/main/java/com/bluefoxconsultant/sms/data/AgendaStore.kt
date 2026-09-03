package com.bluefoxconsultant.sms.data

import com.bluefoxconsultant.sms.network.AgendaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * L'agenda est-il offert ici, et à quelles conditions.
 *
 * Demandé une fois par session : le `/ping` décide de l'onglet, `/config`
 * décide de ce que l'écran propose (reports offerts, présence de l'OdJ).
 */
class AgendaStore(private val repo: AgendaRepository) {

    private val _ping = MutableStateFlow(AgendaPing())
    val ping: StateFlow<AgendaPing> = _ping.asStateFlow()

    private val _config = MutableStateFlow(AgendaConfig())
    val config: StateFlow<AgendaConfig> = _config.asStateFlow()

    private val mutex = Mutex()
    private var loaded = false

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            _ping.value = repo.ping() ?: AgendaPing()
            if (_ping.value.enabled) {
                _config.value = repo.config() ?: AgendaConfig()
            }
            loaded = true
        }
    }

    fun invalidate() {
        loaded = false
        _ping.value = AgendaPing()
        _config.value = AgendaConfig()
    }
}
