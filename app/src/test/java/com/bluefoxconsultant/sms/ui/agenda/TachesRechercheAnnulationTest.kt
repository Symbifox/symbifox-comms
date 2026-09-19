package com.bluefoxconsultant.sms.ui.agenda

import com.bluefoxconsultant.sms.data.AgendaTask
import com.bluefoxconsultant.sms.data.AgendaTaskSearchResponse
import com.bluefoxconsultant.sms.data.AgendaTasksResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * La recherche et l'annulation des tâches, telles que le modèle de vue les
 * mène (#25734). Le dépôt est remplacé : il répond quand l'essai le dit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TachesRechercheAnnulationTest {

    private val principal = StandardTestDispatcher()

    @Before
    fun avant() = Dispatchers.setMain(principal)

    @After
    fun apres() = Dispatchers.resetMain()

    private val attente = AgendaTask(id = 11, name = "Relance client", state = "04_waiting_normal")
    private val faite = AgendaTask(id = 12, name = "Facture envoyée", state = "1_done", done = true)

    private fun vm(
        niveau: Int = 4,
        chercher: suspend (String) -> AgendaTaskSearchResponse = { AgendaTaskSearchResponse(ok = true) },
        ecritures: MutableList<Pair<Int, String>> = mutableListOf(),
        seaux: AgendaTasksResponse = AgendaTasksResponse(ok = true),
    ) = TachesViewModel(
        lireTaches = { _, _, _ -> seaux },
        poserPastille = {},
        chargerOptions = false,
        chercherTaches = chercher,
        ecrireTache = { id, valeurs ->
            ecritures += id to valeurs
            AgendaTask(id = id, name = "maj", state = Regex("\"state\":\"([^\"]+)\"").find(valeurs)?.groupValues?.get(1) ?: "")
        },
        niveauApi = { niveau },
    )

    @Test
    fun `la reponse d'une frappe depassee n'ecrit rien`() = runTest(principal) {
        val appels = mutableListOf<Pair<String, Continuation<AgendaTaskSearchResponse>>>()
        val v = vm(chercher = { q -> suspendCoroutine { appels += q to it } })
        advanceUntilIdle()
        v.openSearch()
        v.onSearchChange("fa")
        advanceUntilIdle()
        v.onSearchChange("fac")
        advanceUntilIdle()
        // La première est partie (la pause de frappe écoulée) avant la seconde.
        assertEquals(listOf("fa", "fac"), appels.map { it.first })
        appels[1].second.resume(AgendaTaskSearchResponse(ok = true, tasks = listOf(attente)))
        advanceUntilIdle()
        appels[0].second.resume(AgendaTaskSearchResponse(ok = true, tasks = listOf(faite)))
        advanceUntilIdle()
        assertEquals(listOf(11), v.resultats.map { it.id })
        assertFalse(v.cherche)
        assertFalse(v.rechercheLocale)
    }

    @Test
    fun `un serveur ancien filtre ce que l'ecran tient, et le dit`() = runTest(principal) {
        var appele = false
        val v = vm(
            niveau = 3,
            chercher = { appele = true; AgendaTaskSearchResponse(ok = true) },
            seaux = AgendaTasksResponse(ok = true, overdue = listOf(attente),
                window = listOf(AgendaTask(id = 13, name = "Autre chose"))),
        )
        advanceUntilIdle()
        v.openSearch()
        v.onSearchChange("relance")
        advanceUntilIdle()
        assertFalse("l'api 3 ne connaît pas /tasks/search", appele)
        assertEquals(listOf(11), v.resultats.map { it.id })
        assertTrue(v.rechercheLocale)
    }

    @Test
    fun `fermer la recherche rend les seaux et oublie le terme`() = runTest(principal) {
        val v = vm(chercher = { AgendaTaskSearchResponse(ok = true, tasks = listOf(attente)) })
        advanceUntilIdle()
        v.openSearch()
        v.onSearchChange("relance")
        advanceUntilIdle()
        assertTrue(v.enRecherche)
        v.closeSearch()
        assertFalse(v.enRecherche)
        assertEquals("", v.recherche)
        assertTrue(v.resultats.isEmpty())
    }

    @Test
    fun `annuler puis retablir remet l'etat d'avant, pas en cours`() = runTest(principal) {
        val ecritures = mutableListOf<Pair<Int, String>>()
        val v = vm(ecritures = ecritures)
        advanceUntilIdle()
        v.open(attente)
        v.cancel(attente)
        advanceUntilIdle()
        assertEquals(11 to """{"state":"1_canceled"}""", ecritures.last())
        assertNull("la fiche se ferme", v.selected)
        assertEquals("04_waiting_normal", v.annulation?.etatAvant)
        v.restoreCancelled()
        advanceUntilIdle()
        assertEquals(11 to """{"state":"04_waiting_normal"}""", ecritures.last())
        assertNull(v.annulation)
    }

    @Test
    fun `retablir une tache qui etait close la rouvre en cours`() = runTest(principal) {
        val ecritures = mutableListOf<Pair<Int, String>>()
        val v = vm(ecritures = ecritures)
        advanceUntilIdle()
        v.cancel(faite)
        advanceUntilIdle()
        v.restoreCancelled()
        advanceUntilIdle()
        assertEquals(12 to """{"state":"01_in_progress"}""", ecritures.last())
    }
}
