package com.bluefoxconsultant.sms.ui.agenda

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bluefoxconsultant.sms.data.AgendaEvent
import com.bluefoxconsultant.sms.data.OdooLinks
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FR_FICHE = Locale.forLanguageTag("fr-CA")

/**
 * Ce qu'une rencontre porte, et les trois gestes qu'on peut poser dessus.
 *
 * ⚠️ « Confirmer » écrit l'état de présence et pose une note interne côté
 * serveur. Il ne passe PAS par `do_accept` d'Odoo, qui publie sous le
 * sous-type « Invitation » : sur un statutaire suivi par un client, confirmer
 * depuis un téléphone lui aurait écrit.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FicheRencontre(
    event: AgendaEvent,
    zone: ZoneId,
    snoozeMinutes: List<Int>,
    rsvpOffert: Boolean,
    busy: Boolean,
    onSnooze: (Int) -> Unit,
    onDismiss: () -> Unit,
    onRsvp: (String) -> Unit,
    onClose: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(event.name, style = MaterialTheme.typography.titleLarge)
            Text(quand(event, zone), style = MaterialTheme.typography.bodyMedium)

            if (event.location.isNotBlank()) {
                Text(event.location, style = MaterialTheme.typography.bodySmall)
            }
            if (event.videocall.isNotBlank()) {
                TextButton(
                    onClick = { ouvrir(context, event.videocall) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text("Rejoindre la visioconférence") }
            }

            event.snoozedUntil?.let {
                Text(
                    "Rappel reporté à " + heureCourte(it, zone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (event.dismissedAt != null && event.snoozedUntil == null) {
                Text(
                    "Rappel marqué vu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Text("Rappel", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                snoozeMinutes.forEach { minutes ->
                    AssistChip(
                        enabled = !busy,
                        onClick = { onSnooze(minutes) },
                        label = { Text(libelleReport(minutes)) },
                    )
                }
                AssistChip(
                    enabled = !busy,
                    onClick = onDismiss,
                    label = { Text("Vu") },
                )
            }

            if (rsvpOffert && event.attendees > 1) {
                HorizontalDivider()
                Text("Présence", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = { onRsvp("accepted") }) {
                        Text("Je confirme")
                    }
                    OutlinedButton(enabled = !busy, onClick = { onRsvp("tentative") }) {
                        Text("Peut-être")
                    }
                    OutlinedButton(enabled = !busy, onClick = { onRsvp("declined") }) {
                        Text("Je décline")
                    }
                }
                if (event.myState.isNotBlank()) {
                    Text(
                        "Actuellement : " + etat(event.myState),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            event.agenda?.let { odj ->
                HorizontalDivider()
                Text("Ordre du jour", style = MaterialTheme.typography.labelLarge)
                Text(odj.name, style = MaterialTheme.typography.bodyMedium)
                odj.topics.forEach { sujet ->
                    Text("• $sujet", style = MaterialTheme.typography.bodySmall)
                }
            }

            event.minutes?.let { cr ->
                HorizontalDivider()
                Text("Compte rendu", style = MaterialTheme.typography.labelLarge)
                if (cr.summary.isNotBlank()) {
                    Text(cr.summary, style = MaterialTheme.typography.bodySmall)
                }
                if (cr.decisions.isNotEmpty()) {
                    Text(
                        "Décisions",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    cr.decisions.forEach { d ->
                        Text("• $d", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (event.attendeeList.isNotEmpty()) {
                HorizontalDivider()
                Text("Participants", style = MaterialTheme.typography.labelLarge)
                event.attendeeList.forEach { p ->
                    Text(
                        p.name + " — " + etat(p.state),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            HorizontalDivider()
            TextButton(
                onClick = { OdooLinks.openRecord(context, "calendar", event.id) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("Ouvrir dans Odoo") }
        }
    }
}

private fun ouvrir(context: android.content.Context, url: String) {
    runCatching {
        androidx.browser.customtabs.CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, android.net.Uri.parse(url))
    }
}

private fun quand(event: AgendaEvent, zone: ZoneId): String {
    if (event.allday) {
        val jour = DateTimeFormatter.ofPattern("EEEE d MMMM", FR_FICHE)
        val date = event.startInstant?.atZone(ZoneId.of("UTC"))?.toLocalDate()
        return "Journée entière, " + (date?.let { jour.format(it) } ?: "")
    }
    val jour = DateTimeFormatter.ofPattern("EEEE d MMMM", FR_FICHE)
    val heure = DateTimeFormatter.ofPattern("HH:mm")
    val start = event.startAt(zone) ?: return ""
    val stop = event.stopAt(zone)
    val base = jour.format(start).replaceFirstChar { it.uppercase() } +
        ", " + heure.format(start)
    return if (stop == null) base else "$base – ${heure.format(stop)}"
}

private fun heureCourte(raw: String, zone: ZoneId): String {
    val instant = com.bluefoxconsultant.sms.data.parseInstant(raw) ?: return raw
    return DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(zone))
}

private fun libelleReport(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes == 60 -> "1 h"
    else -> "${minutes / 60} h"
}

private fun etat(state: String): String = when (state) {
    "accepted" -> "confirmé"
    "declined" -> "décliné"
    "tentative" -> "peut-être"
    "needsAction" -> "sans réponse"
    else -> state.ifBlank { "sans réponse" }
}
