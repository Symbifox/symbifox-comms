package com.bluefoxconsultant.sms.ui.agenda

import com.bluefoxconsultant.sms.data.AgendaTask
import com.bluefoxconsultant.sms.data.parseInstant
import java.text.Normalizer
import java.util.Locale

/**
 * La recherche dans les tâches (#25734), et son repli sur ce que l'écran tient.
 *
 * Le serveur cherche dans TOUTES mes tâches ouvertes (`/tasks/search`, api 4).
 * Face à un serveur plus ancien, l'écran filtre ce qu'il a déjà lu avec la
 * même règle, et le dit : les seaux ne couvrent que l'horizon choisi et une
 * partie des sans-échéance, donc « aucun résultat » n'y veut pas dire « aucune
 * tâche ».
 *
 * Pur, pour que le banc éprouve la règle sans téléphone.
 */

/** Au-delà, les mots n'affinent plus rien : même borne que le serveur. */
const val MOTS_CHERCHES = 5

/** Sans accents ni casse : « Échéance » se trouve en tapant « echeance ». */
internal fun plier(texte: String): String =
    Normalizer.normalize(texte, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)

/** Le numéro d'une tâche, tapé « 25734 » ou « #25734 », sinon null. */
internal fun numeroCherche(terme: String): Int? =
    terme.trim().removePrefix("#").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?.toIntOrNull()

/**
 * Vaut-il la peine de chercher ? Deux caractères, ou un numéro : une lettre
 * seule rendrait la moitié de la base, et le serveur ne la cherche pas non plus.
 */
fun termeCherchable(terme: String): Boolean =
    terme.trim().length >= 2 || numeroCherche(terme) != null

/**
 * Les tâches où CHAQUE mot se trouve quelque part (titre, projet, client ou
 * étiquette), ou dont le numéro est celui tapé. Triées comme au serveur : par
 * échéance, les sans-échéance à la fin, priorité d'abord à égalité.
 */
fun filtrerTaches(taches: List<AgendaTask>, terme: String): List<AgendaTask> {
    if (!termeCherchable(terme)) return emptyList()
    val numero = numeroCherche(terme)
    val mots = terme.trim().split(Regex("\\s+")).take(MOTS_CHERCHES).map(::plier)
    return taches
        .distinctBy { it.id }
        .filter { tache ->
            val lieux = listOf(tache.name, tache.project, tache.partner) + tache.tags.map { it.name }
            val plies = lieux.map(::plier)
            tache.id == numero || mots.all { mot -> plies.any { it.contains(mot) } }
        }
        .sortedWith(
            compareBy<AgendaTask, java.time.Instant?>(nullsLast()) { parseInstant(it.deadline ?: "") }
                .thenByDescending { it.priority.toIntOrNull() ?: 0 },
        )
}
