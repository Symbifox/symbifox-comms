package com.bluefoxconsultant.sms.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persists the chosen Odoo instance URL, one bearer token **per service**, and
 * the transient OAuth-style `state`. Backed by EncryptedSharedPreferences.
 *
 * ⚠️ Plus de repli en clair : quand le Keystore refuse, les secrets restent en
 * mémoire le temps du processus et [stockageDegrade] le dit. Voir
 * [ouvrirMagasins].
 *
 * Two tokens, not one: the app talks to two independent Odoo modules, and a
 * revoked or expired mail token must not sign the user out of the SMS tab.
 */
class TokenStore internal constructor(private val magasins: Magasins) {

    constructor(context: Context) : this(ouvrirMagasins(context))

    private val secrets get() = magasins.secrets
    private val reglages get() = magasins.reglages

    /**
     * Le magasin chiffré ne s'est pas ouvert : les sessions de ce processus ne
     * survivront pas à sa fin, et il faudra se reconnecter au prochain
     * lancement.
     */
    val stockageDegrade: Boolean get() = magasins.degrade

    private val avisDegradeDonne = AtomicBoolean(false)

    /**
     * Vrai UNE fois par processus, et seulement en stockage dégradé : c'est
     * l'écran qui le dit, pas chaque écran.
     */
    fun prendreAvisDegrade(): Boolean =
        magasins.degrade && avisDegradeDonne.compareAndSet(false, true)

    /**
     * Toute écriture qui touche [_tokens] passe par ce verrou, avec la mise à
     * jour du disque. Deux 401 simultanés — un par moitié, depuis deux fils
     * réseau — lisaient la même carte et chacun réécrivait la sienne : l'un des
     * deux effacements se perdait (Q-m2, audit du 2026-09-08).
     */
    private val verrou = Any()

    init {
        migrateSingleToken()
    }

    /**
     * Pre-2.0 installs stored one token under `token`, which was always the
     * SMS one. Move it across so upgrading doesn't silently sign everybody out.
     */
    private fun migrateSingleToken() {
        val legacy = secrets.getString(KEY_LEGACY_TOKEN, null) ?: return
        val edit = secrets.edit()
        if (secrets.getString(tokenKey(Service.SMS), null) == null) {
            edit.putString(tokenKey(Service.SMS), legacy)
            secrets.getString(KEY_LEGACY_USER, null)?.let {
                edit.putString(userKey(Service.SMS), it)
            }
        }
        edit.remove(KEY_LEGACY_TOKEN).remove(KEY_LEGACY_USER).apply()
    }

    private val _instance = MutableStateFlow(reglages.getString(KEY_INSTANCE, null))
    val instanceFlow: StateFlow<String?> = _instance.asStateFlow()
    val instanceUrl: String? get() = _instance.value

    // ---- tokens, one per service ----

    private val _tokens = MutableStateFlow(loadTokens())
    val tokensFlow: StateFlow<Map<Service, String>> = _tokens.asStateFlow()

    private fun loadTokens(): Map<Service, String> = Service.entries
        .mapNotNull { svc -> secrets.getString(tokenKey(svc), null)?.let { svc to it } }
        .toMap()

    fun tokenFor(service: Service): String? = _tokens.value[service]

    fun userNameFor(service: Service): String? = secrets.getString(userKey(service), null)

    /** True once at least one service is signed in — drives top-level navigation. */
    val isSignedIn: Boolean get() = _tokens.value.isNotEmpty()

    /** Services the user actually has a session for. */
    val signedInServices: Set<Service> get() = _tokens.value.keys

    fun saveToken(service: Service, token: String, userName: String?) {
        synchronized(verrou) {
            secrets.edit()
                .putString(tokenKey(service), token)
                .putString(userKey(service), userName)
                .apply()
            _tokens.update { it + (service to token) }
        }
    }

    /**
     * Drops one service's session. Keeps the instance URL and the other token.
     *
     * [siJeton] : n'efface que si c'est ENCORE ce jeton-là. Un 401 qui revient
     * d'une requête partie avec l'ancien jeton ne doit pas effacer celui qu'on
     * vient d'obtenir en se reconnectant entre-temps. Rend vrai si la session
     * a bel et bien été retirée.
     */
    fun clearToken(service: Service, siJeton: String? = null): Boolean {
        synchronized(verrou) {
            if (siJeton != null && _tokens.value[service] != siJeton) return false
            val edit = secrets.edit()
                .remove(tokenKey(service))
                .remove(userKey(service))
            if (service == Service.SMS) edit.remove(KEY_LINES)
            edit.apply()
            // Ce que ce serveur chiffrait valait pour CETTE session : une
            // reconnexion le redira à la prochaine inscription.
            reglages.edit().remove(webpushKey(service)).apply()
            _tokens.update { it - service }
        }
        return true
    }

    /** Full sign-out across both services. */
    fun clearAllTokens() {
        synchronized(verrou) {
            val edit = secrets.edit().remove(KEY_LINES)
            Service.entries.forEach { edit.remove(tokenKey(it)).remove(userKey(it)) }
            edit.apply()
            val reglage = reglages.edit()
            Service.entries.forEach { reglage.remove(webpushKey(it)) }
            reglage.apply()
            _tokens.update { emptyMap() }
        }
    }

    // ---- push chiffré : ce que chaque serveur chiffre toujours ----

