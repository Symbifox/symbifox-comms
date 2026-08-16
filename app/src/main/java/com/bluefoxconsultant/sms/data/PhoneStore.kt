package com.bluefoxconsultant.sms.data

import com.bluefoxconsultant.sms.network.PhoneRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The phone capability, fetched once per session and held in memory.
 *
 * Deliberately not persisted: it depends on the user's group membership and on
 * the PBX link being configured, both of which can change server-side between
 * two launches. A stale "yes" on disk would show a call button that fails; one
 * cheap request at first use is the honest answer.
 */
class PhoneStore(private val repo: PhoneRepository) {

    private val _config = MutableStateFlow(PhoneConfig())
    val config: StateFlow<PhoneConfig> = _config.asStateFlow()

    private val mutex = Mutex()
    private var loaded = false

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            // A missing module answers 404 and an unauthorised device 401. Both
            // mean "no phone here", which is a disabled button, not an error to
            // put in front of the user.
            _config.value = runCatching { repo.config() }.getOrDefault(PhoneConfig())
            loaded = true
        }
    }

    /** After a logout or an instance change, the answer has to be asked again. */
    fun invalidate() {
        loaded = false
        _config.value = PhoneConfig()
    }
}
