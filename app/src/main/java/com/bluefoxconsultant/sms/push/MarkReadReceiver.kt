package com.bluefoxconsultant.sms.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.Service
import com.bluefoxconsultant.sms.data.MarkReadRequest
import kotlinx.serialization.encodeToString
import kotlin.concurrent.thread

/** Swipe-away → POST /mark_read (dismissing on the phone marks it read in Odoo). */
class MarkReadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getIntExtra(Notifier.EXTRA_THREAD_ID, 0)
        if (threadId <= 0) return

        val pending = goAsync()
        val appContext = context.applicationContext
        thread(start = true) {
            try {
                if (!Graph.isReady) Graph.init(appContext)
                if (Graph.tokenStore.tokenFor(Service.SMS) == null) return@thread // logged out → no-op
                val body = Graph.smsApi.json.encodeToString(MarkReadRequest(threadId))
                Graph.smsApi.postJson("/mark_read", body)
            } catch (e: Exception) {
                // best-effort
            } finally {
                pending.finish()
            }
        }
    }
}
