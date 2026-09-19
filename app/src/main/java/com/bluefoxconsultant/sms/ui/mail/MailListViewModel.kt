package com.bluefoxconsultant.sms.ui.mail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.MailConfig
import com.bluefoxconsultant.sms.data.MailCounts
import com.bluefoxconsultant.sms.data.MailDraft
import com.bluefoxconsultant.sms.data.MailFilter
import com.bluefoxconsultant.sms.data.MailMessage
import com.bluefoxconsultant.sms.data.PendingAction
import com.bluefoxconsultant.sms.data.ScheduledMail
import com.bluefoxconsultant.sms.data.ServerDraft
import com.bluefoxconsultant.sms.data.Service
import com.bluefoxconsultant.sms.data.isOffline
import com.bluefoxconsultant.sms.data.pastilleCourriel
import com.bluefoxconsultant.sms.ui.Sequenceur
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiPlural
import com.bluefoxconsultant.sms.ui.uiText

private const val PAGE = 25

/** Durée de vie d'une pierre tombale — le temps que le serveur rattrape. */
private const val TOMBSTONE_MS = 60_000L

/**
 * Ce à quoi une lecture de la liste correspond, pris au LANCEMENT.
 *
 * 🔴 Q-M6 (audit du 2026-09-08) : la page lue s'enregistrait en cache sous le
 * filtre et la recherche COURANTS à l'arrivée de la réponse, pas sous ceux de
 * la requête. Changer de boîte pendant la lecture rangeait « Traités » dans le
 * fichier de la réception, et c'est ce que l'app rouvrait hors ligne.
 *
 * [compte] est le jeton du courriel au lancement : une page lue pour une
 * session ne doit pas entrer dans le cache de la suivante (le cache est vidé à
 * la déconnexion, une réponse en retard le remplirait de nouveau).
 *
 * [boite] est le compte courriel filtré (#25734), `null` pour toutes.
 */
internal data class CleListe(
    val filtre: MailFilter,
    val recherche: String,
    val groupe: Boolean,
    val compte: String?,
    val boite: Int? = null,
) {
    /**
     * Seule la première page d'une section, sans recherche ni filtre de boîte,
     * se reconstruit hors ligne : le cache est rangé par section, et une page
     * filtrée y prendrait la place de la liste complète.
     */
    val enCache: Boolean get() = recherche.isBlank() && boite == null && compte != null
}

class MailListViewModel : ViewModel() {

    /**
     * Voir `Sequenceur` : la dernière lecture de la liste lancée est la seule
     * qui écrit, et [lectureCompteurs] fait de même pour les pastilles.
     * ⚠️ Déclarés AVANT `init`, qui lance la première lecture.
     */
    private val lecture = Sequenceur()
    private val lectureCompteurs = Sequenceur()

    var threads by mutableStateOf<List<MailMessage>>(emptyList())
        private set
    var config by mutableStateOf(MailConfig())
        private set
    var counts by mutableStateOf(MailCounts())
        private set
    var filter by mutableStateOf(MailFilter.INBOX)
        private set

    /** La boîte filtrée (#25734), `null` pour toutes. */
    var accountId by mutableStateOf<Int?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var firstLoadDone by mutableStateOf(false)
        private set
    // Des [UiText] et non des phrases : l'écran les rédige dans la langue du
    // téléphone au moment de les montrer.
    var error by mutableStateOf<UiText?>(null)
        private set
    var notice by mutableStateOf<UiText?>(null)
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
    var undoLabel by mutableStateOf<UiText?>(null)
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

    /**
     * Les brouillons écrits AU POSTE, dans bf_email (#25579).
     *
     * Deuxième pile, dans la même section : celle-ci vient du serveur, elle
     * n'est donc pas un `StateFlow` du magasin local mais un état relu à
     * chaque ouverture de la section. Rien ne fusionne les deux listes — un
     * brouillon appartient au bord où il a été écrit, et les mêler
     * demanderait de trancher des conflits que personne n'a demandés.
     */
    var serverDrafts by mutableStateOf<List<ServerDraft>>(emptyList())
        private set
    var serverDraftsLoading by mutableStateOf(false)
        private set
    /** Renseigné quand le serveur n'a pas répondu : la section le dit. */
    var serverDraftsError by mutableStateOf<UiText?>(null)
        private set

