package com.bluefoxconsultant.sms.ui.login

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.Service
import com.bluefoxconsultant.sms.push.PushRegistrar
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Web-login capture, chained across both services.
 *
 * The app never sees a password: it opens the Odoo web login in a Chrome
 * Custom Tab and trades the returned one-time code for a durable token. With
 * two modules that means two legs — but only the *first* one prompts, because
 * the Odoo session cookie is already set when the second `auth/start` runs
 * with `auth="user"`. The user experiences one login and ends up with a token
 * for each tab.
 *
 * A leg can fail on its own (module absent, no mailbox on that account, user
 * not in the SMS group). That disables its tab; it must never abort the other
 * leg, or one missing module would lock the user out of the app entirely.
 */
class AuthViewModel : ViewModel() {

    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Non-null when one half signed in and the other refused — shown as a note. */
    var partial by mutableStateOf<String?>(null)
        private set

    /** Start the chain: probe first so we only open legs that can succeed. */
    fun startLogin(context: Context) {
        val instance = Graph.tokenStore.instanceUrl ?: return
        error = null
        partial = null
        loading = true
        val appContext = context.applicationContext
        viewModelScope.launch {
            val available = probe(instance)
            loading = false
            if (available.isEmpty()) {
                error = "Aucun module compatible sur ce serveur."
                return@launch
            }
            Graph.tokenStore.saveAvailable(available)
            openLeg(appContext, available.first())
        }
    }

    /**
     * Probe both modules' public `/ping`. Runs off the main thread; a module
     * that doesn't answer is simply absent.
     */
    private suspend fun probe(instance: String): Set<Service> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Service.entries.filter { Graph.apiFor(it).ping(instance) }.toSet()
        }

    /**
     * Connect one service on an app that is already signed in to the other.
     *
     * Without this, an installation upgraded from a single-service build could
     * never discover the second half: [startLogin] is the only thing that
     * probes, and it only runs when there is no token at all. Someone who
     * already had SMS went straight to the tab bar forever.
     */
    fun connectService(context: Context, service: Service) {
        error = null
        partial = null
        openLeg(context, service)
    }

    private fun openLeg(context: Context, service: Service) {
        val instance = Graph.tokenStore.instanceUrl ?: return
        val state = randomState()
        Graph.tokenStore.savePendingLeg(service, state)

        val url = service.baseUrl(instance) +
            "/auth/start?redirect=" + Uri.encode(REDIRECT) +
            "&state=" + Uri.encode(state)

        try {
            CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, Uri.parse(url))
        } catch (e: Exception) {
            error = "Impossible d'ouvrir le navigateur."
        }
    }

    /** Handle a `com.bluefoxconsultant.sms://auth?code=|error=&state=` redirect. */
    fun handleRedirect(context: Context, rawUri: String) {
        if (loading) return
        val uri = try { Uri.parse(rawUri) } catch (e: Exception) { null }
        val code = uri?.getQueryParameter("code")
        val legError = uri?.getQueryParameter("error")
        val state = uri?.getQueryParameter("state")
        val expected = Graph.tokenStore.pendingState
        val service = Graph.tokenStore.pendingService

        // Anti-injection: reject unless state matches the one we sent for this leg.
        if (state.isNullOrBlank() || expected.isNullOrBlank() ||
            state != expected || service == null
        ) {
            Graph.tokenStore.clearPendingState()
            error = "Connexion échouée, réessayez."
            return
        }
        Graph.tokenStore.clearPendingState()

        val appContext = context.applicationContext
        if (code.isNullOrBlank()) {
            // This half said no. Note why, then carry on to the next leg.
            noteLegRefused(service, legError)
            continueChain(appContext, after = service)
            return
        }

        loading = true
        error = null
        viewModelScope.launch {
            try {
                when (service) {
                    Service.SMS -> {
                        val resp = Graph.sms.exchange(code)
                        if (resp.token.isBlank()) throw IllegalStateException("empty token")
                        Graph.tokenStore.saveLines(resp.lines)
                        Graph.tokenStore.saveToken(service, resp.token, resp.userName)
                    }
                    Service.MAIL -> {
                        val resp = Graph.mail.exchange(code)
                        if (resp.token.isBlank()) throw IllegalStateException("empty token")
                        Graph.tokenStore.saveToken(service, resp.token, resp.config.userName)
                    }
                }
                PushRegistrar.register(appContext)
            } catch (e: Exception) {
                noteLegRefused(service, null)
            } finally {
                loading = false
                continueChain(appContext, after = service)
            }
        }
    }

    /** Open the next advertised leg we don't have a token for yet. */
    private fun continueChain(context: Context, after: Service) {
        val store = Graph.tokenStore
        val next = store.available
            .filter { it != after && store.tokenFor(it) == null }
            .minByOrNull { it.ordinal }
        if (next != null) {
            openLeg(context, next)
            return
        }
        // Chain done. Saving a token already flipped navigation; if nothing was
        // obtained at all, say so instead of leaving the button looking inert.
        if (!store.isSignedIn && error == null) {
            error = partial ?: "Connexion échouée, réessayez."
            partial = null
        }
    }

    private fun noteLegRefused(service: Service, reason: String?) {
        val why = when (reason) {
            "no_mailbox" -> "aucune boîte courriel n'est configurée sur ce compte"
            "no_access" -> "ce compte n'a pas accès à ce module"
            else -> "le serveur a refusé la connexion"
        }
        partial = "${service.label} indisponible : $why."
    }

    /** Forget the instance so the app returns to the Instance screen. */
    fun changeServer() {
        error = null
        partial = null
        Graph.tokenStore.clearPendingState()
        Graph.tokenStore.clearInstance()
    }

    private fun randomState(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        const val REDIRECT = "com.bluefoxconsultant.sms://auth"
    }
}
