package com.bluefoxconsultant.sms.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.URLDecoder

/**
 * Navigation décode les arguments de chemin comme `Uri.decode` : les « %xx »
 * seulement, jamais « + » en espace. On rejoue ce décodage-là.
 */
class RouteArgsTest {

    private fun commeNavigation(encode: String): String =
        URLDecoder.decode(encode.replace("+", "%2B"), "UTF-8")

    @Test
    fun `un Message-ID Gmail avec un plus survit a la route`() {
        val cle = "<CA+b=c/d%f@mail.gmail.com>"
        assertEquals(cle, commeNavigation(argumentDeRoute(cle)))
    }

    @Test
    fun `une espace ne devient pas un plus`() {
        val valeur = "deux mots"
        val enc = argumentDeRoute(valeur)
        assertFalse(enc.contains("+"))
        assertEquals(valeur, commeNavigation(enc))
    }

    @Test
    fun `une barre oblique ne casse pas la route`() {
        assertFalse(argumentDeRoute("a/b").contains("/"))
    }
}
