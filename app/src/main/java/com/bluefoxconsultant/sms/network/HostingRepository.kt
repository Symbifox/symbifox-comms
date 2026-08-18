package com.bluefoxconsultant.sms.network

import com.bluefoxconsultant.sms.data.HostingAlerts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString

/**
 * L'hébergement, côté réseau — `bf_hosting_mobile`.
 *
 * En lecture seule, sans exception : rien ici ne redémarre, n'acquitte ni ne
 * modifie quoi que ce soit. Une bannière qui offrirait un bouton d'action
 * depuis un téléphone est le meilleur moyen de relancer une pile au mauvais
 * moment, et le bureau est à deux clics quand il faut vraiment agir.
 *
 * Monte sur le jeton Messages, comme le téléphone : l'hébergement est une
 * capacité de la session en place, pas un compte de plus à ouvrir.
 */
class HostingRepository(private val api: ApiClient) {

    /** Le module répond-il sur cette instance ? */
    suspend fun available(instance: String): Boolean = withContext(Dispatchers.IO) {
        api.ping(instance)
    }

    suspend fun alerts(): HostingAlerts = withContext(Dispatchers.IO) {
        api.json.decodeFromString(api.get("/alerts"))
    }
}
