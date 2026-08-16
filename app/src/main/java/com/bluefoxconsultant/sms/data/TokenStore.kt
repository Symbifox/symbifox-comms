package com.bluefoxconsultant.sms.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persists the chosen Odoo instance URL, one bearer token **per service**, and
 * the transient OAuth-style `state`. Backed by EncryptedSharedPreferences
 * (falls back to plain prefs).
 *
 * Two tokens, not one: the app talks to two independent Odoo modules, and a
 * revoked or expired mail token must not sign the user out of the SMS tab.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "bf_sms_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.getSharedPreferences("bf_sms_plain", Context.MODE_PRIVATE)
    }

    init {
        migrateSingleToken()
    }

    /**
     * Pre-2.0 installs stored one token under `token`, which was always the
     * SMS one. Move it across so upgrading doesn't silently sign everybody out.
     */
    private fun migrateSingleToken() {
        val legacy = prefs.getString(KEY_LEGACY_TOKEN, null) ?: return
        val edit = prefs.edit()
        if (prefs.getString(tokenKey(Service.SMS), null) == null) {
            edit.putString(tokenKey(Service.SMS), legacy)
            prefs.getString(KEY_LEGACY_USER, null)?.let {
                edit.putString(userKey(Service.SMS), it)
            }
        }
        edit.remove(KEY_LEGACY_TOKEN).remove(KEY_LEGACY_USER).apply()
    }

    private val _instance = MutableStateFlow(prefs.getString(KEY_INSTANCE, null))
    val instanceFlow: StateFlow<String?> = _instance.asStateFlow()
    val instanceUrl: String? get() = _instance.value

    // ---- tokens, one per service ----

    private val _tokens = MutableStateFlow(loadTokens())
    val tokensFlow: StateFlow<Map<Service, String>> = _tokens.asStateFlow()

    private fun loadTokens(): Map<Service, String> = Service.entries
        .mapNotNull { svc -> prefs.getString(tokenKey(svc), null)?.let { svc to it } }
        .toMap()

    fun tokenFor(service: Service): String? = _tokens.value[service]

    fun userNameFor(service: Service): String? = prefs.getString(userKey(service), null)

    /** True once at least one service is signed in — drives top-level navigation. */
    val isSignedIn: Boolean get() = _tokens.value.isNotEmpty()

    /** Services the user actually has a session for. */
    val signedInServices: Set<Service> get() = _tokens.value.keys

    fun saveToken(service: Service, token: String, userName: String?) {
        prefs.edit()
            .putString(tokenKey(service), token)
            .putString(userKey(service), userName)
            .apply()
        _tokens.value = _tokens.value + (service to token)
    }

    /** Drops one service's session. Keeps the instance URL and the other token. */
    fun clearToken(service: Service) {
        val edit = prefs.edit()
            .remove(tokenKey(service))
            .remove(userKey(service))
        if (service == Service.SMS) edit.remove(KEY_LINES)
        edit.apply()
        _tokens.value = _tokens.value - service
    }

    /** Full sign-out across both services. */
    fun clearAllTokens() {
        val edit = prefs.edit().remove(KEY_LINES)
        Service.entries.forEach { edit.remove(tokenKey(it)).remove(userKey(it)) }
        edit.apply()
        _tokens.value = emptyMap()
    }

    // ---- which services this instance actually offers (from /ping) ----

    private val _available = MutableStateFlow(loadAvailable())
    val availableFlow: StateFlow<Set<Service>> = _available.asStateFlow()
    val available: Set<Service> get() = _available.value

    private fun loadAvailable(): Set<Service> {
        val raw = prefs.getString(KEY_AVAILABLE, null)
            // No probe recorded yet (fresh install, or upgraded from 1.x where
            // only SMS existed): assume SMS so the tab bar isn't empty before
            // the first /ping lands.
            ?: return setOf(Service.SMS)
        return raw.split(",").mapNotNull { k ->
            Service.entries.firstOrNull { it.key == k }
        }.toSet()
    }

    fun saveAvailable(services: Set<Service>) {
        prefs.edit().putString(KEY_AVAILABLE, services.joinToString(",") { it.key }).apply()
        _available.value = services
    }

    /** Transiently held (persisted so it survives Custom Tab process death). */
    val pendingState: String? get() = prefs.getString(KEY_PENDING_STATE, null)

    /** Which service the in-flight login leg belongs to. */
    val pendingService: Service?
        get() = prefs.getString(KEY_PENDING_SERVICE, null)
            ?.let { k -> Service.entries.firstOrNull { it.key == k } }

    fun savePendingLeg(service: Service, state: String) {
        prefs.edit()
            .putString(KEY_PENDING_STATE, state)
            .putString(KEY_PENDING_SERVICE, service.key)
            .apply()
    }

    fun clearPendingState() {
        prefs.edit().remove(KEY_PENDING_STATE).remove(KEY_PENDING_SERVICE).apply()
    }

    // ---- send-from lines / numbers (SMS only) ----

    private val linesJson = Json { ignoreUnknownKeys = true }

    val lines: List<Line>
        get() = try {
            val raw = prefs.getString(KEY_LINES, null) ?: return emptyList()
            linesJson.decodeFromString(ListSerializer(Line.serializer()), raw)
        } catch (e: Exception) {
            emptyList()
        }

    fun saveLines(lines: List<Line>) {
        val raw = linesJson.encodeToString(ListSerializer(Line.serializer()), lines)
        prefs.edit().putString(KEY_LINES, raw).apply()
    }

    // ---- appearance ----

    private val _themeMode = MutableStateFlow(ThemeMode.from(prefs.getString(KEY_THEME, null)))
    val themeModeFlow: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    val themeMode: ThemeMode get() = _themeMode.value

    fun saveThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.stored).apply()
        _themeMode.value = mode
    }

    fun saveInstance(url: String) {
        prefs.edit().putString(KEY_INSTANCE, url).apply()
        _instance.value = url
    }

    fun clearInstance() {
        prefs.edit().remove(KEY_INSTANCE).remove(KEY_AVAILABLE).apply()
        _available.value = setOf(Service.SMS)
        _instance.value = null
    }

    private companion object {
        const val KEY_INSTANCE = "instance_url"
        const val KEY_PENDING_STATE = "pending_state"
        const val KEY_PENDING_SERVICE = "pending_service"
        const val KEY_LINES = "lines"
        const val KEY_THEME = "theme_mode"
        const val KEY_AVAILABLE = "available_services"

        // Pre-2.0 single-token keys, read once by migrateSingleToken().
        const val KEY_LEGACY_TOKEN = "token"
        const val KEY_LEGACY_USER = "user_name"

        fun tokenKey(service: Service) = "token_${service.key}"
        fun userKey(service: Service) = "user_name_${service.key}"
    }
}
