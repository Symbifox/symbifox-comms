package com.bluefoxconsultant.sms.data

import android.content.Context
import com.bluefoxconsultant.sms.network.ApiException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/** True when a failure means "couldn't reach the server", not "server said no". */
fun Throwable.isOffline(): Boolean = this is IOException ||
    (this is ApiException && code == 0)

/** One action taken while the server was unreachable, waiting to be replayed. */
@Serializable
data class PendingAction(
    /**
     * Generated once, before the first attempt, and reused on every replay.
     * For sends it goes to the server as `client_token`, which is what makes a
     * retry safe — see the send-once ledger in `bf_email_mobile_send.py`.
     */
    val token: String,
    val kind: String,
    @SerialName("created_ms") val createdMs: Long,
    @SerialName("email_ids") val emailIds: List<Int> = emptyList(),
    val handled: Boolean = true,
    @SerialName("until_ms") val untilMs: Long = 0,
    @SerialName("email_id") val emailId: Int = 0,
    val mode: String = "reply",
    val body: String = "",
    val subject: String = "",
    val to: List<String>? = null,
    val cc: List<String>? = null,
) {
    val isSend: Boolean get() = kind == KIND_REPLY || kind == KIND_COMPOSE

    /** What to show the user while it waits. */
    val label: String get() = when (kind) {
        KIND_REPLY -> "Réponse en attente d'envoi"
        KIND_COMPOSE -> "Courriel en attente d'envoi"
        KIND_HANDLE -> if (handled) "Archivage en attente" else "Restauration en attente"
        KIND_SNOOZE -> "Report en attente"
        else -> "Action en attente"
    }

    companion object {
        const val KIND_MARK_READ = "mark_read"
        const val KIND_HANDLE = "handle"
        const val KIND_SNOOZE = "snooze"
        const val KIND_REPLY = "reply"
        const val KIND_COMPOSE = "compose"

        fun newToken(): String = UUID.randomUUID().toString()
    }
}

/**
 * Actions taken offline, replayed in order once the server answers again.
 *
 * Three rules make this safe rather than merely convenient:
 *
 * 1. **FIFO.** "Archive then restore" and "restore then archive" end
 *    differently, so replay preserves the order the user acted in.
 * 2. **Retry only on network failure.** If the server *answers* with a refusal
 *    — empty body, deleted email, no recipient — the action is dropped and
 *    reported. Retrying a business error forever would silently wedge the
 *    queue behind an action that can never succeed.
 * 3. **Sends carry a token from the first attempt.** The one failure the
 *    client cannot diagnose is a send that succeeded with the response lost;
 *    the token turns its replay into a server-side no-op instead of a second
 *    copy in the correspondent's inbox.
 *
 * Triage actions need no token: setting `is_handled` twice is the same as
 * setting it once.
 */
class MailOutbox(private val file: File) {

    /** Production entry point; the File constructor is what tests drive. */
    constructor(context: Context) : this(File(context.filesDir, "mailcache/outbox.json"))
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** Failures worth telling the user about, drained by the UI. */
    private val _failures = mutableListOf<String>()

    @Synchronized
    fun peek(): List<PendingAction> = runCatching {
        if (!file.exists()) emptyList()
        else json.decodeFromString<List<PendingAction>>(file.readText())
    }.getOrDefault(emptyList())

    @Synchronized
    private fun write(actions: List<PendingAction>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "outbox.json.tmp")
            tmp.writeText(json.encodeToString(actions))
            if (!tmp.renameTo(file)) file.writeText(json.encodeToString(actions))
        }
    }

    @Synchronized
    fun enqueue(action: PendingAction) = write(peek() + action)

    @Synchronized
    fun drainFailures(): List<String> {
        val out = _failures.toList()
        _failures.clear()
        return out
    }

    val size: Int get() = peek().size

    /** Pending sends, so the composer/thread can show "waiting to send". */
    fun pendingSends(): List<PendingAction> = peek().filter { it.isSend }

    fun clear() {
        write(emptyList())
    }

    /**
     * Replay everything, oldest first, through the supplied sender. Stops at
     * the first network failure and keeps the rest queued; returns how many
     * actions went through.
     *
     * Takes a function rather than the repository so the queue's ordering and
     * failure rules can be exercised without a server — the part most likely
     * to be subtly wrong is the bookkeeping, not the HTTP.
     */
    suspend fun flush(send: suspend (PendingAction) -> Unit): Int {
        mutex.withLock {
            var sent = 0
            var queue = peek()
            while (queue.isNotEmpty()) {
                val action = queue.first()
                try {
                    send(action)
                } catch (e: Throwable) {
                    if (e.isOffline()) return sent   // still down; keep the queue
                    // The server answered and refused. Drop it, say why.
                    synchronized(this) { _failures.add("${action.label} — abandonnée.") }
                }
                queue = queue.drop(1)
                write(queue)
                sent += 1
            }
            return sent
        }
    }
}
