package com.bluefoxconsultant.sms.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens an Odoo record in the browser.
 *
 * `/odoo/<model>/<id>` is the per-model form route of Odoo 18 — the one shape
 * that works without knowing an action id. A wrong link would NOT 404: the
 * router runs client-side, so the page loads and then fails, which is why the
 * generic form is preferable to anything cleverer.
 *
 * The instance the app is signed into is the one opened, so a phone pointed at
 * a staging server does not send its user to production.
 */
object OdooLinks {

    fun recordUrl(instance: String, model: String, id: Int): String =
        "${instance.trimEnd('/')}/odoo/$model/$id"

    /** True when the browser actually opened. */
    fun openRecord(context: Context, model: String, id: Int): Boolean {
        val instance = Graph.tokenStore.instanceUrl ?: return false
        if (model.isBlank() || id <= 0) return false
        return try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, Uri.parse(recordUrl(instance, model, id)))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}
