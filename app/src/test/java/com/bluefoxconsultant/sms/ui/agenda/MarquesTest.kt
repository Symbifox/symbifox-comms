package com.bluefoxconsultant.sms.ui.agenda

import com.bluefoxconsultant.sms.data.AgendaEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les marques que la grille et la liste lisent toutes les deux. */
class MarquesTest {

    @Test
    fun `une rencontre sans rien n'a aucune marque`() {
        assertTrue(marques(AgendaEvent(id = 1)).vide)
    }

    @Test
    fun `confirmee est MA presence, pas celle des autres`() {
        assertTrue(marques(AgendaEvent(myState = "accepted")).confirmee)
        assertFalse(marques(AgendaEvent(myState = "tentative", attendees = 5)).confirmee)
        assertFalse(marques(AgendaEvent(myState = "needsAction")).confirmee)
    }

    @Test
    fun `l'OdJ et le CR suivent l'etat du document, pas sa presence`() {
        assertTrue(marques(AgendaEvent(agendaState = "draft")).odj)
        assertTrue(marques(AgendaEvent(agendaState = "sent")).odj)
        assertFalse(marques(AgendaEvent(agendaState = "none")).odj)
        assertFalse(marques(AgendaEvent(agendaState = "")).odj)
        assertTrue(marques(AgendaEvent(minutesState = "done")).cr)
        assertFalse(marques(AgendaEvent(minutesState = "none")).cr)
    }

    @Test
    fun `sans OdJ ne s'affiche que s'il n'y a vraiment pas d'OdJ`() {
        // Dispensée ET pourvue d'un OdJ : c'est l'OdJ qui compte, pas la
        // dispense — afficher les deux se contredirait.
        assertFalse(marques(AgendaEvent(skipAgenda = true, agendaState = "draft")).sansOdj)
        assertTrue(marques(AgendaEvent(skipAgenda = true)).sansOdj)
    }

    @Test
    fun `reportee suit le rappel reporte`() {
        assertTrue(marques(AgendaEvent(snoozedUntil = "2026-09-08T14:00:00Z")).reportee)
        assertFalse(marques(AgendaEvent(snoozedUntil = null)).reportee)
    }

    @Test
    fun `vide ignore le C quand on retire la confirmation`() {
        val m = marques(AgendaEvent(myState = "accepted"))
        assertFalse(m.vide)
        assertTrue(m.copy(confirmee = false).vide)
        assertEquals(Marques(confirmee = true), m)
    }
}
