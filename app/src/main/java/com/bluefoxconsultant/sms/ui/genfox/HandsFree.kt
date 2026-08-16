package com.bluefoxconsultant.sms.ui.genfox

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bluefoxconsultant.sms.audio.Speaker
import com.bluefoxconsultant.sms.audio.VoiceRecorder
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.network.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class HandsFreeState { Off, Listening, Sending, Waiting, Speaking }

/**
 * The loop: listen, transcribe, ask, speak, listen again.
 *
 * Ending a sentence is detected by polling the recorder's peak amplitude rather
 * than by shipping a voice-activity model: a run of quiet frames, after
 * something was actually heard, means the person stopped talking. Crude, but it
 * costs nothing and the failure mode is benign — a sentence cut a beat early,
 * which the transcription usually still carries.
 *
 * Nothing here is automatic in the background. The loop only runs while the
 * screen is open and the toggle is on, and any tap turns it off.
 */
class HandsFreeController(
    context: Context,
    private val scope: CoroutineScope,
    private val onQuestion: (String) -> Unit,
    private val onNotice: (String) -> Unit,
) {

    var state by mutableStateOf(HandsFreeState.Off)
        private set

    private val recorder = VoiceRecorder(context)
    private val speaker = Speaker(context)
    private var loopJob: Job? = null

    val isOn: Boolean get() = state != HandsFreeState.Off

    fun start() {
        if (isOn) return
        listen()
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        speaker.onSpoken(null)
        speaker.stop()
        recorder.cancel()
        state = HandsFreeState.Off
    }

    /** The answer landed: say it, then reopen the microphone. */
    fun answered(text: String) {
        if (!isOn) return
        state = HandsFreeState.Speaking
        speaker.onSpoken {
            // Called on the engine's thread — hop back onto the scope before
            // touching state or starting a recording.
            scope.launch { if (isOn) listen() }
        }
        speaker.speak(text)
    }

    /** The turn failed: say nothing more, and stand down rather than loop on it. */
    fun failed() {
        if (!isOn) return
        onNotice("L'assistant n'a pas répondu — mode mains libres arrêté.")
        stop()
    }

    fun waiting() {
        if (isOn) state = HandsFreeState.Waiting
    }

    /**
     * Reads one answer aloud without starting the loop — the replay button on a
     * bubble. Ignored while hands-free is running, which is already speaking.
     */
    fun say(text: String) {
        if (isOn) return
        speaker.onSpoken(null)
        speaker.speak(text)
    }

    fun hush() = speaker.stop()

    private fun listen() {
        loopJob?.cancel()
        if (!recorder.start()) {
            onNotice("Le micro n'est pas disponible.")
            stop()
            return
        }
        state = HandsFreeState.Listening
        loopJob = scope.launch {
            var heard = false
            var quiet = 0
            var elapsed = 0L
            while (elapsed < MAX_LISTEN_MS) {
                delay(POLL_MS)
                elapsed += POLL_MS
                val level = recorder.amplitude()
                if (level > SPEECH_LEVEL) {
                    heard = true
                    quiet = 0
                } else if (heard && level < SILENCE_LEVEL) {
                    quiet++
                    if (quiet >= QUIET_POLLS) break
                }
            }
            val file = recorder.stop()
            if (!heard || file == null) {
                // Heard nothing at all: the person is not talking to it. Stand
                // down instead of recording the room in a loop.
                onNotice("Rien entendu — mode mains libres arrêté.")
                stop()
                return@launch
            }
            state = HandsFreeState.Sending
            try {
                val text = Graph.speech.transcribe(file)
                if (text.isBlank()) {
                    onNotice("Rien n'a été compris.")
                    stop()
                } else {
                    state = HandsFreeState.Waiting
                    onQuestion(text)
                }
            } catch (e: ApiException) {
                onNotice(e.err)
                stop()
            } catch (e: Exception) {
                onNotice("Transcription impossible.")
                stop()
            } finally {
                file.delete()
            }
        }
    }

    fun release() {
        stop()
        speaker.release()
    }

    private companion object {
        const val POLL_MS = 200L
        const val QUIET_POLLS = 10          // deux secondes de silence
        const val MAX_LISTEN_MS = 30_000L
        const val SPEECH_LEVEL = 2_000
        const val SILENCE_LEVEL = 1_200
    }
}
