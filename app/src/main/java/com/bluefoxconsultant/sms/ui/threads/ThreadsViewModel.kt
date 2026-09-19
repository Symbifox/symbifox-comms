package com.bluefoxconsultant.sms.ui.threads

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.Line
import com.bluefoxconsultant.sms.data.Thread
import com.bluefoxconsultant.sms.ui.Sequenceur
import com.bluefoxconsultant.sms.ui.relectureUtile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bluefoxconsultant.sms.data.nonLusDesFils
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiPlural
import com.bluefoxconsultant.sms.ui.uiText

class ThreadsViewModel : ViewModel() {

    /**
     * Voir `Sequenceur` : la dernière lecture lancée est la seule qui écrit.
     * ⚠️ Déclaré AVANT `init`, qui lance la première lecture.
     */
    private val lecture = Sequenceur()

    var threads by mutableStateOf<List<Thread>>(emptyList())
        private set
    var lines by mutableStateOf<List<Line>>(emptyList())
        private set
    var refreshing by mutableStateOf(false)
        private set
    var firstLoadDone by mutableStateOf(false)
        private set

    /** Dernière lecture RÉUSSIE. Voir `ui/Rafraichissement.kt`. */
    private var lu = 0L
    var error by mutableStateOf<UiText?>(null)
        private set

    var selectedLineId by mutableStateOf<Int?>(null)
        private set
    var searchActive by mutableStateOf(false)
        private set
    var searchTerm by mutableStateOf("")
        private set

    private var searchJob: Job? = null

    init {
        lines = Graph.tokenStore.lines
        refresh()
        if (lines.isEmpty()) refreshLines()
    }

    fun refresh() = charger(silencieux = false)

    /**
     * Le battement : au retour à l'écran, puis à la minute.
     *
     * La liste vivait de ses notifications poussées. Un message lu ailleurs,
     * un fil archivé depuis le bureau ou une poussée qui n'est jamais arrivée
     * laissaient l'écran dans son état d'il y a des heures, sans le dire.
     *
     * ⚠️ On ne relit pas pendant une recherche : remplacer les résultats sous
     * les doigts de quelqu'un qui tape est pire que de les laisser dater.
     */
    fun tick() {
        if (refreshing || searchActive) return
        if (!relectureUtile(System.currentTimeMillis(), lu)) return
        charger(silencieux = true)
    }

