package com.bluefoxconsultant.sms.ui.agenda

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Les dates de l'agenda et des tâches, dans la langue du téléphone (Q-M8).
 *
 * Tout passait par `Locale.forLanguageTag("fr-CA")` et des motifs figés
 * (« EEEE d MMMM ») : sur un téléphone en anglais, l'écran écrivait « mardi 15
 * septembre » au milieu de libellés anglais. Le motif vient maintenant
 * d'Android, qui connaît l'ordre propre à chaque langue : « mardi 15
 * septembre » en français, « Tuesday, September 15 » en anglais.
 *
 * ⚠️ On ne demande à Android que la DATE, jamais l'heure. Pour l'heure, le
 * français canadien donne « 14 h 30 », alors que la grille, la liste et les
 * fiches affichent « 14:30 » : l'heure reste un motif numérique figé, qui n'a
 * rien à traduire, et se colle à la date à côté.
 */

/** La locale de l'écran, relue quand la langue du téléphone change. */
@Composable
@ReadOnlyComposable
internal fun localeAffichage(): Locale = LocalConfiguration.current.locales[0]

/**
 * Le formateur que la langue [locale] donne au squelette [squelette]
 * (`"EEEEdMMMM"`, `"EEEdMMM"`, `"dMMM"`).
 *
 * Repli sur la date complète de java.time si le motif d'Android ne se lit pas :
 * les lettres sont les mêmes des deux côtés pour ces squelettes, mais une
 * langue exotique n'a pas à faire tomber l'écran.
 */
internal fun formateurLocal(squelette: String, locale: Locale): DateTimeFormatter =
    runCatching {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, squelette), locale)
    }.getOrElse { DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale) }

/** [formateurLocal] pour un écran, recréé seulement quand la langue change. */
@Composable
internal fun formateurDate(squelette: String): DateTimeFormatter {
    val locale = localeAffichage()
    return remember(squelette, locale) { formateurLocal(squelette, locale) }
}

/** Première lettre en capitale, selon la langue du formateur : « Mardi 15 septembre ». */
internal fun capitaliser(texte: String, locale: Locale): String =
    texte.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

/** L'heure de la grille et des fiches : numérique, la même dans toutes les langues. */
internal val HEURE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
