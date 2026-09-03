package com.bluefoxconsultant.sms.audio

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Les trois signaux sonores du mode mains libres.
 *
 * En mains libres, l'écran ne dit rien à qui ne le regarde pas : c'est
 * précisément la position où on se met en parlant à un assistant. Trois moments
 * doivent donc s'entendre — le micro s'ouvre, la phrase est prise, la boucle
 * s'arrête — sinon un silence se lit comme une panne.
 *
 * [ToneGenerator] plutôt que des fichiers audio : rien à embarquer dans l'APK,
 * rien à décoder, et le son sort sur le même flux que la voix de synthèse, donc
 * il suit le volume que la personne a déjà réglé pour l'assistant.
 *
 * ⚠️ Le constructeur lève sur certains appareils quand le flux est occupé. Un
 * bip raté n'est pas une raison de casser la conversation : tout est enveloppé,
 * et l'absence de son est le pire qui puisse arriver.
 */
class Earcons {

    private var tones: ToneGenerator? =
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME) }.getOrNull()

    /** Le micro s'ouvre : à vous. */
    fun yourTurn() = play(ToneGenerator.TONE_PROP_BEEP, 90)

    /** Le silence a été détecté, la phrase part se faire transcrire. */
    fun captured() = play(ToneGenerator.TONE_PROP_ACK, 180)

    /** La boucle s'arrête — rien entendu, ou un tour qui a échoué. */
    fun standDown() = play(ToneGenerator.TONE_PROP_NACK, 220)

    private fun play(tone: Int, durationMs: Int) {
        runCatching { tones?.startTone(tone, durationMs) }
    }

    fun release() {
        runCatching { tones?.release() }
        tones = null
    }

    private companion object {
        /** Assez pour s'entendre par-dessus une pièce, pas au point de sursauter. */
        const val VOLUME = 70
    }
}
