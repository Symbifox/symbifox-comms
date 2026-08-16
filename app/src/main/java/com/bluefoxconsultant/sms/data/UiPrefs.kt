package com.bluefoxconsultant.sms.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a swipe does, per direction, per half of the app.
 *
 * Defaults match what shipped before this was configurable, so nobody's
 * muscle memory changes by upgrading.
 */
enum class SwipeAction(val key: String, val label: String) {
    NONE("none", "Rien"),
    ARCHIVE("archive", "Archiver"),
    SNOOZE("snooze", "Reporter"),
    MARK_READ("read", "Marquer lu"),
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
enum class QuickAction(val key: String, val label: String) {
    ARCHIVE("archive", "Archiver"),
    SNOOZE("snooze", "Reporter"),
    ROUTE("route", "Router"),
    TASK("task", "Créer une tâche"),
    MARK_READ("read", "Marquer lu"),
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
    }
}
