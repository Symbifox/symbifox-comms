package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.MailConfig
import com.bluefoxconsultant.sms.data.MailCounts
import com.bluefoxconsultant.sms.data.MailDraft
import com.bluefoxconsultant.sms.data.MailFilter
import com.bluefoxconsultant.sms.data.MailMessage
import com.bluefoxconsultant.sms.data.PendingAction
import com.bluefoxconsultant.sms.data.isOffline
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE = 25

/** Durée de vie d'une pierre tombale — le temps que le serveur rattrape. */
private const val TOMBSTONE_MS = 60_000L

class MailListViewModel : ViewModel() {

    var threads by mutableStateOf<List<MailMessage>>(emptyList())
        private set
    var config by mutableStateOf(MailConfig())
        private set
    var counts by mutableStateOf(MailCounts())
        private set
    var filter by mutableStateOf(MailFilter.INBOX)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var firstLoadDone by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    /**
     * The last reversible action, offered as "Annuler" for a few seconds.
     *
     * The action runs immediately and undo *reverses* it, rather than the
     * action being delayed until the undo window closes. Delaying reads
     * better — an undone archive would never touch IMAP — but the work would
     * be lost outright if the screen went away inside those seconds. Losing
     * an archive silently is worse than a second IMAP round-trip.
     */
    var undoable by mutableStateOf<(() -> Unit)?>(null)
        private set
    var undoLabel by mutableStateOf<String?>(null)
        private set

    /** Showing cached data because the server could not be reached. */
    var offline by mutableStateOf(false)
        private set
    /** Actions waiting to be replayed. */
    var queued by mutableStateOf(0)
        private set

    /**
     * Les brouillons de l'appareil, republiés à chaque écriture du magasin.
     *
     * ⚠️ Ils ne passent PAS par [threads] : ce ne sont pas des courriels du
     * serveur, ils n'ont ni identifiant Odoo ni fil, et les faire entrer dans
     * la même liste donnerait des gestes — archiver, reporter, router — qui
     * n'ont aucun sens sur un texte que personne n'a encore reçu.
     */
    val drafts: StateFlow<List<MailDraft>> = Graph.drafts.drafts

    var searchActive by mutableStateOf(false)
        private set
    var searchTerm by mutableStateOf("")
        private set

    private var searchJob: Job? = null

    /**
     * Ce que l'utilisateur vient de retirer de CETTE liste, et que le serveur
     * peut encore rendre pendant quelques instants.
     *
     * ⚠️ Retirer la ligne à l'écran ne suffit pas : le `/handle` répond bien,
     * mais le déplacement IMAP se fait après, et la moindre relecture entre les
     * deux — retour à l'app, tirer pour rafraîchir, notification — ramenait la
     * ligne, qui repartait ensuite toute seule. Une pierre tombale, à durée
     * limitée et en mémoire seulement, tient la promesse du geste sans jamais
     * masquer durablement un courriel que le serveur, lui, garde.
     *
     * Clé par FILTRE : archiver depuis la réception doit bel et bien faire
     * apparaître le courriel dans « Archivés ».
     */
    private val tombstones = mutableMapOf<String, Long>()

    init {
        queued = Graph.outbox.size
        loadConfig()
        refresh()
    }

    /** Masque la ligne dans le filtre courant, le temps que le serveur suive. */
    private fun hide(message: MailMessage) {
        tombstones["${filter.name}:${message.threadKey}"] =
            System.currentTimeMillis() + TOMBSTONE_MS
    }

    /**
     * Rend la ligne visible de nouveau, dans TOUS les filtres.
     *
     * Indispensable à « Annuler » : la restauration ne réinsère pas la ligne
     * elle-même, elle compte sur la relecture suivante. Une pierre tombale
     * oubliée rendrait donc l'annulation sans effet visible.
     */
    private fun unhide(message: MailMessage) {
        tombstones.keys.removeAll { it.endsWith(":${message.threadKey}") }
    }

