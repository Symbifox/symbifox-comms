package com.bluefoxconsultant.sms.push

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.ResolvedDistributor
import com.bluefoxconsultant.sms.R

/**
 * Ce que l'app fait du distributeur, une fois tout su. Voir [deciderDistributeur].
 */
sealed interface DecisionDistributeur {
    data class Utiliser(val paquet: String) : DecisionDistributeur
    data class Demander(val candidats: List<String>) : DecisionDistributeur
    data object Aucun : DecisionDistributeur
}

/**
 * Quel distributeur UnifiedPush utiliser. Pure : c'est elle que le banc éprouve.
 *
 * 🔴 Le défaut réparé (audit du 2026-09-08, C-M3) : `distributors.first()`.
 * Avec ntfy et un second distributeur installés, l'app prenait celui que le
 * gestionnaire de paquets listait d'abord — un ordre que personne n'a choisi,
 * et qui peut changer d'une mise à jour d'Android à l'autre.
 *
 * Dans l'ordre : celui déjà retenu s'il est encore installé ; celui que la
 * personne a désigné par défaut pour toutes ses apps ; le seul installé ; et
 * sinon on DEMANDE. Jamais de choix d'office entre plusieurs.
 *
 * @param courant le distributeur enregistré par le connecteur, ou `null`
 * @param parDefaut le distributeur par défaut du système, ou `null` s'il n'y en
 *   a pas (plusieurs sans défaut, ou aucun)
 * @param installes les distributeurs présents, sans l'app elle-même
 */
fun deciderDistributeur(
    courant: String?,
    parDefaut: String?,
    installes: List<String>,
): DecisionDistributeur = when {
    courant != null && courant in installes -> DecisionDistributeur.Utiliser(courant)
    parDefaut != null && parDefaut in installes -> DecisionDistributeur.Utiliser(parDefaut)
    installes.size == 1 -> DecisionDistributeur.Utiliser(installes.single())
    installes.isEmpty() -> DecisionDistributeur.Aucun
    else -> DecisionDistributeur.Demander(installes.distinct())
}

/** Picks a UnifiedPush distributor and registers the app after login. */
object PushRegistrar {

    /**
     * Les distributeurs entre lesquels la personne doit choisir, ou `null`.
     *
     * Posé ici et montré par l'accueil : l'inscription part d'endroits qui n'ont
     * pas d'écran (l'application au démarrage, la fin d'un appariement).
     */
    private val _aChoisir = MutableStateFlow<List<String>?>(null)
    val aChoisir: StateFlow<List<String>?> = _aChoisir.asStateFlow()

    /**
     * @param quiet réaffirmation au démarrage : elle ne dit rien.
     *   Le conseil d'installer ntfy a sa place juste après une connexion, où
     *   l'utilisateur vient d'agir ; répété à chaque lancement, il devient du
     *   bruit qu'on apprend à ignorer. Même règle pour le choix entre plusieurs
     *   distributeurs : il se pose à la connexion, et se reprend dans Réglages.
     */
    fun register(context: Context, quiet: Boolean = false) {
        val app = context.applicationContext
        try {
            val installes = distributeursInstalles(app)
            val courant = distributeurCourant(app)
            // Le défaut du système n'est consulté que s'il faut : c'est une
            // résolution d'activité, et le distributeur retenu suffit presque
            // toujours.
            val parDefaut = if (courant == null) {
                (UnifiedPush.resolveDefaultDistributor(app) as? ResolvedDistributor.Found)
                    ?.packageName
            } else {
                null
            }
            when (val decision = deciderDistributeur(courant, parDefaut, installes)) {
                is DecisionDistributeur.Utiliser -> inscrire(app, decision.paquet, courant)
                is DecisionDistributeur.Demander -> if (!quiet) _aChoisir.value = decision.candidats
                DecisionDistributeur.Aucun -> if (!quiet) {
                    Toast.makeText(
                        app,
                        app.getString(R.string.push_install_ntfy),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        } catch (e: Throwable) {
            // UnifiedPush unavailable — app still works for browse/send.
        }
    }

    /**
     * La personne a choisi, depuis le dialogue ou les réglages.
     *
     * Changer de distributeur fait désinscrire l'ancien par le connecteur ; le
     * nouveau renvoie un endpoint, et [BfPushService.onNewEndpoint] l'inscrit
     * auprès des deux serveurs avec les mêmes clés.
     */
    fun utiliser(context: Context, paquet: String) {
        val app = context.applicationContext
        _aChoisir.value = null
        try {
            inscrire(app, paquet, distributeurCourant(app))
        } catch (e: Throwable) {
            // Même tolérance qu'à l'inscription : l'app reste utilisable.
        }
    }

    /** Fermer le dialogue sans choisir : la question revient à la prochaine connexion. */
    fun renoncer() {
        _aChoisir.value = null
    }

    /** Les distributeurs installés, sans l'app elle-même. */
    fun distributeursInstalles(context: Context): List<String> = runCatching {
        UnifiedPush.getDistributors(context).filter { it != context.packageName }
    }.getOrDefault(emptyList())

    /** Celui que le connecteur a retenu, s'il est encore installé. */
    fun distributeurCourant(context: Context): String? = runCatching {
        UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context)
    }.getOrNull()

    private fun inscrire(app: Context, paquet: String, courant: String?) {
        if (paquet != courant) UnifiedPush.saveDistributor(app, paquet)
        // Réaffirmer à chaque fois : le distributeur rend le MÊME endpoint s'il
        // en a déjà un, et le connecteur génère les clés WebPush s'il n'en a pas
        // encore — c'est le cas au premier lancement après la 2.41.0.
        UnifiedPush.register(app)
    }
}
