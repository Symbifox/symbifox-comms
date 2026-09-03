package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.ui.ancreARattraper
import com.bluefoxconsultant.sms.ui.relectureUtile
import com.bluefoxconsultant.sms.data.AgendaCalendar
import com.bluefoxconsultant.sms.data.AgendaConfig
import com.bluefoxconsultant.sms.data.AgendaEvent
import com.bluefoxconsultant.sms.data.Graph
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

private const val LIST_DAYS = 20L

enum class AgendaMode {
    DAY,
    WEEK,

    /**
     * La liste : les mêmes événements, à la file, sans grille horaire.
     *
     * Elle n'est pas un repli de la semaine, c'est une autre façon de lire.
     * Sur un agenda peu dense — 9 rencontres sur quatorze jours ici — la grille
     * montre surtout du vide, et la liste montre surtout les rencontres.
     */
    LIST,
}

/**
 * La grille, et ce qu'il faut pour la remplir.
 *
 * La fenêtre demandée déborde d'un jour de chaque côté : une rencontre
 * commencée la veille à 22:00 traverse minuit, et la couper à la borne la
 * ferait disparaître de la colonne où elle est pourtant visible.
 */
class AgendaViewModel : ViewModel() {

    /** Le fuseau de l'APPAREIL : c'est l'heure que la personne lit sur elle. */
    val zone: ZoneId = ZoneId.systemDefault()

    var mode by mutableStateOf(AgendaMode.WEEK)
        private set
    var anchor by mutableStateOf(LocalDate.now(zone))
        private set
    var events by mutableStateOf<List<AgendaEvent>>(emptyList())
        private set
    var taskCounts by mutableStateOf<Map<LocalDate, Int>>(emptyMap())
        private set
    var config by mutableStateOf(AgendaConfig())
        private set
    var loading by mutableStateOf(false)
        private set

    /** Le geste de tirer, distinct de [loading] : il a son propre indicateur. */
    var refreshing by mutableStateOf(false)
        private set

    /** Horodatage de la dernière lecture RÉUSSIE, et de la dernière tentative. */
    var lu by mutableStateOf(0L)
        private set
    var verifieA by mutableStateOf(0L)
        private set

    /**
     * L'ancre suit-elle « aujourd'hui » ?
     *
     * Vrai à l'ouverture et après le pictogramme du jour, faux dès qu'on
     * feuillette : c'est ce qui décide si minuit déplace la grille.
     */
    private var suitAujourdhui = true

    var error by mutableStateOf<String?>(null)
        private set
    var selected by mutableStateOf<AgendaEvent?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /**
     * Hauteur d'une heure, en points. Le pincement la fait varier, ce qui
     * revient à changer le NOMBRE D'HEURES visibles — la demande d'Olivier.
     * Bornée : sous 24 dp le titre d'une rencontre ne rentre plus, au-delà de
     * 160 dp on fait défiler une journée pour rien.
     */
    var hourHeight by mutableStateOf(56f)
        private set

