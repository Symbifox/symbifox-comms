package com.bluefoxconsultant.sms.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Les deux décisions du battement, éprouvées hors de Compose.
 *
 * Elles sont sorties des modèles de vue exprès : c'est le seul moyen de faire
 * passer minuit et une heure d'attente dans un banc qui dure trois
 * millisecondes.
 */
class RafraichissementTest {

    private val zone: ZoneId = ZoneId.of("America/Toronto")

    @Test
    fun `jamais lu vaut relecture`() {
        assertTrue(relectureUtile(maintenant = 1_000L, derniereLecture = 0L))
    }

    @Test
    fun `lecture fraiche ne relit pas`() {
        assertFalse(relectureUtile(maintenant = 100_000L, derniereLecture = 70_000L))
    }

    @Test
    fun `lecture d'une minute relit`() {
        assertTrue(relectureUtile(maintenant = 100_000L, derniereLecture = 40_000L))
    }

    /**
     * Le cas de la grille laissée ouverte : l'écran a passé la nuit sur la
     * date d'hier, et personne n'avait touché aux flèches.
     */
    @Test
    fun `minuit deplace une ancre qui suivait aujourd'hui`() {
        val hier = LocalDate.of(2026, 9, 3)
        val aujourdhui = LocalDate.of(2026, 9, 4)
        assertEquals(aujourdhui, ancreARattraper(hier, aujourdhui, suitAujourdhui = true))
    }

    /**
     * 🔴 Le contre-cas, celui qui rendrait le correctif odieux : quelqu'un est
     * parti regarder la semaine prochaine. Le ramener de force au jour courant
     * à chaque minute rendrait l'agenda inutilisable.
     */
    @Test
    fun `une ancre feuilletee reste ou elle est`() {
        val ailleurs = LocalDate.of(2026, 9, 21)
        val aujourdhui = LocalDate.of(2026, 9, 4)
        assertNull(ancreARattraper(ailleurs, aujourdhui, suitAujourdhui = false))
    }

    @Test
    fun `rien a rattraper quand l'ancre est deja aujourd'hui`() {
        val aujourdhui = LocalDate.of(2026, 9, 4)
        assertNull(ancreARattraper(aujourdhui, aujourdhui, suitAujourdhui = true))
    }

    @Test
    fun `l'heure de lecture est celle de l'appareil`() {
        // 2026-09-03 16:42:00 à Montréal, donné en millisecondes UTC.
        val millis = LocalDate.of(2026, 9, 3).atTime(16, 42)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals("16:42", heureDeLecture(millis, zone))
    }
}