    /**
     * Les envois programmés qui ne sont pas encore partis (#25764). Relus avec
     * les brouillons du poste, dans la même section : c'est là qu'on cherche un
     * message qu'on a écrit et qui n'est pas encore chez son destinataire.
     */
    var scheduled by mutableStateOf<List<ScheduledMail>>(emptyList())
        private set

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
        // Chaque geste qui rend des compteurs (lire, traiter, reporter) met la
        // pastille de l'onglet à jour du même coup : on observe la valeur
        // plutôt que d'instrumenter ses huit points d'écriture.
        viewModelScope.launch {
            snapshotFlow { counts }.collect { Graph.badges.poserCourriel(pastilleCourriel(it)) }
        }
        queued = Graph.outbox.enAttenteVisible()
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
    private fun visible(rows: List<MailMessage>, filtre: MailFilter = filter): List<MailMessage> {
        val now = System.currentTimeMillis()
        tombstones.entries.removeAll { it.value <= now }
        // Même si la file a échoué, la liste ne doit pas contredire le geste :
        // tout ce qui reste en attente demeure caché.
        val queuedIds = Graph.outbox.peek().flatMap { it.emailIds }.toSet()
        val prefix = "${filtre.name}:"
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
        val groupe = grouped
        lectureCompteurs.lancer(viewModelScope) { n ->
            val lus = try {
                Graph.mail.counts(groupe)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@lancer
            }
            if (lectureCompteurs.estCourante(n)) counts = lus
        }
    }

    /**
     * Les totaux qu'une mutation vient de rendre.
     *
     * Plus frais que toute lecture partie avant le geste : celle-ci est donc
     * annulée, sinon elle reviendrait écraser le compte d'après avec celui
     * d'avant.
     */
    private fun poserCompteurs(frais: MailCounts) {
        lectureCompteurs.annuler()
        counts = frais
    }

    fun refresh() {
        // Les brouillons sont déjà là : rien à demander, et le demander ferait
        // répondre « Filtre inconnu » au serveur.
        if (filter == MailFilter.DRAFTS) {
            // Ceux de l'appareil sont déjà là ; ceux du poste se demandent.
            // Le filtre lui-même ne part toujours PAS au serveur : /threads
            // n'en connaît pas la clé et répondrait « Filtre inconnu ».
            // Une lecture d'une autre boîte encore en vol n'a plus rien à
            // écrire ici.
            lecture.annuler()
            firstLoadDone = true
            refreshing = false
            hasMore = false
            error = null
            refreshServerDrafts()
            return
        }
        // Posés HORS de la coroutine, comme ailleurs : voir `AgendaViewModel.charger`.
        refreshing = true
        error = null
        val cle = CleListe(filter, searchTerm, grouped, Graph.tokenStore.tokenFor(Service.MAIL), accountId)
        lecture.lancer(viewModelScope) { n ->
            try {
                // AWAITED, not fired alongside: launching the flush in its own
                // coroutine let the re-fetch overtake it, so the server answered
                // with the state from before the queued action and the row
                // reappeared — then vanished again once the flush landed.
                flushQueue()
                // Lancés ensemble : les totaux ne dépendent pas de la page, et
                // les enchaîner ajouterait un aller-retour au geste le plus
                // fréquent de l'écran.
                loadCounts()
                val resp = Graph.mail.threads(
                    filter = cle.filtre,
                    search = cle.recherche,
                    accountId = cle.boite,
                    offset = 0,
                    limit = PAGE,
                    grouped = cle.groupe,
                )
                // Une réponse dépassée n'écrit RIEN, cache compris : une
                // relecture plus récente de la même boîte a pu arriver avant
                // elle, et la remplacerait par plus vieux qu'elle.
                if (!lecture.estCourante(n)) return@lancer
                threads = visible(resp.threads, cle.filtre)
                hasMore = resp.hasMore
                offline = false
                // Only the plain first page is worth caching; a search result
                // or a later page can't be reconstructed coherently offline.
                // Sous la clé du LANCEMENT, pas sous l'état courant, et pas si
                // la session a changé pendant la lecture.
                if (cle.enCache && Graph.tokenStore.tokenFor(Service.MAIL) == cle.compte) {
                    Graph.mailCache.saveThreads(cle.filtre, resp)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!lecture.estCourante(n)) return@lancer
                if (e.isOffline()) {
                    val cached = if (cle.enCache) Graph.mailCache.loadThreads(cle.filtre) else null
                    if (cached != null) {
                        threads = visible(cached.threads, cle.filtre)
                        // No paging offline: the next page isn't on this device.
                        hasMore = false
                        offline = true
                    } else {
                        error = uiText(R.string.mail_list_offline_no_cache, uiText(cle.filtre.labelRes))
                    }
                } else {
                    error = uiText(R.string.mail_list_load_failed)
                }
            } finally {
                if (lecture.estCourante(n)) {
                    refreshing = false
                    firstLoadDone = true
                }
            }
        }
    }

