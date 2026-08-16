package com.bluefoxconsultant.sms.ui.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class AppendSpokenTest {

    @Test
    fun `empty field takes the transcription as is`() {
        assertEquals("bonjour", appendSpoken("", "bonjour"))
        assertEquals("bonjour", appendSpoken("   ", "  bonjour  "))
    }

    @Test
    fun `dictating twice adds a sentence rather than replacing one`() {
        assertEquals("bonjour ça va", appendSpoken("bonjour", "ça va"))
    }

    @Test
    fun `existing trailing space is not doubled`() {
        assertEquals("bonjour ça va", appendSpoken("bonjour ", "ça va"))
        assertEquals("bonjour\nça va", appendSpoken("bonjour\n", "ça va"))
    }

    @Test
    fun `nothing heard leaves the field untouched`() {
        assertEquals("bonjour", appendSpoken("bonjour", ""))
        assertEquals("bonjour", appendSpoken("bonjour", "   "))
    }
}
