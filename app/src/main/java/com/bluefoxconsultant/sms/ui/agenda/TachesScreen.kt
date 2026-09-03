package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bluefoxconsultant.sms.data.AgendaTask
import com.bluefoxconsultant.sms.data.OdooLinks
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FR_T = Locale.forLanguageTag("fr-CA")

/**
 * Les échéances, dans leur propre onglet.
 *
 * Elles ne sont PAS posées sur la grille de l'agenda : 131 échéances contre 9
 * rencontres sur quatorze jours, avec des pointes à 25 dans une journée. Les
 * mêler aux rencontres reviendrait à cacher les rencontres.
 */
@Composable
fun TachesScreen() {
    val vm: TachesViewModel = viewModel()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = vm.horizonDays == 7L,
                    onClick = { vm.setHorizon(7L) },
                    label = { Text("7 j") },
                )
                FilterChip(
                    selected = vm.horizonDays == 14L,
                    onClick = { vm.setHorizon(14L) },
                    label = { Text("14 j") },
                )
                FilterChip(
                    selected = vm.horizonDays == 30L,
                    onClick = { vm.setHorizon(30L) },
                    label = { Text("30 j") },
                )
                Spacer(Modifier.weight(1f))
                if (vm.loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }

        vm.error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = { vm.clearError(); vm.load() }) { Text("Réessayer") }
                }
            }
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (vm.overdue.isNotEmpty()) {
                item { Entete("En retard (${vm.overdue.size})", alerte = true) }
                items(vm.overdue, key = { "r" + it.id }) { task ->
                    LigneTache(task, vm.zone) {
                        OdooLinks.openRecord(context, "project.task", task.id)
                    }
                }
            }

            item { Entete("À venir (${vm.window.size})") }
            if (vm.window.isEmpty()) {
                item {
                    Text(
                        "Rien d'échu dans cette fenêtre.",
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(vm.window, key = { "w" + it.id }) { task ->
                LigneTache(task, vm.zone) {
                    OdooLinks.openRecord(context, "project.task", task.id)
                }
            }

            item {
                TextButton(
                    onClick = { vm.toggleUndated() },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(
                        if (vm.showUndated) "Masquer les ${vm.undatedCount} sans échéance"
                        else "Voir les ${vm.undatedCount} sans échéance",
                    )
                }
            }
            if (vm.showUndated) {
                items(vm.undated, key = { "u" + it.id }) { task ->
                    LigneTache(task, vm.zone) {
                        OdooLinks.openRecord(context, "project.task", task.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun Entete(titre: String, alerte: Boolean = false) {
    Surface(
        color = if (alerte) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            titre,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (alerte) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LigneTache(task: AgendaTask, zone: ZoneId, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (task.priority != "0") {
                Text("★ ", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                task.name,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                echeance(task, zone),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val sous = listOf(task.project, task.partner).filter { it.isNotBlank() }
        if (sous.isNotEmpty()) {
            Text(
                sous.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * « aujourd'hui », « demain », sinon la date.
 *
 * Une date absolue pour aujourd'hui obligerait à la comparer de tête à la date
 * du jour, ce qui est exactement le travail que l'écran est censé éviter.
 */
private fun echeance(task: AgendaTask, zone: ZoneId): String {
    val at = task.deadlineAt(zone) ?: return "—"
    val today = LocalDate.now(zone)
    val day = at.toLocalDate()
    val heure = DateTimeFormatter.ofPattern("HH:mm").format(at)
    return when (day) {
        today -> "auj. $heure"
        today.plusDays(1) -> "dem. $heure"
        else -> DateTimeFormatter.ofPattern("d MMM", FR_T).format(day)
    }
}
