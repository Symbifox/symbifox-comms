package com.bluefoxconsultant.sms.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread

/**
 * Le stockage des jetons échoue FERMÉ (C-M1), et ses écritures ne se
 * marchent plus dessus (Q-m2). Tout se joue hors de l'Android Keystore :
 * l'ouverture du magasin chiffré est une fonction qu'on fait réussir ou lever.
 */
class MagasinsTest {

    private fun plain(vararg paires: Pair<String, String>) = MemoirePrefs().apply {
        val edit = edit()
        paires.forEach { (k, v) -> edit.putString(k, v) }
        edit.commit()
    }

    @Test
    fun `magasin chiffre ouvert, le clair y est verse puis efface`() {
        val chiffre = MemoirePrefs()
        val clair = plain("token_sms" to "abc", "instance_url" to "https://x.example", "lines" to "[]")
        clair.edit().putStringSet("webpush_types_sms", mutableSetOf("sms")).commit()
        var efface = false

        val m = ouvrirMagasins({ chiffre }, clair) { efface = true }

        assertFalse(m.degrade)
        assertSame(chiffre, m.secrets)
        assertSame(chiffre, m.reglages)
        assertEquals("abc", chiffre.getString("token_sms", null))
        assertEquals("https://x.example", chiffre.getString("instance_url", null))
        assertEquals(setOf("sms"), chiffre.getStringSet("webpush_types_sms", null))
        assertTrue("le fichier en clair doit être supprimé", efface)
        assertTrue(clair.all.isEmpty())
    }

    @Test
    fun `le versement n'ecrase pas ce que le magasin chiffre porte deja`() {
        val chiffre = MemoirePrefs().apply { edit().putString("token_sms", "neuf").commit() }
        val clair = plain("token_sms" to "ancien", "token_mail" to "m")

        ouvrirMagasins({ chiffre }, clair) {}

        assertEquals("neuf", chiffre.getString("token_sms", null))
        assertEquals("m", chiffre.getString("token_mail", null))
    }

    @Test
    fun `rien en clair, rien a effacer`() {
        var efface = false
        ouvrirMagasins({ MemoirePrefs() }, MemoirePrefs()) { efface = true }
        assertFalse(efface)
    }

    @Test
    fun `keystore refuse, les secrets en clair sont effaces et pas utilises`() {
        val clair = plain(
            "token_sms" to "abc", "token_mail" to "def", "user_name_sms" to "Olivier",
            "pending_state" to "s", "pending_service" to "sms", "pending_verifier" to "v",
            "lines" to "[]", "token" to "pre-2.0", "cle_inconnue" to "?",
            "instance_url" to "https://x.example", "theme_mode" to "light",
            "available_services" to "sms,mail",
        )

        val m = ouvrirMagasins({ throw KeyStoreException("refus") }, clair) {}

        assertTrue(m.degrade)
        assertEquals(
            setOf("instance_url", "theme_mode", "available_services"),
            clair.all.keys,
        )
        val store = TokenStore(m)
        assertTrue(store.stockageDegrade)
        assertFalse(store.isSignedIn)
        assertNull(store.tokenFor(Service.SMS))
        assertNull(store.pendingVerifier)
        // Les réglages non secrets survivent : l'instance n'est pas à ressaisir.
        assertEquals("https://x.example", store.instanceUrl)
        assertEquals(ThemeMode.LIGHT, store.themeMode)
    }

    @Test
    fun `en stockage degrade, un jeton neuf ne touche jamais le disque`() {
        val clair = MemoirePrefs()
        val store = TokenStore(ouvrirMagasins({ throw SecurityException("refus") }, clair) {})

        store.saveToken(Service.MAIL, "jeton", "Olivier")
        store.savePendingLeg(Service.SMS, "etat", "verificateur")
        store.saveLines(emptyList())
        store.saveInstance("https://y.example")

        assertEquals("jeton", store.tokenFor(Service.MAIL))
        assertEquals("verificateur", store.pendingVerifier)
        assertEquals(setOf("instance_url"), clair.all.keys)
    }

