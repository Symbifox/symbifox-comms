package com.bluefoxconsultant.sms.ui.agenda

import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.data.AgendaAlarm
import com.bluefoxconsultant.sms.data.AgendaEvent
import com.bluefoxconsultant.sms.ui.uiPlural
import com.bluefoxconsultant.sms.ui.uiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Le défaut réparé : les gestes « reporter » et « vu » offerts à une rencontre
 * dont le rappel n'a pas encore sonné. Chaque cas est une valeur qui ferait
 * passer une règle naïve.
 */
class RappelTest {

    private val maintenant: Instant = Instant.parse("2026-09-08T13:00:00Z")

    private fun event(
        start: String,
        stop: String,
        alarms: List<AgendaAlarm> = emptyList(),
        fired: Boolean = false,
    ) = AgendaEvent(id = 1, start = start, stop = stop, alarms = alarms, reminderFired = fired)

    @Test
    fun `une instance ancienne garde les gestes d'avant`() {
        val e = event("2026-09-10T13:00:00Z", "2026-09-10T14:00:00Z")
        assertEquals(EtatRappel.Ancien, etatRappel(e, api = 2, maintenant = maintenant))
    }

    @Test
    fun `sans rappel, rien a reporter`() {
        val e = event("2026-09-10T13:00:00Z", "2026-09-10T14:00:00Z")
        assertEquals(EtatRappel.Aucun, etatRappel(e, api = 3, maintenant = maintenant))
    }

    @Test
    fun `un rappel a venir se montre sans les gestes`() {
        val a = AgendaAlarm(minutes = 15, notifyAt = "2026-09-10T12:45:00Z")
        val e = event("2026-09-10T13:00:00Z", "2026-09-10T14:00:00Z", listOf(a))
        val etat = etatRappel(e, api = 3, maintenant = maintenant)
        assertTrue(etat is EtatRappel.AVenir)
        assertEquals(listOf(a), (etat as EtatRappel.AVenir).alarms)
    }

    @Test
    fun `le verdict du serveur suffit a dire sonne`() {
        val a = AgendaAlarm(minutes = 15, notifyAt = "2026-09-08T12:50:00Z")
        val e = event("2026-09-08T13:05:00Z", "2026-09-08T14:00:00Z", listOf(a), fired = true)
        assertTrue(etatRappel(e, api = 3, maintenant = maintenant) is EtatRappel.Sonne)
    }

    @Test
    fun `l'heure du rappel passee depuis la lecture vaut sonne, meme si le serveur disait non`() {
        // La fiche a été lue à 12:40, le rappel sonnait à 12:50, il est 13:00.
        val a = AgendaAlarm(minutes = 15, notifyAt = "2026-09-08T12:50:00Z")
        val e = event("2026-09-08T13:05:00Z", "2026-09-08T14:00:00Z", listOf(a), fired = false)
        assertTrue(etatRappel(e, api = 3, maintenant = maintenant) is EtatRappel.Sonne)
    }

    @Test
    fun `une rencontre finie n'a plus rien a reporter`() {
        val a = AgendaAlarm(minutes = 15, notifyAt = "2026-09-08T09:45:00Z")
        val e = event("2026-09-08T10:00:00Z", "2026-09-08T11:00:00Z", listOf(a), fired = true)
        assertTrue(etatRappel(e, api = 3, maintenant = maintenant) is EtatRappel.Passe)
    }

    @Test
    fun `une rencontre en cours dont le rappel a sonne garde les gestes`() {
        val a = AgendaAlarm(minutes = 15, notifyAt = "2026-09-08T12:15:00Z")
        val e = event("2026-09-08T12:30:00Z", "2026-09-08T13:30:00Z", listOf(a), fired = true)
        assertTrue(etatRappel(e, api = 3, maintenant = maintenant) is EtatRappel.Sonne)
    }

    /**
     * Le découpage en minutes, heures et jours ; la phrase elle-même vit dans
     * `strings.xml` (« 15 min avant » en français, « 15 min before » en anglais).
     */
    @Test
    fun `les delais se decoupent en minutes, heures et jours`() {
        assertEquals(uiText(R.string.meeting_reminder_at_start), libelleDelai(0))
        assertEquals(uiPlural(R.plurals.meeting_reminder_minutes_before, 15), libelleDelai(15))
        assertEquals(uiPlural(R.plurals.meeting_reminder_hours_before, 1), libelleDelai(60))
        assertEquals(uiText(R.string.meeting_reminder_hours_minutes_before, 1, 30), libelleDelai(90))
        assertEquals(uiPlural(R.plurals.meeting_reminder_days_before, 2), libelleDelai(2880))
    }
}
