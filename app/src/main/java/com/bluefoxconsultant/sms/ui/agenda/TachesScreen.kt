package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Box(Modifier.fillMaxSize()) {
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
                item { Entete("En retard", vm.overdue.size, alerte = true) }
                items(vm.overdue, key = { "r" + it.id }) { task ->
                    LigneTache(task, vm.zone, vm.busy,
                        onComplete = { vm.complete(task, it) },
                        onOpen = { vm.open(task) })
                }
            }

            item { Entete("À venir", vm.window.size) }
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
                LigneTache(task, vm.zone, vm.busy,
                    onComplete = { vm.complete(task, it) },
                    onOpen = { vm.open(task) })
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
                    LigneTache(task, vm.zone, vm.busy,
                        onComplete = { vm.complete(task, it) },
                        onOpen = { vm.open(task) })
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

        FloatingActionButton(
            onClick = { vm.openComposer() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Nouvelle tâche")
        }
    }

    vm.selected?.let { task ->
        FicheTache(
            task = task,
            zone = vm.zone,
            options = vm.options,
            busy = vm.busy,
            onWrite = { vm.write(task, it) },
            onComplete = { vm.complete(task, it) },
            onOuvrirOdoo = { OdooLinks.openRecord(context, "project.task", task.id) },
            onClose = { vm.close() },
        )
    }

    if (vm.composing) {
        ComposerTache(
            zone = vm.zone,
            options = vm.options,
            busy = vm.busy,
            onCreer = { nom, projet, echeance, priorite, etiquettes ->
                vm.create(nom, projet, echeance, priorite, etiquettes)
            },
            onFermer = { vm.closeComposer() },
        )
    }
}

/**
 * L'en-tête d'un seau.
 *
 * ⚠️ Le compte est une pastille, pas une parenthèse dans le titre. « En retard
 * (12) » se lit comme un titre ; une pastille rouge portant 12 se lit comme
 * une quantité, ce qui est l'information qu'on cherche en ouvrant cet écran.
 * Et la barre pleine d'un bout à l'autre est remplacée par un fond discret :
 * peindre toute la largeur en rouge pour trois tâches en retard rendait le
 * rouge illisible partout ailleurs.
 */
@Composable
private fun Entete(titre: String, compte: Int, alerte: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            titre.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = if (alerte) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (compte > 0) {
            Surface(
                color = if (alerte) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    "$compte",
                    Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (alerte) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Une tâche, avec de quoi la lire ET la fermer.
 *
 * ⚠️ La case est à GAUCHE et le reste de la ligne ouvre la fiche : compléter
 * est le geste le plus fréquent, et le faire passer par un écran de détail
 * était le principal reproche fait à la v1. Le liseré porte la couleur de la
 * tâche quand elle en a une, sinon celle de sa première étiquette.
 */
@Composable
private fun LigneTache(
    task: AgendaTask,
    zone: ZoneId,
    busy: Boolean,
    onComplete: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    val liseré = hexOuNull(task.color)
        ?: task.tags.firstNotNullOfOrNull { hexOuNull(it.color) }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(liseré ?: Color.Transparent),
        )
        Checkbox(
            checked = task.done,
            enabled = !busy,
            onCheckedChange = { onComplete(it) },
        )
        Column(
            Modifier
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(end = 16.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (task.priority != "0") {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Prioritaire",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp).padding(end = 4.dp),
                    )
                }
                Text(
                    task.name,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                val enRetard = !task.done && task.deadlineAt(zone)
                    ?.isBefore(java.time.ZonedDateTime.now(zone)) == true
                Text(
                    echeance(task, zone),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (enRetard) FontWeight.Bold else FontWeight.Normal,
                    color = if (enRetard) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val sous = listOf(task.project, task.stage, task.partner)
                .filter { it.isNotBlank() }
            if (sous.isNotEmpty()) {
                Text(
                    sous.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (task.tags.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    task.tags.take(4).forEach { tag ->
                        val teinte = hexOuNull(tag.color)
                            ?: MaterialTheme.colorScheme.surfaceVariant
                        Surface(
                            color = teinte.copy(alpha = 0.22f),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                tag.name,
                                Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
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
