package com.bluefoxconsultant.sms.ui.agenda

import com.bluefoxconsultant.sms.data.AgendaTask
import com.bluefoxconsultant.sms.data.AgendaTaskCounts
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Le découpage de la liste des tâches, et ce qui doit compter pareil ailleurs.
 *
 * Tout est pur ici, et c'est voulu : minuit, le fuseau et le jour qui change
 * se jouent dans un banc de trois millisecondes, pas sur un téléphone qu'on
 * laisse ouvert jusqu'au lendemain.
 */

/** Combien de jours reçoivent chacun leur intertitre, après aujourd'hui. */
const val JOURS_DETAILLES = 7L

/**
 * Ce qu'une section annonce, pas encore mis en mots.
 *
 * Le découpage ne choisit pas la langue (Q-M8) : il dit « en retard » ou « le
 * mardi 15 », et c'est l'écran qui l'écrit dans la langue du téléphone. Écrire
 * le titre ici figerait la langue au moment de la lecture, et le banc devrait
 * comparer des phrases plutôt que le découpage qu'il éprouve.
 */
sealed interface Intertitre {
    data object EnRetard : Intertitre
    data object Aujourdhui : Intertitre
    data class Jour(val date: LocalDate) : Intertitre
    data object PlusTard : Intertitre
}

/**
 * Une section de la liste. [cle] est stable d'une relecture à l'autre, pour
 * que la liste paresseuse garde sa position.
 */
data class SectionTaches(
    val cle: String,
    val intertitre: Intertitre,
    val taches: List<AgendaTask>,
    val alerte: Boolean = false,
    /** Faux pour les jours : « Mardi 15 septembre » se lit mal en capitales. */
    val majuscules: Boolean = true,
)

/**
 * « Mardi 15 septembre », dans la langue de [format] : l'écran lui passe le
 * motif qu'Android donne à la langue du téléphone, le banc un motif fixe.
 */
fun titreDuJour(jour: LocalDate, format: DateTimeFormatter): String =
    format.format(jour).replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(format.locale) else it.toString()
    }

/**
 * Les tâches datées, redécoupées par DATE LOCALE d'échéance sur l'appareil.
 *
 * Demande d'Olivier du 2026-09-14 (#25717) : En retard, Aujourd'hui, un
 * intertitre par jour pour les sept jours suivants, puis Plus tard.
 *
 * ⚠️ Le serveur découpe à l'INSTANT : `overdue` est ce qui tombe avant
 * maintenant, `window` ce qui tombe après. Une tâche due ce matin à 9:00 lui
 * est donc « en retard » à 14:00 — alors que pour la personne qui lit sa liste,
 * c'est une tâche d'aujourd'hui. On fusionne les deux seaux et on redécoupe par
 * jour, dans le fuseau de l'appareil, qui est l'heure qu'on lit sur soi.
 *
 * Dans chaque section : par échéance, puis priorité d'abord à échéance égale,
 * et l'ordre du serveur à égalité parfaite (le tri est stable). Les jours sans
 * tâche n'ont pas d'intertitre. Une tâche présente dans les deux seaux (une
 * échéance qui tombe pile pendant la requête) n'apparaît qu'une fois.
 */
fun decouperTaches(
    retards: List<AgendaTask>,
    fenetre: List<AgendaTask>,
    zone: ZoneId,
    aujourdhui: LocalDate,
): List<SectionTaches> {
    val datees = (retards + fenetre)
        .distinctBy { it.id }
        .mapNotNull { tache -> tache.deadlineAt(zone)?.let { tache to it } }
        .sortedWith(
            compareBy<Pair<AgendaTask, java.time.ZonedDateTime>> { it.second.toInstant() }
                .thenByDescending { it.first.priority.toIntOrNull() ?: 0 },
        )

    val enRetard = mutableListOf<AgendaTask>()
    val duJour = mutableListOf<AgendaTask>()
    val parJour = sortedMapOf<LocalDate, MutableList<AgendaTask>>()
    val plusTard = mutableListOf<AgendaTask>()
    val dernierDetaille = aujourdhui.plusDays(JOURS_DETAILLES)

    datees.forEach { (tache, echeance) ->
        val jour = echeance.toLocalDate()
        when {
            jour.isBefore(aujourdhui) -> enRetard += tache
            jour == aujourdhui -> duJour += tache
            !jour.isAfter(dernierDetaille) -> parJour.getOrPut(jour) { mutableListOf() } += tache
            else -> plusTard += tache
        }
    }

    return buildList {
        if (enRetard.isNotEmpty()) {
            add(SectionTaches("retard", Intertitre.EnRetard, enRetard, alerte = true))
        }
        if (duJour.isNotEmpty()) add(SectionTaches("aujourdhui", Intertitre.Aujourdhui, duJour))
        parJour.forEach { (jour, taches) ->
            add(SectionTaches("jour-$jour", Intertitre.Jour(jour), taches, majuscules = false))
        }
        if (plusTard.isNotEmpty()) add(SectionTaches("plus-tard", Intertitre.PlusTard, plusTard))
    }
}

/**
 * La pastille de l'onglet Tâches : les retards ET les échéances d'aujourd'hui.
 *
 * Partagée par l'accueil et l'écran des tâches, pour qu'ils comptent pareil.
 * Avant (#25717), l'écran ne comptait que `overdue.size` — ce qui mêlait les
 * tâches de ce matin aux vrais retards et oubliait celles de ce soir — et
 * l'accueil lisait « maintenant plus 24 h », donc une partie de demain.
 *
 * Aujourd'hui est la date locale de l'appareil. Une tâche faite ne compte pas.
 */
fun pastilleTaches(
    retards: List<AgendaTask>,
    fenetre: List<AgendaTask>,
    zone: ZoneId,
    aujourdhui: LocalDate,
): Int = (retards + fenetre)
    .distinctBy { it.id }
    .count { tache ->
        !tache.done && tache.deadlineAt(zone)?.toLocalDate()?.isAfter(aujourdhui) == false
    }

/**
 * Les compteurs par jour de la grille, ou rien.
 *
 * ⚠️ Rien plutôt que faux (Q-M7) : un serveur ancien ignore le `tz` demandé
 * et groupe dans le fuseau du compte. Poser ses nombres sur les jours de
 * l'appareil mettrait la pastille sur le mauvais jour dès que les deux fuseaux
 * diffèrent — une pastille absente se remarque moins qu'une pastille qui ment.
 */
fun compteursPourLaGrille(reponse: AgendaTaskCounts, fuseauAppareil: ZoneId): Map<LocalDate, Int> {
    if (!memeFuseau(reponse.tz, fuseauAppareil)) return emptyMap()
    return reponse.counts.mapNotNull { (cle, compte) ->
        runCatching { LocalDate.parse(cle) }.getOrNull()?.let { it to compte }
    }.toMap()
}

/**
 * Deux fuseaux découpent-ils les jours pareil ? Le même identifiant, ou deux
 * noms aux règles identiques (un alias) : les jours tombent au même instant.
 */
internal fun memeFuseau(tz: String, zone: ZoneId): Boolean {
    val lu = runCatching { ZoneId.of(tz.trim()) }.getOrNull() ?: return false
    return lu == zone || lu.normalized() == zone.normalized() || lu.rules == zone.rules
}