    /** Ce que le serveur rend, moins ce que l'utilisateur a déjà retiré. */
    private fun visible(rows: List<MailMessage>): List<MailMessage> {
        val now = System.currentTimeMillis()
        tombstones.entries.removeAll { it.value <= now }
        // Même si la file a échoué, la liste ne doit pas contredire le geste :
        // tout ce qui reste en attente demeure caché.
        val queuedIds = Graph.outbox.peek().flatMap { it.emailIds }.toSet()
        val prefix = "${filter.name}:"
        return rows.filterNot {
            it.id in queuedIds || tombstones.containsKey(prefix + it.threadKey)
        }
    }

    /**
     * Le repli tel qu'il est affiché en ce moment.
     *
     * Il part avec CHAQUE lecture de totaux : le serveur compte des
     * conversations quand la liste les replie, des messages quand elle est à
     * plat. Envoyer le drapeau à `/threads` sans l'envoyer aux compteurs est
     * exactement ce qui affichait « Boîte de réception · 6 » au-dessus de cinq
     * lignes.
     */
    private val grouped: Boolean get() = Graph.uiPrefs.threadView

    private fun loadConfig() {
        // Paint from cache first so menus and badges exist before the network
        // answers — and still exist if it never does.
        Graph.mailCache.loadConfig()?.let {
            config = it
            counts = it.counts
        }
        viewModelScope.launch {
            try {
                config = Graph.mail.config()
                counts = config.counts
                Graph.mailCache.saveConfig(config)
                config.branding?.let {
                    Graph.brandStore.save(it.name, it.primary, it.dark)
                }
            } catch (e: Exception) {
                // Badges and action menus degrade; the list still works.
            }
        }
    }

    /**
     * Relit les pastilles, sans la page de courriels.
     *
     * ⚠️ C'est le correctif du napkin BF #25096. Les totaux ne descendaient
     * qu'à la construction de ce ViewModel et dans la réponse d'une mutation
     * faite DEPUIS l'app. Tout le reste — un courriel qui arrive, un ménage
     * fait au navigateur, et surtout ouvrir un fil, ce qui marque lu côté
     * serveur sans rien renvoyer — laissait la pastille figée, y compris après
     * un tirer-pour-rafraîchir. Une capture montrait « Non lus · 5 » au-dessus
     * d'une liste qui n'avait plus rien à lire.
     *
     * Silencieux à l'échec : une pastille qui date d'une minute est un moindre
     * mal, et la liste, elle, a son propre message d'erreur.
     */
    private fun loadCounts() {
        viewModelScope.launch {
            runCatching { Graph.mail.counts(grouped) }.onSuccess { counts = it }
        }
    }

    fun refresh() {
        // Les brouillons sont déjà là : rien à demander, et le demander ferait
        // répondre « Filtre inconnu » au serveur.
        if (filter == MailFilter.DRAFTS) {
            firstLoadDone = true
            refreshing = false
            hasMore = false
            error = null
            return
        }
        viewModelScope.launch {
            refreshing = true
            error = null
            // AWAITED, not fired alongside: launching the flush in its own
            // coroutine let the re-fetch overtake it, so the server answered
            // with the state from before the queued action and the row
            // reappeared — then vanished again once the flush landed.
            flushQueue()
            // Lancés ensemble : les totaux ne dépendent pas de la page, et
            // les enchaîner ajouterait un aller-retour au geste le plus
            // fréquent de l'écran.
            loadCounts()
            try {
                val resp = Graph.mail.threads(
                    filter = filter,
                    search = searchTerm,
                    offset = 0,
                    limit = PAGE,
                    grouped = grouped,
                )
                threads = visible(resp.threads)
                hasMore = resp.hasMore
                offline = false
                // Only the plain first page is worth caching; a search result
                // or a later page can't be reconstructed coherently offline.
                if (searchTerm.isBlank()) Graph.mailCache.saveThreads(filter, resp)
            } catch (e: Exception) {
                if (e.isOffline()) {
                    val cached = if (searchTerm.isBlank())
                        Graph.mailCache.loadThreads(filter) else null
                    if (cached != null) {
                        threads = visible(cached.threads)
                        // No paging offline: the next page isn't on this device.
                        hasMore = false
                        offline = true
                    } else {
                        error = "Hors ligne, et rien en cache pour « ${filter.label} »."
                    }
                } else {
                    error = "Impossible de charger les courriels."
                }
            } finally {
                refreshing = false
                firstLoadDone = true
            }
        }
    }