    /**
     * Retient les types que [service] chiffre désormais, tels que
     * `/register_push` vient de les donner.
     *
     * Ignoré si la session de ce service n'existe plus : une inscription
     * partie avant une déconnexion ne doit pas ressusciter l'ensemble que la
     * déconnexion vient de vider.
     */
    fun saveWebpushTypes(service: Service, types: Set<String>) {
        synchronized(verrou) {
            if (_tokens.value[service] == null) return
            reglages.edit().putStringSet(webpushKey(service), types.toMutableSet()).apply()
        }
    }

    /** Vide pour un serveur ancien, ou tant qu'aucune inscription n'a répondu. */
    fun webpushTypesFor(service: Service): Set<String> =
        reglages.getStringSet(webpushKey(service), null)?.toSet().orEmpty()

    // ---- which services this instance actually offers (from /ping) ----

    private val _available = MutableStateFlow(loadAvailable())
    val availableFlow: StateFlow<Set<Service>> = _available.asStateFlow()
    val available: Set<Service> get() = _available.value

    private fun loadAvailable(): Set<Service> {
        val raw = reglages.getString(KEY_AVAILABLE, null)
            // No probe recorded yet (fresh install, or upgraded from 1.x where
            // only SMS existed): assume SMS so the tab bar isn't empty before
            // the first /ping lands.
            ?: return setOf(Service.SMS)
        return raw.split(",").mapNotNull { k ->
            Service.entries.firstOrNull { it.key == k }
        }.toSet()
    }

    fun saveAvailable(services: Set<Service>) {
        reglages.edit().putString(KEY_AVAILABLE, services.joinToString(",") { it.key }).apply()
        _available.value = services
    }

    /** Transiently held (persisted so it survives Custom Tab process death). */
    val pendingState: String? get() = secrets.getString(KEY_PENDING_STATE, null)

    /** Which service the in-flight login leg belongs to. */
    val pendingService: Service?
        get() = secrets.getString(KEY_PENDING_SERVICE, null)
            ?.let { k -> Service.entries.firstOrNull { it.key == k } }

    /**
     * The PKCE verifier of the in-flight leg.
     *
     * Persisted for the same reason as the state: the Custom Tab often evicts
     * this process, and a verifier held in a field would be gone by the time
     * the browser comes back. It never travels through the deep link — only in
     * the exchange body, over HTTPS.
     *
     * ⚠️ En stockage dégradé, il vit en mémoire : un processus évincé pendant
     * l'onglet perd l'appariement en cours, qui échoue alors proprement sur le
     * `state` au retour. C'est le prix de ne rien écrire en clair.
     */
    val pendingVerifier: String? get() = secrets.getString(KEY_PENDING_VERIFIER, null)

    fun savePendingLeg(service: Service, state: String, verifier: String) {
        secrets.edit()
            .putString(KEY_PENDING_STATE, state)
            .putString(KEY_PENDING_SERVICE, service.key)
            .putString(KEY_PENDING_VERIFIER, verifier)
            .apply()
    }

    fun clearPendingState() {
        secrets.edit()
            .remove(KEY_PENDING_STATE)
            .remove(KEY_PENDING_SERVICE)
            .remove(KEY_PENDING_VERIFIER)
            .apply()
    }

    // ---- send-from lines / numbers (SMS only) ----

    private val linesJson = Json { ignoreUnknownKeys = true }

    val lines: List<Line>
        get() = try {
            val raw = secrets.getString(KEY_LINES, null) ?: return emptyList()
            linesJson.decodeFromString(ListSerializer(Line.serializer()), raw)
        } catch (e: Exception) {
            emptyList()
        }

    fun saveLines(lines: List<Line>) {
        val raw = linesJson.encodeToString(ListSerializer(Line.serializer()), lines)
        secrets.edit().putString(KEY_LINES, raw).apply()
    }

    // ---- appearance ----

    private val _themeMode = MutableStateFlow(ThemeMode.from(reglages.getString(KEY_THEME, null)))
    val themeModeFlow: StateFlow<ThemeMode> = _themeMode.asStateFlow()
    val themeMode: ThemeMode get() = _themeMode.value

    fun saveThemeMode(mode: ThemeMode) {
        reglages.edit().putString(KEY_THEME, mode.stored).apply()
        _themeMode.value = mode
    }

    fun saveInstance(url: String) {
        reglages.edit().putString(KEY_INSTANCE, url).apply()
        _instance.value = url
    }

    fun clearInstance() {
        reglages.edit().remove(KEY_INSTANCE).remove(KEY_AVAILABLE).apply()
        _available.value = setOf(Service.SMS)
        _instance.value = null
    }

    private companion object {
        // ⚠️ Les trois premières sont listées dans `estCleNonSecrete` : les
        // renommer ici sans y toucher là les ferait effacer en stockage dégradé.
        const val KEY_INSTANCE = "instance_url"
        const val KEY_THEME = "theme_mode"
        const val KEY_AVAILABLE = "available_services"
        const val KEY_PENDING_STATE = "pending_state"
        const val KEY_PENDING_SERVICE = "pending_service"
        const val KEY_PENDING_VERIFIER = "pending_verifier"
        const val KEY_LINES = "lines"

        // Pre-2.0 single-token keys, read once by migrateSingleToken().
        const val KEY_LEGACY_TOKEN = "token"
        const val KEY_LEGACY_USER = "user_name"

        fun tokenKey(service: Service) = "token_${service.key}"
        fun userKey(service: Service) = "user_name_${service.key}"
        fun webpushKey(service: Service) = PREFIXE_TYPES_CHIFFRES + service.key
    }
}