    /** Replay queued actions; surface anything the server refused outright. */
    private suspend fun flushQueue() {
        // Par les envois différés et non par la file directement : un envoi
        // refusé doit redevenir un brouillon, quel que soit le chemin qui l'a
        // fait partir (#25764).
        runCatching { Graph.envois.vider() }
        Graph.outbox.drainFailures().firstOrNull()?.let { error = it }
        queued = Graph.outbox.enAttenteVisible()
    }

    fun flushOutbox() {
        viewModelScope.launch { flushQueue() }
    }

    /** Dit ce que le composeur ne peut plus dire lui-même : il a déjà quitté. */
    fun announceDraftSaved() {
        notice = uiText(R.string.mail_draft_saved)
    }

    fun announce(message: UiText) {
        notice = message
    }

    fun deleteDraft(draft: MailDraft) {
        Graph.drafts.delete(draft.id)
    }

    /**
     * Relit les brouillons du poste.
     *
     * Silencieux quand l'instance ne sait pas les servir : `server_drafts`
     * absent de `/config` veut dire « trop vieille », et la section doit
     * alors se contenter des brouillons de l'appareil plutôt que d'afficher
     * une erreur pour une fonction que personne n'attend là-bas.
     */
    fun refreshServerDrafts() {
        if (!config.serverDrafts) {
            serverDrafts = emptyList()
            serverDraftsError = null
            return
        }
        viewModelScope.launch {
            serverDraftsLoading = true
            // AVANT la relecture, comme le vidage de la file avant /threads :
            // relire d'abord ferait afficher la version du poste juste avant
            // que la remontée ne la remplace, et la ligne changerait sous les
            // yeux sans que personne ait rien fait.
            pushPendingDrafts()
            if (config.composeurComplet) {
                scheduled = runCatching { Graph.mail.scheduled(limit = PAGE).scheduled }
                    .getOrDefault(scheduled)
            }
            try {
                serverDrafts = Graph.mail.serverDrafts(limit = PAGE).drafts
                serverDraftsError = null
            } catch (e: Exception) {
                // La liste locale reste affichée : ne pas la faire disparaître
                // parce que l'autre moitié n'a pas répondu.
                serverDraftsError = if (e.isOffline())
                    uiText(R.string.mail_server_drafts_offline)
                else uiText(R.string.mail_server_drafts_unavailable)
            } finally {
                serverDraftsLoading = false
            }
        }
    }

    /**
     * Fait remonter au poste ce qui a été retouché au téléphone.
     *
     * ⚠️ Ceci est le vrai chemin d'écriture, pas un rattrapage. Le composeur
     * écrit sur l'appareil au moment de quitter et s'arrête là : sa portée de
     * coroutines meurt avec l'écran, et une écriture distante lancée en
     * partant serait annulée en vol. La remontée a donc lieu ici, où plus rien
     * ne peut l'interrompre, et se retente à chaque ouverture de la section
     * tant qu'elle n'a pas abouti.
     *
     * Un conflit n'est pas une erreur à réessayer : la reprise locale reste en
     * place, la personne rouvre le brouillon et tranche à l'écran.
     */
    private suspend fun pushPendingDrafts() {
        var refuses = 0
        for (pending in Graph.drafts.pendingPushes()) {
            val issue = runCatching { pushServerDraft(pending) }.getOrNull() ?: continue
            if (issue.conflict) {
                refuses += 1
            } else if (issue.ok) {
                Graph.drafts.delete(pending.id)
            }
        }
        if (refuses > 0) {
            notice = uiPlural(R.plurals.mail_server_drafts_conflicts, refuses)
        }
    }

