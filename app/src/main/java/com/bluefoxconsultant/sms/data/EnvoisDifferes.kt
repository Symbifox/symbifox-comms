package com.bluefoxconsultant.sms.data

import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * « Annuler l'envoi » : un envoi décidé attend quelques secondes avant de
 * partir (#25764).
 *
 * Le composeur se ferme au toucher d'Envoyer, comme avant ; c'est donc ici, et
 * pas dans son modèle de vue, que l'envoi attend. La portée du composeur meurt
 * avec l'écran, et un délai tenu par elle serait annulé au premier retour
 * arrière — le message ne partirait jamais.
 *
 * **L'attente passe par la file hors ligne**, avec une heure « pas avant ».
 * C'est ce qui rend le délai sûr : l'app tuée pendant les dix secondes ne perd
 * pas le message, la file le fait partir à sa prochaine vidange. Et c'est ce
 * qui rend « Annuler » sûr : [MailOutbox.retirer] prend le même verrou que
 * l'envoi, donc une annulation touchée pendant que le message part attend son
 * issue et répond « trop tard » plutôt que de mentir.
 *
 * Un envoi que le serveur REFUSE redevient le brouillon qu'il était, pièces et
 * destinataires compris : le composeur n'est plus là pour afficher l'erreur et
 * garder le texte, c'est donc ici qu'on le garde.
 */
class EnvoisDifferes(
    private val outbox: MailOutbox,
    private val drafts: MailDrafts,
    private val envoyer: suspend (PendingAction) -> Unit,
    private val portee: CoroutineScope,
    private val horloge: () -> Long = System::currentTimeMillis,
) {

    /** Un envoi dont le délai court encore, pour le bandeau. */
    data class EnAttente(val token: String, val label: UiText, val echeanceMs: Long)

    /** Ce qu'il est advenu d'un envoi différé, dit une fois. */
    sealed interface Issue {
        data object Parti : Issue
        data object HorsLigne : Issue
        /** Refusé par le serveur ; gardé dans le brouillon [brouillonId]. */
        data class Refuse(val brouillonId: String, val raison: UiText) : Issue
        /** Trop tard pour annuler : le message était déjà parti. */
        data object TropTard : Issue
        /** Ce qu'un écran fermé avait encore à dire (envoi programmé, mis en file…). */
        data class Info(val texte: UiText) : Issue
    }

    private val _enAttente = MutableStateFlow<List<EnAttente>>(emptyList())
    val enAttente: StateFlow<List<EnAttente>> = _enAttente.asStateFlow()

    private val _issue = MutableStateFlow<Issue?>(null)
    val issue: StateFlow<Issue?> = _issue.asStateFlow()

    /**
     * Les envois dont « Annuler » a été touché. Posé AVANT d'attendre le verrou
     * de la file : une vidange qui le tient ne les prend plus (voir
     * [MailOutbox.flush]).
     */
    private val annules = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun consommerIssue() {
        _issue.value = null
    }

    /**
     * Poser l'issue à dire. ⚠️ Un REFUS n'est pas remplacé par une issue plus
     * banale : un envoi refusé dort dans les brouillons, et un « Message
     * envoyé » arrivé juste après le ferait croire parti.
     */
    private fun poser(issue: Issue) {
        if (_issue.value is Issue.Refuse && issue !is Issue.Refuse) return
        _issue.value = issue
    }

    /** Dire quelque chose après la fermeture de l'écran qui le savait. */
    fun annoncer(texte: UiText) {
        poser(Issue.Info(texte))
    }

    /** À la déconnexion : la file est vidée, le bandeau aussi. */
    fun reinitialiser() {
        _enAttente.value = emptyList()
        _issue.value = null
        annules.clear()
    }

    /**
     * Au démarrage de l'app : reprendre les envois qu'un processus mort a
     * laissés en file pendant leur délai (#25764).
     *
     * Sans cette reprise, le message ne partait qu'à la prochaine ouverture de
     * l'onglet Courriel, parfois des heures plus tard, et sans bandeau. Ceux
     * dont le délai court encore retrouvent leur bandeau et leur minuterie ;
     * les autres partent tout de suite.
     */
    suspend fun reprendre() {
        val maintenant = horloge()
        val suivis = _enAttente.value.map { it.token }.toSet()
        val enCours = outbox.peek().filter {
            it.isSend && it.notBeforeMs > maintenant && it.token !in suivis
        }
        for (action in enCours) {
            _enAttente.update { it + EnAttente(action.token, action.label, action.notBeforeMs) }
            portee.launch {
                delay(action.notBeforeMs - maintenant)
                vider()
            }
        }
        if (outbox.peek().any { it.isSend && it.notBeforeMs in 1..maintenant }) vider()
    }

    /** Mettre un envoi en attente pendant [delaiMs], puis le faire partir. */
    fun programmer(action: PendingAction, delaiMs: Long) {
        val echeance = horloge() + delaiMs
        outbox.enqueue(action.copy(notBeforeMs = echeance))
        _enAttente.update { it + EnAttente(action.token, action.label, echeance) }
        portee.launch {
            delay(delaiMs)
            vider()
        }
    }

    /**
     * Annuler un envoi en attente. Rend le brouillon où le texte a été remis,
     * ou `null` quand il est trop tard : le message est parti.
     */
    suspend fun annuler(token: String): MailDraft? {
        // Un second appui sur un envoi déjà annulé ne dit rien : c'est le
        // premier qui a rouvert le brouillon.
        if (_enAttente.value.none { it.token == token }) return null
        annules += token
        try {
            val action = outbox.retirer(token)
            _enAttente.update { liste -> liste.filterNot { it.token == token } }
            if (action == null) {
                poser(Issue.TropTard)
                return null
            }
            val brouillon = brouillonDepuisEnvoi(action)
            return drafts.save(brouillon) ?: brouillon
        } finally {
            annules -= token
        }
    }

    /**
     * Vider la file : ce qui est dû part, ce qui est refusé redevient un
     * brouillon. Appelé à l'échéance de chaque délai, et par la liste des
     * courriels à chaque relecture — le même chemin pour les deux, sans quoi
     * un refus rattrapé par l'un serait perdu par l'autre.
     */
    suspend fun vider(): Int {
        var refus: Issue.Refuse? = null
        val envoyes = try {
            outbox.flush(envoyer, horloge, ignorer = { it.token in annules }) { action, erreur ->
                if (!action.isSend) return@flush false
                val brouillon = brouillonDepuisEnvoi(action)
                val garde = drafts.save(brouillon) ?: brouillon
                // Suivi ou non (un envoi mis en file hors ligne la veille),
                // un refus se dit : le texte n'a pas disparu, il est ici.
                refus = Issue.Refuse(garde.id, raisonDuRefus(erreur))
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0
        }
        val restants = outbox.peek().associateBy { it.token }
        val maintenant = horloge()
        val echus = _enAttente.value.filter { it.echeanceMs <= maintenant && it.token !in annules }
        _enAttente.update { liste -> liste.filter { it.echeanceMs > maintenant } }
        when {
            refus != null -> poser(refus!!)
            echus.any { it.token in restants } -> poser(Issue.HorsLigne)
            echus.isNotEmpty() -> poser(Issue.Parti)
        }
        return envoyes
    }

    private fun raisonDuRefus(erreur: Throwable): UiText =
        erreur.message?.takeIf { it.isNotBlank() && it != "error" }
            ?.let { UiText.Raw(it) }
            ?: uiText(R.string.mail_send_failed)
}
