package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.AgendaTask
import com.bluefoxconsultant.sms.data.Graph
import kotlinx.coroutines.launch
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
    var error by mutableStateOf<String?>(null)
        private set

    init { load() }

    fun clearError() { error = null }

    fun setHorizon(days: Long) {
        if (days == horizonDays) return
        horizonDays = days
        load()
    }

    fun toggleUndated() {
        showUndated = !showUndated
        if (showUndated && undated.isEmpty()) load()
    }

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                val today = LocalDate.now(zone)
                val from = today.minusDays(1).atStartOfDay(zone).toInstant()
                val to = today.plusDays(horizonDays).atStartOfDay(zone).toInstant()
                val res = Graph.agenda.tasks(from, to, undated = showUndated)
                overdue = res.overdue
                window = res.window
                undated = res.undated
                undatedCount = res.undatedCount
            } catch (e: Exception) {
                error = e.message ?: "Échéances indisponibles."
            } finally {
                loading = false
            }
        }
    }
}
