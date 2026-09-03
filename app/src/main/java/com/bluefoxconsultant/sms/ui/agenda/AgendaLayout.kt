package com.bluefoxconsultant.sms.ui.agenda

import com.bluefoxconsultant.sms.data.AgendaEvent
import java.time.LocalDate
import java.time.ZoneId

/** Une rencontre, telle qu'elle occupe UNE colonne de UN jour. */
data class EventSegment(
    val event: AgendaEvent,
    /** Minutes depuis minuit local, bornées au jour. */
    val startMinutes: Int,
    val durationMinutes: Int,
    val columnIndex: Int,
    val columnCount: Int,
)

/** Hauteur minimale d'un segment, en minutes, pour rester cliquable. */
private const val MIN_MINUTES = 20

/**
 * Découpe les rencontres d'un jour et leur attribue une colonne.
 *
 * Trois pièges valent la peine d'être nommés.
 *
 * 1. **Le débordement de minuit.** Une rencontre de 22:00 à 01:00 appartient à
 *    deux jours. Chaque jour n'en reçoit que le morceau qui le concerne, sinon
 *    elle déborderait de la grille au lieu de s'y arrêter.
 * 2. **Le partage de largeur est par GRAPPE, pas global.** Compter les
 *    colonnes sur toute la journée réduirait deux rencontres du matin au tiers
 *    de la largeur parce que trois autres se chevauchent le soir.
 * 3. **Une rencontre de cinq minutes reste cliquable.** Sa hauteur est portée
 *    à un plancher, ce qui la fait chevaucher la suivante — assumé : invisible
 *    serait pire.
 */
fun segmentsFor(day: LocalDate, events: List<AgendaEvent>, zone: ZoneId): List<EventSegment> {
    val dayStart = day.atStartOfDay(zone).toInstant()
    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()

    data class Brut(val event: AgendaEvent, val start: Int, val end: Int)

    val bruts = events.mapNotNull { event ->
        if (event.allday) return@mapNotNull null
        val start = event.startInstant ?: return@mapNotNull null
        val stop = event.stopInstant ?: start
        if (!stop.isAfter(dayStart) || !start.isBefore(dayEnd)) return@mapNotNull null
        val from = if (start.isBefore(dayStart)) dayStart else start
        val to = if (stop.isAfter(dayEnd)) dayEnd else stop
        val startMin = ((from.epochSecond - dayStart.epochSecond) / 60).toInt()
        val endMin = ((to.epochSecond - dayStart.epochSecond) / 60).toInt()
        Brut(event, startMin.coerceIn(0, 1440), endMin.coerceIn(0, 1440))
    }.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))

    if (bruts.isEmpty()) return emptyList()

    val out = mutableListOf<EventSegment>()
    var grappe = mutableListOf<Brut>()
    var finDeGrappe = -1

    fun viderLaGrappe() {
        if (grappe.isEmpty()) return
        // Attribution gloutonne : la première colonne libre au moment voulu.
        val finParColonne = mutableListOf<Int>()
        val colonneDe = mutableMapOf<Int, Int>()
        grappe.forEachIndexed { index, brut ->
            val hauteur = maxOf(brut.end - brut.start, MIN_MINUTES)
            val libre = finParColonne.indexOfFirst { it <= brut.start }
            val col = if (libre >= 0) libre else finParColonne.size
            if (libre >= 0) finParColonne[libre] = brut.start + hauteur
            else finParColonne.add(brut.start + hauteur)
            colonneDe[index] = col
        }
        val total = finParColonne.size.coerceAtLeast(1)
        grappe.forEachIndexed { index, brut ->
            out.add(
                EventSegment(
                    event = brut.event,
                    startMinutes = brut.start,
                    durationMinutes = maxOf(brut.end - brut.start, MIN_MINUTES),
                    columnIndex = colonneDe[index] ?: 0,
                    columnCount = total,
                ),
            )
        }
        grappe = mutableListOf()
        finDeGrappe = -1
    }

    bruts.forEach { brut ->
        val hauteur = maxOf(brut.end - brut.start, MIN_MINUTES)
        if (grappe.isNotEmpty() && brut.start >= finDeGrappe) viderLaGrappe()
        grappe.add(brut)
        finDeGrappe = maxOf(finDeGrappe, brut.start + hauteur)
    }
    viderLaGrappe()
    return out
}
