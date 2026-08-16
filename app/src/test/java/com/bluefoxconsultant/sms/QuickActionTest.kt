package com.bluefoxconsultant.sms

import com.bluefoxconsultant.sms.data.QuickAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le plafond des boutons de la barre : deux, et le plus ancien cède. */
class QuickActionTest {

    @Test
    fun `un troisieme choix evince le plus ancien, pas lui-meme`() {
        var picks = setOf<QuickAction>()
        picks = QuickAction.toggle(picks, QuickAction.ARCHIVE)
        picks = QuickAction.toggle(picks, QuickAction.SNOOZE)
        picks = QuickAction.toggle(picks, QuickAction.ROUTE)

        assertEquals(2, picks.size)
        assertTrue("le choix le plus récent doit rester", QuickAction.ROUTE in picks)
        assertTrue("le précédent doit rester", QuickAction.SNOOZE in picks)
        assertTrue("le plus ancien doit céder", QuickAction.ARCHIVE !in picks)
    }

    @Test
    fun `retirer un bouton deja choisi le retire vraiment`() {
        val picks = QuickAction.toggle(setOf(QuickAction.ARCHIVE), QuickAction.ARCHIVE)
        assertTrue(picks.isEmpty())
    }

    @Test
    fun `on ne depasse jamais le plafond, quoi qu on tape`() {
        var picks = setOf<QuickAction>()
        repeat(3) { QuickAction.entries.forEach { picks = QuickAction.toggle(picks, it) } }
        assertTrue(picks.size <= QuickAction.MAX_IN_BAR)
    }

    @Test
    fun `une cle inconnue en prefs ne fait pas tomber l app`() {
        assertEquals(setOf(QuickAction.ROUTE), QuickAction.from(setOf("route", "licorne")))
        assertTrue(QuickAction.from(setOf("licorne")).isEmpty())
    }

    @Test
    fun `jamais reglé donne le defaut, tout decoche reste vide`() {
        // La nuance compte : sans elle, décocher le dernier bouton ferait
        // revenir « Archiver » au prochain lancement, encore et encore.
        assertEquals(QuickAction.DEFAULTS, QuickAction.from(null))
        assertTrue(QuickAction.from(emptySet()).isEmpty())
    }
}
