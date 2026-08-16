package com.bluefoxconsultant.sms.push

import android.content.Context
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.RegisterPushRequest
import com.bluefoxconsultant.sms.data.Service
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.unifiedpush.android.connector.MessagingReceiver
import kotlin.concurrent.thread

/**
 * UnifiedPush transport: registration endpoint + inbound push messages (ntfy).
 *
 * One endpoint, registered with **both** Odoo modules. They publish to it
 * independently and neither knows the other exists, so payloads are told apart
 * by their `type` field.
 */
class PushReceiver : MessagingReceiver() {

    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        val appContext = context.applicationContext
        if (!Graph.isReady) Graph.init(appContext)
        val store = Graph.tokenStore
        if (!store.isSignedIn) return
        thread(start = true) {
            val body = Graph.smsApi.json.encodeToString(
                RegisterPushRequest(endpoint = endpoint, appVersion = APP_VERSION),
            )
            // Registered per service: one failing must not skip the other, and
            // a service we hold no token for has nothing to register against.
            Service.entries.forEach { service ->
                if (store.tokenFor(service) == null) return@forEach
                try {
                    Graph.apiFor(service).postJson("/register_push", body)
                } catch (e: Exception) {
                    // best-effort; the distributor re-issues the endpoint later.
                }
            }
        }
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        // Nothing to do — app keeps working for browse/send.
    }

    override fun onUnregistered(context: Context, instance: String) {
        // Nothing to do.
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        val appContext = context.applicationContext
        if (!Graph.isReady) Graph.init(appContext)

        val text = String(message, Charsets.UTF_8)
        val obj = try {
            Graph.smsApi.json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return
        }

        fun str(key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull
        fun int(key: String): Int? = obj[key]?.jsonPrimitive?.intOrNull

        when (str("type")) {
            // ---- bf_sms_archive ----
            "sms" -> Notifier.show(
                appContext,
                str("title") ?: "Nouveau message",
                str("body").orEmpty(),
                int("thread_id") ?: 0,
                int("message_id") ?: 0,
            )
            "clear" -> Notifier.cancelThread(appContext, int("thread_id") ?: return)
            "clear_all" -> Notifier.cancelAll(appContext)

            // ---- bf_email_management ----
            "mail" -> Notifier.showMail(
                appContext,
                str("title") ?: "Nouveau courriel",
                str("body").orEmpty(),
                str("preview").orEmpty(),
                // `false` on the batch-summary push, which intOrNull renders as null.
                int("email_id") ?: 0,
                str("thread_key").orEmpty(),
            )
            "mail_clear" -> Notifier.cancelMail(appContext, int("email_id") ?: return)
            "mail_clear_all" -> Notifier.cancelAllMail(appContext)
        }
    }

    private companion object {
        const val APP_VERSION = "2.0.0"
    }
}