    /** Replay queued actions; surface anything the server refused outright. */
    private suspend fun flushQueue() {
        runCatching { Graph.outbox.flush { Graph.mail.replay(it) } }
        Graph.outbox.drainFailures().firstOrNull()?.let { error = it }
        queued = Graph.outbox.size
    }

    fun flushOutbox() {
        viewModelScope.launch { flushQueue() }
    }

    /** Dit ce que le composeur ne peut plus dire lui-même : il a déjà quitté. */
    fun announceDraftSaved() {
        notice = "Brouillon enregistré."
    }

    fun deleteDraft(draft: MailDraft) {
        Graph.drafts.delete(draft.id)
    }

    fun loadMore() {
        if (filter == MailFilter.DRAFTS) return
        if (loadingMore || !hasMore || refreshing) return
        viewModelScope.launch {
            loadingMore = true
            try {
                val resp = Graph.mail.threads(
                    filter = filter,
                    search = searchTerm,
                    offset = threads.size,
                    limit = PAGE,
                    grouped = grouped,
                )
                // Guard against a page that overlaps: a message arriving between
                // two requests shifts every later row down by one, which would
                // otherwise duplicate the boundary thread.
                val known = threads.mapTo(HashSet()) { it.threadKey }
                threads = threads + visible(resp.threads).filterNot { it.threadKey in known }
                hasMore = resp.hasMore
            } catch (e: Exception) {
                hasMore = false
            } finally {
                loadingMore = false
            }
        }
    }

