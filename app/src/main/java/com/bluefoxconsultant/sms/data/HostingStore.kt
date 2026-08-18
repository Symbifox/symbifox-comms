package com.bluefoxconsultant.sms.data

import android.content.Context
import com.bluefoxconsultant.sms.network.HostingRepository
import com.bluefoxconsultant.sms.push.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * L'état du parc hébergé, relu tant que l'app tourne.
 *
 * Une interrogation périodique plutôt qu'une poussée : le module d'hébergement
 * publie déjà ses incidents sur un sujet ntfy à lui, et lui greffer une
 * poussée par appareil demanderait un registre d'abonnés de plus pour la même
 * information. Relire coûte une requête par cycle, et la cadence se resserre
 * quand quelque chose est à terre — c'est le seul moment où l'on regarde.
 *
 * ⚠️ La boucle s'ARRÊTE dès que le serveur répond `enabled: false`. Sans
 * hébergement, il n'y a rien à surveiller : continuer reviendrait à interroger
 * toutes les minutes, à vide, sur le forfait de données de quelqu'un.
 *
 * ⚠️ La notification, elle, survit à la boucle : elle est *ongoing*, donc elle
 * reste dans le tiroir quand l'app est fermée, jusqu'à ce qu'un prochain cycle
 * trouve le parc en ordre et la retire. Corollaire assumé : une panne qui
 * survient alors que l'app n'a jamais tourné n'apparaît pas ici — c'est le
 * sujet ntfy du module qui la porte, comme aujourd'hui.
 */
class HostingStore(private val repo: HostingRepository) {

    private val _state = MutableStateFlow(HostingAlerts())
    val state: StateFlow<HostingAlerts> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /**
     * Retenu pour pouvoir retirer la notification à la déconnexion, d'où qu'on
     * la demande. `applicationContext` seulement : la boucle vit plus longtemps
     * que l'écran qui l'a lancée, et retenir une Activity la ferait fuir.
     */
    private var app: Context? = null

    fun start(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext.also { this.app = it }
        job = scope.launch {
            while (isActive) {
                // Un module absent répond 404, un appareil non autorisé 401.
                // Les deux veulent dire « pas d'hébergement ici » : on garde le
                // dernier état connu plutôt que d'éteindre la bannière sur un
                // simple hoquet de réseau.
                val next = runCatching { repo.alerts() }.getOrNull()
                if (next != null) {
                    _state.value = next
                    Notifier.showHosting(app, next)
                    if (!next.enabled) return@launch
                }
                delay(if (_state.value.isDown) BUSY_MS else IDLE_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Après une déconnexion ou un changement d'instance, tout est à reprendre.
     *
     * ⚠️ La notification est *ongoing* : sans ce retrait explicite, l'avis de
     * panne du parc précédent resterait dans le tiroir de la personne suivante,
     * sans plus rien pour le mettre à jour ni le faire disparaître.
     */
    fun invalidate() {
        stop()
        _state.value = HostingAlerts()
        app?.let { Notifier.clearHosting(it) }
    }

    private companion object {
        const val IDLE_MS = 5 * 60_000L
        const val BUSY_MS = 90_000L
    }
}
