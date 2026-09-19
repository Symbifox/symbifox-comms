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

    /**
     * Une tâche que quelqu'un veut voir dans l'onglet Tâches, venue d'ailleurs
     * — d'un courriel classé dessus. Même patron que `ShareIntake` : l'app
     * bascule d'onglet sur ce signal, et l'écran des tâches le CONSOMME une
     * fois qu'il a ouvert la fiche. Un identifiant laissé ici rouvrirait la
     * même fiche à chaque retour sur l'onglet.
     */
    private val _demandeTache = MutableStateFlow<Int?>(null)
    val demandeTache: StateFlow<Int?> = _demandeTache.asStateFlow()

    fun demanderTache(id: Int) { if (id > 0) _demandeTache.value = id }

    fun consommerTache() { _demandeTache.value = null }

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            // ⚠️ `loaded` seulement si le serveur a RÉPONDU. Une sonde qui
            // échoue hors ligne figeait « pas d'agenda » pour toute la vie du
            // processus ; l'accueil la rejoue à la minute tant qu'elle n'a
            // pas répondu. Audit du 2026-09-08.
            val ping = repo.ping()
            _ping.value = ping ?: AgendaPing()
            if (_ping.value.enabled) {
                _config.value = repo.config() ?: AgendaConfig()
            }
            loaded = ping != null
        }
    }

    fun invalidate() {
        loaded = false
        _ping.value = AgendaPing()
        _config.value = AgendaConfig()
    }
}
