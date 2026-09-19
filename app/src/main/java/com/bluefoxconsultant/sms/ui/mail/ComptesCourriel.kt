package com.bluefoxconsultant.sms.ui.mail

import com.bluefoxconsultant.sms.data.MailAccount

/**
 * Une couleur et un nom court par boîte, pour les reconnaître dans la liste et
 * les filtrer (#25734).
 *
 * Pur, pour que le banc éprouve l'attribution sans téléphone.
 */

/**
 * Les six teintes de l'avis au bureau (`$o-bf-email-popup-colors` de
 * bf_email_management), dans l'ordre du champ. Une boîte sans couleur choisie
 * en reçoit une d'ici : la même palette que le bureau, pour que l'œil
 * n'apprenne qu'un jeu de couleurs.
 */
internal val PALETTE_COMPTES = listOf(
    "#29ABE2", "#64748B", "#16A34A", "#7C3AED", "#D97706", "#E11D48",
)

private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

/**
 * La couleur de chaque boîte, par identifiant de compte.
 *
 * Celle que le serveur donne d'abord. Les autres reçoivent, dans l'ordre des
 * comptes, la première teinte de la palette qu'aucune boîte ne porte encore :
 * deux boîtes de la même couleur ne se distinguent plus, ce qui est tout
 * l'objet. Au-delà de six boîtes, la palette recommence.
 */
fun couleursDesComptes(comptes: List<MailAccount>): Map<Int, String> {
    val choisies = comptes.associate { it.id to it.color.takeIf { c -> HEX.matches(c) }?.uppercase() }
    val prises = choisies.values.filterNotNull().toMutableSet()
    var rang = 0
    return comptes.associate { compte ->
        val couleur = choisies[compte.id] ?: run {
            val libre = PALETTE_COMPTES.firstOrNull { it !in prises }
            val attribuee = libre ?: PALETTE_COMPTES[rang++ % PALETTE_COMPTES.size]
            prises += attribuee
            attribuee
        }
        compte.id to couleur
    }
}

/**
 * Le nom court d'une boîte pour une pastille : « Perso — jane@exemple.test »
 * se lit « Perso ». Sans tiret, le nom entier, sinon l'adresse.
 */
fun libelleCompte(compte: MailAccount): String {
    val nom = compte.name.trim().ifBlank { compte.login.trim() }
    return nom.substringBefore(" — ").substringBefore(" - ").trim().ifBlank { nom }
}
