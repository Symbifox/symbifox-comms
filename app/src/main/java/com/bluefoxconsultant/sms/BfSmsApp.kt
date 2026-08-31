package com.bluefoxconsultant.sms

import android.app.Application
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.push.PushRegistrar

class BfSmsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        // 🔴 L'inscription au distributeur ne se faisait QU'À LA CONNEXION.
        // Perdue une fois — app ntfy réinstallée, données effacées, distributeur
        // changé, mise à jour d'Android — elle ne revenait jamais : l'endpoint
        // restait vide côté serveur et le téléphone ne recevait plus rien, sans
        // erreur nulle part. C'est exactement l'état trouvé le 2026-08-30, sur
        // les DEUX modules à la fois (textos et courriel), et c'est ce qui
        // rendrait le réveil par push muet.
        //
        // Réaffirmer à chaque démarrage est sans coût : le distributeur rend le
        // MÊME endpoint s'il en a déjà un, et onNewEndpoint le réenregistre.
        if (Graph.tokenStore.isSignedIn) PushRegistrar.register(this, quiet = true)
    }
}
