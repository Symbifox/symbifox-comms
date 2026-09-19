package com.bluefoxconsultant.sms.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ce que la barre du bas porte en pastille, là où c'est pertinent.
 *
 * Trois nombres, pas six : les messages et le courriel ont des NON-LUS, les
 * tâches ont des RETARDS et des échéances du jour. L'agenda n'a rien à compter (une rencontre ne se
 * « lit » pas), Gen répond par notification, et un appel manqué vit déjà dans
 * le journal du téléphone. Une pastille sur un onglet où rien n'attend serait
 * du bruit qui finit par cacher les autres.
 */
data class Badges(
    val sms: Int = 0,
    val courriel: Int = 0,
    val taches: Int = 0,
)

/**
 * La pastille du courriel : les non-lus de la RÉCEPTION.
 *
 * Demande d'Olivier du 2026-09-14 (#25717) : `unread` compte tout non-lu non
 * traité, y compris ce qui dort hors de la réception — autres dossiers IMAP,
 * courriels en sourdine — et la pastille annonçait du travail qu'on ne
 * trouvait pas en ouvrant la réception. Un serveur qui ne rend pas encore
 * `inbox_unread` garde l'ancien compte plutôt que zéro.
 */
fun pastilleCourriel(counts: MailCounts): Int = counts.inboxUnread ?: counts.unread

/**
 * Le total des non-lus d'une liste de fils. Pure : c'est elle que le banc
 * éprouve. Les fils masqués comptent quand même — masquer n'est pas lire.
 */
fun nonLusDesFils(fils: List<Thread>): Int = fils.sumOf { it.unreadCount.coerceAtLeast(0) }

/**
 * La source unique des pastilles.
 *
 * Deux voies l'alimentent, et c'est voulu. Les écrans qui LISENT déjà leurs
 * listes y déposent le nombre frais au passage (lire un courriel fait tomber
 * la pastille sans attendre). Et l'accueil relit les trois compteurs à la
 * minute pendant qu'on regarde N'IMPORTE QUEL onglet, sinon la pastille du
 * courriel ne bougerait que quand on est déjà dans le courriel — c'est-à-dire
 * jamais quand elle sert.
 */
class BadgeStore {
    private val _badges = MutableStateFlow(Badges())
    val badges: StateFlow<Badges> = _badges.asStateFlow()

    fun poserSms(n: Int) { _badges.value = _badges.value.copy(sms = n.coerceAtLeast(0)) }
    fun poserCourriel(n: Int) { _badges.value = _badges.value.copy(courriel = n.coerceAtLeast(0)) }
    fun poserTaches(n: Int) { _badges.value = _badges.value.copy(taches = n.coerceAtLeast(0)) }

    fun vider() { _badges.value = Badges() }
}
