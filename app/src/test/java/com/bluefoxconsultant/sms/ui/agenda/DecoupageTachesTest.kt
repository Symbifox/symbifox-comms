package com.bluefoxconsultant.sms.ui.agenda

import com.bluefoxconsultant.sms.data.AgendaTask
import com.bluefoxconsultant.sms.data.AgendaTaskCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Le découpage des tâches par jour local (#25717), la pastille qui compte
 * pareil, et les compteurs de la grille qui refusent le mauvais fuseau (Q-M7).
 */
class DecoupageTachesTest {

    private val montreal: ZoneId = ZoneId.of("America/Toronto")
    private val aujourdhui: LocalDate = LocalDate.of(2026, 9, 14) // un lundi

    /** Une tâche due à [heure] le jour [jour] de Montréal, rendue en UTC comme le serveur. */
    private fun tache(
        id: Int,
        jour: LocalDate,
        heure: String,
        priorite: String = "0",
        faite: Boolean = false,
    ): AgendaTask {
        val (h, m) = heure.split(":").map { it.toInt() }
        val instant = ZonedDateTime.of(jour.atTime(h, m), montreal).toInstant()
        return AgendaTask(id = id, name = "t$id", deadline = instant.toString(),
            priority = priorite, done = faite)
    }

    /**
     * Les intertitres comme l'écran français les écrit. Le découpage ne porte
     * plus de mots (Q-M8) : le banc les remet, pour que chaque attente se lise
     * encore comme la liste qu'on voit.
     */
    private val jourFr = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.forLanguageTag("fr-CA"))

    private fun titres(sections: List<SectionTaches>) = sections.map {
        when (val t = it.intertitre) {
            Intertitre.EnRetard -> "En retard"
            Intertitre.Aujourdhui -> "Aujourd'hui"
            is Intertitre.Jour -> titreDuJour(t.date, jourFr)
            Intertitre.PlusTard -> "Plus tard"
        }
    }
    private fun ids(section: SectionTaches) = section.taches.map { it.id }

    @Test
    fun `retard, aujourd'hui, un intertitre par jour, plus tard`() {
        val sections = decouperTaches(
            retards = listOf(tache(1, aujourdhui.minusDays(3), "10:00")),
            fenetre = listOf(
                tache(2, aujourdhui, "18:00"),
                tache(3, aujourdhui.plusDays(1), "09:00"),
                tache(4, aujourdhui.plusDays(7), "23:59"),
                tache(5, aujourdhui.plusDays(8), "00:00"),
            ),
            zone = montreal,
            aujourdhui = aujourdhui,
        )
        assertEquals(
            listOf("En retard", "Aujourd'hui", "Mardi 15 septembre", "Lundi 21 septembre", "Plus tard"),
            titres(sections),
        )
        assertTrue(sections[0].alerte)
        assertFalse(sections[2].majuscules)
        assertEquals(listOf(5), ids(sections.last()))
    }

    @Test
    fun `une tache de ce matin rendue en retard par le serveur est d'aujourd'hui`() {
        val sections = decouperTaches(
            retards = listOf(tache(1, aujourdhui, "09:00"), tache(2, aujourdhui.minusDays(1), "23:59")),
            fenetre = emptyList(),
            zone = montreal,
            aujourdhui = aujourdhui,
        )
        assertEquals(listOf("En retard", "Aujourd'hui"), titres(sections))
        assertEquals(listOf(2), ids(sections[0]))
        assertEquals(listOf(1), ids(sections[1]))
    }

    @Test
    fun `minuit est la borne, dans le fuseau de l'appareil`() {
        val sections = decouperTaches(
            retards = emptyList(),
            fenetre = listOf(tache(1, aujourdhui, "23:59"), tache(2, aujourdhui.plusDays(1), "00:00")),
            zone = montreal,
            aujourdhui = aujourdhui,
        )
        assertEquals(listOf("Aujourd'hui", "Mardi 15 septembre"), titres(sections))
    }

    @Test
    fun `le fuseau decide du jour, pas l'UTC`() {
        // 21:30 à Montréal = 01:30 UTC le lendemain.
        val soir = tache(1, aujourdhui, "21:30")
        val montrealSections = decouperTaches(emptyList(), listOf(soir), montreal, aujourdhui)
        assertEquals(listOf("Aujourd'hui"), titres(montrealSections))
        val auckland = ZoneId.of("Pacific/Auckland")
        val aucklandSections = decouperTaches(emptyList(), listOf(soir), auckland, aujourdhui)
        assertEquals(listOf("Mardi 15 septembre"), titres(aucklandSections))
    }

    @Test
    fun `les jours sans tache n'ont pas d'intertitre`() {
        val sections = decouperTaches(
            emptyList(),
            listOf(tache(1, aujourdhui.plusDays(2), "10:00"), tache(2, aujourdhui.plusDays(5), "10:00")),
            montreal, aujourdhui,
        )
        assertEquals(listOf("Mercredi 16 septembre", "Samedi 19 septembre"), titres(sections))
    }

    @Test
    fun `tri stable par echeance puis priorite`() {
        val sections = decouperTaches(
            emptyList(),
            listOf(
                tache(1, aujourdhui, "15:00"),
                tache(2, aujourdhui, "10:00"),
                tache(3, aujourdhui, "15:00", priorite = "1"),
                tache(4, aujourdhui, "15:00"),
            ),
            montreal, aujourdhui,
        )
        assertEquals(listOf(2, 3, 1, 4), ids(sections.single()))
    }

    @Test
    fun `une tache presente dans les deux seaux n'apparait qu'une fois`() {
        val t = tache(7, aujourdhui, "12:00")
        val sections = decouperTaches(listOf(t), listOf(t), montreal, aujourdhui)
        assertEquals(listOf(7), ids(sections.single()))
    }

    @Test
    fun `l'intertitre du jour suit la langue du formateur`() {
        val mardi = aujourdhui.plusDays(1)
        assertEquals("Mardi 15 septembre", titreDuJour(mardi, jourFr))
        val anglais = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)
        assertEquals("Tuesday, September 15", titreDuJour(mardi, anglais))
    }

    @Test
    fun `les sections portent leur genre et leur date, pas une phrase`() {
        val sections = decouperTaches(
            retards = listOf(tache(1, aujourdhui.minusDays(1), "10:00")),
            fenetre = listOf(tache(2, aujourdhui.plusDays(2), "10:00")),
            zone = montreal,
            aujourdhui = aujourdhui,
        )
        assertEquals(
            listOf(Intertitre.EnRetard, Intertitre.Jour(aujourdhui.plusDays(2))),
            sections.map { it.intertitre },
        )
        // Les clés restent celles d'avant : la liste paresseuse garde sa position.
        assertEquals(listOf("retard", "jour-2026-09-16"), sections.map { it.cle })
    }

    @Test
    fun `sans echeance ne se glisse dans aucune section datee`() {
        val sections = decouperTaches(emptyList(), listOf(AgendaTask(id = 9, deadline = null)), montreal, aujourdhui)
        assertTrue(sections.isEmpty())
    }

    @Test
    fun `la pastille compte les retards et aujourd'hui, pas demain ni le fait`() {
        val n = pastilleTaches(
            retards = listOf(
                tache(1, aujourdhui.minusDays(2), "10:00"),
                tache(2, aujourdhui, "08:00"),
                tache(3, aujourdhui.minusDays(1), "10:00", faite = true),
            ),
            fenetre = listOf(
                tache(4, aujourdhui, "23:59"),
                tache(5, aujourdhui.plusDays(1), "00:00"),
                tache(2, aujourdhui, "08:00"),
            ),
            zone = montreal,
            aujourdhui = aujourdhui,
        )
        assertEquals(3, n)
    }

    @Test
    fun `le compteur de la grille se pose quand le fuseau rendu est celui de l'appareil`() {
        val rendu = AgendaTaskCounts(ok = true, tz = "America/Toronto",
            counts = mapOf("2026-09-14" to 3, "2026-09-15" to 1, "pas-une-date" to 9))
        assertEquals(
            mapOf(LocalDate.of(2026, 9, 14) to 3, LocalDate.of(2026, 9, 15) to 1),
            compteursPourLaGrille(rendu, montreal),
        )
    }

    @Test
    fun `serveur ancien au fuseau du compte, aucune pastille plutot qu'une fausse`() {
        val ancien = AgendaTaskCounts(ok = true, tz = "Pacific/Auckland",
            counts = mapOf("2026-09-14" to 3))
        assertTrue(compteursPourLaGrille(ancien, montreal).isEmpty())
        val muet = AgendaTaskCounts(ok = true, tz = "", counts = mapOf("2026-09-14" to 3))
        assertTrue(compteursPourLaGrille(muet, montreal).isEmpty())
    }

    @Test
    fun `un alias aux regles identiques vaut le meme fuseau`() {
        assertTrue(memeFuseau("America/Toronto", ZoneId.of("America/Toronto")))
        assertTrue(memeFuseau(" UTC ", ZoneId.of("UTC")))
        assertFalse(memeFuseau("Europe/Paris", montreal))
        assertFalse(memeFuseau("n'importe/quoi", montreal))
    }
}
