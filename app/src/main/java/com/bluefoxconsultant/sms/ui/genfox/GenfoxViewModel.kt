package com.bluefoxconsultant.sms.ui.genfox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.GenfoxMessage
import com.bluefoxconsultant.sms.data.GenfoxSession
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiText

/**
 * One conversation with GenFox on screen, and the polling that goes with it.
 *
 * The question is recorded server-side and answered later, so this holds an
 * optimistic pair — the question, and a pending answer — and replaces the
 * second when the turn lands. Leaving the screen does not cancel the turn: it
 * finishes on the server and a push announces it.
 *
 * ⚠️ Plusieurs conversations peuvent travailler en même temps (#25734). L'état
 * « Gen répond » n'est donc PAS un drapeau de l'écran : il se lit sur la
 * conversation affichée ([asking]). Jusqu'à la 2.42, un drapeau global restait
 * levé quand on ouvrait une autre conversation ou une neuve, et le sondage du
 * tour quitté continuait d'écrire sa réponse dans la conversation d'arrivée.
 * Changer de conversation arrête maintenant le sondage ([vue]) ; le tour
 * quitté continue au serveur, la liste le montre au travail, et on le retrouve
 * en y revenant.
 */
class GenfoxViewModel(
    /** Le niveau de la surface serveur, lu au moment du geste ; le banc le fixe. */
    private val apiServeur: () -> Int = { Graph.genfoxStore.config.value.api },
) : ViewModel() {

    var messages by mutableStateOf<List<GenfoxMessage>>(emptyList())
        private set
    var sessionId by mutableStateOf<Int?>(null)
        private set
    var sessionName by mutableStateOf("")
        private set
    var sessions by mutableStateOf<List<GenfoxSession>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiText?>(null)
        private set

    /** La question part au serveur : le temps de l'aller-retour de `/ask`. */
    var sending by mutableStateOf(false)
        private set

    /** Arrêter a été touché ; le tour n'est pas encore enregistré. */
    var stopping by mutableStateOf(false)
        private set

    /**
     * Une question que le serveur n'a pas prise, rendue à la zone de saisie
     * pour qu'elle ne se perde pas. L'écran la consomme.
     */
    var questionRendue by mutableStateOf<String?>(null)
        private set

    /** Gen travaille dans la conversation AFFICHÉE, et seulement celle-là. */
    val asking: Boolean get() = sending || messages.lastOrNull()?.isPending == true

    /** Le tour en cours ici, s'il est arrivé au serveur et qu'on peut l'arrêter. */
    val tourArretable: Int?
        get() = messages.lastOrNull()?.takeIf { it.isPending && it.id > 0 }?.id
            ?.takeIf { apiServeur() >= 4 }

    private var pollJob: Job? = null

    /**
     * Le numéro de la vue. Chaque changement de conversation l'incrémente, et
     * toute réponse réseau partie sous une autre vue est jetée : c'est ce qui
     * empêche la réponse du fil A d'atterrir dans le fil B.
     */
    private var vue = 0

    /**
     * Vrai quand on a explicitement demandé une conversation neuve.
     *
     * ⚠️ Sans ce drapeau, le geste d'assistance arrive pendant que le
     * `openLatest()` du constructeur vole encore, et la conversation d'hier
     * atterrit PAR-DESSUS la neuve — une course qui ne se voit qu'un appareil
     * lent ou un serveur qui traîne.
     */
    private var fresh = false

    init {
        openLatest()
    }

    fun clearError() { error = null }

    fun questionRestituee() { questionRendue = null }

    /** Opens the most recent conversation, or an empty one if there is none. */
    fun openLatest() {
        val depart = vue
        viewModelScope.launch {
            loading = true
            try {
                sessions = Graph.genfox.sessions()
                // Une conversation a été ouverte pendant l'aller-retour (la
                // notification d'une réponse, une neuve) : la plus récente ne
                // doit pas passer par-dessus.
                if (fresh || vue != depart) return@launch
                val latest = sessions.firstOrNull()
                if (latest == null) {
                    reset()
                } else {
                    open(latest.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = uiText(R.string.gen_error_unreachable)
            } finally {
                // Une conversation ouverte entre-temps tient son propre témoin.
                if (vue == depart) loading = false
            }
        }
    }

    fun refreshSessions() {
        viewModelScope.launch {
            try {
                sessions = Graph.genfox.sessions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // La liste garde ce qu'elle montrait.
            }
        }
    }

    fun open(id: Int) {
        fresh = false
        val ici = changerDeVue()
        viewModelScope.launch {
            loading = true
            try {
                val resp = Graph.genfox.messages(id)
                // La neuve a été demandée pendant l'aller-retour, ou une autre
                // conversation : ce résultat est périmé, l'écrire écraserait
                // celle qu'on regarde.
                if (fresh || ici != vue) return@launch
                sessionId = resp.sessionId
                sessionName = resp.sessionName
                messages = resp.messages
                // Reopening while a turn is still running: pick the polling
                // back up rather than leaving a dot spinning forever.
                resp.messages.lastOrNull()?.takeIf { it.isPending }?.let { poll(it.id, ici) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ici == vue) error = uiText(R.string.gen_error_unreadable)
            } finally {
                if (ici == vue) loading = false
            }
        }
    }

    /**
     * Une conversation neuve, demandée de l'extérieur — le geste d'assistance.
     *
     * Distinct de [reset] : celui-ci se contente de vider l'écran, alors que
     * la neuve doit aussi tenir devant un `openLatest()` encore en vol.
     */
    fun startFresh() {
        fresh = true
        reset()
        refreshSessions()
    }

    /** Une conversation neuve, même pendant qu'une autre travaille. */
    fun reset() {
        changerDeVue()
        sessionId = null
        sessionName = ""
        messages = emptyList()
        loading = false
    }

    private fun changerDeVue(): Int {
        pollJob?.cancel()
        pollJob = null
        vue += 1
        sending = false
        stopping = false
        return vue
    }

    fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty() || asking) return
        val ici = vue
        val conversation = sessionId
        viewModelScope.launch {
            sending = true
            messages = messages + GenfoxMessage(role = "user", content = question) +
                GenfoxMessage(role = "assistant", content = "", state = "pending")
            try {
                val resp = Graph.genfox.ask(question, conversation)
                if (ici != vue) {
                    // On est parti ailleurs pendant l'envoi : la question est
                    // posée, la liste montrera la conversation au travail.
                    refreshSessions()
                    return@launch
                }
                sessionId = resp.sessionId
                sending = false
                poll(resp.turnId, ici)
                refreshSessions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (ici != vue) return@launch
                sending = false
                if (e.code == 409 && conversation != null) {
                    // Un tour tourne déjà dans cette conversation (posé au
                    // bureau, ou par un autre téléphone). Rien n'a été écrit :
                    // la question revient à la saisie, et on affiche le tour
                    // qui tourne vraiment, avec son bouton Arrêter.
                    questionRendue = question
                    error = uiText(R.string.gen_error_busy)
                    open(conversation)
                } else {
                    notPosee()
                }
            } catch (e: Exception) {
                if (ici != vue) return@launch
                sending = false
                notPosee()
            }
        }
    }

    private fun notPosee() {
        replacePending(GenfoxMessage(
            role = "assistant",
            state = "error",
            avis = uiText(R.string.gen_error_not_asked),
        ))
    }

    /**
     * Le bouton Arrêter. On garde le sondage : c'est lui qui verra le tour
     * s'enregistrer avec ce qu'il avait écrit, souvent quelques secondes après.
     */
    fun stop() {
        val tour = tourArretable ?: return
        if (stopping) return
        stopping = true
        val ici = vue
        viewModelScope.launch {
            try {
                Graph.genfox.stop(tour)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ici != vue) return@launch
                stopping = false
                error = uiText(R.string.gen_error_not_stopped)
            }
        }
    }

    fun delete(id: Int) {
        viewModelScope.launch {
            runCatching { Graph.genfox.deleteSession(id) }
            if (sessionId == id) reset()
            refreshSessions()
        }
    }

    private fun poll(turnId: Int, ici: Int) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            // Fast enough that the answer visibly writes itself, slow enough not
            // to hammer an instance that is busy thinking. Each poll returns the
            // text SO FAR, so progress is real rather than animated for show.
            repeat(MAX_POLLS) {
                delay(POLL_MS)
                val turn = try {
                    Graph.genfox.turn(turnId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: return@repeat
                if (ici != vue) return@launch
                replacePending(GenfoxMessage(
                    id = turn.turnId,
                    role = "assistant",
                    content = turn.text,
                    state = turn.state,
                    tools = turn.tools,
                    inputTokens = turn.usage.inputTokens,
                    outputTokens = turn.usage.outputTokens,
                    cacheReadTokens = turn.usage.cacheReadTokens,
                    cacheWriteTokens = turn.usage.cacheWriteTokens,
                    netTokens = turn.usage.netTokens,
                    totalTokens = turn.usage.totalTokens,
                    costUsd = turn.usage.costUsd,
                    durationMs = turn.usage.durationMs,
                    endReason = turn.endReason,
                ))
                if (turn.state != "pending") {
                    sessionName = turn.sessionName
                    stopping = false
                    refreshSessions()
                    return@launch
                }
            }
            if (ici != vue) return@launch
            replacePending(GenfoxMessage(
                role = "assistant",
                state = "error",
                avis = uiText(R.string.gen_error_still_running),
            ))
            stopping = false
        }
    }

    private fun replacePending(replacement: GenfoxMessage) {
        val index = messages.indexOfLast { it.isPending || (replacement.id > 0 && it.id == replacement.id) }
        messages = if (index < 0) messages + replacement
        else messages.toMutableList().also { it[index] = replacement }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val POLL_MS = 700L
        const val MAX_POLLS = 430
    }
}
