package com.bluefoxconsultant.sms.push

import android.content.Context
import android.widget.Toast
import org.unifiedpush.android.connector.UnifiedPush

/** Picks a UnifiedPush distributor and registers the app after login. */
object PushRegistrar {

    /**
     * @param quiet réaffirmation au démarrage : elle ne dit rien.
     *   Le conseil d'installer ntfy a sa place juste après une connexion, où
     *   l'utilisateur vient d'agir ; répété à chaque lancement, il devient du
     *   bruit qu'on apprend à ignorer.
     */
    fun register(context: Context, quiet: Boolean = false) {
        val app = context.applicationContext
        try {
            val distributors = UnifiedPush.getDistributors(app)
            if (distributors.isEmpty()) {
                if (!quiet) {
                    Toast.makeText(
                        app,
                        "Installez/activez l'app ntfy pour les notifications",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return
            }
            UnifiedPush.saveDistributor(app, distributors.first())
            UnifiedPush.registerApp(app)
        } catch (e: Throwable) {
            // UnifiedPush unavailable — app still works for browse/send.
        }
    }
}
