package com.bluefoxconsultant.sms.assist

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.bluefoxconsultant.sms.ui.MainActivity

/**
 * GenFox comme assistant du système.
 *
 * Une fois choisi dans « Applications par défaut → Assistant numérique », le
 * geste d'assistance — appui long sur le bouton d'accueil, balayage depuis un
 * coin, bouton dédié — ouvre GenFox au lieu de l'assistant du fabricant.
 *
 * ⚠️ Un simple filtre d'intention `ACTION_ASSIST` ne suffit PAS : le sélecteur
 * moderne ne liste que les applications qui déclarent un
 * `VoiceInteractionService` avec `supportsAssist`. C'est la raison de tout cet
 * échafaudage pour une action qui, au fond, ouvre un écran.
 *
 * La session ne dessine rien : elle lance l'app et se retire. Une surface
 * flottante par-dessus l'écran courant serait le vrai luxe d'un assistant,
 * mais elle demanderait de reconstruire tout le fil de conversation dans une
 * fenêtre système — pour un bénéfice mince tant que l'app s'ouvre en un éclair.
 */
class AssistService : VoiceInteractionService()

class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AssistSession(this)
}

private class AssistSession(context: android.content.Context) :
    VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // NEW_TASK : on démarre depuis un service, il n'y a pas de pile
        // d'activités à laquelle se raccrocher. SINGLE_TOP évite d'empiler une
        // seconde instance quand l'app est déjà devant.
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_ASSIST, true)
        }
        runCatching { context.startActivity(intent) }
        // Se retirer tout de suite : la session système ne sert que de relais,
        // et la laisser ouverte figerait un panneau vide par-dessus l'app.
        hide()
    }

    companion object {
        const val EXTRA_ASSIST = "bf_assist"
    }
}
