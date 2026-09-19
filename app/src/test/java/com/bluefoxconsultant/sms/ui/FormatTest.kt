package com.bluefoxconsultant.sms.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun `une cle dont le hachage vaut Int MIN_VALUE a quand meme une couleur`() {
        // Le hachage de cette chaîne est exactement Int.MIN_VALUE.
        val cle = "polygenelubricants"
        assertEquals(Int.MIN_VALUE, cle.hashCode())
        avatarColor(cle) // ne doit pas lever
    }
}
