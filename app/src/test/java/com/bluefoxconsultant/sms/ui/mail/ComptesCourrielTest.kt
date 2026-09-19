package com.bluefoxconsultant.sms.ui.mail

import com.bluefoxconsultant.sms.data.MailAccount
import com.bluefoxconsultant.sms.data.MailAccountCounts
import com.bluefoxconsultant.sms.data.MailCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** La couleur et le nom court de chaque boîte (#25734). */
class ComptesCourrielTest {

    // Quatre boîtes : deux portent une couleur choisie au bureau (bleu,
    // ardoise), deux n'en ont pas.
    private val principale = MailAccount(1, "contact@exemple.test", color = "#29abe2")
    private val bonjour = MailAccount(2, "bonjour@ — réception", color = "#64748B")
    private val gen = MailAccount(5, "Gen — gen@bluefoxconsultant.com")
    private val perso = MailAccount(19, "Perso — jane@exemple.test")

    @Test
    fun `la couleur du bureau passe, et les autres ne la reprennent pas`() {
        val couleurs = couleursDesComptes(listOf(principale, bonjour, gen, perso))
        assertEquals("#29ABE2", couleurs[1])
        assertEquals("#64748B", couleurs[2])
        assertEquals("#16A34A", couleurs[5])
        assertEquals("#7C3AED", couleurs[19])
        assertEquals(4, couleurs.values.toSet().size)
    }

    @Test
    fun `une couleur abimee compte comme absente`() {
        val couleurs = couleursDesComptes(listOf(MailAccount(3, "X", color = "bleu"), principale))
        assertNotEquals("bleu", couleurs[3])
        assertNotEquals(couleurs[1], couleurs[3])
    }

    @Test
    fun `au-dela de six boites la palette recommence sans planter`() {
        val comptes = (1..8).map { MailAccount(it, "B$it") }
        val couleurs = couleursDesComptes(comptes)
        assertEquals(8, couleurs.size)
        assertEquals(6, couleurs.values.toSet().size)
    }

    @Test
    fun `le nom court s'arrete au tiret`() {
        assertEquals("Perso", libelleCompte(perso))
        assertEquals("bonjour@", libelleCompte(bonjour))
        assertEquals("contact@exemple.test", libelleCompte(principale))
        assertEquals("gen@x.ca", libelleCompte(MailAccount(9, "", login = "gen@x.ca")))
    }

    @Test
    fun `les totaux d'une boite, sinon les totaux de toutes`() {
        val counts = MailCounts(inbox = 12, unread = 3,
            byAccount = mapOf("19" to MailAccountCounts(inbox = 2, unread = 1)))
        assertEquals(2, counts.pourCompte(19).inbox)
        assertEquals(12, counts.pourCompte(null).inbox)
        // Serveur ancien, ou boîte absente des totaux : pas de zéro trompeur.
        assertEquals(12, counts.pourCompte(5).inbox)
    }
}
