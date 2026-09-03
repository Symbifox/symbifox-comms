package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bluefoxconsultant.sms.data.AgendaEvent
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FR_L = Locale.forLanguageTag("fr-CA")

/**
 * L'agenda à la file, pour qui préfère lire que situer.
 *
 * ⚠️ Les jours VIDES ne sont pas affichés. Une liste qui rend « mercredi :
 * rien » sur quinze lignes redevient une grille, en moins lisible. Le compte
 * d'échéances reste, lui, sur les jours qui en portent : c'est ce qui empêche
 * de lire « journée libre » un jour à vingt-six échéances.
 */
@Composable
fun VueListe(
    days: List<LocalDate>,
    events: List<AgendaEvent>,
    taskCounts: Map<LocalDate, Int>,
    zone: ZoneId,
    onOpen: (AgendaEvent) -> Unit,
    onOpenTasks: () -> Unit,
) {
    val today = LocalDate.now(zone)
    val parJour = days.associateWith { jour ->
        events.filter { it.dayAt(zone) == jour }
            .sortedWith(compareBy({ !it.allday }, { it.start }))
    }
    val garnis = days.filter { !parJour[it].isNullOrEmpty() || (taskCounts[it] ?: 0) > 0 }

    if (garnis.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Rien de prévu sur cette période.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        garnis.forEach { jour ->
            item(key = "j$jour") {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            DateTimeFormatter.ofPattern("EEEE d MMMM", FR_L).format(jour)
                                .replaceFirstChar { it.uppercase() },
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (jour == today) FontWeight.Bold
                            else FontWeight.Normal,
                            color = if (jour == today) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val n = taskCounts[jour] ?: 0
                        if (n > 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable(onClick = onOpenTasks),
                            ) {
                                Text(
                                    "$n échéance" + if (n > 1) "s" else "",
                                    Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }
                }
            }
            items(parJour[jour].orEmpty(), key = { "e${it.key}" }) { event ->
                LigneEvenement(event, zone, onOpen)
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

@Composable
private fun LigneEvenement(event: AgendaEvent, zone: ZoneId, onOpen: (AgendaEvent) -> Unit) {
    val teinte = hexOuNull(event.color)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(event) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 5.dp, end = 10.dp)
                .size(9.dp)
                .clip(CircleShape)
                .let { m ->
                    if (teinte != null) m.then(Modifier.background(teinte)) else m
                },
        )
        Column(Modifier.weight(1f)) {
            Text(
                event.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val details = buildList {
                add(if (event.allday) "Journée entière" else heureListe(event, zone))
                if (event.location.isNotBlank()) add(event.location)
                if (event.calendar.isNotBlank()) add(event.calendar)
            }
            Text(
                details.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val pastilles = buildList {
                if (event.hasAgenda) add("OdJ")
                if (event.hasMinutes) add("CR")
                if (event.skipAgenda) add("sans OdJ")
                if (event.snoozedUntil != null) add("reporté")
            }
            if (pastilles.isNotEmpty()) {
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pastilles.forEach { mot ->
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                mot,
                                Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
        if (event.attendees > 1) {
            Text(
                "${event.attendees}",
                Modifier.width(24.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun heureListe(event: AgendaEvent, zone: ZoneId): String {
    val f = DateTimeFormatter.ofPattern("HH:mm")
    val start = event.startAt(zone) ?: return ""
    val stop = event.stopAt(zone)
    return if (stop == null) f.format(start) else "${f.format(start)} – ${f.format(stop)}"
}
