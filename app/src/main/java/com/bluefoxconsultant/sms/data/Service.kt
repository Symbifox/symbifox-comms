package com.bluefoxconsultant.sms.data

/**
 * The two Odoo modules this app speaks to.
 *
 * They are deliberately independent: separate REST surfaces, separate bearer
 * tokens, separate push registrations. An instance may have one, both, or
 * neither installed — [com.bluefoxconsultant.sms.network.ApiClient.ping] is
 * what decides, and a missing module hides its tab instead of failing the
 * login.
 *
 * Keeping them apart is also what lets each Odoo module stay publishable on
 * its own: neither depends on the other.
 */
enum class Service(
    val apiPath: String,
    /** Suffix for this service's keys in [TokenStore]. */
    val key: String,
    val label: String,
) {
    SMS("/bf_sms_archive/mobile/v1", "sms", "Messages"),
    MAIL("/bf_email_management/mobile/v1", "mail", "Courriel"),
    ;

    fun baseUrl(instance: String): String = instance.trimEnd('/') + apiPath
}
