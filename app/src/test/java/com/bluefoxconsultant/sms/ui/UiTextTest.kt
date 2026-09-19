package com.bluefoxconsultant.sms.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La résolution d'un [UiText], contre une table en mémoire : `Resources`
 * n'existe pas sur la JVM, et ce qu'on veut prouver ici est le câblage (quels
 * arguments partent vers quelle ressource), pas le moteur de formatage
 * d'Android.
 */
class UiTextTest {

    private val phrases = mapOf(
        1 to "Created: %1\$s",
        2 to "%1\$s unavailable: %2\$s.",
        3 to "Mail",
        4 to "no mailbox",
    )

    private val pluriels = mapOf(
        10 to mapOf("one" to "%1\$d file skipped", "other" to "%1\$d files skipped"),
        11 to mapOf("one" to "%1\$d of %2\$s", "other" to "%1\$d of %2\$s"),
    )

    /** Anglais : « one » pour 1 seulement, comme la règle d'Android. */
    private val source = object : SourceDeTextes {
        override fun texte(id: Int, args: Array<Any>): String =
            String.format(phrases.getValue(id), *args)

        override fun pluriel(id: Int, count: Int, args: Array<Any>): String {
            val forme = if (count == 1) "one" else "other"
            return String.format(pluriels.getValue(id).getValue(forme), *args)
        }
    }

    @Test
    fun `un texte du serveur passe tel quel, sans formatage`() {
        // Un « % » venu d'Odoo ne doit pas être lu comme un gabarit.
        assertEquals("Taux : 100 %", UiText.Raw("Taux : 100 %").resolve(source))
    }

    @Test
    fun `une ressource recoit ses arguments dans l'ordre`() {
        assertEquals("Created: Facture 42", uiText(1, "Facture 42").resolve(source))
    }

    @Test
    fun `un argument UiText se resout avant la phrase qui le porte`() {
        val partiel = uiText(2, uiText(3), uiText(4))
        assertEquals("Mail unavailable: no mailbox.", partiel.resolve(source))
    }

    @Test
    fun `un pluriel sans arguments passe le compte comme argument`() {
        assertEquals("1 file skipped", uiPlural(10, 1).resolve(source))
        assertEquals("3 files skipped", uiPlural(10, 3).resolve(source))
    }

    @Test
    fun `un pluriel avec arguments ne double pas le compte`() {
        assertEquals("2 of Inbox", uiPlural(11, 2, 2, UiText.Raw("Inbox")).resolve(source))
    }

    @Test
    fun `deux UiText identiques sont egaux, ce que les essais des ecrans comparent`() {
        assertEquals(uiText(1, "x"), UiText.Res(1, listOf("x")))
        assertEquals(uiPlural(10, 2), UiText.Plural(10, 2))
    }
}
