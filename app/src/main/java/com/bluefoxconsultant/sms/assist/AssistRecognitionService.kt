package com.bluefoxconsultant.sms.assist

import android.content.Intent
import android.speech.RecognitionService

/**
 * Service de reconnaissance minimal, exigé par la déclaration de l'assistant.
 *
 * ⚠️ `voice-interaction-service` REFUSE de s'enregistrer sans un
 * `recognitionService` résolvable — et l'échec est muet : l'app n'apparaît
 * simplement jamais dans le sélecteur d'assistant, sans un mot dans les
 * journaux. Il faut donc une classe, même si elle ne reconnaît rien.
 *
 * GenFox ne fait pas de reconnaissance ici : la dictée passe déjà par Whisper
 * côté serveur, avec un modèle bien meilleur que ce qu'un appareil offrirait,
 * et un second chemin d'écoute n'apporterait qu'une permission de plus à
 * justifier. Toute demande est donc refusée proprement.
 */
class AssistRecognitionService : RecognitionService() {

    override fun onStartListening(intent: Intent?, listener: Callback?) {
        listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
