package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.ui.Sequenceur
import com.bluefoxconsultant.sms.ui.relectureUtile
import com.bluefoxconsultant.sms.data.AgendaTask
import com.bluefoxconsultant.sms.data.AgendaTaskOptions
import com.bluefoxconsultant.sms.data.AgendaTaskSearchResponse
import com.bluefoxconsultant.sms.data.AgendaTasksResponse
import com.bluefoxconsultant.sms.data.Graph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiText

/**
 * Les échéances : sans échéance, en retard, aujourd'hui, jour par jour, plus tard.
 *
 * Le retard est rendu quelle que soit la fenêtre : une échéance dépassée ne
 * disparaît pas parce qu'on regarde la semaine prochaine. Les tâches sans
 * échéance ne sont chargées que sur demande — il y en a 633, et les servir
 * d'office ferait payer à chaque ouverture une liste que personne ne lit.
 *
 * [lireTaches], [poserPastille], [chercherTaches], [ecrireTache] et
 * [niveauApi] sont des paramètres pour le banc, qui y met un dépôt répondant
 * dans le désordre ; l'app prend les défauts.
 */
class TachesViewModel(
    private val lireTaches: suspend (from: Instant, to: Instant, undated: Boolean) -> AgendaTasksResponse =
        { from, to, undated -> Graph.agenda.tasks(from, to, undated) },
    private val poserPastille: (Int) -> Unit = { Graph.badges.poserTaches(it) },
    chargerOptions: Boolean = true,
    private val chercherTaches: suspend (String) -> AgendaTaskSearchResponse =
        { Graph.agenda.searchTasks(it) },
    private val ecrireTache: suspend (Int, String) -> AgendaTask? =
        { id, valeurs -> Graph.agenda.writeTask(id, valeurs) },
    private val niveauApi: () -> Int = { Graph.agendaStore.ping.value.api },
) : ViewModel() {

    /**
     * Voir `Sequenceur` : la dernière lecture lancée est la seule qui écrit.
     * ⚠️ Déclaré AVANT `init`, qui lance la première lecture : plus bas, il
     * serait encore nul à ce moment-là.
     */
    private val lecture = Sequenceur()

    /** La recherche a son propre séquenceur : taper ne doit pas annuler la liste. */
    private val quete = Sequenceur()

    val zone: ZoneId = ZoneId.systemDefault()

    var overdue by mutableStateOf<List<AgendaTask>>(emptyList())
        private set
    var window by mutableStateOf<List<AgendaTask>>(emptyList())
        private set

    /** [overdue] et [window] redécoupés par jour local. Voir `decouperTaches`. */
    var sections by mutableStateOf<List<SectionTaches>>(emptyList())
        private set
    var undated by mutableStateOf<List<AgendaTask>>(emptyList())
        private set
    var undatedCount by mutableStateOf(0)
        private set
    var showUndated by mutableStateOf(false)
        private set
    var horizonDays by mutableStateOf(14L)
        private set
    var loading by mutableStateOf(false)
        private set

    /** Le geste de tirer, distinct de [loading] : il a son propre indicateur. */
    var refreshing by mutableStateOf(false)
        private set

    /** Dernière lecture RÉUSSIE, et dernière tentative. Voir `Rafraichissement`. */
    var lu by mutableStateOf(0L)
        private set
    var verifieA by mutableStateOf(0L)
        private set

    /** Résolu à l'écran, dans la langue du téléphone : voir `UiText`. */
    var error by mutableStateOf<UiText?>(null)
        private set

    /** La tâche ouverte pour modification, et de quoi remplir ses sélecteurs. */
    var selected by mutableStateOf<AgendaTask?>(null)
        private set
    var options by mutableStateOf(AgendaTaskOptions())
        private set
    var composing by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set

    // ── Recherche (#25734) ──────────────────────────────────────────────

    /** Le champ de recherche est ouvert. */
    var rechercheOuverte by mutableStateOf(false)
        private set
    var recherche by mutableStateOf("")
        private set

    /** Vrai dès que le terme vaut une recherche : l'écran montre les résultats. */
    val enRecherche: Boolean get() = rechercheOuverte && termeCherchable(recherche)
    var resultats by mutableStateOf<List<AgendaTask>>(emptyList())
        private set

    /** Le serveur en a trouvé plus qu'il n'en rend. */
    var resultatsTronques by mutableStateOf(false)
        private set
    var cherche by mutableStateOf(false)
        private set

    /**
     * La recherche n'a porté que sur les tâches déjà lues : serveur trop ancien
     * pour `/tasks/search`, ou injoignable. L'écran le dit, parce que « aucun
     * résultat » n'y veut pas dire « aucune tâche ».
     */
    var rechercheLocale by mutableStateOf(false)
        private set

    // ── Annulation (#25734) ─────────────────────────────────────────────

    /** Une tâche qu'on vient d'annuler, et l'état où la remettre. */
    data class Annulation(val tache: AgendaTask, val etatAvant: String)

    var annulation by mutableStateOf<Annulation?>(null)
        private set

    init {
        load()
        if (chargerOptions) {
            viewModelScope.launch {
                options = runCatching { Graph.agenda.taskOptions() }
                    .getOrDefault(AgendaTaskOptions())
            }
        }
    }

    fun open(task: AgendaTask) {
        selected = task
        // Les étapes dépendent du projet : les recharger à l'ouverture évite
        // de proposer une étape qui n'existe pas là où la tâche vit.
        viewModelScope.launch {
            val majs = runCatching { Graph.agenda.taskOptions(task.projectId) }
                .getOrNull() ?: return@launch
            if (selected?.id == task.id) options = majs
        }
    }

    fun close() { selected = null }

    /**
     * Ouvrir une tâche qu'on ne tient pas dans ses seaux — celle d'un courriel
     * classé dessus. Lue par identifiant, même hors de « mes tâches » ; si le
     * serveur la refuse, l'erreur le dit plutôt que d'ouvrir une fiche vide.
     */
    fun ouvrirParId(id: Int) {
        viewModelScope.launch {
            val tache = runCatching { Graph.agenda.task(id) }.getOrNull()
            if (tache == null) {
                error = uiText(R.string.tasks_error_cannot_open)
                return@launch
            }
            open(tache)
        }
    }

    fun openComposer() { composing = true }

    fun closeComposer() { composing = false }

    /**
     * Le geste le plus fréquent. On rejoue la liste après coup plutôt que de
     * retirer la ligne à la main : elle change de seau, elle ne disparaît pas.
     */
    fun complete(task: AgendaTask, done: Boolean) = agir {
        Graph.agenda.completeTask(task.id, done)
    }

    fun write(task: AgendaTask, valeursJson: String) = agir {
        ecrireTache(task.id, valeursJson)
    }

    /**
     * Annuler, pas seulement marquer faite (#25734). La tâche quitte la liste
     * comme une tâche faite ; la fiche se ferme, et l'écran offre de la rétablir
     * dans l'état qu'elle avait.
     */
    fun cancel(task: AgendaTask) {
        val avant = task.state.takeIf { it.isNotBlank() && it !in ETATS_CLOS } ?: ETAT_EN_COURS
        viewModelScope.launch {
            busy = true
            val maj = runCatching { ecrireTache(task.id, """{"state":"$ETAT_ANNULE"}""") }.getOrNull()
            busy = false
            if (maj == null) {
                error = uiText(R.string.tasks_error_change_not_saved)
                return@launch
            }
            if (selected?.id == task.id) selected = null
            annulation = Annulation(maj, avant)
            load()
            relancerRecherche()
        }
    }

    /** « Rétablir » : la tâche revient dans l'état qu'elle avait avant l'annulation. */
    fun restoreCancelled() {
        val a = annulation ?: return
        annulation = null
        agir { ecrireTache(a.tache.id, """{"state":${jsonTexte(a.etatAvant)}}""") }
    }

    fun forgetCancelled() { annulation = null }

    fun openSearch() { rechercheOuverte = true }

    fun closeSearch() {
        rechercheOuverte = false
        recherche = ""
        quete.annuler()
        resultats = emptyList()
        resultatsTronques = false
        rechercheLocale = false
        cherche = false
    }

    /**
     * Chaque frappe relance la recherche, après une courte pause : taper « facture »
     * ne doit pas partir sept fois au serveur. La dernière LANCÉE est la seule qui
     * écrit (voir `Sequenceur`).
     */
    fun onSearchChange(terme: String) {
        recherche = terme
        lancerRecherche(pause = true)
    }

    private fun relancerRecherche() {
        if (enRecherche) lancerRecherche(pause = false)
    }

    private fun lancerRecherche(pause: Boolean) {
        val terme = recherche
        if (!termeCherchable(terme)) {
            quete.annuler()
            resultats = emptyList()
            resultatsTronques = false
            rechercheLocale = false
            cherche = false
            return
        }
        cherche = true
        quete.lancer(viewModelScope) { n ->
            if (pause) delay(PAUSE_FRAPPE_MS)
            val serveur = if (niveauApi() >= 4) {
                try {
                    chercherTaches(terme)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            if (!quete.estCourante(n)) return@lancer
            if (serveur != null) {
                resultats = serveur.tasks
                resultatsTronques = serveur.more
                rechercheLocale = false
            } else {
                resultats = filtrerTaches(overdue + window + undated, terme)
                resultatsTronques = false
                rechercheLocale = true
            }
            cherche = false
        }
    }

    fun create(name: String, projectId: Int, deadline: Instant?,
               priority: String, tagIds: List<Int>) {
        viewModelScope.launch {
            busy = true
            val cree = runCatching {
                Graph.agenda.createTask(name, projectId, deadline, priority, tagIds)
            }.getOrNull()
            busy = false
            if (cree == null) {
                error = uiText(R.string.tasks_error_not_created)
                return@launch
            }
            composing = false
            // Sans échéance elle n'entre dans aucun des deux seaux datés :
            // ouvrir le troisième évite de la croire perdue.
            if (cree.deadline == null) showUndated = true
            load()
        }
    }

    private fun agir(bloc: suspend () -> AgendaTask?) {
        viewModelScope.launch {
            busy = true
            val maj = runCatching { bloc() }.getOrNull()
            busy = false
            if (maj == null) {
                error = uiText(R.string.tasks_error_change_not_saved)
                return@launch
            }
            if (selected?.id == maj.id) selected = maj
            load()
            // Une tâche faite, rouverte ou renommée change de résultats.
            relancerRecherche()
        }
    }

    fun clearError() { error = null }

    fun setHorizon(days: Long) {
        // Retoucher l'horizon déjà choisi relit, comme les modes de l'agenda.
        if (days == horizonDays) {
            refresh()
            return
        }
        horizonDays = days
        load()
    }

    fun toggleUndated() {
        showUndated = !showUndated
        if (showUndated && undated.isEmpty()) load()
    }

    fun load() = charger(Regime.VISIBLE)

    /** Tirer pour relire. */
    fun refresh() = charger(Regime.TIRE)

    /**
     * Le battement : au retour à l'écran, puis à la minute.
     *
     * La fenêtre est calculée à partir d'aujourd'hui à CHAQUE lecture, donc
     * relire suffit à faire passer minuit ; il n'y a pas d'ancre à rattraper
     * comme dans l'agenda. Une échéance qui vient de tomber en retard change
     * alors de seau toute seule.
     */
    fun tick() {
        if (loading || refreshing) return
        if (!relectureUtile(System.currentTimeMillis(), lu)) return
        charger(Regime.SILENCIEUX)
    }

    private enum class Regime { VISIBLE, TIRE, SILENCIEUX }

    private companion object {
        const val PAUSE_FRAPPE_MS = 300L
        const val ETAT_ANNULE = "1_canceled"
        const val ETAT_EN_COURS = "01_in_progress"
        val ETATS_CLOS = setOf("1_done", "1_canceled")
    }

    /**
     * ⚠️ Témoins posés hors de la coroutine, héritage du régime remplacé, et
     * témoins éteints par la seule lecture courante : voir
     * `AgendaViewModel.charger`.
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
        // Pris au LANCEMENT : le jour, la fenêtre et le seau des sans-échéance
        // auxquels la réponse correspond.
        val aujourdhui = LocalDate.now(zone)
        val from = aujourdhui.minusDays(1).atStartOfDay(zone).toInstant()
        // L'horizon compte les jours QUI SUIVENT aujourd'hui, pris en entier :
        // « 7 j » montre les sept intertitres de la semaine qui vient, pas six
        // et demi.
        val to = aujourdhui.plusDays(horizonDays + 1).atStartOfDay(zone).toInstant()
        val avecSansEcheance = showUndated
        lecture.lancer(viewModelScope) { n ->
            try {
                val res = lireTaches(from, to, avecSansEcheance)
                if (!lecture.estCourante(n)) return@lancer
                overdue = res.overdue
                window = res.window
                sections = decouperTaches(res.overdue, res.window, zone, aujourdhui)
                poserPastille(pastilleTaches(res.overdue, res.window, zone, aujourdhui))
                undated = res.undated
                undatedCount = res.undatedCount
                lu = System.currentTimeMillis()
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (effectif != Regime.SILENCIEUX && lecture.estCourante(n)) {
                    // Le message du serveur passe tel quel ; seul le repli est à nous.
                    error = e.message?.let { UiText.Raw(it) }
                        ?: uiText(R.string.tasks_error_unavailable)
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
}
