package com.bluefoxconsultant.sms.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads an answer out loud, using the phone's own speech engine.
 *
 * No server, no model in the APK, and it works offline once the system voice is
 * installed. The engine takes a moment to wake up, so [speak] queues what it is
 * given and flushes it when the engine reports ready — otherwise the first
 * answer of a session is silently dropped, which reads as "the button is
 * broken".
 */
class Speaker(context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var queued: String? = null
    private var onDone: (() -> Unit)? = null

    /** True between the start of an utterance and its end (or its failure). */
    @Volatile
    var speaking: Boolean = false
        private set

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.language = pickLocale()
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { speaking = true }

                    override fun onDone(utteranceId: String?) {
                        speaking = false
                        onDone?.invoke()
                    }

                    @Deprecated("Kept for API 26; the newer overload delegates here.")
                    override fun onError(utteranceId: String?) {
                        speaking = false
                        onDone?.invoke()
                    }
                })
                queued?.let { pending ->
                    queued = null
                    speak(pending)
                }
            }
        }
    }

    /** Quebec French first, then any French the engine has, then its default. */
    private fun pickLocale(): Locale {
        val tts = engine ?: return Locale.getDefault()
        return listOf(Locale.CANADA_FRENCH, Locale.FRENCH).firstOrNull {
            val availability = tts.isLanguageAvailable(it)
            availability == TextToSpeech.LANG_AVAILABLE ||
                availability == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                availability == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        } ?: Locale.getDefault()
    }

    /** Says [text], interrupting whatever was being said. */
    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val tts = engine
        if (tts == null || !ready) {
            queued = clean
            return
        }
        speaking = true
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** Called once the current utterance finishes. Used by the hands-free loop. */
    fun onSpoken(callback: (() -> Unit)?) {
        onDone = callback
    }

    fun stop() {
        queued = null
        speaking = false
        engine?.stop()
    }

    fun release() {
        onDone = null
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private companion object {
        const val UTTERANCE_ID = "genfox"
    }
}
