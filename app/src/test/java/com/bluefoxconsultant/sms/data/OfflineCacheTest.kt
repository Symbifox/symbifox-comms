package com.bluefoxconsultant.sms.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The offline layer, exercised without a device or a server.
 *
 * What's under test is the bookkeeping — what gets stored, what gets returned
 * when the server is unreachable, and above all what the outbox does with an
 * action it cannot deliver. Those rules are where a caching layer quietly goes
 * wrong: showing one conversation's body under another's key, retrying a
 * doomed action forever, or replaying a send without its dedup token.
 */
class OfflineCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache() = MailCache(File(tmp.root, "mailcache"))
    private fun outbox() = MailOutbox(File(tmp.root, "outbox.json"))

    private fun thread(key: String, subject: String) = MailMessage(
        id = key.hashCode(), threadKey = key, subject = subject, messageCount = 1,
    )

    // ---- cache ----

    @Test
    fun `thread list survives a round trip`() {
        val c = cache()
        assertNull("nothing cached yet", c.loadThreads(MailFilter.INBOX))

        c.saveThreads(
            MailFilter.INBOX,
            MailThreadsResponse(threads = listOf(thread("<a@x>", "Facture")), hasMore = true),
        )
        val back = c.loadThreads(MailFilter.INBOX)
        assertNotNull(back)
        assertEquals(1, back!!.threads.size)
        assertEquals("Facture", back.threads.first().subject)
    }

    @Test
    fun `filters are cached independently`() {
        val c = cache()
        c.saveThreads(MailFilter.INBOX, MailThreadsResponse(listOf(thread("<a@x>", "Boîte"))))
        c.saveThreads(MailFilter.SENT, MailThreadsResponse(listOf(thread("<b@x>", "Envoyé"))))

        assertEquals("Boîte", c.loadThreads(MailFilter.INBOX)!!.threads.first().subject)
        assertEquals("Envoyé", c.loadThreads(MailFilter.SENT)!!.threads.first().subject)
    }

    @Test
    fun `a conversation is only served under the key it was stored with`() {
        val c = cache()
        c.saveConversation(
            "<root@x>",
            MailConversationResponse(threadKey = "<root@x>", subject = "Vrai fil"),
        )
        assertEquals("Vrai fil", c.loadConversation("<root@x>")!!.subject)
        // Filenames are derived from a hash; the stored key is the authority.
        // Without that check a collision would show one client's thread inside
        // another's — the worst failure this cache could produce.
        assertNull(c.loadConversation("<autre@x>"))
    }

    @Test
    fun `a corrupted cache file reads as absent, not as a crash`() {
        val c = cache()
        c.saveThreads(MailFilter.INBOX, MailThreadsResponse(listOf(thread("<a@x>", "X"))))
        File(tmp.root, "mailcache/threads-inbox.json").writeText("{ tronqué")

        assertNull("must degrade to no-cache", c.loadThreads(MailFilter.INBOX))
        // And it must be recoverable, not permanently wedged.
        c.saveThreads(MailFilter.INBOX, MailThreadsResponse(listOf(thread("<b@x>", "Y"))))
        assertEquals("Y", c.loadThreads(MailFilter.INBOX)!!.threads.first().subject)
    }

    @Test
    fun `conversations are evicted so the cache cannot grow without bound`() {
        val c = cache()
        repeat(50) { i ->
            c.saveConversation("<k$i@x>", MailConversationResponse(threadKey = "<k$i@x>"))
        }
        val files = File(tmp.root, "mailcache")
            .listFiles { f -> f.name.startsWith("conv-") }.orEmpty()
        assertTrue("kept ${files.size}, expected <= 40", files.size <= 40)
    }

    @Test
    fun `clear wipes bodies on sign-out`() {
        val c = cache()
        c.saveConfig(MailConfig(userName = "Olivier"))
        c.saveConversation("<a@x>", MailConversationResponse(threadKey = "<a@x>"))
        c.clear()
        assertNull(c.loadConfig())
        assertNull(c.loadConversation("<a@x>"))
    }

    // ---- outbox ----

    private fun action(kind: String, token: String = PendingAction.newToken()) =
        PendingAction(token = token, kind = kind, createdMs = 0, emailIds = listOf(1))

    @Test
    fun `queued actions replay oldest first`() = runBlocking {
        val o = outbox()
        o.enqueue(action(PendingAction.KIND_HANDLE, "t1"))
        o.enqueue(action(PendingAction.KIND_MARK_READ, "t2"))
        o.enqueue(action(PendingAction.KIND_SNOOZE, "t3"))

        val seen = mutableListOf<String>()
        val sent = o.flush { seen.add(it.token) }

        // Order matters: archive-then-restore and restore-then-archive differ.
        assertEquals(listOf("t1", "t2", "t3"), seen)
        assertEquals(3, sent)
        assertEquals(0, o.size)
    }

    @Test
    fun `a network failure keeps the queue intact for later`() = runBlocking {
        val o = outbox()
        o.enqueue(action(PendingAction.KIND_HANDLE, "t1"))
        o.enqueue(action(PendingAction.KIND_HANDLE, "t2"))

        var attempts = 0
        val sent = o.flush { attempts++; throw IOException("no route to host") }

        assertEquals("nothing delivered", 0, sent)
        assertEquals("stops at the first failure", 1, attempts)
        assertEquals("both still queued", 2, o.size)
        assertTrue("not reported as abandoned", o.drainFailures().isEmpty())
    }

    @Test
    fun `an action the server refuses is dropped, not retried forever`() = runBlocking {
        val o = outbox()
        o.enqueue(action(PendingAction.KIND_HANDLE, "doomed"))
        o.enqueue(action(PendingAction.KIND_HANDLE, "fine"))

        val delivered = mutableListOf<String>()
        val sent = o.flush { a ->
            if (a.token == "doomed") throw com.bluefoxconsultant.sms.network
                .ApiException(400, "Courriel introuvable.")
            delivered.add(a.token)
        }

        // The refused one must not wedge the queue behind it.
        assertEquals(listOf("fine"), delivered)
        assertEquals(2, sent)
        assertEquals(0, o.size)
        assertEquals(1, o.drainFailures().size)
    }

    @Test
    fun `a replayed send carries the token it was created with`() = runBlocking {
        val o = outbox()
        val token = PendingAction.newToken()
        o.enqueue(
            PendingAction(
                token = token, kind = PendingAction.KIND_REPLY, createdMs = 0,
                emailId = 12, body = "Bonjour",
            ),
        )
        // First attempt fails at the network; the queue keeps the SAME token,
        // which is what lets the server recognise the replay as a duplicate
        // instead of sending a second copy.
        o.flush { throw IOException("offline") }
        assertEquals(token, o.peek().single().token)

        var replayed: String? = null
        o.flush { replayed = it.token }
        assertEquals(token, replayed)
    }

    @Test
    fun `the queue survives a process restart`() {
        val token = PendingAction.newToken()
        outbox().enqueue(
            PendingAction(token = token, kind = PendingAction.KIND_COMPOSE,
                          createdMs = 0, body = "Écrit dans le métro"),
        )
        // A fresh instance over the same file = the app being killed and reopened.
        val reopened = outbox()
        assertEquals(1, reopened.size)
        assertEquals("Écrit dans le métro", reopened.peek().single().body)
        assertTrue(reopened.pendingSends().isNotEmpty())
    }

    @Test
    fun `offline is distinguished from a server refusal`() {
        assertTrue(IOException("boom").isOffline())
        assertTrue(java.net.UnknownHostException("dns").isOffline())
        // A 400 is the server answering — retrying it would never help.
        assertFalse(com.bluefoxconsultant.sms.network.ApiException(400, "nope").isOffline())
        assertFalse(com.bluefoxconsultant.sms.network.ApiException(401, "nope").isOffline())
    }
}