    /**
     * Retenir un envoi programmé : il redevient un brouillon du poste, où il
     * s'ouvre, se corrige et se renvoie. Rien n'est effacé (#25764).
     */
    fun unschedule(envoi: ScheduledMail) {
        val avant = scheduled
        scheduled = scheduled.filterNot { it.id == envoi.id }
        viewModelScope.launch {
            try {
                Graph.mail.unschedule(envoi.id)
                notice = uiText(R.string.mail_scheduled_unscheduled)
                refreshServerDrafts()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scheduled = avant
                error = if (e.isOffline()) uiText(R.string.mail_server_drafts_offline)
                else e.message?.takeIf { it.isNotBlank() && it != "error" }?.let { UiText.Raw(it) }
                    ?: uiText(R.string.mail_scheduled_unschedule_failed)
            }
        }
    }

    /**
     * Jeter un brouillon du poste.
     *
     * Retiré de la liste tout de suite, remis si le serveur refuse : sur une
     * liste courte, attendre l'aller-retour donne l'impression que le geste
     * n'a pas pris.
     */
    fun deleteServerDraft(draft: ServerDraft) {
        val avant = serverDrafts
        serverDrafts = serverDrafts.filterNot { it.id == draft.id }
        viewModelScope.launch {
            try {
                Graph.mail.deleteServerDraft(draft.id)
                notice = uiText(R.string.mail_draft_deleted)
            } catch (e: Exception) {
                serverDrafts = avant
                error = uiText(R.string.mail_delete_failed)
            }
        }
    }

