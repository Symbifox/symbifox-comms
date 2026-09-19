package com.bluefoxconsultant.sms.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bluefoxconsultant.sms.data.Graph
import com.bluefoxconsultant.sms.data.ThemeMode
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R

/**
 * Le thème, en un geste, dans l'en-tête de CHAQUE module.
 *
 * Il existe déjà dans Réglages, en trois choix explicites ; ce bouton n'est pas
 * une seconde vérité, il écrit la MÊME préférence. Ce qu'il ajoute est la
 * portée : personne ne va dans Réglages pour changer la luminosité d'un écran
 * qu'il regarde en ce moment. Il vivait dans le seul en-tête de l'agenda ;
 * Olivier a demandé qu'on le trouve partout, et un bouton qu'on cherche
 * d'un onglet à l'autre ne vaut pas mieux qu'un réglage enfoui.
 *
 * Le cycle est sombre → clair → système, dans cet ordre, parce que le défaut
 * est sombre et qu'un cycle qui commence ailleurs oblige à deux appuis pour
 * revenir d'où l'on vient.
 *
 * @param tint la couleur du pictogramme, pour les barres peintes à l'accent
 *   dont le blanc ne se transmet pas à un `IconButton` posé hors de `actions`.
 */
@Composable
fun BoutonTheme(tint: Color = Color.Unspecified) {
    val mode by Graph.tokenStore.themeModeFlow.collectAsStateWithLifecycle()
    IconButton(onClick = { Graph.tokenStore.saveThemeMode(mode.suivant()) }) {
        Icon(
            imageVector = when (mode) {
                ThemeMode.DARK -> Icons.Filled.DarkMode
                ThemeMode.LIGHT -> Icons.Filled.LightMode
                ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
            },
            contentDescription = stringResource(R.string.theme_button_description, stringResource(mode.libelleRes)),
            tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint,
        )
    }
}
