package com.bluefoxconsultant.sms.sip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * « Refuser » depuis la notification de sonnerie.
 *
 * Un récepteur plutôt qu'une activité : refuser ne doit rien ouvrir, surtout
 * pas par-dessus l'écran de verrouillage.
 */
class CallDeclineReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        IncomingCall.decline(context.applicationContext)
    }
}
