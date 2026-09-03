package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bluefoxconsultant.sms.data.AgendaTaskOptions
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Poser une tâche depuis le téléphone.
 *
 * ⚠️ Le projet est obligatoire, et c'est voulu : une tâche sans projet
 * n'apparaît dans aucun suivi, donc la créer ainsi reviendrait à l'écrire sur
 * un papier qu'on jette. Le serveur refuse d'ailleurs, plutôt que d'en choisir
 * un à notre place.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposerTache(
    zone: ZoneId,
    options: AgendaTaskOptions,
    busy: Boolean,
    onCreer: (String, Int, Instant?, String, List<Int>) -> Unit,
    onFermer: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var titre by remember { mutableStateOf("") }
    var projet by remember { mutableStateOf<Int?>(null) }
    var jours by remember { mutableStateOf<Long?>(null) }
    var priorite by remember { mutableStateOf("0") }
    var etiquettes by remember { mutableStateOf(setOf<Int>()) }
    var filtre by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onFermer, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Nouvelle tâche", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = titre,
                onValueChange = { titre = it },
                label = { Text("Titre") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Projet", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = filtre,
                onValueChange = { filtre = it },
                label = { Text("Filtrer les projets") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            // Il y a des centaines de projets : les afficher tous ferait une
            // liste où l'on ne trouve rien. On en montre douze, filtrées.
            val visibles = options.projects
                .filter { filtre.isBlank() || it.name.contains(filtre, ignoreCase = true) }
                .take(12)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                visibles.forEach { p ->
                    FilterChip(
                        selected = projet == p.id,
                        onClick = { projet = p.id },
                        label = { Text(p.name) },
                    )
                }
            }
            if (visibles.isEmpty()) {
                Text(
                    "Aucun projet ne correspond.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Échéance", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf<Pair<String, Long?>>(
                    "Aucune" to null,
                    "Aujourd'hui" to 0L,
                    "Demain" to 1L,
                    "Dans 3 j" to 3L,
                    "Dans 1 sem." to 7L,
                ).forEach { (libelle, valeur) ->
                    FilterChip(
                        selected = jours == valeur,
                        onClick = { jours = valeur },
                        label = { Text(libelle) },
                    )
                }
            }

            if (options.priorities.isNotEmpty()) {
                Text("Priorité", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.priorities.forEach { p ->
                        FilterChip(
                            selected = priorite == p.value,
                            onClick = { priorite = p.value },
                            label = { Text(p.label) },
                        )
                    }
                }
            }

            if (options.tags.isNotEmpty()) {
                Text("Étiquettes", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.tags.take(20).forEach { tag ->
                        FilterChip(
                            selected = tag.id in etiquettes,
                            onClick = {
                                etiquettes = if (tag.id in etiquettes) {
                                    etiquettes - tag.id
                                } else {
                                    etiquettes + tag.id
                                }
                            },
                            label = { Text(tag.name) },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = titre.isNotBlank() && projet != null && !busy,
                    onClick = {
                        val quand = jours?.let {
                            LocalDate.now(zone).plusDays(it)
                                .atTime(LocalTime.of(17, 0)).atZone(zone).toInstant()
                        }
                        onCreer(
                            titre.trim(),
                            projet ?: 0,
                            quand,
                            priorite,
                            etiquettes.toList(),
                        )
                    },
                ) { Text("Créer") }
                TextButton(onClick = onFermer) { Text("Annuler") }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}
