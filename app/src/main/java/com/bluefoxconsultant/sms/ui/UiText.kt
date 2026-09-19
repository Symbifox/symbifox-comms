package com.bluefoxconsultant.sms.ui

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Un texte d'interface qui n'est pas encore écrit dans une langue.
 *
 * L'app suit la langue du téléphone : `values/strings.xml` en anglais, servi à
 * toute langue non couverte, et `values-fr` en français (Q-M8, audit du
 * 2026-09-08). Un texte ne devient donc une phrase qu'au moment de s'afficher,
 * là où il y a un `Context`. Entre les deux, les modèles de vue, les dépôts et
 * les files d'attente portent ceci : un id de ressource et ses arguments.
 *
 * Pourquoi pas `getString` dans le modèle de vue : il faudrait y retenir un
 * `Context`, qui fuit, et qui fige la langue du moment où le modèle est né :
 * changer la langue du téléphone laisserait l'écran dans l'ancienne. Et un
 * essai JVM ne sait pas fabriquer de `Context` : ici il compare des ids, ou
 * résout contre une [SourceDeTextes] factice.
 *
 * Là où un `Context` est déjà en main au moment d'afficher (Notifier,
 * services, récepteurs, activités, `@Composable`), on lit la ressource
 * directement : `getString`, `stringResource`. [UiText] ne sert qu'à ce qui
 * VOYAGE avant d'être affiché.
 *
 * ⚠️ Ce qui vient du serveur (un nom, un objet, un message d'erreur d'Odoo) ne
 * se traduit pas : il voyage en [Raw] et s'affiche tel quel.
 */
sealed interface UiText {

    /** Une chaîne de `strings.xml`. Les [args] peuvent être eux-mêmes des [UiText]. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /**
     * Un `<plurals>` : [count] choisit la forme. Sans [args], [count] est aussi
     * l'unique argument, ce qui couvre « %d courriels ».
     */
    data class Plural(
        @PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    /** Déjà dans la langue de la personne : venu du serveur, ou tapé par elle. */
    data class Raw(val text: String) : UiText
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

fun uiPlural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
    UiText.Plural(id, count, args.toList())

/**
 * D'où viennent les phrases. Les ressources Android en production ; une table
 * en mémoire dans les essais JVM, où `Resources` n'existe pas.
 */
interface SourceDeTextes {
    fun texte(@StringRes id: Int, args: Array<Any>): String
    fun pluriel(@PluralsRes id: Int, count: Int, args: Array<Any>): String
}

fun Resources.commeSource(): SourceDeTextes = object : SourceDeTextes {
    override fun texte(id: Int, args: Array<Any>): String =
        if (args.isEmpty()) getString(id) else getString(id, *args)

    override fun pluriel(id: Int, count: Int, args: Array<Any>): String =
        getQuantityString(id, count, *args)
}

fun UiText.resolve(source: SourceDeTextes): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> source.texte(id, resoudreArgs(args, source))
    is UiText.Plural -> source.pluriel(id, count, resoudreArgs(args.ifEmpty { listOf(count) }, source))
}

fun UiText.resolve(context: Context): String = resolve(context.resources.commeSource())

/** Un argument [UiText] se résout dans la même langue que la phrase qui le porte. */
private fun resoudreArgs(args: List<Any>, source: SourceDeTextes): Array<Any> =
    args.map { if (it is UiText) it.resolve(source) else it }.toTypedArray()

/**
 * Pour un écran. Lit `LocalConfiguration` comme `stringResource` le fait, pour
 * se recomposer quand la langue change sous l'écran ouvert.
 */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current)
}
