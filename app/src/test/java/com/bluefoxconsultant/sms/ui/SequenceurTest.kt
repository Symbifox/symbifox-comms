package com.bluefoxconsultant.sms.ui

import com.bluefoxconsultant.sms.data.AgendaTask
import com.bluefoxconsultant.sms.data.AgendaTasksResponse
import com.bluefoxconsultant.sms.ui.agenda.TachesViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Deux lectures en vol, et c'est la dernière LANCÉE qui écrit (Q-M6).
 *
 * ⚠️ Le dépôt injecté répond dans le désordre ET reprend sans regarder
 * l'annulation (`suspendCoroutine`, pas `suspendCancellableCoroutine`) : la
 * lecture qu'on vient d'annuler continue donc comme si de rien n'était. C'est
 * le pire cas — une réponse que plus aucun `cancel()` n'arrête — et seul le
 * numéro de séquence peut alors l'empêcher d'écrire. C'est lui qu'on éprouve.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SequenceurTest {

    private val principal = StandardTestDispatcher()

    @Before
    fun avant() {
        Dispatchers.setMain(principal)
    }

    @After
    fun apres() {
        Dispatchers.resetMain()
    }

    /** Un dépôt dont chaque appel attend qu'on lui dise quoi rendre. */
    private class DepotDansLeDesordre {
        val appels = mutableListOf<Continuation<AgendaTasksResponse>>()
        suspend fun lire(): AgendaTasksResponse = suspendCoroutine { appels += it }
    }

    private fun reponse(vararg ids: Int) = AgendaTasksResponse(
        ok = true,
        window = ids.map {
            AgendaTask(id = it, deadline = Instant.now().plusSeconds(3_600).toString())
        },
    )

    @Test
    fun `le sequenceur ne reconnait que la derniere lecture lancee`() = runTest(principal) {
        val s = Sequenceur()
        val ecrits = mutableListOf<String>()
        val attentes = mutableListOf<Continuation<Unit>>()
        s.lancer(this) { n ->
            suspendCoroutine<Unit> { attentes += it }
            if (s.estCourante(n)) ecrits += "A"
        }
        // A est partie et attend sa réponse quand B la remplace.
        advanceUntilIdle()
        s.lancer(this) { n ->
            suspendCoroutine<Unit> { attentes += it }
            if (s.estCourante(n)) ecrits += "B"
        }
        advanceUntilIdle()
        attentes[1].resume(Unit)
        advanceUntilIdle()
        attentes[0].resume(Unit)
        advanceUntilIdle()
        assertEquals(listOf("B"), ecrits)
    }

    @Test
    fun `annuler fait taire ce qui est en vol`() = runTest(principal) {
        val s = Sequenceur()
        var ecrit = false
        val attentes = mutableListOf<Continuation<Unit>>()
        s.lancer(this) { n ->
            suspendCoroutine<Unit> { attentes += it }
            if (s.estCourante(n)) ecrit = true
        }
        advanceUntilIdle()
        s.annuler()
        attentes.single().resume(Unit)
        advanceUntilIdle()
        assertFalse(ecrit)
    }

    @Test
    fun `taches, la reponse perimee n'ecrit rien et ne coupe pas le temoin`() = runTest(principal) {
        val depot = DepotDansLeDesordre()
        val pastilles = mutableListOf<Int>()
        val vm = TachesViewModel(
            lireTaches = { _, _, _ -> depot.lire() },
            poserPastille = { pastilles += it },
            chargerOptions = false,
        )
        // L'`init` a lancé la première lecture ; un tirer lance la seconde.
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, depot.appels.size)
        assertTrue(vm.loading)
        assertTrue(vm.refreshing)

        // L'ancienne revient la première… et ne doit rien écrire, ni éteindre
        // les témoins de la lecture qui la remplace.
        depot.appels[0].resume(reponse(1, 2, 3))
        advanceUntilIdle()
        assertTrue(vm.window.isEmpty())
        assertTrue(vm.sections.isEmpty())
        assertTrue(pastilles.isEmpty())
        assertTrue(vm.loading)
        assertTrue(vm.refreshing)

        // La courante arrive : elle seule écrit, et éteint.
        depot.appels[1].resume(reponse(9))
        advanceUntilIdle()
        assertEquals(listOf(9), vm.window.map { it.id })
        assertEquals(1, vm.sections.size)
        assertFalse(vm.loading)
        assertFalse(vm.refreshing)
        // Posée une fois, par la courante. Sa VALEUR dépend de l'heure du banc
        // (une échéance à une heure d'ici peut tomber demain) : c'est
        // `DecoupageTachesTest` qui l'éprouve, à date fixe.
        assertEquals(1, pastilles.size)
    }

    @Test
    fun `taches, une reponse perimee qui arrive APRES la courante ne l'ecrase pas`() = runTest(principal) {
        val depot = DepotDansLeDesordre()
        val vm = TachesViewModel(
            lireTaches = { _, _, _ -> depot.lire() },
            poserPastille = {},
            chargerOptions = false,
        )
        advanceUntilIdle()
        vm.setHorizon(30L)
        advanceUntilIdle()
        depot.appels[1].resume(reponse(30))
        advanceUntilIdle()
        depot.appels[0].resume(reponse(14))
        advanceUntilIdle()
        assertEquals(listOf(30), vm.window.map { it.id })
        assertFalse(vm.loading)
    }
}
