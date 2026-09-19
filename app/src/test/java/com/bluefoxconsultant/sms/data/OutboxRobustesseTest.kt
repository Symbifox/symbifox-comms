package com.bluefoxconsultant.sms.data

import com.bluefoxconsultant.sms.network.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText

/**
 * Ce que la file hors ligne perdait, relevé à l'audit du 2026-09-08 : une
 * passerelle qui répond 502 pendant qu'Odoo redémarre, un `enqueue` pendant
 * un `flush`, et une coroutine annulée en plein envoi.
 */
class OutboxRobustesseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun outbox() = MailOutbox(File(tmp.root, "outbox.json"))

    private fun action(token: String) = PendingAction(
        token = token, kind = "mark_read", createdMs = 1L, emailIds = listOf(1),
    )

    @Test
    fun `une erreur de passerelle n'est pas un refus`() {
        assertTrue(ApiException(502, "bad gateway").isTransient())
        assertTrue(ApiException(503, "").isTransient())
        assertTrue(ApiException(504, "").isTransient())
        assertTrue(ApiException(429, "").isTransient())
        assertFalse(ApiException(400, "").isTransient())
        assertFalse(ApiException(404, "").isTransient())
        assertFalse(IOException("x").isTransient())
    }

    @Test
    fun `un 503 garde la file, la reponse ecrite n'est pas abandonnee`() = runBlocking {
        val box = outbox()
        box.enqueue(action("a"))
        val sent = box.flush { throw ApiException(503, "redémarrage") }
        assertEquals(0, sent)
        assertEquals(listOf("a"), box.peek().map { it.token })
        assertTrue(box.drainFailures().isEmpty())
    }

    @Test
    fun `un refus franc retire l'action et le dit`() = runBlocking {
        val box = outbox()
        box.enqueue(action("a"))
        box.flush { throw ApiException(400, "nope") }
        assertTrue(box.peek().isEmpty())
        // Ce qui est dit : l'action, nommée dans la langue du téléphone.
        assertEquals(
            listOf(
                UiText.Res(
                    R.string.mail_outbox_dropped,
                    listOf(UiText.Res(R.string.mail_outbox_action_pending)),
                ),
            ),
            box.drainFailures(),
        )
    }

    @Test
    fun `un enqueue pendant le flush n'est pas ecrase`() = runBlocking {
        val box = outbox()
        box.enqueue(action("a"))
        val entre = CompletableDeferred<Unit>()
        val porte = CompletableDeferred<Unit>()
        val job = launch {
            box.flush {
                // A part ; B, ajoutée pendant l'attente, trouve le réseau coupé
                // et doit rester en file.
                if (it.token == "a") { entre.complete(Unit); porte.await() }
                else throw IOException("plus tard")
            }
        }
        // Le flush attend le serveur ; un geste arrive entre-temps.
        entre.await()
        box.enqueue(action("b"))
        porte.complete(Unit)
        job.join()
        assertEquals(listOf("b"), box.peek().map { it.token })
    }

    @Test
    fun `une annulation laisse la file intacte`() = runBlocking {
        val box = outbox()
        box.enqueue(action("a"))
        val entre = CompletableDeferred<Unit>()
        val porte = CompletableDeferred<Unit>()
        val job = launch { box.flush { entre.complete(Unit); porte.await() } }
        entre.await()
        job.cancel(CancellationException("écran fermé"))
        job.join()
        assertEquals(listOf("a"), box.peek().map { it.token })
        assertTrue(box.drainFailures().isEmpty())
    }
}
