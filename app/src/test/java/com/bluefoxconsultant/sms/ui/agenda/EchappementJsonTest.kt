package com.bluefoxconsultant.sms.ui.agenda

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Les titres de tâches d'Olivier portent des guillemets, des apostrophes et des
 * accents. Une concaténation naïve casserait la requête, et le serveur rendrait
 * une erreur illisible plutôt que le vrai problème.
 */
class EchappementJsonTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun relire(valeur: String): String =
        json.parseToJsonElement("""{"name":${jsonTexte(valeur)}}""")
            .jsonObject["name"]!!.jsonPrimitive.content

    @Test
    fun `un titre ordinaire traverse intact`() {
        assertEquals("Relancer François", relire("Relancer François"))
    }

    @Test
    fun `un guillemet ne casse pas la requête`() {
        val brut = """Appeler le "fournisseur" demain"""
        assertEquals(brut, relire(brut))
    }

    @Test
    fun `une barre oblique inversée survit`() {
        val brut = """Chemin C:\\temp et suite"""
        assertEquals(brut, relire(brut))
    }

    @Test
    fun `un saut de ligne ne coupe pas le JSON`() {
        val brut = "Première ligne\nseconde ligne"
        assertEquals(brut, relire(brut))
    }

    @Test
    fun `une tabulation et un caractère de contrôle passent`() {
        val brut = "avant\tapres\u0007fin"
        assertEquals(brut, relire(brut))
    }

    @Test
    fun `l horodatage part en UTC, jamais en heure locale`() {
        // 2026-11-07 22:00 UTC, quelle que soit l'heure de l'appareil.
        val instant = Instant.parse("2026-11-07T22:00:00Z")
        assertEquals("2026-11-07 22:00:00", horodatage(instant))
    }
}
