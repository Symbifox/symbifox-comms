package com.bluefoxconsultant.sms.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Une lecture à la fois, et c'est la dernière LANCÉE qui écrit.
 *
 * 🔴 Le défaut réparé (audit du 2026-09-08, Q-M6) : les listes lançaient une
 * lecture par geste sans annuler la précédente. Deux lectures en vol — un
 * filtre changé pendant qu'une page arrive, une recherche tapée vite, un tirer
 * pendant le battement — et c'était la dernière ARRIVÉE qui gagnait : l'écran
 * montrait « Traités » sous l'onglet « Réception », et le cache du courriel
 * pouvait enregistrer la page d'un filtre sous le nom d'un autre.
 *
 * Deux gardes, parce qu'aucune ne suffit seule :
 * - la lecture neuve ANNULE la précédente — ce qui ne sert à rien contre une
 *   réponse déjà revenue et en attente du fil principal ;
 * - un NUMÉRO dit à chaque réponse si elle est encore attendue, et celle qui ne
 *   l'est plus n'écrit ni l'état, ni le cache, ni les témoins de chargement.
 *
 * Confiné au fil principal, comme les modèles de vue qui s'en servent.
 */
class Sequenceur {

    @Volatile
    private var numero = 0L
    private var travail: Job? = null

    /** Le numéro de la dernière lecture lancée. */
    val courant: Long get() = numero

    /** Annule la lecture en vol et lance [bloc], qui reçoit son numéro. */
    fun lancer(scope: CoroutineScope, bloc: suspend CoroutineScope.(numero: Long) -> Unit): Job {
        travail?.cancel()
        val n = ++numero
        return scope.launch { bloc(n) }.also { travail = it }
    }

    /** Plus rien de ce qui est en vol ne doit écrire. */
    fun annuler() {
        travail?.cancel()
        travail = null
        numero++
    }

    /** La lecture [n] est-elle encore celle qu'on attend ? */
    fun estCourante(n: Long): Boolean = n == numero
}
