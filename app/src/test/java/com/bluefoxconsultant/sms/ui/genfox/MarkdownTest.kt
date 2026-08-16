package com.bluefoxconsultant.sms.ui.genfox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `bold markers are consumed, not shown`() {
        val rendered = renderMarkdown("**23 tâches** ouvertes")
        assertEquals("23 tâches ouvertes", rendered.text)
        assertTrue(rendered.spanStyles.isNotEmpty())
    }

    @Test
    fun `a half-written marker survives the stream`() {
        // Mid-answer the closing ** has not arrived yet: the text must stay
        // readable instead of the rest of the line vanishing into a style.
        assertEquals("**23 tâches ouv", renderMarkdown("**23 tâches ouv").text)
        assertEquals("un *début", renderMarkdown("un *début").text)
    }

    @Test
    fun `bullets become real bullets`() {
        assertEquals("• premier\n• second", renderMarkdown("- premier\n* second").text)
    }

    @Test
    fun `numbered lists keep their numbers`() {
        assertEquals("1. premier\n2. second", renderMarkdown("1. premier\n2. second").text)
    }

    @Test
    fun `headings lose their hashes and keep their words`() {
        assertEquals("Résumé", renderMarkdown("## Résumé").text)
    }

    @Test
    fun `inline code keeps its content`() {
        assertEquals("appelle odoo_get_task ici", renderMarkdown("appelle `odoo_get_task` ici").text)
    }

    @Test
    fun `plain text passes through untouched`() {
        val plain = "Trois tâches t'attendent aujourd'hui, dont deux en retard."
        assertEquals(plain, renderMarkdown(plain).text)
    }

    @Test
    fun `an empty answer renders empty`() {
        assertEquals("", renderMarkdown("").text)
    }
}
