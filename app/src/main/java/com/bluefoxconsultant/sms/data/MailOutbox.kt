package com.bluefoxconsultant.sms.data

import android.content.Context
import com.bluefoxconsultant.sms.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.ui.UiText
import com.bluefoxconsultant.sms.ui.uiText

/** True when a failure means "couldn't reach the server", not "server said no". */
fun Throwable.isOffline(): Boolean = this is IOException ||
    (this is ApiException && code == 0)

/**
 * Le serveur a répondu, mais pas LUI : un 502/503/504 vient du mandataire
 * pendant qu'Odoo redémarre, un 429 dit « pas maintenant ». Lus comme un
 * refus, ils faisaient abandonner une réponse écrite hors ligne, dont le
 * brouillon était déjà effacé. On garde la file et on réessaie plus tard.
 * Audit du 2026-09-08.
 */
fun Throwable.isTransient(): Boolean =
    this is ApiException && code in setOf(429, 502, 503, 504)

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
    // ---- #25764 : tout ce que le composeur tenait, pas seulement le texte ----
    val bcc: List<String>? = null,
    /**
     * Les pièces DÉJÀ téléversées, avec leur nom : l'envoi n'a besoin que de
     * l'identifiant, mais un envoi refusé ou annulé redevient un brouillon, et
     * le brouillon doit les réafficher.
     */
    val attachments: List<StagedUpload> = emptyList(),
    /** [body] est-il déjà du HTML (mise en forme convertie au moment de décider) ? */
    @SerialName("body_is_html") val bodyIsHtml: Boolean = false,
    /** Le texte tel qu'il s'écrivait, balisage léger compris : pour le brouillon. */
    @SerialName("body_source") val bodySource: String = "",
    @SerialName("identity_id") val identityId: Int? = null,
    @SerialName("res_model") val resModel: String? = null,
    @SerialName("res_id") val resId: Int? = null,
    @SerialName("record_name") val recordName: String = "",
    /**
     * Pas avant cette heure : le délai pendant lequel « Annuler » reste
     * possible. La file ne touche pas à une action qui n'est pas due.
     */
    @SerialName("not_before_ms") val notBeforeMs: Long = 0,
    /** Un envoi PROGRAMMÉ au serveur, pas un délai de l'appareil. */
    @SerialName("scheduled_ms") val scheduledMs: Long? = null,
    /** Les destinataires d'une réponse avaient-ils été préparés et retouchés ? */
    @SerialName("recipients_prepared") val recipientsPrepared: Boolean = false,
) {
    val isSend: Boolean get() = kind == KIND_REPLY || kind == KIND_COMPOSE

    fun estDue(maintenant: Long): Boolean = notBeforeMs <= maintenant

    /** What to show the user while it waits. */
    val label: UiText get() = uiText(
        when (kind) {
            KIND_REPLY -> R.string.mail_outbox_reply_pending
            KIND_COMPOSE -> R.string.mail_outbox_compose_pending
            KIND_HANDLE ->
                if (handled) R.string.mail_outbox_archive_pending else R.string.mail_outbox_restore_pending
            KIND_SNOOZE -> R.string.mail_outbox_snooze_pending
            else -> R.string.mail_outbox_action_pending
        },
    )

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

    /**
     * Failures worth telling the user about, drained by the UI. Des [UiText] :
     * la file vit hors de tout écran, la phrase se rédige à l'affichage.
     */
    private val _failures = mutableListOf<UiText>()

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
    fun drainFailures(): List<UiText> {
        val out = _failures.toList()
        _failures.clear()
        return out
    }

    val size: Int get() = peek().size

    /**
     * Ce que le bandeau hors ligne doit compter : pas un envoi dont le délai
     * d'annulation court encore, qui a déjà son propre bandeau (#25764).
     */
    fun enAttenteVisible(maintenant: Long = System.currentTimeMillis()): Int =
        peek().count { it.estDue(maintenant) }

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
    suspend fun flush(
        send: suspend (PendingAction) -> Unit,
    ): Int = flush(send, refus = { _, _ -> false })

    /**
     * [refus] reçoit une action que le serveur a refusée, avec l'erreur. Rend
     * vrai quand il en a fait quelque chose — un envoi refusé redevient un
     * brouillon (#25764), et le message de la file le dit alors autrement que
     * « abandonné ».
     *
     * [maintenant] décide de ce qui est dû : une action dont le délai
     * d'annulation court encore reste en file, et celles d'après passent.
     */
    suspend fun flush(
        send: suspend (PendingAction) -> Unit,
        maintenant: () -> Long = System::currentTimeMillis,
        /**
         * Les actions à ne PAS envoyer, relu à chaque tour : « Annuler » touché
         * pendant qu'une vidange lente tient le verrou marque l'envoi ici, et
         * la boucle ne le prend plus même si son délai échoit entretemps.
         */
        ignorer: (PendingAction) -> Boolean = { false },
        refus: (PendingAction, Throwable) -> Boolean,
    ): Int {
        mutex.withLock {
            var sent = 0
            while (true) {
                // Relue à chaque tour, et retirée PAR IDENTITÉ : une copie
                // locale réécrite après l'envoi effaçait ce qu'un `enqueue`
                // avait ajouté pendant qu'on attendait le serveur.
                val action = peek().firstOrNull { it.estDue(maintenant()) && !ignorer(it) } ?: break
                try {
                    send(action)
                } catch (e: CancellationException) {
                    throw e   // annulée, pas refusée : la file reste intacte
                } catch (e: Throwable) {
                    if (e.isOffline() || e.isTransient()) return sent   // keep the queue
                    // The server answered and refused. Drop it, say why.
                    if (!refus(action, e)) {
                        synchronized(this) { _failures.add(uiText(R.string.mail_outbox_dropped, action.label)) }
                    }
                }
                write(peek().filterNot { it.token == action.token })
                sent += 1
            }
            return sent
        }
    }

    /**
     * Retirer une action de la file, si elle n'est pas partie.
     *
     * Sous le même verrou que [flush] : « Annuler » touché pendant que l'envoi
     * est en vol attend son issue, et rend `null` si le message est parti. Rend
     * l'action retirée sinon.
     */
    suspend fun retirer(token: String): PendingAction? = mutex.withLock {
        val action = peek().firstOrNull { it.token == token } ?: return@withLock null
        write(peek().filterNot { it.token == token })
        action
    }
}
