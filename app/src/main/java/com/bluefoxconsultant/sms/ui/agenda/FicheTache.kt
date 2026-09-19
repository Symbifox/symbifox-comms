package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.res.stringResource
import com.bluefoxconsultant.sms.R

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
    onCancel: () -> Unit,
    onOuvrirOdoo: () -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var choixDate by remember { mutableStateOf(false) }
    var dateChoisie by remember { mutableStateOf<LocalDate?>(null) }
    var titre by remember(task.id) { mutableStateOf(task.name) }
    LaunchedEffect(task.name) { titre = task.name }
    val jourLong = formateurDate("EEEEdMMMM")

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = titre,
                onValueChange = { titre = it },
                label = { Text(stringResource(R.string.common_title)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (titre.trim() != task.name && titre.isNotBlank()) {
                TextButton(
                    enabled = !busy,
                    onClick = { onWrite("""{"name":${jsonTexte(titre.trim())}}""") },
                ) { Text(stringResource(R.string.task_save_title)) }
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
                    Text(
                        stringResource(if (task.done) R.string.task_reopen else R.string.task_mark_done),
                    )
                }
                // Annuler, à côté de Faite (#25734) : une tâche qui n'a plus
                // lieu d'être n'a pas été faite, et la clore « faite » fausse
                // ce qu'on relit ensuite. L'état existait dans les pastilles de
                // statut plus bas, sans qu'on l'y cherche. Pas de confirmation :
                // l'écran offre de rétablir.
                if (!task.done) {
                    OutlinedButton(enabled = !busy, onClick = onCancel) {
                        Text(stringResource(R.string.task_cancel))
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            HorizontalDivider()
            Text(stringResource(R.string.task_deadline), style = MaterialTheme.typography.labelLarge)
            Text(
                // La date dans la langue du téléphone, l'heure en chiffres : voir `DatesLocales`.
                task.deadlineAt(zone)?.let {
                    capitaliser(jourLong.format(it), jourLong.locale) + ", " + HEURE.format(it)
                } ?: stringResource(R.string.task_deadline_none),
                style = MaterialTheme.typography.bodyMedium,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    R.string.agenda_today to 0L,
                    R.string.task_deadline_tomorrow to 1L,
                    R.string.task_deadline_in_3_days to 3L,
                    R.string.task_deadline_in_1_week to 7L,
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
                    ) { Text(stringResource(libelle)) }
                }
                // Le sélecteur d'Android pour tout ce que les quatre raccourcis
                // ne couvrent pas : une date précise, une heure qui n'est pas
                // 17:00. La date d'abord, l'heure ensuite, puis l'écriture.
                OutlinedButton(enabled = !busy, onClick = { choixDate = true }) {
                    Text(stringResource(R.string.task_deadline_pick))
                }
                if (task.deadline != null) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = { onWrite("""{"date_deadline":false}""") },
                    ) { Text(stringResource(R.string.common_remove)) }
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.task_priority), style = MaterialTheme.typography.labelLarge)
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
                Text(stringResource(R.string.task_status), style = MaterialTheme.typography.labelLarge)
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
                Text(stringResource(R.string.task_stage), style = MaterialTheme.typography.labelLarge)
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
                Text(stringResource(R.string.task_tags), style = MaterialTheme.typography.labelLarge)
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
            TextButton(onClick = onOuvrirOdoo) { Text(stringResource(R.string.common_open_in_odoo)) }
        }
    }

    if (choixDate) {
        DialogueDate(
            initiale = task.deadlineAt(zone)?.toLocalDate() ?: LocalDate.now(zone),
            onChoisir = { dateChoisie = it },
            onFermer = { choixDate = false },
        )
    }
    dateChoisie?.let { jour ->
        DialogueHeure(
            initiale = task.deadlineAt(zone)?.toLocalTime() ?: LocalTime.of(17, 0),
            onChoisir = { heure ->
                val quand = jour.atTime(heure).atZone(zone).toInstant()
                onWrite("""{"date_deadline":${jsonTexte(horodatage(quand))}}""")
            },
            onFermer = { dateChoisie = null },
        )
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
