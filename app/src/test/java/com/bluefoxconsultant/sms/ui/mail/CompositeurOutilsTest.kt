package com.bluefoxconsultant.sms.ui.mail

import com.bluefoxconsultant.sms.data.Adresses
import com.bluefoxconsultant.sms.data.MailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Adresses en pastille, destinataires sans doublon, créneaux d'envoi (#25764). */
class CompositeurOutilsTest {

    private val montreal = ZoneId.of("America/Montreal")

    // ------------------------------------------------------------ adresses

    @Test
    fun `un nom accentue avec une virgule voyage sans encodage`() {
        val pastille = MailAddress("Doe, Jane", "jane@client.test").enPastille
        assertEquals("\"Doe, Jane\" <jane@client.test>", pastille)
        assertFalse("pas de RFC 2047", pastille.contains("=?"))
        assertEquals("Doe, Jane" to "jane@client.test", Adresses.decouper(pastille))
        assertEquals("Doe, Jane", Adresses.libelle(pastille))
    }

    @Test
    fun `un guillemet dans le nom est echappe puis relu`() {
        val pastille = Adresses.formater("Jean \"JP\" Proulx", "jp@x.test")
        assertEquals("Jean \"JP\" Proulx" to "jp@x.test", Adresses.decouper(pastille))
    }

    @Test
    fun `une adresse seule reste une adresse seule`() {
        assertEquals("a@b.c", MailAddress("", "a@b.c").enPastille)
        assertEquals("a@b.c", MailAddress("a@b.c", "a@b.c").enPastille)
        assertEquals("" to "a@b.c", Adresses.decouper("a@b.c"))
        assertEquals("a@b.c", Adresses.libelle(" a@b.c "))
    }

    // ----------------------------------------------------- destinataires

    @Test
    fun `une adresse deja presente ailleurs n est pas ajoutee`() {
        val a = listOf("\"Alice\" <alice@x.test>")
        val cc = Destinataires.ajouter(emptyList(), listOf("ALICE@x.test", "bob@x.test"), a)
        assertEquals(listOf("bob@x.test"), cc)
        assertEquals(a, Destinataires.ajouter(a, listOf("alice@x.test")))
    }

    @Test
    fun `la saisie libre se decoupe`() {
        assertEquals(listOf("a@b.c", "d@e.f", "g@h.i"), Destinataires.saisie(" a@b.c, d@e.f;\ng@h.i ,"))
    }

    // ------------------------------------------------------------ créneaux

    @Test
    fun `un mardi matin propose plus tard demain matin et demain apres-midi`() {
        val mardi = ZonedDateTime.of(2026, 9, 15, 9, 20, 0, 0, montreal)
        val c = CreneauxEnvoi.proposer(mardi)
        assertEquals(
            listOf(CreneauxEnvoi.Cle.PLUS_TARD, CreneauxEnvoi.Cle.DEMAIN_MATIN, CreneauxEnvoi.Cle.DEMAIN_APRES_MIDI),
            c.map { it.cle },
        )
        assertEquals(13, c[0].quand.hour)
        assertEquals(16, c[1].quand.dayOfMonth)
        assertEquals(8, c[1].quand.hour)
        assertTrue(c.all { CreneauxEnvoi.acceptable(it.quand, mardi) })
    }

    @Test
    fun `un soir ne propose plus plus tard aujourd hui`() {
        val soir = ZonedDateTime.of(2026, 9, 15, 18, 5, 0, 0, montreal)
        assertFalse(CreneauxEnvoi.proposer(soir).any { it.cle == CreneauxEnvoi.Cle.PLUS_TARD })
    }

    @Test
    fun `un vendredi propose lundi matin`() {
        val vendredi = ZonedDateTime.of(2026, 9, 18, 15, 0, 0, 0, montreal)
        val lundi = CreneauxEnvoi.proposer(vendredi).single { it.cle == CreneauxEnvoi.Cle.LUNDI_MATIN }
        assertEquals(21, lundi.quand.dayOfMonth)
        assertEquals(8, lundi.quand.hour)
    }

    @Test
    fun `une heure trop proche ou trop lointaine est refusee`() {
        val maintenant = ZonedDateTime.of(2026, 9, 15, 9, 0, 0, 0, montreal)
        assertFalse(CreneauxEnvoi.acceptable(maintenant.plusMinutes(1), maintenant))
        assertFalse(CreneauxEnvoi.acceptable(maintenant.minusHours(1), maintenant))
        assertFalse(CreneauxEnvoi.acceptable(maintenant.plusDays(400), maintenant))
        assertTrue(CreneauxEnvoi.acceptable(maintenant.plusMinutes(5), maintenant))
    }

    @Test
    fun `le changement d heure ne decale pas demain matin`() {
        // 2026-11-01 : passage à l'heure normale à Montréal.
        val samedi = ZonedDateTime.of(2026, 10, 31, 20, 0, 0, 0, montreal)
        val demain = CreneauxEnvoi.proposer(samedi).single { it.cle == CreneauxEnvoi.Cle.DEMAIN_MATIN }
        assertEquals(8, demain.quand.hour)
        assertEquals(1, demain.quand.dayOfMonth)
    }
}
