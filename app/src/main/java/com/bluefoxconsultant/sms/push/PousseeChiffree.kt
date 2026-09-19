package com.bluefoxconsultant.sms.push

import com.bluefoxconsultant.sms.data.RegisterPushResponse

/**
 * Ce qu'on retient de la réponse d'un serveur à `/register_push`.
 *
 * ⚠️ Seulement si `webpush` est vrai. Un serveur qui n'a pas enregistré de
 * clés pour cet appareil (clés absentes, ou refusées) pousse en clair ; retenir
 * ses types quand même ferait jeter chacune de ses notifications, en silence.
 */
fun typesChiffres(reponse: RegisterPushResponse): Set<String> =
    if (reponse.webpush) reponse.webpushTypes.filter { it.isNotBlank() }.toSet() else emptySet()

/**
 * Faut-il traiter cette poussée ? Pure : c'est elle que le banc éprouve.
 *
 * 🔴 Le défaut réparé (audit du 2026-09-08, C-M3) : le push arrivait en clair,
 * et qui connaissait l'endpoint — l'URL ntfy, visible du distributeur, de ses
 * journaux, de quiconque la lit au passage — forgeait une notification, une
 * réponse rapide vers un autre fil, ou une sonnerie d'appel plein écran.
 *
 * Désormais le serveur chiffre en RFC 8291 avec les clés de l'appareil, et le
 * connecteur dit s'il a pu déchiffrer. Un message déchiffré vient forcément de
 * qui détient ces clés, donc du serveur : il passe. Un message en clair ne
 * passe que si AUCUN serveur connecté n'a annoncé chiffrer ce type — c'est le
 * cas d'un serveur ancien, qui continue de fonctionner comme avant. Un type
 * qu'un serveur chiffre et qui arrive en clair est, par construction, forgé.
 *
 * @param typesParServeur un ensemble par service connecté ; vide pour un
 *   serveur ancien.
 */
fun accepterPoussee(
    dechiffree: Boolean,
    type: String?,
    typesParServeur: Collection<Set<String>>,
): Boolean {
    if (dechiffree) return true
    if (type.isNullOrBlank()) return false
    return typesParServeur.none { type in it }
}
