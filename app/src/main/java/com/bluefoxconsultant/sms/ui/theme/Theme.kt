package com.bluefoxconsultant.sms.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.bluefoxconsultant.sms.data.Brand
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.ThemeMode
import com.bluefoxconsultant.sms.socle.Symbifox

/**
 * Le thème de Comms : la marque de l'instance, posée sur le socle commun de la
 * famille Symbifox (BF #25765).
 *
 * ⚠️ Les couleurs, la typo et le calcul du texte sur l'accent vivent dans
 * `socle/Socle.kt`, identique dans les cinq applications.
 *
 * 🔴 **Ce que le socle répare ici** : jusqu'à la 2.44.0, `onPrimary` était figé
 * à `Color.White`. Comms était la seule des cinq à ne RIEN calculer, et une
 * instance à marque pâle rendait ses boutons illisibles. Le socle dérive ce
 * texte de la luminance de l'accent, comme les quatre autres.
 *
 * 🔴 Les neutres aussi : le fond clair était blanc pur et l'anthracite
 * `#2D3031`, deux valeurs qu'aucune autre application de la famille ne
 * partageait. Elles viennent maintenant du socle.
 */

/**
 * The instance's accent colour, readable from any composable.
 *
 * Deliberately a CompositionLocal rather than a constant: the same build runs
 * against instances belonging to different companies, and each should see its
 * own colours. Reading `MaterialTheme.colorScheme.primary` works too — this
 * exists so call sites read as intent ("the brand accent") rather than as a
 * Material slot that something else might legitimately want to change.
 */
val LocalBrandAccent = compositionLocalOf { Color(Brand.SYMBIFOX_PRIMARY) }

val BrandAccent: Color
    @Composable get() = LocalBrandAccent.current

@Composable
fun BfSmsTheme(content: @Composable () -> Unit) {
    val mode by Graph.tokenStore.themeModeFlow.collectAsState()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val brand by Graph.brandStore.brandFlow.collectAsState()
    val accent = Color(brand.primary)

    MaterialTheme(
        colorScheme = Symbifox.schema(accent, Color(brand.dark), dark),
        typography = Symbifox.TYPOGRAPHIE,
    ) {
        CompositionLocalProvider(LocalBrandAccent provides accent, content = content)
    }
}
