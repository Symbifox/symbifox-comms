package com.bluefoxconsultant.sms.data

import com.bluefoxconsultant.sms.network.SpeechRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Whether this instance offers dictation, asked once per session.
 *
 * Not persisted, for the same reason as the phone capability: it depends on
 * server-side configuration that can change between two launches, and a stale
 * "yes" on disk means a microphone that records and then fails.
 */
class SpeechStore(private val repo: SpeechRepository) {

    private val _config = MutableStateFlow(SpeechConfig())
    val config: StateFlow<SpeechConfig> = _config.asStateFlow()

    private val mutex = Mutex()
    private var loaded = false

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            // `loaded` seulement sur réponse : voir `AgendaStore`.
            val config = repo.config()
            _config.value = config ?: SpeechConfig()
            loaded = config != null
        }
    }

    fun invalidate() {
        loaded = false
        _config.value = SpeechConfig()
    }
}
