package com.bluefoxconsultant.sms.push

import android.content.Context
import android.widget.Toast
import org.unifiedpush.android.connector.UnifiedPush

/** Picks a UnifiedPush distributor and registers the app after login. */
object PushRegistrar {

    fun register(context: Context) {
        val app = context.applicationContext
        try {
            val distributors = UnifiedPush.getDistributors(app)
            if (distributors.isEmpty()) {
                Toast.makeText(
                    app,
                    "Installez/activez l'app ntfy pour les notifications",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            UnifiedPush.saveDistributor(app, distributors.first())
            UnifiedPush.registerApp(app)
        } catch (e: Throwable) {
            // UnifiedPush unavailable — app still works for browse/send.
        }
    }
}
