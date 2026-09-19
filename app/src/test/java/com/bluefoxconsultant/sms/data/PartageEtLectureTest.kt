package com.bluefoxconsultant.sms.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Le partage entrant (C-M2) et la lecture bornée des pièces jointes (Q-M4,
 * C-F4). Le filtre prend le schéma et l'autorité en chaînes : `android.net.Uri`
 * n'existe pas hors d'un appareil.
 */
class PartageEtLectureTest {

    private val paquet = "com.bluefoxconsultant.sms"
    private val autorites = setOf("com.bluefoxconsultant.sms.attachments", "fournisseur.bizarre.de.l.app")

    private fun accepte(scheme: String?, authority: String?) =
        ShareIntake.uriAcceptable(scheme, authority, autorites, paquet)

    @Test
    fun `une photo de la galerie passe`() {
        assertTrue(accepte("content", "media"))
        assertTrue(accepte("content", "com.android.providers.media.documents"))
        assertTrue(accepte("content", "com.google.android.apps.photos.contentprovider"))
    }

    @Test
    fun `un fichier local est refuse`() {
        assertFalse(accepte("file", ""))
        assertFalse(accepte("file", null))
        assertFalse(accepte(null, null))
        assertFalse(accepte("http", "exemple.com"))
    }

    @Test
    fun `le fournisseur de l'app elle-meme est refuse`() {
        assertFalse(accepte("content", "com.bluefoxconsultant.sms.attachments"))
        assertFalse(accepte("content", "com.bluefoxconsultant.sms.androidx-startup"))
        assertFalse(accepte("content", "com.bluefoxconsultant.sms"))
        assertFalse(accepte("content", "fournisseur.bizarre.de.l.app"))
    }

    @Test
    fun `le prefixe d'usager ne deguise pas le fournisseur de l'app`() {
        assertFalse(accepte("content", "0@com.bluefoxconsultant.sms.attachments"))
        assertFalse(accepte("content", "10@com.bluefoxconsultant.sms.attachments"))
        // Un autre usager, un autre fournisseur : rien à reprocher.
        assertTrue(accepte("content", "10@media"))
    }

    @Test
    fun `la casse ne deguise pas non plus`() {
        assertFalse(accepte("content", "COM.BlueFoxConsultant.SMS.attachments"))
        // Le schéma, lui, doit être exact : le résolveur d'Android n'ouvrirait
        // pas un « CONTENT:// » de toute façon.
        assertFalse(accepte("CONTENT", "media"))
    }

    @Test
    fun `une autorite qui ressemble sans etre un sous-nom passe`() {
        assertTrue(accepte("content", "com.bluefoxconsultant.smsrelay.files"))
    }

    @Test
    fun `une autorite vide est refusee`() {
        assertFalse(accepte("content", ""))
        assertFalse(accepte("content", "12@"))
    }

    // ── Lecture bornée ───────────────────────────────────────────────

    /** Un flux qui compte ce qu'on lui a pris, pour prouver qu'on s'arrête tôt. */
    private class FluxCompte(private val taille: Long) : InputStream() {
        var lus = 0L
        override fun read(): Int = if (lus >= taille) -1 else { lus++; 0x41 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (lus >= taille) return -1
            val n = minOf(len.toLong(), taille - lus).toInt()
            b.fill(0x41, off, off + n)
            lus += n
            return n
        }
    }

    @Test
    fun `sous le plafond, tout est lu tel quel`() {
        val octets = ByteArray(200_000) { (it % 251).toByte() }
        val lu = LectureBornee.lire(ByteArrayInputStream(octets), 1_000_000, octets.size.toLong())
        assertArrayEquals(octets, lu)
        val sansAnnonce = LectureBornee.lire(ByteArrayInputStream(octets), 1_000_000, null)
        assertArrayEquals(octets, sansAnnonce)
    }

    @Test
    fun `pile au plafond passe, un octet de plus non`() {
        val plafond = 150_000L
        assertEquals(
            plafond.toInt(),
            LectureBornee.lire(FluxCompte(plafond), plafond, null).size,
        )
        try {
            LectureBornee.lire(FluxCompte(plafond + 1), plafond, null)
            fail("un octet au-delà du plafond doit être refusé")
        } catch (e: LectureBornee.TropGros) {
            assertEquals(plafond, e.plafond)
        }
    }

    @Test
    fun `une taille annoncee trop grande refuse sans rien lire`() {
        val flux = FluxCompte(10)
        try {
            LectureBornee.lire(flux, 1_000, tailleAnnoncee = 2_000_000_000L)
            fail("refus attendu")
        } catch (e: LectureBornee.TropGros) {
            assertEquals(0L, flux.lus)
        }
    }

    @Test
    fun `une annonce mensongere n'emporte pas la lecture au-dela du plafond`() {
        // Annonce 10 octets, en sert dix milliards : on s'arrête au premier
        // bloc qui franchit le plafond, pas à la fin du flux.
        val flux = FluxCompte(10_000_000_000L)
        try {
            LectureBornee.lire(flux, 1_000_000, tailleAnnoncee = 10)
            fail("refus attendu")
        } catch (e: LectureBornee.TropGros) {
            assertTrue("lu ${flux.lus} octets", flux.lus <= 1_000_000 + 64 * 1024)
        }
    }

    @Test
    fun `un flux vide rend un tableau vide`() {
        assertEquals(0, LectureBornee.lire(ByteArrayInputStream(ByteArray(0)), 10, 0).size)
    }
}
