package com.bluefoxconsultant.sms.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Où [TokenStore] range ce qu'il garde.
 *
 * Deux rôles, et un seul magasin quand tout va bien : le fichier chiffré porte
 * les deux. Ils ne se séparent que lorsque le Keystore refuse de l'ouvrir —
 * voir [ouvrirMagasins].
 */
internal class Magasins(
    /** Jetons, noms d'usager, `state`, vérificateur PKCE, lignes : ce qui ouvre une session. */
    val secrets: SharedPreferences,
    /** Instance, thème, services offerts, types chiffrés du push : rien qui ouvre quoi que ce soit. */
    val reglages: SharedPreferences,
    /** Vrai quand le Keystore a refusé : les secrets ne survivront pas au processus. */
    val degrade: Boolean,
)

/**
 * Les clés qui peuvent vivre en clair. Liste BLANCHE : une clé qu'on ne
 * reconnaît pas est un secret, parce qu'une clé ajoutée demain sans passer ici
 * doit être effacée par erreur plutôt qu'écrite en clair par erreur.
 */
internal fun estCleNonSecrete(cle: String): Boolean =
    cle in CLES_NON_SECRETES || cle.startsWith(PREFIXE_TYPES_CHIFFRES)

private val CLES_NON_SECRETES = setOf("instance_url", "theme_mode", "available_services")

/** Préfixe des types que chaque serveur chiffre ; voir `TokenStore.saveWebpushTypes`. */
internal const val PREFIXE_TYPES_CHIFFRES = "webpush_types_"

internal const val FICHIER_CHIFFRE = "bf_sms_secure"
internal const val FICHIER_ORDINAIRE = "bf_sms_plain"

/**
 * Ouvre le magasin chiffré, et échoue FERMÉ quand il ne s'ouvre pas.
 *
 * 🔴 Le défaut réparé (audit du 2026-09-08, C-M1) : toute exception du
 * Keystore faisait tomber le processus sur `bf_sms_plain`, en clair — jetons,
 * `state`, vérificateur PKCE — sans un mot et sans purge. Un jeton valide
 * pouvait y rester indéfiniment, lisible par quiconque obtient les fichiers de
 * l'app (sauvegarde d'appareil rooté, outil de diagnostic, faille d'une autre
 * surface).
 *
 * Désormais :
 * - **magasin chiffré ouvert** : ce qui traîne dans `bf_sms_plain` y est versé,
 *   sans écraser ce que le magasin chiffré porte déjà (c'est lui que les
 *   processus réussis lisaient), puis le fichier en clair est EFFACÉ ;
 * - **magasin chiffré refusé** : les secrets vivent en mémoire le temps du
 *   processus, les secrets trouvés dans `bf_sms_plain` sont effacés sans être
 *   utilisés, et seuls les réglages non secrets y restent.
 *
 * ⚠️ `commit()` et non `apply()` avant d'effacer la source : `apply()` écrit
 * sur disque plus tard, et un processus tué entre les deux perdrait la session
 * qu'on était en train de déplacer.
 */
internal fun ouvrirMagasins(
    ouvrirChiffre: () -> SharedPreferences,
    ordinaire: SharedPreferences,
    effacerOrdinaire: () -> Unit,
): Magasins {
    val chiffre = try {
        ouvrirChiffre()
    } catch (e: Exception) {
        null
    }

    if (chiffre != null) {
        val restes = ordinaire.all
        if (restes.isNotEmpty()) {
            chiffre.edit(commit = true) {
                restes.forEach { (cle, valeur) ->
                    if (chiffre.contains(cle)) return@forEach
                    when (valeur) {
                        is String -> putString(cle, valeur)
                        is Set<*> -> putStringSet(cle, valeur.filterIsInstance<String>().toMutableSet())
                        is Boolean -> putBoolean(cle, valeur)
                        is Int -> putInt(cle, valeur)
                        is Long -> putLong(cle, valeur)
                        is Float -> putFloat(cle, valeur)
                    }
                }
            }
            ordinaire.edit(commit = true) { clear() }
            effacerOrdinaire()
        }
        return Magasins(secrets = chiffre, reglages = chiffre, degrade = false)
    }

    val aPurger = ordinaire.all.keys.filterNot(::estCleNonSecrete)
    if (aPurger.isNotEmpty()) {
        ordinaire.edit(commit = true) { aPurger.forEach { remove(it) } }
    }
    return Magasins(secrets = MemoirePrefs(), reglages = ordinaire, degrade = true)
}

/** La même décision, branchée sur l'Android Keystore et les fichiers de l'app. */
internal fun ouvrirMagasins(context: Context): Magasins = ouvrirMagasins(
    ouvrirChiffre = {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FICHIER_CHIFFRE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    },
    ordinaire = context.getSharedPreferences(FICHIER_ORDINAIRE, Context.MODE_PRIVATE),
    effacerOrdinaire = { context.deleteSharedPreferences(FICHIER_ORDINAIRE) },
)
