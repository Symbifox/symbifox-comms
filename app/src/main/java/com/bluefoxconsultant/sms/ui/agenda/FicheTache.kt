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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bluefoxconsultant.sms.data.AgendaTask
import com.bluefoxconsultant.sms.data.AgendaTaskOptions
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FR_FT = Locale.forLanguageTag("fr-CA")

/**
 * La fiche d'une tâche, modifiable sur place.
 *
 * ⚠️ Chaque geste part SEUL vers le serveur, et l'écran réaffiche ce que le
 * serveur rend. Grouper les changements dans un « Enregistrer » aurait obligé
 * à tenir un brouillon local qui diverge dès qu'une autre session touche la
 * même tâche, ce qui arrive tous les jours sur cette base.
 *
 * Le titre fait exception : le saisir caractère par caractère enverrait une
 * requête par frappe, donc il a son propre bouton.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FicheTache(
    task: AgendaTask,
    zone: ZoneId,
    options: AgendaTaskOptions,
    busy: Boolean,
    onWrite: (String) -> Unit,
    onComplete: (Boolean) -> Unit,
    onOuvrirOdoo: () -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var titre by remember(task.id) { mutableStateOf(task.name) }
    LaunchedEffect(task.name) { titre = task.name }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = titre,
                onValueChange = { titre = it },
                label = { Text("Titre") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (titre.trim() != task.name && titre.isNotBlank()) {
                TextButton(
                    enabled = !busy,
                    onClick = { onWrite("""{"name":${jsonTexte(titre.trim())}}""") },
                ) { Text("Enregistrer le titre") }
            }

            Text(
                listOf(task.project, task.stage).filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(enabled = !busy, onClick = { onComplete(!task.done) }) {
                    Text(if (task.done) "Rouvrir" else "Marquer faite")
                }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            HorizontalDivider()
            Text("Échéance", style = MaterialTheme.typography.labelLarge)
            Text(
                task.deadlineAt(zone)?.let {
                    DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm", FR_FT).format(it)
                        .replaceFirstChar { c -> c.uppercase() }
                } ?: "Aucune",
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "Aujourd'hui" to 0L,
                    "Demain" to 1L,
                    "Dans 3 j" to 3L,
                    "Dans 1 sem." to 7L,
                ).forEach { (libelle, jours) ->
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            // 17:00 heure locale : une échéance à minuit se lit
                            // comme la veille au soir, et `bf_time_of_day`
                            // réécrirait l'heure si on n'en fixait aucune.
                            val quand = LocalDate.now(zone).plusDays(jours)
                                .atTime(LocalTime.of(17, 0)).atZone(zone).toInstant()
                            onWrite("""{"date_deadline":${jsonTexte(horodatage(quand))}}""")
                        },
                    ) { Text(libelle) }
                }
                if (task.deadline != null) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { onWrite("""{"date_deadline":false}""") },
                    ) { Text("Retirer") }
                }
            }

            HorizontalDivider()
            Text("Priorité", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.priorities.forEach { p ->
                    FilterChip(
                        selected = task.priority == p.value,
                        enabled = !busy,
                        onClick = { onWrite("""{"priority":${jsonTexte(p.value)}}""") },
                        label = { Text(p.label) },
                    )
                }
            }

            if (options.states.isNotEmpty()) {
                HorizontalDivider()
                Text("État", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.states.forEach { e ->
                        FilterChip(
                            selected = task.state == e.value,
                            enabled = !busy,
                            onClick = { onWrite("""{"state":${jsonTexte(e.value)}}""") },
                            label = { Text(e.label) },
                        )
                    }
                }
            }

            if (options.stages.isNotEmpty()) {
                HorizontalDivider()
                Text("Étape", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    options.stages.forEach { e ->
                        FilterChip(
                            selected = task.stageId == e.id,
                            enabled = !busy,
                            onClick = { onWrite("""{"stage_id":${e.id}}""") },
                            label = { Text(e.name) },
                        )
                    }
                }
            }

            if (options.tags.isNotEmpty()) {
                HorizontalDivider()
                Text("Étiquettes", style = MaterialTheme.typography.labelLarge)
                val posees = task.tags.map { it.id }.toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Les étiquettes déjà posées d'abord : sur 185, chercher la
                    // sienne dans l'ordre alphabétique serait le vrai travail.
                    (options.tags.filter { it.id in posees } +
                        options.tags.filter { it.id !in posees }.take(30)
                        ).forEach { tag ->
                        val choisie = tag.id in posees
                        FilterChip(
                            selected = choisie,
                            enabled = !busy,
                            onClick = {
                                val suite = if (choisie) posees - tag.id else posees + tag.id
                                onWrite(
                                    """{"tag_ids":${suite.joinToString(",", "[", "]")}}""",
                                )
                            },
                            label = { Text(tag.name) },
                        )
                    }
                }
            }

            HorizontalDivider()
            TextButton(onClick = onOuvrirOdoo) { Text("Ouvrir dans Odoo") }
        }
    }
}

internal fun horodatage(instant: java.time.Instant): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("UTC")).format(instant)

/** Échappement minimal : un titre de tâche porte guillemets et apostrophes. */
internal fun jsonTexte(valeur: String): String = buildString {
    append('"')
    valeur.forEach { c ->
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
    append('"')
}