    var calendars by mutableStateOf<List<AgendaCalendar>>(emptyList())
        private set
    var composing by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            Graph.agendaStore.ensureLoaded()
            config = Graph.agendaStore.config.value
            calendars = runCatching { Graph.agenda.calendars() }.getOrDefault(emptyList())
            load()
        }
    }

    /** Les jours affichés, dans l'ordre. */
    val days: List<LocalDate>
        get() = when (mode) {
            AgendaMode.DAY -> listOf(anchor)
            AgendaMode.WEEK -> {
                val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                (0L..6L).map { monday.plusDays(it) }
            }
            // La liste part du jour d'ancrage et regarde devant : chercher
            // dans le passé est le travail de la vue jour.
            AgendaMode.LIST -> (0L..LIST_DAYS).map { anchor.plusDays(it) }
        }

    fun switchMode(next: AgendaMode) {
        // Retoucher le mode déjà choisi relit : c'est la commande de relecture
        // la plus à portée, et elle ne coûte pas un pictogramme de plus dans un
        // en-tête déjà plein.
        if (next == mode) {
            refresh()
            return
        }
        mode = next
        load()
    }

    fun step(forward: Boolean) {
        val delta = when (mode) {
            AgendaMode.DAY -> 1L
            AgendaMode.WEEK -> 7L
            AgendaMode.LIST -> LIST_DAYS
        }
        anchor = if (forward) anchor.plusDays(delta) else anchor.minusDays(delta)
        suitAujourdhui = anchor == LocalDate.now(zone)
        load()
    }

    fun today() {
        anchor = LocalDate.now(zone)
        suitAujourdhui = true
        load()
    }

    fun open(event: AgendaEvent) {
        selected = event
        // La fiche complète (OdJ, compte rendu, participants) n'est pas dans la
        // liste : la charger d'un coup pour la semaine ferait sept requêtes de
        // trop pour un écran qu'on n'ouvre peut-être pas.
        viewModelScope.launch {
            val full = runCatching { Graph.agenda.event(event.id, event.key) }.getOrNull()
            if (full != null && selected?.key == event.key) selected = full
        }
    }

    fun close() { selected = null }

    fun openComposer() { composing = true }

    fun closeComposer() { composing = false }

    /** Le pincement, borné pour rester lisible aux deux bouts. */
    fun zoom(facteur: Float) {
        hourHeight = (hourHeight * facteur).coerceIn(24f, 160f)
    }

    fun createEvent(
        name: String,
        startUtc: Instant,
        stopUtc: Instant,
        location: String,
        videocall: String,
        calendarId: Int?,
    ) {
        viewModelScope.launch {
            busy = true
            val cree = runCatching {
                Graph.agenda.createEvent(name, startUtc, stopUtc, location,
                    videocall, calendarId)
            }.getOrNull()
            busy = false
            if (cree == null) {
                error = "La rencontre n'a pas été créée."
                return@launch
            }
            composing = false
            // On se place sur le jour de la rencontre créée : la créer puis
            // laisser l'écran sur une autre semaine donne l'impression que
            // rien ne s'est passé.
            cree.dayAt(zone)?.let {
                anchor = it
                suitAujourdhui = it == LocalDate.now(zone)
            }
            load()
        }
    }

    fun setFlags(event: AgendaEvent, skipAgenda: Boolean? = null,
                 skipDashboard: Boolean? = null) {
        viewModelScope.launch {
            busy = true
            val maj = runCatching {
                Graph.agenda.setFlags(event.id, event.key, skipAgenda, skipDashboard)
            }.getOrNull()
            busy = false
            if (maj == null) {
                error = "L'exclusion n'a pas été enregistrée."
                return@launch
            }
            selected = maj
            load()
        }
    }

    fun clearError() { error = null }

    /** Une relecture demandée : la grille montre qu'elle travaille. */
    fun load() = charger(Regime.VISIBLE)

    /** Tirer pour relire. */
    fun refresh() = charger(Regime.TIRE)

    /**
     * Le battement : au retour à l'écran, puis à la minute.
     *
     * Il fait deux choses parce qu'elles ont la même cause — du temps a passé
     * sans que personne regarde : rattraper le jour si minuit est tombé, et
     * relire si la donnée affichée a vieilli.
     */
    fun tick() {
        val rattrapage = ancreARattraper(anchor, LocalDate.now(zone), suitAujourdhui)
        if (rattrapage != null) {
            anchor = rattrapage
            load()
            return
        }
        if (loading || refreshing) return
        if (!relectureUtile(System.currentTimeMillis(), lu)) return
        charger(Regime.SILENCIEUX)
    }

    private enum class Regime { VISIBLE, TIRE, SILENCIEUX }

    /**
     * ⚠️ Les témoins sont posés HORS de la coroutine.
     *
     * Posés dedans, ils n'existent qu'au prochain tour de la boucle
     * d'événements, et le battement de la minute peut se glisser entre les deux
     * pour lancer une seconde lecture de la même fenêtre.
     */
    private fun charger(regime: Regime) {
        when (regime) {
            Regime.VISIBLE -> loading = true
            Regime.TIRE -> refreshing = true
            Regime.SILENCIEUX -> Unit
        }
        if (regime != Regime.SILENCIEUX) error = null
        viewModelScope.launch {
            try {
                val visible = days
                val from = visible.first().minusDays(1).atStartOfDay(zone).toInstant()
                val to = visible.last().plusDays(2).atStartOfDay(zone).toInstant()
                events = Graph.agenda.events(from, to).events
                taskCounts = Graph.agenda.taskCounts(from, to).counts
                    .mapNotNull { (key, count) ->
                        runCatching { LocalDate.parse(key) }.getOrNull()?.let { it to count }
                    }.toMap()
                lu = System.currentTimeMillis()
                error = null
            } catch (e: Exception) {
                // Une relecture silencieuse qui échoue garde ce qui est affiché
                // plutôt que de vider l'écran sur une coupure de deux secondes.
                // Passé [PERIME_MS], l'en-tête le dit : voir `verifieA`.
                if (regime != Regime.SILENCIEUX) {
                    error = e.message ?: "Agenda indisponible."
                }
            } finally {
                verifieA = System.currentTimeMillis()
                loading = false
                refreshing = false
            }
        }
    }

    fun snooze(event: AgendaEvent, minutes: Int) = act {
        Graph.agenda.snooze(event.id, event.key, minutes)
    }

    fun dismiss(event: AgendaEvent) = act {
        Graph.agenda.dismiss(event.id, event.key)
    }

    fun rsvp(event: AgendaEvent, state: String) = act {
        Graph.agenda.rsvp(event.id, event.key, state)
    }

    /**
     * Un geste, puis on relit.
     *
     * On ne devine pas le nouvel état côté app : le serveur peut avoir résolu
     * l'événement par sa CLÉ vers une occurrence recréée, donc l'identifiant
     * gardé ici n'est pas forcément celui qui a bougé.
     */
    private fun act(block: suspend () -> Boolean) {
        viewModelScope.launch {
            busy = true
            val ok = runCatching { block() }.getOrDefault(false)
            busy = false
            if (!ok) {
                error = "Le geste n'a pas été enregistré."
                return@launch
            }
            val current = selected
            load()
            if (current != null) {
                val full = runCatching {
                    Graph.agenda.event(current.id, current.key)
                }.getOrNull()
                if (full != null) selected = full
            }
        }
    }
}
