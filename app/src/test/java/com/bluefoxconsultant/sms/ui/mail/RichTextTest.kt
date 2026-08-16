package com.bluefoxconsultant.sms.ui.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Conversion markdown-lite → HTML. Le point sensible n'est pas le gras : c'est
 * que le texte tapé reste du texte, y compris quand il ressemble à du balisage.
 */
class RichTextTest {

    @Test
    fun `markup typed by hand stays text`() {
        val html = RichText.toHtml("Dis <b>bonjour</b> & au revoir")
        assertTrue(html.contains("&lt;b&gt;"))
        assertTrue(html.contains("&amp;"))
        assertFalse(html.contains("<b>"))
    }

    @Test
    fun `bold and italic become tags`() {
        assertTrue(RichText.toHtml("un **point** important").contains("<strong>point</strong>"))
        assertTrue(RichText.toHtml("un *mot* nuance").contains("<em>mot</em>"))
    }

    @Test
    fun `italic does not swallow bold`() {
        val html = RichText.toHtml("**gras** et *penche*")
        assertTrue(html.contains("<strong>gras</strong>"))
        assertTrue(html.contains("<em>penche</em>"))
    }

    @Test
    fun `bullets become a single list`() {
        val html = RichText.toHtml("Points :\n- un\n- deux\nFin")
        assertEquals(1, Regex("<ul>").findAll(html).count())
        assertEquals(2, Regex("<li>").findAll(html).count())
        assertTrue(html.contains("Fin"))
    }

    @Test
    fun `plain text reports no formatting`() {
        assertFalse(RichText.hasFormatting("Bonjour, ci-joint le rapport."))
        assertTrue(RichText.hasFormatting("Bonjour **Marie**"))
        assertTrue(RichText.hasFormatting("- un\n- deux"))
    }

    @Test
    fun `wrapping an empty selection puts the caret between the markers`() {
        val (text, caret) = RichText.applyMarker("abc", 1, 1, "**")
        assertEquals("a****bc", text)
        assertEquals(3, caret)
    }

    @Test
    fun `wrapping a selection surrounds it`() {
        val (text, _) = RichText.applyMarker("bonjour tout le monde", 0, 7, "**")
        assertTrue(text.startsWith("**bonjour**"))
    }

    @Test
    fun `a line prefix toggles`() {
        val (added, _) = RichText.applyLinePrefix("un\ndeux", 4, "- ")
        assertTrue(added.contains("- deux"))
        val (removed, _) = RichText.applyLinePrefix(added, 6, "- ")
        assertFalse(removed.contains("- deux"))
    }

    @Test
    fun `out of range offsets do not crash`() {
        RichText.applyMarker("abc", -5, 99, "**")
        RichText.applyLinePrefix("abc", 99, "- ")
    }
}
