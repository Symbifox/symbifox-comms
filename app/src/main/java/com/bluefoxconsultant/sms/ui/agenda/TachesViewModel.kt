package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.AgendaTask
import com.bluefoxconsultant.sms.data.AgendaTaskOptions
import com.bluefoxconsultant.sms.data.Graph
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Les échéances, en trois seaux.
 *
 * Le retard est rendu quelle que soit la fenêtre : une échéance dépassée ne
 * disparaît pas parce qu'on regarde la semaine prochaine. Les tâches sans
 * échéance ne sont chargées que sur demande — il y en a 633, et les servir
 * d'office ferait payer à chaque ouverture une liste que personne ne lit.
 */
class TachesViewModel : ViewModel() {

    val zone: ZoneId = ZoneId.systemDefault()

    var overdue by mutableStateOf<List<AgendaTask>>(emptyList())
        private set
    var window by mutableStateOf<List<AgendaTask>>(emptyList())
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

    var error by mutableStateOf<String?>(null)
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

    init {
        load()
        viewModelScope.launch {
            options = runCatching { Graph.agenda.taskOptions() }
                .getOrDefault(AgendaTaskOptions())
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
        Graph.agenda.writeTask(task.id, valeursJson)
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
                error = "La tâche n'a pas été créée."
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
                error = "Le changement n'a pas été enregistré."
                return@launch
            }
            if (selected?.id == maj.id) selected = maj
            load()
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

    /** ⚠️ Témoins posés hors de la coroutine : voir `AgendaViewModel.charger`. */
    private fun charger(regime: Regime) {
        when (regime) {
            Regime.VISIBLE -> loading = true
            Regime.TIRE -> refreshing = true
            Regime.SILENCIEUX -> Unit
        }
        if (regime != Regime.SILENCIEUX) error = null
        viewModelScope.launch {
            try {
                val today = LocalDate.now(zone)
                val from = today.minusDays(1).atStartOfDay(zone).toInstant()
                val to = today.plusDays(horizonDays).atStartOfDay(zone).toInstant()
                val res = Graph.agenda.tasks(from, to, undated = showUndated)
                overdue = res.overdue
                window = res.window
                undated = res.undated
                undatedCount = res.undatedCount
                lu = System.currentTimeMillis()
                error = null
            } catch (e: Exception) {
                if (regime != Regime.SILENCIEUX) {
                    error = e.message ?: "Échéances indisponibles."
                }
            } finally {
                verifieA = System.currentTimeMillis()
                loading = false
                refreshing = false
            }
        }
    }
}
