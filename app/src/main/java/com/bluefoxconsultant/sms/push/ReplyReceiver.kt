package com.bluefoxconsultant.sms.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.Service
import com.bluefoxconsultant.sms.data.SendThreadRequest
import kotlinx.serialization.encodeToString
import kotlin.concurrent.thread

/** Handles the notification quick-reply RemoteInput → POST /send on a background thread. */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)
            ?.toString()
            .orEmpty()
            .trim()
        val threadId = intent.getIntExtra(Notifier.EXTRA_THREAD_ID, 0)
        val notifId = intent.getIntExtra(Notifier.EXTRA_NOTIF_ID, 0)
        if (reply.isBlank() || threadId <= 0) return

        val pending = goAsync()
        val appContext = context.applicationContext
        thread(start = true) {
            try {
                if (!Graph.isReady) Graph.init(appContext)
                if (Graph.tokenStore.tokenFor(Service.SMS) == null) return@thread // logged out → no-op
                val body = Graph.smsApi.json.encodeToString(SendThreadRequest(threadId, reply))
                Graph.smsApi.postJson("/send", body)
                Notifier.showSent(appContext, notifId)
            } catch (e: Exception) {
                // Leave the notification in place on failure.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val KEY_REPLY = "key_reply"
    }
}
