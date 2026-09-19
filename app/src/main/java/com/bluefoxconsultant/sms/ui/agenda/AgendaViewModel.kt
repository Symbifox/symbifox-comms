package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.ui.Sequenceur
import com.bluefoxconsultant.sms.ui.ancreARattraper
import com.bluefoxconsultant.sms.ui.relectureUtile
import com.bluefoxconsultant.sms.data.AgendaCalendar
import com.bluefoxconsultant.sms.data.AgendaConfig
import com.bluefoxconsultant.sms.data.AgendaEvent
import com.bluefoxconsultant.sms.data.Graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import com.bluefoxconsultant.sms.data.AgendaPartner
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiText

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

    /**
     * Voir `Sequenceur` : la dernière lecture lancée est la seule qui écrit.
     * ⚠️ Déclaré AVANT `init`, qui lance la première lecture : plus bas, il
     * serait encore nul à ce moment-là.
     */
    private val lecture = Sequenceur()

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

    /** Résolu à l'écran, dans la langue du téléphone : voir `UiText`. */
    var error by mutableStateOf<UiText?>(null)
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
                error = uiText(R.string.agenda_error_meeting_not_created)
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
                error = uiText(R.string.agenda_error_flags_not_saved)
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
     *
     * Une lecture neuve annule celle en vol (Q-M6). Silencieuse, elle HÉRITE
     * du régime qu'elle remplace : un geste sur une fiche qui relit pendant un
     * chargement visible ne doit ni éteindre le témoin ni taire l'erreur que
     * la personne attend. Et seule la lecture courante touche aux témoins en
     * finissant — celle qu'on vient d'annuler les éteindrait sous la neuve.
     */
    private fun charger(regime: Regime) {
        val effectif = when {
            regime != Regime.SILENCIEUX -> regime
            refreshing -> Regime.TIRE
            loading -> Regime.VISIBLE
            else -> Regime.SILENCIEUX
        }
        when (effectif) {
            Regime.VISIBLE -> loading = true
            Regime.TIRE -> refreshing = true
            Regime.SILENCIEUX -> Unit
        }
        if (effectif != Regime.SILENCIEUX) error = null
        // La fenêtre et le fuseau sont pris au LANCEMENT : c'est à eux que la
        // réponse correspond, quoi que la grille soit devenue entre-temps.
        val visible = days
        val from = visible.first().minusDays(1).atStartOfDay(zone).toInstant()
        val to = visible.last().plusDays(2).atStartOfDay(zone).toInstant()
        val fuseau = zone
        lecture.lancer(viewModelScope) { n ->
            try {
                val lus = Graph.agenda.events(from, to).events
                val comptes = Graph.agenda.taskCounts(from, to, fuseau.id)
                if (!lecture.estCourante(n)) return@lancer
                events = lus
                taskCounts = compteursPourLaGrille(comptes, fuseau)
                lu = System.currentTimeMillis()
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Une relecture silencieuse qui échoue garde ce qui est affiché
                // plutôt que de vider l'écran sur une coupure de deux secondes.
                // Passé [PERIME_MS], l'en-tête le dit : voir `verifieA`.
                if (effectif != Regime.SILENCIEUX && lecture.estCourante(n)) {
                    // Le message du serveur passe tel quel ; seul le repli est à nous.
                    error = e.message?.let { UiText.Raw(it) }
                        ?: uiText(R.string.agenda_error_unavailable)
                }
            } finally {
                if (lecture.estCourante(n)) {
                    verifieA = System.currentTimeMillis()
                    loading = false
                    refreshing = false
                }
            }
        }
    }

    /** Les contacts trouvés pour le dialogue d'ajout ; vidés avec le terme. */
    var partenaires by mutableStateOf<List<AgendaPartner>>(emptyList())
        private set

    fun chercherPartenaires(terme: String) {
        if (terme.trim().length < 2) { partenaires = emptyList(); return }
        viewModelScope.launch {
            val trouves = Graph.agenda.partners(terme)
            partenaires = trouves
        }
    }

    /** [inviter] est la seule voie par laquelle un courriel part d'ici. */
    fun ajouterParticipant(event: AgendaEvent, partnerId: Int, inviter: Boolean) =
        actSurFiche {
            Graph.agenda.setAttendees(event.id, event.key, add = listOf(partnerId), notify = inviter)
        }

    fun retirerParticipant(event: AgendaEvent, partnerId: Int) = actSurFiche {
        Graph.agenda.setAttendees(event.id, event.key, remove = listOf(partnerId))
    }

    /**
     * Un geste qui REND la fiche : le serveur la renvoie complète, on la prend
     * telle quelle plutôt que de relire, puis on relit la grille pour le
     * compte de participants.
     */
    private fun actSurFiche(block: suspend () -> AgendaEvent?) {
        viewModelScope.launch {
            busy = true
            val fiche = runCatching { block() }.getOrNull()
            busy = false
            if (fiche == null) {
                error = uiText(R.string.agenda_error_action_not_saved)
                return@launch
            }
            if (selected?.key == fiche.key || selected?.id == fiche.id) selected = fiche
            charger(Regime.SILENCIEUX)
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
                error = uiText(R.string.agenda_error_action_not_saved)
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
