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

    /**
     * [kinds] restreint ce que le serveur collecte ET résume.
     *
     * Le tri se fait là-bas, pas ici : « 2 services hors ligne, 1 disque
     * plein » est une phrase accordée en français, et la filtrer côté app
     * obligerait à réécrire cet accord en Kotlin — deux endroits à corriger le
     * jour où la formulation change. Une liste vide vaut « tout », ce que le
     * serveur comprend aussi comme l'absence du paramètre.
     */
    suspend fun alerts(kinds: List<String> = emptyList()): HostingAlerts =
        withContext(Dispatchers.IO) {
            val query = if (kinds.isEmpty()) "" else "?kinds=" + kinds.joinToString(",")
            api.json.decodeFromString(api.get("/alerts$query"))
        }
}