    fun selectFilter(next: MailFilter) {
        if (filter == next) return
        filter = next
        threads = emptyList()
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

    /**
     * Archive optimistically — the row leaves the list at once and comes back
     * on failure. The server also moves the message on the IMAP side, so this
     * is the one action worth reporting when it fails.
     */
    fun clearUndo() {
        undoable = null
        undoLabel = null
    }

    private fun offerUndo(label: String, action: () -> Unit) {
        undoLabel = label
        undoable = action
    }

    fun archive(message: MailMessage) {
        hide(message)
        threads = threads.filterNot { it.threadKey == message.threadKey }
        offerUndo("Archivé") { restore(message) }
        viewModelScope.launch {
            try {
                counts = Graph.mail.setHandled(listOf(message.id), handled = true, grouped = grouped)
            } catch (e: Exception) {
                if (e.isOffline()) queueHandle(message, handled = true)
                else {
                    // Refusé : la ligne doit revenir, donc la pierre tombale part.
                    unhide(message)
                    error = "Archivage impossible."
                    refresh()
                }
            }
        }
    }

    /**
     * The row already left the list; queueing keeps that promise instead of
     * snapping it back and losing the gesture.
     */
    private fun queueHandle(message: MailMessage, handled: Boolean) {
        Graph.outbox.enqueue(
            PendingAction(
                token = PendingAction.newToken(),
                kind = PendingAction.KIND_HANDLE,
                createdMs = System.currentTimeMillis(),
                emailIds = listOf(message.id),
                handled = handled,
            ),
        )
        queued = Graph.outbox.size
        offline = true
        notice = if (handled) "Archivage enregistré, envoi au retour du réseau."
        else "Restauration enregistrée, envoi au retour du réseau."
    }

    fun snooze(message: MailMessage, untilMs: Long) {
        hide(message)
        threads = threads.filterNot { it.threadKey == message.threadKey }
        offerUndo("Reporté") { restore(message) }
        viewModelScope.launch {
            try {
                counts = Graph.mail.snooze(listOf(message.id), untilMs, grouped = grouped)
            } catch (e: Exception) {
                if (e.isOffline()) {
                    Graph.outbox.enqueue(
                        PendingAction(
                            token = PendingAction.newToken(),
                            kind = PendingAction.KIND_SNOOZE,
                            createdMs = System.currentTimeMillis(),
                            emailIds = listOf(message.id),
                            untilMs = untilMs,
                        ),
                    )
                    queued = Graph.outbox.size
                    offline = true
                    notice = "Report enregistré, envoi au retour du réseau."
                } else {
                    unhide(message)
                    error = "Report impossible."
                    refresh()
                }
            }
        }
    }

    fun restore(message: MailMessage) {
        clearUndo()
        // ⚠️ Ne masquer que si la ligne est ENCORE là. Restaurer depuis
        // « Traités » la retire de cette liste ; mais le même appel sert
        // d'annulation juste après un archivage, et la ligne a alors déjà
        // quitté la réception — la remasquer rendrait « Annuler » sans effet
        // visible pendant une minute.
        val present = threads.any { it.threadKey == message.threadKey }
        unhide(message)
        if (present) hide(message)
        threads = threads.filterNot { it.threadKey == message.threadKey }
        viewModelScope.launch {
            try {
                counts = Graph.mail.setHandled(listOf(message.id), handled = false, grouped = grouped)
                notice = "Remis en boîte de réception."
            } catch (e: Exception) {
                if (e.isOffline()) queueHandle(message, handled = false)
                else {
                    unhide(message)
                    error = "Action impossible."
                    refresh()
                }
            }
        }
    }

    fun markRead(message: MailMessage) {
        viewModelScope.launch {
            try {
                counts = Graph.mail.markRead(listOf(message.id), grouped = grouped)
                threads = threads.map {
                    if (it.threadKey == message.threadKey) it.copy(status = "read", unreadCount = 0)
                    else it
                }
            } catch (e: Exception) {
                if (e.isOffline()) {
                    Graph.outbox.enqueue(
                        PendingAction(
                            token = PendingAction.newToken(),
                            kind = PendingAction.KIND_MARK_READ,
                            createdMs = System.currentTimeMillis(),
                            emailIds = listOf(message.id),
                        ),
                    )
                    queued = Graph.outbox.size
                }
            }
        }
    }

    fun spawn(message: MailMessage, kind: String) {
        viewModelScope.launch {
            try {
                val resp = Graph.mail.spawn(message.id, kind)
                notice = resp.record?.let { "Créé : ${it.name}" } ?: "Créé."
                refresh()
            } catch (e: Exception) {
                error = e.message ?: "Création impossible."
            }
        }
    }

    fun route(message: MailMessage, model: String, recordId: Int) {
        viewModelScope.launch {
            try {
                val resp = Graph.mail.route(message.id, model, recordId)
                notice = resp.record?.let { "Importé dans ${it.name}" } ?: "Importé."
                refresh()
            } catch (e: Exception) {
                error = e.message ?: "Routage impossible."
            }
        }
    }

    fun dismissNotice() {
        notice = null
        error = null
    }

    // ── Sélection multiple ────────────────────────────────────────────
    /**
     * Les fils cochés, par clé de fil.
     *
     * La sélection EST le mode : un ensemble vide veut dire liste normale.
     * Un drapeau séparé finirait par mentir sur le compte le jour où une
     * ligne disparaît sous la sélection (archivage venu d'ailleurs, filtre
     * changé), alors que l'intersection avec la liste, elle, reste vraie.
     */
    var selection by mutableStateOf<Set<String>>(emptySet())
        private set

    val selectionMode: Boolean get() = selection.isNotEmpty()

    /** Les messages cochés ENCORE présents dans la liste, dans l'ordre affiché. */
    val selectedMessages: List<MailMessage>
        get() = threads.filter { it.threadKey in selection }

    fun toggleSelect(message: MailMessage) {
        selection = if (message.threadKey in selection) selection - message.threadKey
        else selection + message.threadKey
    }

    fun clearSelection() {
        selection = emptySet()
    }

    fun selectAll() {
        selection = threads.mapTo(LinkedHashSet()) { it.threadKey }
    }

    /**
     * Applique une action à toute la sélection en UN aller-retour : les points
     * d'entrée du serveur (`/handle`, `/snooze`, `/mark_read`) prennent déjà
     * une liste d'identifiants. Boucler côté téléphone multiplierait les
     * requêtes et laisserait la liste dans un état à moitié appliqué si l'une
     * d'elles échouait.
     */
    private fun applyToSelection(
        targets: List<MailMessage>,
        removeRows: Boolean,
        errorText: String,
        pending: PendingAction,
        call: suspend (List<Int>) -> MailCounts,
    ) {
        if (removeRows) {
            targets.forEach { hide(it) }
            val keys = targets.mapTo(HashSet()) { it.threadKey }
            threads = threads.filterNot { it.threadKey in keys }
        }
        viewModelScope.launch {
            try {
                counts = call(targets.map { it.id })
            } catch (e: Exception) {
                if (e.isOffline()) {
                    Graph.outbox.enqueue(pending)
                    queued = Graph.outbox.size
                    offline = true
                    notice = "Action enregistrée, envoi au retour du réseau."
                } else {
                    targets.forEach { unhide(it) }
                    error = errorText
                    refresh()
                }
            }
        }
    }

    private fun plural(n: Int, one: String, many: String) =
        if (n == 1) one else "$n $many"

    fun archiveSelected() {
        val targets = selectedMessages
        if (targets.isEmpty()) return
        clearSelection()
        offerUndo(plural(targets.size, "Archivé", "archivés")) { restoreMany(targets) }
        applyToSelection(
            targets = targets,
            removeRows = true,
            errorText = "Archivage impossible.",
            pending = PendingAction(
                token = PendingAction.newToken(),
                kind = PendingAction.KIND_HANDLE,
                createdMs = System.currentTimeMillis(),
                emailIds = targets.map { it.id },
                handled = true,
            ),
        ) { ids -> Graph.mail.setHandled(ids, handled = true, grouped = grouped) }
    }

    fun restoreSelected() {
        val targets = selectedMessages
        if (targets.isEmpty()) return
        clearSelection()
        restoreMany(targets)
    }

    private fun restoreMany(targets: List<MailMessage>) {
        clearUndo()
        // Même règle que [restore] : ne masquer que ce qui est encore affiché.
        val present = threads.mapTo(HashSet()) { it.threadKey }
        targets.forEach {
            unhide(it)
            if (it.threadKey in present) hide(it)
        }
        val keys = targets.mapTo(HashSet()) { it.threadKey }
        threads = threads.filterNot { it.threadKey in keys }
        viewModelScope.launch {
            try {
                counts = Graph.mail.setHandled(
                    targets.map { it.id }, handled = false, grouped = grouped)
                notice = plural(targets.size, "Remis en boîte de réception.",
                    "courriels remis en boîte de réception.")
            } catch (e: Exception) {
                if (e.isOffline()) {
                    Graph.outbox.enqueue(
                        PendingAction(
                            token = PendingAction.newToken(),
                            kind = PendingAction.KIND_HANDLE,
                            createdMs = System.currentTimeMillis(),
                            emailIds = targets.map { it.id },
                            handled = false,
                        ),
                    )
                    queued = Graph.outbox.size
                    offline = true
                    notice = "Restauration enregistrée, envoi au retour du réseau."
                } else {
                    targets.forEach { unhide(it) }
                    error = "Action impossible."
                    refresh()
                }
            }
        }
    }

    fun markReadSelected() {
        val targets = selectedMessages
        if (targets.isEmpty()) return
        clearSelection()
        // La ligne RESTE : marquer lu ne la sort d'aucune liste sauf « Non
        // lus », que le rafraîchissement suivant réglera de lui-même.
        val keys = targets.mapTo(HashSet()) { it.threadKey }
        threads = threads.map {
            if (it.threadKey in keys) it.copy(status = "read", unreadCount = 0) else it
        }
        applyToSelection(
            targets = targets,
            removeRows = false,
            errorText = "Action impossible.",
            pending = PendingAction(
                token = PendingAction.newToken(),
                kind = PendingAction.KIND_MARK_READ,
                createdMs = System.currentTimeMillis(),
                emailIds = targets.map { it.id },
            ),
        ) { ids -> Graph.mail.markRead(ids, grouped = grouped) }
    }

    fun snoozeSelected(untilMs: Long) {
        val targets = selectedMessages
        if (targets.isEmpty()) return
        clearSelection()
        offerUndo(plural(targets.size, "Reporté", "reportés")) { restoreMany(targets) }
        applyToSelection(
            targets = targets,
            removeRows = true,
            errorText = "Report impossible.",
            pending = PendingAction(
                token = PendingAction.newToken(),
                kind = PendingAction.KIND_SNOOZE,
                createdMs = System.currentTimeMillis(),
                emailIds = targets.map { it.id },
                untilMs = untilMs,
            ),
        ) { ids -> Graph.mail.snooze(ids, untilMs, grouped = grouped) }
    }
}
