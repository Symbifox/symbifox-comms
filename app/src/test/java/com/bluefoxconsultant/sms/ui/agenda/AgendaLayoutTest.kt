package com.bluefoxconsultant.sms.ui.agenda

import com.bluefoxconsultant.sms.data.AgendaEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Les cas qui cassent une pose naïve : le débordement de minuit, le partage de
 * largeur par grappe, et la rencontre trop courte pour être touchée.
 */
class AgendaLayoutTest {

    private val zone: ZoneId = ZoneId.of("America/Toronto")
    private val day: LocalDate = LocalDate.of(2026, 9, 8)

    /** [start] et [stop] sont donnés en heure de Montréal, convertis en UTC. */
    private fun event(
        id: Int,
        start: String,
        stop: String,
        allday: Boolean = false,
    ): AgendaEvent {
        fun utc(local: String): String {
            val (d, t) = local.split(" ")
            val parts = t.split(":")
            val zoned = LocalDate.parse(d)
                .atTime(parts[0].toInt(), parts[1].toInt())
                .atZone(zone)
            return zoned.toInstant().toString().replace(".000Z", "Z")
        }
        return AgendaEvent(
            id = id, key = "odoo:$id", name = "E$id",
            start = utc(start), stop = utc(stop), allday = allday,
        )
    }

    @Test
    fun `une rencontre simple occupe toute la largeur`() {
        val segs = segmentsFor(day, listOf(event(1, "2026-09-08 09:00", "2026-09-08 10:00")), zone)
        assertEquals(1, segs.size)
        assertEquals(1, segs[0].columnCount)
        assertEquals(540, segs[0].startMinutes)
        assertEquals(60, segs[0].durationMinutes)
    }

    @Test
    fun `deux rencontres qui se chevauchent partagent la largeur`() {
        val segs = segmentsFor(
            day,
            listOf(
                event(1, "2026-09-08 09:00", "2026-09-08 10:30"),
                event(2, "2026-09-08 10:00", "2026-09-08 11:00"),
            ),
            zone,
        )
        assertEquals(2, segs.size)
        assertTrue(segs.all { it.columnCount == 2 })
        assertEquals(setOf(0, 1), segs.map { it.columnIndex }.toSet())
    }

    @Test
    fun `le partage se calcule par grappe, pas sur la journee`() {
        // Deux le matin, trois le soir. Compter globalement réduirait celles du
        // matin au tiers de la largeur alors que rien ne les y oblige.
        val segs = segmentsFor(
            day,
            listOf(
                event(1, "2026-09-08 09:00", "2026-09-08 10:00"),
                event(2, "2026-09-08 11:00", "2026-09-08 12:00"),
                event(3, "2026-09-08 19:00", "2026-09-08 20:00"),
                event(4, "2026-09-08 19:15", "2026-09-08 20:15"),
                event(5, "2026-09-08 19:30", "2026-09-08 20:30"),
            ),
            zone,
        )
        val matin = segs.filter { it.event.id in listOf(1, 2) }
        val soir = segs.filter { it.event.id in listOf(3, 4, 5) }
        assertTrue("Le matin ne partage avec personne", matin.all { it.columnCount == 1 })
        assertEquals(3, soir.first().columnCount)
    }

    @Test
    fun `une rencontre a cheval sur minuit est coupee au jour`() {
        val segs = segmentsFor(
            day,
            listOf(event(1, "2026-09-08 22:00", "2026-09-09 01:00")),
            zone,
        )
        assertEquals(1, segs.size)
        assertEquals(1320, segs[0].startMinutes)
        assertEquals(120, segs[0].durationMinutes, )
        assertTrue(
            "Le segment déborde de la grille",
            segs[0].startMinutes + segs[0].durationMinutes <= 1440,
        )
    }

    @Test
    fun `le lendemain recoit le reste de la rencontre`() {
        val segs = segmentsFor(
            day.plusDays(1),
            listOf(event(1, "2026-09-08 22:00", "2026-09-09 01:00")),
            zone,
        )
        assertEquals(1, segs.size)
        assertEquals(0, segs[0].startMinutes)
        assertEquals(60, segs[0].durationMinutes)
    }

    @Test
    fun `une rencontre de cinq minutes garde une hauteur touchable`() {
        val segs = segmentsFor(
            day,
            listOf(event(1, "2026-09-08 09:00", "2026-09-08 09:05")),
            zone,
        )
        assertEquals(20, segs[0].durationMinutes)
    }

    @Test
    fun `une journee entiere ne va pas dans la grille`() {
        val segs = segmentsFor(
            day,
            listOf(event(1, "2026-09-08 00:00", "2026-09-09 00:00", allday = true)),
            zone,
        )
        assertTrue(segs.isEmpty())
    }

    @Test
    fun `un evenement d un autre jour est ignore`() {
        val segs = segmentsFor(
            day,
            listOf(event(1, "2026-09-10 09:00", "2026-09-10 10:00")),
            zone,
        )
        assertTrue(segs.isEmpty())
    }
}
