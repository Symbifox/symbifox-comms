package com.bluefoxconsultant.sms.ui.instance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluefoxconsultant.sms.R
import com.bluefoxconsultant.sms.data.CompteSymbifox
import com.bluefoxconsultant.sms.socle.EcranDeconnecte
import com.bluefoxconsultant.sms.socle.TuileSymbifox
import com.bluefoxconsultant.sms.ui.asString

/**
 * Le premier écran : à quelle instance cet appareil parle.
 *
 * ⚠️ Le gabarit vient du socle (`EcranDeconnecte`) : le logo Symbifox officiel,
 * le mot « Symbifox », le nom de l'application. Avant la connexion, elle ne
 * sait pas chez qui elle va — elle porte donc le produit, et rien d'autre.
 * Jusqu'à la 2.44.0, elle écrivait son nom en 30 sp dans la COULEUR DE MARQUE,
 * ce que les quatre applications sœurs interdisent en toutes lettres : on ne
 * connaît pas le contraste de la couleur d'un locataire.
 *
 * 🔴 Le compte Symbifox d'abord, la saisie en repli. Relu à chaque composition
 * plutôt que mémorisé : installer Compte pendant que cet écran est ouvert doit
 * suffire à le voir apparaître (défaut payé dans Pastilles, où `remember`
 * figeait la détection).
 */
@Composable
fun InstanceScreen(vm: InstanceViewModel = viewModel()) {
    val contexte = LocalContext.current
    var url by rememberSaveable { mutableStateOf("") }
    var saisieManuelle by rememberSaveable { mutableStateOf(false) }
    val comptes = CompteSymbifox.comptes(contexte)

    EcranDeconnecte(
        application = stringResource(R.string.app_name),
        promesse = stringResource(R.string.instance_prompt),
    ) {
        if (comptes.isNotEmpty() && !saisieManuelle) {
            Text(
                stringResource(R.string.socle_compte_titre),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            comptes.forEach { compte ->
                TuileSymbifox(
                    titre = compte.nom,
                    sousTitre = compte.instance.removePrefix("https://"),
                    onClick = { vm.submit(compte.instance) },
                )
                Spacer(Modifier.height(8.dp))
            }
            // ⚠️ La saisie reste atteignable : on peut avoir un compte pour une
            // instance et vouloir apparier ce téléphone à une autre.
            TextButton(onClick = { saisieManuelle = true }) {
                Text(stringResource(R.string.socle_compte_autre))
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                if (comptes.isNotEmpty()) {
                    TextButton(onClick = { saisieManuelle = false }) {
                        Text(stringResource(R.string.socle_compte_revenir))
                    }
                    Spacer(Modifier.height(4.dp))
                }
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.instance_server)) },
                    placeholder = { Text(stringResource(R.string.instance_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { vm.submit(url) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(R.string.instance_continue))
                }
            }
        }

        vm.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it.asString(),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
