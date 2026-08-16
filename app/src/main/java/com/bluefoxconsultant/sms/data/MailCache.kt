package com.bluefoxconsultant.sms.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk snapshot of what the mail API last returned, so the app opens to the
 * last known mailbox instead of a spinner and an error when the server can't
 * be reached.
 *
 * **Files, not Room.** The cached set is one thread-list page per filter plus
 * the recently-opened conversations — tens of kilobytes of index and a few
 * megabytes of bodies. Room would buy querying we don't do, at the price of
 * adding an annotation processor to a shipping build. If the cache ever grows
 * into "search my whole mailbox offline", that trade flips and Room is the
 * upgrade path.
 *
 * **Only page 0, and only unsearched.** Caching page 3 of a filter, or a search
 * result, means reconstructing pagination state that was never coherent
 * offline. The first page of each mailbox is what "open the app on the metro"
 * actually needs.
 *
 * Storage is app-private. Not separately encrypted — the OS already keeps it
 * out of other apps' reach and the device is disk-encrypted — but it IS wiped
 * on sign-out, because mail bodies should not outlive the session.
 */
class MailCache(private val root: File) {

    /** Production entry point; the File constructor is what tests drive. */
    constructor(context: Context) : this(File(context.filesDir, "mailcache"))

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Conversations kept on disk; oldest are evicted past this. */
    private val maxConversations = 40

    private fun dir(): File = root.apply { if (!exists()) mkdirs() }

    /**
     * Write via a temp file and rename. A half-written cache file is worse than
     * no cache: it would throw on every later read and permanently "break"
     * offline mode until the app is reinstalled.
     */
    private fun writeAtomic(file: File, text: String) {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    private inline fun <reified T> read(file: File): T? = runCatching {
        if (!file.exists()) null else json.decodeFromString<T>(file.readText())
    }.getOrNull()

    // ---- config ----

    fun saveConfig(config: MailConfig) =
        writeAtomic(File(dir(), "config.json"), json.encodeToString(config))

    fun loadConfig(): MailConfig? = read<MailConfig>(File(dir(), "config.json"))

    // ---- thread list (page 0 of a filter, no search) ----

    fun saveThreads(filter: MailFilter, response: MailThreadsResponse) =
        writeAtomic(File(dir(), "threads-${filter.key}.json"), json.encodeToString(response))

    fun loadThreads(filter: MailFilter): MailThreadsResponse? =
        read<MailThreadsResponse>(File(dir(), "threads-${filter.key}.json"))

    // ---- conversations ----

    /** Thread keys are Message-IDs — full of characters a filename can't hold. */
    private fun conversationFile(threadKey: String) =
        File(dir(), "conv-${threadKey.hashCode().toUInt().toString(16)}.json")

    fun saveConversation(threadKey: String, response: MailConversationResponse) {
        writeAtomic(conversationFile(threadKey), json.encodeToString(response))
        evictConversations()
    }

    fun loadConversation(threadKey: String): MailConversationResponse? {
        val cached = read<MailConversationResponse>(conversationFile(threadKey))
        // hashCode collisions are rare but not impossible; the stored key is
        // the authority, so a mismatch means this file belongs to some other
        // conversation and must not be shown as this one.
        return cached?.takeIf { it.threadKey == threadKey }
    }

    private fun evictConversations() {
        runCatching {
            val files = dir().listFiles { f -> f.name.startsWith("conv-") } ?: return
            if (files.size <= maxConversations) return
            files.sortedBy { it.lastModified() }
                .take(files.size - maxConversations)
                .forEach { it.delete() }
        }
    }

    /** Sign-out: the cache holds message bodies, so it goes with the session. */
    fun clear() {
        runCatching { root.deleteRecursively() }
    }
}
