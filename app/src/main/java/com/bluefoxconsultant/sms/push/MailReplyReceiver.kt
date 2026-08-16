package com.bluefoxconsultant.sms.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.MailReplyRequest
import com.bluefoxconsultant.sms.data.Service
import kotlinx.serialization.encodeToString
import kotlin.concurrent.thread

/**
 * Notification quick-reply on an email → `POST /reply` in `reply` mode.
 *
 * Recipients and the quoted original are left to the server: it already knows
 * who the message came from, and re-deriving that from a notification payload
 * is how a reply ends up addressed to the wrong person.
 */
class MailReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)
            ?.toString()
            .orEmpty()
            .trim()
        val emailId = intent.getIntExtra(Notifier.EXTRA_EMAIL_ID, 0)
        val notifId = intent.getIntExtra(Notifier.EXTRA_NOTIF_ID, 0)
        if (reply.isBlank() || emailId <= 0) return

        val pending = goAsync()
        val appContext = context.applicationContext
        thread(start = true) {
            try {
                if (!Graph.isReady) Graph.init(appContext)
                if (Graph.tokenStore.tokenFor(Service.MAIL) == null) return@thread
                val body = Graph.mailApi.json.encodeToString(
                    MailReplyRequest(emailId = emailId, mode = "reply", body = reply),
                )
                Graph.mailApi.postJson("/reply", body)
                Notifier.showMailSent(appContext, notifId)
            } catch (e: Exception) {
                // Leave the notification in place on failure.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val KEY_REPLY = "key_mail_reply"
    }
}
