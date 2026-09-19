package com.bluefoxconsultant.sms.ui

import java.net.URLEncoder

/**
 * Un argument de route Navigation, encodé UNE fois.
 *
 * 🔴 Le défaut réparé : les clés de fil étaient encodées avec `URLEncoder`
 * puis décodées avec `URLDecoder` à l'arrivée. Or Navigation décode déjà
 * (`Uri.decode`) les arguments de chemin : le second décodage transformait un
 * « + » (courant dans un Message-ID Gmail) en espace, et le fil ne s'ouvrait
 * plus. On encode ici, on ne décode nulle part.
 *
 * ⚠️ `URLEncoder` écrit l'espace « + », que `Uri.decode` ne rend PAS en
 * espace : on le remplace par « %20 », que les deux comprennent. Pure : c'est
 * elle que le banc éprouve, contre `URLDecoder` joué comme Navigation.
 */
fun argumentDeRoute(valeur: String): String =
    URLEncoder.encode(valeur, "UTF-8").replace("+", "%20")