    @Test
    fun `l'avis de stockage degrade ne se donne qu'une fois`() {
        val degrade = TokenStore(ouvrirMagasins({ throw KeyStoreException("x") }, MemoirePrefs()) {})
        assertTrue(degrade.prendreAvisDegrade())
        assertFalse(degrade.prendreAvisDegrade())
        val sain = TokenStore(ouvrirMagasins({ MemoirePrefs() }, MemoirePrefs()) {})
        assertFalse(sain.prendreAvisDegrade())
    }

    @Test
    fun `la migration pre-2 0 tient toujours`() {
        val chiffre = MemoirePrefs().apply {
            edit().putString("token", "vieux").putString("user_name", "Olivier").commit()
        }
        val store = TokenStore(ouvrirMagasins({ chiffre }, MemoirePrefs()) {})
        assertEquals("vieux", store.tokenFor(Service.SMS))
        assertEquals("Olivier", store.userNameFor(Service.SMS))
        assertFalse(chiffre.contains("token"))
    }

    @Test
    fun `deux 401 simultanes effacent bien les deux sessions`() {
        repeat(300) {
            val store = TokenStore(ouvrirMagasins({ MemoirePrefs() }, MemoirePrefs()) {})
            store.saveToken(Service.SMS, "s", null)
            store.saveToken(Service.MAIL, "m", null)
            val depart = CyclicBarrier(2)
            val a = thread { depart.await(); store.clearToken(Service.SMS, siJeton = "s") }
            val b = thread { depart.await(); store.clearToken(Service.MAIL, siJeton = "m") }
            a.join()
            b.join()
            assertTrue(store.tokensFlow.value.isEmpty())
        }
    }

    @Test
    fun `un 401 revenu avec l'ancien jeton n'efface pas la session neuve`() {
        val store = TokenStore(ouvrirMagasins({ MemoirePrefs() }, MemoirePrefs()) {})
        store.saveToken(Service.SMS, "neuf", null)
        assertFalse(store.clearToken(Service.SMS, siJeton = "ancien"))
        assertEquals("neuf", store.tokenFor(Service.SMS))
        assertTrue(store.clearToken(Service.SMS, siJeton = "neuf"))
        assertNull(store.tokenFor(Service.SMS))
    }

    @Test
    fun `les types chiffres vivent avec la session du service`() {
        val store = TokenStore(ouvrirMagasins({ MemoirePrefs() }, MemoirePrefs()) {})
        // Pas de session : une inscription tardive ne pose rien.
        store.saveWebpushTypes(Service.SMS, setOf("sms"))
        assertTrue(store.webpushTypesFor(Service.SMS).isEmpty())

        store.saveToken(Service.SMS, "s", null)
        store.saveToken(Service.MAIL, "m", null)
        store.saveWebpushTypes(Service.SMS, setOf("sms", "clear"))
        store.saveWebpushTypes(Service.MAIL, setOf("mail"))
        assertEquals(setOf("sms", "clear"), store.webpushTypesFor(Service.SMS))

        store.clearToken(Service.SMS)
        assertTrue(store.webpushTypesFor(Service.SMS).isEmpty())
        assertEquals(setOf("mail"), store.webpushTypesFor(Service.MAIL))

        store.clearAllTokens()
        assertTrue(store.webpushTypesFor(Service.MAIL).isEmpty())
    }

    @Test
    fun `seules trois cles et les types chiffres sont non secretes`() {
        assertTrue(estCleNonSecrete("instance_url"))
        assertTrue(estCleNonSecrete("theme_mode"))
        assertTrue(estCleNonSecrete("available_services"))
        assertTrue(estCleNonSecrete("webpush_types_mail"))
        assertFalse(estCleNonSecrete("token_sms"))
        assertFalse(estCleNonSecrete("user_name_mail"))
        assertFalse(estCleNonSecrete("pending_verifier"))
        assertFalse(estCleNonSecrete("n_importe_quoi"))
    }
}
