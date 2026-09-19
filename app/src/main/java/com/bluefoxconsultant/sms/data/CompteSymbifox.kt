package com.bluefoxconsultant.sms.data

import android.accounts.AccountManager
import android.content.Context

/**
 * Le compte Symbifox de l'appareil, quand Symbifox Compte est installé.
 *
 * ⚠️ **Ce fichier est IDENTIQUE dans les applications sœurs** (Pastilles,
 * Chronomètre, Tokens), au nom de paquet près. Il recopie les constantes du
 * `Contrat` publié de Compte, qui sont de l'API et ne changent plus.
 *
 * 🔴 **On lui demande l'ADRESSE de l'instance et la marque, pas un jeton.**
 * Comms garde son propre appariement, avec son PKCE par volet, ses deux
 * enregistrements d'appareil et ses lignes à révoquer. Taper une adresse est
 * une corvée et une source de fautes de frappe, alors que l'appareil sait déjà
 * pour quelle instance il travaille : c'est ce que le compte porte.
 *
 * ⚠️ Comms est pourtant la seule des cinq pour qui Compte sait courtier un
 * vrai jeton : son `Contrat.MODULES` ne connaît que `bf_email_management` et
 * `bf_sms_archive`, c'est-à-dire exactement les deux volets de cette
 * application. Le faire changerait le parcours de connexion, pas son
 * apparence, et c'est hors du périmètre de #25765.
 *
 * ⚠️ **Aucune permission déclarée, et ce n'est pas un oubli.** Depuis Android 8,
 * une application signée de la même clé que l'authentificateur voit le compte
 * et lit ses données sans `GET_ACCOUNTS`. Toute la flotte porte la même clé
 * Blue Fox. Demander la permission ferait lire l'application plus gourmande
 * qu'elle n'est, sans rien lui accorder de plus.
 */
object CompteSymbifox {

    /** Le type de compte, recopié du contrat publié de Symbifox Compte. */
    private const val TYPE_COMPTE = "com.bluefoxconsultant.symbifox"
    private const val CLE_INSTANCE = "instance"
    private const val CLE_MARQUE = "marque"
    private const val CLE_COULEUR = "couleur"

    data class Trouve(
        val nom: String,
        val instance: String,
        val marque: String?,
        val couleur: String?,
    )

    /**
     * Les comptes Symbifox de l'appareil, avec leur instance.
     *
     * Rend une liste vide quand Compte n'est pas installé, quand aucun compte
     * n'a été ajouté, ou quand la lecture est refusée. Les trois cas se
     * traitent pareil côté écran : on retombe sur la saisie manuelle.
     *
     * ⚠️ Un compte sans adresse d'instance est écarté plutôt que rendu avec un
     * champ vide : il ferait un bouton qui n'appareille rien.
     */
    fun comptes(contexte: Context): List<Trouve> = runCatching {
        val gestionnaire = AccountManager.get(contexte)
        gestionnaire.getAccountsByType(TYPE_COMPTE).mapNotNull { compte ->
            val instance = gestionnaire.getUserData(compte, CLE_INSTANCE)
                ?.trim()?.trimEnd('/')
            if (instance.isNullOrBlank()) return@mapNotNull null
            Trouve(
                nom = compte.name,
                instance = instance,
                marque = gestionnaire.getUserData(compte, CLE_MARQUE),
                couleur = gestionnaire.getUserData(compte, CLE_COULEUR),
            )
        }
    }.getOrDefault(emptyList())
}
