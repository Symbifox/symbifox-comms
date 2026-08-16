package com.bluefoxconsultant.sms.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes real captured responses from `bf_email_management`'s mobile API.
 *
 * The fixtures in `src/test/resources/fixtures/` are verbatim bytes from a live
 * Odoo bench, not hand-written JSON — hand-written fixtures agree with whatever
 * the model already says, which is exactly the bug this is meant to catch.
 * Odoo's habit of emitting `false` where an id, a timestamp or a whole record
 * belongs is the main thing being pinned down here.
 *
 * Regenerate with `scratchpad/capture.sh` against a bench instance.
 */
class MailWireFormatTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name.json")) {
            "missing fixture $name.json"
        }.bufferedReader().readText()

    @Test
    fun `config decodes with accounts, counts and presets`() {
        val config = json.decodeFromString<MailConfig>(fixture("config"))

        assertTrue(config.userName.isNotBlank())
        assertEquals("America/Montreal", config.tz)
        assertTrue("at least one mailbox", config.accounts.isNotEmpty())
        assertTrue("snooze presets present", config.snoozePresets.isNotEmpty())
        // Presets are absolute instants resolved server-side in the user's tz.
        assertTrue(config.snoozePresets.all { it.untilMs > 0 })
        // Only the Odoo apps this instance actually has.
        assertTrue(config.spawnKinds.contains("task"))
        assertTrue(config.routableModels.any { it.model == "res.partner" })
    }

    @Test
    fun `thread list decodes and carries conversation aggregates`() {
        val resp = json.decodeFromString<MailThreadsResponse>(fixture("threads"))

        assertTrue(resp.threads.isNotEmpty())
        resp.threads.forEach { thread ->
            assertTrue("thread_key set", thread.threadKey.isNotBlank())
            assertTrue("message_count >= 1", thread.messageCount >= 1)
            assertTrue("sortDate resolves", thread.sortDate > 0)
        }
        // The seeded two-message conversation folds into one row.
        val folded = resp.threads.firstOrNull { it.messageCount > 1 }
        assertNotNull("a multi-message thread folded into one row", folded)
    }

    @Test
    fun `false-valued ids decode to null instead of throwing`() {
        val resp = json.decodeFromString<MailThreadsResponse>(fixture("threads"))

        // Odoo sends `false`, not null, for an unset many2one. Any of these
        // decoding as a non-null zero would mean the serializer stopped working.
        val orphan = resp.threads.firstOrNull { it.partnerId == null }
        assertNotNull("at least one row has no partner", orphan)
        assertNull("unrouted rows have record == false → null", orphan!!.record)
        assertNull("not snoozed → null, not 0", orphan.snoozedUntilMs)
    }

    @Test
    fun `conversation returns previews plus one full last message`() {
        val resp = json.decodeFromString<MailConversationResponse>(fixture("conversation"))

        assertTrue(resp.messages.size >= 2)
        assertFalse("thread not truncated at this size", resp.truncated)

        val last = resp.messages.last()
        assertTrue("last message arrives with its body", last.isFull)
        assertTrue(
            "remote images parked server-side",
            last.bodyHtml!!.contains("data-blocked-src"),
        )
        assertTrue("inline cid: images untouched", last.bodyHtml!!.contains("cid:"))
        assertEquals("one tracking pixel blocked", 1, last.blockedImages)

        resp.messages.dropLast(1).forEach {
            assertFalse("earlier messages are previews only", it.isFull)
        }
    }

    @Test
    fun `full message decodes with attachment metadata`() {
        val message = json.decodeFromString<MailMessage>(fixture("message"))

        assertTrue(message.isFull)
        assertEquals(1, message.attachments.size)
        val attachment = message.attachments.first()
        assertEquals("rapport.csv", attachment.name)
        assertEquals("text/csv", attachment.mimetype)
        assertTrue(attachment.size > 0)
        // Attachments are addressed by position, never by ir.attachment id.
        assertEquals(0, attachment.idx)
    }

    @Test
    fun `mutation responses carry the post-write counts`() {
        val resp = json.decodeFromString<MailCountsResponse>(fixture("counts"))
        assertTrue(resp.ok)
        // Not a default-constructed object: the server flushed and recounted.
        assertTrue(resp.counts.inbox > 0)
    }

    @Test
    fun `record search decodes`() {
        val resp = json.decodeFromString<MailRecordsResponse>(fixture("records"))
        assertTrue(resp.records.isNotEmpty())
        assertTrue(resp.records.all { it.id > 0 && it.name.isNotBlank() })
    }

    @Test
    fun `ping identifies the module`() {
        val obj = json.parseToJsonElement(fixture("ping"))
        assertTrue(obj.toString().contains("bf_email_management"))
    }

    @Test
    fun `derived display fields behave on real rows`() {
        val resp = json.decodeFromString<MailThreadsResponse>(fixture("threads"))
        resp.threads.forEach { thread ->
            assertTrue("correspondent never blank", thread.correspondent.isNotBlank())
            assertTrue("subject never blank", thread.displaySubject.isNotBlank())
        }
        // An outbound row must not be counted as unread on the phone.
        resp.threads.filter { it.isOutgoing }.forEach {
            assertFalse(it.isUnread)
        }
    }
}
