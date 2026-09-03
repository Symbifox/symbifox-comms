package com.bluefoxconsultant.sms.ui.genfox

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bluefoxconsultant.sms.audio.Earcons
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
 *
 * Chaque changement d'étape s'entend et se date. En mains libres on ne regarde
 * pas l'écran : sans un son au moment où le micro se ferme, et sans un compteur
 * qui avance pendant la transcription, l'attente se lit comme une panne — c'est
 * exactement ce qui a été rapporté du premier essai sur le terrain.
 */
class HandsFreeController(
    context: Context,
    private val scope: CoroutineScope,
    private val onQuestion: (String) -> Unit,
    private val onNotice: (String) -> Unit,
) {

    var state by mutableStateOf(HandsFreeState.Off)
        private set

    /**
     * Ce que la transcription a rendu du dernier tour, affiché tel quel.
     *
     * C'est la réponse à « on n'est pas certains que Gen a compris » : la bulle
     * de la question finit par le dire, mais seulement une fois le tour parti.
     * Ici le texte paraît dès qu'il existe, sous les yeux de qui attend.
     */
    var heard by mutableStateOf<String?>(null)
        private set

    /** Horloge du début de l'étape en cours, pour afficher un compteur qui avance. */
    var phaseSince by mutableStateOf(SystemClock.elapsedRealtime())
        private set

    private val recorder = VoiceRecorder(context)
    private val speaker = Speaker(context)
    private val earcons = Earcons()
    private var loopJob: Job? = null

    /** Change d'étape et remet le compteur à zéro : les deux vont ensemble. */
    private fun enter(next: HandsFreeState) {
        state = next
        phaseSince = SystemClock.elapsedRealtime()
    }

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
        heard = null
        enter(HandsFreeState.Off)
    }

    /** The answer landed: say it, then reopen the microphone. */
    fun answered(text: String) {
        if (!isOn) return
        enter(HandsFreeState.Speaking)
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
        earcons.standDown()
        onNotice("L'assistant n'a pas répondu — mode mains libres arrêté.")
        stop()
    }

    fun waiting() {
        if (isOn && state != HandsFreeState.Waiting) enter(HandsFreeState.Waiting)
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
        this.heard = null
        enter(HandsFreeState.Listening)
        loopJob = scope.launch {
            // ⚠️ Le bip d'ouverture doit avoir FINI avant que le micro s'ouvre.
            // Sinon la boucle s'entend elle-même : le bip passe le seuil de
            // parole, `spoke` devient vrai sans que personne ait parlé, et deux
            // secondes de silence plus tard on envoie l'enregistrement d'une
            // pièce vide se faire transcrire.
            earcons.yourTurn()
            delay(BEEP_GUARD_MS)
            if (!recorder.start()) {
                onNotice("Le micro n'est pas disponible.")
                stop()
                return@launch
            }
            var spoke = false
            var quiet = 0
            var elapsed = 0L
            while (elapsed < MAX_LISTEN_MS) {
                delay(POLL_MS)
                elapsed += POLL_MS
                val level = recorder.amplitude()
                if (level > SPEECH_LEVEL) {
                    spoke = true
                    quiet = 0
                } else if (spoke && level < SILENCE_LEVEL) {
                    quiet++
                    if (quiet >= QUIET_POLLS) break
                }
            }
            val file = recorder.stop()
            if (!spoke || file == null) {
                // Heard nothing at all: the person is not talking to it. Stand
                // down instead of recording the room in a loop.
                earcons.standDown()
                onNotice("Rien entendu — mode mains libres arrêté.")
                stop()
                return@launch
            }
            // Le micro vient de se fermer sur le silence. C'est le seul moment
            // de la boucle qu'on ne peut pas deviner sans regarder l'écran, et
            // le plus long attend juste après : il s'entend.
            earcons.captured()
            enter(HandsFreeState.Sending)
            try {
                val text = Graph.speech.transcribe(file)
                if (text.isBlank()) {
                    earcons.standDown()
                    onNotice("Rien n'a été compris.")
                    stop()
                } else {
                    this@HandsFreeController.heard = text
                    enter(HandsFreeState.Waiting)
                    onQuestion(text)
                }
            } catch (e: ApiException) {
                earcons.standDown()
                onNotice(e.err)
                stop()
            } catch (e: Exception) {
                earcons.standDown()
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
        earcons.release()
    }

    private companion object {
        const val POLL_MS = 200L
        const val QUIET_POLLS = 10          // deux secondes de silence
        const val MAX_LISTEN_MS = 30_000L
        const val SPEECH_LEVEL = 2_000
        const val SILENCE_LEVEL = 1_200
        const val BEEP_GUARD_MS = 250L      // le bip d'ouverture s'éteint avant le micro
    }
}