    /**
     * ⚠️ [refreshing] est posé HORS de la coroutine : posé dedans, il n'existe
     * qu'au prochain tour de la boucle d'événements, et le battement de la
     * minute se glisse entre les deux pour lancer une seconde lecture.
     *
     * Une lecture silencieuse ne touche ni l'indicateur du tirer ni la
     * bannière d'erreur : elle garde ce qui est affiché plutôt que de le
     * remplacer par « impossible de charger » sur une coupure de deux secondes.
     */
    private fun charger(silencieux: Boolean) {
        // Une lecture silencieuse qui remplace un tirer en vol en hérite :
        // l'indicateur reste, et l'erreur se dira. Voir `AgendaViewModel.charger`.
        val muet = silencieux && !refreshing
        if (!muet) {
            refreshing = true
            error = null
        }
        // La ligne et la recherche auxquelles la réponse correspondra, prises
        // au LANCEMENT (Q-M6) : une recherche tapée pendant la lecture ne doit
        // ni recevoir les fils de l'ancienne, ni faire tomber la pastille.
        val ligne = selectedLineId
        val terme = searchTerm
        lecture.lancer(viewModelScope) { n ->
            try {
                val lus = Graph.sms.threads(
                    archived = 0,
                    lineId = ligne,
                    search = terme,
                )
                if (!lecture.estCourante(n)) return@lancer
                threads = lus
                // La pastille de l'onglet suit ce que l'écran vient de lire —
                // mais pas une recherche ni une ligne filtrée, qui ne voient
                // qu'une partie des fils et feraient tomber le compte à tort.
                if (terme.isBlank() && ligne == null) {
                    Graph.badges.poserSms(nonLusDesFils(lus))
                }
                lu = System.currentTimeMillis()
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!muet && lecture.estCourante(n)) error = uiText(R.string.sms_threads_load_failed)
            } finally {
                if (lecture.estCourante(n)) {
                    refreshing = false
                    firstLoadDone = true
                }
            }
        }
    }

    private fun refreshLines() {
        viewModelScope.launch {
            try {
                val fresh = Graph.sms.config().lines
                if (fresh.isNotEmpty()) {
                    lines = fresh
                    Graph.tokenStore.saveLines(fresh)
                }
            } catch (e: Exception) {
                // keep whatever we have
            }
        }
    }

    fun selectLine(lineId: Int?) {
        if (selectedLineId == lineId) return
        selectedLineId = lineId
        refresh()
    }

    fun openSearch() {
        searchActive = true
    }

    fun closeSearch() {
        searchActive = false
        if (searchTerm.isNotEmpty()) {
            searchTerm = ""
            refresh()
        }
    }

    fun onSearchChange(term: String) {
        searchTerm = term
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            refresh()
        }
    }

    /** Last archive, offered as "Annuler" for a few seconds. */
    var undoable by mutableStateOf<(() -> Unit)?>(null)
        private set
    /** Ce que l'annulation défera — « Archivé » ou « 5 archivées ». */
    var undoLabel by mutableStateOf(uiText(R.string.sms_threads_archived_one))
        private set

    fun clearUndo() {
        undoable = null
    }

    fun archive(threadId: Int) {
        threads = threads.filterNot { it.id == threadId }
        undoLabel = uiText(R.string.sms_threads_archived_one)
        undoable = { unarchive(threadId) }
        viewModelScope.launch {
            try {
                Graph.sms.archive(threadId, archived = true)
            } catch (e: Exception) {
                refresh()
            }
        }
    }

    private fun unarchive(threadId: Int) {
        undoable = null
        viewModelScope.launch {
            try {
                Graph.sms.archive(threadId, archived = false)
                refresh()
            } catch (e: Exception) {
                error = uiText(R.string.sms_threads_undo_failed)
            }
        }
    }

    fun togglePin(threadId: Int) {
        viewModelScope.launch {
            try {
                Graph.sms.pin(threadId)
                refresh() // server re-sorts pinned first
            } catch (e: Exception) {
                error = uiText(R.string.sms_threads_action_failed)
            }
        }
    }

    fun dismissError() {
        error = null
    }

    // ── Sélection multiple ────────────────────────────────────────────
    /** Les fils cochés. Vide = liste normale ; c'est la sélection qui EST le mode. */
    var selection by mutableStateOf<Set<Int>>(emptySet())
        private set

    val selectionMode: Boolean get() = selection.isNotEmpty()

    private val selectedThreads: List<Thread>
        get() = threads.filter { it.id in selection }

    /** Tout coché est déjà épinglé : le bouton propose alors l'inverse. */
    val allSelectedPinned: Boolean
        get() = selectedThreads.isNotEmpty() && selectedThreads.all { it.isPinned }

    fun toggleSelect(threadId: Int) {
        selection = if (threadId in selection) selection - threadId else selection + threadId
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun selectAll() {
        selection = threads.mapTo(LinkedHashSet()) { it.id }
    }

    /**
     * Archive toute la sélection.
     *
     * ⚠️ Une requête PAR fil : `/thread/archive` ne prend qu'un identifiant,
     * contrairement au côté courriel. Elles partent en séquence plutôt qu'en
     * parallèle — vingt écritures simultanées sur la même base valent une
     * seconde d'attente de plus.
     */
    fun archiveSelected() {
        val targets = selection.toList()
        if (targets.isEmpty()) return
        clearSelection()
        threads = threads.filterNot { it.id in targets }
        undoLabel = if (targets.size == 1) uiText(R.string.sms_threads_archived_one)
        else uiPlural(R.plurals.sms_threads_archived_count, targets.size)
        undoable = { unarchiveMany(targets) }
        viewModelScope.launch {
            var failed = 0
            targets.forEach { id ->
                try {
                    Graph.sms.archive(id, archived = true)
                } catch (e: Exception) {
                    failed++
                }
            }
            if (failed > 0) {
                error = uiPlural(R.plurals.sms_threads_archive_failed_count, failed)
                refresh()
            }
        }
    }

    private fun unarchiveMany(threadIds: List<Int>) {
        undoable = null
        viewModelScope.launch {
            var failed = 0
            threadIds.forEach { id ->
                try {
                    Graph.sms.archive(id, archived = false)
                } catch (e: Exception) {
                    failed++
                }
            }
            if (failed > 0) error = uiText(R.string.sms_threads_undo_incomplete)
            refresh()
        }
    }

    /**
     * Épingle ou désépingle toute la sélection.
     *
     * Le point d'entrée du serveur BASCULE l'état ; appelé sur une sélection
     * mêlant épinglés et non épinglés, il les inverserait tous les deux et ne
     * réglerait rien. On n'appelle donc que les fils qui ne sont pas déjà dans
     * l'état voulu.
     */
    fun pinSelected(pin: Boolean) {
        val targets = selectedThreads.filter { it.isPinned != pin }.map { it.id }
        clearSelection()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            var failed = 0
            targets.forEach { id ->
                try {
                    Graph.sms.pin(id)
                } catch (e: Exception) {
                    failed++
                }
            }
            if (failed > 0) error = uiPlural(R.plurals.sms_threads_action_failed_count, failed)
            refresh() // le serveur retrie, épinglés d'abord
        }
    }
}
