package com.bluefoxconsultant.sms.data

import android.content.Context
import androidx.annotation.StringRes
import com.bluefoxconsultant.sms.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a swipe does, per direction, per half of the app.
 *
 * Defaults match what shipped before this was configurable, so nobody's
 * muscle memory changes by upgrading.
 */
enum class SwipeAction(val key: String, @StringRes val labelRes: Int) {
    NONE("none", R.string.swipe_none),
    ARCHIVE("archive", R.string.common_archive),
    SNOOZE("snooze", R.string.common_snooze),
    MARK_READ("read", R.string.common_mark_read),
    ;

    companion object {
        fun from(key: String?, fallback: SwipeAction): SwipeAction =
            entries.firstOrNull { it.key == key } ?: fallback

        /** Snoozing needs a date, which SMS has no notion of. */
        val forSms = listOf(NONE, ARCHIVE)
        val forMail = entries.toList()
    }
}

/**
 * An action that can sit in a thread's top bar instead of inside the ⋯ menu.
 *
 * Which of these deserves a permanent button is not a design decision someone
 * else should make for you: a person triaging invoices wants "créer une
 * facture" one tap away, and someone running a helpdesk wants "billet".
 */
enum class QuickAction(val key: String, @StringRes val labelRes: Int) {
    ARCHIVE("archive", R.string.common_archive),
    SNOOZE("snooze", R.string.common_snooze),
    ROUTE("route", R.string.quick_action_route),
    TASK("task", R.string.quick_action_task),
    MARK_READ("read", R.string.common_mark_read),
    ;

    companion object {
        /** Two buttons plus ⋯ is what fits beside a subject line. */
        const val MAX_IN_BAR = 2
        val DEFAULTS = setOf(ARCHIVE)

        /**
         * Add or remove [action], keeping at most [MAX_IN_BAR].
         *
         * Over the cap the *oldest* pick goes, not the new one: a tap that
         * appeared to do nothing would read as a bug, and every action stays
         * reachable under ⋯ regardless.
         */
        fun toggle(current: Set<QuickAction>, action: QuickAction): Set<QuickAction> =
            if (action in current) current - action
            else (current.toList().takeLast(MAX_IN_BAR - 1) + action).toSet()

        fun from(keys: Set<String>?): Set<QuickAction> =
            keys?.mapNotNull { k -> entries.firstOrNull { it.key == k } }?.toSet()
                ?: DEFAULTS
    }
}

data class SwipeConfig(
    val mailStart: SwipeAction = SwipeAction.ARCHIVE,
    val mailEnd: SwipeAction = SwipeAction.ARCHIVE,
    val smsStart: SwipeAction = SwipeAction.ARCHIVE,
    val smsEnd: SwipeAction = SwipeAction.ARCHIVE,
)

/** Les choix offerts pour le délai d'annulation d'un envoi, en secondes. */
val DELAIS_ANNULATION = listOf(0, 5, 10, 20, 30)
const val DELAI_ANNULATION_DEFAUT = 10

class UiPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("bf_swipe", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val configFlow: StateFlow<SwipeConfig> = _config.asStateFlow()
    val config: SwipeConfig get() = _config.value

    /**
     * Fold a conversation into one row, or show every message separately.
     *
     * On by default: folding is what makes this a mail app rather than a
     * message log. But a mailbox where the same subject line recurs for
     * unrelated matters — tickets, forms, automated reports — reads better
     * flat, so it is a preference and not a conviction.
     */
    private val _threadView = MutableStateFlow(prefs.getBoolean(THREAD_VIEW, true))
    val threadViewFlow: StateFlow<Boolean> = _threadView.asStateFlow()
    val threadView: Boolean get() = _threadView.value

    /**
     * Compter les entretiens en retard parmi les alertes d'hébergement.
     *
     * ⚠️ Faux par défaut, et c'est délibéré : le parc en porte des dizaines en
     * permanence, donc les inclure allume la bannière en continu et lui retire
     * tout pouvoir de dire « regarde MAINTENANT ». Une panne, un disque plein
     * ou une sauvegarde en retard sont des faits ; un entretien dû est une
     * intention. Les ralentissements, eux, restent toujours là — un service
     * dégradé compte comme hors ligne côté serveur.
     */
    private val _hostingMaintenance = MutableStateFlow(
        prefs.getBoolean(HOSTING_MAINTENANCE, false),
    )
    val hostingMaintenanceFlow: StateFlow<Boolean> = _hostingMaintenance.asStateFlow()
    val hostingMaintenance: Boolean get() = _hostingMaintenance.value

    fun setHostingMaintenance(enabled: Boolean) {
        prefs.edit().putBoolean(HOSTING_MAINTENANCE, enabled).apply()
        _hostingMaintenance.value = enabled
    }

    /**
     * Montrer le message d'origine sous le texte d'une réponse (#25764).
     *
     * Vrai par défaut : ne pas voir ce à quoi l'on répond était l'irritant.
     * Un réglage et non une conviction, retenu d'un message à l'autre, et
     * basculé depuis le menu du composeur même, là où l'on en a besoin.
     */
    private val _voirOriginal = MutableStateFlow(prefs.getBoolean(VOIR_ORIGINAL, true))
    val voirOriginalFlow: StateFlow<Boolean> = _voirOriginal.asStateFlow()
    val voirOriginal: Boolean get() = _voirOriginal.value

    fun setVoirOriginal(enabled: Boolean) {
        prefs.edit().putBoolean(VOIR_ORIGINAL, enabled).apply()
        _voirOriginal.value = enabled
    }

    /**
     * Les secondes pendant lesquelles un envoi peut encore être annulé (#25764).
     * 0 : l'envoi part au toucher, comme avant.
     */
    private val _delaiAnnulation = MutableStateFlow(
        prefs.getInt(DELAI_ANNULATION, DELAI_ANNULATION_DEFAUT),
    )
    val delaiAnnulationFlow: StateFlow<Int> = _delaiAnnulation.asStateFlow()
    val delaiAnnulation: Int get() = _delaiAnnulation.value

    fun setDelaiAnnulation(secondes: Int) {
        val borne = secondes.coerceIn(0, 60)
        prefs.edit().putInt(DELAI_ANNULATION, borne).apply()
        _delaiAnnulation.value = borne
    }

    private val _quick = MutableStateFlow(
        QuickAction.from(prefs.getStringSet(QUICK_ACTIONS, null)),
    )
    val quickActionsFlow: StateFlow<Set<QuickAction>> = _quick.asStateFlow()

    /**
     * Capped rather than validated-and-rejected: silently keeping the first
     * two is friendlier than an error, and the ⋯ menu still holds everything.
     */
    fun setQuickActions(actions: Set<QuickAction>) {
        val capped = actions.take(QuickAction.MAX_IN_BAR).toSet()
        prefs.edit().putStringSet(QUICK_ACTIONS, capped.map { it.key }.toSet()).apply()
        _quick.value = capped
    }

    fun setThreadView(enabled: Boolean) {
        prefs.edit().putBoolean(THREAD_VIEW, enabled).apply()
        _threadView.value = enabled
    }

    private fun load() = SwipeConfig(
        mailStart = SwipeAction.from(prefs.getString(MAIL_START, null), SwipeAction.ARCHIVE),
        mailEnd = SwipeAction.from(prefs.getString(MAIL_END, null), SwipeAction.ARCHIVE),
        smsStart = SwipeAction.from(prefs.getString(SMS_START, null), SwipeAction.ARCHIVE),
        smsEnd = SwipeAction.from(prefs.getString(SMS_END, null), SwipeAction.ARCHIVE),
    )

    fun save(config: SwipeConfig) {
        prefs.edit()
            .putString(MAIL_START, config.mailStart.key)
            .putString(MAIL_END, config.mailEnd.key)
            .putString(SMS_START, config.smsStart.key)
            .putString(SMS_END, config.smsEnd.key)
            .apply()
        _config.value = config
    }

    private companion object {
        const val MAIL_START = "mail_start"
        const val MAIL_END = "mail_end"
        const val SMS_START = "sms_start"
        const val SMS_END = "sms_end"
        const val THREAD_VIEW = "thread_view"
        const val QUICK_ACTIONS = "quick_actions"
        const val HOSTING_MAINTENANCE = "hosting_maintenance"
        const val VOIR_ORIGINAL = "compose_show_original"
        const val DELAI_ANNULATION = "undo_send_seconds"
    }
}
