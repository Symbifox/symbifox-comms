package com.bluefoxconsultant.sms.push

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.RegisterPushRequest
import com.bluefoxconsultant.sms.data.RegisterPushResponse
import com.bluefoxconsultant.sms.data.Service
import com.bluefoxconsultant.sms.sip.IncomingCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import kotlin.concurrent.thread
import com.bluefoxconsultant.sms.R

/**
 * UnifiedPush transport: registration endpoint + inbound push messages (ntfy).
 *
 * One endpoint, registered with **both** Odoo modules. They publish to it
 * independently and neither knows the other exists, so payloads are told apart
 * by their `type` field.
 *
 * Depuis la 2.42.0 (connecteur 3.x) : un service et non plus un récepteur. Le
 * récepteur exporté vit dans le connecteur, qui déchiffre avant de nous passer
 * le message. ⚠️ Ne PAS redéclarer de récepteur `connector.MESSAGE` dans le
 * manifeste : celui du connecteur se tait dès qu'il en voit un autre de
 * priorité supérieure, et plus rien ne serait déchiffré.
 */
class BfPushService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val appContext = applicationContext
        if (!Graph.isReady) Graph.init(appContext)
        val store = Graph.tokenStore
        if (!store.isSignedIn) return
        val cles = endpoint.pubKeySet
        thread(start = true) {
            val json = Graph.smsApi.json
            val body = json.encodeToString(
                RegisterPushRequest.serializer(),
                RegisterPushRequest(
                    endpoint = endpoint.url,
                    appVersion = APP_VERSION,
                    p256dh = cles?.pubKey,
                    auth = cles?.auth,
                ),
            )
            // Registered per service: one failing must not skip the other, and
            // a service we hold no token for has nothing to register against.
            Service.entries.forEach { service ->
                if (store.tokenFor(service) == null) return@forEach
                try {
                    val reponse = Graph.apiFor(service).postJson("/register_push", body)
                    val lue = runCatching {
                        json.decodeFromString(RegisterPushResponse.serializer(), reponse)
                    }.getOrDefault(RegisterPushResponse(ok = true))
                    // Un serveur ancien ne répond que `{"ok": true}` : ensemble
                    // vide, et son push en clair continue de passer.
                    store.saveWebpushTypes(service, typesChiffres(lue))
                } catch (e: Exception) {
                    // best-effort; the distributor re-issues the endpoint later.
                    // L'ensemble déjà retenu reste : un échec réseau ne dit rien
                    // de ce que le serveur chiffre.
                }
            }
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        // Nothing to do — app keeps working for browse/send.
    }

    override fun onUnregistered(instance: String) {
        // Nothing to do.
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val appContext = applicationContext
        if (!Graph.isReady) Graph.init(appContext)

        val obj = try {
            Graph.smsApi.json.parseToJsonElement(String(message.content, Charsets.UTF_8)).jsonObject
        } catch (e: Exception) {
            // Y compris un chiffré que le connecteur n'a pas su ouvrir : il
            // nous le passe tel quel, et ce n'est pas du JSON.
            return
        }
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        val typesParServeur = Service.entries.map { Graph.tokenStore.webpushTypesFor(it) }
        if (!accepterPoussee(message.decrypted, type, typesParServeur)) return

        // ⚠️ Sur le fil principal, comme du temps du récepteur. Le connecteur
        // appelle ce service tantôt du fil principal (à la liaison), tantôt de
        // son propre exécuteur ; la sonnerie et les notifications ont été
        // écrites pour le premier et gardent un état qui n'est pas partagé.
        Handler(Looper.getMainLooper()).post { distribuer(appContext, type, obj) }
    }

    private fun distribuer(appContext: Context, type: String?, obj: JsonObject) {
        fun str(key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull
        fun int(key: String): Int? = obj[key]?.jsonPrimitive?.intOrNull

        when (type) {
            // ---- bf_sms_archive ----
            "sms" -> Notifier.show(
                appContext,
                str("title") ?: appContext.getString(R.string.common_new_message),
                str("body").orEmpty(),
                int("thread_id") ?: 0,
                int("message_id") ?: 0,
            )
            "clear" -> Notifier.cancelThread(appContext, int("thread_id") ?: return)
            "clear_all" -> Notifier.cancelAll(appContext)

            // ---- bf_email_management ----
            "mail" -> Notifier.showMail(
                appContext,
                str("title") ?: appContext.getString(R.string.common_new_email),
                str("body").orEmpty(),
                str("preview").orEmpty(),
                // `false` on the batch-summary push, which intOrNull renders as null.
                int("email_id") ?: 0,
                str("thread_key").orEmpty(),
            )
            "mail_clear" -> Notifier.cancelMail(appContext, int("email_id") ?: return)
            "mail_clear_all" -> Notifier.cancelAllMail(appContext)

            // ---- bf_softphone : réveil par push ----
            // Le seul type qui n'annonce pas une nouvelle à lire, mais un
            // appel qui ARRIVE : le PBX attend que le poste se réenregistre
            // pour composer, donc chaque seconde perdue ici est une seconde de
            // silence pour le correspondant.
            "call" -> IncomingCall.wake(
                appContext,
                str("peer").orEmpty(),
                str("name").orEmpty(),
                str("call_id").orEmpty(),
            )

            // ---- bf_claude_chat ----
            // The turn was asked minutes ago and finished without the screen
            // being open; this is what makes "ask and pocket the phone" work.
            "genfox" -> Notifier.showGenfox(
                appContext,
                str("title") ?: appContext.getString(R.string.notif_channel_gen),
                str("body").orEmpty(),
                int("session_id") ?: 0,
            )
        }
    }

    private companion object {
        val APP_VERSION: String = com.bluefoxconsultant.sms.BuildConfig.VERSION_NAME
    }
}