    fun loadMore() {
        if (filter == MailFilter.DRAFTS) return
        if (loadingMore || !hasMore || refreshing) return
        // La page suivante appartient à la liste de CETTE lecture : si une
        // relecture la remplace entre-temps, la suite n'a plus où s'accrocher.
        val n = lecture.courant
        val cle = CleListe(filter, searchTerm, grouped, null, accountId)
        loadingMore = true
        viewModelScope.launch {
            try {
                val resp = Graph.mail.threads(
                    filter = cle.filtre,
                    search = cle.recherche,
                    accountId = cle.boite,
                    offset = threads.size,
                    limit = PAGE,
                    grouped = cle.groupe,
                )
                if (!lecture.estCourante(n)) return@launch
                // Guard against a page that overlaps: a message arriving between
                // two requests shifts every later row down by one, which would
                // otherwise duplicate the boundary thread.
                val known = threads.mapTo(HashSet()) { it.threadKey }
                threads = threads + visible(resp.threads, cle.filtre).filterNot { it.threadKey in known }
                hasMore = resp.hasMore
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (lecture.estCourante(n)) hasMore = false
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

    /**
     * Ne montrer qu'une boîte, ou toutes (`null`). Toucher la boîte déjà
     * choisie revient à toutes : c'est le geste qu'on attend d'une pastille.
     */
    fun selectAccount(next: Int?) {
        accountId = if (next == accountId) null else next
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

    private fun offerUndo(label: UiText, action: () -> Unit) {
        undoLabel = label
        undoable = action
    }

    fun archive(message: MailMessage) {
        hide(message)
        threads = threads.filterNot { it.threadKey == message.threadKey }
        offerUndo(uiText(R.string.mail_archived)) { restore(message) }
        viewModelScope.launch {
            try {
                poserCompteurs(Graph.mail.setHandled(listOf(message.id), handled = true, grouped = grouped))
            } catch (e: Exception) {
                if (e.isOffline()) queueHandle(message, handled = true)
                else {
                    // Refusé : la ligne doit revenir, donc la pierre tombale part.
                    unhide(message)
                    error = uiText(R.string.mail_archive_failed)
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
        queued = Graph.outbox.enAttenteVisible()
        offline = true
        notice = if (handled) uiText(R.string.mail_archive_queued)
        else uiText(R.string.mail_restore_queued)
    }

    fun snooze(message: MailMessage, untilMs: Long) {
        hide(message)
        threads = threads.filterNot { it.threadKey == message.threadKey }
        offerUndo(uiText(R.string.mail_snoozed)) { restore(message) }
        viewModelScope.launch {
            try {
                poserCompteurs(Graph.mail.snooze(listOf(message.id), untilMs, grouped = grouped))
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
                    queued = Graph.outbox.enAttenteVisible()
                    offline = true
                    notice = uiText(R.string.mail_snooze_queued)
                } else {
                    unhide(message)
                    error = uiText(R.string.mail_snooze_failed)
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
                poserCompteurs(Graph.mail.setHandled(listOf(message.id), handled = false, grouped = grouped))
                notice = uiText(R.string.mail_restored)
            } catch (e: Exception) {
                if (e.isOffline()) queueHandle(message, handled = false)
                else {
                    unhide(message)
                    error = uiText(R.string.mail_action_failed)
                    refresh()
                }
            }
        }
    }

    fun markRead(message: MailMessage) {
        viewModelScope.launch {
            try {
                poserCompteurs(Graph.mail.markRead(listOf(message.id), grouped = grouped))
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
                    queued = Graph.outbox.enAttenteVisible()
                }
            }
        }
    }

    fun spawn(message: MailMessage, kind: String) {
        viewModelScope.launch {
            try {
                val resp = Graph.mail.spawn(message.id, kind)
                notice = resp.record?.let { uiText(R.string.mail_created_record, it.name) }
                    ?: uiText(R.string.mail_created)
                refresh()
            } catch (e: Exception) {
                // Le message d'une ApiException est celui du serveur : tel quel.
                error = e.message?.let { UiText.Raw(it) } ?: uiText(R.string.mail_create_failed)
            }
        }
    }

    fun route(message: MailMessage, model: String, recordId: Int) {
        viewModelScope.launch {
            try {
                val resp = Graph.mail.route(message.id, model, recordId)
                notice = resp.record?.let { uiText(R.string.mail_routed_record, it.name) }
                    ?: uiText(R.string.mail_routed)
                refresh()
            } catch (e: Exception) {
                error = e.message?.let { UiText.Raw(it) } ?: uiText(R.string.mail_route_failed)
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
        errorText: UiText,
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
                poserCompteurs(call(targets.map { it.id }))
            } catch (e: Exception) {
                if (e.isOffline()) {
                    Graph.outbox.enqueue(pending)
                    queued = Graph.outbox.enAttenteVisible()
                    offline = true
                    notice = uiText(R.string.mail_action_queued)
                } else {
                    targets.forEach { unhide(it) }
                    error = errorText
                    refresh()
                }
            }
        }
    }

    /**
     * Le cas d'un seul garde sa phrase sans chiffre (« Archivé ») ; au-delà, un
     * `<plurals>` qui porte le compte.
     */
    private fun plural(n: Int, @StringRes one: Int, @PluralsRes many: Int): UiText =
        if (n == 1) uiText(one) else uiPlural(many, n)

    fun archiveSelected() {
        val targets = selectedMessages
        if (targets.isEmpty()) return
        clearSelection()
        offerUndo(plural(targets.size, R.string.mail_archived, R.plurals.mail_archived_count)) {
            restoreMany(targets)
        }
        applyToSelection(
            targets = targets,
            removeRows = true,
            errorText = uiText(R.string.mail_archive_failed),
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
                poserCompteurs(Graph.mail.setHandled(
                    targets.map { it.id }, handled = false, grouped = grouped))
                notice = plural(targets.size, R.string.mail_restored, R.plurals.mail_restored_count)
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
                    queued = Graph.outbox.enAttenteVisible()
                    offline = true
                    notice = uiText(R.string.mail_restore_queued)
                } else {
                    targets.forEach { unhide(it) }
                    error = uiText(R.string.mail_action_failed)
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
            errorText = uiText(R.string.mail_action_failed),
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
        offerUndo(plural(targets.size, R.string.mail_snoozed, R.plurals.mail_snoozed_count)) {
            restoreMany(targets)
        }
        applyToSelection(
            targets = targets,
            removeRows = true,
            errorText = uiText(R.string.mail_snooze_failed),
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
