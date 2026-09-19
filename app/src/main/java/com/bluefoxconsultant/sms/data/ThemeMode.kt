package com.bluefoxconsultant.sms.data

import androidx.annotation.StringRes
import com.bluefoxconsultant.sms.R

/**
 * Le thème que porte l'application.
 *
 * ⚠️ Le défaut est **SOMBRE**, pas SYSTEM, et c'est un choix. L'application
 * sert d'abord un agenda et une liste de tâches qu'on consulte au réveil, au
 * lit et en rencontre ; un fond blanc y est agressif, et suivre le système
 * revient à laisser le hasard décider. Qui préfère autre chose le dit une fois
 * dans Réglages ou depuis l'en-tête de l'agenda, et son choix est gardé.
 *
 * ⚠️ Le défaut ne s'applique qu'en l'ABSENCE de valeur enregistrée : une
 * personne qui avait déjà choisi « Système » garde « Système ».
 */
enum class ThemeMode(val stored: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    /** Le suivant dans le cycle du bouton rapide : sombre, clair, système. */
    fun suivant(): ThemeMode = when (this) {
        DARK -> LIGHT
        LIGHT -> SYSTEM
        SYSTEM -> DARK
    }

    @get:StringRes
    val libelleRes: Int
        get() = when (this) {
            SYSTEM -> R.string.theme_system
            LIGHT -> R.string.theme_light
            DARK -> R.string.theme_dark
        }

    companion object {
        val DEFAUT = DARK

        fun from(value: String?): ThemeMode =
            entries.firstOrNull { it.stored == value } ?: DEFAUT
    }
}
