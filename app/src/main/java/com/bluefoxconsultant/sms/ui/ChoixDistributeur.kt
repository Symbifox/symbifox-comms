package com.bluefoxconsultant.sms.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R

/**
 * Choisir l'app qui livre les notifications, parmi celles installées.
 *
 * ⚠️ Un dialogue à nous et non l'écran « Ouvrir avec » du système : celui-ci
 * ne peut porter ni titre ni explication, et une liste d'applications surgie
 * juste après la connexion, sans un mot, se lit comme une erreur. On montre le
 * NOM de l'app, pas son paquet — personne ne reconnaît `io.heckel.ntfy`.
 *
 * Fermer sans choisir ne choisit rien : la question revient à la prochaine
 * connexion, et Réglages la repose à la demande.
 */
@Composable
fun DialogueDistributeur(
    candidats: List<String>,
    courant: String?,
    onChoisir: (String) -> Unit,
    onFermer: () -> Unit,
) {
    val context = LocalContext.current
    val libelles = remember(candidats) {
        candidats.associateWith { libelleDistributeur(context, it) }
    }
    var choisi by remember(candidats, courant) {
        mutableStateOf(courant?.takeIf { it in candidats })
    }
    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text(stringResource(R.string.distributor_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.distributor_dialog_text),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                candidats.forEach { paquet ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { choisi = paquet }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choisi == paquet, onClick = { choisi = paquet })
                        Text(libelles[paquet] ?: paquet, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { choisi?.let(onChoisir) },
                enabled = choisi != null,
            ) { Text(stringResource(R.string.distributor_dialog_use)) }
        },
        dismissButton = {
            TextButton(onClick = onFermer) { Text(stringResource(R.string.distributor_dialog_later)) }
        },
    )
}

/** Le nom de l'app telle que le lanceur l'affiche ; le paquet si on ne le trouve pas. */
fun libelleDistributeur(context: Context, paquet: String): String = runCatching {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getApplicationInfo(paquet, PackageManager.ApplicationInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(paquet, 0)
    }
    info.loadLabel(pm).toString()
}.getOrDefault(paquet)
