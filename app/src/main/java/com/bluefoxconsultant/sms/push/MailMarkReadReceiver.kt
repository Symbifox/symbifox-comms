package com.bluefoxconsultant.sms.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.MailIdsRequest
import com.bluefoxconsultant.sms.data.Service
import kotlinx.serialization.encodeToString
import kotlin.concurrent.thread

/** Swipe-away → `POST /mark_read` (dismissing on the phone marks it read in Odoo). */
class MailMarkReadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val emailId = intent.getIntExtra(Notifier.EXTRA_EMAIL_ID, 0)
        if (emailId <= 0) return

        val pending = goAsync()
        val appContext = context.applicationContext
        thread(start = true) {
            try {
                if (!Graph.isReady) Graph.init(appContext)
                if (Graph.tokenStore.tokenFor(Service.MAIL) == null) return@thread
                val body = Graph.mailApi.json.encodeToString(MailIdsRequest(listOf(emailId)))
                Graph.mailApi.postJson("/mark_read", body)
            } catch (e: Exception) {
                // best-effort
            } finally {
                pending.finish()
            }
        }
    }
}
