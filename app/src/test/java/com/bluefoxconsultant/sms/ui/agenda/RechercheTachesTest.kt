package com.bluefoxconsultant.sms.ui.agenda

import com.bluefoxconsultant.sms.data.AgendaTag
import com.bluefoxconsultant.sms.data.AgendaTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La règle de recherche du repli local (#25734). Chaque essai porte ce qui
 * ferait passer une règle naïve : un mot dans le projet et l'autre dans le
 * titre, un accent, un numéro absent du titre, une tâche sans échéance.
 */
class RechercheTachesTest {

    private val facture = AgendaTask(id = 101, name = "Facture de septembre",
        project = "Client Exemple", deadline = "2026-09-20T15:00:00Z")
    private val rapport = AgendaTask(id = 102, name = "Rapport de septembre",
        project = "Blue Fox", deadline = "2026-09-18T15:00:00Z")
    private val echeance = AgendaTask(id = 103, name = "Revoir l'échéancier",
        partner = "Quincaillerie Brisebois", tags = listOf(AgendaTag(1, "Pivoine")))
    private val toutes = listOf(facture, rapport, echeance)

    private fun ids(terme: String) = filtrerTaches(toutes, terme).map { it.id }

    @Test
    fun `chaque mot peut venir d'un champ different`() {
        assertEquals(listOf(101), ids("facture exemple"))
    }

    @Test
    fun `tous les mots doivent se trouver`() {
        assertEquals(emptyList<Int>(), ids("facture introuvable"))
    }

    @Test
    fun `les accents et la casse ne comptent pas`() {
        assertEquals(listOf(103), ids("ECHEANCIER"))
        assertEquals(listOf(103), ids("échéancier"))
    }

    @Test
    fun `le client et l'etiquette se cherchent aussi`() {
        assertEquals(listOf(103), ids("brisebois"))
        assertEquals(listOf(103), ids("pivoine"))
    }

    @Test
    fun `un numero trouve la tache meme absent du titre`() {
        assertEquals(listOf(102), ids("#102"))
        assertEquals(listOf(102), ids(" 102 "))
    }

    @Test
    fun `les datees d'abord, les sans-echeance a la fin`() {
        // « re » : dans Facture, dans septembre, dans Revoir.
        assertEquals(listOf(102, 101, 103), ids("re"))
    }

    @Test
    fun `une lettre seule ne cherche pas, un chiffre seul si`() {
        assertFalse(termeCherchable(" f "))
        assertTrue(termeCherchable("7"))
        assertTrue(termeCherchable("#7"))
        assertTrue(termeCherchable("fa"))
        assertEquals(emptyList<Int>(), ids("f"))
    }

    @Test
    fun `une tache presente dans deux seaux ne sort qu'une fois`() {
        assertEquals(listOf(101), filtrerTaches(listOf(facture, facture), "facture").map { it.id })
    }
}
